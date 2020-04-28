(ns bigboard.db.core
  (:require
    [next.jdbc.date-time]
    [next.jdbc.result-set]
    [conman.core :as conman]
    [mount.core :refer [defstate]]
    [bigboard.config :refer [env]]
    [luminus-migrations.core :refer [migrate]]
    [camel-snake-kebab.extras :refer [transform-keys]]
    [camel-snake-kebab.core :refer [->kebab-case-keyword
                                    ->snake_case_keyword]]))


(defstate ^:dynamic *db*
          :start (conman/connect! {:jdbc-url (env :database-url)})
          :stop (conman/disconnect! *db*))

(defstate migration
  :start (when (:prod env)
           (migrate ["migrate"] (select-keys env [:database-url]))))

(defstate queries
  :start (conman/load-queries "sql/queries.sql"))

(extend-protocol next.jdbc.result-set/ReadableColumn
  java.sql.Timestamp
  (read-column-by-label [^java.sql.Timestamp v _]
    (.toLocalDateTime v))
  (read-column-by-index [^java.sql.Timestamp v _2 _3]
    (.toLocalDateTime v))
  java.sql.Date
  (read-column-by-label [^java.sql.Date v _]
    (.toLocalDate v))
  (read-column-by-index [^java.sql.Date v _2 _3]
    (.toLocalDate v))
  java.sql.Time
  (read-column-by-label [^java.sql.Time v _]
    (.toLocalTime v))
  (read-column-by-index [^java.sql.Time v _2 _3]
    (.toLocalTime v)))

(defn result-one-snake->kebab
  [this result options]
  (->> (hugsql.adapter/result-one this result options)
       (transform-keys ->kebab-case-keyword)))

(defn result-many-snake->kebab
  [this result options]
  (->> (hugsql.adapter/result-many this result options)
       (map #(transform-keys ->kebab-case-keyword %))))

(defmethod hugsql.core/hugsql-result-fn :1 [sym]
  'bigboard.db.core/result-one-snake->kebab)

(defmethod hugsql.core/hugsql-result-fn :one [sym]
  'bigboard.db.core/result-one-snake->kebab)

(defmethod hugsql.core/hugsql-result-fn :* [sym]
  'bigboard.db.core/result-many-snake->kebab)

(defmethod hugsql.core/hugsql-result-fn :many [sym]
  'bigboard.db.core/result-many-snake->kebab)

(defn query
  "call conman/query directly if you don't want & args switched to snake case"
  [conn queries id & args]
  (let [args (map #(if (map? %)
                     (transform-keys ->snake_case_keyword %)
                     %) args)]
    (apply (partial conman/query conn queries id) args)))

(defn query-embedded
  "query embedded db"
  ([id & args]
   (apply (partial query *db* queries id) args))
  ([id]
   (query *db* queries id {})))
