(ns bigboard.story
  (:require
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

(defn table-did-mount
  [columns data]
  (fn [^js/React.Component this]
    (.DataTable
     (-> this .-refs .-main js/$)
     (clj->js
      {:columns columns
       :data data
       :responsive true}))))

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
    :stale "bug"))

(defn header-bg
  [status]
  (case status
    :problem "#ffd600"
    :success "#7cb342"
    :stale "#bdbdbd"))

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
        {:keys [name status story] :as schedule}
        (some #(when (= name (:name %)) %) @db/schedules)
        _ (reset! story-data nil)
        _ (GET (str "/story?story=" story)
               {:handler #(reset! story-data %)
                :error-handler #(reset! story-err %)})]
    (fn []
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
          :loading (nil? @story-data)}]
        name
        (if (nil? @story-data)
          [:> subheader {:color "grey"} "Loading..."]
          [:> subheader
           {:color "grey"}
           (let [ts (-> @story-data
                        :timestamp
                        localdt->moment)]
             (str "Last generated "
                  (from-phrase ts (js/moment))
                  " at " (format ts)))])]
       (when @story-data
         (let [{:keys [columns data]} @story-data]
           (cond
             (s/ends-with? story ".csv") [table columns data]
             (s/ends-with? story ".json") [vega schedule]
             :else
             [:> header
              {:as "h2"
               :textAlign "center"
               :style {:margin-top "80px"}}
              "Unsupported Story type"])))])))
