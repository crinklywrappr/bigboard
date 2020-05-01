(ns bigboard.sched-form
  (:require
   [bigboard.sui :refer [component]]
   [bigboard.db :as db]
   [reagent.core :as r]
   [ajax.core :refer [GET POST DELETE]]
   [clojure.string :as s]))

(db/request-reporters)

(def reporter (r/atom nil))

;; The reporters element is a bit complex
(defn reporters
  [curr-reporter]
  (let [select (component "Form" "Select")
        group (component "Form" "Group")
        button (component "Form" "Button")
        _ (reset! reporter curr-reporter)]
    [:> group
     [:> select
      {:error @db/reporters-err
       :label {:children "Reporter"
               :htmlFor "reporter"}
       :options @db/reporters
       :placeholder "Choose Reporter"
       :required true
       :search true
       :searchInput {:id "reporter"}
       :defaultValue curr-reporter
       :onChange (fn [_ x] (reset! reporter (.-value x)))}]
     [:> button
      {:icon "refresh"
       :label "⠀" ;; empty unicode character
       :onClick #(db/request-reporters)}]]))

(def cron-err (r/atom nil))
(def cron-sim (r/atom nil))

(defn to-phrase [from to]
  (.to from to))

(defn from-phrase [from to]
  (.from from to))

(defn format [dt]
  (.format dt "M/D/YY h:mma"))

(defn localdt->moment [dt]
  (-> dt .-rep (js/moment "YYYY-MM-DDThh:mm:ss.SSS")))

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

(defn simulate
  ([s]
   (GET (str "/simulate?cron=" s)
        {:handler #(do
                     (reset! cron-sim %)
                     (reset! cron-err nil))
         :error-handler #(do
                           (reset! cron-sim nil)
                           (reset! cron-err (:response %)))}))
  ([]
   (let [list (component "List")
         item (component "List" "Item")]
     [:> list {:style {:color "gray" :font-style "italic"}}
      (doall
       (let [from (localdt->moment
                   (:from @cron-sim))]
         (for [to (:sims @cron-sim)
               :let [to (localdt->moment to)]]
           ^{:key (format to)}
           [:> item
            (str (to-phrase from to) " @ " (format to))])))])))

(defn cron [curr-cron]
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
         :defaultValue curr-cron
         :action (r/as-element
                  [:> button
                   {:onClick
                    #(simulate
                      (-> js/document
                          (.getElementById "cron")
                          .-value))}
                   "Simulate"])}])}
     [:> content
      [cron-help]]]))

(def story-err (r/atom nil))
(def new-story (r/atom nil))

(defn story-instructions []
  (let [list (component "List")
        item (component "List" "Item")]
    [:> list {:style {:color "gray" :font-style "italic"}}
     [:> item (str "Generate story at $BIGBOARD__STORIES/" @new-story)]
     [:> item (str "Generate problem stories at $BIGBOARD__STORIES/" @new-story ".prob")]
     [:> item (str "Generate error messages at $BIGBOARD__STORIES/" @new-story ".err")]]))

(defn story-help []
  (let [segment (component "Segment")
        grid (component "Grid")
        row (component "Grid" "Row")
        column (component "Grid" "Column")
        divider (component "Divider")
        header (component "Header")]
    [:> segment {:basic true}
     [:> grid
      {:divided true
       :relaxed true}
      [:> row {:columns 2}
       [:> column {:textAlign "center"}
        [:> header {:as "h4"} "Table"]
        [:p "A Story containing a comma-delimited table."]]
       [:> column {:textAlign "center"}
        [:> header {:as "h4"} "Vega-lite"]
        [:p "A Story containing a interactive charts & graphs"]]]]]))

(defn check-story-input [event data]
  (let [val (.-value data)]
    (reset! new-story val)
    (if (or (s/ends-with? val ".json")
            (s/ends-with? val ".csv"))
      (reset! story-err nil)
      (reset! story-err "File must end with .json or .csv"))))

(defn stories [curr-story]
  (let [input (component "Form" "Input")
        popup (component "Popup")
        content (component "Popup" "Content")
        _ (reset! new-story curr-story)]
    (fn []
      [:> popup
       {:flowing true
        :hoverable true
        :trigger
        (r/as-element
         [:> input
          {:label "Story"
           :placeholder "Filename which the reporter will produce, sans path"
           :maxLength 255
           :required true
           :id "story"
           :error @story-err
           :defaultValue curr-story
           :onChange check-story-input}])}
       [:> content
        [story-help]]])))

(def name-err (r/atom nil))
(def contact-err (r/atom nil))
(def short-desc-err (r/atom nil))

;; expects a map with header and troubleshoot keys
(def schedule-err
  (r/atom nil))

(defn sched-form
  ([name]
   (let [form (component "Form")
         input (component "Form" "Input")
         textarea (component "Form" "TextArea")
         list (component "List")
         item (component "List" "Item")
         button (component "Button")
         message (component "Message")
         header (component "Message" "Header")
         ;; this is necessary in order to force
         ;; this component to re-render
         schedule (some
                   #(when (= (:name %) name) %)
                   @db/schedules)]
     [:> form
      [:> input {:label "Name (28 chars)"
                 :placeholder "Name"
                 :maxLength 28
                 :required true
                 :id "name"
                 :error @name-err
                 :disabled (-> schedule :name some?)
                 :defaultValue (:name schedule)}]
      [stories (:story schedule)]
      (when (seq @new-story)
        [story-instructions])
      [:> input {:label "Contact"
                 :placeholder "Who to contact if something goes wrong"
                 :maxLength 50
                 :required true
                 :id "contact"
                 :error @contact-err
                 :defaultValue (:contact schedule)}]
      [:> textarea {:label "Short description (140 chars)"
                    :placeholder "Description which will appear on the front page"
                    :maxLength 140
                    :required true
                    :id "short-desc"
                    :error @short-desc-err
                    :defaultValue (:short-desc schedule)}]
      [:> textarea {:label "Long description"
                    :placeholder "More details which will go on the detail page"
                    :id "long-desc"
                    :defaultValue (:long-desc schedule)}]
      [:> textarea {:label "Troubleshooting"
                    :placeholder "What steps should be taken to resolve this issue, if reported?"
                    :id "trouble"
                    :defaultValue (:trouble schedule)}]
      [reporters (:reporter schedule)]
      [cron (:cron schedule)]
      (when (seq @cron-sim)
        [simulate])
      (when (some? @schedule-err)
        [:> message {:negative true}
         [:> header (:header @schedule-err)]
         [:p (:troubleshoot @schedule-err)]])]))
  ([] (sched-form {})))

(defn validate
  [{:keys [name story contact short-desc reporter cron]}]
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

(defn build-schedule-from-input []
  (let [val (fn [node] (or (.-value node) (.getAttribute node "value")))
        req (array-seq (.querySelectorAll js/document "[required]"))
        input (zipmap
               (map (comp keyword #(.-id %)) req)
               (map val req))]
    (assoc
     input
     :story @new-story
     :reporter @reporter
     :trouble (.-value
               (.getElementById
                js/document "trouble"))
     :long-desc (.-value
                 (.getElementById
                  js/document "long-desc")))))
