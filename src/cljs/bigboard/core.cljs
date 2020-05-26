(ns bigboard.core
  (:require
   [bigboard.sui :refer [component]]
   [bigboard.sched-form :as sf]
   [bigboard.story :as story]
   [goog.object]
   [goog.functions :refer [debounce]]
   [reagent.core :as r]
   [reagent.dom :as rdom]
   [goog.events :as events]
   [goog.history.EventType :as HistoryEventType]
   [bigboard.ajax :as ajax]
   [ajax.core :refer [GET POST DELETE PUT]]
   [reitit.core :as reitit]
   [clojure.string :as string]
   [cljsjs.moment]
   [bigboard.ws :as ws]
   [bigboard.db :as db])
  (:import goog.History))

;; (set! *warn-on-infer* true)

(defonce session
  (r/atom
   {:page :home
    :params {}}))

(defn nav-link [uri title page]
  (let [item (component "Menu" "Item")]
    [:> item
     (if (some? uri)
       {:href uri
        :active (str (= page (:page @session)))}
       {:active (str (= page (:page @session)))})
     title]))

(defn navbar []
  (let [container (component "Container")
        menu (component "Menu")]
    [:> menu {:fixed "top" :inverted true :style {:height "60px"}}
     [:> container
      [:a.item.header {:href "#home"} "bigboard"]
      (when (= (:page @session) :story)
        [nav-link nil (-> @session :params :schedule) :story])]]))

;; --- begin: new modal ---

(def new-modal? (r/atom false))

(defn close-new-modal [& args]
  (reset! sf/schedule-err nil)
  (reset! new-modal? false)
  (reset! sf/cron-sim nil)
  (reset! sf/cron-err nil)
  (reset! sf/name-err nil)
  (reset! sf/story-err nil)
  (reset! sf/new-story nil)
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
             (let [msg (-> resp :response :msg)]
               (case (-> resp :response :reason)
                 :cron (reset! sf/cron-err msg)
                 :reporter (reset! db/reporters-err msg)
                 :name (reset! sf/name-err msg)
                 :story (reset! sf/story-err msg)))))}))

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

(defn close-update-modal
  [open?]
  (fn [& args]
    (reset! open? false)
    (reset! sf/schedule-err nil)
    (reset! sf/cron-sim nil)
    (reset! sf/cron-err nil)
    (reset! sf/name-err nil)
    (reset! sf/story-err nil)
    (reset! sf/new-story nil)
    (reset! sf/contact-err nil)
    (reset! sf/short-desc-err nil)
    (reset! db/reporters-err nil)))

(defn update-schedule
  [close-fn schedule]
  (PUT "/schedules"
       {:params schedule
        :handler
        #(do
           (ws/send-transit-msg! {:cmd :refresh :element :schedules})
           (close-fn))
        :error-handler
        (fn [resp]
          (if (== (:status resp) 500)
            (reset! sf/schedule-err (:response resp))
            (let [msg (-> resp :response :msg)]
              (case (-> resp :response :reason)
                :cron (reset! sf/cron-err msg)
                :reporter (reset! db/reporters-err msg)
                :story (reset! sf/story-err msg)))))}))

(defn update-button
  [close-fn]
  (let [button (component "Button")]
    [:> button
     {:primary true
      :onClick
      (fn [& args]
        (let [speculative-schedule (sf/build-schedule-from-input)]
          (when (sf/validate speculative-schedule)
            (update-schedule close-fn speculative-schedule))))}
     "Update"]))

