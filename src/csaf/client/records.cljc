(ns csaf.client.records
  (:require
   [clojure.string :as string]
   [csaf.client.results :as results]))

(defn records-view
  [records]
  [:div {:tw "flex flex-col gap-4"}
   [:p "Records marked with ** indicate a current world record"]
   (for [cls results/classes-in-order
         :let [class-records (get records cls)]
         :when (seq class-records)]
     [:div
      [:h2 {:tw "text-center text-lg text-gray-500 font-smallcaps"}
       (string/capitalize cls)]
      [:table
       [:thead
        [:th "Event"]
        [:th "Athlete"]
        [:th "Distance"]
        [:th "Weight"]
        [:th "Year"]
        [:th "Comment"]]
       [:tbody
        (for [event results/events-in-order
              :let [record (get class-records event)]
              :when (some? record)]
          [:tr
           [:td (results/display-event-name event)]
           [:td (:event-record/athlete-name record)]
           [:td (results/display-distance
                  (:event-record/distance-inches record))]
           [:td (results/display-weight (:event-record/weight record))]
           [:td (:event-record/year record)]
           [:td {:tw "text-sm"} (:event-record/comment record)]])]]])])
