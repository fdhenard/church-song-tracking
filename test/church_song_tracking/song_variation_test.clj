(ns church-song-tracking.song-variation-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.song-variation :as variation] [church-song-tracking.song-merge :as merge]
           [church-song-tracking.admin :as admin] [next.jdbc :as jdbc] [next.jdbc.sql :as sql])
 (:import [java.util.concurrent CountDownLatch TimeUnit]))
(def opts variation/opts)
(defn fixture [f]
 (let [dir (java.nio.file.Files/createTempDirectory "variation-name-test" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
   (db/migrate! nil)
   (let [ds (db/get-datasource)
         a (:id (sql/insert! ds :song {:name "Song" :artist "Author"} opts))
         b (:id (sql/insert! ds :song {:name "Other song" :artist "Author"} opts))]
    (f ds a b)))))
(defn data [song name] {:song_id song :variation_name name :example_link nil :prominent_electric_guitar false})

(deftest point-forward-creation-renames-moves-and-grandfathered-metadata
 (fixture
  (fn [ds a b]
   ;; Recreate the pre-constraint schema only for this historical-behavior fixture.
   (jdbc/execute! ds ["alter table song_variation drop constraint song_variation_song_name_unique"])
   ;; Direct SQL creates fixtures representing records already present before enforcement.
   (let [old (:id (sql/insert! ds :song_variation (data a "Default") opts))
         legacy (:id (sql/insert! ds :song_variation (assoc (data a " default ") :example_link "https://example.org/old") opts))
         other (:id (sql/insert! ds :song_variation (data b "Default") opts))]
    (is (= 2 (count (sql/find-by-keys ds :song_variation {:song_id a} opts))))
    (let [response (admin/save! {:path-params {:entity "variations" :id "new"}
                                :params {"song_id" (str a) "variation_name" "DEFAULT" "example_link" ""}})]
     (is (= 422 (:status response)))
     (is (str/includes? (:body response) "variation name already exists")))
    (is (= 303 (:status (admin/save! {:path-params {:entity "variations" :id (str old)}
                                     :params {"song_id" (str a) "variation_name" "Default" "example_link" "https://example.org/updated"}}))))
    (is (= "https://example.org/updated" (:example_link (sql/get-by-id ds :song_variation old opts))))
    (is (= " default " (:variation_name (sql/get-by-id ds :song_variation legacy opts))))
    (jdbc/with-transaction [tx ds]
     (is (thrown? clojure.lang.ExceptionInfo (variation/validate! tx other (data a "default"))))
     (is (= (data b "Default") (variation/validate! tx other (data b "Default"))))
     (is (= (data a "Acoustic") (variation/validate! tx legacy (data a "Acoustic"))))
     (sql/update! tx :song_variation {:variation_name "Acoustic"} {:id legacy})
     (is (thrown? clojure.lang.ExceptionInfo (variation/validate! tx legacy (data a "Default")))))
    (jdbc/with-transaction [tx ds]
     (variation/ensure! tx (data a "Full Band") false)
     (is (thrown? clojure.lang.ExceptionInfo (variation/validate! tx nil (data a "  full   BAND  "))))
     (is (thrown? clojure.lang.ExceptionInfo (variation/validate! tx nil (data a "full\u00a0band")))))
    (is (= "Default" (:variation_name (sql/get-by-id ds :song_variation other opts))))))))

(deftest imports-reuse-names-without-overwriting-settings
 (fixture
  (fn [ds a b]
   (let [existing (:id (sql/insert! ds :song_variation (assoc (data a "Default") :example_link "https://example.org/arrangement" :prominent_electric_guitar true) opts))]
    (jdbc/with-transaction [tx ds]
     (is (= existing (variation/ensure! tx (data a " default ") true)))
     (is (thrown? clojure.lang.ExceptionInfo (variation/ensure! tx (data a "Default") false))))
    (is (= true (:prominent_electric_guitar (sql/get-by-id ds :song_variation existing opts))))
    (is (= 1 (count (sql/find-by-keys ds :song_variation {:song_id a} opts))))))))

(deftest merges-cannot-introduce-name-collisions
 (let [target {:id 1 :song_id 10 :variation_name "Default" :example_link "one" :prominent_electric_guitar false}
       incoming {:id 2 :song_id 20 :variation_name "DEFAULT" :example_link "two" :prominent_electric_guitar false}
       legacy (assoc incoming :song_id 10)]
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rename one variation" (variation/validate-merge! [target incoming] [1 2] 10)))
  (is (nil? (variation/validate-merge! [target legacy] [1 2] 10)))
  (let [identical (assoc incoming :example_link "one") plan (merge/variation-plan [target identical] 10)]
   (is (= {2 1} (:redirects plan)))
   (is (nil? (variation/validate-merge! [target identical] (:kept plan) 10))))))

(deftest concurrent-new-variations-cannot-use-the-same-name
 (fixture
  (fn [ds a _]
   (let [start (CountDownLatch. 1)
         create (fn [name]
                  (.await start 5 TimeUnit/SECONDS)
                  (try (jdbc/with-transaction [tx ds]
                         (variation/validate! tx nil (data a name))
                         (sql/insert! tx :song_variation (data a name))
                         :created)
                       (catch clojure.lang.ExceptionInfo _ :duplicate)))
         first (future (create "New variation")) second (future (create " new  VARIATION "))]
    (.countDown start)
    (is (= #{:created :duplicate} #{(deref first 15000 :timeout) (deref second 15000 :timeout)}))
    (is (= 1 (count (sql/find-by-keys ds :song_variation {:song_id a} opts))))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
