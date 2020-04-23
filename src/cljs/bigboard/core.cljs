(ns bigboard.core
  (:require
   [cljsjs.semantic-ui-react]
   [goog.object]
   [reagent.core :as r]
   [reagent.dom :as rdom]
   [goog.events :as events]
   [goog.history.EventType :as HistoryEventType]
   [markdown.core :refer [md->html]]
   [bigboard.ajax :as ajax]
   [ajax.core :refer [GET POST DELETE]]
   [reitit.core :as reitit]
   [clojure.string :as string]
   [cljsjs.moment]
   [bigboard.ws :as ws])
  (:import goog.History))

(defonce session
  (r/atom
   {:page :home}))

(def semantic-ui js/semanticUIReact)

(defn component
  "Get a component from sematic-ui-react:
    (component \"Button\")
    (component \"Menu\" \"Item\")"
  [k & ks]
  (if (seq ks)
    (apply goog.object/getValueByKeys semantic-ui k ks)
    (goog.object/get semantic-ui k)))

(defn nav-link [uri title page]
  (let [item (component "Menu" "Item")]
    [:> item
     {:href uri
      :active (str (= page (:page @session)))}
     title]))

(defn navbar []
  (let [container (component "Container")
        menu (component "Menu")]
    [:> menu {:fixed "top" :inverted true :style {:height "60px"}}
     [:> container
      [:a.item.header {:href "/"} "bigboard"]
      [nav-link "/" "home" :home]]]))

;; expects a map with header and troubleshoot keys
(def top-err (r/atom nil))

(def schedules (r/atom nil))

;; TODO: more complicated comparator needed here (maybe???)
(defn request-schedules []
  (letfn [(created [m]
            (update m :created #(js/moment (.-rep %))))
          (cmp [a b]
            (if (.isBefore a b)
              1 -1))]
    (GET "/schedules"
         {:handler
          #(do
             (reset! schedules
                     (sort-by :created cmp (map created %)))
             (reset! top-err nil))
          :error-handler
          #(do
             (reset! top-err
                     {:header "Problem Loading Schedules"
                      :troubleshoot "Contact your System Administrator"})
             (reset! schedules nil))})))

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

(defn schedule-error [name]
  (merge-schedule name {:status :server-error}))

(defn request-schedule [name]
  (GET (str "/schedules?name=" name)
       {:handler #(replace-schedule (assoc % :name name))
        :error-handler #(schedule-error name)}))

;; reporters ui state management is complex
;; we request the file/file-err on page load
;; everytime the reporters select box is rendered we check for an error
;; but DONT check for or empty the file list
(def file-err (r/atom nil))
(def files (r/atom []))

(GET "/reporters"
     {:handler #(do
                  (reset! files (mapv (fn [f] {:key f :text f :value f}) %))
                  (reset! file-err nil))
      :error-handler #(do
                        (reset! file-err (:response %))
                        (reset! files nil))})

(defn reporters []
  (let [select (component "Form" "Select")
        _ (GET "/reporters"
               {:handler (constantly nil)
                :error-handler #(reset! file-err (:response %))})]
    [:> select
     {:id "reporter"
      :fluid true
      :error @file-err
      :label "Executable"
      :options @files
      :placeholder "Choose file"
      :required true
      :onChange (fn [_ x]
                  (.setAttribute
                   (.getElementById js/document "reporter")
                   "value" (.-value x)))}]))

(def cron-err (r/atom nil))
(def cron-sim (r/atom nil))

(defn from [format from to]
  (.from (js/moment to format) from format))

(defn cron
  ([x]
   (GET (str "/simulate?cron=" x)
        {:handler #(do
                     (reset! cron-sim %)
                     (reset! cron-err nil))
         :error-handler #(do
                           (reset! cron-sim nil)
                           (reset! cron-err (:response %)))}))
  ([]
   (let [input (component "Form" "Input")
         button (component "Button")]
     [:> input
      {:id "cron"
       :label "Cron"
       :placeholder "* * * * *"
       :maxLength 256
       :error @cron-err
       :required true
       :action (r/as-element
                [:> button
                 {:onClick #(cron
                             (-> js/document
                                 (.getElementById "cron")
                                 .-value))}
                 "Simulate"])}])))

(def name-err (r/atom nil))
(def story-err (r/atom nil))
(def contact-err (r/atom nil))
(def short-desc-err (r/atom nil))

;; expects a map with header and troubleshoot keys
(def add-schedule-err
  (r/atom nil))

(defn new-form []
  (let [form (component "Form")
        input (component "Form" "Input")
        textarea (component "Form" "TextArea")
        list (component "List")
        item (component "List" "Item")
        button (component "Button")
        message (component "Message")
        header (component "Message" "Header")
        _ (reset! cron-sim nil)
        _ (reset! cron-err nil)
        _ (reset! name-err nil)
        _ (reset! story-err nil)
        _ (reset! contact-err nil)
        _ (reset! short-desc-err nil)
        _ (reset! file-err nil)]
    (fn []
      [:> form
       [:> input {:label "Name (28 chars)"
                  :placeholder "Name"
                  :maxLength 28
                  :required true
                  :id "name"
                  :error @name-err}]
       [:> input {:label "Story"
                  :placeholder "Filename which the reporter will produce, sans path"
                  :maxLength 255
                  :required true
                  :id "story"
                  :error @story-err}]
       [:> input {:label "Contact"
                  :placeholder "Who to contact if something goes wrong"
                  :maxLength 50
                  :required true
                  :id "contact"
                  :error @contact-err}]
       [:> textarea {:label "Short description (140 chars)"
                     :placeholder "Description which will appear on the front page"
                     :maxLength 140
                     :required true
                     :id "short-desc"
                     :error @short-desc-err}]
       [:> textarea {:label "Long description"
                     :placeholder "More details which will go on the detail page"
                     :id "long-desc"}]
       [:> textarea {:label "Troubleshooting"
                     :placeholder "What steps should be taken to resolve this issue, if reported?"
                     :id "trouble"}]
       [reporters]
       [:> list {:size "small" :style {:color "gray" :font-style "italic"}}
        [:> item "minute (0-59)"]
        [:> item "hour (0-23)"]
        [:> item "day of month (1-31)"]
        [:> item "month (1-12)"]
        [:> item "day of week (1-7)"]
        [:> item "exact matches, alternation, ranges, repetition, shifted repetition, and * accepted"]]
       [cron]
       [:> list {:style {:color "gray" :font-style "italic"}}
        (doall
         (for [to (:sims @cron-sim)]
           ^{:key to}
           [:> item
            (from "YYYY-MM-DD hh:mm:ss"
                  (:from @cron-sim) to)]))]
       (when (some? @add-schedule-err)
         [:> message {:negative true}
          [:> header (:header @add-schedule-err)]
          [:p (:troubleshoot @add-schedule-err)]])])))

(defn validate [{:keys [name story contact short-desc reporter cron]}]
  (if (= name "")
    (reset! name-err "This field is required")
    (reset! name-err nil))
  (if (= story "")
    (reset! story-err "This field is required")
    (reset! story-err nil))
  (if (= contact "")
    (reset! contact-err "This field is required")
    (reset! contact-err nil))
  (if (= short-desc "")
    (reset! short-desc-err "This field is required")
    (reset! short-desc-err nil))
  (if (nil? reporter)
    (reset! file-err "This field is required")
    (reset! file-err nil))
  (if (= cron "")
    (reset! cron-err "This field is required")
    (reset! cron-err nil))
  (if (or (= name "") (= story "") (= contact "")
          (= short-desc "") (nil? reporter) (= cron ""))
    false true))

(def new-modal? (r/atom false))

(defn submit-button []
  (let [button (component "Button")]
    [:> button
     {:primary true
      :onClick
      (fn [& args]
        (let [val (fn [node] (or (.-value node) (.getAttribute node "value")))
              req (array-seq (.querySelectorAll js/document "[required]"))
              input (zipmap
                     (map (comp keyword #(.-id %)) req)
                     (map val req))]
          (when (validate input)
            (POST "/schedules"
                  {:params
                   (assoc input
                          :trouble (.-value
                                    (.getElementById
                                     js/document "trouble"))
                          :long-desc (.-value
                                      (.getElementById
                                       js/document "long-desc")))
                   :error-handler
                   (fn [resp]
                     (if (== (:status resp) 500)
                       (reset! add-schedule-err (:response resp))
                       (case (-> resp :response :reason)
                         :cron (reset! cron-err (-> resp :response :msg))
                         :name (reset! name-err (-> resp :response :msg))
                         :story (reset! story-err (-> resp :response :msg)))))
                   :handler
                   #(do
                      (reset! add-schedule-err nil)
                      (reset! new-modal? false)
                      (ws/send-transit-msg! {:cmd :refresh :element :schedules}))}))))}
     "Add"]))

(defn new-modal []
  (let [button (component "Button")
        icon (component "Icon")
        modal (component "Modal")
        header (component "Header")
        content (component "Modal" "Content")
        actions (component "Modal" "Actions")]
    [:> modal
     {:trigger (r/as-element
                [:> button {:class "icon left labeled"
                            :onClick #(reset! new-modal? true)}
                 [:> icon {:class "left plus"}]
                 "Schedule"])
      :size "tiny"
      :open @new-modal?
      :onClose #(reset! new-modal? false)}
     [:> header
      {:icon "plus"
       :content "New Schedule"}]
     [:> content
      [new-form]]
     [:> actions
      [submit-button]]]))

(defn delete-schedule [name]
  (DELETE
   "/schedules"
   {:params {:name name}
    :handler
    #(ws/send-transit-msg! {:cmd :refresh :element :schedules})
    :error-handler
    #(reset! top-err
             {:header (str "There was a problem unscheduling \"" name "\"")
              :troubleshoot "Contact your System Administrator"})}))

(defn delete-modal [name status]
  (let [group (component "Button" "Group")
        button (component "Button")
        or (component "Button" "Or")
        modal (component "Modal")
        header (component "Header")
        actions (component "Modal" "Actions")
        del? (r/atom false)]
    (fn []
      [:> modal
       {:size "tiny"
        :basic true
        :open @del?
        :onClose #(reset! del? false)
        :trigger
        (r/as-element
         [:> button {:basic true
                     :color "red"
                     :icon "delete"
                     :disabled (= status :running) 
                     :onClick #(reset! del? true)}])}
       [:> header
        {:icon "delete"
         :content (str "Unschedule \"" name "\"?")}]
       [:> actions
        [:> button
         {:color "red"
          :onClick #(do
                      (delete-schedule name)
                      (reset! del? false))}
         "Yes"]]])))

(defn get-card-color
  "Blue -> System administrator should troubleshoot schedule
  Green -> Normal operation
  Yellow -> Normal operation but the reporter found some problems
  Red -> Reporter exited uncleanly - a story *may* be available
  Gray -> Story is old - The reporter does not seem to be producing a story"
  [status]
  (cond
    (some (partial = status) [:mia :bad :no-story]) "blue"
    (some (partial = status) [:new :running :success]) "green"
    (= status :problem) "yellow"
    (= status :error) "red"
    (= status :stale) "gray"))

;; TODO: card color, other specialties based on status. next runtime.
(defn card [{:keys [name contact short-desc
                    cron reporter status]
             :as sched}]
  (let [card (component "Card")
        content (component "Card" "Content")
        header (component "Card" "Header")
        meta (component "Card" "Meta")
        desc (component "Card" "Description")
        button-group (component "Button" "Group")
        button (component "Button")
        icon (component "Icon")
        dimmer (component "Dimmer")
        dimmable (component "Dimmer" "Dimmable")
        loader (component "Loader")
        card-color (get-card-color status)]
    [:> card {:color card-color}
     [:> dimmable
      {:as content}
      (cond
        (= status :running)
        [:> dimmer
         {:active true
          :inverted true}
         [:> loader "Running"]]
        (= status :server-error)
        [:> dimmer
         {:active true
          :style {:backgroundColor "rgba(183, 28, 28, 0.85)"}}
         "Server Error"]
        :else [:> dimmer {:active false}])
      (when (some (partial = status) [:mia :bad :no-story])
        [:> icon {:style {:float "right"}
                  :size "big"
                  :name "exclamation"}])
      [:> header name]
      [:> meta contact]
      [:> desc short-desc]]
     [:> content {:extra true}
      [:> button-group {:class ["four"]}
       [delete-modal name status]
       [:> button {:basic true :color "yellow" :icon "edit"}]
       [:> button
        {:basic true
         :disabled (some (partial = status) [:mia :bad :running])
         :color "blue"
         :icon "terminal"
         :onClick #(POST "/trigger" {:params {:name name}})}]
       [:> button
        {:basic true
         :disabled (some (partial = status) [:mia :bad :no-story :new :running])
         :color "green"
         :icon "arrow right"}]]]]))

(defn cards []
  (let [group (component "Card" "Group")]
    [:> group {:itemsPerRow 4
               :stackable true
               :style {:margin-top "80px"}}
     (doall
      (for [c @schedules]
        ^{:key (:name c)}
        [card c]))]))

(defn home-page []
  (let [container (component "Container")
        message (component "Message")
        header (component "Message" "Header")
        _ (request-schedules)]
    (fn []
      [:> container {:style {:margin-top "100px"}}
       [new-modal]
       (when (some? @top-err)
         [:> message {:negative true}
          [:> header (:header @top-err)]
          [:p (:troubleshoot @top-err)]])
       [cards]])))

(def pages
  {:home #'home-page})

(defn page []
  [(pages (:page @session))])

;; -------------------------
;; Routes

(def router
  (reitit/router
    [["/" :home]]))

(defn match-route [uri]
  (->> (or (not-empty (string/replace uri #"^.*#" "")) "/")
       (reitit/match-by-path router)
       :data
       :name))
;; -------------------------
;; History
;; must be called after routes have been defined
(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
      HistoryEventType/NAVIGATE
      (fn [event]
        (swap! session assoc :page (match-route (.-token event)))))
    (.setEnabled true)))

;; -------------------------
;; Initialize app

(defn mount-components []
  (rdom/render [#'navbar] (.getElementById js/document "navbar"))
  (rdom/render [#'page] (.getElementById js/document "app")))

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
    :schedule (schedule-error (:name msg))
    (.debug js/console "Unknown error element: " element)))

(def x (atom nil))

(defn notification-handler [{:keys [cmd] :as msg}]
  (reset! x msg)
  (case cmd
    :refresh (refresh-handler msg)
    :merge (merge-handler msg)
    :replace (replace-handler msg)
    :error (error-handler msg)
    (.debug js/console ("Unknown command: " cmd))))

(defn init! []
  (ajax/load-interceptors!)
  (ws/make-websocket!
   (str "ws://" (.-host js/location) "/ws")
   notification-handler)
  (hook-browser-navigation!)
  (mount-components))
