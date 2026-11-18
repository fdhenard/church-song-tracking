(ns church-song-tracking.library-cleanup
  (:require [clojure.edn :as edn] [clojure.string :as str] [next.jdbc :as jdbc] [next.jdbc.sql :as sql]
            [church-song-tracking.song-tags :as song-tags]
            [church-song-tracking.db :as db] [church-song-tracking.vimeo-import :as vimeo]))
(defn key-for [s] (-> (java.text.Normalizer/normalize (or s "") java.text.Normalizer$Form/NFKC) str/lower-case str/trim (str/replace #"\s+" " ")))
(defn context [ds]
  {:songs (mapv #(assoc % :tags (song-tags/for-song ds (:id %))) (sql/query ds ["select * from song order by id"] vimeo/opts))
   :variations (sql/query ds ["select * from song_variation order by id"] vimeo/opts)
   :performances (sql/query ds ["select id, song_variation_id from song_performance order by id"] vimeo/opts)
   :song-links (sql/query ds ["select * from song_merge_alias order by old_song_id"] vimeo/opts)
   :song-names (sql/query ds ["select * from song_identity_alias order by id"] vimeo/opts)
   :variation-links (sql/query ds ["select * from variation_merge_alias order by old_variation_id"] vimeo/opts)
   :drafts (sql/query ds ["select id, revision, draft_data from vimeo_import_selection where imported_service_id is null and merged_into_selection_id is null and draft_data is not null order by id"] vimeo/opts)})
(defn draft-song-ids [data]
  (reduce
   (fn [ids row]
     (let [draft (try (edn/read-string (:draft_data row))
                      (catch Exception _ (throw (ex-info "An unapproved review draft could not be read. Resolve that draft before cleaning the library." {}))))]
       (reduce (fn [ids song]
                 (let [choice (or (:song-choice song) "auto")]
                   (cond
                     (= choice "new") ids
                     (= choice "auto") (into ids (map :id (filter #(and (= (key-for (:name %)) (key-for (:title song)))
                                                               (or (str/blank? (:author song)) (= (key-for (:artist %)) (key-for (:author song))))) (:songs data))))
                     :else (if-let [id (parse-long (str choice))] (conj ids id) ids)))) ids (:songs draft)))) #{} (:drafts data)))
(defn candidates [data kind]
  (when-not (#{"songs" "variations"} kind) (throw (ex-info "Choose songs or variations for cleanup." {})))
  (let [used (set (map :song_variation_id (:performances data)))
        variation-links (set (map :canonical_variation_id (:variation-links data)))
        song-links (set (concat (map :canonical_song_id (:song-links data)) (map :canonical_song_id (:song-names data))))
        drafts (draft-song-ids data)
        songs (into {} (map (juxt :id identity) (:songs data)))
        variations (group-by :song_id (:variations data))
        result (if (= kind "songs")
                 (for [song (:songs data) :let [children (get variations (:id song) [])]
                       :when (not-any? #(used (:id %)) children)]
                   (assoc song :variation-count (count children)
                          :reason (cond (drafts (:id song)) "Used by an unapproved review draft"
                                        (song-links (:id song)) "Retained by song merge links / names"
                                        (some #(variation-links (:id %)) children) "A variation is retained by merge links"
                                        :else nil)))
                 (for [variation (:variations data) :when (not (used (:id variation)))
                       :let [song (songs (:song_id variation))]]
                   (assoc variation :name (:name song) :artist (:artist song)
                          :reason (cond (drafts (:song_id variation)) "Song is used by an unapproved review draft"
                                        (variation-links (:id variation)) "Retained by variation merge links"
                                        :else nil))))]
    (vec (sort-by (juxt (comp key-for :name) (comp key-for :artist) (comp key-for :variation_name) :id) result))))
(defn fingerprint [data]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest (.getBytes (pr-str data) "UTF-8"))))))
(defn plan [ds kind params]
  (let [data (context ds) available (candidates data kind)
        raw (get params "selected") values (cond (nil? raw) [] (sequential? raw) raw :else [raw])
        ids (mapv #(try (Long/parseLong (str %)) (catch Exception _ nil)) values)
        selected (filterv #(contains? (set ids) (:id %)) available)]
    (when-not (and (seq ids) (<= (count ids) 5000) (= (count ids) (count (distinct ids))) (= (count ids) (count selected)) (not-any? :reason selected))
      (throw (ex-info "Choose unused rows without protected references. A row may have changed; reload and review again." {})))
    {:kind kind :selected selected :state data :snapshot (fingerprint data)
     :variations (if (= kind "songs") (filterv #(contains? (set ids) (:song_id %)) (:variations data)) selected)}))
(defn delete-unused! [kind params]
  (when-not (= "yes" (get params "confirm")) (throw (ex-info "Review the deletion preview and confirm before deleting." {})))
  (jdbc/with-transaction [tx (db/get-datasource)]
    (sql/query tx ["select id from import_approval_lock where id=1 for update"])
    ;; Prevent a saved draft from acquiring a selected identity during deletion.
    (sql/query tx ["select id from vimeo_import_selection where imported_service_id is null for update"] vimeo/opts)
    (let [{:keys [selected variations snapshot] :as planned} (plan tx kind params)
          table (if (= kind "songs") :song :song_variation)]
      (when-not (= snapshot (get params "snapshot")) (throw (ex-info "The library or review drafts changed. Preview the deletion again." {})))
      (doseq [row selected] (sql/query tx [(str "select id from " (name table) " where id=? for update") (:id row)] vimeo/opts))
      (doseq [row variations] (sql/query tx ["select id from song_variation where id=? for update" (:id row)] vimeo/opts))
      (when-not (= snapshot (:snapshot (plan tx kind params))) (throw (ex-info "The library or references changed. Preview the deletion again." {})))
      (sql/insert! tx :library_cleanup_event {:kind kind :before_data (pr-str (select-keys planned [:selected :variations])) :row_count (count selected)} vimeo/opts)
      (doseq [row variations] (sql/delete! tx :song_variation {:id (:id row)}))
      (when (= kind "songs") (doseq [row selected] (sql/delete! tx :song {:id (:id row)})))
      (count selected))))
