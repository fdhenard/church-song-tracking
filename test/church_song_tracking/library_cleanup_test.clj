(ns church-song-tracking.library-cleanup-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.edn :as edn]
           [next.jdbc :as jdbc] [next.jdbc.sql :as sql]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.library-cleanup :as cleanup] [church-song-tracking.admin :as admin]
           [church-song-tracking.vimeo-import :as vimeo]))
(defn fixture [f]
 (let [dir (java.nio.file.Files/createTempDirectory "unused-library" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache") config/get-slide-archive-dir #(str dir "/slides")]
   (db/migrate! nil)
   (with-open [ds (jdbc/get-connection (db/get-datasource))]
    (let [insert (fn [table data] (:id (sql/insert! ds table data vimeo/opts)))
          person (insert :person {:name "Person"})
          service (insert :church_service {:date (java.time.LocalDate/parse "2026-01-01") :title "Service" :sermon_title "Sermon" :preacher_id person :song_leader_id person})
          song (fn [name] (insert :song {:name name :artist "Author"}))
          variation (fn [song name] (insert :song_variation {:song_id song :variation_name name :prominent_electric_guitar false}))
          used (song "Used") uv (variation used "Used") spare (variation used "Unused setting")
          unused (song "Unused") v1 (variation unused "Default") v2 (variation unused "Alternate")
          empty (song "No variations")
          draft (song "Draft song") dv (variation draft "Default")
          alias (song "Merged song") av (variation alias "Default")
          perform (fn [id] (insert :song_performance {:church_service_id service :song_variation_id id :order_in_service 1}))
          _ (perform uv)
          event (insert :song_merge_event {:retained_song_id alias :before_data "{}" :after_data "{}" :performance_count 0})
          _ (insert :song_identity_alias {:canonical_song_id alias :merge_event_id event :original_name "Old title" :original_artist "Author"})
          ve (insert :variation_merge_event {:song_id alias :retained_variation_id av :before_data "{}" :after_data "{}" :performance_count 0})
          _ (insert :variation_merge_alias {:old_variation_id 9999 :canonical_variation_id av :variation_merge_event_id ve})
          _ (vimeo/select! {"links" "https://vimeo.com/123"})
          selection (:id (first (vimeo/queue)))
          _ (sql/update! ds :vimeo_import_selection {:draft_data (pr-str {:songs [{:song-choice (str draft)}]})} {:id selection})]
      (f ds {:used used :uv uv :spare spare :unused unused :v1 v1 :v2 v2 :empty empty :draft draft :dv dv :alias alias :av av :perform perform :selection selection}))))))
(defn params [ds kind ids]
 (let [p {"selected" (mapv str ids) "confirm" "yes"}]
  (assoc p "snapshot" (:snapshot (cleanup/plan ds kind p)))))
(deftest candidates-protect-performance-draft-and-merge-references
 (fixture (fn [ds {:keys [used unused empty draft alias uv spare v1 v2 dv av selection]}]
  (let [data (cleanup/context ds)
        songs (cleanup/candidates data "songs") variants (cleanup/candidates data "variations")]
    (is (= #{unused empty} (set (map :id (remove :reason songs)))))
    (is (not-any? #(= used (:id %)) songs))
    (is (= #{draft alias} (set (map :id (filter :reason songs)))))
    (is (= #{spare v1 v2} (set (map :id (remove :reason variants)))))
    (is (= #{dv av} (set (map :id (filter :reason variants)))))
    (is (= 200 (:status (admin/unused-library-page {:path-params {:entity "songs"}}))))
    (is (= 200 (:status (admin/unused-library-page {:path-params {:entity "variations"}}))))
    (is (= 200 (:status (admin/app {:request-method :get :uri "/songs/unused" :headers {}}))))
    (is (= 200 (:status (admin/app {:request-method :get :uri "/variations/unused" :headers {}}))))
    (is (= 403 (:status (admin/app {:request-method :post :uri "/songs/unused" :headers {}}))))
    (is (thrown? clojure.lang.ExceptionInfo (cleanup/plan ds "songs" {"selected" [(str draft)]})))
    (is (thrown? clojure.lang.ExceptionInfo (cleanup/plan ds "variations" {"selected" [(str uv)]}))))
  (sql/update! ds :vimeo_import_selection {:draft_data (pr-str {:songs [{:title "  UNUSED " :author "author"}]})} {:id selection})
  (is (:reason (first (filter #(= unused (:id %)) (cleanup/candidates (cleanup/context ds) "songs"))))))))
(deftest song-deletion-removes-only-unused-children-and-keeps-history
 (fixture (fn [ds {:keys [unused empty v1 v2 used uv]}]
  (let [p (params ds "songs" [unused empty])]
   (is (thrown? clojure.lang.ExceptionInfo (cleanup/delete-unused! "songs" (dissoc p "confirm"))))
   (is (= 2 (cleanup/delete-unused! "songs" p)))
   (is (nil? (sql/get-by-id ds :song unused vimeo/opts)))
   (is (nil? (sql/get-by-id ds :song empty vimeo/opts)))
   (is (nil? (sql/get-by-id ds :song_variation v1 vimeo/opts)))
   (is (nil? (sql/get-by-id ds :song_variation v2 vimeo/opts)))
   (is (some? (sql/get-by-id ds :song used vimeo/opts)))
   (is (some? (sql/get-by-id ds :song_variation uv vimeo/opts)))
   (is (= 1 (count (sql/query ds ["select * from song_performance"] vimeo/opts))))
   (let [event (first (sql/query ds ["select * from library_cleanup_event"] vimeo/opts))
         before (edn/read-string (slurp (.getCharacterStream ^java.sql.Clob (:before_data event))))]
    (is (= 2 (:row_count event)))
    (is (= #{unused empty} (set (map :id (:selected before)))))
    (is (= #{v1 v2} (set (map :id (:variations before))))))))))
(deftest variation-deletion-keeps-song-and-rejects-new-usage-atomically
 (fixture (fn [ds {:keys [spare used unused v1 perform]}]
  (is (= 1 (cleanup/delete-unused! "variations" (params ds "variations" [spare]))))
  (is (some? (sql/get-by-id ds :song used vimeo/opts)))
  (let [p (params ds "songs" [unused])]
   (perform v1)
   (is (thrown? clojure.lang.ExceptionInfo (cleanup/delete-unused! "songs" p)))
   (is (some? (sql/get-by-id ds :song unused vimeo/opts)))
   (is (some? (sql/get-by-id ds :song_variation v1 vimeo/opts)))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
