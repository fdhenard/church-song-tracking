(ns church-song-tracking.song-variation
  "Friendly variation-name validation backed by H2 normalized-name uniqueness."
  (:require [clojure.string :as str]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]))
(def opts {:builder-fn rs/as-unqualified-lower-maps})
(defn guitar-label [value] (case value true "Yes" false "No" "Review"))
(defn guitar-value [value] (case value true "true" false "false" "review"))
(defn parse-guitar [value]
  (case value
    (nil "" "review") nil
    (true "true" "yes") true
    (false "false" "no") false
    (throw (ex-info "Choose Yes, No, or Review for prominent electric guitar." {}))))
(defn guitar-options [value]
  (let [selected (guitar-value (parse-guitar value))]
    (for [[v label] [["review" "Review"] ["true" "Yes"] ["false" "No"]]]
      [:option {:value v :selected (= selected v)} label])))
(defn name-key [value]
  (-> (java.text.Normalizer/normalize (or value "") java.text.Normalizer$Form/NFKC)
      str/trim (.toLowerCase java.util.Locale/ROOT) (str/replace #"\s+" " ")))
(defn lock! [tx]
  ;; All app paths that can add, rename, or move variations share this transaction lock.
  (sql/query tx ["select id from import_approval_lock where id = 1 for update"]))
(defn validate! [tx id data]
  (lock! tx)
  (let [old (when id (sql/get-by-id tx :song_variation id opts))
        key (name-key (:variation_name data))]
    (when (str/blank? key) (throw (ex-info "Variation name is required." {})))
    (when-not (sql/get-by-id tx :song (:song_id data) opts)
      (throw (ex-info "Choose an existing song for this variation." {})))
    ;; An unchanged identity can be edited; H2 also enforces uniqueness on all writes.
    (when-not (and old (= (:song_id old) (:song_id data)) (= key (name-key (:variation_name old))))
      (when (some #(and (not= id (:id %)) (= key (name-key (:variation_name %))))
                  (sql/find-by-keys tx :song_variation {:song_id (:song_id data)} opts))
        (throw (ex-info "That variation name already exists for this song. Choose a different name. Names ignore letter case and extra spaces." {}))))
    data))
(defn ensure! [tx data reuse-name?]
  (lock! tx)
  (let [matches (filterv #(= (name-key (:variation_name data)) (name-key (:variation_name %)))
                        (sort-by :id (sql/find-by-keys tx :song_variation {:song_id (:song_id data)} opts)))
        exact (first (filter #(= (mapv data [:example_link :prominent_electric_guitar :musical_key])
                                (mapv % [:example_link :prominent_electric_guitar :musical_key])) matches))]
    (cond
      exact (:id exact)
      (and reuse-name? (= 1 (count matches))) (:id (first matches))
      (seq matches) (throw (ex-info "A variation with this name already exists for the song, but its settings differ or older duplicates are ambiguous. Review its variations and use a unique name before importing." {}))
      :else (do (validate! tx nil data) (:id (sql/insert! tx :song_variation data opts))))))
(defn validate-merge! [variations kept-ids retained-id]
  (doseq [[_ group] (group-by #(name-key (:variation_name %)) (filter #((set kept-ids) (:id %)) variations))]
    (when (and (> (count group) 1) (some #(not= retained-id (:song_id %)) group))
      (throw (ex-info (str "These songs have different variations named “" (:variation_name (first group))
                          "”. Rename one variation before merging songs so each name is unique within the merged song. Existing records were left unchanged.") {})))))
