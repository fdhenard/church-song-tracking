(ns church-song-tracking.vimeo-import-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo]
            [church-song-tracking.video-jobs :as jobs]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(deftest feed-and-validation
  (is (= [{:url "https://vimeo.com/123" :title "Sunday & worship" :published "Today"}]
         (vimeo/parse-feed "<rss><channel><item><title>Sunday &amp; worship</title><link>https://vimeo.com/123</link><pubDate>Today</pubDate></item></channel></rss>")))
  (doseq [url ["http://vimeo.com/123" "https://vimeo.com.evil/123" "https://example.com/123" "https://vimeo.com/ewobc"]]
    (is (thrown? clojure.lang.ExceptionInfo (vimeo/video-url url))))
  (is (= "https://vimeo.com/123" (vimeo/video-url " https://vimeo.com/123?share=copy "))))

(deftest selection-does-not-import
  (let [dir (java.nio.file.Files/createTempDirectory "vimeo-selection-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs")
                  config/get-data-base-dir #(str dir)
                  config/get-video-cache-dir #(str dir "/video-cache")
                  vimeo/discover (constantly [])]
      (db/migrate! nil)
      (with-redefs [vimeo/discover #(throw (Exception. "offline"))]
        (is (= 200 (:status (admin/app {:request-method :get :uri "/imports" :headers {}}))))
        (is (str/includes? (:body (admin/imports-page {:params {"browse" "1"}})) "could not be loaded")))
      (with-redefs [vimeo/discover #(vector {:url "https://vimeo.com/123" :title "<Livestream Service>" :published "Today"})]
        (is (str/includes? (:body (admin/imports-page {:params {"browse" "1"}})) "&lt;Livestream Service&gt;")))
      (is (= 403 (:status (admin/app {:request-method :post :uri "/imports" :headers {}}))))
      (is (= 2 (vimeo/select! {"video" ["https://vimeo.com/123" "https://vimeo.com/456"]
                              "links" "https://vimeo.com/123"})))
      (vimeo/select! {"video" "https://vimeo.com/123"})
      (is (= 2 (count (vimeo/queue))))
      (let [requested (atom nil)]
        (with-redefs [jobs/download-selected! (fn [urls] (reset! requested urls) 1)]
          (is (= "/imports?page=2&notice=downloads-queued"
                 (get-in (admin/save-import-selection! {:params {"action" "download-available" "video" "https://vimeo.com/123" "page" "2"}}) [:headers "Location"])))
          (is (= ["https://vimeo.com/123"] @requested))))
      (with-redefs [vimeo/discover (fn [] [{:url "https://vimeo.com/123" :title "Queued Livestream"}
                                         {:url "https://vimeo.com/789" :title "Available Livestream"}])]
        (let [body (:body (admin/imports-page {:params {}}))
              inputs (re-seq #"<input[^>]*>" body)
              queued (first (filter #(str/includes? % "value=\"https://vimeo.com/123\"") inputs))
              available (first (filter #(str/includes? % "value=\"https://vimeo.com/789\"") inputs))]
          (is (str/includes? queued "hidden"))
          (is (str/includes? available "hidden"))
          (is (not (str/includes? body "type=\"checkbox\"")))
          (is (str/includes? body "download-available"))))
      (is (thrown? clojure.lang.ExceptionInfo (vimeo/select! {"links" "https://vimeo.com/789\ninvalid"})))
      (is (= 2 (count (vimeo/queue))))
      (is (= 422 (:status (admin/save-import-selection! {:params {"action" "select"}}))))
      (doseq [table ["church_service" "song" "song_performance" "person"]]
        (is (zero? (:N (jdbc/execute-one! (db/get-datasource) [(str "select count(*) as n from " table)])))))
      (vimeo/remove! (str (:id (first (vimeo/queue)))))
      (is (= 1 (count (vimeo/queue)))))))

(deftest show-only-unimported-videos
  (with-redefs [vimeo/imported-recordings (fn [_] (admin/rows nil "services"))
                vimeo/discarded-services (constantly []) admin/rows (fn [_ _] [{:link_vimeo "http://www.vimeo.com/123?share=copy"}])
                vimeo/queue (fn [] [{:vimeo_url "https://vimeo.com/123"}])
                vimeo/discover (fn [] [{:url "https://vimeo.com/123" :title "Imported Livestream service"}
                                      {:url "https://vimeo.com/456" :title "New LIVEstream service"}
                                      {:url "https://vimeo.com/988039697" :title "East White Oak Bible Church Live Stream 07-28-24"}
                                      {:url "https://vimeo.com/959676301" :title "06-23-24"}
                                      {:url "https://vimeo.com/111" :title "02-30-24"}
                                      {:url "https://vimeo.com/789" :title "Sunday Message"}])]
    (let [body (:body (admin/imports-page {:params {}}))]
      (is (str/includes? body "New LIVEstream service"))
      (is (str/includes? body "East White Oak Bible Church Live Stream 07-28-24"))
      (is (str/includes? body "06-23-24"))
      (is (not (str/includes? body "02-30-24")))
      (is (not (str/includes? body "Sunday Message")))
      (is (not (str/includes? body "Imported Livestream service")))
      (is (not (str/includes? body "href=\"https://vimeo.com/123\"")))
      (is (str/includes? body "Ready for your next action · 3"))))
  (with-redefs [vimeo/imported-recordings (fn [_] (admin/rows nil "services"))
                vimeo/discarded-services (constantly []) admin/rows (fn [_ _] [{:link_vimeo "https://vimeo.com/123"}])
                vimeo/queue (constantly [])
                vimeo/discover (fn [] [{:url "https://vimeo.com/123" :title "Imported Livestream service"}])]
    (is (str/includes? (:body (admin/imports-page {:params {}})) "No unimported service videos were found"))))

(deftest available-list-survives-download-navigation-and-validation-errors
  (with-redefs [vimeo/imported-recordings (fn [_] (admin/rows nil "services"))
                vimeo/discarded-services (constantly []) admin/rows (fn [_ _] [])
                vimeo/queue (constantly [])
                vimeo/discover (fn [] [{:url "https://vimeo.com/456" :title "Available Livestream"}])]
    (doseq [req [{:params {}} {:params {"browse" "0"}} {:params {"notice" "saved"}}]]
      (is (str/includes? (:body (admin/imports-page req)) "Available Livestream")))
    (is (str/includes? (:body (admin/imports-page {:params {}} "Download failed")) "Available Livestream"))
    (with-redefs [vimeo/discover (fn [] (throw (Exception. "Progress polling must not fetch Vimeo")))]
      (is (= 200 (:status (admin/imports-page {:headers {"x-import-progress" "true"}})))))))

(deftest paginated-available-services
  (with-redefs [vimeo/imported-recordings (fn [_] (admin/rows nil "services"))
                vimeo/discarded-services (constantly []) admin/rows (fn [_ _] []) vimeo/queue (constantly [])
                vimeo/discover #(vec (for [n (range 60)] {:url (str "https://vimeo.com/" (+ n 100)) :title (str "Livestream service " n)}))]
    (let [first-page (:body (admin/imports-page {:params {}}))
          second-page (:body (admin/imports-page {:params {"page" "2"}}))]
      (is (str/includes? first-page "60 available services · page 1 of 3"))
      (is (str/includes? first-page "Livestream service 35"))
      (is (not (str/includes? first-page "Livestream service 34")))
      (is (str/includes? second-page "Livestream service 25"))
      (is (str/includes? second-page "Older services"))
      (is (str/includes? second-page "Newer services")))))

(deftest cached-archive-refresh-keeps-previous-results
  (let [dir (java.nio.file.Files/createTempDirectory "vimeo-cache-test" (make-array java.nio.file.attribute.FileAttribute 0))
        old @vimeo/discovery-cache
        gate (promise) started (promise) calls (atom 0)
        initial {:videos [{:url "https://vimeo.com/123" :title "Livestream old"}] :time 0}
        fresh [{:url "https://vimeo.com/456" :title "Livestream fresh"}]]
    (try
      (with-redefs [config/get-video-cache-dir #(str dir)
                    vimeo/fetch-catalog (fn [] (swap! calls inc) (deliver started true) @gate fresh)]
        (reset! vimeo/discovery-cache initial)
        (is (= (:videos initial) (vimeo/discover)))
        (is (true? (deref started 2000 false)))
        (is (= (:videos initial) (vimeo/discover)))
        (is (= 1 @calls))
        (deliver gate true)
        (loop [n 0] (when (and (:refreshing? (vimeo/discovery-status)) (< n 200)) (Thread/sleep 10) (recur (inc n))))
        (is (= fresh (vimeo/discover)))
        (is (.isFile (vimeo/catalog-file)))
        (reset! vimeo/discovery-cache nil)
        (is (= fresh (vimeo/discover)))
        (is (= 1 @calls))
        (with-redefs [vimeo/fetch-catalog #(throw (ex-info "offline" {}))]
          (vimeo/clear-discovery-cache!)
          (is (= fresh (vimeo/discover)))
          (loop [n 0] (when (and (:refreshing? (vimeo/discovery-status)) (< n 200)) (Thread/sleep 10) (recur (inc n))))
          (is (= fresh (vimeo/discover)))
          (is (= "offline" (:error (vimeo/discovery-status))))))
      (finally (deliver gate true) (reset! vimeo/discovery-cache old)))))

(deftest same-title-recordings-are-separate-selections
  (let [dir (java.nio.file.Files/createTempDirectory "duplicate-title-test" (make-array java.nio.file.attribute.FileAttribute 0))
        catalog [{:url "https://vimeo.com/123" :title "Livestream 08-09-26" :duration 3377}
                 {:url "https://vimeo.com/456" :title "Livestream 08-09-26" :duration 1287}]]
    (with-redefs [config/get-db-path #(str dir "/songs") vimeo/cached-catalog (constantly catalog)
                  vimeo/discover (constantly catalog)]
      (db/migrate! nil)
      (is (= 2 (vimeo/select! {"video" ["https://vimeo.com/123" "https://vimeo.com/456"]})))
      (is (= 2 (count (vimeo/queue))))
      (vimeo/select! {"links" "https://vimeo.com/456"})
      (is (= 2 (count (vimeo/queue))) "The same URL remains unique")
      (is (str/includes? (:body (admin/imports-page {:params {}})) "Duplicate title:")))))

(deftest videos-sort-by-service-date-before-upload-id
  (let [newer {:url "https://vimeo.com/100" :title "Livestream 06-07-26"}
        older-new-upload {:url "https://vimeo.com/999" :title "East White Oak Bible Church Live Stream 05-31-26"}
        date-only {:url "https://vimeo.com/50" :title "06-01-26"}
        newer-duplicate {:url "https://vimeo.com/200" :title "LIVEstream 6/7/2026"}
        unknown {:url "https://vimeo.com/500" :title "Livestream"}
        unknown-newer {:url "https://vimeo.com/600" :title "Livestream 02-30-26"}]
    (is (= [newer-duplicate newer date-only older-new-upload unknown-newer unknown]
           (vec (vimeo/newest-videos [older-new-upload unknown newer unknown-newer newer-duplicate date-only]))))))

(deftest discarded-services-persist-hide-and-can-be-restored
  (let [dir (java.nio.file.Files/createTempDirectory "discard-service-test" (make-array java.nio.file.attribute.FileAttribute 0))
        catalog [{:url "https://vimeo.com/123" :title "Livestream 08-09-26" :duration 1287}
                 {:url "https://vimeo.com/456" :title "Livestream 08-09-26" :duration 3377}]]
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")
                  vimeo/cached-catalog (constantly catalog) vimeo/discover (constantly catalog)]
      (db/migrate! nil)
      (is (thrown? clojure.lang.ExceptionInfo (vimeo/discard-service! "https://vimeo.com/123" "   ")))
      (is (empty? (vimeo/discarded-services)))
      (vimeo/discard-service! "https://vimeo.com/123" "Short duplicate recording")
      (vimeo/discard-service! "https://vimeo.com/123" "Short duplicate recording")
      (let [discarded (vimeo/discarded-services)
            body (:body (admin/imports-page {:params {}}))
            available (str/join (re-seq #"(?s)<tr[^>]*data-job-status=\"available\"[^>]*>.*?</tr>" body))]
        (is (= 1 (count discarded)))
        (is (= "Livestream 08-09-26" (:video_title (first discarded))))
        (is (= 1287 (:duration_seconds (first discarded))))
        (is (= "Short duplicate recording" (:discard_reason (first discarded))))
        (is (str/includes? body "Short duplicate recording"))
        (is (some? (:discarded_at (first discarded))))
        (is (not (str/includes? available "https://vimeo.com/123")))
        (is (str/includes? available "https://vimeo.com/456"))
        (is (str/includes? body "Discarded services · 1"))
        (is (str/includes? body "Restore")))
      (is (thrown? clojure.lang.ExceptionInfo (vimeo/select! {"links" "https://vimeo.com/123"})))
      (is (empty? (vimeo/queue)))
      (vimeo/restore-service! "https://vimeo.com/123")
      (is (empty? (vimeo/discarded-services)))
      (is (= 1 (vimeo/select! {"links" "https://vimeo.com/123"})))
      (let [id (:id (first (vimeo/queue)))]
        (sql/update! (db/get-datasource) :vimeo_import_selection {:job_status "downloading"} {:id id})
        (is (thrown? clojure.lang.ExceptionInfo (vimeo/discard-service! "https://vimeo.com/123" "Short duplicate recording")))
        (is (empty? (vimeo/discarded-services)))
        (sql/update! (db/get-datasource) :vimeo_import_selection {:job_status "failed"} {:id id})
        (vimeo/discard-service! "https://vimeo.com/123" "Short duplicate recording")
        (is (empty? (vimeo/queue)))
        (is (= 1 (count (vimeo/discarded-services))))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
