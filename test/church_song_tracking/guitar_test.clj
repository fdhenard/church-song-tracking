(ns church-song-tracking.guitar-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [church-song-tracking.song-variation :as variation]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.import-review :as review]))
(deftest missing-values-default-to-review
  (is (nil? (:prominent_electric_guitar (admin/parse-data "variations" {"song_id" "1" "variation_name" "Test"}))))
  (is (nil? (get-in (review/parse-form {"song-count" "1"}) [:songs 0 :variation-guitar])))
  (is (= ["review"] (for [[_ attrs] (variation/guitar-options nil) :when (:selected attrs)] (:value attrs)))))
(deftest three-states-survive-forms
  (doseq [[input expected label] [["true" true "Yes"] ["false" false "No"] ["review" nil "Review"]]]
    (is (= expected (:prominent_electric_guitar (admin/parse-data "variations"
                           {"song_id" "1" "variation_name" "Test" "prominent_electric_guitar" input}))))
    (is (= expected (get-in (review/parse-form {"song-count" "1" "song-0-variation-guitar" input}) [:songs 0 :variation-guitar])))
    (is (= label (variation/guitar-label expected))))
  (is (thrown? clojure.lang.ExceptionInfo (variation/parse-guitar "invalid"))))
(deftest migration-converts-old-false-and-defaults-to-review
  (with-open [conn (jdbc/get-connection {:dbtype "h2:mem" :dbname "guitar-migration"})]
    (jdbc/execute! conn ["create table song_variation (id int primary key, prominent_electric_guitar boolean not null default false)"])
    (jdbc/execute! conn ["insert into song_variation values (1, true), (2, false)"])
    (doseq [statement (str/split (slurp "resources/migrations/20261116000000-tristate-guitar.up.sql") #"--;;")]
      (jdbc/execute! conn [statement]))
    (jdbc/execute! conn ["insert into song_variation (id) values (3)"])
    (is (= [[1 true] [2 nil] [3 nil]]
           (rest (jdbc/execute! conn ["select * from song_variation order by id"] {:builder-fn next.jdbc.result-set/as-arrays}))))
    (jdbc/execute! conn ["update song_variation set prominent_electric_guitar=false where id=3"])
    (is (= false (get-in (jdbc/execute! conn ["select prominent_electric_guitar from song_variation where id=3"] {:builder-fn next.jdbc.result-set/as-arrays}) [1 0])))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
