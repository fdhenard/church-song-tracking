(ns church-song-tracking.review-variation-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
           [next.jdbc :as jdbc] [next.jdbc.sql :as sql]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.import-review :as review] [church-song-tracking.service-merge :as service-merge]
           [church-song-tracking.admin :as admin] [church-song-tracking.vimeo-import :as vimeo]))
(def base {:date "2026-01-01" :title "Service" :leader "Leader" :preacher "Preacher" :sermon-title "Sermon"})
(deftest chosen-variation-is-saved-validated-and-used-without-changing-settings
 (let [dir (java.nio.file.Files/createTempDirectory "review-variation" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache") config/get-slide-archive-dir #(str dir "/slides")]
   (db/migrate! nil)
   (with-open [ds (jdbc/get-connection (db/get-datasource))]
    (let [insert (fn [table data] (:id (sql/insert! ds table data vimeo/opts)))
          a (insert :song {:name "Song A" :artist "Writer A"}) b (insert :song {:name "Song B" :artist "Writer B"})
          default (insert :song_variation {:song_id a :variation_name "Default" :prominent_electric_guitar false})
          acoustic (insert :song_variation {:song_id a :variation_name "Acoustic" :example_link "https://example.org/example" :prominent_electric_guitar true})
          other (insert :song_variation {:song_id b :variation_name "Default" :prominent_electric_guitar false})
          before (sql/get-by-id ds :song_variation acoustic vimeo/opts)
          _ (vimeo/select! {"links" "https://vimeo.com/123\nhttps://vimeo.com/456\nhttps://vimeo.com/789\nhttps://vimeo.com/1011\nhttps://vimeo.com/1012\nhttps://vimeo.com/1013"})
          [explicit-id auto-id new-id wrong-id missing-id wrong-new-id] (map :id (vimeo/queue))
          parsed (review/parse-form {"song-count" "1" "date" "2026-01-01" "title" "Service" "leader" "Leader" "preacher" "Preacher" "sermon-title" "Sermon"
                                    "song-0-title" "OCR title" "song-0-author" "" "song-0-choice" (str a) "song-0-slide" "1:00"
                                    "song-0-variation-id" (str acoustic)})]
      (is (= (str acoustic) (get-in parsed [:songs 0 :variation-id])))
      (review/stage! explicit-id 0 parsed)
      (let [html (:body (admin/review-page {:path-params {:id (str explicit-id)}}))
            pending (first (service-merge/performances ds {:performances [] :selection {:id explicit-id} :draft parsed
                                                          :song-variations (sql/query ds ["select * from song_variation"] vimeo/opts)}))]
        (is (str/includes? html "Variation to use"))
        (is (re-find #"(?s)<select name=\"song-0-variation-key\">.*?<option[^>]*selected[^>]*>Unset</option>" html))
        ;; An untouched new variation on the import screen defaults to Review.
        (let [select-html (re-find #"(?s)<select name=\"song-0-variation-guitar\">(.*?)</select>" html)]
          (is (some? select-html))
          (is (re-find #"<option[^>]*value=\"review\"[^>]*selected|<option[^>]*selected[^>]*value=\"review\"" (or (second select-html) ""))))
        (is (str/includes? html "review-variation-library"))
        (is (= acoustic (:variation-id pending)))
        (is (= "Acoustic" (:variation pending)))
        (is (= true (:variation-guitar pending))))
      (review/save-draft! explicit-id 1 parsed)
      (is (= (str acoustic) (get-in (review/draft (review/selection explicit-id)) [:songs 0 :variation-id])))
      (let [service (review/approve! explicit-id 2)]
        (is (= acoustic (:song_variation_id (first (sql/query ds ["select * from song_performance where church_service_id=?" service] vimeo/opts))))))
      (review/stage! auto-id 0 (assoc base :date "2026-01-02" :songs [{:title "Song A" :author "Writer A" :slide "1:00" :variation-id (str acoustic)}]))
      (let [service (review/approve! auto-id 1)]
        (is (= acoustic (:song_variation_id (first (sql/query ds ["select * from song_performance where church_service_id=?" service] vimeo/opts))))))
      (is (= before (sql/get-by-id ds :song_variation acoustic vimeo/opts)))
      (is (= 2 (count (sql/query ds ["select * from song_variation where song_id=?" a] vimeo/opts))))
      (review/stage! new-id 0 (assoc base :date "2026-01-03" :songs [{:title "New song" :author "Writer" :song-choice "new" :variation-id "auto" :slide "1:00"}]))
      (let [service (review/approve! new-id 1)
            row (first (sql/query ds ["select v.* from song_variation v join song_performance p on p.song_variation_id=v.id where p.church_service_id=?" service] vimeo/opts))]
        (is (= "Default" (:variation_name row)))
        (is (not= a (:song_id row))))
      (doseq [[id variation choice title] [[wrong-id other (str a) "Song A"] [missing-id 999999 (str a) "Song A"] [wrong-new-id acoustic "new" "Must roll back"]]]
        (review/stage! id 0 (assoc base :date "2026-01-04" :songs [{:title title :author "Writer A" :song-choice choice :variation-id (str variation) :slide "1:00" :new-tags "Must roll back"}]))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"selected variation" (review/approve! id 1)))
        (is (nil? (:imported_service_id (review/selection id)))))
      (is (= 3 (count (sql/query ds ["select * from church_service"] vimeo/opts))))
      (is (empty? (sql/query ds ["select * from song_tag where name='Must roll back'"] vimeo/opts)))
      (is (empty? (sql/query ds ["select * from song where name='Must roll back'"] vimeo/opts))))))))
(deftest typed-variation-is-created-only-on-approval-and-reused-by-name
  (let [dir (java.nio.file.Files/createTempDirectory "typed-variation" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")
                  config/get-slide-archive-dir #(str dir "/slides")]
      (db/migrate! nil)
      (let [ds (db/get-datasource)
            song-id (:id (sql/insert! ds :song {:name "Song" :artist "Writer"} vimeo/opts))
            existing (:id (sql/insert! ds :song_variation {:song_id song-id :variation_name "Acoustic"
                           :example_link "https://example.org" :prominent_electric_guitar true} vimeo/opts))
            params {"song-count" "1" "song-0-title" "Song" "song-0-author" "Writer"
                    "song-0-slide" "1:00" "song-0-choice" (str song-id)
                    "song-0-variation-id" (str existing) "song-0-variation-name" "  Piano  "
                    "song-0-variation-example-link" "https://example.org/piano" "song-0-variation-guitar" "true" "song-0-variation-key" "Eb"}
            parsed (merge (review/parse-form params) base)]
        (is (= "Piano" (get-in parsed [:songs 0 :variation-name])))
        (is (= "D#" (get-in parsed [:songs 0 :variation-key])))
        (is (= "https://example.org/piano" (get-in parsed [:songs 0 :variation-example-link])))
        (is (true? (get-in parsed [:songs 0 :variation-guitar])))
        (is (thrown? clojure.lang.ExceptionInfo (review/review-variation ds song-id {:variation-example-link "https://example.org"})))
        (is (thrown? clojure.lang.ExceptionInfo (review/review-variation ds song-id {:variation-name "Test" :variation-example-link "javascript:alert(1)"})))
        (vimeo/select! {"links" "https://vimeo.com/901
https://vimeo.com/902
https://vimeo.com/903"})
        (doseq [[i row] (map-indexed vector (vimeo/queue))]
          (let [data (-> parsed (assoc :date (str "2026-02-0" (inc i)))
                         (assoc-in [:songs 0 :variation-name] (if (= i 1) " acoustic " "Piano"))
                         (cond-> (= i 2) (assoc-in [:songs 0 :song-choice] "new")))
                id (:id row)]
            (review/stage! id 0 data)
            (is (str/includes? (:body (admin/review-page {:path-params {:id (str id)}})) "Cancel new variation"))
            (when (zero? i)
              (is (= 1 (count (sql/query ds ["select * from song_variation"] vimeo/opts))))
              (is (= "Piano" (:variation (first (service-merge/performances ds
                           {:performances [] :selection row :draft data :song-variations []}))))))
            (let [service (review/approve! id 1)
                  v (first (sql/query ds ["select v.* from song_variation v join song_performance p on p.song_variation_id=v.id where p.church_service_id=?" service] vimeo/opts))]
              (is (= (if (= i 1) "Acoustic" "Piano") (:variation_name v)))
              (if (= i 1)
                (do (is (nil? (:musical_key v))) (is (= existing (:id v))) (is (:prominent_electric_guitar v))
                    (is (= "https://example.org" (:example_link v))))
                (do (is (= "D#" (:musical_key v))) (is (true? (:prominent_electric_guitar v))) (is (= "https://example.org/piano" (:example_link v)))))
              (when (= i 2) (is (not= song-id (:song_id v)))))))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
