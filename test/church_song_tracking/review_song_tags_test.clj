(ns church-song-tracking.review-song-tags-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
           [next.jdbc :as jdbc] [next.jdbc.sql :as sql]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.song-tags :as tags] [church-song-tracking.import-review :as review]
           [church-song-tracking.admin :as admin] [church-song-tracking.vimeo-import :as vimeo]))
(def base {:date "2026-01-01" :title "Service" :leader "Leader" :preacher "Preacher" :sermon-title "Sermon"})
(deftest review-adds-tags-to-resolved-song-only-on-approval
 (let [dir (java.nio.file.Files/createTempDirectory "review-song-tags" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache") config/get-slide-archive-dir #(str dir "/slides")]
   (db/migrate! nil)
   (with-open [ds (jdbc/get-connection (db/get-datasource))]
    (let [existing (:id (sql/insert! ds :song {:name "Library song" :artist "Library writer"} vimeo/opts))
          other (:id (sql/insert! ds :song {:name "Unrelated song" :artist "Writer"} vimeo/opts))
          _ (tags/save-assignments! ds existing {"new-tags" "Advent"})
          _ (tags/save-assignments! ds other {"new-tags" "Christmas"})
          christmas (:id (first (tags/for-song ds other)))
          _ (vimeo/select! {"links" "https://vimeo.com/123\nhttps://vimeo.com/456\nhttps://vimeo.com/789\nhttps://vimeo.com/1011"})
          [first-id new-id auto-id failed-id] (map :id (vimeo/queue))
          parsed (review/parse-form {"song-count" "1" "date" "2026-01-01" "title" "Service" "leader" "Leader" "preacher" "Preacher" "sermon-title" "Sermon"
                                    "song-0-title" "OCR spelling" "song-0-author" "" "song-0-choice" (str existing) "song-0-slide" "1:00"
                                    "song-0-tag-ids" [(str christmas)] "song-0-new-tags" "Missions, christmas"})]
      (is (= [(str christmas)] (get-in parsed [:songs 0 :tag-ids])))
      (is (= "Missions, christmas" (get-in parsed [:songs 0 :new-tags])))
      (review/stage! first-id 0 parsed)
      (is (= #{"Advent"} (set (map :name (tags/for-song ds existing)))))
      (is (= 2 (count (tags/all-tags ds))))
      (let [page (admin/review-page {:path-params {:id (str first-id)}})]
        (is (str/includes? (:body page) "Tags to add"))
        (is (str/includes? (:body page) "song-0-new-tags"))
        (is (str/includes? (:body page) "Christmas")))
      (review/save-draft! first-id 1 parsed)
      (is (= "Missions, christmas" (get-in (review/draft (review/selection first-id)) [:songs 0 :new-tags])))
      (review/approve! first-id 2)
      (is (= #{"Advent" "Christmas" "Missions"} (set (map :name (tags/for-song ds existing)))))
      (is (= "Library writer" (:artist (sql/get-by-id ds :song existing vimeo/opts))))
      (is (= #{"Christmas"} (set (map :name (tags/for-song ds other)))))
      (review/stage! new-id 0 (assoc base :date "2026-01-02" :songs [{:title "New song" :author "Writer" :song-choice "new" :slide "2:00" :new-tags "Missions"}]))
      (review/approve! new-id 1)
      (let [new-song (:id (first (sql/query ds ["select * from song where name='New song'"] vimeo/opts)))]
        (is (= #{"Missions"} (set (map :name (tags/for-song ds new-song)))))
        (review/stage! auto-id 0 (assoc base :date "2026-01-03" :songs [{:title "New song" :author "Writer" :song-choice "auto" :slide "3:00" :new-tags "Easter"}]))
        (review/approve! auto-id 1)
        (is (= #{"Easter" "Missions"} (set (map :name (tags/for-song ds new-song)))))
        (is (= 1 (count (sql/query ds ["select * from song where name='New song'"] vimeo/opts)))))
      (review/stage! failed-id 0 (assoc base :date "2026-01-04" :songs [{:title "Library song" :author "" :song-choice (str existing) :slide "4:00" :new-tags "Must roll back"}
                                                                                 {:title "Invalid new song" :author "Writer" :slide "5:00" :new-tags (apply str (repeat 81 "x"))}]))
      (is (thrown? clojure.lang.ExceptionInfo (review/approve! failed-id 1)))
      (is (nil? (:imported_service_id (review/selection failed-id))))
      (is (= 3 (count (sql/query ds ["select * from church_service"] vimeo/opts))))
      (is (not-any? #(= "Must roll back" (:name %)) (tags/all-tags ds)))
      (is (= #{"Advent" "Christmas" "Missions"} (set (map :name (tags/for-song ds existing))))))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
