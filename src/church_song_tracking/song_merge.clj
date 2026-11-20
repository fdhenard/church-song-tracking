(ns church-song-tracking.song-merge
  "Explicit, transactional reconciliation of duplicate song records and their referrers."
  (:require [church-song-tracking.db :as db]
            [church-song-tracking.song-tags :as song-tags]
            [church-song-tracking.song-variation :as variation]
            [church-song-tracking.variation-merge :as variation-merge]
            [church-song-tracking.vimeo-import :as vimeo]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:import [java.security MessageDigest]))

(defn title-key [value]
  (-> (java.text.Normalizer/normalize (or value "") java.text.Normalizer$Form/NFKC)
      str/trim str/lower-case (str/replace #"\s+" " ")))

(defn- merge-title-key [value]
  (let [title (title-key value)
        key (-> title
                (str/replace #"(?<=\p{L})['’](?=\p{L})" "")
                (str/replace #"[\p{P}\s]+" " ")
                str/trim)]
    (if (str/blank? key) title key)))

(defn find-song-id [ds name artist]
  (or (:id (first (sql/find-by-keys ds :song {:name name :artist artist} vimeo/opts)))
      (let [identity [(title-key name) (title-key artist)]]
        (:canonical_song_id
          (first (filter #(= identity [(title-key (:original_name %)) (title-key (:original_artist %))])
                         (sql/query ds ["select * from song_identity_alias order by id desc"] vimeo/opts)))))))
(defn all-songs [ds]
  (sql/query ds ["select s.*, (select count(*) from song_variation v where v.song_id=s.id) as variation_count, (select count(*) from song_performance p join song_variation v on p.song_variation_id=v.id where v.song_id=s.id) as performance_count from song s order by s.name, s.id"] vimeo/opts))
(defn groups [ds]
  (->> (all-songs ds) (group-by #(merge-title-key (:name %))) vals (filter #(> (count %) 1))
       (sort-by #(merge-title-key (:name (first %))))))
(defn candidates [ds id]
  (when-let [song (sql/get-by-id ds :song id vimeo/opts)]
    (let [key (merge-title-key (:name song))]
      (filterv #(= key (merge-title-key (:name %))) (all-songs ds)))))
(defn canonical-id [ds old-id]
  (:canonical_song_id (sql/get-by-id ds :song_merge_alias old-id :old_song_id vimeo/opts)))
(defn values [value] (cond (nil? value) [] (string? value) [value] :else value))
(defn ids! [value]
  (let [ids (mapv #(or (some-> % str parse-long) (throw (ex-info "Invalid song selection." {}))) (values value))]
    (when-not (and (<= 2 (count ids) 50) (every? pos? ids) (= (count ids) (count (distinct ids))))
      (throw (ex-info "Select two to fifty different song records." {})))
    ids))
(defn placeholders [ids] (str/join "," (repeat (count ids) "?")))
(defn snapshot [ds ids lock?]
  (let [ids (sort ids) suffix (if lock? " for update" "")
        songs (sql/query ds (into [(str "select * from song where id in (" (placeholders ids) ") order by id" suffix)] ids) vimeo/opts)
        variations (sql/query ds (into [(str "select * from song_variation where song_id in (" (placeholders ids) ") order by id" suffix)] ids) vimeo/opts)
        performances (sql/query ds (into [(str "select id, song_variation_id, church_service_id from song_performance where song_variation_id in (select id from song_variation where song_id in (" (placeholders ids) ")) order by id" suffix)] ids) vimeo/opts)]
    {:songs songs :variations variations :performances performances
     :tags (into {} (for [song songs] [(:id song) (song-tags/for-song ds (:id song))]))}))
(defn fingerprint [data]
  (let [stable [(mapv #(mapv % [:id :name :artist :time_added]) (:songs data))
                (mapv #(mapv % [:id :song_id :variation_name :example_link :prominent_electric_guitar :musical_key :time_added]) (:variations data))
                (mapv #(mapv % [:id :song_variation_id :church_service_id]) (:performances data)) (:tags data)]]
    (apply str (map #(format "%02x" (bit-and 255 %))
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (pr-str stable) "UTF-8"))))))
(defn version [ds songs] (fingerprint (snapshot ds (map :id songs) false)))
(def variation-fields [:variation_name :example_link :prominent_electric_guitar :musical_key])
(defn variation-plan [variations retained-id]
  (reduce (fn [{:keys [known] :as result} variation]
            (let [key [(variation/name-key (:variation_name variation)) (:example_link variation) (:prominent_electric_guitar variation) (:musical_key variation)]]
              (if-let [existing (get known key)]
                (update result :redirects assoc (:id variation) existing)
                (-> result (assoc-in [:known key] (:id variation)) (update :kept conj (:id variation))))))
          {:known {} :kept [] :redirects {}}
          (sort-by #(vector (if (= retained-id (:song_id %)) 0 1) (:id %)) variations)))
(defn chosen-variations [variations retained-id params]
  (if-not (some #(str/starts-with? % "variation-choice-") (keys params))
    (variation-plan variations retained-id)
    (let [ids (set (map :id variations))
          redirects (into {} (for [v variations
                                  :let [choice (get params (str "variation-choice-" (:id v)) "keep")]
                                  :when (not= choice "keep")]
                              [(:id v) (some-> choice parse-long)]))
          kept (filterv #(not (contains? redirects %)) (mapv :id variations))]
      (when-not (every? (fn [[old target]] (and (ids target) (not= old target) (not (contains? redirects target)))) redirects)
        (throw (ex-info "Each deleted variation must point to a kept variation from the songs included in this merge." {})))
      {:kept kept :redirects redirects})))

(defn- chosen-field [params selected field]
  (let [source (get params (str field "-source") "custom")]
    (if (= source "custom")
      (str/trim (get params field ""))
      (let [id (some-> source parse-long)
            song (first (filter #(= id (:id %)) selected))]
        (when-not song
          (throw (ex-info "Choose field values from songs included in this merge, or enter a custom value." {})))
        (get song (keyword field))))))

(defn plan [ds params lock?]
  (let [candidate-ids (ids! (get params "candidate"))
        ids (ids! (get params "source"))
        target (some-> (get params "retained") parse-long)
        data (snapshot ds candidate-ids lock?)
        selected (filterv #(contains? (set ids) (:id %)) (:songs data))
        title (chosen-field params selected "name") artist (chosen-field params selected "artist")]
    (when-not (and (= (count candidate-ids) (count (:songs data)))
                   (every? (set candidate-ids) ids) (= (count ids) (count selected)) (contains? (set ids) target))
      (throw (ex-info "Choose a retained song from the selected records. A song may have changed or already been merged; reload the comparison." {})))
    (when-not (= 1 (count (set (map #(merge-title-key (:name %)) (:songs data)))))
      (throw (ex-info "Start from songs with matching titles. Reload the duplicate-song comparison." {})))
    (when-not (= (get params "version") (fingerprint data))
      (throw (ex-info "Song details or references changed since this screen was opened. Reload and compare the latest records before merging." {})))
    (when (or (str/blank? title) (str/blank? artist) (> (count title) 2000) (> (count artist) 2000))
      (throw (ex-info "Enter the final song title and artist (up to 2000 characters each)." {})))
    (let [variations (filterv #(contains? (set ids) (:song_id %)) (:variations data))
          variation-ids (set (map :id variations))
          performances (filterv #(variation-ids (:song_variation_id %)) (:performances data))
          choices (chosen-variations variations target params)]
      (variation/validate-merge! variations (:kept choices) target)
      {:ids ids :target target :name title :artist artist :songs selected :variations variations
       :performances performances :tags (:tags data) :variation-plan choices :candidate-ids candidate-ids})))

(defn merge! [params]
  (when-not (= "yes" (get params "confirm"))
    (throw (ex-info "Confirm that the selected records represent the same song before merging." {})))
  (jdbc/with-transaction [tx (db/get-datasource)]
    ;; Also serialize with service approvals, which may create songs and variations.
    (sql/query tx ["select id from import_approval_lock where id = 1 for update"])
    (let [{:keys [ids target name artist songs variations performances variation-plan] :as planned} (plan tx params true)
          removed (remove #{target} ids)
          event (:id (sql/insert! tx :song_merge_event
                       {:retained_song_id target :before_data (pr-str (select-keys planned [:songs :variations :performances :tags]))
                        :after_data (pr-str {:song-id target :name name :artist artist :variation-redirects (:redirects variation-plan)})
                        :performance_count (count performances)} vimeo/opts))]
      (doseq [[old-id new-id] (:redirects variation-plan)]
        (sql/update! tx :song_performance {:song_variation_id new-id} {:song_variation_id old-id})
        (variation-merge/redirect! tx old-id new-id {:song-event event})
        (sql/delete! tx :song_variation {:id old-id}))
      (doseq [old-song removed]
        (sql/update! tx :song_identity_alias {:canonical_song_id target} {:canonical_song_id old-song}))
      (doseq [song songs]
        (sql/insert! tx :song_identity_alias {:canonical_song_id target :merge_event_id event
                                            :original_name (:name song) :original_artist (:artist song)}))
      (song-tags/merge-tags! tx ids target)
      (doseq [old-song removed]
        (sql/update! tx :song_variation {:song_id target} {:song_id old-song})
        ;; Keep older links working even when a prior merge's retained song is merged again.
        (sql/update! tx :song_merge_alias {:canonical_song_id target} {:canonical_song_id old-song})
        (let [old (first (filter #(= old-song (:id %)) songs))]
          (sql/insert! tx :song_merge_alias {:old_song_id old-song :canonical_song_id target :merge_event_id event
                                           :original_name (:name old) :original_artist (:artist old)}))
        (sql/delete! tx :song {:id old-song}))
      (sql/update! tx :song {:name name :artist artist} {:id target})
      (let [remaining (:n (first (sql/query tx ["select count(*) as n from song_performance p join song_variation v on v.id=p.song_variation_id where v.song_id=?" target] vimeo/opts)))]
        (when-not (= (count performances) remaining)
          (throw (ex-info "The performance references changed during the merge. Nothing was saved; reload and try again." {}))))
      target)))
