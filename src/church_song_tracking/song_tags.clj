(ns church-song-tracking.song-tags
  (:require [clojure.string :as str] [next.jdbc.sql :as sql]
            [church-song-tracking.vimeo-import :as vimeo]))
(defn all-tags [ds] (sql/query ds ["select * from song_tag order by name_key, id"] vimeo/opts))
(defn for-song [ds id]
  (sql/query ds ["select t.* from song_tag t join song_tag_assignment a on a.tag_id=t.id where a.song_id=? order by t.name_key, t.id" id] vimeo/opts))
(defn values [v] (cond (nil? v) [] (sequential? v) (vec v) :else [v]))
(defn save-assignments! [tx song-id params]
  (let [selected (mapv #(parse-long (str %)) (values (get params "tag-ids")))
        names (->> (str/split (get params "new-tags" "") #",") (map #(-> % str/trim (str/replace #"\s+" " "))) (remove str/blank?) distinct vec)]
    (when (or (some #(or (nil? %) (not (pos? %))) selected) (> (+ (count selected) (count names)) 100)
              (some #(> (count %) 80) names))
      (throw (ex-info "Use tags of 1 to 80 characters, with at most 100 tags per song." {})))
    (doseq [id selected]
      (when-not (sql/get-by-id tx :song_tag id vimeo/opts) (throw (ex-info "A selected tag no longer exists. Reload and choose tags again." {}))))
    (let [ids (set (concat selected
                   (for [label names]
                     (or (:id (first (sql/query tx ["select id from song_tag where name_key=CST_VARIATION_NAME_KEY(?)" label] vimeo/opts)))
                         (:id (sql/insert! tx :song_tag {:name label} vimeo/opts))))))]
      (sql/delete! tx :song_tag_assignment {:song_id song-id})
      (doseq [id ids] (sql/insert! tx :song_tag_assignment {:song_id song-id :tag_id id} vimeo/opts))
      ids)))
(defn merge-tags! [tx sources target]
  (let [ids (set (mapcat #(map :id (for-song tx %)) sources))
        current (set (map :id (for-song tx target)))]
    (doseq [id (remove current ids)]
      (sql/insert! tx :song_tag_assignment {:song_id target :tag_id id} vimeo/opts))))

(defn add-assignments! [tx song-id params]
  ;; Review adds tags to the resolved identity without replacing its existing tags.
  (save-assignments! tx song-id
    (assoc params "tag-ids" (vec (distinct (concat (map (comp str :id) (for-song tx song-id))
                                                  (map str (values (get params "tag-ids")))))))))
