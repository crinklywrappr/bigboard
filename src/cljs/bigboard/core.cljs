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
   [bigboard.ws :as ws]
   [bigboard.db :as db])
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

(db/request-reporters)

(defn reporters []
  (let [select (component "Form" "Select")
        group (component "Form" "Group")
        button (component "Form" "Button")]
    [:div
     ;; Using label correctly screws up
     ;; the refresh button alignment.
     [:label
      {:for "reporters"
       :style
       {:fontSize "13px"
        :fontWeight 700
        :lineHeight "18.2px"
        :marginButton "4px"
        :color "rgba(0,0,0,0.87)"}}
      "Reporters"]
     [:> group
      [:> select
       {:fluid true
        :error @db/reporters-err
        ;; :label {:children "Reporter"
        ;;         :htmlFor "reporter"}
        :options @db/reporters
        :placeholder "Choose Reporter"
        :required true
        :search true
        :searchInput {:id "reporter"}
        :onChange (fn [_ x]
                    (.setAttribute
                     (.getElementById js/document "reporter")
                     "value" (.-value x)))}]
      [:> button
       {:icon "refresh"
        :onClick #(db/request-reporters)}]]]))

(def cron-err (r/atom nil))
(def cron-sim (r/atom nil))

(defn from [format from to]
  (.from (js/moment to format) from format))

(defn cron-help []
  (let [list (component "List")
        item (component "List" "Item")
        item-header (component "List" "Header")
        segment (component "Segment")
        grid (component "Grid")
        row (component "Grid" "Row")
        column (component "Grid" "Column")
        divider (component "Divider")
        header (component "Header")]
    [:> segment {:basic true}
     [:> grid
      {:divided true
       :relaxed true}
      [:> row {:columns 3}
       [:> column {:textAlign "center"}
        [:> header {:as "h4"} "Fields (in order)"]
        [:> list {:size "small" :style {:color "gray" :font-style "italic"}}
         [:> item
          [:> item-header "Minute"]
          "Range: 0-59"]
         [:> item
          [:> item-header "Hour"]
          "Range: 0-23"]
         [:> item
          [:> item-header "Day of month"]
          "Range: 1-31"]
         [:> item
          [:> item-header "Month"]
          "Range: 1-12"]
         [:> item
          [:> item-header "Day of week"]
          "Range: 1-7 or SUN-SAT"]]]
       [:> column {:textAlign "center"}
        [:> header {:as "h4"} "Field types"]
        [:> list {:size "small" :style {:color "gray" :font-style "italic"}}
         [:> item
          [:> item-header "Exact matches"]
          "3"]
         [:> item
          [:> item-header "Alternation"]
          "3,10,7"]
         [:> item
          [:> item-header "Ranges"]
          "1-5"]
         [:> item
          [:> item-header "Repetition"]
          "/5"]
         [:> item
          [:> item-header "Shifted Repetition"]
          "2/5 or -2/5"]
         [:> item
          [:> item-header "Star"]
          "*"]]]
       [:> column {:textAlign "center"}
        [:> header {:as "h4"} "Examples"]
        [:> list {:size "small" :style {:color "gray" :font-style "italic"}}
         [:> item
          [:> item-header "0 0 * * *"]
          "12am each day"]
         [:> item
          [:> item-header "30 0 * * *"]
          "12:30am each day"]
         [:> item
          [:> item-header "/5 * * * *"]
          "Every 5 minutes"]
         [:> item
          [:> item-header "0 0,12 * * SAT,SUN"]
          "Twice a day on weekends"]
         [:> item
          [:> item-header "0 12 * * MON-FRI"]
          "Weekdays at noon"]]]]]]))

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
         button (component "Button")
         popup (component "Popup")
         content (component "Popup" "Content")]
     [:> popup
      {:wide "very"
       :flowing true
       :hoverable true
       :trigger
       (r/as-element
        [:> input
         {:id "cron"
          :label "Cron"
          :placeholder "* * * * *"
          :maxLength 256
          :error @cron-err
          :required true
          :action (r/as-element
                   [:> button
                    {:onClick
                     #(cron
                       (-> js/document
                           (.getElementById "cron")
                           .-value))}
                    "Simulate"])}])}
      [:> content
       [cron-help]]])))

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
        header (component "Message" "Header")]
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
    (reset! db/reporters-err "This field is required")
    (reset! db/reporters-err nil))
  (if (= cron "")
    (reset! cron-err "This field is required")
    (reset! cron-err nil))
  (if (or (= name "") (= story "") (= contact "")
          (= short-desc "") (nil? reporter) (= cron ""))
    false true))

(def new-modal? (r/atom false))

(defn close-modal [& args]
  (reset! add-schedule-err nil)
  (reset! new-modal? false)
  (reset! cron-sim nil)
  (reset! cron-err nil)
  (reset! name-err nil)
  (reset! story-err nil)
  (reset! contact-err nil)
  (reset! short-desc-err nil)
  (reset! db/reporters-err nil))

(defn build-schedule-from-input []
  (let [val (fn [node] (or (.-value node) (.getAttribute node "value")))
        req (array-seq (.querySelectorAll js/document "[required]"))
        input (zipmap
               (map (comp keyword #(.-id %)) req)
               (map val req))]
    (assoc
     input
     :trouble (.-value
               (.getElementById
                js/document "trouble"))
     :long-desc (.-value
                 (.getElementById
                  js/document "long-desc")))))

(defn submit-button []
  (let [button (component "Button")]
    [:> button
     {:primary true
      :onClick
      (fn [& args]
        (let [speculative-schedule (build-schedule-from-input)]
          (when (validate speculative-schedule)
            (db/add-schedule
             speculative-schedule
             {:handler close-modal
              :error-handler
              (fn [resp]
                (if (== (:status resp) 500)
                  (reset! add-schedule-err (:response resp))
                  (case (-> resp :response :reason)
                    :cron (reset! cron-err (-> resp :response :msg))
                    :name (reset! name-err (-> resp :response :msg))
                    :story (reset! story-err (-> resp :response :msg)))))}))))}
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
      :onClose close-modal}
     [:> header
      {:icon "plus"
       :content "New Schedule"}]
     [:> content
      [new-form]]
     [:> actions
      [submit-button]]]))

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
