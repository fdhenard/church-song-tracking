(ns church-song-tracking.csv-utils
  (:require [clojure.data.csv :as csv]
            [clojure.string]
            [clojure.java.io :as io]))

(defn csv->maps
  [path col-name-mapping]
  (let [csv-data (with-open [reader (io/reader path)]
                   (into [] (csv/read-csv reader)))
        [column-names & rows] csv-data
        property-names (mapv col-name-mapping column-names)]
    (when (or (empty? column-names) (some nil? property-names)
              (not= (count property-names) (count (set property-names))))
      (throw (ex-info "CSV has missing, unknown, or duplicate headers" {:headers column-names})))
    (mapv (fn [index row]
            (when-not (= (count row) (count column-names))
              (throw (ex-info "CSV row has wrong column count" {:row (+ index 2)})))
            (zipmap property-names row))
          (range) (remove #(every? clojure.string/blank? %) rows))))
