(ns bigboard.routes.rest
  (:require
   [clojure.java.io :as io]
   [bigboard.middleware :as middleware]
   [bigboard.config :as cfg]
   [bigboard.db.model :as db]
   [bigboard.schedules :as sched]
   [ring.util.response]
   [ring.util.http-response :as response]
   [clojure.tools.logging :as log]
   [gooff.core :as go]
   [clj-time.format :as f]
   [clj-time.core :as t]
   [clojure.string :as str]))

(defn reporters [request]
  (if (string? (-> cfg/env :bigboard :reporters))
    (let [dir (io/file (-> cfg/env :bigboard :reporters))]
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
              (-> cfg/env :bigboard :reporters)
              "), or change the BIGBOARD__REPORTERS"))))
    (response/expectation-failed
     (str "BIGBOARD__REPORTERS missing or invalid"))))

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

(defn constraint-violation? [exception]
  (= (type (.getCause exception))
     org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException))

(defn duplicate-story? [exception]
  (str/includes?
   (.getMessage (.getCause exception))
   "UNIQUE_STORIES"))

;; TODO: Add story filename extension verification
(defn add-schedule
  [{:keys [name story contact
           short-desc long-desc
           trouble reporter cron]
    :as params}]
  (let [[v e] (sched/cron? cron)]
    (cond
      (not v) (response/bad-request
               {:reason :cron :msg e})
      (sched/mia? reporter) (response/bad-request
                             {:reason :reporter
                              :msg "Reporter missing"})
      :else
      (try
        (db/add-schedule!
         name story contact
         short-desc long-desc
         trouble reporter cron)
        (sched/add-schedule params)
        (response/ok)
        (catch Exception e
          (if (constraint-violation? e)
            (if (duplicate-story? e)
              (response/bad-request
               {:reason :story :msg "Story already exists"})
              (response/bad-request
               {:reason :name :msg "Name already exists"}))
            (response/internal-server-error
             {:header "Database error occurred."
              :troubleshoot "Contact System Administrator."})))))))

(defn update-schedule
  [{:keys [name story contact
           short-desc long-desc
           trouble reporter cron]
    :as params}]
  (let [[v e] (sched/cron? cron)]
    (cond
      (not v) (response/bad-request
               {:reason :cron :msg e})
      (sched/mia? reporter) (response/bad-request
                             {:reason :reporter
                              :msg "Reporter missing"})
      :else
      (try
        (db/update-schedule!
         name story contact
         short-desc long-desc
         trouble reporter cron)
        (sched/update-schedule params)
        (response/ok)
        (catch Exception e
          (if (and (constraint-violation? e)
                   (duplicate-story? e))
            (response/bad-request
             {:reason :story :msg "Story already exists"})
            (response/internal-server-error
             {:header "Database error occurred."
              :troubleshoot "Contact System Administrator."})))))))

(defn extra-info
  [{:keys [name] :as schedule}]
  (assoc
   schedule
   :status (sched/status schedule)
   :next-run (sched/next-run name)))

(defn schedule [name]
  (try
    (if-let [schedule (db/get-schedule name)]
      (response/ok
       (extra-info schedule))
      (response/not-found))
    (catch Exception e
      (response/internal-server-error))))

(defn schedules [{:keys [params]}]
  (if (seq params)
    (schedule (:name params))
    (try
      (response/ok
       (map extra-info (db/get-schedules)))
      (catch Exception e
        (response/internal-server-error)))))

(defn del-schedule [{:keys [name] :as params}]
  (try
    (do
      (db/unschedule name)
      (response/ok))
    (catch Exception e
      (response/internal-server-error))))

(defn trigger [name]
  (try
    ((sched/run-schedule
      (db/get-schedule name)))
    (response/ok)
    (catch Exception e
      (response/internal-server-error
       (.getMessage e)))))

;; actual scheduling is next
(defn rest-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/reporters" {:get reporters}]
   ["/simulate" {:get simulate}]
   ["/schedules" {:post #(add-schedule (:params %))
                  :put #(update-schedule (:params %))
                  :get schedules
                  :delete #(del-schedule (:params %))}]
   ["/trigger" {:post #(trigger (-> % :params :name))}]])
