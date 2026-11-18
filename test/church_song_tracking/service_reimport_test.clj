(ns church-song-tracking.service-reimport-test
  (:require [church-song-tracking.admin :as admin]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.vimeo-import :as vimeo]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [next.jdbc.sql :as sql]))

(deftest selective-reimport
  (let [dir (java.nio.file.Files/createTempDirectory "service-reimport" (make-array java.nio.file.attribute.FileAttribute 0))
        path "/services/reimport"]
    (with-redefs [config/get-db-path #(str dir "/songs")
                  vimeo/cached-catalog (constantly [{:url "https://vimeo.com/101" :title "Livestream 10-01-26"}
                                                  {:url "https://vimeo.com/102" :title "Livestream 10-01-26"}])]
      (db/migrate! nil)
      (let [ds (db/get-datasource)
            insert! (fn [table data] (:id (sql/insert! ds table data vimeo/opts)))
            person (insert! :person {:name "Leader"})
            service! (fn [title vimeo]
                       (insert! :church_service {:date (java.sql.Date/valueOf "2026-10-01") :title title
                                                 :preacher_id person :song_leader_id person
                                                 :sermon_title "Sermon" :link_vimeo vimeo}))
            selected (service! "Selected service" "https://vimeo.com/101")
            unchecked (service! "Unchecked service" nil)
            larger (service! "Larger service" nil)
            song (insert! :song {:name "Repeat song" :artist ""})
            variation (insert! :song_variation {:song_id song :variation_name "Original"})
            performance! (fn [service order]
                           (insert! :song_performance {:church_service_id service :song_variation_id variation
                                                       :order_in_service order}))
            parent (insert! :vimeo_import_selection {:vimeo_url "https://vimeo.com/101"
                                                      :imported_service_id selected :is_merged true})
            part (insert! :vimeo_import_selection {:vimeo_url "https://vimeo.com/102"
                                                    :imported_service_id selected :merged_into_selection_id parent})
            get-page (fn [] (admin/app {:request-method :get :uri path :headers {}}))
            submit! (fn [form fields]
                      (let [token (second (re-find #"name=\"__anti-forgery-token\"[^>]*value=\"([^\"]+)\"" (:body form)))
                            cookies (get-in form [:headers "Set-Cookie"])
                            cookie (first (str/split (if (string? cookies) cookies (first cookies)) #";"))]
                        (admin/app {:request-method :post :uri path
                                    :headers {"cookie" cookie "content-type" "application/x-www-form-urlencoded"}
                                    :body (java.io.ByteArrayInputStream.
                                           (.getBytes (str fields "&__anti-forgery-token="
                                                           (java.net.URLEncoder/encode token "UTF-8")) "UTF-8"))})))]
        (insert! :service_video_source {:import_selection_id parent :source_selection_id part
                                        :church_service_id selected :source_order 1
                                        :vimeo_url "https://vimeo.com/102" :vimeo_video_title "Part two"})
        (doseq [order (range 1 4)] (performance! selected order))
        (doseq [order (range 1 5)] (performance! larger order))
        (let [page (get-page)
              preview (submit! page (str "action=preview&selected=" selected))
              snapshot (second (re-find #"name=\"snapshot\"[^>]*value=\"([^\"]+)\"" (:body preview)))
              delete-fields (str "action=delete&selected=" selected "&snapshot=" snapshot)
              imported #(vimeo/imported-video-urls (vimeo/imported-recordings ds))]
          (is (= 200 (:status page)))
          (is (str/includes? (:body page) "Selected service"))
          (is (str/includes? (:body page) "Unchecked service"))
          (is (not (str/includes? (:body page) "Larger service")))
          (is (= 200 (:status preview)))
          (is (str/includes? (:body preview) "Repeat song"))
          (is (str/includes? (:body preview) "https://vimeo.com/102"))
          (is (= #{"https://vimeo.com/101" "https://vimeo.com/102"} (imported)))
          (is (= 422 (:status (submit! page delete-fields))))
          (let [extra (performance! selected 4)]
            (is (= 422 (:status (submit! page (str delete-fields "&confirm=yes")))))
            (is (some? (admin/record ds "services" selected)))
            (sql/delete! ds :song_performance {:id extra}))
          (is (= 303 (:status (submit! page (str delete-fields "&confirm=yes")))))
          (is (nil? (admin/record ds "services" selected)))
          (is (every? #(admin/record ds "services" %) [unchecked larger]))
          (is (some? (admin/record ds "songs" song)))
          (is (empty? (imported)))
          (is (empty? (vimeo/queue)))
          (is (str/includes? (:body (admin/imports-page {:headers {"x-import-progress" "true"}}))
                             "https://vimeo.com/102")))))))
