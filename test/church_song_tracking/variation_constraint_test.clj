(ns church-song-tracking.variation-constraint-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.song-variation :as variation] [church-song-tracking.admin :as admin]
           [next.jdbc :as jdbc] [next.jdbc.sql :as sql])
 (:import [java.util.concurrent CountDownLatch TimeUnit]))
(def opts variation/opts)
(defn fixture [f]
 (let [dir (java.nio.file.Files/createTempDirectory "variation-constraint-test" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
   (db/migrate! nil)
   (with-open [connection (jdbc/get-connection (db/get-datasource))]
    (let [ds (db/get-datasource)
          a (:id (sql/insert! ds :song {:name "Song A" :artist "Author"} opts))
          b (:id (sql/insert! ds :song {:name "Song B" :artist "Author"} opts))]
     (f ds a b))))))
(defn create! [ds song name] (:id (sql/insert! ds :song_variation {:song_id song :variation_name name} opts)))
(defn sql-state [f] (try (f) :unexpected-success (catch java.sql.SQLException e (.getSQLState e))))

(deftest h2-enforces-normalized-names-without-app-validation
 (fixture
  (fn [ds a b]
   (let [original (create! ds a "  Full   Band ")
         other (create! ds b "Full Band")
         acoustic (create! ds a "Acoustic")]
    (is (= "full band" (:variation_name_key (sql/get-by-id ds :song_variation original opts))))
    (is (= "  Full   Band " (:variation_name (sql/get-by-id ds :song_variation original opts))))
    (doseq [name ["Full Band" "FULL BAND" "full\tband" "full\u00a0band" "ＦＵＬＬ　ＢＡＮＤ"]]
     (is (= "23505" (sql-state #(create! ds a name)))))
    (is (= "23505" (sql-state #(sql/update! ds :song_variation {:variation_name "full band"} {:id acoustic}))))
    (is (= "23505" (sql-state #(sql/update! ds :song_variation {:song_id a} {:id other}))))
    (is (not= :unexpected-success (sql-state #(sql/update! ds :song_variation {:variation_name_key "bypass"} {:id original}))))
    (is (= 3 (count (sql/query ds ["select * from song_variation"] opts))))
    (is (= 1 (count (sql/query ds ["select * from information_schema.table_constraints where constraint_name='SONG_VARIATION_SONG_NAME_UNIQUE' and constraint_type='UNIQUE'"] opts))))
    (doseq [name ["Default" " \t FULL   band \n" "Ｆｕｌｌ　Ｂａｎｄ" "Cafe\u0301" "\u00a0Acoustic\u00a0"]]
     (is (= (variation/name-key name) (:k (first (sql/query ds ["select CST_VARIATION_NAME_KEY(?) as k" name] opts))))))
    ;; Even if UI validation is bypassed, the constraint is translated to a clear error.
    (with-redefs [variation/validate! (fn [& _] nil)]
     (let [response (admin/save! {:path-params {:entity "variations" :id "new"}
                                 :params {"song_id" (str a) "variation_name" "full band" "example_link" ""}})]
      (is (= 422 (:status response)))
      (is (str/includes? (:body response) "variation name already exists"))))))))

(deftest direct-concurrent-writes-cannot-bypass-the-unique-constraint
 (fixture
  (fn [ds a _]
   (let [start (CountDownLatch. 1)
         attempt (fn [name]
                   (.await start 5 TimeUnit/SECONDS)
                   (try (jdbc/with-transaction [tx ds] (create! tx a name) :created)
                        (catch java.sql.SQLException e (if (= "23505" (.getSQLState e)) :duplicate (throw e)))))
         one (future (attempt "Default")) two (future (attempt " default "))]
    (.countDown start)
    (is (= #{:created :duplicate} #{(deref one 15000 :timeout) (deref two 15000 :timeout)}))
    (is (= 1 (count (sql/query ds ["select * from song_variation"] opts))))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
