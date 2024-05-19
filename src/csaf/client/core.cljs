(ns ^:figwheel-hooks csaf.client.core
  (:require
   [com.rpl.specter :as x]
   [goog.object :as o]
   [reagent.core :as r]
   [bloom.commons.ajax :as ajax]
   [bloom.omni.reagent :as omni.reagent]
   [csaf.client.layout :as layout]))

(enable-console-print!)

(defonce app-state (r/atom {:logged-in? false
                            :score-sheets []
                            ;; TODO: history.pushState to encode this in url
                            :active-sheet nil}))

(defn check-auth!
  []
  (ajax/request {:method "post"
                 :uri "/api/checkauth"
                 :credentials? true
                 :on-success (fn [_] (swap! app-state assoc :logged-in? true))
                 :on-error (fn [_] (swap! app-state assoc :logged-in? false))}))

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
                              :on-success (fn [resp]
                                            (prn "LOGGED IN" resp))
                              :on-error (fn [_]
                                          (prn "ERROR")
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

(defn add-sheet!
  []
  (ajax/request
    {:method "post"
     :uri "/api/score-sheets/new"
     :on-success (fn [new-sheet]
                   (swap! app-state
                          (fn [s]
                            (-> s
                                (update :score-sheets (fnil conj []) new-sheet)
                                (assoc :active-sheet
                                       (:score-sheets/id new-sheet))))))}))

(defn upload-results-view
  []
  (r/with-let [_ (ajax/request {:method "get"
                                 :uri "/api/init-data"
                                 :on-success (fn [{:keys [sheets games members]}]
                                               (swap! app-state
                                                      assoc
                                                      :score-sheets sheets
                                                      :games games
                                                      :members members))})
               dt-formatter (js/Intl.DateTimeFormat. js/undefined #js {:timeZone "UTC"})]
    (if (nil? (:active-sheet @app-state))
      [:div {:tw "flex flex-col"}
       [:h2 "Score Sheets"]
       [:ul
        (doall
          (for [{:score-sheets/keys [id created-at]} (:score-sheets @app-state)]
            ^{:key id}
            [:li [:a {:href (str "/members/sheet/" id)
                      :on-click (fn [e]
                                  (.preventDefault e)
                                  (.stopPropagation e)
                                  (.pushState js/history
                                              nil ""
                                              (str "/members/sheet/" id))
                                  (swap! app-state assoc :active-sheet
                                         id))}
                  [:span "Game ???" ]
                  " "
                  [:span "Created " (.toLocaleDateString created-at)]]]))]
       [:button {:on-click (fn [] (add-sheet!))}
        "Add Sheet"]]
      (let [active-sheet (:active-sheet @app-state)
            sheet (x/select-one
                    [x/ALL #(= active-sheet (:score-sheets/id %))]
                    (:score-sheets @app-state))]
        [:div {:tw "flex flex-col"}
         [:a {:href "/members" :on-click (fn [e] (.preventDefault e)
                                           (swap! app-state assoc :active-sheet nil))}
          "All Sheets"]
         [:h2 "Score Sheet"]
         [:label "Game"
          [:select {:value (str (:score-sheets/games-id sheet))
                    :on-change (fn [e]
                                 (x/setval
                                   [x/ATOM
                                    :score-sheets
                                    x/ALL
                                    (x/if-path
                                      [:score-sheets/id (x/pred= active-sheet)]
                                      :score-sheets/games-id)]
                                   (js/parseInt (.. e -target -value) 10)
                                   app-state))}
           [:option {:value ""} "None selected"]
           (for [game (:games @app-state)]
             ^{:key (:games/id game)}
             [:option {:value (str (:games/id game))}
              (:games/name game)])]]
         [:label "Game Date"
          [:input {:type "date" :name "date"
                   :value (or (some->> (:score-sheets/games-date sheet)
                                       (.format dt-formatter))
                              "")
                   :on-change (fn [e]
                                (x/setval
                                  [x/ATOM
                                   :score-sheets
                                   x/ALL
                                   (x/if-path
                                     [:score-sheets/id (x/pred= active-sheet)]
                                     :score-sheets/games-date)]
                                  (js/Date. (.. e -target -value))
                                  app-state))}]]

         [:h3 "Results"]
         [:label "Upload CSV"
          [:input {:type "file"}]]
         [:table
          (for [row (:score-sheets/data sheet)]
            [:tr
             [:td (pr-str row)]])
          ]
         [:code (pr-str sheet)]
         ]))))

(defn app-view
  []
  [layout/layout
   [:div
    [:h1 "Members Area"]
    (if (not (:logged-in? @app-state))
     [login-view]
     [upload-results-view])]])

(defn render
  []
  (omni.reagent/render [app-view]))

(defn ^:export init
  []
  (when-let [[_ sheet-id] (re-matches #"^/members/sheet/(\d+)$" js/window.location.pathname)]
    (swap! app-state assoc :active-sheet (js/parseInt sheet-id 10)))
  (check-auth!)
  (render))

(defn ^:after-load reload
  []
  (render))
