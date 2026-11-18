(ns church-song-tracking.performance-merge-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.edn :as edn]
           [church-song-tracking.service-merge-test :as fixture]
           [church-song-tracking.service-merge :as merge]
           [church-song-tracking.admin :as admin] [church-song-tracking.variation-merge :as variation-merge]
           [church-song-tracking.db :as db] [church-song-tracking.import-review :as review] [church-song-tracking.vimeo-import :as vimeo]
           [next.jdbc.sql :as sql]))
(deftest combines-individual-performance-fields-and-keeps-old-links
 (fixture/temporary-db
  (fn []
   (let [{:keys [a b p1 p2] :as data} (fixture/fixture) ds (db/get-datasource)
         song (fixture/insert! :song {:name "A completely different OCR title" :artist "Other author"})
         variation (fixture/insert! :song_variation {:song_id song :variation_name "Acoustic" :example_link "https://example.org/arrangement" :prominent_electric_guitar true})]
    (sql/update! ds :song_performance {:time_in_service_youtube "1:00"} {:id p1})
    (sql/update! ds :song_performance {:song_variation_id variation :time_in_service_vimeo "2:00" :song_start_time_vimeo "2:05" :title_image_path "/permanent/test.jpg"} {:id p2})
    (let [k1 (str "performance-" p1) k2 (str "performance-" p2)
          input (assoc (fixture/params data) (str "merge-" k2) k1
                       (str "pick-" k1 "-song") k1 (str "pick-" k1 "-variation") k2
                       (str "pick-" k1 "-time_in_service_vimeo") k2
                       (str "pick-" k1 "-song_start_time_vimeo") k2
                       (str "pick-" k1 "-title_image_path") k2)
          original-song (:song_id (sql/get-by-id ds :song_variation (:song_variation_id (sql/get-by-id ds :song_performance p1 vimeo/opts)) vimeo/opts))]
     (is (= 1 (count (:kept (merge/plan ds input)))))
     (is (= {k2 k1} (get-in (merge/plan ds input) [:performance-plan :redirects])))
     (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input (str "merge-" k1) k2))))
     (is (thrown? clojure.lang.ExceptionInfo (merge/plan ds (assoc input (str "pick-" k1 "-song") "not-a-member"))))
     (is (= a (merge/merge! input)))
     (let [p (sql/get-by-id ds :song_performance p1 vimeo/opts)
           v (sql/get-by-id ds :song_variation (:song_variation_id p) vimeo/opts)]
      (is (= "2:00" (:time_in_service_vimeo p)))
      (is (= "1:00" (:time_in_service_youtube p)))
      (is (= "2:05" (:song_start_time_vimeo p)))
      (is (= "/permanent/test.jpg" (:title_image_path p)))
      (is (= "Original spreadsheet evidence" (:source_data p)))
      (is (= original-song (:song_id v)))
      (is (= "Acoustic" (:variation_name v)))
      (is (= "https://example.org/arrangement" (:example_link v)))
      (is (true? (:prominent_electric_guitar v))))
     (is (nil? (sql/get-by-id ds :song_performance p2 vimeo/opts)))
     (is (= p1 (merge/performance-canonical-id ds p2)))
     (is (= (str "/performances/" p1) (get-in (admin/edit-page {:path-params {:entity "performances" :id (str p2)}}) [:headers "Location"])))
     (is (nil? (sql/get-by-id ds :song_variation variation vimeo/opts)) "Discarded variation is deleted once unused")
     (is (some? (variation-merge/canonical-id ds variation)))
     (let [event (first (sql/query ds ["select * from service_merge_event"] vimeo/opts))]
      (is (= {k2 k1} (get-in (edn/read-string (:after_data event)) [:performance-plan :redirects])))))))))
