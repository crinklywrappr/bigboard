(ns bigboard.db.model
  (:require
   [bigboard.db.core :as db]
   [clojure.string :as s]))

(defn add-schedule!
  [name story contact
   short-desc long-desc
   trouble reporter cron]
  (db/query-embedded
   :add-schedule
   {:name name :story story :contact contact
    :short-desc short-desc :long-desc long-desc
    :trouble trouble :reporter reporter :cron cron}))

(defn update-schedule!
  [name story contact
   short-desc long-desc
   trouble reporter cron]
  (db/query-embedded
   :update-schedule
   {:name name :story story :contact contact
    :short-desc short-desc :long-desc long-desc
    :trouble trouble :reporter reporter :cron cron}))

(defn fix-exit-code
  "Used for retrieval"
  [cd]
  (when (some? cd)
    (+' cd 128)))

(defn stream->text
  [ntext]
  (when (some? ntext)
    (let [r (.getCharacterStream ntext)]
      (-> "line.separator"
          System/getProperty
          (s/join (line-seq r))))))

(defn get-schedules []
  (map
   (fn [m]
     (-> m
         (update :long-desc stream->text)
         (update :trouble stream->text)
         (update :exit-code fix-exit-code)))
   (db/query-embedded :get-schedules)))

(defn unschedule [name]
  (db/query-embedded
   :unschedule
   {:name name}))

(defn record-trigger [name]
  (db/query-embedded
   :record-trigger
   {:name name}))

(defn record-finished
  "Subtracts 128 from exit code in order to fit
  inside a tinyint, which is -128 to 127"
  [name exit-code]
  (db/query-embedded
   :record-finished
   {:name name
    :code (- exit-code 128)}))

(defn get-schedule [name]
  (when-let [x (db/query-embedded
                :get-schedule
                {:name name})]
    (-> x
        (update :long-desc stream->text)
        (update :trouble stream->text)
        (update :exit-code fix-exit-code))))
