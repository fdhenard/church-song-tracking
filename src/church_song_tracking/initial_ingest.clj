
(ns church-song-tracking.initial-ingest
  "Read source CSV data for the initial database ingest."
  (:require [church-song-tracking.config :as config]
            [church-song-tracking.csv-utils :as csv-utils]
            [church-song-tracking.db :as db]
            [church-song-tracking.song-merge :as song-merge]
            [church-song-tracking.song-variation :as variation]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def col-name-mapping
  {"Song Name" :song-name
   "Artist on Slide" :artist-on-slide
   "Artist from Youtube" :artist-from-youtube
   "Song Order" :song-order
   "Song time in service - youtube" :song-time-in-service-youtube
   "Song Start time - no slide - vimeo" :song-start-time--no-slide--vimeo
   "Song Start time - with slide - vimeo" :song-start-time--with-slide--vimeo
   "Example Link" :example-link
   "Prominent Electric Guitar" :prominent-electric-guitar
   "Date" :date
   "Leader" :leader
   "Service link - youtube" :service-link--youtube
   "Service link - vimeo" :service-link--vimeo
   "Sermon Title Slide Time - youtube" :sermon-title-slide-time--youtube
   "Sermon Title Slide Time - vimeo" :sermon-title-slide-time--vimeo
   "Preacher" :preacher
   "Sermon Series" :sermon-series
   "Sermon Title" :sermon-title
   "Sermon Topic" :sermon-topic
   "Holiday" :holiday
   "Interest for Next Men's Retreat" :interest-for-next-mens-retreat})

(defn- present [s] (when-not (str/blank? s) (str/trim s)))

(defn- ensure-record! [tx table data]
  (if (= table :song_variation)
    (variation/ensure! tx data false)
  (or (when (= table :song) (song-merge/find-song-id tx (:name data) (:artist data)))
      (:id (first (sql/find-by-keys tx table data
                                 {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))
      (:id (sql/insert! tx table data
                       {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))))

(defn- named! [tx table name]
  (when-let [name (present name)] (ensure-record! tx table {:name name})))

(defn- ingest-row! [tx row]
  (let [date (java.time.LocalDate/parse (:date row)
               (java.time.format.DateTimeFormatter/ofPattern "M/d/uuuu"))
        order (Integer/parseInt (:song-order row))
        _ (when-not (pos? order) (throw (ex-info "Song order must be positive" {:row row})))
        service {:date date :title (str date)
                 :link_youtube (present (:service-link--youtube row))
                 :link_vimeo (present (:service-link--vimeo row))
                 :preacher_id (named! tx :person (:preacher row))
                 :song_leader_id (named! tx :person (:leader row))
                 :sermon_series_id (named! tx :sermon_series (:sermon-series row))
                 :sermon_title (:sermon-title row)
                 :sermon_title_slide_time_youtube (present (:sermon-title-slide-time--youtube row))
                 :sermon_title_slide_time_vimeo (present (:sermon-title-slide-time--vimeo row))
                 :sermon_topic (present (:sermon-topic row))
                 :holiday_id (named! tx :holiday (:holiday row))
                 :interest_for_next_mens_retreat (boolean (present (:interest-for-next-mens-retreat row)))}
        prior-service (first (sql/find-by-keys tx :church_service {:date date}
                              {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))
        metadata (dissoc service :date :interest_for_next_mens_retreat)
        _ (when (and prior-service
                     (not= metadata (select-keys prior-service (keys metadata))))
            (throw (ex-info "Service metadata differs for this date; import aborted" {:date date})))
        service-id (or (:id prior-service) (ensure-record! tx :church_service service))
        _ (when (and prior-service (:interest_for_next_mens_retreat service))
            (sql/update! tx :church_service {:interest_for_next_mens_retreat true} {:id service-id}))
        existing (first (sql/find-by-keys tx :song_performance
                         {:church_service_id service-id :order_in_service order}
                         {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
    (if existing
      (if (= (:source_data existing) (pr-str row))
        :skipped
        (throw (ex-info "Existing performance differs; import aborted" {:date date :order order})))
      (let [song-id (ensure-record! tx :song
                      {:name (:song-name row)
                       :artist (or (present (:artist-on-slide row))
                                   (present (:artist-from-youtube row)) "")})
            variation-id (ensure-record! tx :song_variation
                           {:song_id song-id :variation_name "Default"
                            :example_link (present (:example-link row))
                            :prominent_electric_guitar (when (= "y" (str/lower-case (:prominent-electric-guitar row))) true)})]
        (sql/insert! tx :song_performance
          {:song_variation_id variation-id :church_service_id service-id
           :order_in_service order
           :time_in_service_youtube (present (:song-time-in-service-youtube row))
           :time_in_service_vimeo (or (present (:song-start-time--with-slide--vimeo row))
                                      (present (:song-start-time--no-slide--vimeo row)))
           :source_data (pr-str row)})
        :imported))))

(defn initial-ingest
  "Import a CSV transactionally. Repeating the same import skips existing rows."
  ([] (initial-ingest {}))
  ([{:keys [path]}]
   (let [path (or path (str (config/get-data-base-dir) "/Church Songs Played - Sheet1.csv"))
         rows (csv-utils/csv->maps path col-name-mapping)]
     (doseq [[index row] (map-indexed vector rows)
             key [:song-name :date :song-order :leader :preacher :sermon-title]]
       (when (str/blank? (get row key))
         (throw (ex-info "Missing required CSV value" {:row (+ index 2) :column key}))))
     (db/migrate! nil)
     (let [result (jdbc/with-transaction [tx (db/get-datasource)]
                    (variation/lock! tx)
                    (frequencies (mapv #(ingest-row! tx %) rows)))]
       (println result)
       result))))
