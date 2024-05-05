(ns ^:figwheel-hooks csaf.client.core
  (:require
   [bloom.omni.reagent :as omni.reagent]
   [csaf.client.layout :as layout]))

(enable-console-print!)

(defn app-view
  []
  [layout/layout
   [:<>
    [:h1 "Hello, CSAF"]
    [:p "beep boop"]]])

(defn render
  []
  (omni.reagent/render [app-view]))

(defn ^:export init
  []
  (render))

(defn ^:after-load reload
  []
  (render))
