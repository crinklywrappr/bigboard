(ns bigboard.schedules
  (:require [bigboard.config :as cfg]
            [bigboard.db.model :as db]
            [bigboard.routes.ws :as ws]
            [gooff.core :as go]
            [mount.core :refer [defstate]]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]])
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

(defn status
  "Looks for a .prob file *first*"
  [{:keys [last-triggered last-finished] :as sched}]
  (let [reporter (io/file (reporter-path (:reporter sched)))
        story (io/file (story-path (:story sched)))
        prob (io/file (story-path (:story sched) ".prob"))
        [good? _] (cron? (:cron sched))]
    (cond
      (not (.exists reporter)) :mia
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
            ts (.toLocalDateTime (Timestamp. (.lastModified story)))]
        (if (and (.isAfter last-triggered ts) (.isAfter last-finished ts))
          :stale
          type)))))

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
                 :status :running}}))
      (delete-old-story story)
      (let [rp (reporter-path reporter)]
        (when (.exists (io/file rp))
          (try
            (->> rp sh :exit
                 (db/record-finished name))
            (catch Exception e
              (db/record-finished name 1)
              (make-error-file story e)))))
      (notify-clients!
       (ws/edn->transit
        {:cmd :replace
         :element :schedule
         :name name
         :new (let [sched (db/get-schedule name)]
                (assoc sched :status (status sched)))}))
      (catch Exception e
        (notify-clients!
         (ws/edn->transit
          {:cmd :error
           :element :schedule
           :name name}))))))

(defstate schedules
  :start (doseq [sched (db/get-schedules)]
           (go/add-schedule
            (:name sched)
            (go/cron (:cron sched))
            (run-schedule sched))
           (go/start (:name sched)))
  :stop (do
          (go/stop)
          (go/clear-schedule)))
