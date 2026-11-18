(ns church-song-tracking.slide-archive-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [next.jdbc.sql :as sql]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo]
            [church-song-tracking.import-review :as review]
            [church-song-tracking.slide-archive :as archive]
            [church-song-tracking.admin :as admin]))

(def sample {:date "2026-10-25" :title "Service" :leader "Leader" :preacher "Preacher"
             :sermon-title "Sermon" :sermon-time "0:30" :sermon-evidence-index 10
             :songs [{:title "Song" :author "Author" :slide "1:00" :evidence-index 20}]})

(deftest approval-archives-images-and-backfill-is-idempotent
  (let [root (java.nio.file.Files/createTempDirectory "slide-archive-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cache (io/file (str root) "cache") durable (io/file (str root) "durable")]
    (with-redefs [config/get-db-path #(str root "/songs") config/get-video-cache-dir #(str cache)
                  config/get-slide-archive-dir #(str durable)]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue))) frames (io/file cache (str id) "frames")]
        (.mkdirs frames)
        (spit (io/file frames "frame-000011.jpg") "sermon-image")
        (spit (io/file frames "frame-000021.jpg") "song-image")
        (review/stage! id 0 sample)
        (let [service-id (review/approve! id 1)
              service (sql/get-by-id (db/get-datasource) :church_service service-id vimeo/opts)
              performance (first (sql/query (db/get-datasource) ["select * from song_performance"] vimeo/opts))
              sermon (:sermon_title_image_path service) song (:title_image_path performance)]
          (is (= "sermon-image" (slurp sermon)))
          (is (= "song-image" (slurp song)))
          (is (.startsWith sermon (.getCanonicalPath durable)))
          (is (= 200 (:status (admin/archived-title-response {:path-params {:entity "services" :id (str service-id)}}))))
          (is (= 2 (get-in (archive/backfill! {}) [:counts :already-archived])))
          (is (not (.exists (io/file cache (str id)))))
          (is (= "cleaned" (:cache_cleanup_status (review/selection id))))
          (.mkdirs frames)
          (spit (io/file frames "frame-000011.jpg") "sermon-image")
          (spit (io/file frames "frame-000021.jpg") "song-image")
          ;; Simulate a pre-feature database: backfill attaches permanent paths from approved provenance.
          (sql/update! (db/get-datasource) :church_service {:sermon_title_image_path nil} {:id service-id})
          (sql/update! (db/get-datasource) :song_performance {:title_image_path nil} {:id (:id performance)})
          (is (= 2 (get-in (archive/backfill! {}) [:counts :archived])))
          (is (= 2 (get-in (archive/backfill! {}) [:counts :already-archived])))
          (.delete (io/file frames "frame-000011.jpg")) (.delete (io/file frames "frame-000021.jpg"))
          (is (= "sermon-image" (slurp sermon)) "Permanent copy survives cache removal")
          (is (= "song-image" (slurp song)))
          (is (= 2 (get-in (archive/backfill! {}) [:counts :already-archived])))
          (is (nil? (archive/safe-image-file (str root "/outside.jpg")))))))))

(deftest missing-selected-image-blocks-approval-without-library-writes
  (let [root (java.nio.file.Files/createTempDirectory "missing-slide-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str root "/songs") config/get-video-cache-dir #(str root "/cache")
                  config/get-slide-archive-dir #(str root "/durable")]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue)))]
        (review/stage! id 0 sample)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"image is missing" (review/approve! id 1)))
        (is (empty? (sql/query (db/get-datasource) ["select * from church_service"] vimeo/opts)))
        (is (nil? (:imported_service_id (review/selection id))))))))

(deftest historical-frame-staging-is-discovered-without-a-selection-row
  (let [root (java.nio.file.Files/createTempDirectory "legacy-slide-cache-test" (make-array java.nio.file.attribute.FileAttribute 0))
        folder (io/file (str root) "-11")]
    (.mkdirs folder)
    (spit (io/file folder "metadata.json") "{\"webpage_url\":\"https://player.vimeo.com/video/123\"}")
    (with-redefs [config/get-video-cache-dir #(str root)]
      (is (= {"https://vimeo.com/123" -11} (archive/cache-urls))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
