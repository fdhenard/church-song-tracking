(ns church-song-tracking.service-merge
  "Explicit reconciliation of library services and a saved import draft."
  (:require [church-song-tracking.musical-key :as musical-key]
            [church-song-tracking.db :as db]
            [church-song-tracking.import-review :as review]
            [church-song-tracking.song-merge :as song]
            [church-song-tracking.song-variation :as variation]
            [church-song-tracking.variation-merge :as variation-merge]
            [church-song-tracking.video-metadata :as metadata]
            [church-song-tracking.vimeo-import :as vimeo]
            [church-song-tracking.cache-cleanup :as cleanup]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def fields
  [[:date "Service date"] [:title "Service title"] [:preacher_id "Preacher"]
   [:song_leader_id "Song leader"] [:sermon_title "Sermon title"] [:sermon_scripture "Sermon scripture"] [:sermon_series_id "Sermon series"]
   [:sermon_topic "Sermon topic"] [:holiday_id "Holiday"] [:interest_for_next_mens_retreat "Consider for men's retreat"]
   [:link_vimeo "Primary Vimeo link"] [:vimeo_video_title "Primary Vimeo video title"] [:link_youtube "YouTube link"]
   [:sermon_title_slide_time_vimeo "Sermon slide time · Vimeo"]
   [:sermon_title_slide_time_youtube "Sermon slide time · YouTube"]
   [:sermon_title_image_path "Sermon title image"]])
(defn canonical-id [ds id]
  (:canonical_service_id (sql/get-by-id ds :service_merge_alias id :old_service_id vimeo/opts)))
(defn groups [ds]
  (->> (sql/query ds ["select * from church_service order by date desc, id"] vimeo/opts)
       (group-by #(str (:date %))) vals (filter #(> (count %) 1))
       (sort-by #(str (:date (first %)))) reverse))
(defn positive-id [value]
  (let [id (some-> value str parse-long)]
    (when-not (and id (pos? id)) (throw (ex-info "Invalid service selection." {}))) id))
(defn context [ds params]
  (let [selection-id (when (seq (get params "selection")) (positive-id (get params "selection")))
        selection (when selection-id (sql/get-by-id ds :vimeo_import_selection selection-id vimeo/opts))
        _ (when (and selection-id (nil? selection)) (throw (ex-info "The import no longer exists." {})))
        draft (when selection (review/draft selection))
        group-id (when (seq (get params "group")) (positive-id (get params "group")))
        group (when group-id (sql/get-by-id ds :church_service group-id vimeo/opts))
        date (or (when-not (str/blank? (:date draft)) (:date draft)) (some-> group :date str) (get params "date"))
        date (try (java.time.LocalDate/parse (or date ""))
                  (catch Exception _ (throw (ex-info "Save a valid service date in the review draft before comparing services." {}))))
        services (sql/query ds ["select * from church_service where date=? order by id" date] vimeo/opts)
        ids (mapv :id services)
        placeholders (str/join "," (repeat (count ids) "?"))
        query (fn [table column]
                (if (seq ids) (sql/query ds (into [(str "select * from " table " where " column " in (" placeholders ") order by id")] ids) vimeo/opts) []))
        performances (query "song_performance" "church_service_id")
        imports (query "vimeo_import_selection" "imported_service_id")
        recordings (query "service_video_source" "church_service_id")
        sources (query "service_merge_source" "church_service_id")
        aliases (if (seq ids) (sql/query ds (into [(str "select * from service_merge_alias where canonical_service_id in (" placeholders ") order by old_service_id")] ids) vimeo/opts) [])]
    {:date date :services services :selection selection :draft draft :performances performances
     :imports imports :recordings recordings :sources sources :aliases aliases
     :songs (sql/query ds ["select * from song order by id"] vimeo/opts)
     :song-variations (sql/query ds ["select * from song_variation order by id"] vimeo/opts)
     :song-aliases (sql/query ds ["select * from song_identity_alias order by id"] vimeo/opts)
     :variation-aliases (sql/query ds ["select * from variation_merge_alias order by old_variation_id"] vimeo/opts)
     :variation-references (sql/query ds ["select p.*, s.date as service_date, s.sermon_title as service_sermon from song_performance p join church_service s on s.id=p.church_service_id order by p.id"] vimeo/opts)}))
(defn serialize [data]
  (pr-str (walk/postwalk #(if (or (instance? java.time.temporal.TemporalAccessor %) (instance? java.util.Date %)) (str %) %) data)))
(defn fingerprint [data]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest (.getBytes (serialize data) "UTF-8"))))))
(defn incoming [data]
  (when-let [selection (:selection data)]
    (let [d (:draft data)]
      {:key "incoming" :id "incoming" :date (:date d) :title (:title d) :preacher_id (:preacher d)
       :song_leader_id (:leader d) :sermon_title (:sermon-title d) :sermon_scripture (:sermon-scripture d) :sermon_series_id (:sermon-series d)
       :sermon_topic (:sermon-topic d) :link_vimeo (:vimeo_url selection)
       :vimeo_video_title (metadata/original-title selection)
       :sermon_title_slide_time_vimeo (:sermon-time d)
       :sermon_title_image_path (when (:sermon-evidence-index d) (str "/imports/" (:id selection) "/frame/" (:sermon-evidence-index d)))
       :interest_for_next_mens_retreat false})))
(defn candidates [data]
  (cond-> (mapv #(assoc % :key (str (:id %))) (:services data)) (:selection data) (conj (incoming data))))
(defn performances [ds data]
  (let [existing (for [p (:performances data)
                       :let [v (sql/get-by-id ds :song_variation (:song_variation_id p) vimeo/opts)
                             s (when v (sql/get-by-id ds :song (:song_id v) vimeo/opts))]]
                   (assoc p :key (str "performance-" (:id p)) :service-key (str (:church_service_id p))
                            :variation-id (:id v) :song-id (:song_id v) :song-title (:name s) :song-author (:artist s) :variation (:variation_name v)
                            :variation-key (:musical_key v) :variation-example-link (:example_link v) :variation-guitar (:prominent_electric_guitar v)
                            :image-url (when (:title_image_path p) (str "/performances/" (:id p) "/title-image"))))
        pending (when (:selection data)
                  (for [[i s] (map-indexed vector (review/order-songs (remove review/blank-song? (get-in data [:draft :songs]))))
                        :let [choice (or (:song-choice s) "auto")
                              song-id (cond (= choice "auto") (song/find-song-id ds (:title s) (:author s))
                                            (= choice "new") nil :else (parse-long choice))
                              existing (when song-id (sql/get-by-id ds :song song-id vimeo/opts))
                              default (or (review/review-variation ds song-id s)
                                          (first (filter #(and (= song-id (:song_id %)) (= "default" (variation/name-key (:variation_name %)))) (:song-variations data))))]]
                    {:key (str "incoming-" i) :service-key "incoming" :order_in_service (or (:order s) (inc i))
                     :variation-id (:id default) :song-id song-id :song-title (or (:name existing) (:title s)) :song-author (or (:artist existing) (:author s)) :variation (or (:variation_name default) "Default")
                     :variation-key (:musical_key default) :variation-example-link (:example_link default) :variation-guitar (:prominent_electric_guitar default)
                     :time_in_service_vimeo (if (seq (:slide s)) (:slide s) (:start s))
                     :song_start_time_vimeo (:start s)
                     :image-url (when (:evidence-index s) (str "/imports/" (get-in data [:selection :id]) "/frame/" (:evidence-index s)))}))]
    (vec (concat existing pending))))
(defn service-overrides [ds candidates field-sources]
  (let [rows (into {} (map (juxt :key identity) candidates))
        value (fn [field] (let [source (field-sources field) v (get-in rows [source field])]
                           (if (and (#{:preacher_id :song_leader_id :sermon_series_id} field) (not= source "incoming"))
                             (when v (:name (sql/get-by-id ds (if (= field :sermon_series_id) :sermon_series :person) v vimeo/opts)))
                             v)))]
    (into {} (for [[review-field service-field] [[:date :date] [:title :title] [:preacher :preacher_id]
                                               [:leader :song_leader_id] [:sermon-title :sermon_title] [:sermon-scripture :sermon_scripture]
                                               [:sermon-series :sermon_series_id] [:sermon-topic :sermon_topic]
                                               [:sermon-time :sermon_title_slide_time_vimeo]]]
               [review-field (str (or (value service-field) ""))]))))

(def performance-fields
  [[:song "Song / author"] [:variation "Variation"] [:musical_key "Key"] [:time_in_service_vimeo "Title slide / fallback time · Vimeo"]
   [:time_in_service_youtube "Time · YouTube"] [:song_start_time_vimeo "Song start · Vimeo"] [:title_image_path "Title slide image"]])
(defn performance-label [row]
  (str (if (= "incoming" (:service-key row)) "Import" (str "Service #" (:service-key row)))
       " · song " (:order_in_service row) " · " (:song-title row)))
(defn performance-field-label [row field]
  (case field
    :song (str (:song-title row) " · " (:song-author row))
    :variation (str (:variation row) " · key: " (musical-key/label (:variation-key row)) " · example: " (or (:variation-example-link row) "none")
                    " · electric guitar: " (variation/guitar-label (:variation-guitar row)))
    :title_image_path (if (:image-url row) "Slide available" "No slide")
    (or (get row field) "—")))
(defn performance-canonical-id [ds id]
  (:canonical_performance_id (sql/get-by-id ds :performance_merge_alias id :old_performance_id vimeo/opts)))
(defn performance-choices [performances keep-keys params]
  (let [by-key (into {} (map (juxt :key identity) performances))
        redirects (into {} (for [p performances :let [destination (get params (str "merge-" (:key p)) "")]
                                :when (seq destination)] [(:key p) destination]))
        redirects (into {} (for [[source destination] redirects]
                             [source (loop [target destination seen #{source}]
                                       (when (contains? seen target)
                                         (throw (ex-info "Performance merge destinations form a loop. Choose one final performance to keep for this group." {})))
                                       (if-let [next-target (redirects target)]
                                         (recur next-target (conj seen target))
                                         target))]))
        kept (filterv #(and (contains? (set keep-keys) (:key %)) (not (contains? redirects (:key %)))) performances)]
    (doseq [[source target] redirects]
      (when-not (and (by-key target) (not= source target) (not (contains? redirects target))
                     (some #(= target (:key %)) kept))
        (throw (ex-info "Merge into a kept performance from the included services. A destination cannot itself be merged or excluded." {}))))
    {:kept kept :redirects redirects
     :fields (into {} (for [p kept :let [target (:key p)
                                       members (conj (set (for [[source destination] redirects :when (= destination target)] source)) target)]]
                       [target (into {} (for [[field] performance-fields
                                            :let [source (get params (str "pick-" target "-" (name field)) target)]]
                                        (do (when-not (members source)
                                              (throw (ex-info "Choose fields only from this performance and the performances selected to merge into it." {})))
                                            [field source])))]))}))

(defn variation-settings [row] (select-keys row [:example_link :prominent_electric_guitar :musical_key]))
(defn selected-variation-plan [data performances performance-plan]
  (let [rows (into {} (map (juxt :key identity) performances))
        affected (set (keep :id performances))
        decisions (mapv (fn [p]
                         (let [key (:key p) picks (get-in performance-plan [:fields key])
                               song-row (rows (picks :song)) source (rows (picks :variation))
                               desired {:musical_key (:variation-key source) :variation_name (:variation source) :example_link (:variation-example-link source)
                                        :prominent_electric_guitar (:variation-guitar source)}
                               matching (when (:song-id song-row)
                                          (first (filter #(and (= (:song-id song-row) (:song_id %))
                                                               (= (variation/name-key (:variation source)) (variation/name-key (:variation_name %))))
                                                         (:song-variations data))))
                               outside (when (and matching (not= (variation-settings matching) (variation-settings desired)))
                                         (filterv #(and (= (:id matching) (:song_variation_id %)) (not (affected (:id %)))) (:variation-references data)))]
                           {:key key :song-key (or (:song-id song-row) [(:song-title song-row) (:song-author song-row)])
                            :song-title (:song-title song-row) :desired desired :matching matching :outside outside}))
                       (:kept performance-plan))]
    (doseq [[_ group] (group-by #(vector (:song-key %) (variation/name-key (get-in % [:desired :variation_name]))) decisions)]
      (when (> (count (set (map #(variation-settings (:desired %)) group))) 1)
        (throw (ex-info "Kept performances select different settings for the same song and variation name. Choose one set of variation settings before merging." {}))))
    {:variation-names (into {} (map #(vector (:key %) (get-in % [:desired :variation_name])) decisions))
     :variation-decisions decisions
     :unused-variations (let [used (set (keep #(get-in % [:matching :id]) decisions))
                              candidates (set (keep :variation-id performances))]
                          (filterv (fn [v] (and (candidates (:id v)) (not (used (:id v)))
                                               (not-any? #(and (= (:id v) (:song_variation_id %)) (not (affected (:id %)))) (:variation-references data))))
                                   (:song-variations data)))
     :variation-conflicts (->> decisions (filter #(seq (:outside %))) (group-by #(get-in % [:matching :id])) vals (mapv first))}))
(defn ensure-performance-variation! [tx desired affected-ids approved-shared]
  (let [matching (first (filter #(= (variation/name-key (:variation_name desired)) (variation/name-key (:variation_name %)))
                               (sql/find-by-keys tx :song_variation {:song_id (:song_id desired)} vimeo/opts)))]
    (if (and matching (not= (variation-settings matching) (variation-settings desired)))
      (let [_ (sql/query tx ["select id from song_variation where id=? for update" (:id matching)] vimeo/opts)
            outside (remove #(affected-ids (:id %)) (sql/find-by-keys tx :song_performance {:song_variation_id (:id matching)} vimeo/opts))]
        (when (and (seq outside) (not= (set (map :id outside)) (get approved-shared (:id matching))))
          (throw (ex-info "This variation is used by other performances. Preview their references and explicitly approve the shared variation decision before accepting." {})))
        (variation/validate! tx (:id matching) desired)
        (sql/update! tx :song_variation desired {:id (:id matching)})
        (:id matching))
      (variation/ensure! tx desired false))))

(defn plan [ds params]
  (let [data (context ds params)
        _ (when-not (= (get params "version") (fingerprint data))
            (throw (ex-info "Service details, draft, or references changed. Reload and compare the latest records." {})))
        choices (candidates data)
        included (song/values (get params "source"))
        selected (filterv #(contains? (set included) (:key %)) choices)
        target (get params "retained")
        _ (when-not (and (<= 2 (count included) 50) (= (count included) (count (set included)))
                         (= (count included) (count selected)) (contains? (set included) target))
            (throw (ex-info "Include at least two services and choose an included service row to keep." {})))
        pending? (contains? (set included) "incoming")
        _ (when pending?
            (let [row (:selection data)]
              (when (or (:imported_service_id row) (:range_needs_ocr row) (:merged_into_selection_id row)
                        (#{"queued" "downloading" "extracting" "recognizing" "merging"} (:job_status row)))
                (throw (ex-info "Finish processing and save the import review before merging. This import must be unapproved and ready for review." {})))
              nil))
        field-sources (into {} (for [[field] fields
                                    :let [source (get params (str "field-" (name field)) target)]]
                                (do (when-not (contains? (set included) source)
                                      (throw (ex-info "Choose service fields from included rows only." {})))
                                    [field source])))
        overrides (service-overrides ds choices field-sources)
        _ (when pending? (review/validated (merge (:draft data) overrides)))
        all-performances (filterv #(contains? (set included) (:service-key %)) (performances ds data))
        keep-keys (song/values (get params "performance"))
        performance-plan (performance-choices all-performances keep-keys params)
        performance-plan (merge performance-plan (selected-variation-plan data all-performances performance-plan))
        kept (:kept performance-plan)
        _ (when-not (and (seq kept) (= (count keep-keys) (count (set keep-keys))) (every? (set (map :key all-performances)) keep-keys))
            (throw (ex-info "Keep at least one performance, using only performances from included services." {})))
        orders (into {} (for [p kept :let [order (some-> (get params (str "order-" (:key p))) parse-long)]]
                         (do (when-not (and order (<= 1 order 1000)) (throw (ex-info "Give kept performances positive orders from 1 to 1000." {})))
                             [(:key p) order])))
        _ (when-not (= (count orders) (count (set (vals orders))))
            (throw (ex-info "Give each kept performance a unique order in the merged service." {})))
        by-key (into {} (map (juxt :key identity) choices))]
    {:context data :selected selected :target target :field-sources field-sources :pending? pending? :service-overrides overrides
     :final (into {} (for [[field source] field-sources] [field (get-in by-key [source field])]))
     :performance-plan performance-plan :kept kept :orders orders :removed (filterv #(not (contains? (set (map :key kept)) (:key %))) all-performances)}))
(defn merge! [params]
  (when-not (= "yes" (get params "confirm")) (throw (ex-info "Review the preview and confirm the service merge." {})))
  (let [result
        (jdbc/with-transaction [tx (db/get-datasource)]
          (sql/query tx ["select id from import_approval_lock where id=1 for update"])
          (let [{:keys [selected target field-sources pending? kept removed orders] :as planned} (plan tx params)
                snapshot (:context planned)
                _ (when (and (some #(str/starts-with? % "variation-name-") (keys params))
                             (not= "selected-settings-v2" (get params "variation-policy")))
                    (throw (ex-info "Variation handling changed. Return to the comparison and preview the merge again; variation names are now preserved." {})))
                approved-shared (into {} (for [decision (get-in planned [:performance-plan :variation-conflicts])
                                          :let [id (get-in decision [:matching :id])]
                                          :when (= "yes" (get params (str "approve-shared-variation-" id)))] [id (set (map :id (:outside decision)))]))
                _ (doseq [decision (get-in planned [:performance-plan :variation-conflicts])
                          :let [id (get-in decision [:matching :id])]
                          :when (not (approved-shared id))]
                    (throw (ex-info "Review the other services using the conflicting variation and explicitly approve the shared variation decision in preview, or change the selected variation." {})))
                ;; Lock the library rows as well as the shared approval lock; stale views cannot silently win.
                _ (doseq [row (:services snapshot)] (sql/query tx ["select id from church_service where id=? for update" (:id row)] vimeo/opts))
                _ (when-not (= (get params "version") (fingerprint (context tx params)))
                    (throw (ex-info "Service references changed while locking. Reload the comparison." {})))
                pending-id (when pending? (review/approve-in-transaction! tx (get-in snapshot [:selection :id]) (get-in snapshot [:selection :revision]) {:service-overrides (:service-overrides planned)}))
                source-ids (mapv #(if (= "incoming" (:key %)) pending-id (:id %)) selected)
                target-id (if (= target "incoming") pending-id (positive-id target))
                actual (into {} (for [row selected :let [id (if (= "incoming" (:key row)) pending-id (:id row))]]
                                 [(:key row) (sql/get-by-id tx :church_service id vimeo/opts)]))
                final (into {} (for [[field source] field-sources] [field (get-in actual [source field])]))
                pending-performances (when pending-id (sql/query tx ["select * from song_performance where church_service_id=? order by order_in_service, id" pending-id] vimeo/opts))
                ;; Incoming review songs are stored in service order on approval.
                incoming-keys (map :key (sort-by :order_in_service (filter #(= "incoming" (:service-key %)) (concat kept removed))))
                performance-ids (merge (into {} (for [p (:performances snapshot)] [(str "performance-" (:id p)) (:id p)]))
                                       (zipmap incoming-keys (map :id pending-performances)))
                original-performances (into {} (for [[key id] performance-ids]
                                                   [key (sql/get-by-id tx :song_performance id vimeo/opts)]))
                  original-variations (into {} (for [id (set (map :song_variation_id (vals original-performances)))]
                                                 [id (sql/get-by-id tx :song_variation id vimeo/opts)]))
                before {:services (vec (vals actual)) :performances (vec (concat (:performances snapshot) pending-performances))
                        :context snapshot :variations (vec (vals original-variations))}
                event (:id (sql/insert! tx :service_merge_event
                             {:retained_service_id target-id :before_data (serialize before)
                              :after_data (serialize {:final final :kept kept :removed removed :orders orders :field-sources field-sources :performance-plan (:performance-plan planned)})} vimeo/opts))
                deleted (remove #{target-id} source-ids)]
            (doseq [row (vals actual)]
              (sql/insert! tx :service_merge_source
                {:church_service_id target-id :merge_event_id event :original_service_id (:id row)
                 :original_date (:date row) :original_title (:title row) :vimeo_url (:link_vimeo row)
                 :vimeo_video_title (:vimeo_video_title row) :youtube_url (:link_youtube row)
                 :sermon_title_image_path (:sermon_title_image_path row)}))
            (doseq [p kept]
              (let [key (:key p) picks (get-in planned [:performance-plan :fields key])
                    source-row (fn [field] (get original-performances (picks field)))
                    song-variation (get original-variations (:song_variation_id (source-row :song)))
                    chosen-variation (get original-variations (:song_variation_id (source-row :variation)))
                    variation-id (ensure-performance-variation! tx
                                   (assoc (select-keys chosen-variation [:variation_name :example_link :prominent_electric_guitar :musical_key])
                                          :variation_name (get-in planned [:performance-plan :variation-names key]) :song_id (:song_id song-variation))
                                   (set (map performance-ids (map :key (concat kept removed)))) approved-shared)
                    fields (into {} (for [[field] performance-fields :when (not (#{:song :variation} field))]
                                      [field (get (source-row field) field)]))]
                (sql/update! tx :song_performance (assoc fields :song_variation_id variation-id :church_service_id target-id :order_in_service (orders key))
                             {:id (performance-ids key)})))
            (doseq [[old destination] (get-in planned [:performance-plan :redirects])
                    :let [old-id (performance-ids old) new-id (performance-ids destination)]]
              (sql/update! tx :performance_merge_alias {:canonical_performance_id new-id} {:canonical_performance_id old-id})
              (sql/insert! tx :performance_merge_alias {:old_performance_id old-id :canonical_performance_id new-id :merge_event_id event}))
            (doseq [p removed] (sql/delete! tx :song_performance {:id (performance-ids (:key p))}))
            ;; Only variations touched by this reconciliation are candidates for cleanup.
            ;; Check global references, including services outside this merge.
            (let [kept-by-key (into {} (map (fn [p] [(:key p) (sql/get-by-id tx :song_performance (performance-ids (:key p)) vimeo/opts)]) kept))]
              (doseq [[old-id old] original-variations
                      :when (empty? (sql/find-by-keys tx :song_performance {:song_variation_id old-id} vimeo/opts))]
                (let [replacement (first (keep (fn [[key original]]
                                                (when (= old-id (:song_variation_id original))
                                                  (:song_variation_id (get kept-by-key (or (get-in planned [:performance-plan :redirects key]) key)))))
                                              original-performances))]
                  (if (and replacement (not= replacement old-id))
                    (variation-merge/redirect! tx old-id replacement {})
                    (sql/delete! tx :variation_merge_alias {:canonical_variation_id old-id}))
                  (sql/delete! tx :song_variation {:id old-id}))))
            (doseq [old deleted]
              (sql/update! tx :vimeo_import_selection {:imported_service_id target-id} {:imported_service_id old})
              (sql/update! tx :service_video_source {:church_service_id target-id} {:church_service_id old})
              (sql/update! tx :service_merge_source {:church_service_id target-id} {:church_service_id old})
              (sql/update! tx :service_merge_alias {:canonical_service_id target-id} {:canonical_service_id old})
              (sql/insert! tx :service_merge_alias {:old_service_id old :canonical_service_id target-id :merge_event_id event})
              (sql/delete! tx :church_service {:id old}))
            (sql/update! tx :service_merge_event
              {:after_data (serialize {:final final :kept kept :removed removed :orders orders :field-sources field-sources
                                      :performance-plan (:performance-plan planned) :performance-ids performance-ids
                                      :performance-results (mapv #(sql/get-by-id tx :song_performance (performance-ids (:key %)) vimeo/opts) kept)})}
              {:id event})
            (sql/update! tx :church_service final {:id target-id})
            {:service-id target-id :selection-id (when pending? (get-in snapshot [:selection :id]))}))]
    ;; Images are archived by approval before commit. Cleanup verifies them and retains cache if needed.
    (when-let [id (:selection-id result)]
      (try (cleanup/cleanup-approved! id) (catch Exception _ nil)))
    (:service-id result)))