(defn update-modal
  [name]
  (let [button (component "Button")
        icon (component "Icon")
        modal (component "Modal")
        header (component "Header")
        content (component "Modal" "Content")
        actions (component "Modal" "Actions")
        open? (r/atom false)
        close (close-update-modal open?)]
    (fn []
      [:> modal
       {:trigger (r/as-element
                  [:> button
                   {:basic true
                    :color "yellow"
                    :icon "edit"
                    :onClick #(reset! open? true)}])
        :size "tiny"
        :open @open?
        :onClose close}
       [:> header
        {:icon "edit"
         :content "Update"}]
       [:> content
        [sf/sched-form name]]
       [:> actions
        [update-button close]]])))

;; --- end: update modal

;; --- begin: searchbar

(def searching? (r/atom false))
(def matching-schedules (r/atom []))

(defn search [schedules s]
  (js/setTimeout
   (fn []
     (reset! searching? false))
   1000)
  (reset! searching? true))

(add-watch
 db/schedules :search
 (fn [_ _ _ new]
   (search new (.val (js/$ "#searchbar")))))

(defn searchbar []
  (let [search (component "Search")]
    [:> search
     {:style
      {:width "100%"}
      :id "searchbar"
      :showNoResults false
      :loading @searching?
      :onSearchChange
      (debounce
       (fn [e val]
         (bigboard.core/search
          @db/schedules val))
       500)}]))

;; --- end: searchbar

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
    (some (partial = status) [:new :running :success]) "green"
    (= status :problem) "yellow"
    (= status :stale) "grey"
    (= status :error) "red"
    (some (partial = status) [:mia :bad :no-story]) "blue"))

(defn read-story
  [{:keys [status name story] :as schedule}]
  (let [button (component "Button")]
    (cond
      (some (partial = status) [:new :running])
      [:> button
       {:basic true
        :disabled true
        :color "green"
        :icon "arrow right"}]
      (some (partial = status) [:mia :bad :no-story])
      (let [modal (component "Modal")
            header (component "Header")
            content (component "Modal" "Content")
            open? (r/atom false)]
        (fn []
          [:> modal
           {:size "tiny"
            :basic true
            :open @open?
            :onClose #(reset! open? false)
            :trigger
            (r/as-element
             [:> button
              {:basic true
               :color "green"
               :icon "arrow right"
               :onClick #(reset! open? true)}])}
           [:> header
            {:icon "info"
             :content (str "\"" name "\" status: \"" status "\"")}]
           [:> content
            [:p (case status
                  :mia "The reporter is missing from the filesystem"
                  :bad "The cron configuration is incorrect"
                  :no-story "The Story has not been generated")]]]))
      (= status :error)
      (let [modal (component "Modal")
            header (component "Header")
            content (component "Modal" "Content")
            msg (r/atom "")
            open? (r/atom false)]
        (fn []
          [:> modal
           {:size "tiny"
            :basic true
            :open @open?
            :onClose #(reset! open? false)
            :trigger
            (r/as-element
             [:> button
              {:basic true
               :color "green"
               :icon "arrow right"
               :onClick
               (fn [& args]
                 (GET "/error-story"
                      {:params {:story story}
                       :handler
                       #(do
                          (reset! msg %)
                          (reset! open? true))
                       :error-handler
                       #(do
                          (reset! msg (str "No story generated.  Exit code is: "
                                           (:exit-code schedule)))
                          (reset! open? true))}))}])}
           [:> header
            {:icon "info"
             :content (str "\"" name "\" status: \"" status "\"")}]
           [:> content
            [:p @msg]]]))
      :else
      [:> button
       {:basic true
        :href (str "#story/" name)
        :onClick #(do (reset! story/story-data nil)
                      (reset! story/story-err nil))
        :color "green"
        :icon "arrow right"}])))

(defn get-duration [start finish]
  (let [m (.diff finish start "minutes")
        s (.diff finish start "seconds")]
    (if (< s 1)
      ["Blazing" "Under a second"]
      (if (< m 1)
        ["Fast" (str s " seconds")]
        ["Slow" (str m " minutes")]))))

(defn runtimes
  [{:keys [status last-triggered last-finished next-run]}]
  (let [table (component "Table")
        body (component "Table" "Body")
        row (component "Table" "Row")
        cell (component "Table" "Cell")
        header (component "Header")
        list (component "List")
        item (component "List" "Item")
        item-icon (component "List" "Icon")
        item-content (component "List" "Content")
        item-header (component "List" "Header")
        nr (sf/localdt->moment next-run)]
    (if (= status :new)
      [:> table {:basic "very" :celled "true"}
       [:> body
        [:> row
         [:> cell
          [:> header {:size "huge"} "?"]]
         [:> cell
          [:> list {:size "small" :style {:color "gray" :font-style "italic"}}
           [:> item
            [:> item-header (sf/format nr)]
            (sf/to-phrase (js/moment) nr)]]]]
        [:> row
         [:> cell
          [:> header {:size "huge"} "?"]]
         [:> cell]]]]
      (let [lt (sf/localdt->moment last-triggered)
            lf (sf/localdt->moment last-finished)
            [cat desc] (get-duration lt lf)]
        [:> table {:basic "very" :celled "true"}
         [:> body
          [:> row
           [:> cell
            [:> list {:size "small" :style {:color "gray" :font-style "italic"}}
             [:> item
              [:> item-content
               [:> item-header (sf/format lt)]
               (sf/from-phrase lt (js/moment))]]]]
           [:> cell
            [:> list {:size "small" :style {:color "gray" :font-style "italic"}}
             [:> item
              [:> item-header (sf/format nr)]
              (sf/to-phrase (js/moment) nr)]]]]
          [:> row
           [:> cell
            [:> list {:size "small" :style {:color "gray" :font-style "italic"}}
             [:> item [:> item-header cat] desc]]]
           [:> cell]]]]))))

(def detail-schedule (r/atom nil))

(defn card
  [{:keys [name contact short-desc
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
        loader (component "Loader")]
    [:> card {:color (get-card-color status)}
     [:> dimmable
      {:as content
       :onMouseEnter #(reset! detail-schedule name)
       :onMouseLeave #(reset! detail-schedule nil)}
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
        (= status :not-found)
        [:> dimmer
         {:active true
          :style {:backgroundColor "rgba(183, 28, 28, 0.85)"}}
         "Not Found"]
        (= @detail-schedule name)
        [:> dimmer
         {:active true
          :inverted true}
         [runtimes sched]]
        :else [:> dimmer {:active false}])
      (cond
        (some (partial = status) [:new :success])
        [:> icon {:style {:float "right"}
                  :size "big"
                  :name "check"
                  :color "green"}]
        (= status :problem)
        [:> icon {:style {:float "right"}
                  :size "big"
                  :name "exclamation"
                  :color "yellow"}]
        (= status :stale)
        [:> icon {:style {:float "right"}
                  :size "big"
                  :name "bug"
                  :color "grey"}]
        (some (partial = status) [:mia :bad :no-story])
        [:> icon {:style {:float "right"}
                  :size "big"
                  :name "exclamation"
                  :color "blue"}]
        (= status :error)
        [:> icon {:style {:float "right"}
                  :size "big"
                  :name "bomb"
                  :color "red"}])
      [:> header name]
      [:> meta contact]
      [:> desc short-desc]]
     [:> content
      {:extra "true"
       :class "card-bottom"
       :style {:max-height "fit-content"}}
      [:> button-group {:class ["four"]}
       [delete-modal name status]
       [update-modal name]
       [:> button
        {:basic true
         :disabled (some (partial = status) [:mia :bad :running])
         :color "blue"
         :icon "terminal"
         :onClick #(POST "/trigger" {:params {:name name}})}]
       [read-story sched]]]]))

(defn cards []
  (let [group (component "Card" "Group")]
    [:> group {:itemsPerRow 4
               :stackable true
               :style {:margin-top "80px"}}
     (doall
      (for [s @db/schedules]
        ^{:key (:name s)}
        [card s]))]))

(db/request-schedules)

(defn home-page
  [params]
  (let [container (component "Container")
        message (component "Message")
        header (component "Message" "Header")
        button (component "Button")]
    [:> container {:style {:margin-top "100px"}}
     [:div
      [new-modal]
      [:div
       {:style
        {:display "inline-block"
         :width "60%" :float "right"}}
       [searchbar]]]
     (when (some? @db/schedules-err)
       [:> message {:negative true}
        [:> header (:header @db/schedules-err)]
        [:p (:troubleshoot @db/schedules-err)]])
     [cards]]))

(def pages
  {:home #'home-page
   :home2 #'home-page
   :story #'story/story-page})

(defn page []
  [(pages (:page @session)) (:params @session)])

;; -------------------------
;; Routes

(def router
  (reitit/router
   [["/" :home]
    ["home" :home2]
    ["story/:schedule" :story]]))

(defn match-route [uri]
  (->> (or (not-empty (string/replace uri #"^.*#" "")) "/")
       (reitit/match-by-path router)))

;; -------------------------
;; History
;; must be called after routes have been defined
(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
      HistoryEventType/NAVIGATE
      (fn [event]
        (if-let [match (match-route (.-token event))]
          (swap! session assoc
                 :page (-> match :data :name)
                 :params (:path-params match)))))
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
