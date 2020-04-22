(ns bigboard.schedules
  (:require [bigboard.config :as cfg]
            [bigboard.db.model :as db]
            [gooff.core :as go]
            [clojure.java.io :as io])
  (:import [java.sql Timestamp]))

(defn cron? [s]
  (try
    (go/cron s)
    [true ""]
    (catch Exception e
      [false (.getMessage e)])))

(defn state
  "Looks for a .prob file *first*"
  [{:keys [last-triggered last-finished] :as sched}]
  (let [reporter (io/file (str (-> cfg/env :bigboard :reporters) "\\" (:reporter sched)))
        story (io/file (str (-> cfg/env :bigboard :stories) "\\" (:story sched)))
        prob (io/file (str (-> cfg/env :bigboard :stories) "\\" (:story sched) ".prob"))
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

