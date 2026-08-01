(ns church-song-tracking.db
  "Database migration entry points."
  (:require [church-song-tracking.config :as config]
            [clojure.java.io :as io]
            [migratus.core :as migratus]))

(defn- db-config
  []
  (let [path (config/get-db-path)]
    (when-let [parent (.getParentFile (io/file path))]
      (.mkdirs parent))
    {:store :database
     :migration-dir "migrations"
     :db {:dbtype "sqlite"
          :dbname path}}))

(defn migrate!
  "Apply all outstanding migrations.

  With Clojure CLI:
    clojure -X:migrate"
  [_opts]
  (migratus/migrate (db-config)))

(defn rollback!
  "Roll back the most recently applied migration."
  [_opts]
  (migratus/rollback (db-config)))

(comment
  
  (migrate! nil)

  (rollback! nil)
  
  )