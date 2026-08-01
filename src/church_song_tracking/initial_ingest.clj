
(ns church-song-tracking.initial-ingest
  "Read source CSV data for the initial database ingest."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [church-song-tracking.config :as config]))

(defn initial-ingest
  []
  (let [path (str (config/get-data-base-dir) "/Church Songs Played - Sheet1.csv")
        csv-data
        (with-open [reader (io/reader path)]
          (into [] (csv/read-csv reader)))]
    csv-data))


(comment
  
  (initial-ingest)
  )