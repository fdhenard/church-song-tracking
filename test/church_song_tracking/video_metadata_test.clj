(ns church-song-tracking.video-metadata-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [next.jdbc.sql :as sql]
            [church-song-tracking.db :as db]
            [church-song-tracking.config :as config]
            [church-song-tracking.vimeo-import :as vimeo]
            [church-song-tracking.video-jobs :as jobs]
            [church-song-tracking.import-review :as review]
            [church-song-tracking.video-metadata :as metadata]))

(deftest original-title-survives-download-review-approval-and-cache-removal
  (let [root (java.nio.file.Files/createTempDirectory "video-title-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str root "/songs") config/get-video-cache-dir #(str root "/cache")
                  vimeo/cached-catalog (constantly [{:url "https://vimeo.com/123" :title "Discovery title"}])
                  jobs/preflight! (fn [& _] {:python "unused"}) jobs/submit-stage! (fn [& _])]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue))) folder (io/file (config/get-video-cache-dir) (str id))
            data {:date "2026-10-25" :title "My edited service title" :leader "Leader" :preacher "Preacher"
                  :sermon-title "Sermon" :songs [{:title "Song" :author "Author" :slide "1:00"}]}]
        (is (= "Discovery title" (:vimeo_video_title (review/selection id))))
        (.mkdirs folder)
        (let [token (jobs/start! id true)]
          (spit (io/file folder "metadata.json") "{\"title\":\"Original downloaded video title\"}")
          (jobs/complete-download! id token 0 "https://vimeo.com/123" {:analyze-after-download? false}))
        (is (= "Original downloaded video title" (:vimeo_video_title (review/selection id))))
        (review/stage! id 0 data)
        (.delete (io/file folder "metadata.json"))
        (let [service-id (review/approve! id 1)
              service (sql/get-by-id (db/get-datasource) :church_service service-id vimeo/opts)]
          (is (= "My edited service title" (:title service)))
          (is (= "Original downloaded video title" (:vimeo_video_title service)))
          (sql/update! (db/get-datasource) :church_service {:vimeo_video_title nil} {:id service-id})
          (is (= 1 (:services-updated (metadata/backfill! {}))))
          (is (= "Original downloaded video title" (:vimeo_video_title (sql/get-by-id (db/get-datasource) :church_service service-id vimeo/opts))))
          (is (= 0 (:services-updated (metadata/backfill! {})))))))))

(deftest legacy-extraction-title-is-not-the-edited-service-title
  (is (= "Original" (metadata/original-extraction-title {:extracted_data (pr-str {:draft {:title "Original"}})})))
  (is (nil? (metadata/original-extraction-title {:draft_data (pr-str {:title "Edited"})}))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
