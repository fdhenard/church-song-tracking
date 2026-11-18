(ns church-song-tracking.record-merge
  (:require [clojure.string :as str] [next.jdbc :as jdbc] [next.jdbc.sql :as sql]
            [church-song-tracking.db :as db] [church-song-tracking.vimeo-import :as vimeo]))
(def specs {"people" {:table :person :columns [:preacher_id :song_leader_id] :label "preachers / people"}
            "series" {:table :sermon_series :columns [:sermon_series_id] :label "sermon series"}})
(defn name-key [s] (-> (java.text.Normalizer/normalize (or s "") java.text.Normalizer$Form/NFKC) str/trim str/lower-case (str/replace #"\s+" " ")))
(defn rows [ds kind]
  (when-not (specs kind) (throw (ex-info "Unsupported merge type." {})))
  (sql/query ds [(str "select * from " (name (:table (specs kind))) " order by lower(name), id")] vimeo/opts))
(defn references [ds kind]
  (sql/query ds [(str "select id, date, title, sermon_title, " (str/join ", " (map name (:columns (specs kind)))) " from church_service order by date desc, id")] vimeo/opts))
(defn canonical-id [ds kind id]
  (:canonical_id (first (sql/query ds ["select canonical_id from record_merge_alias where kind=? and old_id=?" kind id] vimeo/opts))))
(defn find-name-id [ds kind value]
  (:canonical_id (first (sql/query ds ["select canonical_id from record_merge_name_alias where kind=? and name_key=?" kind (name-key value)] vimeo/opts))))
(defn values [v] (cond (nil? v) [] (sequential? v) (vec v) :else [v]))
(defn snapshot [data]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest (.getBytes (pr-str data) "UTF-8"))))))
(defn plan [ds kind params]
  (let [all (rows ds kind) refs (references ds kind)
        ids (mapv #(try (Long/parseLong (str %)) (catch Exception _ nil)) (values (get params "source")))
        selected (filterv #(contains? (set ids) (:id %)) all)
        kept (try (Long/parseLong (str (get params "keep"))) (catch Exception _ nil))
        retained (first (filter #(= kept (:id %)) selected))
        _ (when-not (and (<= 2 (count ids) 100) (= (count ids) (count (distinct ids))) (= (count ids) (count selected)) retained)
            (throw (ex-info "Select at least two records and choose one of those records to keep." {})))
        final-name (str/trim (or (not-empty (get params "name")) (:name retained)))
        _ (when (or (str/blank? final-name) (> (count final-name) 500)) (throw (ex-info "Enter a name of 1 to 500 characters." {})))
        _ (when (some #(and (not ((set ids) (:id %))) (= (name-key final-name) (name-key (:name %)))) all)
            (throw (ex-info "That name belongs to another record. Include it in this merge or choose a different name." {})))
        affected (filterv (fn [row] (some #((set ids) (get row %)) (:columns (specs kind)))) refs)
        state {:records all :services (mapv #(update % :date str) refs)
               :aliases (sql/query ds ["select * from record_merge_alias where kind=? order by old_id" kind] vimeo/opts)
               :names (sql/query ds ["select * from record_merge_name_alias where kind=? order by name_key" kind] vimeo/opts)}]
    {:kind kind :selected selected :kept kept :name final-name :affected affected :state state :snapshot (snapshot state)}))
(defn merge! [kind params]
  (when-not (= "yes" (get params "confirm")) (throw (ex-info "Review the preview and confirm this merge." {})))
  (jdbc/with-transaction [tx (db/get-datasource)]
    (sql/query tx ["select id from import_approval_lock where id=1 for update"])
    (let [{:keys [selected kept name state snapshot] :as planned} (plan tx kind params)
          {:keys [table columns]} (specs kind)]
      (when-not (= snapshot (get params "snapshot")) (throw (ex-info "These records or services changed. Preview the merge again." {})))
      (doseq [row selected] (sql/query tx [(str "select id from " (clojure.core/name table) " where id=? for update") (:id row)] vimeo/opts))
      (doseq [row (:affected planned)] (sql/query tx ["select id from church_service where id=? for update" (:id row)] vimeo/opts))
      (when-not (= snapshot (:snapshot (plan tx kind params)))
        (throw (ex-info "These records or services changed. Preview the merge again." {})))
      (let [event (:id (sql/insert! tx :record_merge_event
                       {:kind kind :canonical_id kept :before_data (pr-str state)
                        :after_data (pr-str {:kept kept :name name :sources (mapv :id selected)})} vimeo/opts))]
        (doseq [row selected]
          (let [old (:id row)]
            (doseq [column columns] (sql/update! tx :church_service {column kept} {column old}))
            (sql/update! tx :record_merge_alias {:canonical_id kept} {:kind kind :canonical_id old})
            (sql/update! tx :record_merge_name_alias {:canonical_id kept} {:kind kind :canonical_id old})
            (when (not= kept old)
              (sql/insert! tx :record_merge_alias {:kind kind :old_id old :canonical_id kept :merge_event_id event} vimeo/opts))))
        (doseq [alias-name (conj (mapv :name selected) name)]
          (let [key (name-key alias-name) existing (find-name-id tx kind alias-name)]
            (when (and existing (not= kept existing))
              (throw (ex-info "A previous merge associates that name with a different record. Resolve that record first." {})))
            (when-not existing (sql/insert! tx :record_merge_name_alias {:kind kind :name_key key :canonical_id kept} vimeo/opts))))
        (sql/update! tx table {:name name} {:id kept})
        (doseq [row selected :when (not= kept (:id row))] (sql/delete! tx table {:id (:id row)}))
        kept))))
