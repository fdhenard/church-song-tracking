(ns church-song-tracking.variation-merge-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.song-merge :as song-merge]
           [church-song-tracking.variation-merge :as merge]
           [church-song-tracking.admin :as admin]
           [next.jdbc.sql :as sql]))
(def opts merge/opts)
(defn insert! [table data] (:id (sql/insert! (db/get-datasource) table data opts)))
(defn fixture [f]
 (let [dir (java.nio.file.Files/createTempDirectory "variation-merge-test" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
   (db/migrate! nil)
   ;; Historical merge fixtures intentionally recreate the database before its final unique constraint.
   (sql/query (db/get-datasource) ["alter table song_variation drop constraint song_variation_song_name_unique"])
   (let [ds (db/get-datasource)
         a (insert! :song {:name "Song" :artist "Author"}) b (insert! :song {:name "Song" :artist "Other author"})
         v1 (insert! :song_variation {:song_id a :variation_name "Default" :example_link "https://example.org/first" :prominent_electric_guitar false})
         v2 (insert! :song_variation {:song_id a :variation_name " default " :example_link "https://example.org/second" :prominent_electric_guitar true})
         v3 (insert! :song_variation {:song_id a :variation_name "DEFAULT" :prominent_electric_guitar false})
         unique (insert! :song_variation {:song_id a :variation_name "Acoustic" :prominent_electric_guitar false})
         other (insert! :song_variation {:song_id b :variation_name "Default" :prominent_electric_guitar false})
         person (insert! :person {:name "Person"})
         service (insert! :church_service {:date (java.time.LocalDate/parse "2026-01-04") :title "Service" :preacher_id person :song_leader_id person :sermon_title "Sermon"})]
    (doseq [[index id] (map-indexed vector [v1 v2 v3 unique other])]
     (insert! :song_performance {:song_variation_id id :church_service_id service :order_in_service (inc index)
                                :time_in_service_vimeo "1:00" :song_start_time_vimeo "1:02"
                                :title_image_path "/permanent/frame.jpg" :source_data "original source notes"}))
    (f ds {:a a :b b :v1 v1 :v2 v2 :v3 v3 :unique unique :other other})))))
(defn params [ds {:keys [a v1 v2 v3]}]
 {"group" (str v1) "song" (str a) "candidate" (mapv str [v1 v2 v3]) "source" (mapv str [v1 v2 v3])
  "retained" (str v1) "variation_name" "Default" "example_link" "" "guitar" "no"
  "version" (merge/fingerprint (merge/snapshot ds a false)) "confirm" "yes"})

(deftest combines-fields-and-preserves-all-performance-details
 (fixture
  (fn [ds {:keys [a v1 v2 v3 unique other] :as data}]
   (let [before (sql/query ds ["select * from song_performance order by id"] opts)
         input (assoc (params ds data) "source" (mapv str [v1 v2]) "variation_name" "Main arrangement"
                      "example_link" "https://example.org/final" "guitar" "yes")
         planned (merge/plan ds input false)]
    (is (= 1 (count (merge/groups ds nil))))
    (is (= 200 (:status (admin/app {:request-method :get :uri "/variations/merge" :headers {}}))))
    (is (str/includes? (:body (admin/variation-merge-confirmation {:params input} planned)) "Confirm and merge variations"))
    (is (thrown? clojure.lang.ExceptionInfo (merge/merge! (dissoc input "confirm"))))
    (is (= v1 (merge/merge! input)))
    (is (nil? (sql/get-by-id ds :song_variation v2 opts)))
    (is (some? (sql/get-by-id ds :song_variation v3 opts)))
    (is (some? (sql/get-by-id ds :song_variation unique opts)))
    (is (some? (sql/get-by-id ds :song_variation other opts)))
    (let [final (sql/get-by-id ds :song_variation v1 opts)]
     (is (= "Main arrangement" (:variation_name final)))
     (is (= "https://example.org/final" (:example_link final)))
     (is (:prominent_electric_guitar final)))
    (is (= v1 (merge/canonical-id ds v2)))
    (is (= (str "/variations/" v1) (get-in (admin/edit-page {:path-params {:entity "variations" :id (str v2)}}) [:headers "Location"])))
    (let [after (sql/query ds ["select * from song_performance order by id"] opts)]
     (is (= 5 (count after)))
     (is (= (mapv #(dissoc % :song_variation_id) before) (mapv #(dissoc % :song_variation_id) after)))
     (is (= [v1 v1 v3 unique other] (mapv :song_variation_id after))))
    (is (= 1 (count (sql/query ds ["select * from variation_merge_event"] opts))))))))

(deftest rejects-collisions-stale-forms-and-cross-song-selections
 (fixture
  (fn [ds {:keys [a v1 v2 v3 other] :as data}]
   (let [input (params ds data)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Include all" (merge/plan ds (assoc input "source" (mapv str [v1 v2])) false)))
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input "variation_name" "Acoustic") false)))
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input "candidate" (mapv str [v1 other]) "source" (mapv str [v1 other])) false)))
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input "example_link" "javascript:bad") false)))
    (sql/update! ds :song_variation {:example_link "https://example.org/changed"} {:id v2})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed" (merge/merge! input)))
    (is (= 5 (count (sql/query ds ["select * from song_variation"] opts))))
    (is (empty? (sql/query ds ["select * from variation_merge_event"] opts)))))))

(deftest old-variation-links-follow-subsequent-song-merges
 (fixture
  (fn [ds {:keys [a b v1 v2 v3 other] :as data}]
   (is (= v1 (merge/merge! (params ds data))))
   (is (empty? (merge/groups ds a)))
   (sql/query ds ["alter table song_variation add constraint song_variation_song_name_unique unique (song_id, variation_name_key)"])
   (let [songs (song-merge/candidates ds a)
         input {"candidate" (mapv (comp str :id) songs) "source" (mapv str [a b]) "retained" (str b)
                "name" "Song" "artist" "Final author" "confirm" "yes" "version" (song-merge/version ds songs)}]
    (is (= b (song-merge/merge! input)))
    (is (= other (merge/canonical-id ds v1)))
    (is (= other (merge/canonical-id ds v2)))
    (is (= other (merge/canonical-id ds v3)))
    (is (= 5 (count (sql/query ds ["select * from song_performance"] opts))))))))

(deftest failed-write-rolls-back-merge-history-and-reference-moves
 (fixture
  (fn [ds data]
   (let [input (params ds data) before (sql/query ds ["select * from song_performance order by id"] opts)
         real-delete sql/delete!]
    (with-redefs [sql/delete! (fn [connection table & args]
                              (if (= :song_variation table) (throw (java.sql.SQLException. "Simulated failure"))
                                (apply real-delete connection table args)))]
     (is (thrown? java.sql.SQLException (merge/merge! input))))
    (is (= before (sql/query ds ["select * from song_performance order by id"] opts)))
    (is (= 5 (count (sql/query ds ["select * from song_variation"] opts))))
    (is (empty? (sql/query ds ["select * from variation_merge_event"] opts)))
    (is (empty? (sql/query ds ["select * from variation_merge_alias"] opts)))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
