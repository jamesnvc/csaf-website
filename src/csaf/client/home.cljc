(ns csaf.client.home)

(defn home-view
  [content]
  [:div.main {:tw "grid gap-2 mx-1"
              :style {:grid-template-columns "15% 1fr 6rem"}}
   [:div.gallery-image {:tw "h-100vh bg-no-repeat"
                        :style {:animation "changeGalleryImage 45s infinite"}}]
   [:div content]

   [:div "COMING EVENTS"]])
