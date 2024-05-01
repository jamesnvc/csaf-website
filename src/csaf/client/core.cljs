(ns ^:figwheel-hooks csaf.client.core
  (:require
   [bloom.omni.reagent :as omni.reagent]))

(enable-console-print!)

(defn app-view
  []
  [:div {:tw "bg-white min-h-100vh w-80vw max-w-1000px mx-auto font-sans-serif"}
   [:div.top-bar {:tw "flex flex-row items-center"}
    [:a {:href "/"}
     [:img {:src "/images/csaf_logo.jpg" :alt "CSAF Logo"
            :tw "w-10rem"}]]
    [:div {:tw "flex-grow flex flex-col"}
     [:h1 {:tw "font-urbanist text-gray-500 text-center"}
      "Canadian " [:span {:tw "text-black"} "Scottish Athletic"] " Federation"]
     (let [a-tw "inline-block text-center hover:text-red-800 no-underline text-black h-2.5rem"]
       [:nav {:tw "flex flex-col md:grid grid-cols-6 text-xs"}
        [:a {:tw a-tw :href "/"} "Home"]
        [:a {:tw a-tw :href "/"} "Athletes"]
        [:a {:tw a-tw :href "/"} "Competitions"]
        [:a {:tw a-tw :href "/"} "Information"]
        [:a {:tw a-tw :href "/"} "About CSAF"]
        [:a {:tw a-tw :href "/"} "Contact Us"]])]]
   [:div.main {:tw "grid gap-2 mx-1"
               :style {:grid-template-columns "15% 1fr 6rem"}}
    [:div.gallery-image {:tw "h-100vh bg-no-repeat"
                         :style {:animation "changeGalleryImage 30s infinite"}}]
    [:div [:h1 "Hello, CSAF!"]]

    [:div "COMING EVENTS"]]
   ])

(defn render
  []
  (omni.reagent/render [app-view]))

(defn ^:export init
  []
  (render))

(defn ^:after-load reload
  []
  (render))
