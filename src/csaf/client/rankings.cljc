(ns csaf.client.rankings
  (:require
   [clojure.string :as string]
   [clojure.math :as math]
   [com.rpl.specter :as x]
   [csaf.client.results :as results]))

(def ranking-events
  (x/setval
    [x/ALL (x/pred #{"braemar" "sheaf"})]
    x/NONE
    results/events-in-order))

(defn rankings-view
  [{:keys [rankings-by-class year]}]
  [:div
   [:h1 "Rankings for "
    (if (nil? year) "Current Year"
        year)]
   [:a {:href "/rankings/2023"} "Previous Year"]
   (for [cls results/classes-in-order
         :let [class-results (get rankings-by-class cls)]
         :when class-results]
     ^{:key cls}
     [:div
      [:h2 {:tw "text-center text-lg text-gray-500 font-smallcaps"}
       (string/capitalize cls)]

      [:table
       [:thead {:tw "sticky top-0"}
        [:tr
         [:th {:rowSpan 2} "Rank"]
         [:th {:rowSpan 2} "Athlete"]
         [:th {:rowSpan 2} "Total Points"]
         (for [event ranking-events]
           [:th {:colSpan 2}
            (results/abbrev-event-name event)])]
        [:tr {:tw "text-xs"}
         (for [_ ranking-events]
           [:<>
            [:th "Throw"]
            [:th "Points"]])]]
       [:tbody
        (for [[rank result] (->> class-results vals
                                 (sort-by :score #(compare %2 %1))
                                 (map-indexed vector))]
          [:tr
           [:td (inc rank)]
           [:td
            [:a {:href (str "/athletes/" (:members/id result))}
             (:members/last-name result) ", " (:members/first-name result)]]
           [:td (/ (math/floor (* 10 (:score result))) 10)]
           (for [event ranking-events
                 :let [event-result (get-in result [:events event])]]
             [:<>
              [:td {:tw "text-sm"}
               (when (= event "caber")
                 [:<> (some-> event-result
                              :clock-minutes
                              results/display-clock)
                  " "
                  (some-> (:weight event-result) results/display-weight)
                  " "])
               (results/display-distance
                 (:distance-inches event-result))]
              [:td {:tw "text-xs"}
               (some-> (:score event-result)
                       math/round)]])])]]])])
