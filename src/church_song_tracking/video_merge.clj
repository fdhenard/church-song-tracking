(ns church-song-tracking.video-merge
  (:require [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.video-jobs :as jobs]
            [church-song-tracking.video-metadata :as metadata]
            [church-song-tracking.vimeo-import :as vimeo]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(defn sources [ds selection-id]
  (sql/query ds ["select * from service_video_source where import_selection_id = ? order by source_order" selection-id] vimeo/opts))
(defn service-sources [ds service-id]
  (sql/query ds ["select * from service_video_source where church_service_id = ? order by source_order" service-id] vimeo/opts))
(defn note [entries]
  (str "Merged recording. Original Vimeo videos in playback order:\n"
       (str/join "\n" (for [s entries] (str (:source_order s) ". " (:vimeo_video_title s) " — " (:vimeo_url s))))))
(defn values [value] (cond (nil? value) [] (string? value) [value] :else (vec value)))
(defn ordered-ids [params]
  (let [included (set (values (get params "include")))
        ids (mapv #(or (parse-long %) (throw (ex-info "Invalid recording selection." {})))
                  (filter included (values (get params "part"))))]
    (when-not (and (<= 2 (count ids) 8) (= (count ids) (count (distinct ids))))
      (throw (ex-info "Choose two to eight different recordings and set their playback order." {})))
    ids))

(defn create! [params]
  (let [ids (ordered-ids params) parent-id (first ids) ds (db/get-datasource)]
    (jdbc/with-transaction [tx ds]
      (sql/query tx ["select id from import_approval_lock where id = 1 for update"])
      (let [locked (into {} (for [id (sort ids)]
                             [id (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))]))
            rows (mapv locked ids)
            files (mapv jobs/downloaded-file ids)
            titles (mapv metadata/original-title rows)
            title (str/trim (or (get params "recording-title") (first titles) ""))]
        (when (str/blank? title)
          (throw (ex-info "Enter a recording title or choose one of the selected titles." {})))
        (when (some (vimeo/imported-video-urls (vimeo/imported-recordings tx)) (map :vimeo_url rows))
          (throw (ex-info "One of these recordings already belongs to an approved service." {})))
        (when (some nil? rows) (throw (ex-info "A selected recording no longer exists. Reload the merge screen." {})))
        (when (some #(or (:imported_service_id %) (:merged_into_selection_id %) (:is_merged %) (jobs/active-statuses (:job_status %))) rows)
          (throw (ex-info "Use unimported recordings that are downloaded and not processing or already part of a merge." {})))
        (when (some nil? files) (throw (ex-info "Download every selected part before creating the merged recording." {})))
        (when (and (some :draft_data rows) (not= "yes" (get params "discard-review")))
          (throw (ex-info "Confirm that existing OCR review work will be replaced by a new OCR pass on the combined video." {})))
        (let [entries (mapv (fn [position row title]
                              {:import_selection_id parent-id :source_selection_id (:id row) :source_order (inc position)
                               :vimeo_url (:vimeo_url row) :vimeo_video_title title}) (range) rows titles)
              directory (io/file (config/get-video-cache-dir) (str parent-id))
              manifest {:title title :allow_conversion (= "yes" (get params "allow-conversion"))
                        :sources (mapv (fn [entry file]
                                         {:selection_id (:source_selection_id entry) :order (:source_order entry)
                                          :url (:vimeo_url entry) :title (:vimeo_video_title entry) :path (.getCanonicalPath file)}) entries files)}]
          ;; Keep originals and source records intact until approval. No library records are written here.
          (doseq [entry entries] (sql/insert! tx :service_video_source entry))
          (doseq [row (rest rows)]
            (sql/update! tx :vimeo_import_selection {:merged_into_selection_id parent-id :job_status "merged-part"
                                                    :job_token nil :job_message "Included in a merged recording"} {:id (:id row)}))
          (sql/update! tx :vimeo_import_selection
            {:is_merged true :vimeo_video_title title :draft_data nil :extracted_data nil :revision (inc (:revision (first rows)))
             :trim_start_seconds 0 :trim_end_seconds nil :range_needs_ocr false :job_refresh_review false
             :job_status "failed" :job_token nil :job_message "Merge prepared · ready to create combined video"} {:id parent-id})
          (spit (io/file directory "merge-plan.json") (json/write-str manifest)))))
    (jobs/start! parent-id true)
    parent-id))

(defn attach-service! [tx selection service-id]
  (when (:is_merged selection)
    (let [entries (sources tx (:id selection))]
      (when (or (< (count entries) 2) (some #(nil? (:merged_start_seconds %)) entries))
        (throw (ex-info "The merged recording has not finished validation. Finish merging before approval." {})))
      (sql/update! tx :church_service {:is_merged true :merged_recording_note (note entries)} {:id service-id})
      (sql/update! tx :service_video_source {:church_service_id service-id} {:import_selection_id (:id selection)})
      (doseq [entry entries :when (not= (:id selection) (:source_selection_id entry))]
        (sql/update! tx :vimeo_import_selection
          {:imported_service_id service-id :cache_cleanup_status "pending" :cache_cleanup_message "Merged service approved; source cache cleanup pending."}
          {:id (:source_selection_id entry)})))))

(defn retry-with-conversion! [id]
  (jdbc/with-transaction [tx (db/get-datasource)]
    (let [row (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))]
      (when-not (and (:is_merged row) (not (:imported_service_id row)) (= "failed" (:job_status row)) (nil? (:draft_data row)))
        (throw (ex-info "Only a failed, unapproved merge can be retried with conversion." {})))
      (let [file (io/file (config/get-video-cache-dir) (str id) "merge-plan.json")
            plan (json/read-str (slurp file) :key-fn keyword)]
        (spit file (json/write-str (assoc plan :allow_conversion true :force_conversion true))))))
  (jobs/start! id true))

(defn move-cache! [from to]
  (java.nio.file.Files/move (.toPath (io/file from)) (.toPath (io/file to))
                            (make-array java.nio.file.CopyOption 0)))

(defn undo! [id revision confirmed?]
  (when-not confirmed?
    (throw (ex-info "Confirm that review work on the combined recording will be discarded." {})))
  (let [ds (db/get-datasource)
        directory (io/file (config/get-video-cache-dir) (str id))
        suffix (str (java.util.UUID/randomUUID))
        staged (io/file (config/get-video-cache-dir) (str "undo-stage-" suffix))
        backup (io/file (config/get-video-cache-dir) (str "undone-merge-" id "-" suffix))
        swapped (atom false)]
    (try
      (jdbc/with-transaction [tx ds]
        (sql/query tx ["select id from import_approval_lock where id=1 for update"])
        (let [row (first (sql/query tx ["select * from vimeo_import_selection where id=? for update" id] vimeo/opts))
              entries (sources tx id)
              ids (mapv :source_selection_id entries)
              rows (mapv #(first (sql/query tx ["select * from vimeo_import_selection where id=? for update" %] vimeo/opts)) (sort ids))]
          (when-not (and row (:is_merged row) (not (:imported_service_id row))
                         (not (:merged_into_selection_id row)) (<= 2 (count entries))
                         (= id (:source_selection_id (first entries)))
                         (every? #(and % (not (:imported_service_id %))
                                       (not (jobs/active-statuses (:job_status %)))) rows)
                         (not-any? :church_service_id entries))
            (throw (ex-info "Only an unapproved merged recording can be undone. Wait for processing to finish or cancel it first." {})))
          (when-not (= revision (:revision row))
            (throw (ex-info "This recording changed. Reload Import services before undoing the merge." {})))
          (let [metadata-file (io/file directory "metadata.json")
                data (when (.isFile metadata-file) (json/read-str (slurp metadata-file) :key-fn keyword))
                original (or (first (filter #(and (.isFile %) (re-matches #"source-original\.(mp4|webm|mkv|mov)" (.getName %))) (.listFiles directory)))
                             (when-not (:merged data)
                               (first (filter #(and (.isFile %) (re-matches #"video\.(mp4|webm|mkv|mov)" (.getName %))) (.listFiles directory)))))
                entry (first entries)]
            (when-not original
              (throw (ex-info "The original first recording is missing. Nothing was changed." {})))
            (.mkdirs staged)
            (let [target (io/file staged (str "video." (last (str/split (.getName original) #"\."))))]
              (try
                (java.nio.file.Files/createLink (.toPath target) (.toPath original))
                (catch java.io.IOException _ (io/copy original target))))
            (spit (io/file staged "metadata.json")
                  (json/write-str {:title (:vimeo_video_title entry) :webpage_url (:vimeo_url entry)
                                   :duration (or (:duration_seconds entry)
                                                 (get-in data [:sources 0 :duration])
                                                 (when-not (:merged data) (:duration data)))}))
            (move-cache! directory backup)
            (try (move-cache! staged directory) (reset! swapped true)
                 (catch Exception e (move-cache! backup directory) (throw e)))
            (doseq [source rows]
              (let [parent? (= id (:id source))]
                (sql/update! tx :vimeo_import_selection
                  (cond-> {:merged_into_selection_id nil :job_token nil :job_refresh_review false
                           :revision (inc (:revision source))
                           :job_status (if (or parent? (jobs/downloaded-file (:id source))) "downloaded" "failed")
                           :job_message "Merge undone · original recording restored"}
                    parent? (assoc :is_merged false :vimeo_video_title (:vimeo_video_title entry) :draft_data nil :extracted_data nil
                                   :trim_start_seconds 0 :trim_end_seconds nil :range_needs_ocr false))
                  {:id (:id source)})))
            (sql/delete! tx :service_video_source {:import_selection_id id}))))
      id
      (catch Exception e
        (when @swapped
          (move-cache! directory staged)
          (move-cache! backup directory))
        (throw e)))))
