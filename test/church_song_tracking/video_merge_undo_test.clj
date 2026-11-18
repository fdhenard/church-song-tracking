(ns church-song-tracking.video-merge-undo-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str] [clojure.java.io :as io] [clojure.data.json :as json]
            [church-song-tracking.config :as config] [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo] [church-song-tracking.video-jobs :as jobs]
            [church-song-tracking.video-merge :as merge] [church-song-tracking.import-review :as review]
            [church-song-tracking.admin :as admin] [next.jdbc.sql :as sql]))

(defn with-merge [completed? f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "undo-merge-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache")
                  config/get-slide-archive-dir #(str dir "/slides") jobs/start! (fn [& _])]
      (db/migrate! nil)
      (vimeo/select! {"links" "https://vimeo.com/123\nhttps://vimeo.com/456"})
      (let [ds (db/get-datasource) ids (mapv :id (sort-by :id (vimeo/queue))) [a b] ids]
        (doseq [id ids :let [folder (io/file (config/get-video-cache-dir) (str id))]]
          (.mkdirs folder)
          (spit (io/file folder "video.mp4") (str "original-" id))
          (spit (io/file folder "metadata.json") (json/write-str {:title (str "Video " id) :duration 10}))
          (sql/update! ds :vimeo_import_selection {:job_status "downloaded" :vimeo_video_title (str "Video " id)} {:id id}))
        (is (thrown? clojure.lang.ExceptionInfo
                     (merge/create! {"include" (mapv str ids) "part" (mapv str ids) "recording-title" "  "})))
        (merge/create! {"include" (mapv str ids) "part" (mapv str ids) "recording-title" " Combined worship "})
        (is (= "Combined worship" (:vimeo_video_title (review/selection a))))
        (is (= "Combined worship" (:title (json/read-str (slurp (io/file (config/get-video-cache-dir) (str a) "merge-plan.json")) :key-fn keyword))))
        (is (= (str "Video " a) (:vimeo_video_title (first (merge/sources ds a)))))
        (when completed?
          (let [folder (io/file (config/get-video-cache-dir) (str a))]
            (io/copy (io/file folder "video.mp4") (io/file folder "source-original.mp4"))
            (spit (io/file folder "video.mp4") "combined")
            (spit (io/file folder "metadata.json")
                  (json/write-str {:merged true :duration 20 :sources [{:duration 10} {:duration 10}]}))))
        (f ds a b)))))

(deftest restores-completed-and-failed-merges
  (doseq [completed? [true false]]
    (with-merge completed?
      (fn [ds a b]
        (let [revision (:revision (review/selection a))]
          (is (thrown? clojure.lang.ExceptionInfo (merge/undo! a revision false)))
          (is (thrown? clojure.lang.ExceptionInfo (merge/undo! a (dec revision) true)))
          (is (str/includes? (pr-str (admin/import-selection-row (review/selection a) {} {})) "Undo merged recording"))
          (is (= "/imports?notice=merge-undone"
                 (get-in (admin/save-import-selection! {:params {"action" "undo-merge" "selection" (str a)
                                                                "revision" (str revision) "confirm-undo" "yes"}})
                         [:headers "Location"])))
          (is (false? (:is_merged (review/selection a))))
          (is (= (str "Video " a) (:vimeo_video_title (review/selection a))))
          (is (nil? (:merged_into_selection_id (review/selection b))))
          (is (empty? (merge/sources ds a)))
          (is (= (str "original-" a) (slurp (jobs/downloaded-file a))))
          (is (= (str "original-" b) (slurp (jobs/downloaded-file b))))
          (is (= 10 (:duration (json/read-str (slurp (io/file (config/get-video-cache-dir) (str a) "metadata.json")) :key-fn keyword))))
          (is (thrown? clojure.lang.ExceptionInfo (merge/undo! a revision true))))))))

(deftest rejects-active-merges-and-rolls-back-cache-on-database-failure
  (with-merge true
    (fn [ds a b]
      (let [revision (:revision (review/selection a)) real-delete sql/delete!]
        (sql/update! ds :vimeo_import_selection {:job_status "merging"} {:id a})
        (is (thrown? clojure.lang.ExceptionInfo (merge/undo! a revision true)))
        (sql/update! ds :vimeo_import_selection {:job_status "downloaded"} {:id a})
        (with-redefs [sql/delete! (fn [& _] (throw (java.sql.SQLException. "Simulated failure")))]
          (is (thrown? java.sql.SQLException (merge/undo! a revision true))))
        (is (:is_merged (review/selection a)))
        (is (= a (:merged_into_selection_id (review/selection b))))
        (is (= 2 (count (merge/sources ds a))))
        (is (= "combined" (slurp (jobs/downloaded-file a))))
        (is (= a (merge/undo! a revision true)))))))

(deftest refuses-approved-merges-and-missing-originals
  (with-merge true
    (fn [ds a _]
      (let [revision (:revision (review/selection a))
            person (:id (sql/insert! ds :person {:name "Person"} vimeo/opts))
            service (:id (sql/insert! ds :church_service {:date (java.time.LocalDate/parse "2025-04-13") :title "Approved" :preacher_id person :song_leader_id person :sermon_title "Sermon"} vimeo/opts))]
        (sql/update! ds :vimeo_import_selection {:imported_service_id service} {:id a})
        (is (thrown? clojure.lang.ExceptionInfo (merge/undo! a revision true)))
        (sql/update! ds :vimeo_import_selection {:imported_service_id nil} {:id a})
        (io/delete-file (io/file (config/get-video-cache-dir) (str a) "source-original.mp4"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"original first recording is missing" (merge/undo! a revision true)))
        (is (:is_merged (review/selection a)))
        (is (= "combined" (slurp (jobs/downloaded-file a))))))))
