(ns church-song-tracking.song-merge-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.song-merge :as merge] [church-song-tracking.admin :as admin]
           [next.jdbc :as jdbc] [next.jdbc.sql :as sql] [church-song-tracking.vimeo-import :as vimeo]))

(defn insert! [table data] (:id (sql/insert! (db/get-datasource) table data vimeo/opts)))
(defn temporary-db [f]
 (let [dir (java.nio.file.Files/createTempDirectory "song-merge-test" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
   (db/migrate! nil) (with-open [keeper (jdbc/get-connection (db/get-datasource))] (f)))))
(defn fixture []
 (let [a (insert! :song {:name "Amazing Grace" :artist "John Newton"})
       b (insert! :song {:name " amazing,   grace! " :artist "Newton, John"})
       c (insert! :song {:name "Amazing Grace" :artist "Different composition author"})
       leader (insert! :person {:name "Leader"})
       service (insert! :church_service {:date (java.time.LocalDate/parse "2026-01-04") :title "Worship"
                                        :preacher_id leader :song_leader_id leader :sermon_title "Sermon"})
       v1 (insert! :song_variation {:song_id a :variation_name "Default" :prominent_electric_guitar false})
       v2 (insert! :song_variation {:song_id b :variation_name "Default" :prominent_electric_guitar false})
       v3 (insert! :song_variation {:song_id b :variation_name "Acoustic" :example_link "https://example.org/arrangement" :prominent_electric_guitar true})
       p1 (insert! :song_performance {:song_variation_id v1 :church_service_id service :order_in_service 1 :time_in_service_vimeo "01:00" :title_image_path "/permanent/example.jpg" :source_data "original OCR evidence"})
       p2 (insert! :song_performance {:song_variation_id v2 :church_service_id service :order_in_service 2 :time_in_service_vimeo "04:00"})
       p3 (insert! :song_performance {:song_variation_id v3 :church_service_id service :order_in_service 3 :song_start_time_vimeo "07:00"})]
  {:a a :b b :c c :v1 v1 :v2 v2 :v3 v3 :p1 p1 :p2 p2 :p3 p3 :service service}))
(defn params [{:keys [a b]}]
 (let [ds (db/get-datasource) songs (merge/candidates ds a)]
  {"candidate" (mapv (comp str :id) songs) "source" [(str a) (str b)] "group" (str a) "retained" (str a)
   "name" "Amazing Grace" "artist" "John Newton" "version" (merge/version ds songs) "confirm" "yes"}))

(deftest duplicate-song-page-renders-without-matches
 (temporary-db
  (fn []
   (let [response (admin/app {:request-method :get :uri "/songs/merge" :headers {}})]
    (is (= 200 (:status response)))
    (is (str/includes? (:body response) "No matching duplicate song titles."))))))

(deftest preserves-references-and-consolidates-only-identical-variations
 (temporary-db
  (fn []
   (let [{:keys [a b c v1 v2 v3 p1 p2 p3] :as data} (fixture)
         ds (db/get-datasource) input (params data)
         before (sql/query ds ["select * from song_performance order by id"] vimeo/opts)
         planned (merge/plan ds input false)]
    (is (= 1 (count (merge/groups ds))))
    (is (= 3 (count (:songs (merge/snapshot ds [a b c] false)))))
    (is (= {v2 v1} (get-in planned [:variation-plan :redirects])))
    (is (= 3 (count (:performances planned))))
    (is (= 200 (:status (admin/app {:request-method :get :uri "/songs/merge" :headers {}}))))
    (is (str/includes? (:body (admin/song-merge-confirmation {:params input} planned)) "Confirm and merge songs"))
    (is (thrown? clojure.lang.ExceptionInfo (merge/merge! (dissoc input "confirm"))))
    (is (= a (merge/merge! input)))
    (is (nil? (sql/get-by-id ds :song b vimeo/opts)))
    (is (some? (sql/get-by-id ds :song c vimeo/opts)) "Unselected same-title / different-author composition stays separate")
    (is (= a (merge/canonical-id ds b)))
    (is (= a (merge/find-song-id ds "Amazing, Grace!" "Newton, John")))
    (is (= 303 (:status (admin/edit-page {:path-params {:entity "songs" :id (str b)}}))))
    (is (= (str "/songs/" a) (get-in (admin/edit-page {:path-params {:entity "songs" :id (str b)}}) [:headers "Location"])))
    (is (= a (:song_id (sql/get-by-id ds :song_variation v3 vimeo/opts))))
    (is (nil? (sql/get-by-id ds :song_variation v2 vimeo/opts)))
    (is (= v1 (:song_variation_id (sql/get-by-id ds :song_performance p2 vimeo/opts))))
    (let [after (sql/query ds ["select * from song_performance order by id"] vimeo/opts)]
     (is (= (mapv #(dissoc % :song_variation_id) before) (mapv #(dissoc % :song_variation_id) after)))
     (is (= 3 (count after))))
    (is (= 1 (count (sql/query ds ["select * from song_merge_event"] vimeo/opts))))
    (is (thrown? clojure.lang.ExceptionInfo (merge/merge! input)))
    ;; A second merge retains a different ID; all older aliases follow the final song.
    (let [songs (merge/candidates ds a)
          input2 {"candidate" (mapv (comp str :id) songs) "source" [(str a) (str c)] "retained" (str c)
                  "name" "Amazing Grace" "artist" "Corrected artist" "version" (merge/version ds songs) "confirm" "yes"}]
     (is (= c (merge/merge! input2)))
     (is (= c (merge/canonical-id ds b)))
     (is (= c (merge/canonical-id ds a)))
     (is (= c (merge/find-song-id ds "Amazing Grace" "John Newton")))
     (is (= c (merge/find-song-id ds "Amazing, Grace!" "Newton, John")))
     (is (= "Corrected artist" (:artist (sql/get-by-id ds :song c vimeo/opts)))))))))

(deftest rejects-stale-or-invalid-merges-without-partial-changes
 (temporary-db
  (fn []
   (let [{:keys [a b c v3] :as data} (fixture) ds (db/get-datasource) input (params data)]
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input "retained" (str c)) false)))
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input "artist" " ") false)))
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input "source" [(str a) (str a)]) false)))
    (sql/update! ds :song_variation {:example_link "https://example.org/changed"} {:id v3})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed since" (merge/merge! input)))
    (is (= 3 (count (sql/query ds ["select * from song"] vimeo/opts))))
    (is (empty? (sql/query ds ["select * from song_merge_event"] vimeo/opts)))
    (is (empty? (sql/query ds ["select * from song_merge_alias"] vimeo/opts)))))))

