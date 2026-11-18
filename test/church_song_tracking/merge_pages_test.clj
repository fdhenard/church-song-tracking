(ns church-song-tracking.merge-pages-test
  (:require [clojure.test :refer [deftest is]] [clojure.string :as str]
            [church-song-tracking.admin :as admin] [church-song-tracking.video-jobs :as jobs]
            [church-song-tracking.video-metadata :as metadata] [church-song-tracking.vimeo-import :as vimeo]))

(deftest selects-then-orders-only-selected-recordings
  (let [rows (mapv #(hash-map :id % :vimeo_url (str "https://vimeo.com/" %) :job_status "downloaded") [1 2 3])]
    (with-redefs [vimeo/queue (constantly rows) jobs/downloaded-file (constantly true)
                  metadata/original-title #(str "Recording " (:id %))]
      (let [html (:body (admin/merge-page {:params {"video" "https://vimeo.com/1"}}))]
        (is (str/includes? html "Continue"))
        (is (str/includes? html "Recording 3"))
        (is (not (str/includes? html "Move up")))
        (is (not (str/includes? html "<video")))
        (is (not (str/includes? html "Create merged service video"))))
      (let [html (:body (admin/merge-page {:params {"step" "order" "include" ["1" "2"] "part" ["2" "1"]}}))]
        (is (str/includes? html "Arrange recordings"))
        (is (str/includes? html "name=\"recording-title\""))
        (is (str/includes? html "<datalist id=\"merge-recording-titles\""))
        (is (str/includes? html "<option value=\"Recording 1\""))
        (is (str/includes? html "<option value=\"Recording 2\""))
        (is (= 2 (count (re-seq #"<video " html))))
        (is (str/includes? html "/imports/1/preview"))
        (is (str/includes? html "/imports/2/preview"))
        (is (not (str/includes? html "/imports/3/preview")))
        (is (str/includes? html "Move up"))
        (is (str/includes? html "Create merged service video"))
        (is (str/includes? html "Back to selection"))
        (is (not (str/includes? html "Recording 3")))
        (is (< (str/index-of html "Recording 2") (str/index-of html "Recording 1"))))
      (doseq [ids [["1"] ["1" "99"]]]
        (let [html (:body (admin/merge-page {:params {"step" "order" "include" ids}}))]
          (is (str/includes? html "Select two to eight available"))
          (is (not (str/includes? html "Create merged service video"))))))))
