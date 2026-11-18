(ns church-song-tracking.db
  "Database migration entry points."
  (:require [church-song-tracking.config :as config]
            [clojure.java.io :as io]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]))

(defn- db-spec
  []
  (let [path (config/get-db-path)]
    {:dbtype "h2"
     :dbname path}))

(defn- migration-config
  []
  (let [path (config/get-db-path)]
    (when-let [parent (.getParentFile (io/file path))]
      (.mkdirs parent))
    {:store :database
     :migration-dir "migrations"
     :db (db-spec)}))

(defn migrate!
  "Apply all outstanding migrations.

  With Clojure CLI:
    clojure -X:migrate"
  [_opts]
  (migratus/migrate (migration-config)))

(defn rollback!
  "Roll back the most recently applied migration."
  [_opts]
  (migratus/rollback (migration-config)))

(comment
  
  (config/get-db-path)
  
  (migrate! nil)

  (rollback! nil)
  
  )

(defn get-datasource []
  (jdbc/get-datasource (db-spec)))
