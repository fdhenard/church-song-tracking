(ns church-song-tracking.admin
  (:require [church-song-tracking.musical-key :as musical-key]
            [church-song-tracking.db :as db]
            [church-song-tracking.song-tags :as song-tags]
            [church-song-tracking.library-cleanup :as library-cleanup]
            [church-song-tracking.song-statistics :as song-statistics]
            [church-song-tracking.song-prediction :as prediction]
            [church-song-tracking.record-merge :as record-merge]
            [church-song-tracking.song-merge :as song-merge]
            [church-song-tracking.service-merge :as service-merge]
            [church-song-tracking.service-reimport :as service-reimport]
            [church-song-tracking.song-variation :as variation]
            [church-song-tracking.variation-merge :as variation-merge]
            [church-song-tracking.cache-cleanup :as cleanup]
            [church-song-tracking.slide-archive :as archive]
            [church-song-tracking.config :as config]
            [church-song-tracking.vimeo-session :as vimeo-session]
            [church-song-tracking.video-metadata :as metadata]
            [church-song-tracking.video-merge :as video-merge]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [church-song-tracking.import-review :as review]
            [church-song-tracking.video-jobs :as jobs]
            [church-song-tracking.video-range :as video-range]
            [church-song-tracking.vimeo-import :as vimeo]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [hiccup2.core :as h]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [reitit.ring :as router]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery *anti-forgery-token*]]))

;; [column label type required?]. Only these declared columns can be written.
(def entities
  {"services" {:table :church_service :label "Services" :singular "Service"
               :fields [[:date "Date" :date true] [:title "Service title" :text true]
                        [:preacher_id "Preacher" "people" true] [:song_leader_id "Song leader" "people" true]
                        [:sermon_title "Sermon title" :text true] [:sermon_scripture "Sermon scripture" :text] [:sermon_series_id "Sermon series" "series"]
                        [:sermon_topic "Sermon topic" :text] [:holiday_id "Holiday" "holidays"]
                        [:link_youtube "YouTube link" :url] [:link_vimeo "Vimeo link" :url]
                        [:sermon_title_slide_time_youtube "Sermon slide · YouTube" :text]
                        [:sermon_title_slide_time_vimeo "Sermon slide · Vimeo" :text]
                        [:interest_for_next_mens_retreat "Consider for men's retreat" :boolean]]}
   "songs" {:table :song :label "Songs" :singular "Song"
            :fields [[:name "Song title" :text true] [:artist "Artist" :text]]}
   "people" {:table :person :label "People" :singular "Person" :fields [[:name "Name" :text true]]}
   "series" {:table :sermon_series :label "Sermon series" :singular "Sermon series" :fields [[:name "Name" :text true]]}
   "holidays" {:table :holiday :label "Holidays" :singular "Holiday" :fields [[:name "Name" :text true]]}
   "variations" {:table :song_variation :label "Variations" :singular "Variation"
                 :fields [[:song_id "Song" "songs" true] [:variation_name "Variation name" :text true] [:musical_key "Key" :musical-key]
                          [:example_link "Example link" :url] [:prominent_electric_guitar "Prominent electric guitar" :nullable-boolean]]}
   "performances" {:table :song_performance :label "Performances" :singular "Performance"
                   :fields [[:church_service_id "Service" "services" true]
                            [:song_variation_id "Song variation" "variations" true]
                            [:order_in_service "Order in service" :number true]
                            [:musical_key "Key" :musical-key]
                            [:time_in_service_youtube "YouTube time" :text] [:time_in_service_vimeo "Vimeo time" :text]
                            [:song_start_time_vimeo "Song start · Vimeo (optional)" :text]]}})

(def opts {:builder-fn rs/as-unqualified-lower-maps})
(defn rows [conn entity] (sql/query conn [(str "select * from " (name (:table (entities entity))))] opts))
(defn record [conn entity id] (sql/get-by-id conn (:table (entities entity)) id opts))
(defn label-for [entity row]
  (case entity
    "services" (str (:date row) " · " (if (= (str (:date row)) (:title row)) (:sermon_title row) (:title row)))
    "songs" (str (:name row) (when (seq (:artist row)) (str " · " (:artist row))))
    "variations" (str (:variation_name row) " (#" (:song_id row) ")")
    (or (:name row) (str "#" (:id row)))))
(defn choices [conn entity]
  (sort-by (comp str/lower-case second)
    (for [row (rows conn entity)]
      [(:id row) (if (= entity "variations")
                  (str (:name (record conn "songs" (:song_id row))) " · " (:variation_name row))
                  (label-for entity row))])))
