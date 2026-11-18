(ns church-song-tracking.video-jobs
  (:require [church-song-tracking.config :as config]
            [church-song-tracking.vimeo-session :as vimeo-session]
            [church-song-tracking.video-metadata :as metadata]
            [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:import [java.util UUID]
           [java.util.concurrent Executors ExecutorService ThreadFactory TimeUnit]))

(def active-statuses #{"queued" "downloading" "extracting" "recognizing" "merging"})
(defn- worker-factory [label]
  (reify ThreadFactory
    (newThread [_ task] (doto (Thread. task label) (.setDaemon true)))))
(defonce ^ExecutorService download-executor
  (Executors/newFixedThreadPool 3 (worker-factory "service-video-download")))
(defonce ^ExecutorService analysis-executor
  (Executors/newSingleThreadExecutor (worker-factory "service-video-analysis")))

(defn submit-stage! [download-only? task]
  (.submit ^ExecutorService (if download-only? download-executor analysis-executor) ^Runnable task))

(defonce merge-executor
  (Executors/newSingleThreadExecutor (worker-factory "service-video-merge")))
(defonce processes (atom {}))

(defn preflight!
  ([] (preflight! true))
  ([_key-free?]
   (let [{:keys [python] :as settings} (config/video-settings)]
     (when-not (.canExecute (io/file python))
       (throw (ex-info "Video tools are missing. Follow the video processing setup in README.md." {})))
     settings)))

(defn- update-job! [id token data]
  (sql/update! (db/get-datasource) :vimeo_import_selection data {:id id :job_token token}))

(defn complete! [id token revision result]
  (jdbc/with-transaction [tx (db/get-datasource)]
    (let [row (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))
          data (:draft result)
          data (if (and (:job_refresh_review row) (:draft_data row))
                 (assoc (merge data (edn/read-string (:draft_data row))) :analysis-notes (:analysis-notes data)) data)]
      (when-not (and row (= token (:job_token row)) (= revision (:revision row))
                     (active-statuses (:job_status row)) (or (nil? (:draft_data row)) (:job_refresh_review row)) (nil? (:imported_service_id row)))
        (throw (ex-info "The selection changed while processing; the results were retained on disk without overwriting the draft." {})))
      (when-not (and (map? data) (vector? (:songs data)))
        (throw (ex-info "The analyzer returned an invalid draft." {})))
      (sql/update! tx :vimeo_import_selection
        {:draft_data (pr-str data) :extracted_data (pr-str result) :revision (inc revision)
         :vimeo_video_title (or (metadata/present (:vimeo_video_title result)) (metadata/downloaded-title id) (metadata/original-title row))
         :range_needs_ocr false :job_refresh_review false
         :job_status "ready" :job_message (if (:local_only result)
                                            "Local OCR complete · review the slides and fill in service details. No API calls."
                                            "OCR and image recognition complete. Review and approve this service.")}
        {:id id}))))

(defn downloaded-file [id]
  (let [directory (io/file (config/get-video-cache-dir) (str id))]
    (when (and (.isFile (io/file directory "metadata.json"))
               (or (not (.isFile (io/file directory "merge-plan.json")))
                   (try (:merged (json/read-str (slurp (io/file directory "metadata.json")) :key-fn keyword)) (catch Exception _ false))))
      (first (filter #(and (.isFile %) (re-matches #"video\.(mp4|webm|mkv|mov)" (.getName %)))
                     (.listFiles directory))))))

(declare run-job!)

(defn complete-download! [id token revision url settings]
  (let [continue? (:analyze-after-download? settings)]
    (jdbc/with-transaction [tx (db/get-datasource)]
      (let [row (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))]
        (when-not (and (= token (:job_token row)) (= revision (:revision row))
                       (active-statuses (:job_status row)) (nil? (:draft_data row)) (nil? (:imported_service_id row)))
          (throw (ex-info "The selection changed while downloading; the video is retained without starting analysis." {})))
        (when (:is_merged row)
          (let [data (json/read-str (slurp (io/file (config/get-video-cache-dir) (str id) "metadata.json")) :key-fn keyword)]
            (when-not (:merged data) (throw (ex-info "Merged video metadata is missing." {})))
            (doseq [source (:sources data)]
              (sql/update! tx :service_video_source {:duration_seconds (:duration source) :merged_start_seconds (:merged_start source)}
                {:import_selection_id id :source_selection_id (:selection_id source)}))))
        (sql/update! tx :vimeo_import_selection
          {:vimeo_video_title (or (metadata/downloaded-title id) (metadata/original-title row))
           :job_status (if continue? "queued" "downloaded")
           :job_message (if (:is_merged row) "Merged recording ready · choose an OCR range, then continue import"
                          (if continue? "Video downloaded · waiting for OCR and image recognition"
                                      "Video downloaded · ready to continue import"))}
          {:id id})))
    (when continue?
      (submit-stage! false #(run-job! id token revision url
                                     (assoc settings :download-only? false :analyze-only? true :analyze-after-download? false :merge? false))))))

