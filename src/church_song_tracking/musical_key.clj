(ns church-song-tracking.musical-key
  (:require [clojure.string :as str]))
(def choices
  [["C" "C"] ["C#" "C♯ / D♭"] ["D" "D"] ["D#" "D♯ / E♭"]
   ["E" "E"] ["F" "F"] ["F#" "F♯ / G♭"] ["G" "G"]
   ["G#" "G♯ / A♭"] ["A" "A"] ["A#" "A♯ / B♭"] ["B" "B"]])
(defn parse-key [value]
  (let [value (some-> value str str/trim)]
    (when-not (str/blank? value)
      (let [value (get {"Db" "C#" "Eb" "D#" "Gb" "F#" "Ab" "G#" "Bb" "A#"} value value)]
        (if (contains? (set (map first choices)) value) value
            (throw (ex-info "Choose a musical key from the list, or leave it unset." {})))))))
(defn label [value] (get (into {} choices) value "—"))

(defn options [value]
  (cons [:option {:value "" :selected (nil? value)} "Unset"]
        (for [[v text] choices] [:option {:value v :selected (= value v)} text])))
