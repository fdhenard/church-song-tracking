(ns church-song-tracking.song-frame-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [next.jdbc :as jdbc]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.import-review :as review]
            [church-song-tracking.vimeo-import :as vimeo]))
(deftest selected-song-frame-preserves-edits-and-adds-review-entry
  (let [dir (java.nio.file.Files/createTempDirectory "song-frame-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs")]
      (db/migrate! nil)
      (with-open [keeper (jdbc/get-connection (db/get-datasource))]
        (vimeo/select! {"links" "https://vimeo.com/123"})
        (let [id (:id (first (vimeo/queue)))
              data {:title "Edited title" :sermon-scripture "Romans 10:13-17"
                    :songs [{:title "Existing song" :author "Author" :order 1 :song-choice "7"}]}]
          (review/stage! id 0 data)
          (with-redefs [review/nominate-frame!
                        (fn [selected revision seconds kind]
                          (is (= [id 1 5430 "song_title"] [selected revision seconds kind]))
                          {:revision 1 :frame {:index 1810 :ocr "Manually chosen slide"} :details []})]
            (is (= 1 (review/add-song-frame! id 1 5430 data))))
          (let [row (review/selection id) draft (review/draft row) song (second (:songs draft))]
            (is (= 2 (:revision row)))
            (is (= "Edited title" (:title draft)))
            (is (= "Romans 10:13-17" (:sermon-scripture draft)))
            (is (= (first (:songs data)) (first (:songs draft))))
            (is (= {:title "" :author "" :order 2 :song-choice "auto" :slide "1:30:30" :evidence-index 1810}
                   (select-keys song [:title :author :order :song-choice :slide :evidence-index])))
            (is (nil? (:imported_service_id row)))
            (is (thrown? clojure.lang.ExceptionInfo
                  (review/add-song-frame! id 2 5430 (assoc data :songs (vec (repeat 100 {}))))))))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
