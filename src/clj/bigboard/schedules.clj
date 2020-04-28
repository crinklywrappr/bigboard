(ns bigboard.schedules
  (:require [bigboard.config :as cfg]
            [bigboard.db.model :as db]
            [bigboard.routes.ws :as ws]
            [gooff.core :as go]
            [mount.core :refer [defstate]]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clj-time.core :as t]
            [clojure.edn :as edn]
            [clojure.string :as s])
  (:import [java.sql Timestamp]
           [java.io File]
           [java.util Date]))

(def notify-clients! (partial ws/notify-clients! nil))

(defn story-path
  ([file]
   (-> cfg/env :bigboard :stories
       (str File/separator file)))
  ([file ext]
   (-> cfg/env :bigboard :stories
       (str File/separator file "." ext))))

(defn reporter-path [file]
  (-> cfg/env :bigboard :reporters
      (str File/separator file)))

(defn cron? [s]
  (try
    (go/cron s)
    [true ""]
    (catch Exception e
      [false (.getMessage e)])))

(defn mia?
  [reporter]
  (not
   (.exists
    (io/file
     (reporter-path
      reporter)))))

(defn last-modified
  [file]
  (.toLocalDateTime
   (Timestamp.
    (.lastModified file))))

(defn status
  "Looks for a .prob file *first*"
  [{:keys [last-triggered last-finished reporter] :as sched}]
  (let [story (io/file (story-path (:story sched)))
        prob (io/file (story-path (:story sched) "prob"))
        [good? _] (cron? (:cron sched))]
    (cond
      (mia? reporter) :mia
      (not good?) :bad
      (nil? last-triggered) :new
      (nil? last-finished) :running
      (.isAfter last-triggered last-finished) :running
      (> (:exit-code sched) 0) :error
      (and (not (.exists story))
           (not (.exists prob))) :no-story
      :else
      (let [[file type] (if (.exists prob)
                          [prob :problem]
                          [story :success])
            ts (last-modified file)]
        (if (and (.isAfter last-triggered ts)
                 (.isAfter last-finished ts))
          :stale
          type)))))

;; gooff uses joda-time and you'll notice the -X
;; at the end of each date-time which indicates
;; the timezone.  However, dmitri has provided
;; transit support for java.time.LocalDateTime,
;; so any time being communicated to the server
;; uses that class.
(defn to-local-datetime [dt]
  (-> dt
      .getMillis
      Date.
      .getTime
      Timestamp.
      .toLocalDateTime))

;; next-run does not update until after a process
;; is finished, so simply looking up :next-run in
;; the sched-map is not always what we are looking for
(defn next-run [name]
  (let [next (-> (go/get-sched-map)
                 (get name)
                 :next-run)]
    (if (t/before? (t/now) next)
      (to-local-datetime next)
      (to-local-datetime
       (first
        (go/simulate
         (-> (go/get-sched-map)
             (get name)
             :rules) 1))))))

(defn delete-old-story [prefix]
  (io/delete-file (story-path prefix) :story)
  (io/delete-file (story-path prefix "prob") :prob)
  (io/delete-file (story-path prefix "err") :error))

(defn make-error-file [prefix exception]
  (let [filename (story-path prefix "err")]
    (when (not (.exists (io/file filename)))
      (spit filename (.getMessage exception)))))

(defn now []
  (.toLocalDateTime
   (Timestamp.
    (.getTime
     (Date.)))))

(defn extra-info
  [{:keys [name] :as schedule}]
  (assoc
   schedule
   :status (status schedule)
   :next-run (next-run name)))

(defn get-extension [reporter]
  (str "." (last (s/split reporter #"\."))))

(defn get-runner
  "by default just returns [reporter]"
  [name reporter]
  (let [runners (-> "runners.edn"
                    io/resource
                    slurp
                    edn/read-string)]
    (conj
     (or (get runners name)
         (get runners (get-extension reporter) []))
     reporter)))

(defn run-schedule
  "Returns a function which executes the schedule in this order:
  1. update last-trigger
  2. notify clients
  3. delete old story
  4. run reporter and update exit-code & last-finished
  4a. generate error report if one occurs and the script did not make one
  5. notify clients

  Notifies clients if any error occured not on steps 4 or 4a"
  [{:keys [name story reporter]}]
  (fn []
    (try
      (db/record-trigger name)
      (notify-clients!
       (ws/edn->transit
        {:cmd :merge
         :element :schedule
         :name name
         :delta {:last-triggered (now) ;; approx.
                 :next-run (next-run name)
                 :status :running}}))
      (delete-old-story story)
      (let [rp (reporter-path reporter)]
        (when (.exists (io/file rp))
          (try
            (->> rp (get-runner name) (apply sh)
                 :exit (db/record-finished name))
            (catch Exception e
              (db/record-finished name 1)
              (make-error-file story e)))))
      (notify-clients!
       (ws/edn->transit
        {:cmd :replace
         :element :schedule
         :name name
         :new (extra-info (db/get-schedule name))}))
      (catch Exception e
        (notify-clients!
         (ws/edn->transit
          {:cmd :error
           :element :schedule
           :name name}))))))

(defn add-schedule [schedule]
  (go/add-schedule
   (:name schedule)
   (go/cron (:cron schedule))
   (run-schedule schedule))
  (go/start (:name schedule)))

(defn update-schedule
  [{:keys [name story reporter cron]
    :as schedule}]
  (go/update-fn name (run-schedule schedule))
  (go/update-rules name (go/cron cron))
  (go/restart name))

(defn unschedule [name]
  (go/stop name)
  (go/remove-schedule name))

(defstate schedules
  :start (doseq [sched (db/get-schedules)]
           (add-schedule sched))
  :stop (do
          (go/stop)
          (go/clear-schedule)))
