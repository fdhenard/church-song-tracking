(ns church-song-tracking.song-statistics
  (:require [next.jdbc.sql :as sql] [church-song-tracking.vimeo-import :as vimeo]))
(defn leaders [ds]
  (sql/query ds ["select distinct p.id, p.name from person p join church_service s on s.song_leader_id=p.id order by p.name, p.id"] vimeo/opts))
(defn report [ds leader]
  (when (and leader (not (some #(= leader (:id %)) (leaders ds))))
    (throw (ex-info "Choose a worship leader from the list." {})))
  (let [where-clause (if leader " where cs.song_leader_id=?" "")
        query (fn [text] (sql/query ds (cond-> [text] leader (conj leader)) vimeo/opts))
        totals (first (query (str "select count(distinct cs.id) as services, count(p.id) as performances, count(distinct v.song_id) as songs from church_service cs join song_performance p on p.church_service_id=cs.id join song_variation v on v.id=p.song_variation_id" where-clause)))
        denominator (:services totals)
        songs (query (str "select song.id, song.name, song.artist, count(p.id) as performances, count(distinct cs.id) as services, min(cs.date) as first_used, max(cs.date) as last_used from song join song_variation v on v.song_id=song.id join song_performance p on p.song_variation_id=v.id join church_service cs on cs.id=p.church_service_id" where-clause " group by song.id, song.name, song.artist order by services desc, performances desc, last_used desc, lower(song.name), lower(song.artist), song.id fetch first 20 rows only"))]
    {:leader leader :totals totals
     :songs (mapv #(assoc % :repeats (max 0 (dec (:services %)))
                           :selection-rate (if (pos? denominator) (* 100.0 (/ (:services %) denominator)) 0.0)) songs)}))
