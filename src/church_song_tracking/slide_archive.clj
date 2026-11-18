(ns church-song-tracking.slide-archive
  "Permanent, immutable title-slide copies and idempotent backfill from retained local sources."
  (:require [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.nio.file Files StandardCopyOption CopyOption]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(def opts {:builder-fn rs/as-unqualified-lower-maps})
(defn read-source [value] (try (when (seq value) (edn/read-string value)) (catch Exception _ nil)))
(defn time-index [value]
  (when (and (string? value) (re-matches #"(?:\d+:)?[0-5]?\d:[0-5]\d" value))
    (long (Math/round (/ (reduce #(+ (* %1 60) %2) 0 (map parse-long (str/split value #":"))) 3.0)))))
(defn frame-index [index time]
  (let [n (if (integer? index) index (time-index time))]
    (when (and n (<= 0 n 1000000)) n)))
(defn safe-image-file [path]
  (when (seq path)
    (let [root (.toPath (.getCanonicalFile (io/file (config/get-slide-archive-dir))))
          file (.getCanonicalFile (io/file path))]
      (when (and (.startsWith (.toPath file) root) (.isFile file) (pos? (.length file))) file))))
(defn digest [file]
  (apply str (map #(format "%02x" (bit-and 255 %))
                 (.digest (MessageDigest/getInstance "SHA-256") (Files/readAllBytes (.toPath (io/file file)))))))
(defn recover-frame! [folder index]
  (when (some #(.isFile (io/file folder (str "video." %))) ["mp4" "webm" "mkv" "mov"])
    (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                [(:python (config/video-settings)) "scripts/extract_slide_frame.py"
                                 "--directory" (str folder) "--index" (str index)])
                           (.redirectOutput ProcessBuilder$Redirect/DISCARD)
                           (.redirectError (io/file folder "archive-frame.log"))))]
      (when-not (.waitFor process 75 TimeUnit/SECONDS)
        (.destroyForcibly process)
        (throw (ex-info "Recovering the title-slide image timed out." {})))
      (when-not (zero? (.exitValue process))
        (throw (ex-info "The title-slide frame could not be recovered from the retained video." {}))))))
(defn preserve! [{:keys [selection-id service-id kind index time required?]}]
  (let [index (frame-index index time)]
    (when index
      (let [folder (when selection-id (io/file (config/get-video-cache-dir) (str selection-id)))
            source (when folder (io/file folder "frames" (format "frame-%06d.jpg" (inc index))))]
        (when (and folder (not (.isFile source))) (recover-frame! folder index))
        (if-not (and source (.isFile source) (pos? (.length source)))
          (when required? (throw (ex-info "The selected title-slide image is missing. Restore its cached video/frame before approving this service." {:selection-id selection-id :frame index})))
          (let [hash (digest source)
                root (io/file (config/get-slide-archive-dir))
                target (.getCanonicalFile (io/file root (str "service-" service-id) (str kind "-" hash ".jpg")))]
            (when-not (and (.isFile target) (= hash (digest target)))
              (.mkdirs (.getParentFile target))
              (let [tmp (Files/createTempFile (.toPath (.getParentFile target)) ".slide-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
                (try
                  (Files/copy (.toPath source) tmp (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
                  (when-not (= hash (digest (.toFile tmp))) (throw (ex-info "The archived image did not match its source." {})))
                  (Files/move tmp (.toPath target) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
                  (finally (Files/deleteIfExists tmp)))))
            (.getAbsolutePath target)))))))

(defn cache-urls []
  (into {} (keep (fn [folder]
                   (try
                     (let [file (io/file folder "metadata.json")
                           metadata (when (.isFile file) (json/read-str (slurp file) :key-fn keyword))
                           id (some->> (:webpage_url metadata) (re-find #"/([0-9]+)(?:[?#].*)?$") second)]
                       ;; Positive cache IDs are imports; negative IDs are historical service recovery sources.
                       (when (and id (parse-long (.getName folder))) [(str "https://vimeo.com/" id) (parse-long (.getName folder))]))
                     (catch Exception _ nil)))
                 (or (.listFiles (io/file (config/get-video-cache-dir))) []))))
(defn archive-record! [tx table row column request]
  (cond
    (safe-image-file (get row column)) {:status :already-archived}
    (nil? (frame-index (:index request) (:time request))) {:status :not-applicable}
    :else (try
            (if-let [path (preserve! request)]
              (do (sql/update! tx table {column path} {:id (:id row)}) {:status :archived :path path})
              {:status :missing})
            (catch Exception e {:status :error :message (.getMessage e)}))))
(defn backfill! [_]
  (let [datasource (db/get-datasource)
        cache (cache-urls)
        services (sql/query datasource ["select * from church_service order by id"] opts)
        results
        (vec (mapcat
          (fn [service]
            (jdbc/with-transaction [tx datasource]
              (let [service (sql/get-by-id tx :church_service (:id service) opts)
                    selection (first (sql/query tx ["select * from vimeo_import_selection where imported_service_id = ? order by id desc" (:id service)] opts))
                    performances (sql/query tx ["select * from song_performance where church_service_id = ? order by order_in_service" (:id service)] opts)
                    original-service (or (:service (some #(read-source (:source_data %)) performances)) (read-source (:draft_data selection)))
                    url (when-let [[_ id] (re-find #"https?://(?:www\.)?vimeo\.com/([0-9]+)" (or (:link_vimeo service) ""))] (str "https://vimeo.com/" id))
                    selection-id (or (:id selection) (get cache url))
                    base {:selection-id selection-id :service-id (:id service)}
                    sermon (archive-record! tx :church_service service :sermon_title_image_path
                             (assoc base :kind "sermon" :index (:sermon-evidence-index original-service)
                                         :time (:sermon_title_slide_time_vimeo service)))]
                (cons (assoc sermon :service-id (:id service) :kind :sermon)
                  (doall (for [performance performances
                               :let [source (read-source (:source_data performance))
                                     song (:song source)
                                     slide (if (map? song) (:slide song) (:song-start-time--with-slide--vimeo source))
                                     result (archive-record! tx :song_performance performance :title_image_path
                                              (assoc base :kind (str "song-" (:order_in_service performance))
                                                          :index (:evidence-index song) :time slide))]]
                           (assoc result :service-id (:id service) :performance-id (:id performance) :kind :song))))))) services))]
    {:counts (frequencies (map :status results)) :results results :archive-directory (config/get-slide-archive-dir)}))