(deftest database-failure-rolls-back-reference-moves-and-history
 (temporary-db
  (fn []
   (let [data (fixture) ds (db/get-datasource) input (params data)
         before (sql/query ds ["select * from song_performance order by id"] vimeo/opts)
         real-delete sql/delete!]
    (with-redefs [sql/delete! (fn [connection table & args]
                               (if (= :song table) (throw (java.sql.SQLException. "Simulated database failure"))
                                 (apply real-delete connection table args)))]
      (is (thrown? java.sql.SQLException (merge/merge! input))))
    (is (= before (sql/query ds ["select * from song_performance order by id"] vimeo/opts)))
    (is (= 3 (count (sql/query ds ["select * from song_variation"] vimeo/opts))))
    (is (= 3 (count (sql/query ds ["select * from song"] vimeo/opts))))
    (is (empty? (sql/query ds ["select * from song_merge_event"] vimeo/opts)))
    (is (empty? (sql/query ds ["select * from song_merge_alias"] vimeo/opts)))
    (is (empty? (sql/query ds ["select * from song_identity_alias"] vimeo/opts)))))))

(deftest chooses-fields-independently-and-updates-selected-row
 (temporary-db
  (fn []
   (let [{:keys [a b c p1] :as data} (fixture) ds (db/get-datasource)
         input (assoc (params data) "retained" (str b) "name-source" (str a) "artist-source" (str b)
                      "name" "Ignored input" "artist" "Ignored input")
         planned (merge/plan ds input false)
         body (:body (admin/song-merge-page {:params {"group" (str a)}}))]
    (is (= "Amazing Grace" (:name planned)))
    (is (= "Newton, John" (:artist planned)))
    (is (str/includes? body "name-source"))
    (is (str/includes? body "artist-source"))
    (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input "artist-source" (str c)) false)))
    (is (= b (merge/merge! input)))
    (is (nil? (sql/get-by-id ds :song a vimeo/opts)))
    (is (= "Amazing Grace" (:name (sql/get-by-id ds :song b vimeo/opts))))
    (is (= "Newton, John" (:artist (sql/get-by-id ds :song b vimeo/opts))))
    (is (= b (merge/canonical-id ds a)))
    (is (= b (:song_id (sql/get-by-id ds :song_variation
                         (:song_variation_id (sql/get-by-id ds :song_performance p1 vimeo/opts)) vimeo/opts))))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