(defn url [entity id] (str "/" entity (when id (str "/" id))))
(defn redirect [location] {:status 303 :headers {"Location" location} :body ""})
(defn token [] [:input {:type "hidden" :name "__anti-forgery-token" :value *anti-forgery-token*}])
(defn page [entity title & content]
  {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
   :body (str "<!doctype html>"
          (h/html [:html {:lang "en"}
                   [:head [:meta {:charset "utf-8"}] [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                    [:title (str title " · Church Song Tracking")] [:link {:rel "stylesheet" :href "/admin.css"}]
                    [:script {:src "/review.js" :defer true}]]
                   [:body [:aside [:a.brand {:href "/services"} [:span.mark "♫"] "Church Song Tracking"]
                           [:p.nav-label "LIBRARY"]
                           [:nav (for [key ["services" "songs" "people" "series" "holidays" "variations" "performances"]]
                                   [:a {:href (url key nil) :class (when (= key entity) "active")} (:label (entities key))])
                            [:a {:href "/predictions" :class (when (= entity "predictions") "active")} "Song prediction"]
                            [:a {:href "/statistics" :class (when (= entity "statistics") "active")} "Song statistics"]
                            [:a {:href "/imports" :class (when (= entity "imports") "active")} "Import services"]]]
                    [:main [:header [:span.eyebrow "WORSHIP LIBRARY"] [:span.local "Local admin"]]
                     content]]]))})

(defn display-value [conn [_ _ type] value]
  (cond (= type "variations") (when value (let [row (record conn type value)]
                                           (str (:name (record conn "songs" (:song_id row))) " · " (:variation_name row))))
        (string? type) (when value (label-for type (record conn type value)))
        (= type :musical-key) (musical-key/label value)
        (= type :nullable-boolean) (variation/guitar-label value)
        (= type :boolean) (if value "Yes" "—")
        (nil? value) "—"
        :else (str value)))

(defn recording-sources-panel [conn kind id]
  (let [entries (if (= kind :service) (video-merge/service-sources conn id) (video-merge/sources conn id))]
    (when (seq entries)
      [:section.notice [:strong "Merged recording"]
       [:p "Original Vimeo recordings in playback order. Review times refer to the combined video; each source’s start is shown below."]
       [:ol (for [entry entries]
              [:li [:a {:href (:vimeo_url entry) :target "_blank" :rel "noopener noreferrer"} (:vimeo_video_title entry)]
               " · " (:vimeo_url entry)
               (when (:merged_start_seconds entry)
                 (str " · combined video starts at " (video-range/timestamp (long (:merged_start_seconds entry)))))])]])))

(defn merge-link [url]
  (str "/imports/merge?video=" (java.net.URLEncoder/encode (str url) "UTF-8")))

(defn merge-page
  ([req] (merge-page req nil))
  ([req error]
   (let [params (:params req) url (get params "video")
         candidates (->> (vimeo/queue)
                         (filter #(and (jobs/downloaded-file (:id %))
                                       (not (:imported_service_id %)) (not (:is_merged %))
                                       (not (:merged_into_selection_id %))
                                       (not (jobs/active-statuses (:job_status %)))))
                         (map #(assoc % :url (:vimeo_url %) :title (metadata/original-title %)))
                         vimeo/newest-videos)
         included (set (video-merge/values (get params "include")))
         chosen (filterv #(included (str (:id %))) candidates)
         ordering? (= "order" (get params "step"))
         valid? (and (<= 2 (count chosen) 8) (= (count chosen) (count included)))
         ordering? (and ordering? valid?)
         submitted-order (zipmap (video-merge/values (get params "part")) (range))
         ordered (sort-by #(get submitted-order (str (:id %)) Long/MAX_VALUE) chosen)]
     (page "imports" "Merge recordings"
       [:a.back {:href "/imports"} "← Import services"]
       [:h1 (if ordering? "Arrange recordings" "Merge recordings")]
       (when error [:p.error {:role "alert"} error])
       (when (and (= "order" (get params "step")) (not valid?))
         [:p.error {:role "alert"} "Select two to eight available downloaded recordings to continue."])
       (if-not ordering?
         [:form.card {:method "get" :action "/imports/merge"}
          [:input {:type "hidden" :name "video" :value url}]
          [:input {:type "hidden" :name "step" :value "order"}]
          (when (contains? params "recording-title")
            [:input {:type "hidden" :name "recording-title" :value (get params "recording-title")}])
          (for [id (video-merge/values (get params "part"))]
            [:input {:type "hidden" :name "part" :value id}])
          [:p "Select two to eight recordings to merge."]
          [:div (for [row candidates]
            [:label.checkbox
             [:input {:type "checkbox" :name "include" :value (:id row)
                      :checked (boolean (if (contains? params "include")
                                          (included (str (:id row))) (= url (:vimeo_url row))))}]
             (:title row)])]
          (when (empty? candidates) [:p "No downloaded recordings are available to merge."])
          [:button {:disabled (< (count candidates) 2)} "Continue"]]
         [:div
          [:p "Use Move up / Move down to set playback order, starting with the first recording."]
          [:form.card {:id "merge-recordings" :method "post" :action "/imports/merge"} (token)
           [:input {:type "hidden" :name "video" :value url}]
           [:label.field [:span "Recording title"]
            [:input {:type "text" :name "recording-title" :list "merge-recording-titles" :required true
                     :value (get params "recording-title" (:title (first ordered)))}]
            [:small.muted "Choose a selected recording’s title or type your own."]]
           [:datalist {:id "merge-recording-titles"}
            (for [title (distinct (map :title ordered))] [:option {:value title}])]
           [:div {:id "merge-parts"}
            (for [row ordered]
              [:section.card {:data-merge-part true}
               [:strong (:title row)]
               [:video {:controls true :preload "metadata"
                        :src (str "/imports/" (:id row) "/preview")
                        :aria-label (str "Preview " (:title row))
                        :style "display:block;width:100%;max-width:720px;margin:12px 0"}]
               [:input {:type "hidden" :name "part" :value (:id row)}]
               [:input {:type "hidden" :name "include" :value (:id row)}]
               [:div.actions
                [:button.secondary {:type "button" :data-merge-move "up" :aria-label (str "Move up " (:title row))} "Move up"]
                [:button.secondary {:type "button" :data-merge-move "down" :aria-label (str "Move down " (:title row))} "Move down"]]])]
           [:p "Entire recordings are joined; overlapping content will remain. The combined video needs a new OCR pass and review."]
           [:label.checkbox [:input {:type "checkbox" :name "allow-conversion" :value "yes" :checked (or (not error) (= "yes" (get params "allow-conversion")))}]
            "Allow conversion if the recordings use different formats (slower)"]
           [:label.checkbox [:input {:type "checkbox" :name "discard-review" :value "yes" :checked (= "yes" (get params "discard-review"))}]
            "I understand existing OCR review work on these parts will be replaced by a new OCR pass on the combined video"]
           [:div.actions
            [:button.secondary {:type "submit" :formmethod "get" :name "step" :value "select" :formnovalidate true} "Back to selection"]
            [:button {:name "step" :value "order"} "Create merged service video"]]]])))))

(defn save-merge! [req]
  (try
    (let [id (video-merge/create! (:params req))]
      (redirect (str "/imports?notice=merge-queued&merged=" id)))
    (catch clojure.lang.ExceptionInfo e (assoc (merge-page req (.getMessage e)) :status 422))))

(defn song-merge-link [id] (str "/songs/merge?group=" id))
(defn song-merge-page
  ([req] (song-merge-page req nil))
  ([req error]
   (let [ds (db/get-datasource) id (some-> (get-in req [:params "group"]) parse-long)
         songs (when id (song-merge/candidates ds id))
         selected (set (song-merge/values (get-in req [:params "source"])))
         retained (or (get-in req [:params "retained"]) (some-> songs first :id str))]
     (page "songs" "Merge duplicate songs"
       [:a.back {:href "/songs"} "← Songs"]
       [:h1 "Merge duplicate songs"]
       (when error [:p.error {:role "alert"} error])
       (if-not id
         (let [q (str/lower-case (get-in req [:params "q"] ""))
               groups (filter #(str/includes? (str/lower-case (str/join " " (mapcat (juxt :name :artist) %))) q) (song-merge/groups ds))]
           (list
             [:p "Compare records with the same title, then choose which actually represent the same song. Names are grouped ignoring letter case and extra spaces."]
             [:form.search {:method "get" :action "/songs/merge"}
              [:input {:name "q" :value (get-in req [:params "q"] "") :placeholder "Search duplicate titles or artists" :aria-label "Search duplicate songs"}]
              [:button "Search"]]
             [:div.table-wrap [:table [:thead [:tr [:th "Song title"] [:th "Artists"] [:th "Song records"] [:th "Performances"] [:th ""]]]
              [:tbody (if (seq groups)
                (for [group groups]
                  [:tr [:td (:name (first group))] [:td (str/join " · " (distinct (map :artist group)))]
                   [:td (count group)] [:td (reduce + (map :performance_count group))]
                   [:td [:a.button.secondary {:href (song-merge-link (:id (first group)))} "Compare and merge"]]])
                [[:tr [:td {:colspan 5} "No matching duplicate song titles."]]])]]]))
         (if (< (count songs) 2)
           [:p.notice "This song has no other same-title records to compare. It may already have been merged. " [:a {:href "/songs/merge"} "View other duplicate songs"]]
           (let [comparison (song-merge/snapshot ds (map :id songs) false)
                 variations (:variations comparison)
                 by-song (group-by :song_id variations)
                 performances-by-variation (frequencies (map :song_variation_id (:performances comparison)))
                 songs (mapv #(assoc % :variation_count (count (get by-song (:id %)))
                                      :performance_count (reduce + 0 (map (fn [v] (get performances-by-variation (:id v) 0)) (get by-song (:id %)))))
                             (:songs comparison))]
             (list
               [:p.notice "The same title with different artists may identify different songs. Select only records you have verified represent the same song. No records are selected automatically."]
               [:form.card {:method "post" :action "/songs/merge" :id "song-merge-form"} (token)
                [:input {:type "hidden" :name "action" :value "preview"}]
                [:input {:type "hidden" :name "group" :value id}]
                [:input {:type "hidden" :name "version" :value (song-merge/fingerprint comparison)}]
                (for [song songs] [:input {:type "hidden" :name "candidate" :value (:id song)}])
                [:h2 "Compare song records"]
                [:div.table-wrap [:table [:thead [:tr [:th "Include"] [:th "Song record"] [:th "Song title"] [:th "Artist"] [:th "Variations"] [:th "Performances"] [:th "Added"]]]
                 [:tbody (for [song songs]
                   [:tr
                    [:td [:input {:type "checkbox" :name "source" :value (:id song) :checked (contains? selected (str (:id song)))
                                 :aria-label (str "Include song #" (:id song) ": " (:name song) " · " (:artist song))}]]
                    [:td [:a {:href (str "/songs/" (:id song)) :target "_blank" :rel "noopener noreferrer"} (str "Song #" (:id song))]]
                    [:td (:name song)] [:td (:artist song)] [:td (:variation_count song)] [:td (:performance_count song)] [:td (str (:time_added song))]])]]]
                [:h2 "Final song"]
                [:div.fields
                 [:label.field [:span "Retain this song record"]
                  [:select {:name "retained" :required true}
                   (for [song songs]
                     [:option {:value (:id song) :selected (= retained (str (:id song)))}
                      (str "#" (:id song) " · " (:name song) " · " (:artist song))])]]
                 [:label.field [:span "Choose song title from"]
                  [:select {:name "name-source" :data-merge-field "name"}
                   [:option {:value "custom" :selected (= "custom" (get-in req [:params "name-source"] "custom"))} "Enter or edit a value below"]
                   (for [song songs]
                     [:option {:value (:id song) :data-field-value (:name song)
                               :selected (= (str (:id song)) (get-in req [:params "name-source"]))}
                      (str "Song #" (:id song) " · " (:name song))])]]
                 [:label.field [:span "Final song title"]
                  [:input {:name "name" :required true :maxlength 2000 :list "merge-song-titles"
                           :value (get-in req [:params "name"] (:name (first songs)))}]
                  [:datalist {:id "merge-song-titles"} (for [name (distinct (map :name songs))] [:option {:value name}])]]
                 [:label.field [:span "Choose artist / author from"]
                  [:select {:name "artist-source" :data-merge-field "artist"}
                   [:option {:value "custom" :selected (= "custom" (get-in req [:params "artist-source"] "custom"))} "Enter or edit a value below"]
                   (for [song songs]
                     [:option {:value (:id song) :data-field-value (:artist song)
                               :selected (= (str (:id song)) (get-in req [:params "artist-source"]))}
                      (str "Song #" (:id song) " · " (:artist song))])]]
                 [:label.field [:span "Final artist / author"]
                  [:input {:name "artist" :required true :maxlength 2000 :list "merge-song-artists"
                           :value (get-in req [:params "artist"] (:artist (first songs)))}]
                  [:datalist {:id "merge-song-artists"} (for [artist (distinct (map :artist songs))] [:option {:value artist}])]]]
                [:p "Choose which included song supplies each field, or enter corrections below. Choose the song row to update using Retain this song record. Its ID and creation date are kept. References move to that row and the other included song rows are deleted only after confirmation."]
                [:h2 "Variation fields to preserve"]
                [:p "Choose which variations to keep. For each variation to delete, choose a kept variation to receive its performances. The kept variation’s name, example link, and guitar setting are preserved. Kept names must be unique within the merged song."]
                [:div.table-wrap [:table [:thead [:tr [:th "Song"] [:th "Variation"] [:th "Example link"] [:th "Prominent electric guitar"] [:th "Key"] [:th "Merge action"]]]
                 [:tbody (for [variation variations]
                   [:tr [:td (str "#" (:song_id variation))]
                    [:td [:a {:href (str "/variations/" (:id variation)) :target "_blank" :rel "noopener noreferrer"} (str "#" (:id variation) " · " (:variation_name variation))]]
                    [:td (or (:example_link variation) "—")] [:td (variation/guitar-label (:prominent_electric_guitar variation))] [:td (musical-key/label (:musical_key variation))]
                    [:td [:select {:name (str "variation-choice-" (:id variation)) :aria-label (str "Action for variation #" (:id variation))}
                          [:option {:value "keep" :selected (= "keep" (get-in req [:params (str "variation-choice-" (:id variation))] "keep"))} "Keep this variation"]
                          (for [other variations :when (not= (:id variation) (:id other))]
                            [:option {:value (:id other) :selected (= (str (:id other)) (get-in req [:params (str "variation-choice-" (:id variation))]))}
                             (str "Delete; move performances to #" (:id other) " · " (:variation_name other) " (song #" (:song_id other) ")")])]]])]]]
                [:div.actions [:button "Preview merge"] [:a.button.secondary {:href "/songs/merge"} "Other duplicate songs"]]]))))))))

(defn song-merge-confirmation [req planned]
  (let [{:keys [songs target name artist performances variation-plan]} planned]
    (page "songs" "Confirm song merge"
      [:h1 "Confirm song merge"]
      [:p "Review the final song and references before saving. This step combines library records; original OCR/source notes remain preserved as historical evidence."]
      [:p "Tags from all included songs will be kept on the retained song."]
      [:section.card [:h2 name] [:p (str "Artist / author: " artist)] [:p (str "Retained song record: #" target)]
       [:p (str (count songs) " song records become one. " (count performances) " performance references are preserved across "
                (count (set (map :church_service_id performances))) " services.")]
       [:p (str (count (:kept variation-plan)) " distinct variations remain; " (count (:redirects variation-plan)) " variation records are deleted after their references move.")]
       [:ul (for [song songs] [:li (str "#" (:id song) " · " (:name song) " · " (:artist song)
                                                      (if (= target (:id song)) " (retained)" " (references move to retained song; this row is deleted)"))])]]
      [:section.card [:h2 "Variation changes"]
       [:ul (for [v (:variations planned)]
              [:li (str "#" (:id v) " · " (:variation_name v)
                        (if-let [destination (get-in variation-plan [:redirects (:id v)])]
                          (str " — delete; move all performances to variation #" destination)
                          " — keep name, example link, and guitar setting"))])]]
      [:form.card {:method "post" :action "/songs/merge"} (token)
       (for [[key value] (:params req) :when (str/starts-with? key "variation-choice-")]
         [:input {:type "hidden" :name key :value value}])
       (for [key ["group" "version" "candidate" "source" "retained" "name" "artist" "name-source" "artist-source"]
             value (song-merge/values (get-in req [:params key]))]
         [:input {:type "hidden" :name key :value value}])
       [:label.checkbox [:input {:type "checkbox" :name "confirm" :value "yes" :required true}]
        "I verified that these records represent the same song and approve the final title and artist."]
       [:p "Performance IDs, service order, timestamps, archived images, and source notes are preserved. Old song links redirect to the retained song. A merge history snapshot is saved in H2."]
       [:div.actions [:button {:name "action" :value "merge"} "Confirm and merge songs"]
        [:button.secondary {:name "action" :value "edit" :formnovalidate true} "Back to comparison"]]])))

(defn save-song-merge! [req]
  (try
    (let [params (:params req) action (get params "action")]
      (case action
        "edit" (song-merge-page req)
        "preview" (song-merge-confirmation req (song-merge/plan (db/get-datasource) params false))
        "merge" (redirect (str "/songs/" (song-merge/merge! params) "?notice=songs-merged"))
        (throw (ex-info "Unknown song merge action." {}))))
    (catch clojure.lang.ExceptionInfo e (assoc (song-merge-page req (.getMessage e)) :status 422))
    (catch java.sql.SQLException _ (assoc (song-merge-page req "The references changed or could not be updated. Nothing was merged. Reload the comparison and try again.") :status 422))))


(defn variation-merge-link [id] (str "/variations/merge?group=" id))
(defn variation-merge-page
  ([req] (variation-merge-page req nil))
  ([req error]
   (let [ds (db/get-datasource) id (some-> (get-in req [:params "group"]) parse-long)
         song-filter (some-> (get-in req [:params "song"]) parse-long)
         anchor (when id (or (sql/get-by-id ds :song_variation id opts)
                             (when-let [canonical (variation-merge/canonical-id ds id)] (sql/get-by-id ds :song_variation canonical opts))))
         comparison (when anchor (variation-merge/snapshot ds (:song_id anchor) false))
         song (:song comparison)
         candidates (when anchor (filterv #(= (variation/name-key (:variation_name anchor)) (variation/name-key (:variation_name %))) (:variations comparison)))
         first-choice (first candidates)
         selected (set (variation-merge/values (get-in req [:params "source"])))
         retained (or (get-in req [:params "retained"]) (some-> first-choice :id str))]
     (page "variations" "Merge duplicate variations"
       [:a.back {:href "/variations"} "← Variations"]
       [:h1 "Merge duplicate variations"]
       (when error [:p.error {:role "alert"} error])
       (if-not id
         (let [q (str/lower-case (get-in req [:params "q"] ""))
               groups (filter #(str/includes? (str/lower-case (str/join " " (mapcat (juxt :song_name :song_artist :variation_name) %))) q)
                              (variation-merge/groups ds song-filter))]
           (list
             [:p "Compare variations with the same name within the same song. Names are grouped ignoring letter case and extra spaces. Merging happens only after you choose final values and confirm."]
             (when song-filter [:p [:a {:href (str "/songs/" song-filter)} "Back to this song"] " · " [:a {:href "/variations/merge"} "All duplicate variations"]])
             [:form.search {:method "get" :action "/variations/merge"}
              (when song-filter [:input {:type "hidden" :name "song" :value song-filter}])
              [:input {:name "q" :value (get-in req [:params "q"] "") :placeholder "Search song, artist, or variation" :aria-label "Search duplicate variations"}]
              [:button "Search"]]
             [:div.table-wrap [:table [:thead [:tr [:th "Song"] [:th "Artist"] [:th "Variation name"] [:th "Records"] [:th "Performances"] [:th ""]]]
              [:tbody (if (seq groups)
                (for [group groups :let [first-choice (first group)]]
                  [:tr [:td [:a {:href (str "/songs/" (:song_id first-choice))} (:song_name first-choice)]]
                   [:td (:song_artist first-choice)] [:td (:variation_name first-choice)] [:td (count group)]
                   [:td (reduce + (map :performance_count group))]
                   [:td [:a.button.secondary {:href (variation-merge-link (:id first-choice))} "Compare and merge"]]])
                [[:tr [:td {:colspan 6} "No matching duplicate variation names."]]])]]]))
         (if (< (count candidates) 2)
           [:p.notice "No duplicate variations remain for this selection. "
            (when anchor [:a {:href (str "/variations/" (:id anchor))} "Open current variation"])
            " · " [:a {:href "/variations/merge"} "View other duplicate variations"]]
           (let [counts (frequencies (map :song_variation_id (:performances comparison)))]
             (list
               [:h2 (str (:name song) " · " (:artist song))]
               [:p.notice "Select only variations that should become one arrangement. Compare any different example links or guitar settings before choosing the final values."]
               [:form.card {:method "post" :action "/variations/merge" :id "variation-merge-form"} (token)
                [:input {:type "hidden" :name "action" :value "preview"}]
                [:input {:type "hidden" :name "group" :value id}]
                [:input {:type "hidden" :name "song" :value (:id song)}]
                [:input {:type "hidden" :name "version" :value (variation-merge/fingerprint comparison)}]
                (for [v candidates] [:input {:type "hidden" :name "candidate" :value (:id v)}])
                [:h2 "Compare variations"]
                [:div.table-wrap [:table [:thead [:tr [:th "Include"] [:th "Variation"] [:th "Name"] [:th "Example link"] [:th "Prominent electric guitar"] [:th "Key"] [:th "Performances"] [:th "Added"]]]
                 [:tbody (for [v candidates]
                   [:tr
                    [:td [:input {:type "checkbox" :name "source" :value (:id v) :checked (contains? selected (str (:id v))) :aria-label (str "Include variation #" (:id v))}]]
                    [:td [:a {:href (str "/variations/" (:id v)) :target "_blank" :rel "noopener noreferrer"} (str "#" (:id v))]]
                    [:td (:variation_name v)]
                    [:td (if (re-matches #"https?://[^\s]+" (or (:example_link v) ""))
                           [:a {:href (:example_link v) :target "_blank" :rel "noopener noreferrer"} (:example_link v)] (or (:example_link v) "—"))]
                    [:td (variation/guitar-label (:prominent_electric_guitar v))] [:td (musical-key/label (:musical_key v))] [:td (get counts (:id v) 0)] [:td (str (:time_added v))]])]]]
                [:h2 "Final variation"]
                [:div.fields
                 [:label.field [:span "Retain this variation record"]
                  [:select {:name "retained" :required true}
                   (for [v candidates] [:option {:value (:id v) :selected (= retained (str (:id v)))} (str "#" (:id v) " · " (:variation_name v))])]]
                 [:label.field [:span "Final variation name"]
                  [:input {:name "variation_name" :required true :maxlength 2000 :list "merge-variation-names" :value (get-in req [:params "variation_name"] (:variation_name first-choice))}]
                  [:datalist {:id "merge-variation-names"} (for [name (distinct (map :variation_name candidates))] [:option {:value name}])]]
                 [:label.field [:span "Final example link (optional)"]
                  [:input {:type "url" :name "example_link" :list "merge-variation-links" :value (get-in req [:params "example_link"] (or (:example_link first-choice) ""))}]
                  [:datalist {:id "merge-variation-links"} (for [link (distinct (keep :example_link candidates))] [:option {:value link}])]]
                 [:label.field [:span "Key"]
                  [:select {:name "musical_key"} (musical-key/options (get-in req [:params "musical_key"] (:musical_key first-choice)))]]
                 [:label.field [:span "Prominent electric guitar"]
                  [:select {:name "guitar" :required true}
                   (variation/guitar-options
                     (get-in req [:params "guitar"] (variation/guitar-value (:prominent_electric_guitar first-choice))))]]]
                [:p "Choose existing field values or enter corrections. The retained record must be selected above. The final name must be unique among this song’s remaining variations."]
                [:div.actions [:button "Preview variation merge"] [:a.button.secondary {:href "/variations/merge"} "Other duplicate variations"]]]))))))))

(defn variation-merge-confirmation [req planned]
  (let [{:keys [song variations target final performances]} planned]
    (page "variations" "Confirm variation merge"
      [:h1 "Confirm variation merge"]
      [:section.card [:h2 (str (:name song) " · " (:artist song))]
       [:p (str "Retained variation: #" target " · " (:variation_name final))]
       [:p (str "Example link: " (or (:example_link final) "None"))]
       [:p (str "Key: " (musical-key/label (:musical_key final)))]
       [:p (str "Prominent electric guitar: " (variation/guitar-label (:prominent_electric_guitar final)))]
       [:p (str (count variations) " variations become one. " (count performances) " performance references are preserved across " (count (set (map :church_service_id performances))) " services.")]
       [:ul (for [v variations] [:li (str "#" (:id v) " · " (:variation_name v) (if (= target (:id v)) " (retained)" " (references move to retained variation)"))])]]
      [:form.card {:method "post" :action "/variations/merge"} (token)
       (for [key ["group" "song" "version" "candidate" "source" "retained" "variation_name" "example_link" "guitar" "musical_key"]
             value (variation-merge/values (get-in req [:params key]))]
         [:input {:type "hidden" :name key :value value}])
       [:label.checkbox [:input {:type "checkbox" :name "confirm" :value "yes" :required true}]
        "I approve combining these variations and using the final fields shown above."]
       [:p "Performance IDs, service placement, timestamps, slide-image paths, and original source notes are preserved. A merge history snapshot is saved in H2, and old variation links redirect to the retained record."]
       [:div.actions [:button {:name "action" :value "merge"} "Confirm and merge variations"]
        [:button.secondary {:name "action" :value "edit" :formnovalidate true} "Back to comparison"]]])))

(defn save-variation-merge! [req]
  (try
    (case (get-in req [:params "action"])
      "edit" (variation-merge-page req)
      "preview" (variation-merge-confirmation req (variation-merge/plan (db/get-datasource) (:params req) false))
      "merge" (redirect (str "/variations/" (variation-merge/merge! (:params req)) "?notice=variations-merged"))
      (throw (ex-info "Unknown variation merge action." {})))
    (catch clojure.lang.ExceptionInfo e (assoc (variation-merge-page req (.getMessage e)) :status 422))
    (catch java.sql.SQLException _ (assoc (variation-merge-page req "The references changed or could not be updated. Nothing was merged. Reload and try again.") :status 422))))


(defn service-merge-value [ds row field]
  (let [value (get row field) entity (case field :preacher_id "people" :song_leader_id "people" :sermon_series_id "series" :holiday_id "holidays" nil)]
    (cond
      (or (nil? value) (and (string? value) (str/blank? value))) "—"
      (= field :sermon_title_image_path) "Title slide available"
      (and entity (not= "incoming" (:key row))) (or (:name (record ds entity value)) "—")
      (boolean? value) (if value "Yes" "No")
      :else (str value))))
(defn service-merge-form [req ds data]
  (let [params (:params req) candidates (service-merge/candidates data)
        included (set (if (contains? params "source") (song-merge/values (get params "source")) (map :key candidates)))
        retained (get params "retained" (:key (first candidates)))
        performances (service-merge/performances ds data)
        kept (set (if (contains? params "version") (song-merge/values (get params "performance")) (map :key performances)))
        matching (frequencies (map #(song-merge/title-key (:song-title %)) performances))]
    [:form.card {:method "post" :action "/services/merge" :id "service-merge-form"} (token)
     [:input {:type "hidden" :name "action" :value "preview"}]
     [:input {:type "hidden" :name "date" :value (str (:date data))}]
     (when-let [selection (:selection data)] [:input {:type "hidden" :name "selection" :value (:id selection)}])
     [:input {:type "hidden" :name "version" :value (service-merge/fingerprint data)}]
     [:h2 "Compare service records"]
     [:p "Matching dates identify possible duplicates. Include only records representing the same service. A saved import draft can be approved and reconciled here in one transaction. You can use existing service fields to fill missing import details. Save any review edits before returning here."]
     [:div.table-wrap [:table [:thead [:tr [:th "Include"] [:th "Service row"] [:th "Date"] [:th "Title"] [:th "Sermon"] [:th "Source"] [:th "Sermon slide"]]]
      [:tbody (for [row candidates]
        [:tr [:td [:input {:type "checkbox" :name "source" :value (:key row) :checked (included (:key row)) :aria-label (str "Include service " (:key row))}]]
         [:td (if (= "incoming" (:key row))
                [:a {:href (str "/imports/" (get-in data [:selection :id]))} "Saved import draft"]
                [:a {:href (str "/services/" (:id row)) :target "_blank" :rel "noopener noreferrer"} (str "Service #" (:id row))])]
         [:td (str (:date row))] [:td (:title row)] [:td (:sermon_title row)]
         [:td (when (seq (:link_vimeo row)) [:a {:href (:link_vimeo row) :target "_blank" :rel "noopener noreferrer"} (or (:vimeo_video_title row) "Vimeo video")])]
         [:td (when (:sermon_title_image_path row)
                [:a {:href (if (= "incoming" (:key row)) (:sermon_title_image_path row) (str "/services/" (:id row) "/title-image")) :target "_blank" :rel "noopener noreferrer"}
                 [:img {:src (if (= "incoming" (:key row)) (:sermon_title_image_path row) (str "/services/" (:id row) "/title-image")) :alt "Sermon title slide" :loading "lazy" :style "max-width:180px"}]])]])]]]
     [:h2 "Choose the service row to keep"]
     [:label.field [:span "Retain and update this row"]
      [:select {:name "retained" :required true} (for [row candidates]
        [:option {:value (:key row) :selected (= retained (:key row))} (if (= "incoming" (:key row)) "Create the row from the saved import" (str "Service #" (:id row) " · " (:title row)))])]]
     [:h2 "Choose service fields"]
     [:p.muted "Choose a source for each field. Other source links, titles, and permanent sermon images remain in the merge history. Video files are not joined by this flow; timestamps keep their original video coordinates."]
     [:div.fields (for [[field label] service-merge/fields]
       [:label.field [:span label]
        [:select {:name (str "field-" (name field))}
         (for [row candidates]
           [:option {:value (:key row) :selected (= (get params (str "field-" (name field)) retained) (:key row))}
            (str (if (= "incoming" (:key row)) "Import" (str "#" (:id row))) " · " (service-merge-value ds row field))])]])]
     [:h2 "Song performances to keep"]
     [:p "Keep the performances you want and assign a unique order to each. Unchecked performances are removed only after confirmation; their original data is saved in the merge history. Matching song titles are flagged as suggestions. You can merge any performances from included services, even when their titles or authors differ. Use Merge into to combine duplicate performances, then choose one song, variation, and value for each field on the kept row. Unchecked rows without a merge destination are simply removed."]
     [:div.table-wrap.performance-merge-table [:table [:thead [:tr [:th "Keep"] [:th "Final order"] [:th "Source service"] [:th "Song / author"] [:th "Variation"] [:th "Video time"] [:th "Slide"] [:th "Merge duplicate"]]]
      [:tbody (for [[i performance] (map-indexed vector performances)]
        [:tr {:data-service-source (:service-key performance)}
         [:td [:input {:type "checkbox" :name "performance" :value (:key performance) :checked (kept (:key performance)) :aria-label (str "Keep " (:song-title performance) " from " (:service-key performance))}]]
         [:td [:input {:type "number" :min 1 :max 1000 :name (str "order-" (:key performance)) :aria-label (str "Order for " (:key performance))
                       :value (get params (str "order-" (:key performance)) (inc i))}]]
         [:td (if (= "incoming" (:service-key performance)) (str "Import #" (get-in data [:selection :id])) (str "Service #" (:service-key performance)))]
         [:td [:strong (:song-title performance)] [:br] (:song-author performance)
          (when (> (get matching (song-merge/title-key (:song-title performance)) 0) 1) [:p.notice "Possible duplicate song performance"])]
         [:td (:variation performance)] [:td
          (when (seq (:time_in_service_vimeo performance)) [:div (str "Vimeo: " (:time_in_service_vimeo performance))])
          (when (seq (:time_in_service_youtube performance)) [:div (str "YouTube: " (:time_in_service_youtube performance))])
          (when-not (or (seq (:time_in_service_vimeo performance)) (seq (:time_in_service_youtube performance))) "—")]
         [:td (when-let [image (:image-url performance)] [:a {:href image :target "_blank" :rel "noopener noreferrer"}
                                                        [:img {:src image :alt (str "Title slide: " (:song-title performance)) :loading "lazy" :style "max-width:180px"}]])]
         [:td
          [:select {:name (str "merge-" (:key performance)) :data-performance-merge (:key performance) :aria-label (str "Merge " (:key performance) " into")}
           [:option {:value ""} "Keep separate / use checkbox to remove"]
           (for [other performances :when (not= (:key other) (:key performance))]
             [:option {:value (:key other) :selected (= (:key other) (get params (str "merge-" (:key performance))))}
              (str "Merge into " (service-merge/performance-label other))])]
         ]])]]]
     (for [performance performances]
       [:section.card.performance-field-card {:data-performance-fields (:key performance)}
        [:h3 (str "Choose final performance fields · " (service-merge/performance-label performance))]
        [:p.muted "Choose one value per field from this performance or the performances selected to merge into it. Unrelated songs are excluded. Song and variation can come from different rows; this uses the selected variation settings for the chosen song. Changes affecting other performances require separate approval in preview."]
        [:div.fields (for [[field label] service-merge/performance-fields]
          [:label.field [:span label]
           [:select {:name (str "pick-" (:key performance) "-" (name field)) :data-performance-field (:key performance)}
            (for [other performances]
              [:option {:value (:key other) :data-service-source (:service-key other) :selected (= (:key other) (get params (str "pick-" (:key performance) "-" (name field)) (:key performance)))}
               (str (if (= "incoming" (:service-key other)) "Import" (str "Service #" (:service-key other)))
                    " · song " (:order_in_service other) " · " (service-merge/performance-field-label other field))])]
           [:small.muted {:data-picked-value true}]])]
        [:p.muted "The selected variation name and settings are preserved. Discarded variations are removed only if unused. Shared conflicts require an explicit decision in preview."]])

     [:p.notice "Preview does not write anything. Acceptance updates the retained service, moves kept references, removes the other included service rows, and records the full merge history. Permanent image files are never deleted by this merge."]
     [:div.actions [:button "Preview service merge"] [:a.button.secondary {:href "/services/merge"} "Other possible duplicates"]]]))
(defn service-merge-page
  ([req] (service-merge-page req nil))
  ([req error]
   (let [ds (db/get-datasource) params (:params req)
         comparison? (some #(seq (get params %)) ["group" "selection" "date"])
         data (when comparison? (try (service-merge/context ds params) (catch clojure.lang.ExceptionInfo _ nil)))]
     (page "services" "Merge possible duplicate services"
       [:a.back {:href "/services"} "← Services"] [:h1 "Merge possible duplicate services"]
       (when error [:p.error {:role "alert"} error])
       (if comparison?
         (if data
           (if (< (count (service-merge/candidates data)) 2)
             [:p.notice "No other service for this saved date. The records may already have been merged. " [:a {:href "/services/merge"} "Other possible duplicates"]]
             (service-merge-form req ds data))
           [:p.notice "Save a valid service date on the import review first, then open this comparison again."])
         (list
           [:p "Compare library services sharing a date, or open Compare and merge from a Possible duplicate notice on Import services. Matching dates alone do not prove the services are duplicates."]
           [:div.table-wrap [:table [:thead [:tr [:th "Service date"] [:th "Library records"] [:th "Sermons"] [:th ""]]]
            [:tbody (if-let [groups (seq (service-merge/groups ds))]
              (for [group groups] [:tr [:td (str (:date (first group))) ] [:td (count group)]
                [:td (str/join " · " (map :sermon_title group))]
                [:td [:a.button.secondary {:href (str "/services/merge?group=" (:id (first group)))} "Compare and merge"]]])
              (list [:tr [:td {:colspan 4} "No same-date duplicates in the library."]]))]]]
           [:h2 "Saved imports matching library services"]
           [:div.actions (for [selection (sort-by #(str (:date (review/draft %))) #(compare %2 %1) (vimeo/queue))
                               :let [draft (review/draft selection)]
                               :when (and (not (:imported_service_id selection)) (seq (:date draft))
                                          (seq (sql/query ds ["select id from church_service where cast(date as varchar)=?" (:date draft)] opts)))]
                           [:a.button.secondary {:href (str "/services/merge?selection=" (:id selection))}
                            (str (:date draft) " · " (metadata/original-title selection) " · import #" (:id selection))])]))))))
(defn service-merge-confirmation [req planned]
  (let [ds (db/get-datasource) params (:params req)]
    (page "services" "Confirm service merge"
      [:h1 "Confirm service merge"]
      [:p.notice "Nothing has been changed. Review the final fields and every removed performance before accepting."]
      [:section.card [:h2 (str "Retained service: " (:target planned))]
       [:dl (for [[field label] service-merge/fields
                  :let [source (get (:field-sources planned) field)
                        row (first (filter #(= source (:key %)) (:selected planned)))]]
              (list [:dt label] [:dd (service-merge-value ds row field)]))]
       [:h3 "Service rows"] [:ul (for [row (:selected planned)]
         [:li (str (:key row) " · " (:title row) (if (= (:key row) (:target planned)) " — retained" " — references move; row deleted"))])]
       [:h3 "Kept performances"] [:ol (for [p (sort-by #(get (:orders planned) (:key %)) (:kept planned))
                  :let [source (get-in planned [:performance-plan :fields (:key p) :song])
                        chosen (or (first (filter #(= source (:key %)) (concat (:kept planned) (:removed planned)))) p)]]
         [:li (str (get (:orders planned) (:key p)) ". " (:song-title chosen) " · " (:song-author chosen) " (keep row " (:key p) ")")])]
       [:h3 "Merged performance fields"]
       (for [p (:kept planned) :let [key (:key p)
                                    members (for [[source target] (get-in planned [:performance-plan :redirects]) :when (= target key)] source)]
             :when (seq members)]
         [:section [:h4 (str "Keep " key "; merge " (str/join ", " members) " into it")]
          [:p.notice (str "Final variation name: " (get-in planned [:performance-plan :variation-names key]) ". Discarded unused variations will be deleted.")]
          [:dl (for [[field label] service-merge/performance-fields
                     :let [source (get-in planned [:performance-plan :fields key field])
                           row (first (filter #(= source (:key %)) (concat (:kept planned) (:removed planned))))]]
                 (list [:dt label] [:dd (str source " · " (service-merge/performance-field-label row field))]))]])
       [:h3 "Unused variations to remove"]
       [:ul (if (seq (get-in planned [:performance-plan :unused-variations]))
              (for [v (get-in planned [:performance-plan :unused-variations])]
                [:li (str "#" (:id v) " · " (:variation_name v) " · song #" (:song_id v))])
              (list [:li "No existing variations become unused."]))]
       [:p.muted "Any temporary import variations left unused by this merge are also removed. Existing unrelated unused variations are not swept."]
       [:h3 "Performances to remove"] [:ul (if (seq (:removed planned))
         (for [p (:removed planned)] [:li (str (:song-title p) " · " (:song-author p) " (source " (:service-key p) ")")])
         (list [:li "None"]))]]
      [:form.card {:method "post" :action "/services/merge"} (token)
       (for [[key value] params :when (and (not (#{"action" "confirm" "__anti-forgery-token" "variation-policy"} key)) (not (str/starts-with? key "variation-name-")) (not (str/starts-with? key "approve-shared-variation-"))) v (song-merge/values value)]
         [:input {:type "hidden" :name key :value v}])
       [:input {:type "hidden" :name "variation-policy" :value "selected-settings-v2"}]
       (for [decision (get-in planned [:performance-plan :variation-conflicts])
             :let [id (get-in decision [:matching :id])]]
         [:section.notice [:h3 (str "Shared variation decision · " (:song-title decision) " · " (get-in decision [:desired :variation_name]))]
          [:p "These other performances use the conflicting variation. Approving this separate decision applies your selected settings to that shared variation too."]
          [:p (str "Current settings: " (get-in decision [:matching :example_link]) " · key: " (musical-key/label (get-in decision [:matching :musical_key])) " · electric guitar: " (variation/guitar-label (get-in decision [:matching :prominent_electric_guitar])))]
          [:p (str "Selected settings: " (get-in decision [:desired :example_link]) " · key: " (musical-key/label (get-in decision [:desired :musical_key])) " · electric guitar: " (variation/guitar-label (get-in decision [:desired :prominent_electric_guitar])))]
          [:ul (for [p (:outside decision)] [:li [:a {:href (str "/performances/" (:id p)) :target "_blank" :rel "noopener noreferrer"}
                                                 (str (:service_date p) " · " (:service_sermon p) " · performance #" (:id p))]])]
          [:label.checkbox [:input {:type "checkbox" :name (str "approve-shared-variation-" id) :value "yes" :required true}]
           "I explicitly approve applying the selected variation settings to these other performances."]])
       [:label.checkbox [:input {:type "checkbox" :name "confirm" :value "yes" :required true}]
        "I reviewed the fields and performances and approve this service merge and the listed deletions."]
       [:div.actions [:button {:name "action" :value "merge"} "Accept service merge"]
        [:button.secondary {:name "action" :value "edit" :formnovalidate true} "Back to comparison"]]])))
(defn save-service-merge! [req]
  (try
    (case (get-in req [:params "action"])
      "edit" (service-merge-page req)
      "preview" (service-merge-confirmation req (service-merge/plan (db/get-datasource) (:params req)))
      "merge" (redirect (str "/services/" (service-merge/merge! (:params req)) "?notice=services-merged"))
      (throw (ex-info "Unknown service merge action." {})))
    (catch clojure.lang.ExceptionInfo e (assoc (service-merge-page req (.getMessage e)) :status 422))
    (catch java.sql.SQLException _ (assoc (service-merge-page req "The service references changed or could not be saved. Nothing was merged; reload and compare again.") :status 422))))

(defn record-merge-page
  ([req] (record-merge-page req nil))
  ([req error]
   (let [kind (get-in req [:path-params :entity]) params (:params req)
         records (record-merge/rows (db/get-datasource) kind)
         refs (record-merge/references (db/get-datasource) kind)
         selected (set (record-merge/values (get params "source")))
         title (if (= kind "people") "Merge preachers / people" "Merge sermon series")]
     (page kind title
       [:a.back {:href (str "/" kind)} "← Back to records"] [:h1 title]
       (when error [:p.error {:role "alert"} error])
       [:p "Select records representing the same person or series, choose the row to keep, and optionally correct its name. Preview every affected service before confirming. Names do not have to match."]
       (when (= kind "people") [:p.notice "Preachers and song leaders share person records. Merging a person updates both roles wherever that person is used; the preview lists those services."])
       [:form.card {:method "post" :action (str "/" kind "/merge")} (token)
        [:div.table-wrap [:table [:thead [:tr [:th "Include"] [:th "Record"] [:th "Name"] [:th "Services / roles"]]]
         [:tbody (for [row records]
           [:tr [:td [:input {:type "checkbox" :name "source" :value (:id row) :checked (selected (str (:id row))) :aria-label (str "Include " (:name row) " #" (:id row))}]]
            [:td [:a {:href (str "/" kind "/" (:id row))} (str "#" (:id row))]] [:td (:name row)]
            [:td (if (= kind "people")
                   (str (count (filter #(= (:id row) (:preacher_id %)) refs)) " as preacher; " (count (filter #(= (:id row) (:song_leader_id %)) refs)) " as song leader")
                   (str (count (filter #(= (:id row) (:sermon_series_id %)) refs)) " services"))]])]]]
        [:label.field [:span "Record to keep"] [:select {:name "keep" :required true}
          [:option {:value ""} "Choose an included record…"]
          (for [row records] [:option {:value (:id row) :selected (= (str (:id row)) (get params "keep"))} (str (:name row) " · #" (:id row))])]]
        [:label.field [:span "Final name (optional; otherwise keep the selected record’s name)"]
         [:input {:type "text" :name "name" :maxlength 500 :value (get params "name" "")}]]
        [:button {:name "action" :value "preview"} "Preview merge"]]))))

(defn record-merge-confirmation [req planned]
  (let [{:keys [kind selected kept name affected snapshot]} planned]
    (page kind "Confirm merge" [:h1 "Confirm merge"] [:p "Nothing has been changed. Review the name and affected services before accepting."]
      [:h2 (str "Keep #" kept " · " name)]
      [:p (str "Remove records: " (str/join ", " (for [row selected :when (not= kept (:id row))] (str "#" (:id row) " · " (:name row)))))]
      [:p "All service references move to the retained row. Old record links redirect to it, and previous names remain recognized during future imports."]
      [:h2 (str (count affected) " affected services")]
      [:ul (for [row affected] [:li [:a {:href (str "/services/" (:id row))} (str (:date row) " · " (:sermon_title row))]
         (when (= kind "people") (str " — " (str/join ", " (keep (fn [[field label]] (when (some #(= (get row field) (:id %)) selected) label)) [[:preacher_id "preacher"] [:song_leader_id "song leader"]]))))])]
      [:form.card {:method "post" :action (str "/" kind "/merge")} (token)
       (for [row selected] [:input {:type "hidden" :name "source" :value (:id row)}])
       [:input {:type "hidden" :name "keep" :value kept}] [:input {:type "hidden" :name "name" :value name}]
       [:input {:type "hidden" :name "snapshot" :value snapshot}]
       [:label [:input {:type "checkbox" :name "confirm" :value "yes" :required true}] " I approve this merge, the reference updates, and deletion of the other selected records."]
       [:div.actions [:button {:name "action" :value "merge"} "Accept merge"]
        [:button.secondary {:name "action" :value "edit" :formnovalidate true} "Back to comparison"]]])))

(defn save-record-merge! [req]
  (let [kind (get-in req [:path-params :entity])]
    (try
      (case (get-in req [:params "action"])
        "edit" (record-merge-page req)
        "preview" (record-merge-confirmation req (record-merge/plan (db/get-datasource) kind (:params req)))
        "merge" (redirect (str "/" kind "/" (record-merge/merge! kind (:params req))))
        (throw (ex-info "Unknown merge action." {})))
      (catch clojure.lang.ExceptionInfo e (assoc (record-merge-page req (.getMessage e)) :status 422))
      (catch java.sql.SQLException _ (assoc (record-merge-page req "The records changed or could not be merged. Nothing was saved; preview again.") :status 422)))))

(defn table-view [conn entity records columns]
  [:div.table-wrap [:table [:thead [:tr (for [[_ label] columns] [:th label]) [:th ""]]]
                    [:tbody (if (seq records)
                              (for [row records]
                                [:tr (for [[key :as field] columns] [:td (if (and (= entity "services") (= key :is_merged)) (if (:is_merged row) (recording-sources-panel conn :service (:id row)) "—") (if (= key :song-tags) (str/join ", " (map :name (song-tags/for-song conn (:id row)))) (display-value conn field (get row key))))])
                                 [:td [:a.row-link {:href (url entity (:id row))} "Open →"]]])
                              (list [:tr [:td {:colspan (inc (count columns)) :class "empty"} "No records yet."]]))]]])

(defn unused-library-page
  ([req] (unused-library-page req nil))
  ([req error]
   (let [kind (get-in req [:path-params :entity])
         available (library-cleanup/candidates (library-cleanup/context (db/get-datasource)) kind)
         eligible (remove :reason available)
         title (str "Clean up unused " kind)]
     (page kind title [:a.back {:href (str "/" kind)} (str "← " (str/capitalize kind))]
       [:h1 title]
       (when error [:p.error {:role "alert"} error])
       (when (= "cleaned" (get-in req [:params "notice"])) [:p.notice {:role "status"} "Selected unused rows deleted. No service performances were removed."])
       [:p (if (= kind "songs")
              "Songs listed here have no recorded performances across any of their variations. Deleting a song also deletes its unused variations, listed in the preview."
              "Variations listed here have no recorded performances. Deleting a variation keeps its song and all service records.")]
       [:p.muted "Saved review drafts and merge links protect rows from cleanup. Protected rows are listed for visibility and cannot be selected. References are checked again when you confirm."]
       [:h2 (str (count eligible) " eligible · " (- (count available) (count eligible)) " protected")]
       [:form {:method "post" :action (str "/" kind "/unused") :id "unused-cleanup-form"} (token)
        [:div.table-wrap [:table [:thead [:tr
          [:th [:input.video-check {:type "checkbox" :data-unused-select-all true :aria-label "Select all eligible unused rows" :disabled (empty? eligible)}]]
          [:th "Song / author"] [:th (if (= kind "songs") "Unused variations" "Variation")] [:th "Status"]]]
         [:tbody (if (seq available)
           (for [row available]
             [:tr [:td [:input.video-check {:type "checkbox" :name "selected" :value (:id row) :disabled (some? (:reason row))
                                           :aria-label (str "Select " (:name row) (when (:variation_name row) (str " · " (:variation_name row))) " #" (:id row))}]]
              [:td [:a {:href (str "/songs/" (if (= kind "songs") (:id row) (:song_id row)))} [:strong (:name row)]] [:br] (:artist row)]
              [:td (if (= kind "songs") (:variation-count row)
                        [:a {:href (str "/variations/" (:id row))} (str (:variation_name row) " · #" (:id row))])]
              [:td (or (:reason row) "Unused · eligible for deletion")]])
           [[:tr [:td.empty {:colspan 4} (str "No unused " kind " found.")]]])]]]
        [:div.actions [:button {:name "action" :value "preview" :disabled (empty? eligible)} "Preview deletion"]]]))))

(defn unused-library-confirmation [req planned]
  (let [{:keys [kind selected variations snapshot]} planned]
    (page kind "Confirm unused row deletion" [:h1 "Confirm unused row deletion"]
      [:p "Nothing has been deleted. Check every selected row below before confirming."]
      [:h2 (str (count selected) " " kind " selected")]
      [:ul (for [row selected] [:li (str "#" (:id row) " · " (:name row) " · " (:artist row)
                                                    (when (:variation_name row) (str " · " (:variation_name row))))])]
      (when (= kind "songs")
        [:section [:h2 (str (count variations) " unused variations will also be deleted")]
         [:ul (for [row variations] [:li (str "#" (:id row) " · song #" (:song_id row) " · " (:variation_name row))])]])
      [:p "No performances, services, video files, or slide images are deleted. A copy of the deleted database records is kept in cleanup history."]
      [:form.card {:method "post" :action (str "/" kind "/unused")} (token)
       (for [row selected] [:input {:type "hidden" :name "selected" :value (:id row)}])
       [:input {:type "hidden" :name "snapshot" :value snapshot}]
       [:label.checkbox [:span "I reviewed these unused rows and approve deleting them from the library."]
        [:input {:type "checkbox" :name "confirm" :value "yes" :required true}]]
       [:div.actions [:button.danger-button {:name "action" :value "delete"} "Delete selected unused rows"]
        [:a.button.secondary {:href (str "/" kind "/unused")} "Cancel"]]])))

(defn save-unused-library! [req]
  (let [kind (get-in req [:path-params :entity])]
    (try
      (case (get-in req [:params "action"])
        "preview" (unused-library-confirmation req (library-cleanup/plan (db/get-datasource) kind (:params req)))
        "delete" (do (library-cleanup/delete-unused! kind (:params req)) (redirect (str "/" kind "/unused?notice=cleaned")))
        (throw (ex-info "Unknown cleanup action." {})))
      (catch clojure.lang.ExceptionInfo e (assoc (unused-library-page req (.getMessage e)) :status 422))
      (catch java.sql.SQLException _ (assoc (unused-library-page req "A reference changed or deletion could not be completed. Nothing was deleted; reload and preview again.") :status 422)))))

(defn service-reimport-page
  ([req] (service-reimport-page req nil))
  ([req error]
   (let [services (service-reimport/candidates (db/get-datasource))
         deleted (some-> (get-in req [:params "deleted"]) parse-long)
         title "Services with three or fewer performances"]
     (page "services" title
       [:a.back {:href "/services"} "← Services"]
       [:h1 title]
       (when error [:p.error {:role "alert"} error])
       (when (and deleted (pos? deleted))
         [:p.notice {:role "status"}
          (str "Deleted " deleted (if (= 1 deleted) " service. " " services. "))
          [:a {:href "/imports"} "Go to Import services →"]])
       [:p "Select the services you want to start over. Open a service to inspect its details before selecting it."]
       (if (seq services)
         [:form {:method "post" :action "/services/reimport"} (token)
          [:div.table-wrap [:table
           [:thead [:tr [:th "Select"] [:th "Date / service"] [:th "Sermon"] [:th "Livestream"] [:th "Performances"]]]
           [:tbody
            (for [{:keys [id date title sermon_title vimeo_video_title link_vimeo performance_count]} services]
              [:tr
               [:td [:input.video-check {:type "checkbox" :name "selected" :value id
                                         :aria-label (str "Select service #" id " on " date)}]]
               [:td [:a {:href (url "services" id) :target "_blank" :rel "noopener"}
                     (str date " · " title " (#" id ")")]]
               [:td sermon_title]
               [:td (or (not-empty vimeo_video_title) (not-empty link_vimeo) "—")]
               [:td performance_count]])]]]
          [:div.actions [:button {:name "action" :value "preview"} "Preview deletion"]]]
         [:p "No services with three or fewer performances were found."])))))

(defn- service-reimport-confirmation [{:keys [rows snapshot]}]
  (page "services" "Confirm service deletion"
    [:h1 "Confirm service deletion"]
    [:p "Deleting these services also deletes their song performances and linked import drafts. Songs, variations, and archived images are kept."]
    [:p "After deletion, use Import services to download the Vimeo recordings again. For merged services, download the original parts and merge them again if needed. A recording still used by another saved service stays marked as imported."]
    [:ul
     (for [{:keys [service vimeo-urls song-names]} rows]
       [:li [:strong (str (label-for "services" service) " (#" (:id service) ")")]
        [:div (if (seq song-names) (str/join ", " song-names) "0 performances")]
        (if (seq vimeo-urls)
          [:ul (for [link vimeo-urls] [:li [:a {:href link :target "_blank" :rel "noopener"} link]])]
          [:div.muted "No Vimeo link on file. You'll need the source link to re-import this service."])])]
    [:form.card {:method "post" :action "/services/reimport"} (token)
     (for [row rows] [:input {:type "hidden" :name "selected" :value (get-in row [:service :id])}])
     [:input {:type "hidden" :name "snapshot" :value snapshot}]
     [:label.checkbox [:input {:type "checkbox" :name "confirm" :value "yes" :required true}]
      "I reviewed these services and want to delete them from the database."]
     [:div.actions [:button.danger-button {:name "action" :value "delete"} "Delete selected services"]
      [:a.button.secondary {:href "/services/reimport"} "Cancel"]]]))

(defn save-service-reimport! [req]
  (let [params (:params req)]
    (try
      (case (get params "action")
        "preview" (service-reimport-confirmation (service-reimport/preview (db/get-datasource) params))
        "delete" (redirect (str "/services/reimport?deleted=" (service-reimport/delete-selected! params)))
        (throw (ex-info "Unknown service deletion action." {})))
      (catch clojure.lang.ExceptionInfo e
        (assoc (service-reimport-page req (.getMessage e)) :status 422))
      (catch java.sql.SQLException _
        (assoc (service-reimport-page req "Deletion could not be completed. No services were deleted. Reload and preview again.") :status 422)))))

(defn prediction-page [req]
  (let [ds (db/get-datasource) available (prediction/options ds) params (:params req)]
    (try
      (let [input (prediction/parse-options available params)
            submitted (= "1" (get params "predict"))
            result (when submitted (prediction/report ds available input))]
        (page "predictions" "Song prediction"
          [:h1 "Song prediction"]
          [:p "Choose the emphasis for Sunday to rank up to 10 songs from your library. All inputs are optional; leaving them blank uses overall service history."]
          [:form.card {:method "get" :action "/predictions"}
           [:input {:type "hidden" :name "predict" :value "1"}]
           [:div.fields
            (for [[key label rows empty-label] [["leader" "Song leader" (:leaders available) "Any leader"]
                                                ["holiday" "Holiday (optional)" (:holidays available) "No holiday emphasis"]]]
              [:label.field {:for (str "prediction-" key)} [:span label]
               [:select {:id (str "prediction-" key) :name key}
                [:option {:value ""} empty-label]
                (for [row rows] [:option {:value (:id row) :selected (= (get input (keyword key)) (:id row))} (:name row)])]])
            (for [[key label] [["title" "Sermon title"] ["scripture" "Sermon scripture"]]]
              [:label.field {:for (str "prediction-" key)} [:span label]
               [:input {:id (str "prediction-" key) :name key :maxlength 1000 :value (get input (keyword key))
                        :placeholder (when (= key "scripture") "Example: John 3:16–21")}]] )]
           [:fieldset [:legend "Themes / tags (optional)"]
            [:p.muted "Select any number. Matching songs receive a boost; untagged songs remain eligible."]
            (if (seq (:tags available))
              (for [tag (:tags available)]
                [:label.checkbox [:input {:type "checkbox" :name "tags" :value (:id tag)
                                 :checked (contains? (:tag-ids input) (:id tag))}] (str " " (:name tag) " ")])
              [:p "No song tags yet. Add tags on a song’s edit page."])]
           [:fieldset [:legend "AI related-sermon matching"]
            [:label.checkbox [:input {:type "checkbox" :role "switch" :name "ai" :value "on" :checked (:ai? input)}]
             "Use AI to find related past sermons"]
            [:p.muted "Off by default. When on, Predict songs sends your sermon title/scripture and past sermon titles/scripture to OpenAI for theme matching. API usage may incur charges. Turn off and predict again for local-only results."]]
           [:div.actions [:button "Predict songs"] [:a.button.secondary {:href "/predictions"} "Reset"]]]
          (when submitted
            [:section.card [:h2 "Most likely songs"]
             [:p {:role "status"} (case (:ai-status result) :on "AI matching is on. Related sermon themes contribute to ranking."
                                                        :unavailable (str "AI unavailable: " (:ai-error result) " Showing local-only predictions.")
                                                        "AI matching is off. Local-only predictions; no API calls.")]
             [:p (str "Ranked using " (:service-count result) " recorded services. Rankings are relative, not percentage probabilities.")]
             (if (seq (:songs result))
               [:ol (for [song (:songs result)]
                      [:li [:p [:a {:href (str "/songs/" (:id song))} [:strong (:name song)]] " · " (:artist song)]
                       [:ul (for [reason (:reasons song)] [:li reason])]
                       (when-let [id (:related-sermon-id song)] [:a {:href (str "/services/" id)} "View related past service"])])]
               [:p "No songs have supporting history or matching tags yet. Record services or tag songs, then try again."])
             (when (< (count (:songs result)) 10) [:p.muted "Fewer than 10 songs have supporting evidence for this selection."])])
          [:section.card [:h2 "How predictions work"]
           [:p "Leader usage and matching tags carry the most weight, followed by holiday history, sermon title word matches, and scripture matches. Overall song usage provides a baseline. Reprises and different variations count as one song per service."]
           [:p "Scripture matching recognizes the same book and chapter, with a smaller boost for the same book. Use full book names consistently. Local title matching uses shared words. Optional AI matching uses semantic similarity to connect sermons with related themes even when their wording differs."]
           [:p "Tags and holidays boost rankings rather than filter songs out. New tagged songs can appear without performance history. Predictions have not yet been tested against past Sundays; incomplete history and tags affect results. Recent usage is included in overall history, without a separate rotation adjustment."]]))
      (catch clojure.lang.ExceptionInfo e
        (assoc (page "predictions" "Song prediction" [:h1 "Song prediction"] [:p.error (.getMessage e)]
                     [:a {:href "/predictions"} "Return to song prediction"]) :status 422)))))

(defn statistics-page [req]
  (let [ds (db/get-datasource) options (song-statistics/leaders ds)
        requested (get-in req [:params "leader"] "")
        leader (when (seq requested) (parse-long requested))]
    (try
      (when (and (seq requested) (not (and leader (pos? leader))))
        (throw (ex-info "Choose a worship leader from the list." {})))
      (let [{:keys [songs totals]} (song-statistics/report ds leader)
            selected (first (filter #(= leader (:id %)) options))]
        (page "statistics" "Song usage statistics"
          [:h1 "Song usage statistics"]
          [:p "Top 20 songs across all variations, with an optional worship-leader filter."]
          [:form.card.statistics-filter {:method "get" :action "/statistics"}
           [:label.field {:for "statistics-leader"} [:span "Worship leader"]
            [:select {:id "statistics-leader" :name "leader"}
             [:option {:value ""} "All worship leaders"]
             (for [row options] [:option {:value (:id row) :selected (= leader (:id row))} (:name row)])]]
           [:div.actions [:button "Show statistics"] [:a.button.secondary {:href "/statistics"} "Reset filters"]]]
          [:h2 (if selected (str "Top 20 · " (:name selected)) "Top 20 · all worship leaders")]
          [:p (str (:services totals) " recorded services · " (:performances totals) " performances · " (:songs totals) " distinct songs")]
          [:p.muted "Only saved services with at least one recorded song are counted; unapproved import drafts are excluded. Songs with different authors remain separate unless merged in the library."]
          [:div.table-wrap [:table [:thead [:tr [:th "Rank"] [:th "Song / author"] [:th "Services used"] [:th "Repeat services"] [:th "Performances"] [:th "Historical selection rate"] [:th "Last used"]]]
           [:tbody (if (seq songs)
             (for [[i song] (map-indexed vector songs)]
               [:tr [:td (inc i)] [:td [:a {:href (str "/songs/" (:id song))} [:strong (:name song)]] [:br] (:artist song)]
                [:td (:services song)] [:td (:repeats song)] [:td (:performances song)]
                [:td (format "%.1f%%" (double (:selection-rate song)))] [:td (str (:last_used song))]])
             [[:tr [:td.empty {:colspan 7} "No song performances recorded for this selection."]]])]]]
          [:section.card [:h2 "How to read these numbers"]
           [:p "Songs are ranked by the number of distinct services in which they were used. Ties use performance count, latest use, then song title and author. All variations of a song count together; a reprise in the same service adds a performance but does not add another service use."]
           [:p "Repeat services counts uses after the first service: five services means four repeats."]
           [:p "Historical selection rate is services using the song divided by all recorded services in the selected group. For example, 10 of 40 services is 25%. This is a baseline estimate of selection again if the past pattern continues, not a forecast or a guarantee. Rates do not add to 100% because a service includes several songs."]
           [:p.muted "The estimate does not yet account for recency, season, holidays, missions conferences, or sermon topics. Small histories and incomplete records can make it unreliable."]]))
      (catch clojure.lang.ExceptionInfo e
        (assoc (page "statistics" "Song statistics" [:h1 "Song usage statistics"] [:p.error (.getMessage e)] [:a {:href "/statistics"} "Reset filters"]) :status 422)))))

(defn list-page [req]
  (let [entity (get-in req [:path-params :entity]) config (entities entity)]
    (if-not config (assoc (page nil "Not found" [:h1 "Page not found"]) :status 404)
      (with-open [conn (jdbc/get-connection (db/get-datasource))]
        (let [q (get-in req [:params "q"] "")
              records (->> (rows conn entity)
                           (filter (fn [row]
                                     (let [text (str (str/join " " (map #(display-value conn % (get row (first %))) (:fields config)))
                                                     (when (= entity "songs")
                                                       (str " " (str/join " " (map :name (song-tags/for-song conn (:id row)))))))]
                                       (str/includes? (str/lower-case text) (str/lower-case q)))))
                           (sort-by #(if (= entity "services") (str (:date %)) (:id %))))
              records (if (= entity "services") (reverse records) records)
              columns (cond
                        (= entity "services") [[:date "Date" :date] [:vimeo_video_title "Livestream name" :text] [:is_merged "Recording sources" :boolean] [:sermon_title "Sermon" :text] [:preacher_id "Preacher" "people"] [:song_leader_id "Song leader" "people"]]
                        (= entity "songs") (conj (vec (:fields config)) [:song-tags "Tags" :text])
                        (#{"performances" "variations"} entity) (take 5 (:fields config))
                        :else (take 4 (:fields config)))]
          (page entity (:label config)
                [:div.heading [:div [:h1 (:label config)] [:p.muted (str (count records) " records in your library")]]
                 [:a.button {:href (url entity "new")} (str "+ Add " (str/lower-case (:singular config)))]]
                (when (= entity "services")
                  [:div.actions
                   [:a.button.secondary {:href "/services/merge"} "Merge possible duplicate services"]
                   [:a.button.secondary {:href "/services/reimport"} "Review services with ≤3 performances"]])
                (when (#{"people" "series"} entity) [:p [:a.button.secondary {:href (str "/" entity "/merge")} (if (= entity "people") "Merge preachers / people" "Merge sermon series")]])
                (when (= entity "songs") [:p [:a.button.secondary {:href "/songs/merge"} "Merge duplicate songs"]])
                (when (= entity "songs") [:p [:a.button.secondary {:href "/statistics"} "Song usage statistics"]])
                (when (= entity "variations") [:p [:a.button.secondary {:href "/variations/merge"} "Merge duplicate variations"]])
                (when (#{"songs" "variations"} entity) [:p [:a.button.secondary {:href (str "/" entity "/unused")} (str "Clean up unused " entity)]])
                [:form.search {:method "get"} [:input {:name "q" :placeholder (str "Search " (str/lower-case (:label config)) "…") :value q :aria-label "Search"}]
                 [:button.secondary "Search"]]
                (table-view conn entity records columns)
                (when (= entity "songs")
                  [:div {:hidden true :id "song-identity-aliases"}
                   (for [alias (sql/query conn ["select * from song_identity_alias order by id"] opts)]
                     [:span {:data-name (:original_name alias) :data-artist (:original_artist alias)
                             :data-song-path (str "/songs/" (:canonical_song_id alias))}])])))))))

(defn field-view [conn row [key label type required]]
  (let [value (get row key) attrs {:id (name key) :name (name key) :required (boolean required)}]
    [:label.field {:for (name key) :class (when (= type :boolean) "checkbox")}
     [:span label (when required [:span.required " *"])]
     (cond
       (string? type) [:select attrs [:option {:value ""} "Select…"]
                      (for [[id text] (choices conn type)] [:option {:value id :selected (= (str value) (str id))} text])]
       (= type :musical-key) [:select attrs (musical-key/options value)]
       (= type :nullable-boolean) [:select attrs (variation/guitar-options value)]
       (= type :boolean) [:input (assoc attrs :type "checkbox" :value "true" :checked (or (= value true) (= value "true")))]
       :else [:input (merge attrs {:type (name type) :value (str value)} (when (= type :number) {:min 1 :step 1}))])]))

(defn related-panel [conn entity row]
  (let [id (:id row)]
    (case entity
      "services" [:section [:div.heading [:h2 "Songs in this service"]
                            [:a.button.secondary {:href (str "/performances/new?church_service_id=" id)} "+ Add song"]]
                  (table-view conn "performances"
                    (sort-by :order_in_service (filter #(= id (:church_service_id %)) (rows conn "performances")))
                    [[:order_in_service "Order" :number] [:song_variation_id "Song variation" "variations"]
                     [:musical_key "Key" :musical-key]
                     [:time_in_service_youtube "YouTube time" :text] [:time_in_service_vimeo (if (:is_merged row) "Combined video time" "Vimeo time") :text]
                            [:song_start_time_vimeo "Song start · Vimeo (optional)" :text]])]
      "songs" [:section [:p [:a.button.secondary {:href (str "/variations/merge?song=" id)} "Merge duplicate variations for this song"]]
               [:div.heading [:h2 "Variations"] [:a.button.secondary {:href (str "/variations/new?song_id=" id)} "+ Add variation"]]
               (table-view conn "variations" (filter #(= id (:song_id %)) (rows conn "variations")) (:fields (entities "variations")))
               [:h2 "Performance history"]
               (let [variation-ids (set (map :id (filter #(= id (:song_id %)) (rows conn "variations"))))]
                 (table-view conn "performances" (filter #(variation-ids (:song_variation_id %)) (rows conn "performances"))
                             [[:church_service_id "Service" "services"] [:order_in_service "Order" :number]]))]
      nil)))

(defn archived-title-panel [entity row]
  (when-let [path (case entity "services" (:sermon_title_image_path row) "performances" (:title_image_path row) nil)]
    [:section.card [:h2 (if (= entity "services") "Archived sermon title slide" "Archived song title slide")]
     [:a {:href (str "/" entity "/" (:id row) "/title-image") :target "_blank" :rel "noopener noreferrer"}
      [:img {:src (str "/" entity "/" (:id row) "/title-image") :alt "Permanently archived title slide"
             :loading "lazy" :style "width:100%;max-width:960px;border-radius:6px"}]]]))

(defn service-reconciliation-panel [ds id]
  (let [sources (sql/query ds ["select * from service_merge_source where church_service_id=? order by id" id] opts)]
    (when (seq sources)
      [:section.card [:h2 "Reconciled service sources"]
       [:p "Service records were reconciled after review. Video files were not joined. Original links, titles, and permanent images remain preserved; performance times refer to their original recordings."]
       [:ul (for [source sources]
         [:li (str "Service #" (:original_service_id source) " · " (:original_date source) " · " (:original_title source))
          (when (seq (:vimeo_url source)) [:p [:a {:href (:vimeo_url source) :target "_blank" :rel "noopener noreferrer"} (or (:vimeo_video_title source) "Vimeo recording")]])
          (when (seq (:youtube_url source)) [:p [:a {:href (:youtube_url source) :target "_blank" :rel "noopener noreferrer"} "YouTube recording"]])
          (when (seq (:sermon_title_image_path source)) [:p [:a {:href (str "/services/" id "/merge-image/" (:id source)) :target "_blank" :rel "noopener noreferrer"} "Original sermon title slide"]])])]
       [:p.muted "The complete original service and performance rows, including removed performances and image paths, are saved in H2 service_merge_event history."]])))

(defn song-tag-fields [conn id submitted]
  (let [tags (song-tags/all-tags conn)
        selected (set (if (and submitted (:tags-present submitted))
                        (map str (song-tags/values (:tag-ids submitted)))
                        (when id (map (comp str :id) (song-tags/for-song conn id)))))]
    [:section.song-tag-fields [:h2 "Song tags"]
     [:p.muted "Choose any existing tags, or add short phrases separated by commas. Tags describe suitability; they do not change service holidays or sermon series."]
     [:input {:type "hidden" :name "tags-present" :value "yes"}]
     [:div.song-tag-choices (for [tag tags]
       [:label.checkbox [:input {:type "checkbox" :name "tag-ids" :value (:id tag) :checked (selected (str (:id tag)))}] [:span (:name tag)]])]
     [:label.field {:for "new-song-tags"} [:span "Add tags (comma separated)"]
      [:input {:id "new-song-tags" :type "text" :name "new-tags" :value (or (:new-tags submitted) "") :placeholder "Christmas, Easter, Missions"}]]]))

(defn edit-page
  ([req] (edit-page req nil nil))
  ([req error submitted]
   (let [{:keys [entity id]} (:path-params req) config (entities entity)]
     (if-not config (assoc (page nil "Not found" [:h1 "Page not found"]) :status 404)
       (with-open [conn (jdbc/get-connection (db/get-datasource))]
         (let [new? (= id "new") row (if new? {} (record conn entity (parse-long id)))
               values (or submitted (if new? (into {} (for [[k] (:fields config)] [k (get-in req [:params (name k)])])) row))]
           (if (nil? row)
             (if-let [canonical (case entity "people" (record-merge/canonical-id conn entity (parse-long id))
                                               "series" (record-merge/canonical-id conn entity (parse-long id))
                                               "songs" (song-merge/canonical-id conn (parse-long id))
                                               "variations" (variation-merge/canonical-id conn (parse-long id))
                                               "services" (service-merge/canonical-id conn (parse-long id))
                                               "performances" (service-merge/performance-canonical-id conn (parse-long id)) nil)]
               (redirect (str "/" entity "/" canonical))
               (assoc (page entity "Not found" [:h1 "Record not found"]) :status 404))
             (page entity (str (if new? "Add " "Edit ") (:singular config))
               [:a.back {:href (url entity nil)} (str "← " (:label config))]
               [:div.heading [:div [:h1 (if new? (str "Add " (str/lower-case (:singular config))) (label-for entity row))]
                              [:p.muted (if new? "Add a record to your library." "Edit details and save your changes.")]]]
               (when error [:div.error {:role "alert"} error])
               (when (= "services-merged" (get-in req [:params "notice"])) [:p.notice "Services merged. Kept performances, source links, and merge history are preserved."])
               (when (and (= entity "services") (not new?)) (service-reconciliation-panel conn (:id row)))
               (when (and (= entity "services") (not new?)) [:p [:a.button.secondary {:href (str "/services/merge?group=" (:id row))} "Compare possible duplicate services"]])
               (when (= "variations-merged" (get-in req [:params "notice"])) [:p.notice "Variations merged. All performance references now use this variation."])
               (when (and (= entity "variations") (not new?))
                 [:p [:a.button.secondary {:href (variation-merge-link (:id row))} "Compare and merge duplicate variations"]])
               (when (= "songs-merged" (get-in req [:params "notice"])) [:p.notice "Songs merged. All performance references and distinct variations are preserved."])
               (when (and (= entity "songs") (not new?))
                 [:p [:a.button.secondary {:href (song-merge-link (:id row))} "Compare and merge same-title songs"]])
               (when (= entity "variations")
                 [:p.muted "Variation names must be unique within a song, ignoring letter case and extra spaces. This rule is enforced by H2 as well as the application."])
               [:form.card {:method "post" :action (url entity id)} (token)
                [:div.fields (for [field (:fields config)] (field-view conn values field))]
                (when (= entity "songs") (song-tag-fields conn (when-not new? (parse-long id)) submitted))
                [:div.actions [:button "Save changes"] [:a.button.secondary {:href (url entity nil)} "Cancel"]]]
               (when (and (= entity "services") (:vimeo_video_title row))
                 [:p.muted (str "Original Vimeo video title: " (:vimeo_video_title row))])
               (when (and (= entity "services") (not new?))
                 (when-let [state (first (sql/query conn ["select cache_cleanup_status, cache_cleanup_message from vimeo_import_selection where imported_service_id = ? and cache_cleanup_status is not null order by id desc" (:id row)] opts))]
                   [:p {:class (if (= "cleaned" (:cache_cleanup_status state)) "muted" "notice")}
                    (:cache_cleanup_message state)]))
               (when (and (= entity "services") (not new?)) (recording-sources-panel conn :service (:id row)))
               (when (and (= entity "performances") (not new?) (:church_service_id row))
                 (recording-sources-panel conn :service (:church_service_id row)))
               (when-not new? (archived-title-panel entity row))
               (when-not new? (related-panel conn entity row))
               (when-not new? [:section.danger [:h2 "Delete record"] [:p "Records in use must be unlinked before they can be deleted."]
                               [:a.button.danger-button {:href (str (url entity id) "/delete")} "Review deletion"]])))))))))

(defn parse-data [entity params]
  (into {} (for [[key label type required] (:fields (entities entity))
                 :let [value (str/trim (get params (name key) ""))]]
             (do (when (and required (str/blank? value)) (throw (ex-info (str label " is required.") {})))
                 [key (cond
                        (= type :musical-key) (musical-key/parse-key value)
                        (= type :nullable-boolean) (variation/parse-guitar value)
                        (= type :boolean) (= value "true")
                        (str/blank? value) (if (and (= entity "songs") (= key :artist)) "" nil)
                        (= type :date) (try (java.time.LocalDate/parse value) (catch Exception _ (throw (ex-info "Enter a valid date." {}))))
                        (or (= type :number) (string? type)) (let [n (parse-long value)]
                            (when-not (and n (pos? n)) (throw (ex-info (str label " must be a positive number.") {}))) n)
                        (= type :url) (do (when-not (re-matches #"https?://[^\s]+" value)
                                          (throw (ex-info (str label " must be an HTTP or HTTPS URL.") {}))) value)
                        :else value)]))))

(defn save! [req]
  (let [{:keys [entity id]} (:path-params req)]
    (if-not (entities entity) {:status 404 :body "Not found"}
      (try
        (let [data (parse-data entity (:params req))
              saved-id (jdbc/with-transaction [tx (db/get-datasource)]
                         (when (= entity "songs") (sql/query tx ["select id from import_approval_lock where id=1 for update"]))
                         (when (and (not= id "new") (nil? (record tx entity (parse-long id))))
                           (throw (ex-info "Record no longer exists." {})))
                         (when (= entity "variations")
                           (variation/validate! tx (when-not (= id "new") (parse-long id)) data))
                         (when (= entity "performances")
                           (when (some #(not= (:id %) (parse-long id))
                                   (sql/find-by-keys tx :song_performance (select-keys data [:church_service_id :order_in_service]) opts))
                             (throw (ex-info "That position is occupied. Choose a free song order first." {}))))
                         (let [saved (if (= id "new") (:id (sql/insert! tx (:table (entities entity)) data opts))
                                       (do (sql/update! tx (:table (entities entity)) data {:id (parse-long id)}) (parse-long id)))]
                           (when (and (= entity "songs") (= "yes" (get-in req [:params "tags-present"])))
                             (song-tags/save-assignments! tx saved (:params req)))
                           saved))]
          (redirect (url entity saved-id)))
        (catch clojure.lang.ExceptionInfo e
          (assoc (edit-page req (.getMessage e) (into {} (map (fn [[k v]] [(keyword k) v]) (:params req)))) :status 422))
        (catch java.sql.SQLException e
          (if (str/starts-with? (or (.getSQLState e) "") "23")
            (assoc (edit-page req
                     (if (and (= entity "variations") (str/includes? (str/upper-case (or (.getMessage e) "")) "SONG_VARIATION_SONG_NAME_UNIQUE"))
                       "That variation name already exists for this song. Choose a different name. Names ignore letter case and extra spaces."
                       "A related record is missing or this change conflicts with existing data.")
                     (into {} (map (fn [[k v]] [(keyword k) v]) (:params req)))) :status 422)
            (throw e)))))))

(defn delete-page [req]
  (let [{:keys [entity id]} (:path-params req)]
    (if-not (entities entity) {:status 404 :body "Not found"}
      (with-open [conn (jdbc/get-connection (db/get-datasource))]
        (if-let [row (record conn entity (parse-long id))]
          (page entity "Confirm deletion" [:h1 "Delete this record?"] [:p (label-for entity row)]
                [:form.card {:method "post" :action (str (url entity id) "/delete")} (token)
                 [:p "This cannot be undone. Records referenced elsewhere cannot be deleted."]
                 [:div.actions [:button.danger-button "Delete permanently"] [:a.button.secondary {:href (url entity id)} "Cancel"]]])
          {:status 404 :body "Record not found"})))))

(defn delete! [req]
  (let [{:keys [entity id]} (:path-params req)]
    (if-not (entities entity) {:status 404 :body "Not found"}
      (try (sql/delete! (db/get-datasource) (:table (entities entity)) {:id (parse-long id)})
           (redirect (url entity nil))
           (catch java.sql.SQLException e
             (if (str/starts-with? (or (.getSQLState e) "") "23")
               (assoc (edit-page req "This record is still in use. Remove or reassign its related records first." nil) :status 409)
               (throw e)))))))

(defn import-selection-row [item video-titles title-groups]
  (let [downloaded (jobs/downloaded-file (:id item))]
    [:tr {:data-job-status (:job_status item)} [:td [:a {:href (:vimeo_url item) :title (:vimeo_url item) :target "_blank" :rel "noopener noreferrer"}
                                  (or (:vimeo_video_title item) (get video-titles (:vimeo_url item)) (:vimeo_url item))]
                                  (when-let [duration (if (:is_merged item) (when downloaded (video-range/duration (:id item)))
                                                        (:duration (first (filter #(= (:vimeo_url item) (:url %)) (vimeo/cached-catalog)))))]
                                    [:p.muted (str "Recording length · " (video-range/timestamp duration))])
                                  (when (> (count (get title-groups (vimeo/title-key (get video-titles (:vimeo_url item))))) 1)
                                    [:p.notice "Duplicate title: multiple Vimeo recordings share this title. "
                                     "Choose downloaded parts using Merge with another service below."])
                                  (when (:is_merged item) (recording-sources-panel (db/get-datasource) :import (:id item)))]
                       [:td (or (:job_message item) (if (:draft_data item) "Draft · needs your review" "Ready to start"))
                        (when (and (= "failed" (:job_status item))
                                   (re-find #"(?i)401|403|login|sign in|session|public access" (or (:job_message item) "")))
                          [:p [:a {:href "#vimeo-session" :onclick "document.getElementById('vimeo-session').open=true"} "Set up or refresh Vimeo session"]])]
                       [:td (if (and (:draft_data item) (not (:range_needs_ocr item)))
                              [:a.button.secondary {:href (str "/imports/" (:id item))} "Review extracted data"]
                              [:form {:method "post" :action "/imports"} (token)
                               [:input {:type "hidden" :name "selection" :value (:id item)}]
                               [:button {:name "action" :value (if (jobs/active-statuses (:job_status item)) "cancel-import" "start-import")}
                                (cond (jobs/active-statuses (:job_status item)) "Cancel processing"
                                      (= "failed" (:job_status item)) "Retry import"
                                      (and (:range_needs_ocr item) downloaded) "OCR selected range"
                                      downloaded "Continue import"
                                      :else "Start import")]])
                        (when (and (:draft_data item) (:range_needs_ocr item))
                          [:a.button.secondary {:href (str "/imports/" (:id item))} "Review saved draft"])
                        (when (and downloaded (not (:is_merged item)) (not (:imported_service_id item))
                                   (not (:merged_into_selection_id item)) (not (jobs/active-statuses (:job_status item))))
                          [:a.button.secondary {:href (merge-link (:vimeo_url item))} "Merge with another service"])
                        (when (and downloaded (not (jobs/active-statuses (:job_status item))))
                          [:a.button.secondary {:href (str "/imports/" (:id item) "/trim")} "Choose OCR range"])
                        (when-not (jobs/active-statuses (:job_status item))
                          (if-let [file (jobs/downloaded-file (:id item))]
                            [:a.button.secondary {:href (str "/imports/" (:id item) "/video")} "Save video file"]
                            [:form {:method "post" :action "/imports"} (token)
                             [:input {:type "hidden" :name "selection" :value (:id item)}]
                             [:button.secondary {:name "action" :value "download-video"} "Download video"]]))
                        (when (and (:is_merged item) (not (:imported_service_id item))
                                   (not (jobs/active-statuses (:job_status item))))
                          [:details [:summary "Undo merged recording"]
                           [:p "Restore the original recordings as separate imports. Review work on the combined video will be discarded. The combined video is kept in a backup cache folder."]
                           [:form {:method "post" :action "/imports"} (token)
                            [:input {:type "hidden" :name "action" :value "undo-merge"}]
                            [:input {:type "hidden" :name "selection" :value (:id item)}]
                            [:input {:type "hidden" :name "revision" :value (:revision item)}]
                            [:label.checkbox [:input {:type "checkbox" :name "confirm-undo" :value "yes" :required true}]
                             "Discard combined review work and restore separate recordings"]
                            [:button.secondary "Undo merged recording"]]])
                        (when (and (:is_merged item) (= "failed" (:job_status item)) (not downloaded))
                          [:form {:method "post" :action "/imports"} (token)
                           [:input {:type "hidden" :name "selection" :value (:id item)}]
                           [:button.secondary {:name "action" :value "retry-merge-converted"} "Retry merge with conversion"]])
                        (when-not (:is_merged item) [:form {:method "post" :action "/imports"} (token)
                             [:input {:type "hidden" :name "action" :value "remove"}]
                             [:input {:type "hidden" :name "selection" :value (:id item)}]
                             [:button.secondary "Remove"]])]]))

(defn available-download-row [{:keys [url title duration]} title-groups page-number]
  [:tr {:data-job-status "available"}
   [:td [:a {:href url :target "_blank" :rel "noopener noreferrer" :title url} (or title url)]
    (when (number? duration) [:p.muted (str "Recording length · " (video-range/timestamp duration))])
    (when (> (count (get title-groups (vimeo/title-key title))) 1)
      [:p.notice "Duplicate title: multiple Vimeo recordings share this title. Download the parts to merge them."])]
   [:td (if (= 0 duration) "No recording reported" "Available to download")]
   [:td [:form {:method "post" :action "/imports"} (token)
         [:input {:type "hidden" :name "action" :value "download-available"}]
         [:input {:type "hidden" :name "video" :value url}]
         [:input {:type "hidden" :name "page" :value page-number}]
         [:button {:disabled (= 0 duration)} "Download"]]
         [:form {:method "post" :action "/imports"} (token)
          [:input {:type "hidden" :name "action" :value "discard-available"}]
          [:input {:type "hidden" :name "video" :value url}]
          [:input {:type "hidden" :name "page" :value page-number}]
          [:button.secondary "Discard"]]]])

(defn imports-page
  ([req] (imports-page req nil))
  ([req error]
   (let [browse? (not= "true" (get-in req [:headers "x-import-progress"]))
         _ (when (and browse? (= "1" (get-in req [:params "browse"]))) (vimeo/clear-discovery-cache!))
         discovery (when browse? (try {:videos (vimeo/discover)}
                                      (catch Exception _ {:error "Vimeo uploads could not be loaded. You can still paste video links below."})))
         catalog (or (:videos discovery) (vimeo/cached-catalog))
         video-info (into {} (map (juxt :url identity) catalog))
         video-titles (into {} (map (juxt :url :title) catalog))
         title-groups (group-by #(vimeo/title-key (:title %)) catalog)
         imported-urls (vimeo/imported-video-urls (vimeo/imported-recordings (db/get-datasource)))
         all-selected (vimeo/queue)
         selected (vec (remove #(or (:merged_into_selection_id %) (:imported_service_id %) (contains? imported-urls (:vimeo_url %))) all-selected))
         selected-urls (set (map :vimeo_url all-selected))
         discarded (vimeo/discarded-services)
         discarded-urls (set (map :vimeo_url discarded))
         available (vimeo/newest-videos
                     (filter #(and (str/includes? (str/lower-case (or (:title %) "")) "livestream")
                                   (not (contains? imported-urls (:url %)))
                                   (not (contains? selected-urls (:url %)))
                                   (not (contains? discarded-urls (:url %)))) catalog))
         total (count available)
         page-count (max 1 (long (Math/ceil (/ total 25.0))))
         page-number (min page-count (max 1 (or (some-> (get-in req [:params "page"]) parse-long) 1)))
         available-page (take 25 (drop (* 25 (dec page-number)) available))
         ready (vimeo/newest-videos
                 (concat (map #(assoc % :available-video %) available-page)
                         (for [item selected :when (not (jobs/active-statuses (:job_status item)))]
                           (assoc (or (get video-info (:vimeo_url item)) {:url (:vimeo_url item)}) :selection item))))
         processing (vimeo/newest-videos
                      (for [item selected :when (jobs/active-statuses (:job_status item))]
                        (assoc (or (get video-info (:vimeo_url item)) {:url (:vimeo_url item)}) :selection item)))
         catalog-status (vimeo/discovery-status)]
     (page "imports" "Import services"
       [:p [:a.button.secondary {:href "/services/merge"} "Merge possible duplicate services"]]
       [:div.heading [:div [:h1 "Import services"] [:p.muted "Download a service, choose its OCR range, then review and approve the extracted details."]]
        [:a.button.secondary {:href vimeo/church-url :target "_blank" :rel "noopener noreferrer"} "Open Vimeo ↗"]]
       (when error [:div.error {:role "alert"} error])
       (when (= "ocr-discarded" (get-in req [:params "notice"]))
         [:p.notice {:role "status"} "OCR range and review results discarded. Choose OCR range for this service below. The download and OCR cache were kept."])
       (when (= "merge-undone" (get-in req [:params "notice"]))
         [:p.notice {:role "status"} "Merge undone. The original recordings are available separately below."])
       (when (= "merge-queued" (get-in req [:params "notice"]))
         [:p.notice "Merged recording queued. When it is ready, choose its OCR range and continue import as one service."])
       (when (= "downloads-queued" (get-in req [:params "notice"]))
         [:p.notice {:role "status"} "Downloads queued. Up to three videos download at once."])
       [:details {:id "vimeo-session" :open (when (= "vimeo-session-saved" (get-in req [:params "notice"])) true)}
        [:summary (str "Vimeo download session · " (if (vimeo-session/connected?) "saved" "not connected"))]
        [:p "If Vimeo blocks a public download, open Vimeo in Chrome or Firefox and sign in. Then save that browser’s session here and retry the download. No password is entered in this app."]
        [:p [:a.button.secondary {:href "https://vimeo.com/log_in" :target "_blank" :rel "noopener noreferrer"} "Open Vimeo sign in ↗"]]
        [:p.muted "Saving reads the selected browser’s cookies and stores only Vimeo cookies in a private file on this Mac, outside Dropbox and the video cache. Downloads try public access first. Session cookies can expire; save again when needed."]
        [:form {:method "post" :action "/imports"} (token)
         [:input {:type "hidden" :name "action" :value "connect-vimeo-session"}]
         [:label.field [:span "Browser you signed in with"]
          [:select {:name "browser"} [:option {:value "chrome"} "Chrome · default profile"] [:option {:value "firefox"} "Firefox · most recently used profile"]]]
         [:button "Save Vimeo session"]]
        (when (vimeo-session/connected?)
          [:form {:method "post" :action "/imports"} (token)
           [:input {:type "hidden" :name "action" :value "disconnect-vimeo-session"}]
           [:button.secondary "Disconnect Vimeo session"]])]
       [:section {:id "import-queue" :data-processing (str (boolean (seq processing)))}
        [:p.muted "Up to three videos download at once. Finished downloads can continue through local OCR and review while the others download."]
        [:section {:data-queue-group "ready"}
         [:div.heading [:h2 (str "Ready for your next action · " (count ready))]
          [:a.button.secondary {:href "/imports?browse=1"} "Refresh Vimeo uploads"]]
         [:p.muted (str total " available services · page " page-number " of " page-count ". Selected services needing action are also shown on every page.")]
         (when (:refreshing? catalog-status)
           [:p.notice {:id "catalog-refreshing"} "Refreshing the Vimeo archive in the background. The previous catalog remains available. " [:a {:href "/imports"} "Check for updated results"]])
         (when (:error catalog-status) [:p.error (:error catalog-status)])
         (when (:error discovery) [:div.error {:role "alert"} (:error discovery)])
         [:div.actions
          (when (> page-number 1) [:a.button.secondary {:href (str "/imports?page=" (dec page-number))} "← Newer services"])
          (when (< page-number page-count) [:a.button.secondary {:href (str "/imports?page=" (inc page-number))} "Older services →"])]
         [:div.table-wrap [:table [:thead [:tr [:th "Service video"] [:th "Status"] [:th "Next action"]]]
          [:tbody (if (seq ready)
                    (for [video ready :let [item (:selection video)]]
                      (if (and item (or (= "failed" (:job_status item)) (:draft_data item) (jobs/downloaded-file (:id item))))
                        (import-selection-row item video-titles title-groups)
                        (available-download-row video title-groups page-number)))
                    (list [:tr [:td.empty {:colspan 3} "No services need your action. No unimported Livestream videos were found in the cached catalog."]]))]]]
         [:details [:summary "Download a service by Vimeo link"]
          [:form.card {:method "post" :action "/imports"} (token)
           [:input {:type "hidden" :name "action" :value "select"}]
           [:label.field {:for "links"} [:span "Vimeo video links · one per line"]
            [:textarea {:id "links" :name "links" :rows 4} (get-in req [:params "links"] "")]]
           [:button "Download pasted videos"]]]]
        [:section {:data-queue-group "processing"}
         [:h2 (str "Queued or processing · " (count processing))]
         [:div.table-wrap [:table [:thead [:tr [:th "Service video"] [:th "Status"] [:th "Actions"]]]
          [:tbody (if (seq processing)
                    (for [video processing] (import-selection-row (:selection video) video-titles title-groups))
                    (list [:tr [:td.empty {:colspan 3} "No services are queued or processing right now."]]))]]]]
        [:details {:id "discarded-services"} [:summary (str "Discarded services · " (count discarded))]
         [:p.muted "These videos are saved in the database and hidden from available downloads. Restore a video to make it available again."]
         [:div.table-wrap [:table [:thead [:tr [:th "Service video"] [:th "Discard reason"] [:th "Discarded"] [:th "Action"]]]
          [:tbody (if (seq discarded)
                    (for [item (vimeo/newest-videos (map #(assoc % :url (:vimeo_url %) :title (:video_title %)) discarded))]
                      [:tr [:td [:a {:href (:vimeo_url item) :target "_blank" :rel "noopener noreferrer"} (:video_title item)]
                            (when (:duration_seconds item) [:p.muted (str "Recording length · " (video-range/timestamp (:duration_seconds item)))])]
                       [:td (:discard_reason item)]
                       [:td (str (:discarded_at item))]
                       [:td [:form {:method "post" :action "/imports"} (token)
                             [:input {:type "hidden" :name "action" :value "restore-discarded"}]
                             [:input {:type "hidden" :name "video" :value (:vimeo_url item)}]
                             [:input {:type "hidden" :name "page" :value page-number}]
                             [:button.secondary "Restore"]]]])
                    (list [:tr [:td.empty {:colspan 4} "No discarded services."]]))]]]]]))))

(defn save-import-selection! [req]
  (try
    (case (get-in req [:params "action"])
      "undo-merge" (do (video-merge/undo! (or (some-> (get-in req [:params "selection"]) parse-long) 0)
                                          (some-> (get-in req [:params "revision"]) parse-long)
                                          (= "yes" (get-in req [:params "confirm-undo"])))
                       (redirect "/imports?notice=merge-undone"))
      "retry-merge-converted" (do (video-merge/retry-with-conversion! (or (some-> (get-in req [:params "selection"]) parse-long) 0)) (redirect "/imports"))
      "connect-vimeo-session" (do (vimeo-session/connect! (get-in req [:params "browser"])) (redirect "/imports?notice=vimeo-session-saved#vimeo-session"))
      "disconnect-vimeo-session" (do (vimeo-session/disconnect!) (redirect "/imports#vimeo-session"))
      "backfill-video-titles" {:status 200 :headers {"Content-Type" "application/json; charset=utf-8"}
                               :body (json/write-str (metadata/backfill! {}))}
      "backfill-slide-images" {:status 200 :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
                                :body (json/write-str (archive/backfill! {}))}
      "discard-available" (do (vimeo/discard-service! (get-in req [:params "video"]) (get-in req [:params "discard-reason"]))
                               (redirect (str "/imports?page=" (max 1 (or (some-> (get-in req [:params "page"]) parse-long) 1)))))
      "restore-discarded" (do (vimeo/restore-service! (get-in req [:params "video"]))
                               (redirect (str "/imports?page=" (max 1 (or (some-> (get-in req [:params "page"]) parse-long) 1)))))
      "download-available" (let [urls (vimeo/selected-urls (:params req))
                                 page-number (max 1 (or (some-> (get-in req [:params "page"]) parse-long) 1))]
                             (vimeo/select! (:params req))
                             (jobs/download-selected! urls)
                             (redirect (if-let [url (get-in req [:params "return-merge"])]
                                         (merge-link (vimeo/video-url url))
                                         (str "/imports?page=" page-number "&notice=downloads-queued"))))
      "select" (do (vimeo/select! (:params req))
                   (jobs/download-selected! (vimeo/selected-urls (:params req)))
                   (redirect "/imports?notice=downloads-queued"))
      "start-import" (do (jobs/start! (or (some-> (get-in req [:params "selection"]) parse-long)
                                         (throw (ex-info "Invalid selection." {}))))
                         (redirect "/imports"))
      "local-ocr-only" (do (jobs/start! (or (some-> (get-in req [:params "selection"]) parse-long)
                                             (throw (ex-info "Invalid selection." {}))) false true)
                           (redirect "/imports"))
      "download-pending" (do (jobs/download-pending!) (redirect "/imports"))
      "download-video" (do (jobs/start! (or (some-> (get-in req [:params "selection"]) parse-long)
                                             (throw (ex-info "Invalid selection." {}))) true)
                           (redirect "/imports"))
      "cancel-import" (do (jobs/cancel! (some-> (get-in req [:params "selection"]) parse-long)) (redirect "/imports"))
      "remove" (do (let [id (get-in req [:params "selection"])]
                     (jobs/cancel! (some-> id parse-long)) (vimeo/remove! id)) (redirect "/imports"))
      (throw (ex-info "Unknown selection action." {})))
    (catch clojure.lang.ExceptionInfo e
      (assoc (imports-page req (.getMessage e)) :status 422))))

(defn compact-ocr-evidence [frames]
  (:records
    (reduce
      (fn [{:keys [records positions]} frame]
        (let [signature (->> (re-seq #"[\p{L}\p{N}_]+" (str/lower-case
                                      (java.text.Normalizer/normalize (or (:ocr frame) "") java.text.Normalizer$Form/NFKC)))
                             (str/join " "))
              key (if (and (= "ocr_candidate" (:kind frame)) (seq signature)) [:ocr signature] [:frame (:index frame)])
              appearances (or (seq (:occurrences frame))
                              [{:index (:index frame) :first_seen_seconds (or (:first_seen_seconds frame) (* 3 (:index frame)))
                                :last_seen_seconds (or (:last_seen_seconds frame) (* 3 (:index frame)))}])]
          (if-let [position (get positions key)]
            {:positions positions
             :records (update-in records [position :occurrences]
                        (fn [prior] (vec (vals (into (sorted-map) (map (juxt :index identity) (concat prior appearances)))))))}
            {:positions (assoc positions key (count records))
             :records (conj records (assoc frame :occurrences (vec appearances)))})))
      {:records [] :positions {}} frames)))

(defn source-slide-panel [id revision field-name selected-index frames video-url]
  (let [frame (first (filter #(= selected-index (:index %)) frames))]
    [:div.source-preview
     [:label.field [:span "Source slide"]
      [:select {:name field-name :data-evidence-select "true" :data-service-id id :data-revision revision}
       [:option {:value ""} "Choose a source slide…"]
       (for [source (sort-by :index frames)
             :let [text (str/replace (or (:ocr source) "") #"\s+" " ")]]
         [:option {:value (:index source) :selected (= selected-index (:index source))}
          (str (video-range/timestamp (* 3 (:index source))) " · " (subs text 0 (min 85 (count text))))])]]
     [:a.source-video-link {:href (str video-url "#t=" (* 3 (or selected-index 0)) "s") :target "_blank" :rel "noopener noreferrer"}
      [:img.source-slide-image {:src (when frame (str "/imports/" id "/frame/" (:index frame) "?revision=" revision))
                                :hidden (nil? frame) :loading "lazy" :alt "Source slide for the adjacent proposed data"}]]
     [:p.muted.source-slide-caption (if frame (str "Source time · " (video-range/timestamp (* 3 (:index frame)))) "Select a slide to check the adjacent fields.")]
     [:details [:summary "Show original OCR text"] [:pre.source-ocr-text (or (:ocr frame) "No source selected.")]]]))

(defn review-song-tags [i song tags]
  (let [selected (set (map str (:tag-ids song))) id (str "song-" i "-new-tags")]
    [:div.song-tag-fields [:h3 "Tags to add"]
     [:p.muted "Tags are added when you approve the saved service. They apply to the existing or new song chosen above; existing tags are kept. Unchecking here only cancels a pending addition."]
     [:div.song-tag-choices (for [tag tags]
       [:label.checkbox [:input {:type "checkbox" :name (str "song-" i "-tag-ids") :value (:id tag) :checked (selected (str (:id tag)))}]
        [:span (:name tag)]])]
     [:label.field {:for id} [:span "Add song tags (comma separated)"]
      [:input {:id id :type "text" :name id :value (or (:new-tags song) "") :placeholder "Christmas, Easter, Missions"}]]]))

(defn review-page
  ([req] (review-page req nil nil))
  ([req error submitted]
   (let [id (some-> (get-in req [:path-params :id]) parse-long)
         [selection cache-error]
         (when id
           (if error
             [(review/selection id)]
             (try [(jobs/recheck-cache! id)]
                  (catch clojure.lang.ExceptionInfo e
                    (if (= :cache-unreadable (:kind (ex-data e)))
                      [(review/selection id) (.getMessage e)]
                      (throw e))))))]
     (if-not selection
       {:status 404 :body "Selection not found"}
       (let [data (or submitted (review/draft selection))
             extraction (when (:extracted_data selection) (edn/read-string (:extracted_data selection)))
             source-frames (vec (:evidence extraction))
             songs (review/order-songs (:songs data))
             songs (if (seq songs) songs [{}])]
         (page "imports" "Review service"
           [:a.back {:href "/imports"} "← Import services"]
           [:div.heading [:div [:h1 "Review service before importing"]
                          [:p.muted "Check the saved service details and every song. Nothing enters the library until you approve."]]]
           [:p [:a {:href (:vimeo_url selection) :target "_blank" :rel "noopener noreferrer"} (if (:is_merged selection) "Watch first original recording ↗" "Watch source video ↗")]]
           (when (:is_merged selection) (recording-sources-panel (db/get-datasource) :import id))
           (when-let [title (metadata/original-title selection)] [:p.muted (str "Original Vimeo video title: " title)])
           (when (and (not (:imported_service_id selection)) (seq (:date data))
                      (seq (sql/query (db/get-datasource) ["select id from church_service where cast(date as varchar)=?" (:date data)] opts)))
             [:p.notice "Possible duplicate service in the library. Save your draft first. "
              [:a.button.secondary {:href (str "/services/merge?selection=" id)} "Compare and merge service"]])
           (when error [:div.error {:role "alert"} error])
           (when cache-error [:div.error {:role "alert"} cache-error])
           (when (= "suggestions" (get-in req [:params "notice"])) [:p.notice {:role "status"} "Suggestions prepared from saved OCR. Review the fields beside each source slide before approving."])
           (when (= "saved" (get-in req [:params "notice"])) [:p.notice {:role "status"} "Draft saved. Review your changes before approving."])
           (if (:imported_service_id selection)
             [:p.notice "This service has been imported. " [:a {:href (str "/services/" (:imported_service_id selection))} "Open service →"]]
             (list
               [:div.review-save-bar {:role "region" :aria-label "Draft actions"}
                [:span "Review draft"]
                [:button {:type "submit" :form "review-draft" :name "action" :value "save-draft"} "Save draft"]]
               (when-not (:extracted_data selection)
                 [:p.notice "No video extraction is available yet. Use Start import from the import list to download and analyze this video."])
               (when (#{jobs/missing-cache-message jobs/missing-evidence-message jobs/missing-merge-message}
                       (:job_message selection))
                 [:p.notice (:job_message selection) " "
                  [:a {:href "/imports"} "Open Import services →"]])
               (when (and (:range_needs_ocr selection) (jobs/downloaded-file id))
                 [:p.notice "Run local OCR for the selected range before approving. Your field edits are preserved. "
                  [:a {:href (str "/imports/" id "/trim")} "Choose range and run OCR →"]])
               (when (:analysis-notes data) [:p.notice (:analysis-notes data)])
               (when (or (:extracted_data selection) (:range_needs_ocr selection))
                 [:section.card [:h2 "Start over with a different OCR range"]
                  [:p "Discard the range, extracted slides, and song / sermon review fields. Service date, title, leader, and preacher are kept. The downloaded video and reusable OCR cache are kept."]
                  [:form {:method "post" :action (str "/imports/" id)} (token)
                   [:input {:type "hidden" :name "revision" :value (:revision selection)}]
                   [:button.secondary {:name "action" :value "discard-ocr"} "Discard OCR and choose range again"]]])
               (when (:extracted_data selection)
                 [:form.card {:method "post" :action (str "/imports/" id)} (token)
                  [:input {:type "hidden" :name "revision" :value (:revision selection)}]
                  [:p "Find song credits and a possible sermon title screen in the saved OCR. Existing field edits will be kept."]
                  [:button.secondary {:name "action" :value "suggest-details"} "Find song and sermon details"]])
               (when (:extracted_data selection)
                 (let [extraction (edn/read-string (:extracted_data selection))
                       evidence (compact-ocr-evidence (filter #(#{"song_title" "sermon_title" "person_caption" "ocr_candidate"} (:kind %)) (:evidence extraction)))]
                   (when (seq evidence)
                     [:details.card [:summary "View recognized slides and OCR evidence"]
                      [:div.slide-evidence
                       (for [frame evidence]
                         [:figure [:a {:href (str (:vimeo_url selection) "#t=" (* 3 (:index frame)) "s")
                                       :target "_blank" :rel "noopener noreferrer"}
                                   [:img {:src (str "/imports/" id "/frame/" (:index frame) "?revision=" (:revision selection))
                                          :loading "lazy" :alt (str "Recognized slide at " (* 3 (:index frame)) " seconds")}]]
                          [:figcaption [:strong (str (* 3 (:index frame)) " seconds · " (:kind frame))]
                           (when (> (count (:occurrences frame)) 1)
                             [:details [:summary (str (count (:occurrences frame)) " appearances · show timestamps")]
                              [:ul (for [appearance (:occurrences frame)]
                                     [:li [:a {:href (str (:vimeo_url selection) "#t=" (:first_seen_seconds appearance) "s")
                                               :target "_blank" :rel "noopener noreferrer"}
                                           (str (:first_seen_seconds appearance) " seconds")]])]])
                           [:pre (:ocr frame)] (when (seq (:uncertainty frame)) [:p (:uncertainty frame)])]])]])))
               [:div {:hidden true :id "review-variation-library"}
                (for [v (sql/query (db/get-datasource) ["select * from song_variation order by variation_name, id"] opts)]
                  [:span {:data-variation-id (:id v) :data-song-id (:song_id v) :data-name (:variation_name v)
                          :data-musical-key (musical-key/label (:musical_key v)) :data-example (or (:example_link v) "") :data-guitar (variation/guitar-value (:prominent_electric_guitar v))}])]
               [:form.card {:id "review-draft" :method "post" :action (str "/imports/" id)} (token)
                [:input {:type "hidden" :name "revision" :value (:revision selection)}]
                [:input {:type "hidden" :name "song-count" :value (count songs)}]
                [:h2 "Service details"]
                [:div.fields (for [field (take 5 review/service-fields)] (field-view nil data field))]
                [:h2 "Sermon details to import"]
                [:div.review-source-pair {:data-source-video (:vimeo_url selection)}
                 (source-slide-panel id (:revision selection) "sermon-evidence-index" (:sermon-evidence-index data) source-frames (:vimeo_url selection))
                 [:div [:p.muted "Check the sermon title, series, and slide time against this source. Verify other service fields separately."]
                  (when (:sermon-suggestion-notes data) [:p.notice (:sermon-suggestion-notes data)])
                  (when (:sermon-reference data) [:p "Scripture read from slide: " (:sermon-reference data)])
                  [:div.fields (for [field (drop 5 review/service-fields)] (field-view nil data field))]]]
                [:h2 "Songs in service order"]
                [:p.muted "Order is calculated from title / author slide time, using song start time when the slide time is missing, and saved when you approve. Equal times retain their displayed sequence. Save the draft to refresh the sequence. Use m:ss or h:mm:ss for timestamps. Leave unused song rows completely blank. Remove an extra performance with Remove performance; this also saves your other edits. Titles and authors are required. Prefer the title / author slide time; if unavailable, provide a song start time instead. Song start time is otherwise optional."]
                (for [[i song] (map-indexed vector songs)]
                  [:section.song-review {:id (str "song-review-" i)} [:h2 (str "Song " (or (:order song) (inc i)) " · derived order")]
                   [:button.secondary {:name "action" :value (str "remove-song-" i)} "Remove performance"]
                   [:div.review-source-pair {:data-source-video (:vimeo_url selection)}
                    (source-slide-panel id (:revision selection) (str "song-" i "-evidence-index") (:evidence-index song) source-frames (:vimeo_url selection))
                    [:div [:p.muted "These are the song fields that will be imported. Verify author spelling and the title / author slide time. Use song start time only as a fallback when the credit slide time is unavailable."]
                     [:label.field [:span "Song to import"]
                      [:select {:name (str "song-" i "-choice") :data-song-choice (or (:song-choice song) "auto")}
                       [:option {:value "auto" :selected (= "auto" (or (:song-choice song) "auto"))} "Match title and author automatically"]
                       [:option {:value "new" :selected (= "new" (:song-choice song))} "Create a new song"]
                       (when-let [existing-id (parse-long (or (:song-choice song) ""))]
                         (when-let [existing (sql/get-by-id (db/get-datasource) :song existing-id vimeo/opts)]
                           [:option {:value (str existing-id) :selected true}
                            (str "Use existing: " (:name existing) " · " (:artist existing))]))]]
                     [:label.field {:for (str "song-" i "-variation-id")} [:span "Variation to use"]
                      [:select {:id (str "song-" i "-variation-id") :name (str "song-" i "-variation-id") :data-review-variation true}
                       [:option {:value "auto" :selected (or (nil? (:variation-id song)) (= "auto" (str (:variation-id song))))} "Use Default (existing or create)"]
                       (when-let [id (some-> (:variation-id song) str parse-long)]
                         (when-let [v (sql/get-by-id (db/get-datasource) :song_variation id opts)]
                           [:option {:value id :selected true} (:variation_name v)]))]]
                     [:p.muted {:data-review-variation-details true} "Choose a variation belonging to the song above. New songs start with Default. Existing variation settings are preserved."]
                     (let [expanded (or (seq (:variation-name song)) (seq (:variation-example-link song)) (some? (:variation-guitar song)) (some? (:variation-key song)))
                           panel-id (str "song-" i "-new-variation")]
                       [:div
                        [:button.secondary {:type "button" :data-add-review-variation true :aria-controls panel-id
                                            :aria-expanded (if expanded "true" "false")}
                         (if expanded "Cancel new variation" "Add variation")]
                        [:div {:id panel-id :data-new-review-variation true :hidden (not expanded)}
                         [:label.field [:span "Variation name"]
                          [:input {:name (str "song-" i "-variation-name") :value (or (:variation-name song) "")
                                   :maxlength 240 :data-review-variation-name true}]]
                         [:label.field [:span "Example link (optional)"]
                          [:input {:type "url" :name (str "song-" i "-variation-example-link")
                                   :value (or (:variation-example-link song) "")}]]
                         [:label.field [:span "Key"]
                          [:select {:name (str "song-" i "-variation-key")} (musical-key/options (:variation-key song))]]
                         [:label.field [:span "Prominent electric guitar"]
                          [:select {:name (str "song-" i "-variation-guitar")}
                           (variation/guitar-options (:variation-guitar song))]]
                         [:p.muted "Created when you approve. These fields take precedence over the dropdown. A matching name reuses the existing variation and settings; choose a unique name to use different settings."]]])
                     (when (:suggestion-notes song) [:p.notice (:suggestion-notes song)])
                     (when (and (str/blank? (:author song))
                                (not (re-matches #"[1-9]\d*" (str (:song-choice song)))))
                       [:p.notice {:role "status"} [:strong "Author credit needs manual review. "]
                        "Check the source slide and OCR text, then enter verified author names or choose an existing song before approval."])
                     [:div.fields
                      (for [[key label] (remove #(= :ocr (first %)) review/song-fields)]
                        (field-view nil {(keyword (str "song-" i "-" (name key))) (get song key)}
                                    [(keyword (str "song-" i "-" (name key))) label :text]))]
                     (review-song-tags i song (song-tags/all-tags (db/get-datasource)))
                     [:details [:summary "Evidence notes (optional)"]
                      (field-view nil {(keyword (str "song-" i "-ocr")) (:ocr song)}
                                  [(keyword (str "song-" i "-ocr")) "Evidence notes" :text])]]]])
                [:div.actions [:button {:name "action" :value "save-draft"} "Save draft"]
                 [:button.secondary {:name "action" :value "add-song"} "Save and add song"]]]
               [:section.card [:h2 "Approve this service"]
                [:p "Approval imports the last saved draft, including its songs, as one transaction. Save any edits above first. You approve each service separately. After approval commits and the selected title-slide images are verified in permanent storage, the downloaded video and OCR cache are removed."]
                [:form {:method "post" :action (str "/imports/" id)} (token)
                 [:input {:type "hidden" :name "revision" :value (:revision selection)}]
                 [:p {:id "approval-hint" :role "status"}]
                 [:button {:id "approve-draft" :name "action" :value "approve" :disabled (or cache-error (nil? (:draft_data selection)) (:range_needs_ocr selection))} "Approve saved draft and import"]]]))))))))

(defn save-review! [req]
  (let [id (some-> (get-in req [:path-params :id]) parse-long)
        revision (some-> (get-in req [:params "revision"]) parse-long)]
    (try
      (when-not (and id revision) (throw (ex-info "Invalid draft revision. Reload this page." {})))
      (cond
        (= "suggest-sermon-frame" (get-in req [:params "action"]))
        {:status 200 :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
         :body (json/write-str (review/nominate-sermon! id revision (some-> (get-in req [:params "frame-seconds"]) parse-long)))}
        (= "add-song-frame" (get-in req [:params "action"]))
        (let [position (review/add-song-frame! id revision
                         (some-> (get-in req [:params "frame-seconds"]) parse-long)
                         (review/parse-form (:params req)))]
          (redirect (str "/imports/" id "?notice=saved#song-review-" position)))
        (= "discard-ocr" (get-in req [:params "action"]))
        (do (video-range/discard-ocr! id revision) (redirect "/imports?notice=ocr-discarded#import-queue"))
        (= "suggest-details" (get-in req [:params "action"]))
        (do (review/suggest-details! id revision) (redirect (str "/imports/" id "?notice=suggestions")))
        (= "approve" (get-in req [:params "action"]))
        (redirect (str "/services/" (review/approve! id revision)))
        :else
        (let [action (get-in req [:params "action"])
              removed-index (some->> action (re-matches #"remove-song-(\d+)") second parse-long)
              _ (when-not (or (#{"save-draft" "add-song"} action) (some? removed-index)) (throw (ex-info "Unknown review action." {})))
              data (review/parse-form (:params req) removed-index)
              data (if (= action "add-song") (update data :songs conj {:order (inc (reduce max 0 (map #(or (:order %) 0) (:songs data))))}) data)]
          (when (> (count (:songs data)) 100) (throw (ex-info "A draft supports up to 100 songs." {})))
          (review/save-draft! id revision data)
          (redirect (str "/imports/" id "?notice=saved"))))
      (catch clojure.lang.ExceptionInfo e
        (assoc (review-page req (.getMessage e)
                 (when (= "add-song-frame" (get-in req [:params "action"]))
                   (try (review/parse-form (:params req)) (catch Exception _ nil)))) :status 422)))))

(defn range-page
  ([req] (range-page req nil))
  ([req error]
   (let [{:keys [entity id]} (:path-params req) id (some-> id parse-long)
         row (when (and (= entity "imports") id) (review/selection id))
         file (when row (jobs/downloaded-file id))]
     (if-not (and row file)
       {:status 404 :body "Download this video before choosing its OCR range."}
       (page "imports" "Choose OCR range"
         [:a.back {:href "/imports"} "← Import services"]
         [:div.heading [:div [:h1 "Choose the section to OCR"]
                        [:p.muted "Skip announcements or other sections by setting a start and end time."]]]
         [:p (if (:is_merged row)
               "Only the selected section is sampled for local OCR. Slide timestamps refer to the full combined recording; original-source offsets are preserved with the service."
               "The downloaded video is preserved. Only the selected section is sampled for local OCR; slide timestamps still refer to the original Vimeo video.")]
         (when error [:div.error {:role "alert"} error])
         (when (= "saved" (get-in req [:params "notice"])) [:p.notice "OCR range saved."])
         (when (:draft_data row) [:p.notice "Reprocessing replaces the recognized slides and preserves your edited service details and songs. Review the new results before approval."])
         [:video {:id "trim-preview" :controls true :preload "metadata" :src (str "/imports/" id "/preview")
                  :style "width:100%;max-height:520px;background:#111"}]
         [:p {:id "trim-player-status" :role "status"}]
         [:form.card {:method "post" :action (str "/imports/" id "/trim")}
          (token) [:input {:type "hidden" :name "revision" :value (:revision row)}]
          [:div.fields
           [:label.field {:for "trim-start"} [:span "Start · seconds, m:ss, or h:mm:ss"]
            [:input {:id "trim-start" :name "start" :value (or (get-in req [:params "start"]) (video-range/timestamp (:trim_start_seconds row)))}]
            [:button.secondary {:type "button" :data-time-target "trim-start"} "Use current video time"]]
           [:label.field {:for "trim-end"} [:span "End · leave blank for end of video"]
            [:input {:id "trim-end" :name "end" :value (or (get-in req [:params "end"]) (video-range/timestamp (:trim_end_seconds row)))}]
            [:button.secondary {:type "button" :data-time-target "trim-end"} "Use current video time"]]]
          [:p.muted "Choose at least 3 seconds and at most 3 hours. OCR samples every 3 seconds within this range. Changing the range requires running OCR again before approval."]
          [:div.actions.range-actions
           [:button.secondary {:type "button" :id "preview-range"} "Preview selected range"]
           [:button.secondary {:type "button" :id "whole-video-range"} "Use whole video"]
           [:button.secondary {:name "action" :value "save-range"} "Save range"]
           [:button {:name "action" :value "save-range-and-ocr" :disabled (or (:imported_service_id row) (jobs/active-statuses (:job_status row)))} "Save range and run local OCR"]]])))))

(defn save-range! [req]
  (try
    (let [id (some-> (get-in req [:path-params :id]) parse-long)
          revision (some-> (get-in req [:params "revision"]) parse-long)
          action (get-in req [:params "action"])]
      (when-not (and id revision (#{"save-range" "save-range-and-ocr"} action))
        (throw (ex-info "Invalid range request. Reload the page." {})))
      (video-range/save! id revision (get-in req [:params "start"]) (get-in req [:params "end"]) (= action "save-range-and-ocr"))
      (if (= action "save-range-and-ocr")
        (do (jobs/start! id) (redirect "/imports"))
        (redirect (str "/imports/" id "/trim?notice=saved"))))
    (catch clojure.lang.ExceptionInfo e (assoc (range-page req (.getMessage e)) :status 422))))

(defn preview-response [req]
  (let [{:keys [entity id]} (:path-params req) id (some-> id parse-long)]
    (if-let [file (when (and (= entity "imports") id (pos? id) (review/selection id)) (jobs/downloaded-file id))]
      (video-range/preview-response file (get-in req [:headers "range"]))
      {:status 404 :body "Download this video to preview it."})))

(defn video-response [req]
  (let [{:keys [entity id]} (:path-params req) id (some-> id parse-long)]
    (if-let [file (when (and (= entity "imports") id (pos? id) (review/selection id))
                   (jobs/downloaded-file id))]
      {:status 200 :headers {"Content-Type" "application/octet-stream"
                            "Content-Disposition" (str "attachment; filename=\"service-" id "-" (.getName file) "\"")
                            "Content-Length" (str (.length file)) "Cache-Control" "no-store"}
       :body file}
      {:status 404 :body "The video has not been downloaded yet."})))

(defn frame-response [req]
  (let [{:keys [entity id frame]} (:path-params req)
        service-id (some-> id parse-long) index (some-> frame parse-long)]
    (if-not (and (= entity "imports") service-id (pos? service-id) index (<= 0 index 1000000)
                 (review/selection service-id))
      {:status 404 :body "Frame not found"}
      (let [file (io/file (config/get-video-cache-dir) (str service-id) "frames"
                          (format "frame-%06d.jpg" (inc index)))]
        (if (.isFile file)
          {:status 200 :headers {"Content-Type" "image/jpeg" "Cache-Control" "private, max-age=3600"} :body file}
          {:status 404 :body "Frame not found"})))))

(defn ocr-frame-response [req]
  (let [{:keys [entity id frame]} (:path-params req)
        id (some-> id parse-long) index (some-> frame parse-long)
        row (when (and (= entity "imports") id (pos? id) index (<= 0 index 1000000)) (review/selection id))
        extraction (some-> (:extracted_data row) edn/read-string)
        source (first (filter #(= index (:index %)) (:evidence extraction)))]
    (if source
      {:status 200 :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
       :body (json/write-str {:index index :ocr (:ocr source)})}
      {:status 404 :body "Source OCR not found"})))

(defn archived-title-response [req]
  (let [{:keys [entity id]} (:path-params req)
        spec (case entity "services" [:church_service :sermon_title_image_path] "performances" [:song_performance :title_image_path] nil)
        id (some-> id parse-long)
        row (when (and spec id (pos? id)) (sql/get-by-id (db/get-datasource) (first spec) id opts))
        file (when row (archive/safe-image-file (get row (second spec))))]
    (if file
      {:status 200 :headers {"Content-Type" "image/jpeg" "Cache-Control" "private, max-age=3600"} :body file}
      {:status 404 :body "Archived image not found"})))

(defn service-merge-image-response [req]
  (let [{:keys [entity id frame]} (:path-params req)
        id (some-> id parse-long) source-id (some-> frame parse-long)
        row (when (and (= entity "services") id source-id)
              (sql/get-by-id (db/get-datasource) :service_merge_source source-id opts))
        file (when (= id (:church_service_id row)) (archive/safe-image-file (:sermon_title_image_path row)))]
    (if file {:status 200 :headers {"Content-Type" "image/jpeg"} :body file}
        {:status 404 :body "Archived image not found"})))

(def routes
  (-> (router/ring-handler
        (router/router
          [["/" {:get (fn [_] (redirect "/services"))}]
           ["/:entity" {:get (fn [req] (case (get-in req [:path-params :entity])
                                         "predictions" (prediction-page req)
                                         "statistics" (statistics-page req)
                                         "imports" (imports-page req) (list-page req)))
                         :post (fn [req] (if (= "imports" (get-in req [:path-params :entity]))
                                          (save-import-selection! req) {:status 404 :body "Not found"}))}]
           ["/:entity/:id" {:get (fn [req]
                                      (cond
                                        (= (:path-params req) {:entity "services" :id "reimport"}) (service-reimport-page req)
                                        (and (#{"songs" "variations"} (get-in req [:path-params :entity])) (= "unused" (get-in req [:path-params :id]))) (unused-library-page req)
                                        (and (#{"people" "series"} (get-in req [:path-params :entity])) (= "merge" (get-in req [:path-params :id]))) (record-merge-page req)
                                        (and (= "services" (get-in req [:path-params :entity])) (= "merge" (get-in req [:path-params :id]))) (service-merge-page req)
                                        (and (= "variations" (get-in req [:path-params :entity])) (= "merge" (get-in req [:path-params :id]))) (variation-merge-page req)
                                       (and (= "songs" (get-in req [:path-params :entity])) (= "merge" (get-in req [:path-params :id]))) (song-merge-page req)
                                        (= "imports" (get-in req [:path-params :entity])) (if (= "merge" (get-in req [:path-params :id])) (merge-page req) (review-page req))
                                        :else (edit-page req)))
                             :post (fn [req]
                                     (cond
                                       (= (:path-params req) {:entity "services" :id "reimport"}) (save-service-reimport! req)
                                       (and (#{"songs" "variations"} (get-in req [:path-params :entity])) (= "unused" (get-in req [:path-params :id]))) (save-unused-library! req)
                                       (and (#{"people" "series"} (get-in req [:path-params :entity])) (= "merge" (get-in req [:path-params :id]))) (save-record-merge! req)
                                       (and (= "services" (get-in req [:path-params :entity])) (= "merge" (get-in req [:path-params :id]))) (save-service-merge! req)
                                       (and (= "variations" (get-in req [:path-params :entity])) (= "merge" (get-in req [:path-params :id]))) (save-variation-merge! req)
                                       (and (= "songs" (get-in req [:path-params :entity])) (= "merge" (get-in req [:path-params :id]))) (save-song-merge! req)
                                       (= "imports" (get-in req [:path-params :entity])) (if (= "merge" (get-in req [:path-params :id])) (save-merge! req) (save-review! req))
                                       :else (save! req)))}]
           ["/:entity/:id/delete" {:get delete-page :post delete!}]
           ["/:entity/:id/merge-image/:frame" {:get service-merge-image-response}]
           ["/:entity/:id/title-image" {:get archived-title-response}]
           ["/:entity/:id/video" {:get video-response}]
           ["/:entity/:id/preview" {:get preview-response}]
           ["/:entity/:id/trim" {:get range-page :post save-range!}]
           ["/:entity/:id/frame/:frame" {:get frame-response}]
           ["/:entity/:id/ocr/:frame" {:get ocr-frame-response}]])
        (router/create-default-handler))
      wrap-anti-forgery
      (wrap-session {:cookie-attrs {:http-only true :same-site :strict}})
      wrap-params))

(defn app [req]
  (if (and (= :get (:request-method req)) (#{"/admin.css" "/review.js"} (:uri req)))
    {:status 200 :headers {"Content-Type" (if (= "/review.js" (:uri req)) "text/javascript; charset=utf-8" "text/css")}
     :body (slurp (io/resource (str "public" (:uri req))))}
    (if (#{"/songs/unused" "/variations/unused" "/people/merge" "/series/merge" "/imports/merge" "/songs/merge" "/variations/merge" "/services/merge" "/services/reimport"} (:uri req))
      (routes req)
    (if-let [[_ id] (re-matches #"/[^/]+/([^/]+)(?:/delete)?" (:uri req))]
      (if (or (= id "new") (and (parse-long id) (pos? (parse-long id))))
        (routes req)
        {:status 404 :body "Record not found"})
      (routes req)))))

(defonce server (atom nil))
;; Keep H2 open while the admin runs, avoiding repeated compilation of the generated-key function.
(defonce database-connection (atom nil))
(defn stop! []
  (jobs/stop-processes!)
  (when-let [s @server] (.stop s) (reset! server nil))
  (when-let [connection @database-connection] (.close ^java.sql.Connection connection) (reset! database-connection nil)))
(defn start! [{:keys [port] :or {port 3000}}]
  (stop!)
  (db/migrate! nil)
  (cleanup/resume-pending!)
  (jobs/recover!)
  (try
    (when-let [notice (jobs/reconcile-cache!)] (println notice))
    (catch clojure.lang.ExceptionInfo e
      (if (= :cache-unreadable (:kind (ex-data e)))
        (println (.getMessage e))
        (throw e))))
  (reset! database-connection (jdbc/get-connection (db/get-datasource)))
  (try
    (reset! server (jetty/run-jetty #'app {:host "127.0.0.1" :port port :join? false}))
    (catch Exception e (stop!) (throw e)))
  (println (str "Church Song Tracking admin: http://127.0.0.1:" port)))
