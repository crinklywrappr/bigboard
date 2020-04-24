(ns bigboard.core
  (:require
   [bigboard.sui :refer [component]]
   [bigboard.sched-form :as sf]
   [goog.object]
   [reagent.core :as r]
   [reagent.dom :as rdom]
   [goog.events :as events]
   [goog.history.EventType :as HistoryEventType]
   [markdown.core :refer [md->html]]
   [bigboard.ajax :as ajax]
   [ajax.core :refer [GET POST DELETE PUT]]
   [reitit.core :as reitit]
   [clojure.string :as string]
   [cljsjs.moment]
   [bigboard.ws :as ws]
   [bigboard.db :as db])
  (:import goog.History))

(defonce session
  (r/atom
   {:page :home}))

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

;; --- begin: new modal ---

(def new-modal? (r/atom false))

(defn close-new-modal [& args]
  (reset! sf/schedule-err nil)
  (reset! new-modal? false)
  (reset! sf/cron-sim nil)
  (reset! sf/cron-err nil)
  (reset! sf/name-err nil)
  (reset! sf/story-err nil)
  (reset! sf/contact-err nil)
  (reset! sf/short-desc-err nil)
  (reset! db/reporters-err nil))

(defn add-schedule [new-schedule]
  (POST "/schedules"
        {:params new-schedule
         :handler
         #(do
            (ws/send-transit-msg! {:cmd :refresh :element :schedules})
            (close-new-modal))
         :error-handler
         (fn [resp]
           (if (== (:status resp) 500)
             (reset! sf/schedule-err (:response resp))
             (case (-> resp :response :reason)
               :cron (reset! sf/cron-err (-> resp :response :msg))
               :name (reset! sf/name-err (-> resp :response :msg))
               :story (reset! sf/story-err (-> resp :response :msg)))))}))

(defn add-button []
  (let [button (component "Button")]
    [:> button
     {:primary true
      :onClick
      (fn [& args]
        (let [speculative-schedule (sf/build-schedule-from-input)]
          (when (sf/validate speculative-schedule)
            (add-schedule speculative-schedule))))}
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
      :onClose close-new-modal}
     [:> header
      {:icon "plus"
       :content "New Schedule"}]
     [:> content
      [sf/sched-form]]
     [:> actions
      [add-button]]]))

;; --- end: new modal ---

;; --- begin: update modal ---

(def update-modal? (r/atom false))

(defn close-update-modal [& args]
  (reset! sf/schedule-err nil)
  (reset! update-modal? false)
  (reset! sf/cron-sim nil)
  (reset! sf/cron-err nil)
  (reset! sf/name-err nil)
  (reset! sf/story-err nil)
  (reset! sf/contact-err nil)
  (reset! sf/short-desc-err nil)
  (reset! db/reporters-err nil))

(defn update-schedule [schedule]
  (PUT "/schedules"
       {:params schedule
        :handler
        #(do
           (ws/send-transit-msg! {:cmd :refresh :element :schedules})
           (close-update-modal))
        :error-handler
        (fn [resp]
          (if (== (:status resp) 500)
            (reset! sf/schedule-err (:response resp))
            (case (-> resp :response :reason)
              :cron (reset! sf/cron-err (-> resp :response :msg))
              :story (reset! sf/story-err (-> resp :response :msg)))))}))

(defn update-button []
  (let [button (component "Button")]
    [:> button
     {:primary true
      :onClick
      (fn [& args]
        (let [speculative-schedule (sf/build-schedule-from-input)]
          (when (sf/validate speculative-schedule)
            (update-schedule speculative-schedule))))}
     "Update"]))

(defn update-modal [schedule]
  (let [button (component "Button")
        icon (component "Icon")
        modal (component "Modal")
        header (component "Header")
        content (component "Modal" "Content")
        actions (component "Modal" "Actions")]
    [:> modal
     {:trigger (r/as-element
                [:> button
                 {:basic true
                  :color "yellow"
                  :icon "edit"
                  :onClick #(reset! update-modal? true)}])
      :size "tiny"
      :open @update-modal?
      :onClose close-update-modal}
     [:> header
      {:icon "edit"
       :content "Update"}]
     [:> content
      [sf/sched-form schedule]]
     [:> actions
      [update-button]]]))

;; --- end: update modal

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
                      (db/delete-schedule name)
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
       [update-modal sched]
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
      (for [c @db/schedules]
        ^{:key (:name c)}
        [card c]))]))

(defn home-page []
  (let [container (component "Container")
        message (component "Message")
        header (component "Message" "Header")
        _ (db/request-schedules)]
    (fn []
      [:> container {:style {:margin-top "100px"}}
       [new-modal]
       (when (some? @db/schedules-err)
         [:> message {:negative true}
          [:> header (:header @db/schedules-err)]
          [:p (:troubleshoot @db/schedules-err)]])
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

(defn init! []
  (ajax/load-interceptors!)
  (ws/make-websocket!
   (str "ws://" (.-host js/location) "/ws")
   db/notification-handler)
  (hook-browser-navigation!)
  (mount-components))
