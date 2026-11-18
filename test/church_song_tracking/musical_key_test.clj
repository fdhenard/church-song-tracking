(ns church-song-tracking.musical-key-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [church-song-tracking.musical-key :as key]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.import-review :as review]))
(deftest keys-are-optional-and-validated
  (is (= 12 (count key/choices)))
  (doseq [v [nil "" "  "]] (is (nil? (key/parse-key v))))
  (doseq [[v _] key/choices]
    (is (= v (:musical_key (admin/parse-data "performances"
                    {"church_service_id" "1" "song_variation_id" "1" "order_in_service" "1" "musical_key" v})))))
  (doseq [[flat sharp] [["Db" "C#"] ["Eb" "D#"] ["Gb" "F#"] ["Ab" "G#"] ["Bb" "A#"]]]
    (is (= sharp (key/parse-key flat))))
  (doseq [v ["H" "Am" "C##"]] (is (thrown? clojure.lang.ExceptionInfo (key/parse-key v)))))
(deftest database-default-and-constraint
  (with-open [conn (jdbc/get-connection {:dbtype "h2:mem" :dbname "musical-key-test"})]
    (jdbc/execute! conn ["create table song_performance (id int primary key)"])
    (jdbc/execute! conn ["insert into song_performance values (1)"])
    (jdbc/execute! conn [(slurp "resources/migrations/20261116000001-performance-key.up.sql")])
    (is (nil? (:musical_key (first (jdbc/execute! conn ["select * from song_performance"] {:builder-fn rs/as-unqualified-lower-maps})))))
    (doseq [[v _] key/choices] (jdbc/execute! conn ["update song_performance set musical_key=?" v]))
    (is (thrown? java.sql.SQLException (jdbc/execute! conn ["update song_performance set musical_key='H'"])))
    (jdbc/execute! conn ["insert into song_performance (id) values (2)"])
    (is (nil? (:musical_key (first (jdbc/execute! conn ["select * from song_performance where id=2"] {:builder-fn rs/as-unqualified-lower-maps})))))))
(deftest variation-keys-default-and-round-trip
  (is (nil? (:musical_key (admin/parse-data "variations" {"song_id" "1" "variation_name" "Default"}))))
  (doseq [[v _] key/choices]
    (is (= v (:musical_key (admin/parse-data "variations" {"song_id" "1" "variation_name" "Default" "musical_key" v}))))
    (let [song (get-in (review/parse-form {"song-count" "1" "song-0-variation-name" "Test" "song-0-variation-key" v}) [:songs 0])]
      (is (= v (:variation-key song)))
      (is (= v (:musical_key (review/review-variation nil nil song))))))
  (with-open [conn (jdbc/get-connection {:dbtype "h2:mem" :dbname "variation-key-test"})]
    (jdbc/execute! conn ["create table song_variation (id int primary key)"])
    (jdbc/execute! conn ["insert into song_variation values (1)"])
    (jdbc/execute! conn [(slurp "resources/migrations/20261116000002-variation-key.up.sql")])
    (is (nil? (:musical_key (first (jdbc/execute! conn ["select * from song_variation"] {:builder-fn rs/as-unqualified-lower-maps})))))
    (is (thrown? java.sql.SQLException (jdbc/execute! conn ["update song_variation set musical_key='H'"])))))
(let [r (run-tests)] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))
