(ns bigboard.story
  (:require
   [goog.object :as g]
   [reagent.core :as r]
   [bigboard.sui :refer [component]]
   [bigboard.db :as db]
   [bigboard.sched-form :refer [localdt->moment
                                from-phrase
                                format]]
   [ajax.core :refer [GET]]
   [clojure.string :as s]))

(defn table-render []
  (let [container (component "Container")]
    [:> container
     [:table.ui.celled.table
      {:ref "main" :width "100%"}]]))

(defn decorate
  [row data idx]
  (when-let [class (g/get data "bigboard-story-class")]
    (.addClass (js/$ row) class)))

(defn table-did-mount
  [columns data]
  (fn [^js/React.Component this]
    (.DataTable
     (-> this .-refs .-main js/$)
     (clj->js
      {:columns (remove
                 #(= (:data %) :bigboard-story-class)
                 columns)
       :data data
       :responsive true
       :deferRender true
       :rowCallback decorate}))))

(defn table-will-unmount
  [_]
  (.destroy
   (.DataTable
    (.find
     (js/$ ".dataTables_wrapper")
     "table"))
   true))

(defn table
  [columns data]
  (r/create-class
   {:reagent-render table-render
    :component-did-mount (table-did-mount columns data)
    :component-will-unmount table-will-unmount
    :should-component-update (fn [& args] false)}))

;; -----

(defn vega
  [schedule]
  (let [header (component "header")]
    [:> header
     {:as "h2"
      :textAlign "center"
      :style {:margin-top "80px"}}
     "Vega support is in the works"]))

;; -----

(defn header-icon
  [status]
  (case status
    :problem "exclamation"
    :success "check"
    :stale "bug"
    "question"))

(defn header-bg
  [status]
  (case status
    :problem "#ffd600"
    :success "#7cb342"
    :stale "#bdbdbd"
    "#42a5f5"))

(def story-data (r/atom nil))
(def story-err (r/atom nil))

(defn story-page
  "I don't what the story page to recieve
    updates, so there will be no ratoms"
  [params]
  (let [name (:schedule params)
        header (component "Header")
        icon (component "Icon")
        content (component "Header" "Content")
        subheader (component "Header" "Subheader")
        container (component "Container")
        _ (GET (str "/story?name=" name)
               {:handler #(reset! story-data %)
                :error-handler #(reset! story-err %)})]
    (fn []
      (if (some? @db/schedules)
        (let [{:keys [name status story long-desc
                      short-desc trouble] :as schedule}
              (some #(when (= name (:name %)) %) @db/schedules)]
          [:div
           [:> header
            {:size "huge"
             :icon true
             :textAlign "center"
             :style {:margin-top "110px"
                     :margin-bottom "50px"
                     :padding-top "28px"
                     :padding-bottom "20px"
                     :background (header-bg status)
                     :color "white"}}
            [:> icon
             {:name (header-icon status)
              :circular true
              :loading (and (nil? @story-err)
                            (nil? @story-data))}]
            name
            (cond
              (and (nil? @story-err)
                   (nil? @story-data))
              [:> subheader {:color "grey"} "Loading..."]
              (nil? @story-data)
              [:> subheader {:color "grey"} "No timestamp"]
              :else
              [:> subheader
               {:color "grey"}
               (let [ts (-> @story-data
                            :timestamp
                            localdt->moment)]
                 (str "Last generated "
                      (from-phrase ts (js/moment))
                      " at " (format ts)))])]
           (cond
             (some? @story-data)
             [:div
              [:> container
               {:style {:margin-bottom "50px"}}
               [:> header
                {:size "large"
                 :textAlign "center"}
                "Description"]
               (if (seq long-desc)
                 [:p long-desc]
                 [:p {:style {:text-align "center"}} short-desc])]
              (when (and (= status :problem)
                         (seq trouble))
                [:> container
                 {:style {:margin-bottom "50px"}}
                 [:> header
                  {:size "large"
                   :textAlign "center"}
                  "Troubleshooting"]
                 [:p trouble]])
              (let [{:keys [columns data]} @story-data]
                (cond
                  (s/ends-with? story ".csv") [table columns data]
                  (s/ends-with? story ".json") [vega schedule]
                  :else
                  [:> header
                   {:as "h2"
                    :textAlign "center"
                    :style {:margin-top "80px"}}
                   "Unsupported Story type"]))]
             (some? @story-err)
             [:> container
              [:> header
               {:size "huge"
                :textAlign "center"} "Error"]
              [:> header
               {:size "large"
                :textAlign "center"} @story-err]])])
        [:> header
         {:size "huge"
          :icon true
          :textAlign "center"
          :style {:margin-top "110px"
                  :margin-bottom "50px"
                  :padding-top "28px"
                  :padding-bottom "20px"
                  :background (header-bg :default)
                  :color "white"}}
         [:> icon
          {:name (header-icon :default)
           :circular true
           :loading true}]
         "Loading..."]))))
