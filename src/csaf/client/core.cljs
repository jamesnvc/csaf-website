(ns ^:figwheel-hooks csaf.client.core
  (:require
   [bloom.omni.reagent :as omni.reagent]))

(enable-console-print!)

(defn app-view
  []
  [:div {:tw "bg-white min-h-100vh w-80vw max-w-1000px mx-auto"}
   [:div.top-bar {:tw "flex flex-row items-center"}
    [:img {:src "/images/csaf_logo.jpg" :alt "CSAF Logo"
           :tw "w-10rem"}]
    [:div {:tw "flex-grow"}
     [:h1 {:tw "font-urbanist text-gray-500"}
      "Canadian " [:span {:tw "text-black"} "Scottish Athletic"] " Federation"]]]
   [:h1 "Hello, CSAF!"]])

(defn render
  []
  (omni.reagent/render [app-view]))

(defn ^:export init
  []
  (render))

(defn ^:after-load reload
  []
  (render))
