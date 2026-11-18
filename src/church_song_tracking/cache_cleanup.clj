(ns church-song-tracking.cache-cleanup
  "Delete only an approved import's disposable cache after verifying its permanent image references."
  (:require [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.slide-archive :as archive]
            [clojure.java.io :as io]
            [next.jdbc.sql :as sql])
  (:import [java.nio.file Files LinkOption]
           [java.util Comparator]
           [java.time Instant]
           [java.sql Timestamp]))

(defn verify-image! [path required?]
  (when (or required? (seq path))
    (let [file (archive/safe-image-file path)
          expected (when file (second (re-find #"([0-9a-f]{64})\.jpg$" (.getName file))))]
      (when-not (and file expected (= expected (archive/digest file)))
        (throw (ex-info "A permanent title-slide image is missing or failed checksum verification. Cached files were retained." {}))))))
(defn cache-directory [id]
  (when-not (and (integer? id) (pos? id)) (throw (ex-info "Invalid import cache ID." {})))
  (let [root (.getCanonicalFile (io/file (config/get-video-cache-dir)))
        file (io/file root (str id))
        canonical (.getCanonicalFile file)
        permanent (.toPath (.getCanonicalFile (io/file (config/get-slide-archive-dir))))]
    (when (or (Files/isSymbolicLink (.toPath file))
              (and (.exists file) (not (.isDirectory file)))
              (not= root (.getParentFile canonical)) (not= (str id) (.getName canonical))
              (.startsWith permanent (.toPath canonical)))
      (throw (ex-info "The cache path is unsafe for automatic cleanup. Cached files were retained." {})))
    file))
(defn verify-approved-images! [ds row]
  (let [parent (when (:merged_into_selection_id row) (sql/get-by-id ds :vimeo_import_selection (:merged_into_selection_id row) archive/opts))
        _ (when (and (:merged_into_selection_id row) (not= (:imported_service_id row) (:imported_service_id parent)))
            (throw (ex-info "Merged source approval could not be verified. Cached files were retained." {})))
        row (or parent row)
        service (sql/get-by-id ds :church_service (:imported_service_id row) archive/opts)
        draft (archive/read-source (:draft_data row))]
    (when-not service (throw (ex-info "The approved service no longer exists. Cached files were retained." {})))
    ;; Reconciliation may retain another service's primary slide. The import's original
    ;; permanent slide remains referenced by merge history and must still be verified.
    (let [sources (sql/query ds ["select * from service_merge_source where church_service_id=?" (:id service)] archive/opts)
          original (first (filter #(= (:vimeo_url row) (:vimeo_url %)) sources))]
      (verify-image! (or (:sermon_title_image_path service) (:sermon_title_image_path original))
                     (some? (archive/frame-index (:sermon-evidence-index draft) (:sermon-time draft))))
      (doseq [source sources] (verify-image! (:sermon_title_image_path source) false))
      (doseq [event (sql/query ds ["select distinct e.* from service_merge_event e join service_merge_source s on s.merge_event_id=e.id where s.church_service_id=?" (:id service)] archive/opts)
              performance (:performances (archive/read-source (:before_data event)))
              :let [provenance (archive/read-source (:source_data performance)) song (:song provenance)]
              :when (= (:vimeo_url row) (:vimeo-url provenance))]
        (verify-image! (:title_image_path performance)
                       (some? (archive/frame-index (:evidence-index song) (:slide song))))))
    (doseq [performance (sql/query ds ["select * from song_performance where church_service_id = ?" (:id service)] archive/opts)
            :let [song (:song (archive/read-source (:source_data performance)))]]
      (verify-image! (:title_image_path performance)
                     (some? (archive/frame-index (:evidence-index song) (:slide song)))))))
(defn cleanup-approved! [id]
  (let [ds (db/get-datasource)
        row (sql/get-by-id ds :vimeo_import_selection id archive/opts)]
    (if-not (and row (:imported_service_id row)
                 (not (#{"queued" "downloading" "extracting" "recognizing" "merging"} (:job_status row))))
      {:status :retained :message "Only an approved, inactive import cache can be removed."}
      (let [result (try
                     (let [folder (cache-directory id)]
                       (when (.exists folder)
                         (verify-approved-images! ds row)
                         ;; Files.walk does not follow descendant symbolic links.
                         (with-open [paths (Files/walk (.toPath folder) (make-array java.nio.file.FileVisitOption 0))]
                           (doseq [path (iterator-seq (.iterator (.sorted paths (Comparator/reverseOrder))))]
                             (Files/deleteIfExists path))))
                       {:status :cleaned :message "Downloaded video, sampled frames, and local OCR cache removed after approval and image verification."})
                     (catch Exception e {:status :retained :message (str "Service approved; cache cleanup was deferred. " (or (.getMessage e) "Local cached files could not be fully removed."))}))]
        ;; Approval is already committed. A cleanup failure must never appear as an approval failure.
        (try
          (sql/update! ds :vimeo_import_selection
            (cond-> {:cache_cleanup_status (if (= :cleaned (:status result)) "cleaned" "retained")
                     :cache_cleanup_message (:message result)}
              (= :cleaned (:status result)) (assoc :cache_cleaned_at (Timestamp/from (Instant/now)))) {:id id})
          (catch Exception _ nil))
        result))))
(defn resume-pending! []
  ;; Existing records have NULL here; this never sweeps older approved caches.
  (doseq [row (sql/query (db/get-datasource)
               ["select id from vimeo_import_selection where cache_cleanup_status = 'pending' and imported_service_id is not null"] archive/opts)]
    (cleanup-approved! (:id row))))
