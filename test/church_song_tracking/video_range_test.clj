(ns church-song-tracking.video-range-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.video-range :as vr]
            [church-song-tracking.video-jobs :as jobs]
            [church-song-tracking.vimeo-import :as vimeo]
            [church-song-tracking.import-review :as review]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc.sql :as sql]))

(deftest timestamps-and-seeking
  (is (= 3723 (vr/parse-time "1:02:03")))
  (is (= 90 (vr/parse-time "1:30")))
  (is (= 90 (vr/parse-time "90")))
  (doseq [s ["-1" "1:60" "no" "1:02:03:04"]]
    (is (thrown? clojure.lang.ExceptionInfo (vr/parse-time s))))
  (let [file (java.io.File/createTempFile "video-range" ".mp4")]
    (spit file "0123456789")
    (doseq [[header expected] [["bytes=2-5" "2345"] ["bytes=-3" "789"] ["bytes=8-" "89"]]]
      (let [response (vr/preview-response file header)]
        (is (= 206 (:status response)))
        (with-open [body (:body response)] (is (= expected (slurp body))))))
    (let [response (vr/preview-response file "bytes=0-2") buffer (byte-array 20)]
      (with-open [body (:body response)]
        (is (= 3 (.read ^java.io.InputStream body buffer)))
        (is (= "012" (String. buffer 0 3 "UTF-8")))
        (is (= -1 (.read ^java.io.InputStream body buffer)))))
    (is (= 416 (:status (vr/preview-response file "bytes=9-2"))))
    (is (= 416 (:status (vr/preview-response file "bytes=1-2,5-6"))))
    (is (= 200 (:status (vr/preview-response file nil))))))

(deftest range-reprocessing-preserves-edits-and-requires-new-review
  (let [dir (java.nio.file.Files/createTempDirectory "ocr-range-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cache (str dir "/cache")]
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir (constantly cache)
                  jobs/preflight! (fn [& _] {:python "unused" :model "unused"}) jobs/submit-stage! (fn [& _])]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue)))
            folder (io/file cache (str id))
            req {:path-params {:entity "imports" :id (str id)}}
            draft {:title "Edited service" :preacher "Edited preacher" :songs [{:title "Edited song"}]}]
        (.mkdirs folder)
        (spit (io/file folder "metadata.json") "{\"duration\":100}")
        (spit (io/file folder "video.mp4") "test-video")
        (review/stage! id 0 draft)
        (is (= 200 (:status (admin/range-page req))))
        (is (str/includes? (:body (admin/range-page req)) "Save range and run local OCR"))
        (is (= 403 (:status (admin/app {:request-method :post :uri (str "/imports/" id "/trim") :headers {}}))))
        (vr/save! id 1 "0:10" "0:20")
        (is (= 10 (:trim_start_seconds (review/selection id))))
        (is (:range_needs_ocr (review/selection id)))
        (is (= draft (review/draft (review/selection id))))
        (is (thrown? clojure.lang.ExceptionInfo (review/approve! id 2)))
        (is (thrown? clojure.lang.ExceptionInfo (vr/save! id 2 "0:10" "0:11")))
        (is (thrown? clojure.lang.ExceptionInfo (vr/save! id 2 "0:10" "2:00")))
        (jobs/start! id)
        (let [row (review/selection id)]
          (is (:job_refresh_review row))
          (jobs/complete! id (:job_token row) 2
            {:draft {:title "Machine title" :songs [] :analysis-notes "New range"} :local_only true :evidence []}))
        (is (= "Edited service" (:title (review/draft (review/selection id)))))
        (is (= (:songs draft) (:songs (review/draft (review/selection id)))))
        (is (= "New range" (:analysis-notes (review/draft (review/selection id)))))
        (is (false? (:range_needs_ocr (review/selection id))))
        (is (false? (:job_refresh_review (review/selection id))))
        (is (thrown? clojure.lang.ExceptionInfo (vr/save! id 2 "0" "20")))))))

(deftest discard-ocr-keeps-video-and-reusable-cache
  (let [dir (java.nio.file.Files/createTempDirectory "discard-ocr-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue))) folder (io/file (config/get-video-cache-dir) (str id))
            cached (io/file folder "local-ocr" "frame.json")
            draft {:date "2026-10-25" :title "My title" :leader "Leader" :preacher "Preacher"
                   :sermon-title "Wrong sermon" :songs [{:title "Wrong range song"}]}]
        (.mkdirs (.getParentFile cached))
        (spit cached "cached text") (spit (io/file folder "metadata.json") "{\"duration\":100}")
        (spit (io/file folder "video.mp4") "original-video")
        (review/stage! id 0 draft)
        (vr/save! id 1 "10" "40")
        (is (thrown? clojure.lang.ExceptionInfo (vr/discard-ocr! id 1)))
        (vr/discard-ocr! id 2)
        (let [row (review/selection id)]
          (is (= "downloaded" (:job_status row)))
          (is (= 0 (:trim_start_seconds row))) (is (nil? (:trim_end_seconds row)))
          (is (nil? (:extracted_data row))) (is (:range_needs_ocr row))
          (is (= (select-keys draft [:date :title :leader :preacher]) (review/draft row)))
          (is (= "cached text" (slurp cached)))
          (is (= "original-video" (slurp (jobs/downloaded-file id))))
          (is (thrown? clojure.lang.ExceptionInfo (review/approve! id 3)))
          (vr/save! id 3 "20" "50")
          (is (= 20 (:trim_start_seconds (review/selection id)))))
        (sql/update! (db/get-datasource) :vimeo_import_selection {:job_status "recognizing"} {:id id})
        (is (thrown? clojure.lang.ExceptionInfo (vr/discard-ocr! id 4)))
))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
