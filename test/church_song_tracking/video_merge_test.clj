(ns church-song-tracking.video-merge-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io] [clojure.data.json :as json] [clojure.string :as str]
            [church-song-tracking.config :as config] [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo] [church-song-tracking.video-jobs :as jobs]
            [church-song-tracking.video-merge :as merge] [church-song-tracking.import-review :as review]
            [church-song-tracking.admin :as admin] [next.jdbc.sql :as sql]))

(deftest merge-review-approval-and-exclusive-cache-cleanup
 (let [dir (.toFile (java.nio.file.Files/createTempDirectory "merge-review-test" (make-array java.nio.file.attribute.FileAttribute 0)))
       started (atom [])]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")
                config/get-slide-archive-dir #(str dir "/permanent") jobs/start! (fn [& args] (swap! started conj args))]
   (db/migrate! nil)
   (vimeo/select! {"links" "https://vimeo.com/123\nhttps://vimeo.com/456"})
   (let [ds (db/get-datasource) rows (sort-by :id (vimeo/queue)) [a b] (map :id rows)]
    (doseq [row rows :let [folder (io/file (config/get-video-cache-dir) (str (:id row)))]]
     (.mkdirs folder)
     (spit (io/file folder "video.mp4") "synthetic video")
     (spit (io/file folder "metadata.json") (json/write-str {:title "Livestream 08-09-26" :duration 10}))
     (sql/update! ds :vimeo_import_selection {:job_status "downloaded" :vimeo_video_title "Livestream 08-09-26"} {:id (:id row)}))
    (is (thrown? clojure.lang.ExceptionInfo (merge/ordered-ids {"include" [(str a) (str a)] "part" [(str a) (str a)]})))
    (let [params {"include" [(str a) (str b)] "part" [(str b) (str a)] "allow-conversion" "yes"}]
     (is (= 200 (:status (admin/app {:request-method :get :uri "/imports/merge" :query-string "video=https%3A%2F%2Fvimeo.com%2F123" :headers {}}))))
     (is (str/includes? (:body (admin/merge-page {:params {"video" "https://vimeo.com/123"}})) "Continue"))
     (let [html (:body (admin/merge-page {:params (assoc params "video" "https://vimeo.com/123" "step" "order")} "Confirm review reset"))]
       (is (< (str/index-of html (str "name=\"part\" type=\"hidden\" value=\"" b)) (str/index-of html (str "name=\"part\" type=\"hidden\" value=\"" a))))
       (is (str/includes? html "Confirm review reset")))
     (sql/update! ds :vimeo_import_selection {:job_status "recognizing"} {:id a})
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not processing" (merge/create! params)))
     (sql/update! ds :vimeo_import_selection {:job_status "downloaded" :vimeo_video_title "Different title"} {:id a})
     (is (str/includes? (:body (admin/merge-page {:params {"video" "https://vimeo.com/456"}})) "Different title"))
     (sql/update! ds :vimeo_import_selection {:draft_data "{:songs []}"} {:id a})
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Confirm" (merge/create! params)))
     (is (empty? (merge/sources ds b)))
     (is (= b (merge/create! (assoc params "discard-review" "yes"))))
     (is (= [[b true]] @started))
     (is (= [b a] (mapv :source_selection_id (merge/sources ds b))))
     (is (= b (:merged_into_selection_id (review/selection a))))
     (is (nil? (jobs/downloaded-file b)))
     (is (thrown? clojure.lang.ExceptionInfo (review/save-draft! a 0 {})))
     (is (thrown? clojure.lang.ExceptionInfo (vimeo/remove! (str a))))
     (let [html (:body (admin/imports-page {:headers {"x-import-progress" "true"}}))]
      (is (str/includes? html "Merged recording")))
     ;; Simulate successful worker output, then use the real download handoff and approval transaction.
     (spit (io/file (config/get-video-cache-dir) (str b) "metadata.json")
       (json/write-str {:title "Livestream 08-09-26" :duration 30 :merged true
                        :sources [{:selection_id b :duration 20 :merged_start 0} {:selection_id a :duration 10 :merged_start 20}]}))
     (sql/update! ds :vimeo_import_selection {:job_status "merging" :job_token "merge-token"} {:id b})
     (jobs/complete-download! b "merge-token" 1 "https://vimeo.com/456" {})
     (is (= [0.0 20.0] (mapv :merged_start_seconds (merge/sources ds b))))
     (is (some? (jobs/downloaded-file b)))
     (review/stage! b 1 {:date "2026-08-09" :title "Merged worship" :leader "Leader" :preacher "Preacher"
                         :sermon-title "Sermon" :sermon-time "" :songs [{:title "Song" :author "Author" :start "1:05" :slide ""}]})
     (let [service-id (review/approve! b 2) service (sql/get-by-id ds :church_service service-id vimeo/opts)
           sources (merge/service-sources ds service-id)]
      (is (:is_merged service))
      (is (str/includes? (:merged_recording_note service) "https://vimeo.com/456"))
      (is (str/includes? (:merged_recording_note service) "https://vimeo.com/123"))
      (is (= [b a] (mapv :source_selection_id sources)))
      (is (= service-id (:imported_service_id (review/selection a))))
      (is (every? #(= "cleaned" (:cache_cleanup_status (review/selection %))) [a b]))
      (is (not (.exists (io/file (config/get-video-cache-dir) (str a)))))
      (is (not (.exists (io/file (config/get-video-cache-dir) (str b)))))))))))

(deftest background-merge-stops-at-range-selection
 (let [dir (.toFile (java.nio.file.Files/createTempDirectory "merge-worker-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")
                config/video-settings (fn [] {:python ".video-tools/bin/python" :model "unused" :local-ocr? true})]
   (db/migrate! nil)
   (vimeo/select! {"links" "https://vimeo.com/321\nhttps://vimeo.com/654"})
   (let [ds (db/get-datasource) ids (sort (map :id (vimeo/queue)))
         make-clip "import imageio_ffmpeg,subprocess,sys;subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-v','error','-f','lavfi','-i','color=blue:s=160x90:r=30:d=0.5','-c:v','libx264','-preset','ultrafast',sys.argv[1]],check=True)"]
    (doseq [id ids :let [folder (io/file (config/get-video-cache-dir) (str id))]]
     (.mkdirs folder)
     (let [p (.start (ProcessBuilder. ^java.util.List [".video-tools/bin/python" "-c" make-clip (str (io/file folder "video.mp4"))]))]
      (is (zero? (.waitFor p))))
     (spit (io/file folder "metadata.json") (json/write-str {:title "Livestream 01-01-26" :duration 0.5}))
     (sql/update! ds :vimeo_import_selection {:job_status "downloaded" :vimeo_video_title "Livestream 01-01-26"} {:id id}))
    (let [id (merge/create! {"include" (mapv str ids) "part" (mapv str ids) "allow-conversion" "yes"})
          row (loop [attempt 0]
                (let [r (review/selection id)]
                  (if (and (< attempt 150) (jobs/active-statuses (:job_status r)))
                    (do (Thread/sleep 200) (recur (inc attempt))) r)))]
     (when (jobs/active-statuses (:job_status row)) (jobs/cancel! id))
     (is (= "downloaded" (:job_status row)) (:job_message row))
     (is (nil? (:draft_data row)) "Merge completion must wait for user OCR-range selection")
     (is (some? (jobs/downloaded-file id)))
     (is (= [0.0 0.5] (mapv :merged_start_seconds (merge/sources ds id)))))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))

(deftest merge-button-only-for-downloaded-idle-imports
 (let [item {:id 42 :vimeo_url "https://vimeo.com/42" :vimeo_video_title "Livestream" :job_status "downloaded"}
       render #(pr-str (admin/import-selection-row % {} {}))]
   (with-redefs [vimeo/cached-catalog (constantly []) jobs/downloaded-file (constantly nil)]
     (is (not (str/includes? (render item) "Merge with another service"))))
   (with-redefs [vimeo/cached-catalog (constantly []) jobs/downloaded-file (constantly (io/file "/tmp/downloaded.mp4"))]
     (is (str/includes? (render item) "Merge with another service"))
     (is (not (str/includes? (render (assoc item :job_status "recognizing")) "Merge with another service")))
     (is (not (str/includes? (render (assoc item :imported_service_id 1)) "Merge with another service")))
     (is (not (str/includes? (render (assoc item :merged_into_selection_id 1)) "Merge with another service"))))))
