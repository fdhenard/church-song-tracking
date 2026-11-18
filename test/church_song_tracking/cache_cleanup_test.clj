(ns church-song-tracking.cache-cleanup-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [next.jdbc.sql :as sql]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo]
            [church-song-tracking.import-review :as review]
            [church-song-tracking.cache-cleanup :as cleanup]))

(def sample {:date "2026-10-25" :title "Service" :leader "Leader" :preacher "Preacher"
             :sermon-title "Sermon" :sermon-time "0:30" :sermon-evidence-index 10
             :songs [{:title "Song" :author "Author" :slide "1:00" :evidence-index 20}]})
(defn with-store [f]
  (let [root (java.nio.file.Files/createTempDirectory "approval-cleanup-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cache (io/file (str root) "cache") permanent (io/file (str root) "permanent")]
    (with-redefs [config/get-db-path #(str root "/songs") config/get-video-cache-dir #(str cache)
                  config/get-slide-archive-dir #(str permanent)]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue))) folder (io/file cache (str id))]
        (.mkdirs (io/file folder "frames")) (.mkdirs (io/file folder "local-ocr"))
        (spit (io/file folder "video.mp4") "downloaded-video")
        (spit (io/file folder "metadata.json") "{\"title\":\"Original video title\"}")
        (spit (io/file folder "frames/frame-000011.jpg") "sermon-image")
        (spit (io/file folder "frames/frame-000021.jpg") "song-image")
        (spit (io/file folder "local-ocr/frame.json") "cached OCR")
        (review/stage! id 0 sample)
        (f {:id id :folder folder :cache cache :permanent permanent})))))

(deftest successful-approval-removes-only-its-own-cache-after-saving-images
  (with-store
    (fn [{:keys [id folder cache]}]
      (let [other (io/file cache "999")]
        (.mkdirs other) (spit (io/file other "video.mp4") "other-video")
        (java.nio.file.Files/createSymbolicLink (.toPath (io/file folder "other-cache-link")) (.toPath other)
          (make-array java.nio.file.attribute.FileAttribute 0))
        (let [service-id (review/approve! id 1)
              service (sql/get-by-id (db/get-datasource) :church_service service-id vimeo/opts)
              performance (first (sql/query (db/get-datasource) ["select * from song_performance"] vimeo/opts))
              selection (review/selection id)]
          (is (= service-id (:imported_service_id selection)))
          (is (= "Original video title" (:vimeo_video_title service)))
          (is (= "sermon-image" (slurp (:sermon_title_image_path service))))
          (is (= "song-image" (slurp (:title_image_path performance))))
          (is (not (.exists folder)))
          (is (= "other-video" (slurp (io/file other "video.mp4"))))
          (is (= "cleaned" (:cache_cleanup_status selection)))
          (is (some? (:cache_cleaned_at selection))))))))

(deftest corrupt-archive-retains-video-and-ocr-without-undoing-approval
  (with-store
    (fn [{:keys [id folder]}]
      (let [service-id (with-redefs [cleanup/cleanup-approved! (constantly nil)] (review/approve! id 1))
            service (sql/get-by-id (db/get-datasource) :church_service service-id vimeo/opts)]
        (spit (:sermon_title_image_path service) "corrupted-image")
        (is (= :retained (:status (cleanup/cleanup-approved! id))))
        (is (= service-id (:imported_service_id (review/selection id))))
        (is (= "downloaded-video" (slurp (io/file folder "video.mp4"))))
        (is (= "cached OCR" (slurp (io/file folder "local-ocr/frame.json"))))
        (is (= "retained" (:cache_cleanup_status (review/selection id))))))))

(deftest startup-only-resumes-new-pending-cleanups-and-keeps-unapproved-caches
  (with-store
    (fn [{:keys [id folder]}]
      (is (= :retained (:status (cleanup/cleanup-approved! id))))
      (is (.exists folder))
      (with-redefs [cleanup/cleanup-approved! (constantly nil)] (review/approve! id 1))
      ;; NULL represents an approval that predates automatic cleanup.
      (sql/update! (db/get-datasource) :vimeo_import_selection {:cache_cleanup_status nil} {:id id})
      (cleanup/resume-pending!)
      (is (.exists folder))
      (sql/update! (db/get-datasource) :vimeo_import_selection {:cache_cleanup_status "pending"} {:id id})
      (cleanup/resume-pending!)
      (is (not (.exists folder)))
      (is (= "cleaned" (:cache_cleanup_status (review/selection id)))))))

(deftest unsafe-root-symlink-does-not-delete-another-import
  (with-store
    (fn [{:keys [id folder cache]}]
      (with-redefs [cleanup/cleanup-approved! (constantly nil)] (review/approve! id 1))
      (let [original (io/file cache "original") other (io/file cache "999")]
        (java.nio.file.Files/move (.toPath folder) (.toPath original) (make-array java.nio.file.CopyOption 0))
        (.mkdirs other) (spit (io/file other "video.mp4") "keep-me")
        (java.nio.file.Files/createSymbolicLink (.toPath folder) (.toPath other) (make-array java.nio.file.attribute.FileAttribute 0))
        (is (= :retained (:status (cleanup/cleanup-approved! id))))
        (is (= "keep-me" (slurp (io/file other "video.mp4"))))
        (is (.exists original))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
