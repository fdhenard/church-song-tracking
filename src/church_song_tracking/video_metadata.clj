(ns church-song-tracking.video-metadata
  "Original video metadata is independent of editable service review fields."
  (:require [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]))
(def opts {:builder-fn rs/as-unqualified-lower-maps})
(defn present [value] (when (and (string? value) (not (str/blank? value))) value))
(defn original-extraction-title [row]
  (try (let [data (edn/read-string (:extracted_data row))]
         (or (present (:vimeo_video_title data)) (present (get-in data [:draft :vimeo-video-title]))
             (present (get-in data [:draft :title])))) (catch Exception _ nil)))
(defn downloaded-title [id]
  (try (present (:title (json/read-str (slurp (io/file (config/get-video-cache-dir) (str id) "metadata.json")) :key-fn keyword)))
       (catch Exception _ nil)))
(defn canonical-url [url]
  (when-let [[_ id] (re-find #"https?://(?:www\.)?vimeo\.com/([0-9]+)" (or url ""))] (str "https://vimeo.com/" id)))
(defn catalog-titles []
  (try (into {} (map (juxt :url :title) (:videos (edn/read-string (slurp (io/file (config/get-video-cache-dir) "vimeo-catalog.edn"))))))
       (catch Exception _ {})))
(defn original-title [row]
  (or (present (:vimeo_video_title row)) (downloaded-title (:id row)) (original-extraction-title row)
      (present (get (catalog-titles) (:vimeo_url row)))))
(defn cache-titles []
  (into {} (keep (fn [folder]
                  (try (let [data (json/read-str (slurp (io/file folder "metadata.json")) :key-fn keyword)
                             id (some->> (:webpage_url data) (re-find #"/([0-9]+)(?:[?#].*)?$") second)
                             title (present (:title data))]
                         (when (and id title) [(str "https://vimeo.com/" id) title]))
                       (catch Exception _ nil)))
                (or (.listFiles (io/file (config/get-video-cache-dir))) []))))
(defn backfill! [_]
  (let [ds (db/get-datasource) catalog (catalog-titles) cached (cache-titles)
        selections (sql/query ds ["select * from vimeo_import_selection"] opts)
        selected-count (atom 0) service-count (atom 0) unavailable (atom [])]
    (doseq [row selections :when (not (present (:vimeo_video_title row)))]
      (when-let [title (or (downloaded-title (:id row)) (original-extraction-title row) (present (get catalog (:vimeo_url row))))]
        (sql/query ds ["update vimeo_import_selection set vimeo_video_title = ? where id = ? and vimeo_video_title is null" title (:id row)])
        (swap! selected-count inc)))
    (let [known (into {} (map (juxt :vimeo_url :vimeo_video_title) (sql/query ds ["select * from vimeo_import_selection"] opts)))]
      (doseq [service (sql/query ds ["select * from church_service where vimeo_video_title is null"] opts)
              :let [url (canonical-url (:link_vimeo service))] :when url]
        (if-let [title (or (present (get known url)) (present (get cached url)) (present (get catalog url)))]
          (do (sql/query ds ["update church_service set vimeo_video_title = ? where id = ? and vimeo_video_title is null" title (:id service)])
              (swap! service-count inc))
          (swap! unavailable conj (:id service)))))
    {:selections-updated @selected-count :services-updated @service-count :unavailable-service-ids @unavailable}))
