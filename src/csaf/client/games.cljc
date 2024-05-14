(ns csaf.client.games
  (:require
   [clojure.string :as string]
   [csaf.client.results :as results]))

(defn games-history-view
  [{:keys [available-years selected games]}]
  [:div {:tw "flex flex-col gap-4"}
   [:h1 "COMPETITION HISTORY"]
   [:div.filters {:tw "mx-8"}
    [:h1 "Filter By"]
    [:form {:method "get" :action "/games"}
     [:h2 "Year"]
     [:div {:tw "flex flex-row flex-wrap gap-4"}
      [:label [:input {:type "radio" :name "filter-year"
                       :value "false" :checked (not (get selected "filter-year"))}]
       "Any"]
      (for [{:keys [year]} available-years]
        [:label
         [:input {:type "radio" :name "filter-year" :value (str year)
                  :checked (= (get selected "filter-year") (str year))}]
         (str year)])]

     #_[:h2 "Class"]

     #_[:h2 "Event Type"]

     [:button {:tw "px-2 py-1 rounded bg-gray-100 border-1px border-solid border-black"}
      "Search"]]]

   [:div.results {:tw "flex flex-col gap-8 mx-4"}
    (if (empty? games)
      [:h4 "No Results"]
      (for [game-date (sort #(compare %2 %1) (keys games))]
        [:div
         [:h2 {:tw "text-xl"} (get-in games [game-date :games/name])]
         [:h3 {:tw "text-lg text-gray-500 mx-4"}
          (str game-date)]
         [:table {:tw "w-full"}
          (for [[class results] (->> (vals (get-in games [game-date :results]))
                                     (group-by :game-member-results/class)
                                     (sort-by first #(compare %2 %1)))]
            [:<>
             [:thead
              [:tr
               [:th {:tw "font-normal text-sm text-gray-500"}
                    [string/capitalize class]]]
              [:tr [:th ""]
               [:th "Name"] [:th "Place"]
               [:th "BRAE"] [:th "STON"] [:th "SHF"] [:th "CABR"]
               [:th "LWFD"] [:th "HWFD"] [:th "LHMR"] [:th "HHMR"]
               [:th "WOB"]]]
             [:tbody {:tw "text-sm"}
              (for [result (->> results (sort-by :game-results-placing/placing))]
                [:tr
                 [:td {:tw "bg-white"}]
                 [:td (:members/last-name result) ", " (:members/first-name result)]
                 [:td (:game-results-placing/placing result)]
                 (let [event-results (group-by :game-member-results/event (:events result))]
                   (for [event-name results/events-in-order
                         :let [{:game-member-results/keys [distance-inches clock-minutes weight]}
                               (get-in event-results [event-name 0])]]
                     [:td
                      (if (or (nil? weight) (nil? distance-inches)
                              (and (zero? weight) (zero? distance-inches)))
                        "N/A"
                        (case event-name
                          "caber"
                          [:<>
                           (results/display-clock clock-minutes)
                           " "
                           (results/display-distance distance-inches)
                           [:br]
                           (results/display-weight weight)]
                          ("braemar" "open" "sheaf")
                          [:<>
                           (results/display-distance distance-inches)
                           [:br]
                           (results/display-weight weight)]
                          (results/display-distance distance-inches)))]))
                 ])]])]]))
    ]])
