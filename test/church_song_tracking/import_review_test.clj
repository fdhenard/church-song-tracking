(ns church-song-tracking.import-review-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [next.jdbc.sql :as sql]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.import-review :as review]
            [church-song-tracking.vimeo-import :as vimeo]
            [next.jdbc :as jdbc]))

(def sample {:date "2026-10-25" :title "Sunday worship" :leader "Leader" :preacher "Preacher"
             :holiday "Christmas" :sermon-scripture "Romans 10:13-17" :sermon-title "Sermon" :sermon-series "Series" :sermon-time "30:00"
             :songs [{:order 3 :title "Song" :author "Author" :start "1:05" :slide "1:08" :ocr "Song / Author"}]})
(defn count-table [table]
  (:N (jdbc/execute-one! (db/get-datasource) [(str "select count(*) as n from " table)])))

(deftest review-and-explicit-approval
  (let [dir (java.nio.file.Files/createTempDirectory "review-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123\nhttps://vimeo.com/456"})
      (let [id (:id (first (vimeo/queue)))
            other (:id (second (vimeo/queue)))]
        (is (= 200 (:status (admin/app {:request-method :get :uri (str "/imports/" id) :headers {}}))))
        (is (str/includes? (:body (admin/review-page {:path-params {:id (str id)}})) "No video extraction"))
        (is (str/includes? (:body (admin/review-page {:path-params {:id (str id)}})) "Author credit needs manual review."))
        (is (= 403 (:status (admin/app {:request-method :post :uri (str "/imports/" id) :headers {}}))))
        (review/stage! id 0 sample)
        (is (zero? (count-table "church_service")))
        (review/save-draft! id 1 (assoc sample :title "Corrected title"))
        (is (= "Corrected title" (:title (review/draft (review/selection id)))))
        (is (str/includes? (:body (admin/review-page {:path-params {:id (str id)}})) "Corrected title"))
        (is (thrown? clojure.lang.ExceptionInfo (review/approve! id 1)))
        (is (thrown? clojure.lang.ExceptionInfo (review/save-draft! id 1 sample)))
        (is (zero? (count-table "church_service")))
        (let [service-id (review/approve! id 2)]
          (is (= service-id (:imported_service_id (review/selection id))))
          (is (= 1 (count-table "church_service")))
          (is (= 1 (count-table "song_performance")))
          (is (= "Romans 10:13-17" (:sermon_scripture (sql/get-by-id (db/get-datasource) :church_service service-id vimeo/opts))))
          (let [service (sql/get-by-id (db/get-datasource) :church_service service-id vimeo/opts)]
            (is (= "Christmas" (:name (sql/get-by-id (db/get-datasource) :holiday (:holiday_id service) vimeo/opts)))))
          (is (= 1 (:order_in_service (first (sql/query (db/get-datasource) ["select order_in_service from song_performance"] vimeo/opts)))))
          (is (= 1 (count-table "song")))
          (is (nil? (:imported_service_id (review/selection other))))
          (is (thrown? clojure.lang.ExceptionInfo (review/approve! id 3)))
          (is (= 1 (count-table "church_service"))))
        (review/save-draft! other 0 (assoc sample :songs []))
        (is (thrown? clojure.lang.ExceptionInfo (review/approve! other 1)))
        (review/save-draft! other 1 sample)
        (is (thrown? clojure.lang.ExceptionInfo (review/approve! other 2)))
        (is (= 1 (count-table "church_service")))
        (is (= 2 (count-table "person")))))))

(deftest suggestions-show-source-images-and-preserve-user-edits
  (let [dir (java.nio.file.Files/createTempDirectory "source-review-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs")
                  config/get-video-cache-dir #(str dir "/cache")
                  config/video-settings (fn [] {:python "python3"})]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue)))
            data {:title "Manually edited service" :sermon-title "Manually edited sermon"
                  :songs [{:title "My corrected title" :author "My corrected author" :slide "0:30" :evidence-index 10}]}
            extraction {:evidence [{:index 10 :kind "ocr_candidate" :ocr "\"Song A\" by Writer A. Public Domain. CCLI #1"}
                                   {:index 40 :kind "ocr_candidate" :ocr "\"Song B\" by Writer B. Public Domain. CCLI #1"}
                                   {:index 90 :kind "ocr_candidate" :ocr "Series: The Gospel\n\"Sermon screen\" Acts 11:1-18"}]}]
        (sql/update! (db/get-datasource) :vimeo_import_selection
          {:draft_data (pr-str data) :extracted_data (pr-str extraction)} {:id id})
        (review/suggest-details! id 0)
        (let [draft (review/draft (review/selection id))
              body (:body (admin/review-page {:path-params {:id (str id)}}))]
          (is (= "Manually edited service" (:title draft)))
          (is (= "Manually edited sermon" (:sermon-title draft)))
          (is (= "My corrected author" (:author (first (:songs draft)))))
          (is (= 2 (count (:songs draft))))
          (is (= 40 (:evidence-index (second (:songs draft)))))
          (is (str/includes? body "review-source-pair"))
          (is (str/includes? body (str "/imports/" id "/frame/10?revision=1")))
          (is (str/includes? body "song-1-evidence-index")))
        (is (zero? (count-table "church_service")))
        (is (zero? (count-table "song")))
        (review/suggest-details! id 1)
        (is (= 2 (count (:songs (review/draft (review/selection id))))))))))

