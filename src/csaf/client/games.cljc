(ns csaf.client.games
  (:require
   [clojure.string :as string]
   [csaf.client.results :as results]))

(defn games-history-filter-view
  [selected available-years]
  [:div.filters {:tw "mx-8"}
   [:h1 "Filter By"]
   [:form {:method "get" :action "/games"
           :tw "flex flex-col gap-3"}
    [:fieldset
     [:legend "Year"]
     [:div {:tw "flex flex-row flex-wrap gap-4"}
      ;; Should we allow having no year? So much data...
      #_[:label [:input {:type "radio" :name "filter-year"
                         :value "false" :checked (not (get selected "filter-year"))}]
         "All"]
      (for [{:keys [year]} available-years]
        [:label
         [:input {:type "radio" :name "filter-year" :value (str year)
                  :checked (= (get selected "filter-year") (str year))}]
         (str year)])]]

    [:fieldset [:legend "Class"]
     [:div {:tw "flex flex-row flax-wrap gap-4"}
      (for [class ["juniors" "lightweight" "amateurs" "open" "masters" "womens"
                   "womensmaster"]]
        [:label
         [:input {:type "checkbox" :name "class"
                  :value class :checked (contains? (set (get selected "class"))
                                                   class)
                  :tw "mr-1"}]
         (if (= class "womensmaster")
           "Women's Masters"
           (string/capitalize class))])]]

    [:fieldset [:legend "Event Type"]
     [:div {:tw "flex flex-row flex-wrap gap-4"}
      [:label
       [:input {:type "radio" :name "filter-event"
                :value "false" :checked (not (get selected "filter-event"))
                :tw "mr-1"}]
       "All"]
      (for [event-name results/events-in-order]
        [:label
         [:input {:type "radio" :name "filter-event"
                  :value event-name :checked (= (get selected "filter-event")
                                                event-name)
                  :tw "mr-1"}]
         (results/display-event-name event-name)])]]

    [:button "Filter"]]])


(defn games-history-view
  [{:keys [available-years selected games]}]
  [:div {:tw "flex flex-col gap-4"}
   [:h1 "COMPETITION HISTORY"]

   [games-history-filter-view selected available-years]

   [:div.results {:tw "flex flex-col gap-8 mx-4"}
    (if (empty? games)
      [:h4 "No Results"]
      (for [game-date (sort #(compare %2 %1) (keys games))
            game (vals (get games game-date))]
        [:div
         [:h2 {:tw "text-xl"} (game :games/name)]
         [:h3 {:tw "text-lg text-gray-500 mx-4"}
          (str game-date)]
         [:table {:tw "w-full"}
          (for [[class results] (->> (vals (game :results))
                                     (group-by :game-member-results/class)
                                     (sort-by first #(compare %2 %1)))]
            [:<>
             [:thead
              [:tr
               [:th {:tw "font-normal text-sm text-gray-500"}
                    [string/capitalize class]]]
              [:tr [:th ""]
               [:th "Name"] [:th "Place"]
               (for [event-name results/events-in-order
                     :when (or (not (get selected "filter-event"))
                               (= event-name (get selected "filter-event")))]
                 [:th (results/abbrev-event-name event-name)])]]
             [:tbody {:tw "text-sm"}
              (for [result (->> results (sort-by :game-results-placing/placing))]
                [:tr
                 [:td {:tw "bg-white"}]
                 [:td (:members/last-name result) ", " (:members/first-name result)]
                 [:td (:game-results-placing/placing result)]
                 (for [event-name results/events-in-order
                       :when (or (not (get selected "filter-event"))
                                 (= event-name (get selected "filter-event")))
                       :let [{:game-member-results/keys [distance-inches clock-minutes weight]}
                             (get-in result [:events event-name])]]
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
                        (results/display-distance distance-inches)))])])]])]]))]])
