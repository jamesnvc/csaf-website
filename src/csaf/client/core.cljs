(ns ^:figwheel-hooks csaf.client.core
  (:require
   [bloom.omni.reagent :as omni.reagent]))

(enable-console-print!)

(defn app-view
  []
  [:div [:h1 "Hello, CSAF!"]])

(defn render
  []
  (omni.reagent/render [app-view]))

(defn ^:export init
  []
  (render))

(defn ^:after-load reload
  []
  (render))
