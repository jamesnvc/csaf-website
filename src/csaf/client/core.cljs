(ns ^:figwheel-hooks csaf.client.core
  (:require
   [goog.object :as o]
   [reagent.core :as r]
   [bloom.commons.ajax :as ajax]
   [bloom.omni.reagent :as omni.reagent]
   [csaf.client.layout :as layout]
   [csaf.client.upload-results :refer [upload-results-view]]
   [csaf.client.state :refer [app-state]]))

(enable-console-print!)

(defn check-auth!
  []
  (ajax/request {:method "post"
                 :uri "/api/checkauth"
                 :credentials? true
                 :on-success (fn [_] (swap! app-state assoc :logged-in? true))
                 :on-error (fn [_] (swap! app-state assoc :logged-in? false))}))

(defn fetch-init-data!
  []
  (ajax/request
    {:method "get"
     :uri "/api/init-data"
     :on-success
     (fn [{:keys [sheets games members logged-in-user]}]
       (swap! app-state
              assoc
              :score-sheets (into {}
                                  (map (fn [sheet]
                                         [(:score-sheets/id sheet)
                                          sheet]))
                                  sheets)
              :games games
              :members members
              :logged-in-user logged-in-user))}))

(defn login-view
  []
  (r/with-let [err (r/atom nil)]
    [:form {:tw "mx-auto w-50% items-center flex flex-col gap-2"
            :on-submit (fn [e] (.preventDefault e)
                         (reset! err nil)
                         (let [form (.. e -target -elements)]
                           (ajax/request
                             {:uri "/api/authenticate"
                              :method "post"
                              :credentials? true
                              :params {:username (-> form (o/get "username")
                                                     (o/get "value"))
                                       :password (-> form (o/get "password")
                                                     (o/get "value"))}
                              :on-success (fn [_]
                                            (swap! app-state assoc :logged-in? true)
                                            (fetch-init-data!))
                              :on-error (fn [_]
                                          (reset! err "Incorrect username or password"))})))}
     (when-let [error @err]
       [:div.error error])
     [:label
      "Username: "
      [:input {:type "text" :name "username" :tw "border-black border-1px"}]]
     [:label
      "Password: "
      [:input {:type "password" :name "password"
               :tw "border-black border-1px"}]]
     [:button {:tw "rounded bg-gray-200"} "Log In"]]))

(defn app-view
  []
  [layout/layout
   [:div
    [:h1 {:tw "text-xl"} "Members Area"]
    (if (not (:logged-in? @app-state))
      [login-view]
      [upload-results-view])]
   (:logged-in-user @app-state)])

(defn render
  []
  (omni.reagent/render [app-view]))

(defn ^:export init
  []
  (when-let [[_ sheet-id] (re-matches #"^/members/sheet/(\d+)$" js/window.location.pathname)]
    (swap! app-state assoc :active-sheet (js/parseInt sheet-id 10)))
  (fetch-init-data!)
  (check-auth!)
  (render))

(defn ^:after-load reload
  []
  (render))
