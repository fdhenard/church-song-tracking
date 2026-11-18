(ns church-song-tracking.service-merge-test
 (:require [clojure.test :refer [deftest is run-tests]]
           [clojure.string :as str] [clojure.java.io :as io] [clojure.edn :as edn]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.service-merge :as merge] [church-song-tracking.admin :as admin]
           [church-song-tracking.import-review :as review] [church-song-tracking.vimeo-import :as vimeo]
           [next.jdbc :as jdbc] [next.jdbc.sql :as sql]))
(defn insert! [table data] (:id (sql/insert! (db/get-datasource) table data vimeo/opts)))
(defn temporary-db [f]
 (let [dir (java.nio.file.Files/createTempDirectory "service-merge-test" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")
                config/get-slide-archive-dir #(str dir "/slides")]
   (db/migrate! nil)
   (with-open [keeper (jdbc/get-connection (db/get-datasource))] (f)))))
(defn fixture []
 (let [person (insert! :person {:name "Library person"})
       service {:date (java.time.LocalDate/parse "2026-08-09") :title "Spreadsheet service"
                :sermon_title "Library sermon" :preacher_id person :song_leader_id person}
       a (insert! :church_service (assoc service :link_vimeo "https://vimeo.com/111" :vimeo_video_title "First video"))
       b (insert! :church_service (assoc service :title "Video service" :sermon_title "Video sermon" :link_vimeo "https://vimeo.com/222" :vimeo_video_title "Second video"))
       c (insert! :church_service (assoc service :title "Separate evening service"))
       song (insert! :song {:name "Song" :artist "Author"})
       variation (insert! :song_variation {:song_id song :variation_name "Default"})
       performance #(insert! :song_performance {:church_service_id % :song_variation_id variation :order_in_service 1
                                                :time_in_service_vimeo "1:05" :source_data "Original spreadsheet evidence"})
       p1 (performance a) p2 (performance b) p3 (performance c)]
  {:a a :b b :c c :p1 p1 :p2 p2 :p3 p3}))
(defn params [{:keys [a b p1 p2]}]
 (let [ds (db/get-datasource) base {"group" (str a)} data (merge/context ds base)]
  (assoc base "version" (merge/fingerprint data) "source" [(str a) (str b)] "retained" (str a)
    "field-sermon_title" (str b) "performance" [(str "performance-" p1) (str "performance-" p2)]
    (str "order-performance-" p1) "1" (str "order-performance-" p2) "2" "confirm" "yes")))
(deftest combines-library-records-preserves-provenance-and-old-links
 (temporary-db
  (fn []
   (let [{:keys [a b c p1 p2 p3] :as data} (fixture) ds (db/get-datasource) input (params data)
         before (sql/query ds ["select * from song_performance order by id"] vimeo/opts)
         planned (merge/plan ds input)]
    (is (= 1 (count (merge/groups ds))))
    (is (= "Video sermon" (get-in planned [:final :sermon_title])))
    (is (= 200 (:status (admin/app {:request-method :get :uri "/services/merge" :headers {}}))))
    (is (str/includes? (:body (admin/service-merge-page {:params {"group" (str a)}})) "Song performances to keep"))
    (is (str/includes? (:body (admin/service-merge-confirmation {:params input} planned)) "Accept service merge"))
    (is (= 3 (count (sql/query ds ["select * from church_service"] vimeo/opts))) "Preview never changes library")
    (is (= a (merge/merge! input)))
    (is (= "Video sermon" (:sermon_title (sql/get-by-id ds :church_service a vimeo/opts))))
    (is (nil? (sql/get-by-id ds :church_service b vimeo/opts)))
    (is (some? (sql/get-by-id ds :church_service c vimeo/opts)))
    (is (= a (:church_service_id (sql/get-by-id ds :song_performance p2 vimeo/opts))))
    (is (= c (:church_service_id (sql/get-by-id ds :song_performance p3 vimeo/opts))))
    (let [after (sql/query ds ["select * from song_performance order by id"] vimeo/opts)]
     (is (= (mapv #(dissoc % :church_service_id :order_in_service) before)
            (mapv #(dissoc % :church_service_id :order_in_service) after))))
    (is (= a (merge/canonical-id ds b)))
    (is (= (str "/services/" a) (get-in (admin/edit-page {:path-params {:entity "services" :id (str b)}}) [:headers "Location"])))
    (is (contains? (vimeo/imported-video-urls (vimeo/imported-recordings ds)) "https://vimeo.com/222"))
    (is (= 2 (count (sql/query ds ["select * from service_merge_source"] vimeo/opts))))
    (is (thrown? clojure.lang.ExceptionInfo (merge/merge! input)))))))
(deftest explicit-performance-removal-and-order-validation
 (temporary-db
  (fn []
   (let [{:keys [a p1 p2] :as data} (fixture) ds (db/get-datasource) input (params data)]
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input (str "order-performance-" p2) "1"))))
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input "performance" []))))
    (is (thrown? clojure.lang.ExceptionInfo (merge/merge! (dissoc input "confirm"))))
    (let [input (assoc input "performance" [(str "performance-" p2)] (str "order-performance-" p2) "1")]
     (is (= 1 (count (:removed (merge/plan ds input)))))
     (merge/merge! input)
     (is (nil? (sql/get-by-id ds :song_performance p1 vimeo/opts)))
     (is (some? (sql/get-by-id ds :song_performance p2 vimeo/opts)))
     (let [event (first (sql/query ds ["select * from service_merge_event"] vimeo/opts))]
      (is (seq (:performances (edn/read-string (:before_data event)))))
      (is (= 1 (count (:removed (edn/read-string (:after_data event))))))))))))