(deftest source-links-and-song-order-survive-saving
  (let [data (review/parse-form {"song-count" "2" "sermon-evidence-index" "90"
                                 "song-0-slide" "10:00" "song-0-title" "Second" "song-0-order" "2" "song-0-evidence-index" "40"
                                 "song-1-start" "2:00" "song-1-title" "First" "song-1-order" "1" "song-1-evidence-index" "10"})]
    (is (= ["First" "Second"] (mapv :title (:songs data))))
    (is (= [10 40] (mapv :evidence-index (:songs data))))
    (is (= 90 (:sermon-evidence-index data)))
    (is (review/blank-song? {:order 1 :evidence-index 10}))
    (is (thrown? clojure.lang.ExceptionInfo (review/parse-form {"song-count" "0" "sermon-evidence-index" "-1"})))))

(deftest optional-song-start-and-navigation-fallback
  (doseq [[slide start expected] [["1:08" "" "1:08"] ["" "1:05" "1:05"] ["1:08" "1:05" "1:08"]]]
    (let [dir (java.nio.file.Files/createTempDirectory "optional-start-test" (make-array java.nio.file.attribute.FileAttribute 0))]
      (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
        (db/migrate! nil)
        (vimeo/select! {"links" "https://vimeo.com/123"})
        (let [id (:id (first (vimeo/queue)))
              data (assoc sample :songs [(assoc (first (:songs sample)) :slide slide :start start)])]
          (review/stage! id 0 (assoc-in (assoc-in data [:songs 0 :slide] "") [:songs 0 :start] ""))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"slide time or a song start time" (review/approve! id 1)))
          (review/save-draft! id 1 data)
          (review/approve! id 2)
          (let [performance (first (sql/query (db/get-datasource) ["select * from song_performance"] vimeo/opts))]
            (is (= expected (:time_in_service_vimeo performance)))
            (is (= (when (seq start) start) (:song_start_time_vimeo performance)))))))))

(deftest nominated-frame-retains-unsaved-review-state-and-is-revision-guarded
  (let [dir (java.nio.file.Files/createTempDirectory "nominate-sermon-test" (make-array java.nio.file.attribute.FileAttribute 0))
        runner (io/file (str dir) "ocr-stub")
        result {:frame {:index 633 :kind "sermon_title" :ocr "Pastor Justin Waples"}
                :details {:preacher "Justin Waples" :sermon-time "31:39"}}]
    (spit runner (str "#!/usr/bin/env python3\nprint(" (pr-str (json/write-str result)) ")\n"))
    (.setExecutable runner true)
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")
                  config/video-settings (fn [] {:python (str runner)})]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue))) folder (io/file (config/get-video-cache-dir) (str id))]
        (.mkdirs folder)
        (review/stage! id 0 sample)
        (let [response (review/nominate-sermon! id 1 1899) row (review/selection id)]
          (is (= 2 (:revision response)))
          (is (= "Justin Waples" (get-in response [:details :preacher])))
          (is (= sample (review/draft row)) "Nomination never overwrites saved edits; UI merges into unsaved fields")
          (is (= 633 (:index (first (:evidence (clojure.edn/read-string (:extracted_data row)))))))
          (is (zero? (count-table "church_service"))))
        (is (thrown? clojure.lang.ExceptionInfo (review/nominate-sermon! id 1 1899)))
        (is (thrown? clojure.lang.ExceptionInfo (review/nominate-sermon! id 2 -1)))
        (sql/update! (db/get-datasource) :vimeo_import_selection {:job_status "recognizing"} {:id id})
        (is (thrown? clojure.lang.ExceptionInfo (review/nominate-sermon! id 2 1899)))))))

