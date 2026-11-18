(ns church-song-tracking.song-prediction-test
  (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
            [church-song-tracking.song-prediction :as prediction]
            [church-song-tracking.sermon-similarity :as ai]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.config :as config] [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo]
            [next.jdbc.sql :as sql]))

(def songs [{:id 1 :name "Leader favorite" :artist "Writer"}
            {:id 2 :name "Mission song" :artist "Writer"}
            {:id 3 :name "Christmas song" :artist "Writer"}
            {:id 4 :name "New tagged song" :artist "Writer"}])
(def services [{:id 1 :song_leader_id 1 :sermon_title "Sent to the nations" :sermon_scripture "Matthew 28:16-20"}
               {:id 2 :song_leader_id 1 :sermon_title "God loves" :sermon_scripture "John 3:16"}
               {:id 3 :song_leader_id 2 :holiday_id 1 :sermon_title "Christmas" :sermon_scripture "Luke 2:1-20"}])
(def available {:leaders [{:id 1 :name "Leader"} {:id 2 :name "Other"}]
                :holidays [{:id 1 :name "Christmas"}]
                :tags [{:id 1 :name "Missions"} {:id 2 :name "Christmas"}]})
(def input {:title "" :scripture "" :tag-ids #{}})
(defn rank [options]
  (prediction/rank-songs songs services {1 #{1 2} 2 #{1} 3 #{3}} {2 #{1} 3 #{2} 4 #{1}} available (merge input options)))
(deftest ranking-signals
  (is (= 1 (:id (first (rank {:leader 1})))))
  (is (= 3 (:id (first (rank {:holiday 1})))))
  (is (= 2 (:id (first (rank {:tag-ids #{1}})))))
  (is (some #(= 4 (:id %)) (rank {:tag-ids #{1}})))
  (is (some #(= 1 (:id %)) (rank {:tag-ids #{1}})))
  (is (> (:score (first (filter #(= 2 (:id %)) (rank {:title "Sent nations"}))))
         (:score (first (filter #(= 2 (:id %)) (rank {}))))))
  (is (some #(str/includes? % "same book and chapter")
            (:reasons (first (filter #(= 2 (:id %)) (rank {:scripture "Matthew 28:1"}))))))
  (is (= 0.0 (prediction/scripture-match "John 3:16" "Luke 3:16")))
  (is (= 1.0 (prediction/scripture-match "1 John 3:16" "1 John 3:1")))
  (is (= 0.0 (prediction/scripture-match "John 3:16" "1 John 3:16")))
  (is (= 0.25 (prediction/scripture-match "John 3:16" "John 4:1")))
  (is (= [] (prediction/rank-songs songs [] {} {} available input)))
  (is (every? #(seq (:reasons %)) (rank {:leader 1 :tag-ids #{1}})))
  (is (= (rank input) (rank input))))
(deftest options-validation
  (is (= #{1 2} (:tag-ids (prediction/parse-options available {"tags" ["1" "2"]}))))
  (is (= #{1} (:tag-ids (prediction/parse-options available {"tags" "1"}))))
  (doseq [params [{"leader" "oops"} {"leader" "999"} {"holiday" "999"} {"tags" ["999"]}]]
    (is (thrown? clojure.lang.ExceptionInfo (prediction/parse-options available params)))))
(deftest database-and-page
  (let [dir (java.nio.file.Files/createTempDirectory "song-prediction" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs")]
      (db/migrate! nil)
      (let [ds (db/get-datasource)
            insert (fn [table data] (:id (sql/insert! ds table data vimeo/opts)))
            leader (insert :person {:name "Leader"})
            song (insert :song {:name "Example song" :artist "Writer"})
            variation (insert :song_variation {:song_id song :variation_name "Default" :prominent_electric_guitar false})
            service (insert :church_service {:date (java.time.LocalDate/parse "2026-01-01") :title "Sunday"
                                            :sermon_title "Sent" :song_leader_id leader :preacher_id leader})
            _ (doseq [order [1 2]] (insert :song_performance {:church_service_id service :song_variation_id variation :order_in_service order}))
            opts (prediction/options ds)
            report (prediction/report ds opts (assoc input :leader leader))]
        (let [calls (atom 0)]
          (with-redefs [ai/related (fn [_ _] (swap! calls inc) (throw (ex-info "AI unavailable" {})))]
            (is (= :off (:ai-status (prediction/report ds opts input))))
            (is (zero? @calls))
            (let [fallback (prediction/report ds opts (assoc input :ai? true :title "Shepherd"))]
              (is (= :unavailable (:ai-status fallback)))
              (is (= "AI unavailable" (:ai-error fallback)))
              (is (= 1 (count (:songs fallback)))))
            (is (= 1 @calls))))
        (is (= 1 (:service-count report)))
        (is (= 1 (count (:songs report))))
        (is (some #(str/includes? % "1 of 1") (:reasons (first (:songs report)))))
        (let [page (admin/app {:request-method :get :uri "/predictions" :query-string (str "predict=1&leader=" leader) :headers {}})]
          (is (= 200 (:status page)))
          (is (str/includes? (:body page) "Example song"))
          (is (str/includes? (:body page) "Fewer than 10")))
        (is (= 422 (:status (admin/prediction-page {:params {"leader" "invalid"}}))))
        (is (= 200 (:status (admin/prediction-page {:params {}}))))))))
(deftest top-ten-limit
  (let [catalog (mapv #(hash-map :id % :name (str "Song " %) :artist "Writer") (range 20))]
    (is (= 10 (count (prediction/rank-songs catalog [{:id 1}] {1 (set (range 20))} {} available input))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
