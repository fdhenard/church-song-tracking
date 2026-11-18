(ns church-song-tracking.song-merge-variation-choice-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [church-song-tracking.song-merge-test :as fixture]
            [church-song-tracking.song-merge :as merge]
            [church-song-tracking.variation-merge :as variation-merge]
            [church-song-tracking.db :as db]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.vimeo-import :as vimeo]
            [clojure.string :as str]
            [next.jdbc.sql :as sql]))
(deftest explicitly-consolidates-different-variations-without-losing-performances
  (fixture/temporary-db
    (fn []
      (let [{:keys [a b v1 v2 v3 p1 p2 p3] :as data} (fixture/fixture)
            ds (db/get-datasource)
            input (assoc (fixture/params data)
                    (str "variation-choice-" v1) (str v3)
                    (str "variation-choice-" v2) (str v3)
                    (str "variation-choice-" v3) "keep")
            kept-before (sql/get-by-id ds :song_variation v3 vimeo/opts)
            before (sql/query ds ["select * from song_performance order by id"] vimeo/opts)
            planned (merge/plan ds input false)]
        (is (= [v3] (get-in planned [:variation-plan :kept])))
        (is (= {v1 v3 v2 v3} (get-in planned [:variation-plan :redirects])))
        (is (str/includes? (:body (admin/song-merge-confirmation {:params input} planned)) (str "variation-choice-" v1)))
        (is (thrown? clojure.lang.ExceptionInfo
              (merge/plan ds (assoc input (str "variation-choice-" v3) (str v1)) false)))
        (is (thrown? clojure.lang.ExceptionInfo
              (merge/plan ds (assoc input (str "variation-choice-" v1) "999999") false)))
        (is (= a (merge/merge! input)))
        (is (nil? (sql/get-by-id ds :song b vimeo/opts)))
        (is (nil? (sql/get-by-id ds :song_variation v1 vimeo/opts)))
        (is (nil? (sql/get-by-id ds :song_variation v2 vimeo/opts)))
        (is (= a (:song_id (sql/get-by-id ds :song_variation v3 vimeo/opts))))
        (is (= (select-keys kept-before [:variation_name :example_link :prominent_electric_guitar])
               (select-keys (sql/get-by-id ds :song_variation v3 vimeo/opts) [:variation_name :example_link :prominent_electric_guitar])))
        (let [after (sql/query ds ["select * from song_performance order by id"] vimeo/opts)]
          (is (= (mapv #(dissoc % :song_variation_id) before) (mapv #(dissoc % :song_variation_id) after)))
          (is (every? #(= v3 (:song_variation_id %)) after)))
        (is (= v3 (variation-merge/canonical-id ds v1)))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
