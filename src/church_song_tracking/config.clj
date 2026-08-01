(ns church-song-tracking.config
  "Application configuration loaded from an external EDN file."
  (:require [clojure.core.memoize :as memo]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]))

(def ^:private config-path-env-var
  "CHURCH_SONG_TRACKING_CONFIG_PATH")

(def ^:private non-empty-string [:string {:min 1}])

(def ^:private config-schema
  [:map
   [:data-base-dir non-empty-string]
   [:db-name non-empty-string]])

(defn- validate-config
  [config path]
  (if-let [explanation (m/explain config-schema config)]
    (throw (ex-info "Application config is invalid"
                    {:config-path path
                     :explanation explanation}))
    config))

(defn- get-config-no-memo
  "Load application configuration from the EDN file named by
  CHURCH_SONG_TRACKING_CONFIG_PATH."
  []
  (let [path (System/getenv config-path-env-var)]
    (when-not (seq path)
      (throw (ex-info (str config-path-env-var " must be set to an EDN config file")
                      {:environment-variable config-path-env-var})))
    (let [file (io/file path)]
      (when-not (.isFile file)
        (throw (ex-info (str "Config file does not exist: " path)
                        {:config-path path})))
      (-> (try
            (edn/read-string (slurp file))
            (catch Exception cause
              (throw (ex-info (str "Could not read EDN config file: " path)
                              {:config-path path}
                              cause))))
          (validate-config path)))))

(def ^:private get-config
  "Return application configuration, reloading it at most once every five minutes."
  (memo/ttl get-config-no-memo :ttl/threshold (* 5 60 1000)))

(defn get-data-base-dir []
  (:data-base-dir (get-config)))

(defn get-db-path [] 
  (str (get-data-base-dir) "/" (:db-name (get-config))))


(comment

  (+ 1 1)

  (get-db-path)

  )