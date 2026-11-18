(ns church-song-tracking.video-jobs-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.import-review :as review]
            [church-song-tracking.vimeo-import :as vimeo]
            [church-song-tracking.video-jobs :as jobs]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest processing-stages-without-approval
  (let [dir (java.nio.file.Files/createTempDirectory "video-job-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs")
                  config/get-data-base-dir #(str dir)
                  config/get-video-cache-dir #(str dir "/video-cache")]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue)))
            data {:title "Extracted title" :songs [{:title "Song" :author "Author" :slide "1:05" :start ""}]}]
        (is (str/includes? (:body (admin/imports-page {:headers {"x-import-progress" "true"}})) "download-available"))
        (sql/update! (db/get-datasource) :vimeo_import_selection
          {:job_status "recognizing" :job_token "test-token"} {:id id})
        (is (str/includes? (:body (admin/imports-page {:headers {"x-import-progress" "true"}})) "Cancel processing"))
        (is (thrown? clojure.lang.ExceptionInfo (review/save-draft! id 0 data)))
        (is (thrown? clojure.lang.ExceptionInfo (review/approve! id 0)))
        (is (thrown? clojure.lang.ExceptionInfo (jobs/complete! id "wrong-token" 0 {:draft data})))
        (jobs/complete! id "test-token" 0 {:draft data :evidence []})
        (is (= "ready" (:job_status (review/selection id))))
        (is (str/includes? (:body (admin/imports-page {:headers {"x-import-progress" "true"}})) "Review extracted data"))
        (is (= data (review/draft (review/selection id))))
        (is (= 1 (:revision (review/selection id))))
        (is (zero? (:N (jdbc/execute-one! (db/get-datasource) ["select count(*) as n from church_service"]))))
        (vimeo/select! {"links" "https://vimeo.com/123"})
        (is (= data (review/draft (review/selection id))))
        (is (thrown? clojure.lang.ExceptionInfo (jobs/complete! id "test-token" 0 {:draft data})))
        (sql/update! (db/get-datasource) :vimeo_import_selection {:job_status "downloading" :job_token "cancel-token"} {:id id})
        (jobs/cancel! id)
        (is (= "failed" (:job_status (review/selection id))))
        (is (nil? (:job_token (review/selection id))))
        (sql/update! (db/get-datasource) :vimeo_import_selection {:job_status "queued"} {:id id})
        (jobs/recover!)
        (is (= "failed" (:job_status (review/selection id))))))))

