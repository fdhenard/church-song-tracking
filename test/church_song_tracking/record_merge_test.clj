(ns church-song-tracking.record-merge-test
 (:require [clojure.test :refer [deftest is run-tests]] [next.jdbc :as jdbc] [next.jdbc.sql :as sql]
           [church-song-tracking.db :as db] [church-song-tracking.config :as config]
           [church-song-tracking.import-review :as review]
           [church-song-tracking.record-merge :as merge] [church-song-tracking.admin :as admin]
           [church-song-tracking.vimeo-import :as vimeo]))
(defn insert! [ds table data] (:id (sql/insert! ds table data vimeo/opts)))
(defn fixture [f]
 (let [dir (java.nio.file.Files/createTempDirectory "record-merge-test" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs")
                config/get-video-cache-dir #(str dir "/cache")
                config/get-slide-archive-dir #(str dir "/slides")]
   (db/migrate! nil)
   (with-open [ds (jdbc/get-connection (db/get-datasource))]
    (let [a (insert! ds :person {:name "Pastor One"}) b (insert! ds :person {:name "P. One"}) c (insert! ds :person {:name "Leader"})
          x (insert! ds :sermon_series {:name "Gospel"}) y (insert! ds :sermon_series {:name "The Gospel"})
          service (fn [date preacher leader series] (insert! ds :church_service {:date (java.time.LocalDate/parse date) :title date :sermon_title "Sermon" :preacher_id preacher :song_leader_id leader :sermon_series_id series}))
          s1 (service "2026-01-01" a b x) s2 (service "2026-01-02" b c y)]
      (f ds {:a a :b b :c c :x x :y y :s1 s1 :s2 s2}))))))
(defn params [ds kind ids kept name]
 (let [p {"source" (mapv str ids) "keep" (str kept) "name" name "confirm" "yes"}]
  (assoc p "snapshot" (:snapshot (merge/plan ds kind p)))))
(deftest merge-people-updates-both-roles-and-keeps-aliases
 (fixture (fn [ds {:keys [a b c s1 s2]}]
  (let [p (params ds "people" [a b] a "Pastor Canonical")]
   (is (= 2 (count (:affected (merge/plan ds "people" p)))))
   (is (= (:snapshot (merge/plan ds "people" p)) (:snapshot (merge/plan ds "people" p))))
   (is (thrown? clojure.lang.ExceptionInfo (merge/merge! "people" (dissoc p "confirm"))))
   (is (= a (merge/merge! "people" p)))
   (is (nil? (sql/get-by-id ds :person b vimeo/opts)))
   (is (= "Pastor Canonical" (:name (sql/get-by-id ds :person a vimeo/opts))))
   (is (= a (:song_leader_id (sql/get-by-id ds :church_service s1 vimeo/opts))))
   (is (= a (:preacher_id (sql/get-by-id ds :church_service s2 vimeo/opts))))
   (is (= c (:song_leader_id (sql/get-by-id ds :church_service s2 vimeo/opts))))
   (is (= a (merge/canonical-id ds "people" b)))
   (is (= a (merge/find-name-id ds "people" " P. ONE ")))
   (is (= a (merge/find-name-id ds "people" "Pastor One")))
   (is (= 200 (:status (admin/record-merge-page {:path-params {:entity "people"}}))))
   (is (= c (merge/merge! "people" (params ds "people" [a c] c "Leader"))))
   (is (= c (merge/canonical-id ds "people" b)))
   (is (= c (merge/find-name-id ds "people" "P. One")))
   (vimeo/select! {"links" "https://vimeo.com/123"})
   (let [id (:id (first (vimeo/queue)))
         _ (review/stage! id 0 {:date "2026-01-03" :title "Later import" :preacher "P. One" :leader "Pastor One"
                               :sermon-title "Later sermon" :songs [{:title "Song" :author "Writer" :slide "1:00"}]})
         service-id (review/approve! id 1)
         service (sql/get-by-id ds :church_service service-id vimeo/opts)]
     (is (= c (:preacher_id service)))
     (is (= c (:song_leader_id service)))
     (is (= 1 (count (sql/query ds ["select * from person"] vimeo/opts)))))))))
(deftest merge-series-preview-rejects-changes-and-moves-all-services
 (fixture (fn [ds {:keys [x y s1 s2]}]
  (let [p (params ds "series" [x y] x "Gospel")]
   (sql/update! ds :church_service {:title "Changed"} {:id s1})
   (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed" (merge/merge! "series" p)))
   (is (some? (sql/get-by-id ds :sermon_series y vimeo/opts)))
   (is (= x (merge/merge! "series" (params ds "series" [x y] x "Gospel"))))
   (is (nil? (sql/get-by-id ds :sermon_series y vimeo/opts)))
   (is (= x (:sermon_series_id (sql/get-by-id ds :church_service s1 vimeo/opts))))
   (is (= x (:sermon_series_id (sql/get-by-id ds :church_service s2 vimeo/opts))))
   (is (= x (merge/find-name-id ds "series" "The Gospel")))
   (is (= 1 (count (sql/query ds ["select * from record_merge_event"] vimeo/opts))))
   (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds "series" {"source" [(str x)] "keep" (str x)})))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
