(ns bigboard.db
  (:require
   [bigboard.ws :as ws]
   [reagent.core :as r]
   [ajax.core :refer [GET POST DELETE]]))

;; expects a string
(def reporters-err (r/atom nil))
(def reporters (r/atom nil))

;; expects a map with header & troubleshoot keys
(def schedules-err (r/atom nil))
(def schedules (r/atom nil))

(defn delete-schedule [name]
  (DELETE
   "/schedules"
   {:params {:name name}
    :handler
    #(ws/send-transit-msg! {:cmd :refresh :element :schedules})
    :error-handler
    #(reset! schedules-err
             {:header (str "There was a problem unscheduling \"" name "\"")
              :troubleshoot "Contact your System Administrator"})}))

(defn request-reporters []
  (GET "/reporters"
       {:handler
        #(do
           (reset! reporters (mapv (fn [f] {:key f :text f :value f}) %))
           (reset! reporters-err nil))
        :error-handler
        #(do
           (reset! reporters nil)
           (reset! reporters-err (:response %)))}))

(defn request-schedules []
  (letfn [(created [m]
            (update m :created #(js/moment (.-rep %))))
          (cmp [a b]
            (if (.isBefore a b)
              1 -1))]
    (GET "/schedules"
         {:handler
          #(do
             (reset! schedules (sort-by :created cmp (map created %)))
             (reset! schedules-err nil))
          :error-handler
          #(do
             (reset! schedules nil)
             (reset!
              schedules-err
              {:header "Problem Loading Schedules"
               :troubleshoot "Contact your System Administrator"}))})))

(defn merge-schedule [name delta]
  (swap!
   schedules
   (fn [schedules]
     (map
      #(if (= (:name %) name)
         (merge % delta)
         %)
      schedules))))

(defn replace-schedule [{:keys [name] :as new}]
  (swap!
   schedules
   (fn [schedules]
     (map
      #(if (= (:name %) name)
         new %)
      schedules))))

(defn schedule-error [name status]
  (merge-schedule name
                  {:status (if (= status 404)
                             :not-found
                             :server-error)}))

(defn request-schedule [name]
  (GET (str "/schedules?name=" name)
       {:handler #(replace-schedule (assoc % :name name))
        :error-handler #(schedule-error name (:status %))}))

(defn refresh-handler [{:keys [element] :as msg}]
  (case element
    :schedules (request-schedules)
    :schedule (request-schedule (:name msg))
    (.debug js/console "Unknown refresh element: " element)))

(defn merge-handler [{:keys [element] :as msg}]
  (case element
    :schedule (merge-schedule (:name msg) (:delta msg))
    (.debug js/console "Unknown merge element: " element)))

(defn replace-handler [{:keys [element] :as msg}]
  (case element
    :schedule (replace-schedule (:new msg))
    (.debug js/console "Unknown replace element: " element)))

(defn error-handler [{:keys [element] :as msg}]
  (case element
    :schedule (schedule-error (:name msg) 500)
    (.debug js/console "Unknown error element: " element)))

(defn notification-handler [{:keys [cmd] :as msg}]
  (case cmd
    :refresh (refresh-handler msg)
    :merge (merge-handler msg)
    :replace (replace-handler msg)
    :error (error-handler msg)
    (.debug js/console ("Unknown command: " cmd))))