(deftest rejects-stale-data-and-rolls-back-failure
 (temporary-db
  (fn []
   (let [{:keys [b] :as data} (fixture) ds (db/get-datasource) input (params data)
         before (merge/context ds input) real-delete sql/delete!]
    (with-redefs [sql/delete! (fn [tx table & args]
                              (if (= table :church_service) (throw (java.sql.SQLException. "Simulated failure"))
                                  (apply real-delete tx table args)))]
     (is (thrown? java.sql.SQLException (merge/merge! input))))
    (is (= before (merge/context ds input)))
    (is (empty? (sql/query ds ["select * from service_merge_event"] vimeo/opts)))
    (sql/update! ds :church_service {:title "Concurrent edit"} {:id b})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed" (merge/merge! input)))))))
(def draft {:date "2026-08-09" :title "Incoming worship" :leader "Incoming leader" :preacher "Incoming preacher"
            :sermon-title "Incoming sermon" :songs [{:title "Song" :author "Author" :order 1 :slide "" :start "2:00"}]})
(deftest reconciles-pending-import-with-library-in-one-transaction
 (temporary-db
  (fn []
   (let [{:keys [a p1] :as data} (fixture) ds (db/get-datasource)]
    (vimeo/select! {"links" "https://vimeo.com/333"})
    (let [selection (:id (first (vimeo/queue)))]
     (review/stage! selection 0 (assoc draft :leader "" :preacher ""))
     (is (thrown? clojure.lang.ExceptionInfo (review/approve! selection 1)) "Ordinary approval still rejects duplicate dates")
     (let [base {"selection" (str selection)} snapshot (merge/context ds base)
           input (assoc base "version" (merge/fingerprint snapshot) "source" [(str a) "incoming"] "retained" (str a)
                   "field-sermon_title" "incoming" "field-preacher_id" (str a) "field-link_vimeo" "incoming"
                   "performance" ["incoming-0"] "order-incoming-0" "1" "confirm" "yes")]
      (is (= a (merge/merge! input)))
      (is (= a (:imported_service_id (review/selection selection))))
      (is (= "Incoming sermon" (:sermon_title (sql/get-by-id ds :church_service a vimeo/opts))))
      (is (= 3 (count (sql/query ds ["select * from church_service"] vimeo/opts))) "Temporary incoming service is removed")
      (is (nil? (sql/get-by-id ds :song_performance p1 vimeo/opts)))
      (let [p (first (sql/query ds ["select * from song_performance where church_service_id=?" a] vimeo/opts))]
       (is (= "2:00" (:song_start_time_vimeo p)))
       (is (= "https://vimeo.com/333" (:vimeo-url (edn/read-string (:source_data p))))))))))))
(defn pending-input [a selection]
 (let [base {"selection" (str selection)} snapshot (merge/context (db/get-datasource) base)]
  (assoc base "version" (merge/fingerprint snapshot) "source" [(str a) "incoming"] "retained" "incoming"
         "performance" ["incoming-0"] "order-incoming-0" "1" "confirm" "yes")))
(deftest keeps-incoming-row-and-preserves-images-before-cleaning-cache
 (temporary-db
  (fn []
   (let [{:keys [a]} (fixture) ds (db/get-datasource)]
    (vimeo/select! {"links" "https://vimeo.com/333"})
    (let [selection (:id (first (vimeo/queue)))
          folder (io/file (config/get-video-cache-dir) (str selection) "frames")]
     (.mkdirs folder)
     (doseq [index [2 3]] (spit (io/file folder (format "frame-%06d.jpg" index)) "synthetic image bytes"))
     (review/stage! selection 0 (assoc draft :sermon-evidence-index 2 :sermon-time "0:06"
                                     :songs [(assoc (first (:songs draft)) :evidence-index 1 :slide "0:03")]))
     (let [input (pending-input a selection)
           ;; Keep the incoming row but choose the library sermon fields without an image.
           input (assoc input "field-sermon_title" (str a) "field-sermon_title_image_path" (str a))
           target (merge/merge! input)
           sources (sql/query ds ["select * from service_merge_source where church_service_id=?" target] vimeo/opts)
           archived (first (filter #(= "https://vimeo.com/333" (:vimeo_url %)) sources))]
      (is (not= a target))
      (is (= target (merge/canonical-id ds a)))
      (is (= target (:imported_service_id (review/selection selection))))
      (is (= "Library sermon" (:sermon_title (sql/get-by-id ds :church_service target vimeo/opts))))
      (is (.isFile (io/file (:sermon_title_image_path archived))))
      (is (= "cleaned" (:cache_cleanup_status (review/selection selection))))
      (is (not (.exists (.getParentFile folder))))
      (is (= 200 (:status (admin/service-merge-image-response {:path-params {:entity "services" :id (str target) :frame (str (:id archived))}}))))))))))
(deftest pending-approval-is-rolled-back-if-reconciliation-fails
 (temporary-db
  (fn []
   (let [{:keys [a]} (fixture) ds (db/get-datasource)]
    (vimeo/select! {"links" "https://vimeo.com/333"})
    (let [selection (:id (first (vimeo/queue))) real-delete sql/delete!]
     (review/stage! selection 0 draft)
     (let [input (pending-input a selection) before (merge/context ds input)]
      (with-redefs [sql/delete! (fn [tx table & args]
                                (if (= table :church_service) (throw (java.sql.SQLException. "Simulated failure"))
                                    (apply real-delete tx table args)))]
       (is (thrown? java.sql.SQLException (merge/merge! input))))
      (is (= before (merge/context ds input)))
      (is (nil? (:imported_service_id (review/selection selection))))
      (is (empty? (sql/query ds ["select * from service_merge_event"] vimeo/opts)))))))))
(deftest subsequent-merges-follow-aliases-and-reference-updates
 (temporary-db
  (fn []
   (let [{:keys [a b c p1 p2 p3] :as data} (fixture) ds (db/get-datasource)]
    (merge/merge! (params data))
    (let [base {"group" (str a)} snapshot (merge/context ds base)
          input (assoc base "version" (merge/fingerprint snapshot) "source" [(str a) (str c)] "retained" (str c)
                       "performance" (mapv #(str "performance-" %) [p1 p2 p3])
                       "confirm" "yes")
          input (reduce (fn [m [i id]] (assoc m (str "order-performance-" id) (str (inc i)))) input (map-indexed vector [p1 p2 p3]))]
     (is (= c (merge/merge! input)))
     (is (= c (merge/canonical-id ds a)))
     (is (= c (merge/canonical-id ds b)))
     (is (= 3 (count (sql/query ds ["select * from song_performance where church_service_id=?" c] vimeo/opts)))))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
