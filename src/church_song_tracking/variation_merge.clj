(ns church-song-tracking.variation-merge
  "Explicit, transactional cleanup of duplicate variation names within one song."
  (:require [church-song-tracking.musical-key :as musical-key]
            [church-song-tracking.db :as db]
            [church-song-tracking.song-variation :as variation]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:import [java.security MessageDigest]))
(def opts variation/opts)
(defn values [value] (cond (nil? value) [] (string? value) [value] :else value))
(defn ids! [value]
  (let [ids (mapv #(or (some-> % str parse-long) (throw (ex-info "Invalid variation selection." {}))) (values value))]
    (when-not (and (<= 2 (count ids) 50) (every? pos? ids) (= (count ids) (count (distinct ids))))
      (throw (ex-info "Select two to fifty different variations." {})))
    ids))
(defn all-variations [ds]
  (sql/query ds ["select v.*, s.name as song_name, s.artist as song_artist, (select count(*) from song_performance p where p.song_variation_id=v.id) as performance_count from song_variation v join song s on s.id=v.song_id order by s.name, v.song_id, v.id"] opts))
(defn groups [ds song-id]
  (->> (all-variations ds) (filter #(or (nil? song-id) (= song-id (:song_id %))))
       (group-by #(vector (:song_id %) (variation/name-key (:variation_name %)))) vals
       (filter #(> (count %) 1))
       (sort-by #(vector (variation/name-key (:song_name (first %))) (:song_id (first %)) (variation/name-key (:variation_name (first %)))))))
(defn canonical-id [ds old-id]
  (:canonical_variation_id (sql/get-by-id ds :variation_merge_alias old-id :old_variation_id opts)))
(defn snapshot [ds song-id lock?]
  (let [suffix (if lock? " for update" "")]
    {:song (first (sql/query ds [(str "select * from song where id=?" suffix) song-id] opts))
     :variations (sql/query ds [(str "select * from song_variation where song_id=? order by id" suffix) song-id] opts)
     :performances (sql/query ds [(str "select id, song_variation_id, church_service_id from song_performance where song_variation_id in (select id from song_variation where song_id=?) order by id" suffix) song-id] opts)}))
(defn fingerprint [data]
  (let [stable [(mapv (:song data) [:id :name :artist :time_added])
                (mapv #(mapv % [:id :song_id :variation_name :example_link :prominent_electric_guitar :musical_key :time_added]) (:variations data))
                (mapv #(mapv % [:id :song_variation_id :church_service_id]) (:performances data))]]
    (apply str (map #(format "%02x" (bit-and 255 %))
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (pr-str stable) "UTF-8"))))))
(defn plan [ds params lock?]
  (let [song-id (some-> (get params "song") parse-long)
        candidate-ids (ids! (get params "candidate")) ids (ids! (get params "source"))
        target (some-> (get params "retained") parse-long)
        data (snapshot ds song-id lock?)
        candidates (filterv #((set candidate-ids) (:id %)) (:variations data))
        selected (filterv #((set ids) (:id %)) candidates)
        name (str/trim (get params "variation_name" "")) link (str/trim (get params "example_link" ""))
        guitar (get params "guitar")]
    (when-not (and (:song data) (= (count candidate-ids) (count candidates)) (= (count ids) (count selected))
                   (every? (set candidate-ids) ids) (contains? (set ids) target))
      (throw (ex-info "Choose a retained variation from the selected records of this song. Reload if any variation was already merged." {})))
    (when-not (= 1 (count (set (map #(variation/name-key (:variation_name %)) candidates))))
      (throw (ex-info "Only duplicate variation names within the same song can be merged here." {})))
    (when-not (= (get params "version") (fingerprint data))
      (throw (ex-info "This song, its variations, or their references changed. Reload and compare the current records before merging." {})))
    (when (or (str/blank? (variation/name-key name)) (> (count name) 2000))
      (throw (ex-info "Enter the final variation name (up to 2000 characters)." {})))
    (variation/parse-guitar guitar)
    (when (and (seq link) (not (re-matches #"https?://[^\s]+" link)))
      (throw (ex-info "The example link must be an HTTP or HTTPS URL, or blank." {})))
    (when (some #(and (not ((set ids) (:id %))) (= (variation/name-key name) (variation/name-key (:variation_name %)))) (:variations data))
      (throw (ex-info "Another variation of this song would still have that name. Include all duplicate-name records or choose a different final name." {})))
    {:song (:song data) :variations selected :ids ids :target target
     :final {:musical_key (musical-key/parse-key (get params "musical_key")) :song_id song-id :variation_name name :example_link (not-empty link) :prominent_electric_guitar (variation/parse-guitar guitar)}
     :performances (filterv #((set ids) (:song_variation_id %)) (:performances data))}))
(defn redirect! [tx old-id new-id {:keys [variation-event song-event]}]
  (sql/update! tx :variation_merge_alias {:canonical_variation_id new-id} {:canonical_variation_id old-id})
  (sql/insert! tx :variation_merge_alias {:old_variation_id old-id :canonical_variation_id new-id
                                        :variation_merge_event_id variation-event :song_merge_event_id song-event}))
(defn merge! [params]
  (when-not (= "yes" (get params "confirm"))
    (throw (ex-info "Confirm that these variations should be combined before merging." {})))
  (jdbc/with-transaction [tx (db/get-datasource)]
    (variation/lock! tx)
    (let [{:keys [song variations ids target final performances]} (plan tx params true)
          event (:id (sql/insert! tx :variation_merge_event
                       {:song_id (:id song) :retained_variation_id target
                        :before_data (pr-str {:song song :variations variations :performance-references performances})
                        :after_data (pr-str (assoc final :id target)) :performance_count (count performances)} opts))]
      (doseq [old-id (remove #{target} ids)]
        (sql/update! tx :song_performance {:song_variation_id target} {:song_variation_id old-id})
        (redirect! tx old-id target {:variation-event event})
        (sql/delete! tx :song_variation {:id old-id}))
      (variation/validate! tx target final)
      (sql/update! tx :song_variation final {:id target})
      (let [remaining (:n (first (sql/query tx ["select count(*) as n from song_performance where song_variation_id=?" target] opts)))]
        (when-not (= (count performances) remaining)
          (throw (ex-info "Performance references changed during merging. Nothing was saved; reload and try again." {}))))
      target)))
