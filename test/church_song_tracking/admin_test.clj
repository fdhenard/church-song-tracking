(ns church-song-tracking.admin-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [church-song-tracking.admin :as admin]
            [church-song-tracking.config :as config]
            [church-song-tracking.db :as db]
            [church-song-tracking.initial-ingest :as ingest]
            [next.jdbc.sql :as sql]
            [clojure.string :as str]))

(defn request [entity id params] {:path-params {:entity entity :id (str id)} :params params})
(deftest admin-crud
  (let [dir (java.nio.file.Files/createTempDirectory "admin-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config/get-db-path #(str dir "/songs")]
      (ingest/initial-ingest {:path "resources/Church Songs Played - Sheet1.csv"})
      (doseq [entity (keys admin/entities)]
        (is (= 200 (:status (admin/app {:request-method :get :uri (str "/" entity) :headers {}}))))
        (is (= 200 (:status (admin/edit-page (request entity "new" {}))))))
      (is (= 403 (:status (admin/app {:request-method :post :uri "/people/new" :headers {}}))))
      (let [form (admin/app {:request-method :get :uri "/people/new" :headers {}})
            token (second (re-find #"name=\"__anti-forgery-token\"[^>]*value=\"([^\"]+)\"" (:body form)))
            cookies (get-in form [:headers "Set-Cookie"])
            cookie (first (str/split (if (string? cookies) cookies (first cookies)) #";"))
            response (admin/app {:request-method :post :uri "/people/new"
                                 :headers {"cookie" cookie "content-type" "application/x-www-form-urlencoded"}
                                 :body (java.io.ByteArrayInputStream.
                                         (.getBytes (str "name=Middleware+test&__anti-forgery-token="
                                                     (java.net.URLEncoder/encode token "UTF-8")) "UTF-8"))})]
        (is (= 303 (:status response))))
      (is (= 404 (:status (admin/app {:request-method :get :uri "/missing" :headers {}}))))
      (is (= 422 (:status (admin/save! (request "people" "new" {"name" " "})))))
      (let [response (admin/save! (request "people" "new" {"name" "<script>alert(1)</script>" "id" "1"}))
            location (get-in response [:headers "Location"])
            id (last (str/split location #"/"))]
        (is (= 303 (:status response)))
        (is (str/includes? (:body (admin/edit-page (request "people" id {}))) "&lt;script&gt;"))
        (is (= 303 (:status (admin/save! (request "people" id {"name" "Updated person"})))))
        (is (= "Updated person" (:name (admin/record (db/get-datasource) "people" (parse-long id)))))
        (is (= 200 (:status (admin/delete-page (request "people" id {})))))
        (is (= 303 (:status (admin/delete! (request "people" id {})))))
        (is (nil? (admin/record (db/get-datasource) "people" (parse-long id)))))
      (let [service (first (admin/rows (db/get-datasource) "services"))]
        (is (= 409 (:status (admin/delete! (request "services" (:id service) {})))))
        (is (= 422 (:status (admin/save! (request "performances" "new"
                                          {"church_service_id" (str (:id service)) "song_variation_id" "1" "order_in_service" "1"}))))))
      (is (= 404 (:status (admin/edit-page (request "songs" "99999" {}))))))))

(let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
