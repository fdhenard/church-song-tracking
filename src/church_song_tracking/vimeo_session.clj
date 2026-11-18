(ns church-song-tracking.vimeo-session
  (:require [church-song-tracking.config :as config]
            [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [java.util.concurrent TimeUnit]))

(defn cookie-file []
  (io/file (System/getProperty "user.home") "Library/Application Support/church-song-tracking/vimeo-session.cookies"))

(defn connected? [] (.isFile (cookie-file)))

(defn disconnect! []
  (java.nio.file.Files/deleteIfExists (.toPath (cookie-file))))

(defn connect! [browser]
  (when-not (#{"chrome" "firefox"} browser) (throw (ex-info "Choose Chrome or Firefox." {})))
  (let [process (.start (doto (ProcessBuilder. ^java.util.List
                               [(:python (config/video-settings)) "scripts/vimeo_session.py" "--browser" browser
                                "--destination" (.getAbsolutePath (cookie-file))])
                         (.redirectError (io/file "/dev/null"))))]
    (when-not (.waitFor process 90 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Reading the browser session timed out. Open the browser, allow any macOS Keychain prompt, then try again." {})))
    (let [result (try (json/read-str (slurp (.getInputStream process)) :key-fn keyword) (catch Exception _ nil))]
      (when-not (and (zero? (.exitValue process)) (:ok result))
        (throw (ex-info (or (:error result) "Could not save the Vimeo session. Open your browser and sign in, then try again.") {}))))))
