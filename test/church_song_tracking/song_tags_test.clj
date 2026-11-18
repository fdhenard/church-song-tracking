(ns church-song-tracking.song-tags-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
           [next.jdbc :as jdbc] [next.jdbc.sql :as sql]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.admin :as admin] [church-song-tracking.song-tags :as tags]
           [church-song-tracking.song-merge :as merge] [church-song-tracking.library-cleanup :as cleanup]
           [church-song-tracking.vimeo-import :as vimeo]))
(deftest reusable-tags-save-search-merge-and-cleanup
 (let [dir (java.nio.file.Files/createTempDirectory "song-tags" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache") config/get-slide-archive-dir #(str dir "/slides")]
   (db/migrate! nil)
   (with-open [ds (jdbc/get-connection (db/get-datasource))]
    (let [insert (fn [name] (:id (sql/insert! ds :song {:name name :artist "Writer"} vimeo/opts)))
          a (insert "Same song") b (insert "Same song") c (insert "Unused song")
          save (fn [id params] (admin/save! {:path-params {:entity "songs" :id (str id)}
                                           :params (merge {"name" "Same song" "artist" "Writer" "tags-present" "yes"} params)}))]
      (is (= 303 (:status (save a {"new-tags" "Christmas, Easter,  Missions, CHRISTMAS"}))))
      (is (= #{"Christmas" "Easter" "Missions"} (set (map :name (tags/for-song ds a)))))
      (is (= 3 (count (tags/all-tags ds))))
      (let [christmas (:id (first (filter #(= "Christmas" (:name %)) (tags/all-tags ds))))]
        (is (= 303 (:status (save b {"tag-ids" (str christmas) "new-tags" "missions, Advent"}))))
        (is (= #{"Christmas" "Missions" "Advent"} (set (map :name (tags/for-song ds b)))))
        (is (= 4 (count (tags/all-tags ds)))))
      (let [response (admin/list-page {:path-params {:entity "songs"} :params {"q" "Advent"}})]
        (is (str/includes? (:body response) (str "href=\"/songs/" b "\"")))
        (is (not (str/includes? (:body response) (str "href=\"/songs/" a "\"")))))
      (is (= 200 (:status (admin/edit-page {:path-params {:entity "songs" :id (str a)}}))))
      (is (= 303 (:status (admin/save! {:path-params {:entity "songs" :id (str a)} :params {"name" "Same song" "artist" "Writer"}}))))
      (is (= 3 (count (tags/for-song ds a))))
      (is (= 422 (:status (save a {"name" "Must roll back" "new-tags" (apply str (repeat 81 "x"))}))))
      (is (= "Same song" (:name (sql/get-by-id ds :song a vimeo/opts))))
      (is (= 3 (count (tags/for-song ds a))))
      (is (thrown? java.sql.SQLException (sql/insert! ds :song_tag {:name "  CHRISTMAS "} vimeo/opts)))
      (let [params {"candidate" [(str a) (str b)] "source" [(str a) (str b)] "retained" (str a)
                    "name" "Same song" "artist" "Writer" "confirm" "yes"
                    "version" (merge/fingerprint (merge/snapshot ds [a b] false))}]
        (is (= a (merge/merge! params))))
      (is (= #{"Christmas" "Easter" "Missions" "Advent"} (set (map :name (tags/for-song ds a)))))
      (is (empty? (tags/for-song ds b)))
      (is (= 303 (:status (save a {}))))
      (is (empty? (tags/for-song ds a)))
      (is (= 4 (count (tags/all-tags ds))))
      (tags/save-assignments! ds c {"new-tags" "Missions"})
      (let [p {"selected" [(str c)] "confirm" "yes"}
            p (assoc p "snapshot" (:snapshot (cleanup/plan ds "songs" p)))]
        (is (= 1 (cleanup/delete-unused! "songs" p))))
      (is (empty? (sql/query ds ["select * from song_tag_assignment where song_id=?" c] vimeo/opts)))
      (is (= 4 (count (tags/all-tags ds)))))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
