(ns church-song-tracking.service-reimport
  (:require [church-song-tracking.db :as db]
            [church-song-tracking.library-cleanup :as cleanup]
            [church-song-tracking.vimeo-import :as vimeo]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def max-performances 3)

(defn candidates [ds]
  (sql/query ds
    ["select s.id, s.date, s.title, s.sermon_title, s.link_vimeo, s.vimeo_video_title,
             coalesce(p.performance_count, 0) as performance_count
        from church_service s
        left join (select church_service_id, count(*) as performance_count
                     from song_performance group by church_service_id) p on p.church_service_id = s.id
       where coalesce(p.performance_count, 0) <= ?
       order by s.date desc, s.id desc" max-performances] vimeo/opts))

(defn- selected-ids [params]
  (let [raw (get params "selected")
        ids (mapv #(parse-long (str %)) (if (sequential? raw) raw [raw]))]
    (when-not (and (every? #(and % (pos? %)) ids) (seq ids) (apply distinct? ids))
      (throw (ex-info "Select at least one service to preview." {})))
    (sort ids)))

(defn- service-state [ds id lock?]
  (let [lock (when lock? " for update")
        query (fn [statement] (sql/query ds [(str statement lock) id] vimeo/opts))
        service (first (query "select * from church_service where id = ?"))
        performances (query "select * from song_performance where church_service_id = ? order by order_in_service, id")]
    (when-not (and service (<= (count performances) max-performances))
      (throw (ex-info "A selected service was removed or now has more than three performances. Review the list again." {})))
    (let [sources (query "select id, vimeo_url from service_merge_source where church_service_id = ? order by id")
          recordings (query "select id, vimeo_url from service_video_source where church_service_id = ? order by id")
          imports (query "select id, vimeo_url, merged_into_selection_id from vimeo_import_selection where imported_service_id = ? order by id")]
      {:service service :performances performances :sources sources :recordings recordings :imports imports})))

(defn- selection [ds params lock?]
  (let [rows (mapv #(service-state ds % lock?) (selected-ids params))]
    {:rows rows :snapshot (cleanup/fingerprint rows)}))

(defn preview [ds params]
  (let [{:keys [rows] :as plan} (selection ds params false)]
    (assoc plan :rows
      (mapv (fn [{:keys [service performances sources recordings imports] :as row}]
              (assoc row
                :vimeo-urls (sort (vimeo/imported-video-urls
                                   (cons service (map #(hash-map :link_vimeo (:vimeo_url %))
                                                      (concat sources recordings imports)))))
                :song-names (mapv (fn [performance]
                                   (:name (first (sql/query ds
                                      ["select s.name from song s join song_variation v on v.song_id = s.id where v.id = ?"
                                       (:song_variation_id performance)] vimeo/opts))))
                                 performances)))
            rows))))

(defn delete-selected! [params]
  (when-not (= "yes" (get params "confirm"))
    (throw (ex-info "Review the preview and confirm before deleting." {})))
  (jdbc/with-transaction [tx (db/get-datasource)]
    (sql/query tx ["select id from import_approval_lock where id = 1 for update"])
    (let [{:keys [rows snapshot]} (selection tx params true)]
      (when-not (= snapshot (get params "snapshot"))
        (throw (ex-info "A selected service or its references changed. Preview the deletion again." {})))
      (doseq [{:keys [service performances]} rows
              :let [id (:id service)]]
        (sql/delete! tx :service_video_source {:church_service_id id})
        (sql/delete! tx :service_merge_source {:church_service_id id})
        (sql/delete! tx :service_merge_alias {:canonical_service_id id})
        (doseq [performance performances]
          (sql/delete! tx :performance_merge_alias {:canonical_performance_id (:id performance)})
          (sql/delete! tx :song_performance {:id (:id performance)})))
      ;; An approved merged import's parts point to its parent selection.
      (doseq [part? [true false]
              row rows
              item (:imports row)
              :when (= part? (some? (:merged_into_selection_id item)))]
        (sql/delete! tx :vimeo_import_selection {:id (:id item)}))
      (doseq [row rows]
        (sql/delete! tx :church_service {:id (get-in row [:service :id])}))
      (count rows))))
