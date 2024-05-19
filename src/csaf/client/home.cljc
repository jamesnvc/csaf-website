(ns csaf.client.home
  (:require
   [csaf.client.styles :as styles]))

(defn home-view
  []
  [:div.main {:tw "grid gap-2 mx-1"
              :style {:grid-template-columns "15% 1fr 6rem"}}
   [:div.gallery-image {:tw "h-100vh bg-no-repeat"
                        :style {:animation "changeGalleryImage 45s infinite"}}]

   [:div
    [:h1 {:tw "text-lg"} "Coming Soon"]
    [:p "csaf.ca is currently being brought over from its old host; please bear with us while we complete the process."]
    [:p "You can view " [:a {:href "/athletes" :tw styles/a-tw} "Individual results"]
     " and "
     [:a {:href "/games" :tw styles/a-tw} "Games history"] "."]
    [:p "The rest of the content will be back soon."]]

   [:div "COMING EVENTS"]])