(defn- run-job! [id token revision url settings]
  (let [directory (io/file (config/get-video-cache-dir) (str id))
        own-process (atom nil)]
    (try
      (let [row (sql/get-by-id (db/get-datasource) :vimeo_import_selection id vimeo/opts)]
        (when-not (and (= token (:job_token row)) (active-statuses (:job_status row)))
          (throw (ex-info "Processing was cancelled." {}))))
      (.mkdirs directory)
      (let [builder (ProcessBuilder. ^java.util.List
                      (if (:merge? settings)
                        [(:python settings) "scripts/merge_videos.py" "--manifest" (.getAbsolutePath (io/file directory "merge-plan.json"))]
                      (cond-> [(:python settings) "scripts/analyze_service.py" "--url" url
                               "--directory" (.getAbsolutePath directory) "--model" (:model settings)
                               "--trim-start" (str (or (:trim-start settings) 0))]
                        (some? (:trim-end settings)) (conj "--trim-end" (str (:trim-end settings)))
                        (:download-only? settings) (conj "--download-only")
                        (vimeo-session/connected?) (conj "--vimeo-cookie-file" (.getAbsolutePath (vimeo-session/cookie-file)))
                        (:analyze-only? settings) (conj "--analyze-only")
                        (:ocr-only? settings) (conj "--ocr-only")
                        (false? (:local-ocr? settings)) (conj "--no-local-ocr"))))
            _ (.redirectError builder (io/file directory "worker.log"))
            process (.start builder)
            _ (reset! own-process process)
            _ (swap! processes assoc token process)
            _ (when-not (= token (:job_token (sql/get-by-id (db/get-datasource) :vimeo_import_selection id vimeo/opts)))
                (.destroyForcibly process))
            ;; Bound work even if a downloader or API call stops making progress.
            timeout (future (when-not (.waitFor process 6 TimeUnit/HOURS) (.destroyForcibly process)))
            events (with-open [reader (io/reader (.getInputStream process))]
                     (reduce (fn [last-event line]
                               (when-not (= token (:job_token (sql/get-by-id (db/get-datasource) :vimeo_import_selection id vimeo/opts)))
                                 (.destroyForcibly process))
                               (if-let [event (try (json/read-str line :key-fn keyword) (catch Exception _ nil))]
                                 (do (when (active-statuses (:status event))
                                       (update-job! id token {:job_status (:status event) :job_message (:message event)}))
                                     event)
                                 last-event)) nil (line-seq reader)))
            exit (.waitFor process)]
        (future-cancel timeout)
        (cond
          (and (zero? exit) (:download-only? settings) (= "downloaded" (:status events)))
          (complete-download! id token revision url settings)
          (and (zero? exit) (= "complete" (:status events)))
          (complete! id token revision (json/read-str (slurp (io/file directory "result.json")) :key-fn keyword))
          :else
          (throw (ex-info (if (= "failed" (:status events)) (:message events) "Video processing stopped. Retry to reuse downloaded files and completed recognition batches.") {}))))
      (catch Exception e
        (update-job! id token {:job_status "failed" :job_message (if (instance? clojure.lang.ExceptionInfo e)
                                                                                (.getMessage e)
                                                                                "Video processing failed. Check the local job files and retry.")}))
      (finally (swap! processes (fn [running]
                                 (if (= (get running token) @own-process)
                                   (dissoc running token) running)))))))

