(ns bigboard.story
  (:require
   [reagent.core :as r]
   [bigboard.sui :refer [component]]
   [bigboard.db :as db]
   [ajax.core :refer [GET]]))

(comment

  (def columns
    [{:title "Name"
      :data "name"}
     {:title "Age"
      :data "age"}])

  (def data
    [{"name" "Matthew"
      "age" "26"}
     {"name" "Anna"
      "age" "24"}
     {"name" "Michelle"
      "age" "42"}
     {"name" "Frank"
      "age" "46"}])

  (defn home-render []
    [:div
     [:table.ui.celled.table
      {:ref "main" :width "100%"}]])

  (defn home-did-mount
    [^js/React.Component this]
    (.DataTable
     (-> this .-refs .-main js/$)
     (clj->js
      {:columns columns
       :data data
       :responsive true})))

  (defn home-will-unmount
    [_]
    (.destroy
     (.DataTable
      (.find
       (js/$ ".dataTables_wrapper")
       "table"))
     true))

  (defn home []
    (r/create-class
     {:reagent-render home-render
      :component-did-mount home-did-mount
      :component-will-unmount home-will-unmount
      :should-component-update (fn [& args] false)})))

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

(defn story-page
  "I don't what the story page to recieve
    updates, so there will be no ratoms"
  [name]
  (let [header (component "Header")
        icon (component "Icon")
        content (component "Header" "Content")
        {:keys [status] :as schedule}
        (some #(when (= name (:name %)) %) @db/schedules)]
    (fn []
      [:> header
       {:as "h1"
        :icon true
        :textAlign "center"
        :style {:margin-top "110px"
                :padding-top "28px"
                :padding-bottom "20px"
                :background (header-bg status)
                :color "white"}}
       [:> icon
        {:name (header-icon status)
         :circular true}]
       [:> content
        name]])))
