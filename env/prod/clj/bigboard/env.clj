(ns bigboard.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[bigboard started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[bigboard has shut down successfully]=-"))
   :middleware identity})