(defn start!
  ([id] (start! id false))
  ([id download-only?] (start! id download-only? true))
  ([id download-only? _legacy-local-only?]
   (let [local-only? true
        settings (preflight! true)
        selected-row (sql/get-by-id (db/get-datasource) :vimeo_import_selection id vimeo/opts)
        cached? (and (boolean (downloaded-file id))
                     (or (not (:is_merged selected-row))
                         (not (seq (sql/query (db/get-datasource) ["select id from service_video_source where import_selection_id = ? and merged_start_seconds is null" id] vimeo/opts)))))
        download-stage? (or download-only? (not cached?))
        settings (cond-> (assoc settings :ocr-only? local-only? :download-only? download-stage?
                                 :analyze-only? (not download-stage?)
                                 :analyze-after-download? (and (not download-only?) download-stage?))
                   local-only? (assoc :local-ocr? true))
        token (str (UUID/randomUUID))
        row (jdbc/with-transaction [tx (db/get-datasource)]
              (let [row (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))]
                (when-not row (throw (ex-info "Selection not found." {:kind :unavailable})))
                (when (:merged_into_selection_id row)
                  (throw (ex-info "This recording is part of a merged service. Continue the merged recording instead." {:kind :unavailable})))
                (when (or (:imported_service_id row) (and (:draft_data row) (not (:range_needs_ocr row))))
                  (throw (ex-info "This service already has a draft or has been imported. Review it before starting another analysis." {:kind :unavailable})))
                (when (active-statuses (:job_status row))
                  (throw (ex-info "This service is already queued or processing." {:kind :busy})))
                (sql/update! tx :vimeo_import_selection {:job_token token :job_refresh_review (boolean (:range_needs_ocr row)) :job_status "queued" :job_message (if (and (:is_merged row) (not cached?)) "Waiting for a merge slot"
                                                                            (if download-stage? "Waiting for a download slot"
                                                                                             (if local-only? "Waiting for local OCR · no API calls" "Video downloaded · waiting for OCR and image recognition")))} {:id id})
                row))]
    (let [merge? (and (:is_merged row) (not cached?))
          task #(run-job! id token (:revision row) (:vimeo_url row)
                         (assoc settings :merge? merge? :analyze-after-download? (and (not merge?) (:analyze-after-download? settings)) :trim-start (:trim_start_seconds row) :trim-end (:trim_end_seconds row)))]
      (if merge?
        (.submit ^ExecutorService merge-executor ^Runnable task)
        (submit-stage! download-stage? task)))
    token)))

(defn pending-downloads [selections]
  (filter #(and (= "selected" (:job_status %)) (nil? (:draft_data %))
                (nil? (:merged_into_selection_id %)) (nil? (:imported_service_id %)) (nil? (downloaded-file (:id %)))) selections))

(defn download-pending! []
  (preflight! true)
  (let [imported (vimeo/imported-video-urls
                   (vimeo/imported-recordings (db/get-datasource)))
        pending (pending-downloads (remove #(contains? imported (:vimeo_url %)) (vimeo/queue)))]
    (when (empty? pending) (throw (ex-info "There are no pending videos to download. Use Download video to retry a failed download." {})))
    (doseq [row pending]
      (try (start! (:id row) true)
           (catch clojure.lang.ExceptionInfo e
             (when-not (#{:busy :unavailable} (:kind (ex-data e))) (throw e)))))
    (count pending)))

(defn download-selected! [urls]
  (let [urls (set urls)
        imported (vimeo/imported-video-urls
                   (vimeo/imported-recordings (db/get-datasource)))
        pending (filter #(and (contains? urls (:vimeo_url %))
                              (not (contains? imported (:vimeo_url %)))
                              (#{"selected" "failed"} (:job_status %))
                              (nil? (:merged_into_selection_id %)) (nil? (:draft_data %)) (nil? (:imported_service_id %))
                              (nil? (downloaded-file (:id %)))) (vimeo/queue))]
    (doseq [row pending]
      (try (start! (:id row) true)
           (catch clojure.lang.ExceptionInfo e
             (when-not (#{:busy :unavailable} (:kind (ex-data e))) (throw e)))))
    (count pending)))

(defn- destroy-tree! [^Process process]
  (with-open [children (.descendants process)]
    (.forEach children (reify java.util.function.Consumer (accept [_ child] (.destroyForcibly ^java.lang.ProcessHandle child)))))
  (.destroyForcibly process))

(defn cancel! [id]
  (jdbc/with-transaction [tx (db/get-datasource)]
    (let [row (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))]
      (when (active-statuses (:job_status row))
        (sql/update! tx :vimeo_import_selection {:job_status "failed" :job_message "Processing cancelled. Retry to reuse completed work." :job_token nil} {:id id})
        (when-let [p (get @processes (:job_token row))] (destroy-tree! p))))))

(defn recover! []
  (jdbc/execute! (db/get-datasource)
    ["update vimeo_import_selection set job_status = 'failed', job_token = null, job_message = 'Processing interrupted by server restart. Retry to resume.' where job_status in ('queued', 'downloading', 'extracting', 'recognizing', 'merging')"]))

(defn stop-processes! []
  (doseq [process (vals @processes)]
    (destroy-tree! process)))
(defonce shutdown-hook
  (let [hook (Thread. ^Runnable stop-processes! "stop-video-imports")]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))