(deftest downloads-and-analysis-do-not-block-each-other
  (let [started (CountDownLatch. 3)
        release (CountDownLatch. 1)
        fourth (promise)
        analysis (promise)
        downloads (mapv (fn [_] (jobs/submit-stage! true #(do (.countDown started) (.await release)))) (range 3))]
    (try
      (is (.await started 5 TimeUnit/SECONDS) "Three downloads can run simultaneously")
      (let [waiting (jobs/submit-stage! true #(deliver fourth :started))
            recognition (jobs/submit-stage! false #(deliver analysis :started))]
        (is (= :started (deref analysis 3000 :timeout)) "Analysis starts while all download slots are busy")
        (is (= :waiting (deref fourth 100 :waiting)) "Additional downloads wait for a free slot")
        (.countDown release)
        (is (= :started (deref fourth 3000 :timeout)))
        (.get waiting 5 TimeUnit/SECONDS)
        (.get recognition 5 TimeUnit/SECONDS))
      (finally
        (.countDown release)
        (doseq [task downloads] (.get task 5 TimeUnit/SECONDS))))))

(deftest download-handoff-and-cached-analysis
  (let [dir (java.nio.file.Files/createTempDirectory "video-handoff-test" (make-array java.nio.file.attribute.FileAttribute 0))
        submitted (atom [])]
    (with-redefs [config/get-db-path #(str dir "/songs")
                  config/get-data-base-dir #(str dir)
                  config/get-video-cache-dir #(str dir "/video-cache")
                  jobs/preflight! (fn [& _] {:python "unused" :model "test"})
                  jobs/submit-stage! (fn [download? task] (swap! submitted conj download?))]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123
https://vimeo.com/456"})
      (let [[a b] (vimeo/queue)
            id (:id a)]
        (with-redefs [jobs/downloaded-file (constantly nil)]
          (jobs/start! id false))
        (is (= [true] @submitted) "Full imports begin in the download pool")
        (is (thrown? clojure.lang.ExceptionInfo (jobs/start! id true)) "A service cannot run twice")
        (let [token (:job_token (review/selection id))]
          (jobs/complete-download! id token 0 (:vimeo_url a) {:analyze-after-download? true})
          (is (= [true false] @submitted) "Completed download hands off to the independent analysis pool")
          (is (= "queued" (:job_status (review/selection id))))
          (is (nil? (:draft_data (review/selection id)))))
        (with-redefs [jobs/downloaded-file (constantly (java.io.File. "cached-video.mp4"))]
          (jobs/start! (:id b) false))
        (is (= [true false false] @submitted) "A cached file goes directly to analysis, regardless of other downloads")
        (vimeo/select! {"links" "https://vimeo.com/789\nhttps://vimeo.com/999"})
        (with-redefs [jobs/downloaded-file (constantly nil)]
          (is (= 1 (jobs/download-selected! ["https://vimeo.com/789"])))
          (is (= 0 (jobs/download-selected! ["https://vimeo.com/789"])))
          (is (= 1 (jobs/download-pending!)))
          (is (= [true false false true true] @submitted) "Bulk downloads go to the download pool")
          (is (thrown? clojure.lang.ExceptionInfo (jobs/download-pending!)) "Bulk actions do not requeue active jobs"))
        (is (zero? (:N (jdbc/execute-one! (db/get-datasource) ["select count(*) as n from church_service"]))))))))

(deftest video-and-frame-files-use-the-cache-not-the-database-directory
  (let [dir (java.nio.file.Files/createTempDirectory "separate-video-cache-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cache (str dir "/cache")
        directory (io/file cache "7")]
    (with-redefs [config/get-video-cache-dir (constantly cache)
                  config/get-data-base-dir #(throw (Exception. "Video files must not use the database directory"))
                  review/selection (fn [_] {:id 7})]
      (.mkdirs (io/file directory "frames"))
      (spit (io/file directory "metadata.json") "{}")
      (spit (io/file directory "video.mp4") "test-video")
      (spit (io/file directory "frames" "frame-000001.jpg") "test-frame")
      (is (= (.getCanonicalPath (io/file directory "video.mp4"))
             (.getCanonicalPath (jobs/downloaded-file 7))))
      (is (= 200 (:status (admin/video-response {:path-params {:entity "imports" :id "7"}}))))
      (is (= 200 (:status (admin/frame-response {:path-params {:entity "imports" :id "7" :frame "0"}})))))))

(deftest default-import-jobs-stage-raw-ocr-without-api-credentials
  (let [dir (java.nio.file.Files/createTempDirectory "local-only-job-test" (make-array java.nio.file.attribute.FileAttribute 0))
        checked (atom nil) submitted (atom nil)]
    (with-redefs [config/get-db-path #(str dir "/songs")
                  config/get-video-cache-dir #(str dir "/cache")
                  jobs/downloaded-file (constantly (io/file (str dir) "video.mp4"))
                  jobs/preflight! (fn [key-free?] (reset! checked key-free?) {:python "unused" :model "unused"})
                  jobs/submit-stage! (fn [download? _] (reset! submitted download?))]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue)))
            response (admin/save-import-selection! {:params {"action" "start-import" "selection" (str id)}})
            row (review/selection id)]
        (is (= 303 (:status response)))
        (is (= true @checked) "Local-only mode uses credential-free preflight")
        (is (= false @submitted) "A downloaded file uses the analysis pool")
        (jobs/complete! id (:job_token row) 0
          {:draft {:title "Local review" :songs [] :analysis-notes "LOCAL OCR ONLY"}
           :local_only true :evidence [{:index 0 :kind "ocr_candidate" :ocr "Song title / Writer"}]})
        (is (str/includes? (:job_message (review/selection id)) "No API calls"))
        (is (str/includes? (:body (admin/review-page {:path-params {:id (str id)}})) "Song title / Writer"))
        (is (thrown? clojure.lang.ExceptionInfo (review/approve! id 1)))
        (is (zero? (:N (jdbc/execute-one! (db/get-datasource) ["select count(*) as n from church_service"]))))))))

(deftest existing-local-review-slides-are-grouped-without-losing-occurrences
  (let [frames [{:index 0 :kind "ocr_candidate" :ocr "Song A / Writer"}
                {:index 4 :kind "ocr_candidate" :ocr "SONG A Writer"}
                {:index 9 :kind "ocr_candidate" :ocr "Song A / Different Writer"}]
        grouped (admin/compact-ocr-evidence frames)]
    (is (= 2 (count grouped)))
    (is (= [0 4] (mapv :index (:occurrences (first grouped)))))
    (is (= [0 12] (mapv :first_seen_seconds (:occurrences (first grouped)))))))

(deftest selected-downloads-retry-failures-without-requeueing-active-jobs
  (let [started (atom [])]
    (with-redefs [db/get-datasource (constantly nil) sql/query (fn [& _] [])
                  jobs/downloaded-file (constantly nil)
                  jobs/start! (fn [id download?] (swap! started conj [id download?]))
                  vimeo/queue (constantly [{:id 1 :vimeo_url "https://vimeo.com/123" :job_status "failed"}
                                           {:id 2 :vimeo_url "https://vimeo.com/456" :job_status "downloading"}
                                           {:id 3 :vimeo_url "https://vimeo.com/789" :job_status "selected"}])]
      (is (= 1 (jobs/download-selected! ["https://vimeo.com/123" "https://vimeo.com/456"])))
      (is (= [[1 true]] @started)))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
