(ns church-song-tracking.song-statistics-test
 (:require [clojure.test :refer [deftest is run-tests]] [clojure.string :as str]
           [next.jdbc :as jdbc] [next.jdbc.sql :as sql]
           [church-song-tracking.config :as config] [church-song-tracking.db :as db]
           [church-song-tracking.song-statistics :as stats] [church-song-tracking.admin :as admin]
           [church-song-tracking.vimeo-import :as vimeo]))
(deftest usage-counts-distinct-services-across-variations-and-filters-by-leader
 (let [dir (java.nio.file.Files/createTempDirectory "song-statistics" (make-array java.nio.file.attribute.FileAttribute 0))]
  (with-redefs [config/get-db-path #(str dir "/songs") config/get-video-cache-dir #(str dir "/cache") config/get-slide-archive-dir #(str dir "/slides")]
   (db/migrate! nil)
   (with-open [ds (jdbc/get-connection (db/get-datasource))]
    (let [insert (fn [table data] (:id (sql/insert! ds table data vimeo/opts)))
          a (insert :person {:name "Leader A"}) b (insert :person {:name "Leader B"}) preacher (insert :person {:name "Preacher only"})
          service (fn [date leader] (insert :church_service {:date (java.time.LocalDate/parse date) :title date :sermon_title "Sermon" :preacher_id preacher :song_leader_id leader}))
          s1 (service "2026-01-01" a) s2 (service "2026-01-08" a) s3 (service "2026-01-15" b)
          _ (service "2026-01-22" a)
          song (fn [title author] (insert :song {:name title :artist author}))
          variation (fn [id name] (insert :song_variation {:song_id id :variation_name name :prominent_electric_guitar false}))
          alpha (song "Alpha" "Writer") alpha1 (variation alpha "Default") alpha2 (variation alpha "Acoustic")
          beta (song "Beta" "Writer") beta1 (variation beta "Default")
          different (song "Alpha" "Other writer") different1 (variation different "Default")
          unused (song "Unused song" "Writer")
          perform (fn [service variation] (insert :song_performance {:church_service_id service :song_variation_id variation :order_in_service 1}))]
     (doseq [[s v] [[s1 alpha1] [s1 alpha2] [s2 alpha2] [s3 alpha1] [s2 beta1] [s3 beta1] [s1 different1]]] (perform s v))
     (let [report (stats/report ds nil) first-song (first (:songs report))]
      (is (= {:services 3 :performances 7 :songs 3} (:totals report)))
      (is (= alpha (:id first-song)))
      (is (= 4 (:performances first-song)))
      (is (= 3 (:services first-song)))
      (is (= 2 (:repeats first-song)))
      (is (= 100.0 (:selection-rate first-song)))
      (is (= "2026-01-15" (str (:last_used first-song))))
      (is (= [alpha beta different] (mapv :id (:songs report))))
      (is (not-any? #(= unused (:id %)) (:songs report))))
     (let [report (stats/report ds a)]
      (is (= {:services 2 :performances 5 :songs 3} (:totals report)))
      (is (= 3 (:performances (first (:songs report)))))
      (is (= 50.0 (:selection-rate (first (filter #(= beta (:id %)) (:songs report)))))))
     (is (= #{a b} (set (map :id (stats/leaders ds)))))
     (is (thrown? clojure.lang.ExceptionInfo (stats/report ds 99999)))
     (doseq [n (range 22)] (perform s3 (variation (song (str "Rare " n) "Writer") "Default")))
     (is (= 20 (count (:songs (stats/report ds nil)))))
     (is (= [alpha beta] (mapv :id (take 2 (:songs (stats/report ds nil))))))
     (let [page (admin/statistics-page {:params {"leader" (str a)}})]
      (is (= 200 (:status page)))
      (is (str/includes? (:body page) "Leader A")))
     (is (= 422 (:status (admin/statistics-page {:params {"leader" "invalid"}}))))
     (jdbc/execute! ds ["delete from song_performance"])
     (is (= [] (:songs (stats/report ds nil))))
     (is (= 0 (:services (:totals (stats/report ds nil))))))))))
(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
