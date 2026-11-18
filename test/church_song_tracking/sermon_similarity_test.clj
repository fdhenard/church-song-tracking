(ns church-song-tracking.sermon-similarity-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [church-song-tracking.sermon-similarity :as ai]
            [church-song-tracking.song-prediction :as prediction]))
(deftest semantic-matches-and-cache
  (reset! ai/cache {})
  (let [calls (atom [])
        services [{:id 1 :sermon_title "God cares for his people"} {:id 2 :sermon_title "Unrelated"} {:id 3}]]
    (with-redefs [ai/request-embeddings (fn [texts] (swap! calls conj texts) (mapv #(if (.contains % "Unrelated") [0.0 1.0] [1.0 0.0]) texts))]
      (let [result (ai/related services {:title "The Good Shepherd"})]
        (is (= #{1} (set (keys result))))
        (is (= 1.0 (get-in result [1 :score])))
        (is (= result (ai/related services {:title "The Good Shepherd"})))
        (is (= 1 (count @calls)))
        (is (= 3 (count (first @calls)))))
      (ai/related [(assoc (first services) :sermon_title "Updated title")] {:title "The Good Shepherd"})
      (is (= 2 (count @calls)))))
  (is (thrown? clojure.lang.ExceptionInfo (ai/related [] {})))
  (is (= {} (ai/related [] {:title "Title"})))
  (is (= 0.0 (ai/cosine [0 0] [1 0]))))
(deftest failed-calls-are-not-cached
  (reset! ai/cache {})
  (with-redefs [ai/request-embeddings (fn [_] (throw (ex-info "Unavailable" {})))]
    (is (thrown? clojure.lang.ExceptionInfo (ai/embeddings ["Title"])))
    (is (empty? @ai/cache))))
(deftest ai-boost-has-source
  (let [songs [{:id 1 :name "Theme song" :artist "Writer"} {:id 2 :name "Favorite" :artist "Writer"}]
        services [{:id 1} {:id 2}]
        input {:title "Shepherd" :scripture "" :tag-ids #{} :related-sermons {1 {:score 0.9 :title "God cares"}}}
        ranked (prediction/rank-songs songs services {1 #{1} 2 #{2}} {} {:tags []} input)]
    (is (= 1 (:id (first ranked))))
    (is (= 1 (:related-sermon-id (first ranked))))
    (is (some #(.contains % "AI theme match") (:reasons (first ranked))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
