(ns church-song-tracking.vimeo-import
  "Service selection only. No video downloads or library writes."
  (:require [church-song-tracking.db :as db]
            [church-song-tracking.config :as config]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [javax.xml.parsers DocumentBuilderFactory]
           [java.io ByteArrayInputStream]))

(def church-url "https://vimeo.com/ewobc")
(def feed-url (str church-url "/videos/rss"))
(def opts {:builder-fn rs/as-unqualified-lower-maps})

(defn video-url [value]
  (let [value (str/trim (or value ""))]
    (if-let [[_ id] (re-matches #"https://(?:www\.)?vimeo\.com/([0-9]+)(?:[/?#][^\s]*)?" value)]
      (str "https://vimeo.com/" id)
      (throw (ex-info "Use a Vimeo video link such as https://vimeo.com/123456789." {})))))

(defn imported-video-urls [services]
  (set (keep (fn [service]
               (try (video-url (str/replace (or (:link_vimeo service) "") #"^http:" "https:"))
                    (catch clojure.lang.ExceptionInfo _ nil)))
             services)))

(defn imported-recordings [ds]
  (sql/query ds ["select link_vimeo from church_service union select vimeo_url as link_vimeo from service_merge_source union select vimeo_url as link_vimeo from service_video_source where church_service_id is not null"] opts))

(defn parse-feed [xml]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
                  (.setFeature "http://xml.org/sax/features/external-general-entities" false)
                  (.setFeature "http://xml.org/sax/features/external-parameter-entities" false)
                  (.setXIncludeAware false)
                  (.setExpandEntityReferences false))
        doc (.parse (.newDocumentBuilder factory) (ByteArrayInputStream. (.getBytes xml "UTF-8")))
        items (.getElementsByTagName doc "item")
        field (fn [item tag] (some-> (.item (.getElementsByTagName item tag) 0) .getTextContent str/trim))]
    (->> (range (.getLength items))
         (keep (fn [n]
                 (let [item (.item items n)]
                   (try (let [media (.item (.getElementsByTagName item "media:content") 0)
                              duration (when media (parse-long (.getAttribute media "duration")))]
                          (cond-> {:url (video-url (field item "link"))
                                   :title (field item "title") :published (field item "pubDate")}
                            duration (assoc :duration duration)))
                        (catch clojure.lang.ExceptionInfo _ nil)))))
         (reduce (fn [videos video]
                   (if (some #(= (:url %) (:url video)) videos) videos (conj videos video))) []))))

(defn- fetch-feed []
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) .build)
        request (-> (HttpRequest/newBuilder (URI/create feed-url))
                    (.timeout (Duration/ofSeconds 20))
                    (.header "User-Agent" "ChurchSongTracking/1.0") .GET .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "Vimeo did not return its public feed." {})))
    (let [videos (parse-feed (.body response))]
      (when (empty? videos) (throw (ex-info "No videos were found in Vimeo's public feed." {})))
      videos)))

(defn queue []
  (sql/query (db/get-datasource) ["select * from vimeo_import_selection order by id desc"] opts))

(defn selected-urls [params]
  (let [selected (get params "video" [])
        selected (if (string? selected) [selected] selected)
        manual (remove str/blank? (str/split-lines (get params "links" "")))
        urls (vec (distinct (map video-url (concat selected manual))))]
    (when (empty? urls) (throw (ex-info "Select at least one video or paste a Vimeo video link." {})))
    (when (> (count urls) 100) (throw (ex-info "Select up to 100 videos at a time." {})))
    urls))

(defn title-key [title] (str/join " " (str/split (str/lower-case (str/trim (or title ""))) #"\s+")))
(defn video-sort-key [{:keys [title url]}]
  (let [[_ month day year] (re-find #"(?i)^\s*livestream\s*[:\-]?\s*(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?!\d)" (or title ""))
        date (when year
               (try (str (java.time.LocalDate/of (+ (parse-long year) (if (= 2 (count year)) 2000 0))
                                                  (parse-long month) (parse-long day)))
                    (catch Exception _ nil)))
        id (or (some->> (re-find #"/([0-9]+)$" (or url "")) second bigint) 0N)]
    [(or date "0000-00-00") id]))
(defn newest-videos [videos] (sort-by video-sort-key #(compare %2 %1) videos))

(declare discovery-cache catalog-file)
(defn cached-catalog []
  (or (:videos @discovery-cache)
      (try (:videos (edn/read-string (slurp (catalog-file)))) (catch Exception _ []))))
(defn discarded-services []
  (sql/query (db/get-datasource) ["select * from discarded_vimeo_service order by discarded_at desc"] opts))

(defn discard-service! [url reason]
  (let [reason (str/trim (or reason ""))
        _ (when-not (<= 1 (count reason) 2000)
            (throw (ex-info "A discard reason is required (up to 2000 characters)." {})))
        url (video-url url)
        video (first (filter #(= url (:url %)) (cached-catalog)))]
    (jdbc/with-transaction [tx (db/get-datasource)]
      (sql/query tx ["select id from import_approval_lock where id = 1 for update"])
      (let [row (first (sql/query tx ["select * from vimeo_import_selection where vimeo_url = ? for update" url] opts))
            imported (imported-video-urls (imported-recordings tx))]
        (when (or (contains? imported url)
                  (and row (or (:imported_service_id row) (:draft_data row)
                               (not (#{"selected" "failed" "downloaded"} (:job_status row)))
                               (and (.isFile (io/file (config/get-video-cache-dir) (str (:id row)) "metadata.json"))
                                    (some #(.isFile (io/file (config/get-video-cache-dir) (str (:id row)) (str "video." %))) ["mp4" "webm" "mkv" "mov"])))))
          (throw (ex-info "Only services available for download can be discarded. This service is already processing, downloaded, or imported." {})))
        (sql/query tx ["merge into discarded_vimeo_service (vimeo_url, video_title, duration_seconds, discard_reason) key(vimeo_url) values (?, ?, ?, ?)"
                       url (or (:title video) url) (:duration video) reason])
        (when row (sql/delete! tx :vimeo_import_selection {:id (:id row)}))))))

(defn restore-service! [url]
  (sql/delete! (db/get-datasource) :discarded_vimeo_service {:vimeo_url (video-url url)}))

(defn select! [params]
  (let [urls (selected-urls params) titles (into {} (map (juxt :url :title) (cached-catalog)))]
    (jdbc/with-transaction [tx (db/get-datasource)]
      (sql/query tx ["select id from import_approval_lock where id = 1 for update"])
      (let [discarded (set (map :vimeo_url (sql/query tx ["select vimeo_url from discarded_vimeo_service"] opts)))]
        (when (some discarded urls)
          (throw (ex-info "This video was discarded. Restore it from Discarded services before downloading." {}))))
      (doseq [url urls]
        ;; MERGE's unique key makes repeated selections and concurrent requests safe.
        (sql/query tx ["merge into vimeo_import_selection (vimeo_url) key(vimeo_url) values (?)" url])
        (when-let [title (get titles url)]
          (sql/query tx ["update vimeo_import_selection set vimeo_video_title = ? where vimeo_url = ? and vimeo_video_title is null" title url]))))
    (count urls)))

(defn remove! [id]
  (when-not (and (string? id) (parse-long id) (pos? (parse-long id)))
    (throw (ex-info "Invalid selection." {})))
  (let [row (sql/get-by-id (db/get-datasource) :vimeo_import_selection (parse-long id) opts)]
    (when (or (:is_merged row) (:merged_into_selection_id row))
      (throw (ex-info "Merged recordings and their source records are kept together for provenance. They cannot be removed separately." {})))
    (sql/delete! (db/get-datasource) :vimeo_import_selection {:id (parse-long id)})))

(defonce discovery-cache (atom nil))
(defonce discovery-lock (Object.))
(def cache-ttl-ms (* 60 60 1000))
(defn catalog-file [] (io/file (config/get-video-cache-dir) "vimeo-catalog.edn"))
(defn discovery-status [] (select-keys @discovery-cache [:refreshing? :error :time :complete?]))
(defn clear-discovery-cache! []
  ;; Keep the last successful catalog visible while refreshing it.
  (locking discovery-lock
    (swap! discovery-cache #(assoc (or % (try (edn/read-string (slurp (catalog-file))) (catch Exception _ nil)) {}) :time 0))))
(defn parse-catalog [data]
  (->> (:entries data)
       (keep (fn [entry]
               (when (and (re-matches #"[0-9]+" (str (:id entry))) (seq (:title entry)))
                 {:url (str "https://vimeo.com/" (:id entry)) :title (:title entry)
                  :published (or (:upload_date entry) "") :duration (:duration entry)})))
       (reduce (fn [result video] (if (some #(= (:url %) (:url video)) result) result (conj result video))) [])))
(defn fetch-catalog []
  (let [output (java.io.File/createTempFile "vimeo-catalog-" ".json")
        errors (java.io.File/createTempFile "vimeo-catalog-" ".log")]
    (try
      (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                  [(:python (config/video-settings)) "scripts/discover_vimeo.py"
                                   "--cache-dir" (config/get-video-cache-dir)])
                             (.redirectOutput output) (.redirectError errors)))]
        (when-not (.waitFor process 600 java.util.concurrent.TimeUnit/SECONDS)
          (.destroyForcibly process)
          (throw (ex-info "Vimeo catalog refresh timed out. The previous catalog is still available." {})))
        (when-not (zero? (.exitValue process))
          (throw (ex-info "Vimeo catalog refresh failed. Check the video tools and try again; the previous catalog is still available." {})))
        (let [videos (parse-catalog (json/read-str (slurp output) :key-fn keyword))]
          (when (empty? videos) (throw (ex-info "Vimeo returned an empty catalog. The previous catalog was kept." {})))
          videos))
      (finally (.delete output) (.delete errors)))))
(defn discover []
  (locking discovery-lock
    (when (nil? @discovery-cache)
      (reset! discovery-cache
        (or (try (edn/read-string (slurp (catalog-file))) (catch Exception _ nil))
            {:videos (try (fetch-feed) (catch Exception _ [])) :time 0})))
    (let [cached @discovery-cache]
      (when (and (not (:refreshing? cached))
                 (>= (- (System/currentTimeMillis) (or (:time cached) 0)) cache-ttl-ms))
        (swap! discovery-cache assoc :refreshing? true :error nil)
        (future
          (try
            (let [catalog {:videos (fetch-catalog) :time (System/currentTimeMillis) :complete? true}
                  file (catalog-file)
                  tmp (io/file (str file ".tmp"))]
              (.mkdirs (.getParentFile file))
              (spit tmp (pr-str catalog))
              (java.nio.file.Files/move (.toPath tmp) (.toPath file)
                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
              (reset! discovery-cache catalog))
            (catch Exception e
              ;; Back off after failures rather than retrying on every request.
              (swap! discovery-cache assoc :refreshing? false :time (System/currentTimeMillis) :error (.getMessage e)))))))
    (:videos @discovery-cache)))
