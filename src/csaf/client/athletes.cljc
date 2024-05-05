(ns csaf.client.athletes
  (:require
   [clojure.string :as string]
   [csaf.client.styles :as styles]))

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
  [:div {:tw "flex flex-col gap-4"}
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
         (list [:dt {:tw dt-tw} "Height "]
               [:dd height]))

       (when-let [weight (:members/weight member)]
         (list [:dt {:tw dt-tw} "Weight "]
               [:dd weight]))

       (when-let [tartan (:members/tartan member)]
         (list [:dt {:tw dt-tw} "Tartan "]
               [:dd tartan]))])]

   (when-let [bio (:members/biography member)]
     [:div.bio [:p bio]])

   [:div.prs
    [:h3 {:tw "text-lg text-gray-500"} "PERSONAL BESTS"]
    [:code "TODO"]]


   [:div.record
    [:h3 {:tw "text-lg text-gray-500"} "GAMES RECORD"]
    [:code "TODO"]]

   ]
  )