(deftest explicit-song-choice-controls-approved-reference
  (let [dir (java.nio.file.Files/createTempDirectory "song-choice-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
      (db/migrate! nil)
      (with-open [keeper (jdbc/get-connection (db/get-datasource))]
        (let [existing (:id (sql/insert! keeper :song {:name "Song" :artist "Library author"} vimeo/opts))]
          (vimeo/select! {"links" "https://vimeo.com/123\nhttps://vimeo.com/456\nhttps://vimeo.com/789"})
          (doseq [[row choice date] (map vector (vimeo/queue) [(str existing) "new" "999999"] ["2026-10-25" "2026-10-26" "2026-10-27"])]
            (let [data (assoc sample :date date :songs [(assoc (first (:songs sample)) :song-choice choice :author (if (= choice "new") "Author" ""))])
                  id (:id row)]
              (review/stage! id 0 data)
              (is (str/includes? (:body (admin/review-page {:path-params {:id (str id)}})) "Song to import"))
              (if (= choice "999999")
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"selected song no longer exists" (review/approve! id 1)))
                (let [service-id (review/approve! id 1)
                      song-id (:song_id (first (sql/query keeper ["select v.song_id from song_performance p join song_variation v on v.id=p.song_variation_id where p.church_service_id=?" service-id] vimeo/opts)))]
                  (is (= (= choice "new") (not= existing song-id)))))))
          (is (= 2 (count-table "song")))
          (is (= "Library author" (:artist (sql/get-by-id keeper :song existing vimeo/opts)))))))))

(deftest recovered-suggestions-use-video-order-without-reordering-reviewed-songs
  (let [existing [{:title "Reviewed later song" :author "Edited" :evidence-index 282}
                  {:title "Reviewed last song" :evidence-index 485}]
        candidate {:title "How Firm A Foundation" :author "" :evidence-index 211}]
    (let [result (review/insert-song-suggestion existing candidate)]
      (is (= (into [candidate] existing) (mapv #(dissoc % :order) result)))
      (is (= [1 2 3] (mapv :order result))))
    (is (= (conj existing {:title "Manual"})
           (review/insert-song-suggestion existing {:title "Manual"})))
    (is (thrown? clojure.lang.ExceptionInfo
          (review/validated (assoc sample :songs [(assoc candidate :slide "10:33")]))))))

(deftest existing-song-identity-does-not-require-an-ocr-author
  (let [data (assoc sample :songs [{:title "Christ Our Wisdom" :author "" :start "29:48" :slide "" :song-choice "12"}])]
    (is (= "12" (get-in (review/validated data) [:songs 0 :song-choice])))
    (doseq [choice ["auto" "new" "missing" "0"]]
      (is (thrown? clojure.lang.ExceptionInfo
            (review/validated (assoc-in data [:songs 0 :song-choice] choice)))))
    (is (thrown? clojure.lang.ExceptionInfo
          (review/validated (assoc-in data [:songs 0 :start] ""))))))

(deftest remove-review-performance
  (let [saved (atom nil)
        params {"revision" "7" "action" "remove-song-1" "song-count" "3"
                "title" "Edited service"
                "song-0-title" "Keep first" "song-0-order" "9"
                "song-1-title" "Extra" "song-1-order" "invalid"
                "song-2-title" "Keep last" "song-2-order" "2"}]
    (with-redefs [review/save-draft! (fn [id revision data] (reset! saved [id revision data]))]
      (is (= 303 (:status (admin/save-review! {:path-params {:id "106"} :params params}))))
      (is (= [106 7] (take 2 @saved)))
      (is (= "Edited service" (:title (last @saved))))
      (is (= ["Keep first" "Keep last"] (mapv :title (:songs (last @saved)))))
      (is (= [1 2] (mapv :order (:songs (last @saved))))))
    (is (thrown? clojure.lang.ExceptionInfo (review/parse-form params 3)))
    (is (empty? (:songs (review/parse-form {"song-count" "1"} 0))))))

(deftest timestamp-derived-order
  (let [songs [{:title "Hour" :slide "1:00:00" :order 1}
               {:title "Fallback" :slide "" :start "2:00" :order 1}
               {:title "Slide wins" :slide "10:00" :start "0:01"}
               {:title "Tie" :slide "2:00"}
               {:title "Unfinished"}]
        result (review/order-songs songs)]
    (is (= ["Fallback" "Tie" "Slide wins" "Hour" "Unfinished"] (mapv :title result)))
    (is (= [1 2 3 4 5] (mapv :order result)))))

(deftest approval-persists-timestamp-order-for-old-drafts
  (let [dir (java.nio.file.Files/createTempDirectory "review-order" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123"})
      (let [id (:id (first (vimeo/queue)))
            songs [{:title "Later" :author "Author" :slide "10:00" :order 1}
                   {:title "Earlier" :author "Author" :start "2:00" :order 1}]]
        (review/stage! id 0 (assoc sample :songs songs))
        (let [html (:body (admin/review-page {:path-params {:id (str id)}}))]
          (is (not (str/includes? html "song-0-order"))))
        (review/approve! id 1)
        (is (= [{:name "Earlier" :order_in_service 1} {:name "Later" :order_in_service 2}]
               (sql/query (db/get-datasource)
                 ["select s.name, p.order_in_service from song_performance p join song_variation v on v.id=p.song_variation_id join song s on s.id=v.song_id order by p.order_in_service"] vimeo/opts)))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
