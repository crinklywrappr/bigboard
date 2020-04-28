(ns bigboard.routes.rest
  (:require
   [clojure.data.csv :as csv]
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
   [clojure.string :as s]
   [camel-snake-kebab.core :refer [->kebab-case-keyword]])
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
  (s/includes?
   (.getMessage (.getCause exception))
   "UNIQUE_STORIES"))

(defn add-schedule
  [{:keys [name story contact
           short-desc long-desc
           trouble reporter cron]
    :as params}]
  (let [[v e] (sched/cron? cron)]
    (cond
      (and (not (s/ends-with? story ".json"))
           (not (s/ends-with? story ".csv")))
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
      (and (not (s/ends-with? story ".json"))
           (not (s/ends-with? story ".csv")))
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
    ((sched/run-schedule
      (db/get-schedule name)))
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

(defn error-story
  "If story is provided, we ignore the name
  parameter to avoid a database lookup"
  ([story]
   (try
     (let [file (io/file (sched/story-path story "err"))]
       (if (.exists file)
         (response/ok
          (slurp file))
         (response/not-found
          "Story missing from filesystem")))
     (catch Exception e
       (response/internal-server-error
        "Problem reading error story"))))
  ([name story]
   (cond
     (seq story) (error-story story)
     (seq name) (try
                  (-> name
                      db/get-schedule
                      :story error-story)
                  (catch Exception e
                    (response/internal-server-error
                     "Problem querying database for schedule")))
     :else (response/bad-request
            "Either provide a schedule name or story name"))))

(defn csv-data->maps
  [csv-data]
  {:columns
   (map
    (fn [col]
      {:name col
       :data (->kebab-case-keyword col)})
    (first csv-data))
   :data
   (doall
    (map zipmap
         (->> (first csv-data)
              (map ->kebab-case-keyword)
              repeat)
         (rest csv-data)))})

(defn read-csv
  [story]
  (response/ok
   (assoc
    (csv-data->maps
     (csv/read-csv
      (io/reader story)))
    :timestamp (sched/last-modified story))))

;; TODO: flesh out
(defn read-json
  [story]
  (response/ok
   (slurp story)))

(defn read-story
  [prefix story]
  (cond
    (s/ends-with? prefix ".csv") (read-csv story)
    (s/ends-with? prefix ".json") (read-json story)
    :else
    (response/expectation-failed
     "Story expected to be either .csv or .json with a possible .prob extension")))

(def x (atom nil))

(defn get-story
  "If story is provided, we ignore the name
  parameter to avoid a database lookup.
  Like status, this function also ignores a
  good story if a problem story exists"
  ([story]
   (try
     (let [prob (io/file (sched/story-path story "prob"))
           good (io/file (sched/story-path story))]
       (cond
         (.exists prob) (read-story story prob)
         (.exists good) (read-story story good)
         :else
         (response/not-found
          "Story missing from filesystem")))
     (catch Exception e
       (reset! x e)
       (response/internal-server-error
        "Problem reading error story"))))
  ([name story]
   (cond
     (seq story) (get-story story)
     (seq name) (try
                  (-> name
                      db/get-schedule
                      :story get-story)
                  (catch Exception e
                    (response/internal-server-error
                     "Problem querying database for schedule")))
     :else (response/bad-request
            "Either provide a schedule name or story name"))))

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
   ["/data" {:get #(-> % :params :file data)}]
   ["/error-story" {:get #(error-story
                           (-> % :params :name)
                           (-> % :params :story))}]
   ["/story" {:get #(get-story
                     (-> % :params :name)
                     (-> % :params :story))}]])
