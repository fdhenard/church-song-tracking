(ns church-song-tracking.sermon-similarity
  (:require [clojure.data.json :as json] [clojure.string :as str])
  (:import [java.net URI] [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(def model "text-embedding-3-small")
(defonce cache (atom {}))
(defn api-key [] (not-empty (str/trim (or (System/getenv "CHURCH_SONG_TRACKING_OPENAI_API_KEY") ""))))
(defn sermon-text [title scripture]
  (when (or (not (str/blank? title)) (not (str/blank? scripture)))
    (str "Sermon title: " (subs (or title "") 0 (min 1000 (count (or title ""))))
         "\nScripture: " (subs (or scripture "") 0 (min 1000 (count (or scripture "")))))))
(defn request-embeddings [texts]
  (let [key (api-key)]
    (when-not key (throw (ex-info "AI matching needs CHURCH_SONG_TRACKING_OPENAI_API_KEY on the server." {})))
    (try
      (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) .build)
            request (-> (HttpRequest/newBuilder (URI/create "https://api.openai.com/v1/embeddings"))
                        (.timeout (Duration/ofSeconds 30))
                        (.header "Authorization" (str "Bearer " key)) (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString (json/write-str {:model model :input texts :encoding_format "float"}))) .build)
            response (.send client request (HttpResponse$BodyHandlers/ofString))]
        (when-not (= 200 (.statusCode response))
          (throw (ex-info (case (.statusCode response)
                           401 "AI authentication failed. Check the server API key."
                           429 "AI matching is rate limited or out of credits. Try later or check API billing."
                           "AI matching is temporarily unavailable. Try again later.") {})))
        (let [rows (:data (json/read-str (.body response) :key-fn keyword))
              vectors (mapv :embedding (sort-by :index rows))]
          (when-not (and (= (count texts) (count vectors))
                         (= (vec (range (count texts))) (mapv :index (sort-by :index rows)))
                         (every? #(and (sequential? %) (seq %) (every? (fn [n] (and (number? n) (Double/isFinite (double n)))) %)) vectors)
                         (apply = (map count vectors)))
            (throw (ex-info "AI matching returned an invalid response. Try again." {})))
          vectors))
      (catch clojure.lang.ExceptionInfo e (throw e))
      (catch Exception _ (throw (ex-info "AI matching could not connect or timed out. Try again later." {}))))))
(defn embeddings [texts]
  ;; Cache only successful vectors; bounded and reset when the server restarts.
  (locking cache
    (let [missing (vec (distinct (remove #(contains? @cache [model %]) texts)))
          fresh (into {} (mapcat (fn [batch] (map (fn [text vector] [[model text] vector]) batch (request-embeddings (vec batch))))
                                (partition-all 64 missing)))
          all (merge @cache fresh)
          result (mapv #(get all [model %]) texts)]
      (reset! cache (if (> (count all) 2048) fresh all))
      result)))
(defn cosine [a b]
  (when-not (= (count a) (count b)) (throw (ex-info "AI matching returned incompatible vectors." {})))
  (let [dot (reduce + (map * a b)) norm #(Math/sqrt (double (reduce + (map * % %))))
        denominator (* (norm a) (norm b))]
    (if (pos? denominator) (max -1.0 (min 1.0 (/ dot denominator))) 0.0)))
(defn related [services input]
  (let [query (sermon-text (:title input) (:scripture input))
        candidates (vec (filter #(sermon-text (:sermon_title %) (:sermon_scripture %)) services))]
    (when-not query (throw (ex-info "Enter a sermon title or scripture to use AI matching." {})))
    (if (empty? candidates) {}
      (let [vectors (embeddings (into [query] (map #(sermon-text (:sermon_title %) (:sermon_scripture %)) candidates)))]
        (into {} (keep (fn [[service vector]]
                        (let [score (cosine (first vectors) vector)]
                          (when (>= score 0.55)
                            [(:id service) {:score score :title (:sermon_title service) :scripture (:sermon_scripture service)}])))
                      (map vector candidates (rest vectors))))))))