(deftest shared-variation-conflicts-need-an-explicit-decision
 (fixture/temporary-db
  (fn []
   (let [{:keys [a p1 p2 p3] :as data} (fixture/fixture) ds (db/get-datasource)
         original-id (:song_variation_id (sql/get-by-id ds :song_performance p1 vimeo/opts))
         other (fixture/insert! :song {:name "Song" :artist "Other author"})
         source-id (fixture/insert! :song_variation {:song_id other :variation_name "Default" :prominent_electric_guitar true})]
    (sql/update! ds :song_performance {:song_variation_id source-id} {:id p2})
    (let [k1 (str "performance-" p1) k2 (str "performance-" p2)
          input (assoc (fixture/params data) (str "merge-" k2) k1 (str "pick-" k1 "-variation") k2)
          before (merge/context ds input) planned (merge/plan ds input)]
     (is (= "Default" (get-in planned [:performance-plan :variation-names k1])))
     (is (= [p3] (mapv :id (get-in planned [:performance-plan :variation-conflicts 0 :outside]))))
     (is (= before (merge/context ds input)))
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicitly approve" (merge/merge! input)))
     (is (= before (merge/context ds input)))
     (is (clojure.string/includes? (:body (admin/service-merge-confirmation {:params input} planned)) "Shared variation decision"))
     (is (= a (merge/merge! (assoc input (str "approve-shared-variation-" original-id) "yes"))))
     (is (= "Default" (:variation_name (sql/get-by-id ds :song_variation original-id vimeo/opts))))
     (is (true? (:prominent_electric_guitar (sql/get-by-id ds :song_variation original-id vimeo/opts))))
     (is (= original-id (:song_variation_id (sql/get-by-id ds :song_performance p3 vimeo/opts))))
     (is (nil? (sql/get-by-id ds :song_variation source-id vimeo/opts))))))))
(deftest unshared-conflicting-variation-is-reconciled-without-renaming
 (fixture/temporary-db
  (fn []
   (let [{:keys [a p1 p2 p3] :as data} (fixture/fixture) ds (db/get-datasource)
         original-id (:song_variation_id (sql/get-by-id ds :song_performance p1 vimeo/opts))
         song-id (:song_id (sql/get-by-id ds :song_variation original-id vimeo/opts))
         unrelated (fixture/insert! :song_variation {:song_id song-id :variation_name "Unrelated"})
         other (fixture/insert! :song {:name "Song" :artist "Other author"})
         source-id (fixture/insert! :song_variation {:song_id other :variation_name "Default" :example_link "https://example.org/chosen" :prominent_electric_guitar true})]
    (sql/update! ds :song_performance {:song_variation_id unrelated} {:id p3})
    (sql/update! ds :song_performance {:song_variation_id source-id} {:id p2})
    (let [k1 (str "performance-" p1) k2 (str "performance-" p2)
          input (assoc (fixture/params data) (str "merge-" k2) k1 (str "pick-" k1 "-variation") k2)]
     (is (empty? (get-in (merge/plan ds input) [:performance-plan :variation-conflicts])))
     (is (= a (merge/merge! input)))
     (is (= "Default" (:variation_name (sql/get-by-id ds :song_variation original-id vimeo/opts))))
     (is (= "https://example.org/chosen" (:example_link (sql/get-by-id ds :song_variation original-id vimeo/opts))))
     (is (nil? (sql/get-by-id ds :song_variation source-id vimeo/opts)))
     (is (= original-id (variation-merge/canonical-id ds source-id)))
     (is (empty? (sql/query ds ["select v.id from song_variation v where not exists (select 1 from song_performance p where p.song_variation_id=v.id)"] vimeo/opts))))))))
