(ns church-song-tracking.performance-field-source-test
 (:require [clojure.test :refer [deftest is run-tests]]
           [church-song-tracking.service-merge-test :as fixture]
           [church-song-tracking.service-merge :as merge]
           [church-song-tracking.db :as db] [church-song-tracking.vimeo-import :as vimeo]
           [next.jdbc.sql :as sql]))
(deftest fields-are-limited-to-the-selected-performance-merge-group
 (fixture/temporary-db
  (fn []
   (let [{:keys [a b p1 p2 p3] :as data} (fixture/fixture) ds (db/get-datasource)
         variation (:song_variation_id (sql/get-by-id ds :song_performance p1 vimeo/opts))
         extra (fixture/insert! :song_performance {:church_service_id b :song_variation_id variation :order_in_service 2})
         key1 (str "performance-" p1) key2 (str "performance-" p2)]
    (sql/update! ds :song_performance {:time_in_service_youtube "1:00"} {:id p1})
    (sql/update! ds :song_performance {:time_in_service_youtube "2:00"} {:id p2})
    (let [input (assoc (fixture/params data) (str "merge-" key2) key1
                       (str "pick-" key1 "-time_in_service_youtube") key2)]
     (is (= key2 (get-in (merge/plan ds input) [:performance-plan :fields key1 :time_in_service_youtube])))
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"selected to merge"
           (merge/plan ds (assoc input (str "pick-" key1 "-time_in_service_youtube") (str "performance-" extra)))))
     (is (thrown? clojure.lang.ExceptionInfo
           (merge/plan ds (assoc input (str "pick-" key1 "-time_in_service_youtube") (str "performance-" p3)))))
     (is (= a (merge/merge! input)))
     (is (= "2:00" (:time_in_service_youtube (sql/get-by-id ds :song_performance p1 vimeo/opts))))
     (is (nil? (sql/get-by-id ds :song_performance p2 vimeo/opts)))
     (is (some? (sql/get-by-id ds :song_performance p3 vimeo/opts))))))))
(deftest chained-performances-resolve-to-one-kept-row
 (let [rows [{:key "a"} {:key "b"} {:key "c"}]
       result (merge/performance-choices rows ["c"]
                {"merge-a" "b" "merge-b" "c" "pick-c-song" "a"})]
  (is (= {"a" "c" "b" "c"} (:redirects result)))
  (is (= ["c"] (mapv :key (:kept result))))
  (is (= "a" (get-in result [:fields "c" :song])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"form a loop"
        (merge/performance-choices rows ["c"] {"merge-a" "b" "merge-b" "a"})))
  (is (thrown? clojure.lang.ExceptionInfo
        (merge/performance-choices rows ["c"] {"merge-a" "missing"})))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
