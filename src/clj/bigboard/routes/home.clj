(ns bigboard.routes.home
  (:require
   [bigboard.layout :as layout]
   [bigboard.db.model :as db]
   [clojure.java.io :as io]
   [bigboard.middleware :as middleware]
   [ring.util.response]
   [ring.util.http-response :as response]))

(defn home-page [request]
  (layout/render request "home.html"))

(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/" {:get home-page}]])
