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
   [clojure.string :as str])
  (:import [java.io FilenameFilter]))

(defn reporters
  [request]
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

(defn simulate
  [{:keys [params]}]
  (try
    (response/ok
     {:from (sched/now)
      :sims (map sched/to-local-datetime
                 (-> params :cron go/cron
                     (go/simulate 7)))})
    (catch Exception e
      (response/service-unavailable
       (.getMessage e)))))

(defn constraint-violation?
  [exception]
  (= (type (.getCause exception))
     org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException))

(defn duplicate-story?
  [exception]
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
      (and (not (str/ends-with? story ".json"))
           (not (str/ends-with? story ".csv")))
      (response/bad-request
       {:reason :story
        :msg "Story must end in .json or .csv"})
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
      (and (not (str/ends-with? story ".json"))
           (not (str/ends-with? story ".csv")))
      (response/bad-request
       {:reason :story
        :msg "Story must end in .json or .csv"})
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

(defn schedule
  [name]
  (try
    (if-let [schedule (db/get-schedule name)]
      (response/ok
       (sched/extra-info schedule))
      (response/not-found))
    (catch Exception e
      (response/internal-server-error))))

(defn schedules
  [{:keys [params]}]
  (if (seq params)
    (schedule (:name params))
    (try
      (response/ok
       (map sched/extra-info (db/get-schedules)))
      (catch Exception e
        (response/internal-server-error)))))

(defn del-schedule
  [{:keys [name] :as params}]
  (try
    (do
      (db/unschedule name)
      (sched/unschedule name)
      (response/ok))
    (catch Exception e
      (response/internal-server-error))))

(defn trigger
  [name]
  (try
    (.start
     (Thread.
      (sched/run-schedule
       (db/get-schedule name))))
    (response/ok)
    (catch Exception e
      (response/internal-server-error
       (.getMessage e)))))

(defn data
  "returns the contents of file from
  the BIGBOARD__DATA directory"
  [file]
  (try
    (if (seq (-> cfg/env :bigboard :data))
      (let [path (-> cfg/env :bigboard :data io/file)]
        (if (.exists path)
          (if-let [f (first
                      (.listFiles
                       path
                       (reify FilenameFilter
                         (accept [_ _ f]
                           (= f file)))))]
            (response/ok (slurp f))
            (response/not-found
             (str file " not found in BIGBOARD__DATA")))
          (response/not-found
           "BIGBOARD__DATA missing from filesystem")))
      (response/not-found "BIGBOARD__DATA not set"))
    (catch Exception e
      (response/internal-server-error))))

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
   ["/trigger" {:post #(-> % :params :name trigger)}]
   ["/data" {:get #(-> % :params :file data)}]])
