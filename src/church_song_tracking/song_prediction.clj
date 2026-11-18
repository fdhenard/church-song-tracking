(ns church-song-tracking.song-prediction
  (:require [clojure.string :as str] [clojure.set :as set]
            [next.jdbc.sql :as sql]
            [church-song-tracking.song-statistics :as stats]
            [church-song-tracking.sermon-similarity :as semantic]
            [church-song-tracking.song-tags :as tags]
            [church-song-tracking.vimeo-import :as vimeo]))

(def stop-words #{"a" "an" "and" "are" "as" "at" "be" "by" "for" "from" "in" "is" "it" "of" "on" "or" "our" "the" "to" "with" "your"})
(defn words [text]
  (set (remove stop-words (re-seq #"[\p{L}\p{N}]+" (str/lower-case (or text ""))))))
(defn similarity [a b]
  (let [a (words a) b (words b)]
    (if (seq a) (double (/ (count (set/intersection a b)) (count a))) 0.0)))
(defn scripture [text]
  ;; Keep book and chapter together: a shared chapter number in another book is not a match.
  (when-let [[_ book chapter] (re-find #"(?i)^\s*((?:[1-3]\s*)?[a-z]+(?:\s+[a-z]+)*)\s+(\d+)" (or text ""))]
    [(str/replace (str/lower-case book) #"\s+" "") chapter]))
(defn scripture-match [a b]
  (let [a (scripture a) b (scripture b)]
    (cond (or (nil? a) (nil? b)) 0.0 (= a b) 1.0 (= (first a) (first b)) 0.25 :else 0.0)))
(defn options [ds]
  {:leaders (stats/leaders ds)
   :holidays (sql/query ds ["select * from holiday order by name, id"] vimeo/opts)
   :tags (tags/all-tags ds)})
(defn parse-options [available params]
  (let [id! (fn [value rows label]
              (when-not (str/blank? value)
                (let [id (parse-long value)]
                  (when-not (some #(= id (:id %)) rows)
                    (throw (ex-info (str "Choose " label " from the list.") {}))) id)))
        ids (mapv #(id! (str %) (:tags available) "tags") (tags/values (get params "tags")))]
    (when (or (> (count ids) 100) (some nil? ids)) (throw (ex-info "Choose valid tags from the list." {})))
    (doseq [k ["title" "scripture"]]
      (when (> (count (get params k "")) 1000) (throw (ex-info "Keep sermon inputs under 1,000 characters." {}))))
    {:ai? (= "on" (get params "ai"))
     :leader (id! (get params "leader" "") (:leaders available) "a song leader")
     :holiday (id! (get params "holiday" "") (:holidays available) "a holiday")
     :tag-ids (set ids) :title (str/trim (get params "title" ""))
     :scripture (str/trim (get params "scripture" ""))}))

(defn rank-songs [songs services usages assignments available {:keys [leader holiday tag-ids title scripture] :as input}]
  (let [services (filter #(pos? (count (get usages (:id %)))) services)
        leader-services (filter #(= leader (:song_leader_id %)) services)
        holiday-services (filter #(= holiday (:holiday_id %)) services)
        holiday-name (:name (first (filter #(= holiday (:id %)) (:holidays available))))
        matching-tags (filter #(or (contains? tag-ids (:id %))
                                  (and holiday-name (= (words holiday-name) (words (:name %))))) (:tags available))
        rate (fn [history song-id]
               (if (seq history) (double (/ (count (filter #(contains? (get usages (:id %)) song-id) history)) (count history))) 0.0))]
    (->> songs
         (map (fn [song]
                (let [id (:id song) history (filter #(contains? (get usages (:id %)) id) services)
                      overall (rate services id) leader-rate (if leader (rate leader-services id) 0.0)
                      holiday-rate (if holiday (rate holiday-services id) 0.0)
                      matched (filter #(contains? (get assignments id #{}) (:id %)) matching-tags)
                      title-score (reduce max 0.0 (map #(similarity title (:sermon_title %)) history))
                      scripture-score (reduce max 0.0 (map #(scripture-match scripture (:sermon_scripture %)) history))
                      related (->> history (keep #(when-let [match (get (:related-sermons input) (:id %))] (assoc match :id (:id %)))) (sort-by (comp - :score)) first)
                      reasons (cond-> []
                                related (conj (str "AI theme match: used with “" (:title related) "”" (when (seq (:scripture related)) (str " (" (:scripture related) ")")) "."))
                                (pos? leader-rate) (conj (str "Chosen in " (count (filter #(= leader (:song_leader_id %)) history)) " of " (count leader-services) " recorded services led by this leader."))
                                (seq matched) (conj (str "Matching tags: " (str/join ", " (map :name matched)) "."))
                                (pos? holiday-rate) (conj (str "Used in " (count (filter #(= holiday (:holiday_id %)) history)) " services for " holiday-name "."))
                                (pos? title-score) (conj (str "Used with a sermon sharing title words: “" (:sermon_title (apply max-key #(similarity title (:sermon_title %)) history)) "”."))
                                (pos? scripture-score) (conj (str "Used with " (:sermon_scripture (apply max-key #(scripture-match scripture (:sermon_scripture %)) history)) " (" (if (= 1.0 scripture-score) "same book and chapter" "same biblical book") ")."))
                                (pos? overall) (conj (str "Appears in " (count history) " recorded services overall.")))]
                  (assoc song :score (+ (* 4 leader-rate) (* 3 holiday-rate) (* 3 (count matched))
                                                     (* 2 title-score) (* 2 scripture-score) (* 3 (or (:score related) 0.0)) overall)
                         :reasons reasons :related-sermon-id (:id related)))))
         (filter #(pos? (:score %)))
         (sort-by (juxt (comp - :score) (comp str/lower-case :name) (comp str/lower-case :artist) :id))
         (take 10) vec)))
(defn report [ds available input]
  (let [services (sql/query ds ["select * from church_service"] vimeo/opts)
        usages (reduce (fn [m row] (update m (:church_service_id row) (fnil conj #{}) (:song_id row))) {}
                 (sql/query ds ["select p.church_service_id, v.song_id from song_performance p join song_variation v on v.id=p.song_variation_id"] vimeo/opts))
        assignments (reduce (fn [m row] (update m (:song_id row) (fnil conj #{}) (:tag_id row))) {}
                      (sql/query ds ["select * from song_tag_assignment"] vimeo/opts))]
    (let [ai-result (when (:ai? input)
                      (try {:matches (semantic/related (filter #(seq (get usages (:id %))) services) input)}
                           (catch clojure.lang.ExceptionInfo e {:error (.getMessage e)})))
          input (assoc input :related-sermons (:matches ai-result))]
    {:ai-status (cond (not (:ai? input)) :off (:error ai-result) :unavailable :else :on)
     :ai-error (:error ai-result)
     :songs (rank-songs (sql/query ds ["select * from song"] vimeo/opts) services usages assignments available input)
     :service-count (count (filter #(seq (get usages (:id %))) services))})))
