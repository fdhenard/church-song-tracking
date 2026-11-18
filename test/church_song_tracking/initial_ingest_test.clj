(ns church-song-tracking.initial-ingest-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.initial-ingest :as ingest]
            [next.jdbc :as jdbc]))

(deftest repository-import
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "song-import-test"
                     (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [config/get-db-path #(str dir "/songs")]
      (is (= {:imported 40} (ingest/initial-ingest {:path "resources/Church Songs Played - Sheet1.csv"})))
      (is (= {:skipped 40} (ingest/initial-ingest {:path "resources/Church Songs Played - Sheet1.csv"})))
      (let [ds (db/get-datasource)]
        (is (= 11 (:N (jdbc/execute-one! ds ["select count(*) as n from church_service"]))))
        (is (= 40 (:N (jdbc/execute-one! ds ["select count(*) as n from song_performance where source_data is not null"]))))
        (is (= 1 (:N (jdbc/execute-one! ds ["select count(*) as n from church_service where interest_for_next_mens_retreat = true"]))))))))

(let [result (run-tests)]
  (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
