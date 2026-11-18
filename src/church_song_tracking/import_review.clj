(ns church-song-tracking.import-review
  "Drafts stay outside the library until a saved revision is explicitly approved."
  (:require [church-song-tracking.musical-key :as musical-key]
            [church-song-tracking.db :as db]
            [church-song-tracking.song-tags :as song-tags]
            [church-song-tracking.record-merge :as record-merge]
            [church-song-tracking.song-merge :as song-merge]
            [church-song-tracking.song-variation :as variation]
            [church-song-tracking.video-merge :as video-merge]
            [church-song-tracking.cache-cleanup :as cleanup]
            [church-song-tracking.slide-archive :as archive]
            [church-song-tracking.config :as config]
            [church-song-tracking.video-metadata :as metadata]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [church-song-tracking.vimeo-import :as vimeo]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def service-fields
  [[:date "Service date" :date] [:title "Service title" :text]
   [:leader "Song leader" :text] [:preacher "Preacher" :text] [:holiday "Holiday (optional)" :text]
   [:sermon-title "Sermon title" :text] [:sermon-scripture "Sermon scripture" :text] [:sermon-series "Sermon series" :text]
   [:sermon-time "Sermon title slide time" :text] [:sermon-topic "Sermon topic" :text]])
(def song-fields
  [[:title "Song title"] [:author "Author(s) on slide"]
   [:start "Song start time (optional fallback)"] [:slide "Title / author slide time"]
   [:ocr "Slide OCR / evidence notes"]])
(defn- timestamp-seconds [value]
  (when (and (string? value) (re-matches #"(?:\d+:)?[0-5]?\d:[0-5]\d" value))
    (reduce (fn [seconds part] (+ (* 60 seconds) (parse-long part))) 0 (str/split value #":"))))

(defn order-songs
  "Derive contiguous persisted orders from slide time, falling back to start time.
  Ties retain row order; unfinished rows without valid times stay at the end."
  [songs]
  (->> songs
       (sort-by #(or (timestamp-seconds (:slide %))
                     (timestamp-seconds (:start %)) Long/MAX_VALUE))
       (map-indexed #(assoc %2 :order (inc %1)))
       vec))

(defn draft [selection] (if-let [s (:draft_data selection)] (edn/read-string (str s)) {:songs []}))
(defn selection [id]
  (sql/get-by-id (db/get-datasource) :vimeo_import_selection id vimeo/opts))
(defn- locked [tx id revision]
  (let [row (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))]
    (when-not row (throw (ex-info "This selection no longer exists." {})))
    (when (#{"queued" "downloading" "extracting" "recognizing" "merging"} (:job_status row))
      (throw (ex-info "Video processing is still running. Wait for the review draft." {})))
    (when (:merged_into_selection_id row) (throw (ex-info "This recording is a source part. Review its combined recording instead." {})))
    (when (:imported_service_id row) (throw (ex-info "This service has already been imported." {})))
    (when-not (= revision (:revision row))
      (throw (ex-info "This draft changed since you opened it. Reload and review the latest version." {})))
    row))

(defn save-draft!
  "Store review edits only. Never writes to library tables."
  [id revision data]
  (jdbc/with-transaction [tx (db/get-datasource)]
    (let [row (locked tx id revision)
          data (update (merge (draft row) data) :songs order-songs)]
      (sql/update! tx :vimeo_import_selection {:draft_data (pr-str data) :revision (inc revision)} {:id id}))))

(defn stage!
  "Future analyzer entry point. Retains the original extraction for provenance."
  [id revision data]
  (jdbc/with-transaction [tx (db/get-datasource)]
    (locked tx id revision)
    (sql/update! tx :vimeo_import_selection
      {:draft_data (pr-str data) :extracted_data (pr-str data) :revision (inc revision)} {:id id})))

(defn blank-value? [value] (or (nil? value) (and (string? value) (str/blank? value))))
(defn blank-song? [song]
  (and (every? #(blank-value? (get song (first %))) song-fields)
       (empty? (:tag-ids song)) (blank-value? (:new-tags song))))
(defn- evidence-index [value]
  (when-not (str/blank? value)
    (let [n (parse-long value)]
      (when-not (and n (<= 0 n 1000000)) (throw (ex-info "Invalid source slide." {}))) n)))

(defn insert-song-suggestion [existing candidate]
  ;; Insert newly found slides in video order without reordering reviewed rows.
  (let [position (when (number? (:evidence-index candidate))
                   (first (keep-indexed
                            (fn [i song]
                              (when (and (number? (:evidence-index song))
                                         (> (:evidence-index song) (:evidence-index candidate))) i)) existing)))]
    (if (some? position)
      (mapv #(assoc %1 :order (inc %2))
            (concat (subvec existing 0 position) [candidate] (subvec existing position)) (range))
      (conj existing candidate))))

(defn suggest-details! [id revision]
  (let [row (selection id)
        _ (when-not row (throw (ex-info "Selection not found." {})))
        _ (when-not (:extracted_data row) (throw (ex-info "Run local OCR before finding details." {})))
        extraction (edn/read-string (:extracted_data row))
        process (.start (ProcessBuilder. ^java.util.List [(:python (config/video-settings)) "scripts/ocr_suggestions.py"]))]
    (with-open [writer (io/writer (.getOutputStream process))] (json/write extraction writer))
    (let [output (slurp (.getInputStream process))
          exit (.waitFor process)
          proposed (when (zero? exit) (json/read-str output :key-fn keyword))]
      (when-not (and proposed (not (:error proposed))) (throw (ex-info "Could not read suggestions from the saved OCR." {})))
      (jdbc/with-transaction [tx (db/get-datasource)]
        (let [row (locked tx id revision)
              current (draft row)
              service (reduce-kv (fn [data k v] (if (blank-value? (get data k)) (assoc data k v) data)) current (:sermon proposed))
              songs (reduce (fn [existing candidate]
                              (if-let [position (first (keep-indexed
                                     (fn [i song] (when (or (= (:evidence-index song) (:evidence-index candidate))
                                                            (and (not (blank-value? (:slide song))) (= (:slide song) (:slide candidate)))
                                                            (and (= (str/lower-case (or (:title song) "")) (str/lower-case (:title candidate)))
                                                                 (or (blank-value? (:slide song)) (= (:slide song) (:slide candidate))))) i)) existing))]
                                (update existing position #(merge-with (fn [old new] (if (blank-value? old) new old)) % candidate))
                                (insert-song-suggestion existing candidate)))
                            (vec (remove blank-song? (:songs current))) (:songs proposed))
              data (assoc service :songs songs)]
          (sql/update! tx :vimeo_import_selection {:draft_data (pr-str data) :revision (inc revision)} {:id id}))))))

(defn nominate-frame! [id revision seconds kind]
  (when-not (and (integer? seconds) (<= 0 seconds 3000000))
    (throw (ex-info "Choose a valid time in the source video." {})))
  (jdbc/with-transaction [tx (db/get-datasource)] (locked tx id revision))
  (let [index (long (Math/round (/ seconds 3.0)))
        folder (io/file (config/get-video-cache-dir) (str id))
        process (.start (doto (ProcessBuilder. ^java.util.List
                                [(:python (config/video-settings)) "scripts/nominate_sermon.py"
                                 "--directory" (str folder) "--index" (str index) "--kind" kind])
                           (.redirectError (io/file folder "sermon-frame.log"))))]
    (when-not (.waitFor process 240 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Local OCR timed out on this frame. Your draft has not changed; retry to reuse cached work." {})))
    (when-not (zero? (.exitValue process))
      (throw (ex-info "Could not read this frame with local OCR. Check the downloaded video and local OCR tools." {})))
    (let [result (json/read-str (slurp (.getInputStream process)) :key-fn keyword)]
      (jdbc/with-transaction [tx (db/get-datasource)]
        (let [row (locked tx id revision)
              extraction (if (:extracted_data row) (edn/read-string (:extracted_data row)) {})
              evidence (vec (sort-by :index (conj (vec (remove #(= index (:index %)) (:evidence extraction))) (:frame result))))]
          (sql/update! tx :vimeo_import_selection
            {:extracted_data (pr-str (assoc extraction :evidence evidence)) :revision (inc revision)} {:id id})
          (assoc result :revision (inc revision)))))))

(defn nominate-sermon! [id revision seconds]
  (nominate-frame! id revision seconds "sermon_title"))

(defn add-song-frame! [id revision seconds data]
  (when (>= (count (:songs data)) 100)
    (throw (ex-info "A draft supports up to 100 songs." {})))
  (let [result (nominate-frame! id revision seconds "song_title")
        index (get-in result [:frame :index])
        proposed (first (:details result))
        song (merge {:title "" :author "" :start "" :song-choice "auto"}
                    proposed
                    {:order (inc (reduce max 0 (map #(or (:order %) 0) (:songs data))))
                     :slide (:slide proposed)
                     :evidence-index index :ocr (get-in result [:frame :ocr])
                     :suggestion-notes "Manually selected video frame. Choose an existing song or create a new song; verify the title, author, and slide time before approval."})
        seconds (* 3 index)
        slide (if (>= seconds 3600)
                (format "%d:%02d:%02d" (quot seconds 3600) (mod (quot seconds 60) 60) (mod seconds 60))
                (format "%d:%02d" (quot seconds 60) (mod seconds 60)))
        song (assoc song :slide (or (:slide song) slide))]
    (save-draft! id (:revision result) (update data :songs conj song))
    (first (keep-indexed (fn [i entry] (when (= index (:evidence-index entry)) i))
                         (order-songs (conj (:songs data) song))))))

(defn parse-form
  ([params] (parse-form params nil))
  ([params removed-index]
  (let [n (parse-long (get params "song-count" "0"))]
    (when-not (and n (<= 0 n 100)) (throw (ex-info "Invalid song count." {})))
    (when (and (some? removed-index) (not (<= 0 removed-index (dec n))))
      (throw (ex-info "Invalid song row. Reload this page." {})))
    (assoc (into {} (for [[key] service-fields] [key (str/trim (get params (name key) ""))]))
      :sermon-evidence-index (evidence-index (get params "sermon-evidence-index" ""))
      :songs (->> (range n)
                  (remove #(= removed-index %))
                  (mapv (fn [i]
                            (assoc (into {} (for [[key] song-fields]
                                             [key (str/trim (get params (str "song-" i "-" (name key)) ""))]))
                              :song-choice (get params (str "song-" i "-choice") "auto")
                              :variation-id (get params (str "song-" i "-variation-id") "auto")
                              :variation-name (str/trim (get params (str "song-" i "-variation-name") ""))
                              :variation-key (musical-key/parse-key (get params (str "song-" i "-variation-key")))
                              :variation-example-link (str/trim (get params (str "song-" i "-variation-example-link") ""))
                              :variation-guitar (variation/parse-guitar (get params (str "song-" i "-variation-guitar")))
                              :tag-ids (vec (song-tags/values (get params (str "song-" i "-tag-ids"))))
                              :new-tags (str/trim (get params (str "song-" i "-new-tags") ""))
                              :evidence-index (evidence-index (get params (str "song-" i "-evidence-index") "")))))
                  order-songs)))))

(defn- timestamp! [value label required?]
  (when (or required? (seq value))
    (when-not (and (string? value) (re-matches #"(?:\d+:)?[0-5]?\d:[0-5]\d" value))
      (throw (ex-info (str label " must use m:ss or h:mm:ss.") {})))))
(defn validated [data]
  (doseq [[key label] [[:date "Date"] [:title "Service title"] [:leader "Song leader"]
                      [:preacher "Preacher"] [:sermon-title "Sermon title"]]]
    (when (str/blank? (get data key)) (throw (ex-info (str label " is required before approval.") {}))))
  (let [date (try (java.time.LocalDate/parse (:date data))
                  (catch Exception _ (throw (ex-info "Enter a valid service date." {}))))
        songs (order-songs (remove blank-song? (:songs data)))]
    (when (empty? songs) (throw (ex-info "Add at least one song before approval." {})))
    (timestamp! (:sermon-time data) "Sermon slide time" false)
    (doseq [song songs]
      ;; An explicit library selection supplies identity; OCR fields remain evidence.
      ;; The selected ID is checked inside the approval transaction before commit.
      (when-not (re-matches #"[1-9]\d*" (str (:song-choice song)))
        (doseq [key [:title :author]]
          (when (str/blank? (get song key)) (throw (ex-info "Every new or automatically matched song needs a title and author before approval. Choose an existing song or enter the missing details." {})))))
      (timestamp! (:start song) "Song start time" false)
      (timestamp! (:slide song) "Song slide time" false)
      (when (and (str/blank? (:slide song)) (str/blank? (:start song)))
        (throw (ex-info "Each song needs a title / author slide time or a song start time as a fallback." {}))))
    (assoc data :date date :songs songs)))
(defn- ensure! [tx table data]
  (if (= table :song_variation)
    (variation/ensure! tx data true)
  (or (when (= table :song) (song-merge/find-song-id tx (:name data) (:artist data)))
      (:id (first (sql/find-by-keys tx table data vimeo/opts)))
      (when-let [kind (case table :person "people" :sermon_series "series" nil)]
        (when-let [id (record-merge/find-name-id tx kind (:name data))]
          (when (sql/get-by-id tx table id vimeo/opts) id)))
      (:id (sql/insert! tx table data vimeo/opts)))))
(defn selected-variation [ds song-id value]
  (when-not (or (nil? value) (str/blank? (str value)) (= "auto" (str value)))
    (let [id (parse-long (str value))
          row (when (and id (pos? id)) (sql/get-by-id ds :song_variation id vimeo/opts))]
      (when-not (and row (= song-id (:song_id row)))
        (throw (ex-info "The selected variation no longer exists or belongs to a different song. Choose a variation for the selected song and save the draft again." {})))
      row)))

(defn review-variation [ds song-id song]
  (let [name (str/trim (or (:variation-name song) ""))
        link (str/trim (or (:variation-example-link song) ""))]
    (when (and (str/blank? name) (or (seq link) (some? (:variation-key song)) (some? (:variation-guitar song))))
      (throw (ex-info "Enter a name for the new variation." {})))
    (when (and (seq link) (not (re-matches #"https?://[^\s]+" link)))
      (throw (ex-info "Variation example link must be an http or https URL." {})))
    (if (str/blank? name)
      (selected-variation ds song-id (:variation-id song))
      (do
        (when (> (count name) 240)
          (throw (ex-info "Variation name must be at most 240 characters." {})))
        (or (when song-id
              (first (filter #(= (variation/name-key name) (variation/name-key (:variation_name %)))
                             (sql/find-by-keys ds :song_variation {:song_id song-id} vimeo/opts))))
            {:musical_key (musical-key/parse-key (:variation-key song)) :song_id song-id :variation_name name :example_link (when (seq link) link)
             :prominent_electric_guitar (:variation-guitar song)})))))

(defn- optional [s] (when-not (str/blank? s) s))

(defn approve-in-transaction!
  "Approve within the caller transaction. Reconciliation is reserved for the explicit service merge flow."
  [tx id revision reconciliation?]
  (do
    ;; Serialize approvals across selections to prevent duplicate service dates.
    (sql/query tx ["select id from import_approval_lock where id = 1 for update"])
    (let [row (locked tx id revision)
          _ (when (:range_needs_ocr row) (throw (ex-info "The OCR range changed. Run local OCR for the new range before approving." {})))
          _ (when (:merged_into_selection_id row) (throw (ex-info "Review the combined recording instead of this source part." {})))
          data (validated (merge (draft row) (when (map? reconciliation?) (:service-overrides reconciliation?))))
          ;; Refuse to merge into an existing service without a separate reconciliation review.
          _ (when (and (not reconciliation?) (or (seq (sql/find-by-keys tx :church_service {:date (:date data)} vimeo/opts))
                      (contains? (vimeo/imported-video-urls
                                   (vimeo/imported-recordings tx)) (:vimeo_url row))))
              (throw (ex-info "A service already exists for this date or Vimeo video. Resolve it in the library before importing." {})))
          preacher (ensure! tx :person {:name (:preacher data)})
          leader (ensure! tx :person {:name (:leader data)})
          series (when-let [s (optional (:sermon-series data))] (ensure! tx :sermon_series {:name s}))
          holiday (when-let [s (optional (:holiday data))] (ensure! tx :holiday {:name s}))
          service-id (:id (sql/insert! tx :church_service
                            {:date (:date data) :title (:title data) :link_vimeo (:vimeo_url row)
                             :vimeo_video_title (metadata/original-title row)
                             :preacher_id preacher :song_leader_id leader :sermon_series_id series :holiday_id holiday
                             :sermon_title (:sermon-title data) :sermon_scripture (optional (:sermon-scripture data)) :sermon_topic (optional (:sermon-topic data))
                             :sermon_title_slide_time_vimeo (optional (:sermon-time data))} vimeo/opts))]
      (when-let [path (archive/preserve! {:selection-id id :service-id service-id :kind "sermon"
                                         :index (:sermon-evidence-index data) :time (:sermon-time data)
                                         :required? (some? (:sermon-evidence-index data))})]
        (sql/update! tx :church_service {:sermon_title_image_path path} {:id service-id}))
      (doseq [[index song] (map-indexed vector (:songs data))]
        (let [choice (or (:song-choice song) "auto")
              song-id (cond
                        (= choice "auto") (ensure! tx :song {:name (:title song) :artist (:author song)})
                        (= choice "new") (:id (sql/insert! tx :song {:name (:title song) :artist (:author song)} vimeo/opts))
                        :else (let [existing-id (parse-long choice)
                                    existing (when (and existing-id (pos? existing-id))
                                               (sql/get-by-id tx :song existing-id vimeo/opts))]
                                (when-not existing
                                  (throw (ex-info "The selected song no longer exists. Choose a song again before approval." {})))
                                existing-id))
              _ (when (or (seq (:tag-ids song)) (not (str/blank? (:new-tags song))))
                  (song-tags/add-assignments! tx song-id {"tag-ids" (:tag-ids song) "new-tags" (or (:new-tags song) "")}))
              chosen-variation (review-variation tx song-id song)
              variation (or (:id chosen-variation)
                            (ensure! tx :song_variation (or chosen-variation
                              {:song_id song-id :variation_name "Default"
                               :example_link nil :prominent_electric_guitar nil})))]
          (sql/insert! tx :song_performance
            {:song_variation_id variation :church_service_id service-id :order_in_service (:order song)
             :time_in_service_vimeo (or (optional (:slide song)) (optional (:start song)))
             :song_start_time_vimeo (optional (:start song))
             :title_image_path (archive/preserve! {:selection-id id :service-id service-id :kind (str "song-" (:order song))
                                                  :index (:evidence-index song) :time (:slide song)
                                                  :required? (some? (:evidence-index song))})
             :source_data (pr-str {:vimeo-url (:vimeo_url row) :reviewed-revision revision
                                   :song song :service (assoc (dissoc data :songs) :date (str (:date data)))
                                   :extracted-data (some-> (:extracted_data row) str edn/read-string)})})))
      (video-merge/attach-service! tx row service-id)
      (sql/update! tx :vimeo_import_selection {:imported_service_id service-id :revision (inc revision)
                                             :cache_cleanup_status "pending" :cache_cleanup_message "Approval committed; cache cleanup pending."} {:id id})
      service-id)))

(defn approve! [id revision]
  (let [service-id (jdbc/with-transaction [tx (db/get-datasource)]
                     (approve-in-transaction! tx id revision false))]
    (doseq [selection-id (distinct (cons id (map :source_selection_id (video-merge/sources (db/get-datasource) id))))]
      (try (cleanup/cleanup-approved! selection-id) (catch Exception _ nil)))
    service-id))