(deftest merges-incoming-video-details-into-existing-performance
 (fixture/temporary-db
  (fn []
   (let [{:keys [a p1]} (fixture/fixture) ds (db/get-datasource)]
    (sql/update! ds :song_performance {:time_in_service_youtube "1:23"} {:id p1})
    (vimeo/select! {"links" "https://vimeo.com/333"})
    (let [selection (:id (first (vimeo/queue)))]
     (review/stage! selection 0 fixture/draft)
     (let [base {"selection" (str selection)} data (merge/context ds base)
           key (str "performance-" p1)
           input (assoc base "version" (merge/fingerprint data) "source" [(str a) "incoming"] "retained" (str a)
                        "performance" [key] (str "order-" key) "1" "merge-incoming-0" key
                        (str "pick-" key "-time_in_service_vimeo") "incoming-0"
                        (str "pick-" key "-song_start_time_vimeo") "incoming-0" "confirm" "yes")]
      (is (= a (merge/merge! input)))
      (let [rows (sql/query ds ["select * from song_performance where church_service_id=?" a] vimeo/opts)
            p (first rows)
            event (first (sql/query ds ["select * from service_merge_event"] vimeo/opts))
            after (edn/read-string (:after_data event))
            removed-id (get-in after [:performance-ids "incoming-0"])]
       (is (= 1 (count rows)))
       (is (= p1 (:id p)))
       (is (= "1:23" (:time_in_service_youtube p)))
       (is (= "2:00" (:time_in_service_vimeo p)))
       (is (= "2:00" (:song_start_time_vimeo p)))
       (is (= p1 (merge/performance-canonical-id ds removed-id)))
       (is (= a (:imported_service_id (review/selection selection))))
       (is (= [(dissoc p :time_added)] (mapv #(dissoc % :time_added) (:performance-results after)))))))))))

(deftest incoming-song-can-use-the-existing-performance-variation-settings
 (fixture/temporary-db
  (fn []
   (let [{:keys [a p1]} (fixture/fixture) ds (db/get-datasource)
         original-variation (:song_variation_id (sql/get-by-id ds :song_performance p1 vimeo/opts))]
    (sql/update! ds :song_variation {:example_link "https://example.org/original" :prominent_electric_guitar true} {:id original-variation})
    (vimeo/select! {"links" "https://vimeo.com/333"})
    (let [selection (:id (first (vimeo/queue)))
          draft (assoc-in fixture/draft [:songs 0 :author] "Correct incoming author")]
     (review/stage! selection 0 draft)
     (let [base {"selection" (str selection)} data (merge/context ds base) key (str "performance-" p1)
           input (assoc base "version" (merge/fingerprint data) "source" [(str a) "incoming"] "retained" (str a)
                        "performance" [key] (str "order-" key) "1" "merge-incoming-0" key
                        (str "pick-" key "-song") "incoming-0" "confirm" "yes")]
      (is (= a (merge/merge! input)))
      (let [p (sql/get-by-id ds :song_performance p1 vimeo/opts)
            v (sql/get-by-id ds :song_variation (:song_variation_id p) vimeo/opts)
            song (sql/get-by-id ds :song (:song_id v) vimeo/opts)]
       (is (= "Correct incoming author" (:artist song)))
       (is (= "Default" (:variation_name v)))
       (is (= "https://example.org/original" (:example_link v)))
       (is (true? (:prominent_electric_guitar v)))
       (is (not= original-variation (:id v)))
       (is (= 1 (count (sql/find-by-keys ds :song_variation {:song_id (:song_id v)} vimeo/opts)))))))))))

(deftest discarded-import-variation-is-deleted-and-old-forms-require-review
 (fixture/temporary-db
  (fn []
   (let [{:keys [a p1]} (fixture/fixture) ds (db/get-datasource)
         original-id (:song_variation_id (sql/get-by-id ds :song_performance p1 vimeo/opts))]
    (vimeo/select! {"links" "https://vimeo.com/333"})
    (let [selection (:id (first (vimeo/queue))) draft (assoc-in fixture/draft [:songs 0 :author] "New incoming author")]
     (review/stage! selection 0 draft)
     (let [base {"selection" (str selection)} snapshot (merge/context ds base) key (str "performance-" p1)
           input (assoc base "version" (merge/fingerprint snapshot) "source" [(str a) "incoming"] "retained" (str a)
                        "performance" [key] (str "order-" key) "1" "merge-incoming-0" key "confirm" "yes")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"preview the merge again"
            (merge/merge! (assoc input (str "variation-name-" key) "Default (merged performance)"))))
      (is (= snapshot (merge/context ds input)))
      (is (= a (merge/merge! input)))
      (is (= original-id (:song_variation_id (sql/get-by-id ds :song_performance p1 vimeo/opts))))
      (is (empty? (sql/query ds ["select v.id from song_variation v where not exists (select 1 from song_performance p where p.song_variation_id=v.id)"] vimeo/opts)))
      (is (empty? (sql/query ds ["select id from song_variation where variation_name like '%merged performance%'"] vimeo/opts)))))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
