(ns church-song-tracking.video-range
  (:require [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(defn timestamp [seconds]
  (when (some? seconds)
    (let [n (long seconds)] (format "%d:%02d:%02d" (quot n 3600) (quot (mod n 3600) 60) (mod n 60)))))
(defn parse-time [text]
  (when-not (str/blank? text)
    (let [parts (str/split (str/trim text) #":")]
      (when-not (and (<= 1 (count parts) 3) (every? #(re-matches #"\d{1,8}" %) parts))
        (throw (ex-info "Enter seconds, m:ss, or h:mm:ss for the OCR range." {})))
      (let [numbers (mapv parse-long parts)]
        (when (or (some #(>= % 60) (rest numbers)) (> (reduce #(+ (* %1 60) %2) 0 numbers) 604800))
          (throw (ex-info "Enter a valid OCR range time." {})))
        (reduce #(+ (* %1 60) %2) 0 numbers)))))
(defn duration [id]
  (let [file (io/file (config/get-video-cache-dir) (str id) "metadata.json")]
    (when (.isFile file) (:duration (json/read-str (slurp file) :key-fn keyword)))))

(defn save! [id revision start-text end-text & [reprocess?]]
  (let [start (or (parse-time start-text) 0) end (parse-time end-text) length (duration id)
        effective-end (or end length)]
    (when (or (and effective-end (< (- effective-end start) 3))
              (and length (or (>= start length) (and end (> end length))))
              (and effective-end (> (- effective-end start) 10800)))
      (throw (ex-info "Choose a range within the video, at least 3 seconds and at most 3 hours long." {})))
    (jdbc/with-transaction [tx (db/get-datasource)]
      (let [row (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))]
        (when-not row (throw (ex-info "Selection not found." {})))
        (when (or (:merged_into_selection_id row) (:imported_service_id row) (#{"queued" "downloading" "extracting" "recognizing" "merging"} (:job_status row)))
          (throw (ex-info "Wait for processing to finish or cancel it before changing the OCR range. Imported services cannot be reprocessed." {})))
        (when-not (= revision (:revision row)) (throw (ex-info "This selection changed. Reload before saving the range." {})))
        (let [changed? (not= [start end] [(:trim_start_seconds row) (:trim_end_seconds row)])]
          (when (or changed? reprocess?)
            (sql/update! tx :vimeo_import_selection
              {:trim_start_seconds start :trim_end_seconds end :revision (inc revision)
               :range_needs_ocr true :job_status "downloaded"
               :job_message "OCR range changed · run local OCR to update the review. Existing edits are preserved."} {:id id})))))))

(defn discard-ocr! [id revision]
  (jdbc/with-transaction [tx (db/get-datasource)]
    (let [row (first (sql/query tx ["select * from vimeo_import_selection where id = ? for update" id] vimeo/opts))]
      (when-not row (throw (ex-info "Selection not found." {})))
      (when (or (:merged_into_selection_id row) (:imported_service_id row) (#{"queued" "downloading" "extracting" "recognizing" "merging"} (:job_status row)))
        (throw (ex-info "Wait for processing to finish before discarding OCR. Imported services cannot be reset." {})))
      (when-not (= revision (:revision row))
        (throw (ex-info "This selection changed. Reload before discarding OCR." {})))
      ;; Clear review proposals, not the reusable per-frame OCR cache or original video.
      (let [draft (when (:draft_data row) (edn/read-string (:draft_data row)))
            kept (select-keys draft [:date :title :leader :preacher])]
        (sql/update! tx :vimeo_import_selection
          {:draft_data (pr-str kept) :extracted_data nil :trim_start_seconds 0 :trim_end_seconds nil
           :range_needs_ocr true :job_refresh_review false :job_token nil
           :revision (inc revision) :job_status "downloaded"
           :job_message "OCR review discarded · choose a new range. Download and reusable OCR cache kept."} {:id id})))))

(defn preview-response [file range-header]
  (let [size (.length file) base {"Content-Type" (or (java.net.URLConnection/guessContentTypeFromName (.getName file)) "video/mp4") "Accept-Ranges" "bytes" "Cache-Control" "no-store"}
        invalid {:status 416 :headers (assoc base "Content-Range" (str "bytes */" size)) :body "Invalid byte range"}]
    (if-not range-header
      {:status 200 :headers (assoc base "Content-Length" (str size)) :body file}
      (if-let [[_ left right] (re-matches #"bytes=(\d*)-(\d*)" range-header)]
        (let [a (when (seq left) (parse-long left)) b (when (seq right) (parse-long right))
              suffix? (empty? left)
              start (if suffix? (when b (max 0 (- size b))) a)
              end (if suffix? (dec size) (min (or b (dec size)) (dec size)))]
          (if-not (and (pos? size) start (<= 0 start end) (< start size)
                       (or (not suffix?) (and b (pos? b)))
                       (or (empty? right) b))
            invalid
            (let [length (inc (- end start)) stream (io/input-stream file) remaining (atom length)]
              (try (.skipNBytes stream start) (catch Exception e (.close stream) (throw e)))
              {:status 206 :headers (assoc base "Content-Length" (str length) "Content-Range" (str "bytes " start "-" end "/" size))
               :body (proxy [java.io.FilterInputStream] [stream]
                       (read
                         ([] (if (zero? @remaining) -1 (let [v (.read stream)] (when (not= -1 v) (swap! remaining dec)) v)))
                         ([buffer]
                          (if (zero? (alength ^bytes buffer)) 0
                            (if (zero? @remaining) -1
                              (let [n (.read stream buffer 0 (int (min @remaining (alength ^bytes buffer))))]
                                (when (pos? n) (swap! remaining - n)) n))))
                         ([buffer offset requested]
                          (if (zero? @remaining) -1
                            (let [n (.read stream buffer offset (int (min @remaining requested)))]
                              (when (pos? n) (swap! remaining - n)) n)))))})))
        invalid))))
