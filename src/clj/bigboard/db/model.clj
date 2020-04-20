(ns bigboard.db.model
  (:require
   [bigboard.db.core :as db]
   [clojure.string :as s]))

(defn add-schedule! [name story contact
                     short-desc long-desc
                     trouble reporter cron]
  (db/query-embedded
   :add-schedule
   {:name name :story story :contact contact
    :short-desc short-desc :long-desc long-desc
    :trouble trouble :reporter reporter :cron cron}))

(defn get-schedules []
  (letfn [(f [ntext]
            (let [r (.getCharacterStream ntext)]
              (-> "line.separator"
                  System/getProperty
                  (s/join (line-seq r)))))]
    (map
     (fn [m]
       (-> m
           (update :long-desc f)
           (update :trouble f)))
     (db/query-embedded :get-schedules))))

(defn unschedule [name]
  (db/query-embedded
   :unschedule
   {:name name}))
