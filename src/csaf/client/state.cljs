(ns csaf.client.state
  (:require
   [reagent.core :as r]))

(defonce app-state (r/atom {:logged-in? false
                            :score-sheets []
                            :logged-in-user nil
                            :active-sheet nil}))
