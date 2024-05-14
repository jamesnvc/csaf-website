(ns csaf.client.athletes
  (:require
   [clojure.string :as string]
   [csaf.client.styles :as styles]
   [csaf.client.results :refer [?> display-clock display-distance display-weight
                                events-in-order display-event-name]]))

(defn all-athletes-view
  [athletes]
  [:div [:h1 "ATHLETES"]
   [:div {:tw "flex flex-col md:grid md:grid-cols-2 lg:grid-cols-3 gap-4"}
    (for [{:members/keys [id first-name last-name]} athletes]
      [:a {:href (str "/athletes/" id)
           :tw styles/a-tw}
       last-name ", " first-name])]] )

(defn athlete-view
  [member]
  [:div {:tw "flex flex-col gap-4 mx-4"}
   [:h2 {:tw "text-xl"} (:members/first-name member) " " (:members/last-name member)]
   (when-let [email (:members/email member)]
     [:a {:tw styles/a-tw :href (str "mailto:" email)} email])

   [:div.location
    (let [locale (->> (keep member [:members/city :members/province])
                      (string/join ", "))]
      (when-not (string/blank? locale)
        [:div locale]))
    [:div (:members/country member)]
    (when-let [postal (:members/postal-code member)]
      [:div postal])]

   [:div.stats
    (let [dt-tw "after:content-[\"::\"]"]
      [:dl {:tw "grid gap-1" :style {:grid-template-columns "max-content max-content"}}
       (when-let [height (:members/height member)]
         [:<> [:dt {:tw dt-tw} "Height "]
          [:dd height]])

       (when-let [weight (:members/weight member)]
         [:<> [:dt {:tw dt-tw} "Weight "]
          [:dd weight]])

       (when-let [tartan (:members/tartan member)]
         [:<> [:dt {:tw dt-tw} "Tartan "]
          [:dd tartan]])])]

   (when-let [bio (:members/biography member)]
     [:div.bio [:hiccup/raw-html bio]])

   [:div.prs
    [:h3 {:tw "text-lg text-gray-500"} "PERSONAL BESTS"]

    [:table {:tw "w-full"}
     [:thead
      [:tr [:th "Event"] [:th "Class"] [:th "Location"] [:th "Mark"] [:th "Date"]]]
     [:tbody
      (for [event-name events-in-order
            :let [result (get-in member [:member/prs event-name])]]
        [:tr
         [:td (display-event-name event-name)]
         [:td (some-> (:class result) string/capitalize)]
         [:td {:tw "text-sm"} (:games/name result)]
         [:td
          (->> [(when (= event-name "caber")
                  (some-> (:clock-minutes result) display-clock))
                (display-distance (:distance-inches result))
                (when (#{"braemar" "open" "sheaf" "caber"} event-name)
                  (some-> (:weight result) (?> zero? (constantly nil))
                          display-weight))]
               (remove nil?)
               (string/join " "))]
         [:td (str (:game-instances/date result))]])]]]


   [:div.record
    [:h3 {:tw "text-lg text-gray-500"} "GAMES RECORD"]
    [:table
     [:thead
      [:tr
       [:th "Date"]
       [:th "Location"]
       [:th "Place"]
       [:th "BRE"]
       [:th "STON"]
       [:th "SHF"]
       [:th "CABR"]
       [:th "LWFD"]
       [:th "HWFD"]
       [:th "LWFD"]
       [:th "HHMR"]
       [:th "WOB"]]]
     [:tbody
      (for [game (:member/game-results member)]
        [:tr
         [:td (str (:game-instances/date game))]
         [:td {:tw "text-sm"} (:game-name game)]
         [:td (pr-str (:placing game))]
         (for [event-name events-in-order
               :let [result (get-in game [:result event-name])]]
           [:td
            (if (or (nil? (:weight result)) (nil? (:distance-inches result))
                  (and (zero? (:weight result)) (zero? (:distance-inches result))))
              "N/A"
              (case event-name
                "caber"
                [:<>
                 (display-clock (:clock-minutes result))
                 " "
                 (display-distance (:distance-inches result))
                 [:br]
                 (display-weight (:weight result))]
                ("braemar" "open" "sheaf")
                [:<>
                 (display-distance (:distance-inches result))
                 [:br]
                 (display-weight (:weight result))]
                (display-distance (:distance-inches result))))])])]]]])
