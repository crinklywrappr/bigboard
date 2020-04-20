(ns bigboard.routes.rest
  (:require
   [clojure.java.io :as io]
   [bigboard.middleware :as middleware]
   [bigboard.config :as cfg]
   [bigboard.db.model :as db]
   [ring.util.response]
   [ring.util.http-response :as response]
   [clojure.tools.logging :as log]
   [gooff.core :as go]
   [clj-time.format :as f]
   [clj-time.core :as t]
   [clojure.string :as str]))

(defn reporters [request]
  (if (string? (:reporters-dir cfg/env))
    (let [dir (io/file (:reporters-dir cfg/env))]
      (if (.exists dir)
        (try
          (response/ok
           (map
            #(.getName %)
            (.listFiles
             dir
             (reify java.io.FileFilter
               (accept [_ f]
                 (and (.isFile f)
                      (.canExecute f)
                      (not (.isHidden f))))))))
          (catch Exception e
            (log/error (.getMessage e))
            (response/service-unavailable
             "Error while looking up reporters")))
        (response/expectation-failed
         (str "Reporters directory does not exist.  "
              "Either create the directory ("
              (:reporters-dir cfg/env)
              "), or change the config (:reporters-dir)"))))
    (response/expectation-failed
     (str ":reporters-dir missing or invalid in config file"))))

(defn simulate [{:keys [params]}]
  (try
    (let [format (f/formatters :mysql)]
      (response/ok
       {:from (f/unparse format (t/now))
        :sims (map
               #(f/unparse format %)
               (-> params :cron go/cron
                   (go/simulate 5)))}))
    (catch Exception e
      (response/service-unavailable
       (.getMessage e)))))

(defn cron? [s]
  (try
    (go/cron s)
    [true ""]
    (catch Exception e
      [false (.getMessage e)])))

(defn constraint-violation? [exception]
  (= (type (.getCause exception))
     org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException))

(defn add-schedule [{:keys [name story contact
                            short-desc long-desc
                            trouble reporter cron]
                     :as params}]
  (let [[v e] (cron? cron)]
    (if v
      (try
        (db/add-schedule!
         name story contact
         short-desc long-desc
         trouble reporter cron)
        (response/ok)
        (catch Exception e
          (if (constraint-violation? e)
            (response/bad-request
             {:reason :name :msg "Name already exists"})
            (response/internal-server-error
             {:header "Database error occurred."
              :troubleshoot "Contact System Administrator."}))))
      (response/bad-request
       {:reason :cron :msg e}))))

(defn schedules [_]
  (try
    (response/ok
     (db/get-schedules))
    (catch Exception e
      (response/internal-server-error))))

(defn del-schedule [{:keys [name] :as params}]
  (try
    (do
      (db/unschedule name)
      (response/ok))
    (catch Exception e
      (response/internal-server-error))))

;; websockets for push notifications and actual scheduling are next
(defn rest-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/reporters" {:get reporters}]
   ["/simulate" {:get simulate}]
   ["/schedules" {:post #(add-schedule (:params %))
                  :get schedules
                  :delete #(del-schedule (:params %))}]])
