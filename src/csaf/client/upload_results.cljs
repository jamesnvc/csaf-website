(ns csaf.client.upload-results
  (:require
   [clojure.string :as string]
   [bloom.commons.ajax :as ajax]
   [com.rpl.specter :as x]
   [reagent.core :as r]
   [csaf.client.styles :as styles]
   [csaf.client.state :refer [app-state]]
   [csaf.client.results :as results]))

(defn add-sheet!
  []
  (ajax/request
    {:method "post"
     :uri "/api/score-sheets/new"
     :on-success (fn [new-sheet]
                   (swap! app-state
                          (fn [s]
                            (-> s
                                (update :score-sheets assoc (:score-sheets/id new-sheet) new-sheet)
                                (assoc :active-sheet
                                       (:score-sheets/id new-sheet)))))
                   (.pushState js/history
                               nil ""
                               (str "/members/sheet/" (:score-sheets/id new-sheet))))}))

(defn select-sheet-view
  []
  [:div {:tw "flex flex-col"}
   [:h2 "Score Sheets"]
   [:ul
    (doall
      (for [{:score-sheets/keys [id created-at games-id games-date status]}
            (->> (vals (:score-sheets @app-state))
                 (sort-by :score-sheets/created-at))]
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
              (if games-id
                (let [game-name (x/select-first
                                  [x/ATOM :games x/ALL
                                   (x/if-path [:games/id (x/pred= games-id)]
                                              :games/name)]
                             app-state)]
                  [:span game-name
                   (when games-date (str " @ " (.toLocaleDateString games-date)))])
                "New Results Sheet")
              " "
              [:span {:tw "text-sm text-gray-400"}
               "Created " (.toLocaleDateString created-at)]
              " "
              [:span {:tw "text-sm"}
               (case status
                 "pending" "In Progress"
                 "complete" "Awaiting Approval"
                 "approved" "Approved")]]]))]
   [:button {:on-click (fn [] (add-sheet!))}
    "Add Games Results"]])

(defn field-view
  [opts]
  (r/with-let [editing? (r/atom false)]
    (if (and @editing? (not (:read-only opts)))
      [:input.field {:default-value (:value opts)
               :auto-focus true
               :on-blur (fn [e]
                          (x/setval (:path opts) (.. e -target -value) app-state)
                          ((:save-changes! opts))
                          (reset! editing? false))}]
      [:div.field {:on-click (fn [_] (reset! editing? true))}
       (:value opts)
       "\u00a0"])))

(defn save-sheet!
  [sheet on-success]
  (swap! app-state assoc ::saving? true)
  (ajax/request {:method :post
                 :uri (str "/api/score-sheets/" (:score-sheets/id sheet))
                 :params {:sheet sheet}
                 :credentials? true
                 :on-success (fn [_] (swap! app-state assoc ::saving? false)
                               (on-success))
                 :on-error (fn [err] (js/console.error "ERROR SAVING SHEET" err))}))

(defn sheet-view
  []
  (r/with-let [dt-formatter (js/Intl.DateTimeFormat. js/undefined #js {:timeZone "UTC"})
               last-saved (r/atom nil)
               save-changes! (fn []
                               (let [sheet (get-in @app-state
                                                   [:score-sheets (:active-sheet @app-state)])]
                                 (save-sheet! sheet #(reset! last-saved sheet))))]
    (let [active-sheet (:active-sheet @app-state)
          sheet (get-in @app-state [:score-sheets active-sheet])
          editable? (= "pending" (:score-sheets/status sheet)) ; TODO: or current user is admin
          member-names (into #{}
                             (map (fn [{:members/keys [first-name last-name]}]
                                    (str last-name ", " first-name)))
                             (@app-state :members))]
      (when (nil? @last-saved) (reset! last-saved sheet))
      [:div {:tw "flex flex-col gap-3"}
       [:a {:href "/members" :on-click (fn [e] (.preventDefault e)
                                         (swap! app-state assoc :active-sheet nil))
            :tw styles/a-tw}
        "Back to All Sheets"]
       [:h2 {:tw "text-lg"} "Score Sheet"]
       (when editable?
         [:div
          (cond
            (::saving? @app-state) [:span "Saving..."]
            (= @last-saved sheet) [:span "Changes saved"]
            :else [:span "Unsaved Changes"])])
       (r/with-let [new-game-name (r/atom nil)]
         [:<>
          [:label [:span {:tw "font-bold"} "Game: "]
            [:select {:value (if (some? @new-game-name)
                               "new-game"
                               (str (:score-sheets/games-id sheet)))
                      :disabled (not editable?)
                      :on-change (fn [e]
                                   (if (= "new-game" (.. e -target -value))
                                     (reset! new-game-name "")
                                     (do (x/setval
                                           [x/ATOM
                                            :score-sheets
                                            (x/keypath active-sheet)
                                            :score-sheets/games-id]
                                           (js/parseInt (.. e -target -value) 10)
                                           app-state)
                                         (save-changes!))))}
             [:option {:value ""} "None selected"]
             [:option {:value "new-game"} "New Games"]
             (for [game (:games @app-state)]
               ^{:key (:games/id game)}
               [:option {:value (str (:games/id game))}
                (:games/name game)])]]
          (when (some? @new-game-name)
            [:form {:on-submit
                    (fn [e]
                      (.preventDefault e)
                      (ajax/request {:method :post
                                     :uri "/api/games/new"
                                     :params {:game-name @new-game-name}
                                     :on-success (fn [{:keys [new-game]}]
                                                   (swap! app-state
                                                          update :games
                                                          conj new-game)
                                                   (swap! app-state update :games
                                                          (fn [gs] (sort-by :games/name gs)))
                                                   (swap! app-state
                                                          assoc-in
                                                          [:score-sheets
                                                           active-sheet
                                                           :score-sheets/games-id]
                                                          (:games/id new-game))
                                                   (reset! new-game-name nil)
                                                   (save-changes!))}))}
             [:input {:placeholder "Game Name"
                      :value @new-game-name
                      :on-change (fn [e]
                                   (->> (.. e -target -value)
                                        (reset! new-game-name)))}]
             [:button {:disabled (string/blank? @new-game-name)} "Create"]])])
       [:label [:span {:tw "font-bold"} "Game Date: "]
        [:input {:type "date" :name "date"
                 :value (or (some->> (:score-sheets/games-date sheet)
                                     (.format dt-formatter))
                            "")
                 :read-only (not editable?)
                 :on-change (fn [e]
                              (x/setval
                                [x/ATOM
                                 :score-sheets
                                 (x/keypath active-sheet)
                                 :score-sheets/games-date]
                                (js/Date. (.. e -target -value))
                                app-state)
                              (save-changes!))}]]

       [:h3 {:tw "font-bold"} "Results"]
       [:details
        [:summary "Click to show expected columns for spreadsheet"]
        [:div {:tw "text-sm"}
         [:p "In no particular order:"]
         [:dl.expected-format {:tw "ml-4"}
          [:dt "Name"]
          [:dd "Athlete's name, as \"Last, First\""]
          [:dt "Country"]
          [:dd "Athlete's Country"]
          [:dt "Class"]
          [:dd "One of "
           [:ul {:tw "list-disc ml-4"}
            [:li "Juniors"]
            [:li "Lightweight"]
            [:li "Amateurs" ]
            [:li "Open"]
            [:li "Masters"]
            [:li "Womens"]
            [:li "Womensmaster"]]]
          [:dt "\"Event code\", e.g. WOB, BRAE"]
          [:dd "The distance for the event in the format feet'inches\" (e.g. 42'2\"), "
           "or clock score for caber in the format hour:minutes (e.g. 11:30). Codes are:"
           [:ul {:tw "list-disc ml-4"}
            (for [event results/events-in-order
                  :let [code (results/abbrev-event-name event)]]
              ^{:key code}
              [:li code])]]
          [:dt "\"Event code\"_weight, e.g. BRAE_weight"]
          [:dd "The weight of the implement in pounds"]
          [:dt "CABR_length"]
          [:dd "The length of the caber like feet'inches\", e.g. 19'9\""]]]]

       (when editable?
         [:label {:tw "py-1 px-2 rounded bg-gray-200 border-1px border-black"} "Upload CSV"
          [:input {:tw "hidden"
                   :type "file"
                   :accept "text/csv"
                   :multiple false
                   :on-change (fn [e]
                                (when-let [file (aget (.. e -target -files) 0)]
                                  (.. file text
                                      (then
                                        (fn [csv]
                                          (ajax/request
                                            {:method "post"
                                             :uri "/api/csvify"
                                             :params {:csv-text csv}
                                             :credentials? true
                                             :on-success (fn [resp]
                                                           (x/setval
                                                             [x/ATOM
                                                              :score-sheets
                                                              (x/keypath active-sheet)
                                                              :score-sheets/data]
                                                             (->> (vec (:data resp))
                                                                  (x/setval
                                                                    [x/ALL
                                                                     #(every? string/blank? %)]
                                                                    x/NONE))
                                                             app-state)
                                                           (save-changes!))}))))))}]])
       (let [partial-path [x/ATOM :score-sheets (x/keypath active-sheet)
                           :score-sheets/data]
             name-idx (x/select-first
                        [:score-sheets/data x/FIRST
                         x/INDEXED-VALS
                         (x/if-path [x/LAST
                                     (x/view string/trim)
                                     (x/view string/lower-case)
                                     (x/pred= "name")]
                                    x/FIRST)]
                        sheet)]
         [:div {:tw "overflow-scroll"}
          (when (and editable? (seq (:score-sheets/data sheet)))
            [:p "Click on a cell to edit the contents"])
          [:table.results-upload-sheet
           (when-let [headers (first (:score-sheets/data sheet))]
             [:thead
              [:tr
               (when editable? [:th ])
               (for [[cidx col] (map-indexed vector headers)]
                 ^{:key cidx}
                 [:th [field-view {:value col
                                   :read-only (not editable?)
                                   :path (conj partial-path (x/nthpath 0)
                                               (x/nthpath cidx))
                                   :save-changes! save-changes!}]])]])
           [:tbody
            (for [[ridx row] (rest (map-indexed vector (:score-sheets/data sheet)))]
              ^{:key ridx}
              [:tr
               (when editable?
                 [:td [:button {:on-click (fn [_]
                                            (when (js/confirm "Delete this row?")
                                              (x/setval
                                                (conj partial-path (x/nthpath ridx))
                                                x/NONE
                                                app-state)
                                              (save-changes!)))}
                       "-"]])
               (for [[cidx col] (map-indexed vector row)]
                 ^{:key cidx}
                 [:td {:class (when (= cidx name-idx)
                                (if (contains? member-names col)
                                  "valid-member"
                                  "missing-member"))}
                  [field-view {:value col
                               :read-only (not editable?)
                               :path (conj partial-path (x/nthpath ridx)
                                           (x/nthpath cidx))
                               :save-changes! save-changes!}]])])
            (when (and editable? (seq (:score-sheets/data sheet)))
              [:tr
               [:td
                [:button {:on-click (fn []
                                      (x/setval
                                        (conj partial-path x/AFTER-ELEM)
                                        (vec (repeat (count (first (:score-sheets/data sheet))) ""))
                                        app-state)
                                      (save-changes!))}
                 " + "]]])]]])


       [:div.submit

        (case (:score-sheets/status sheet)
          "pending"
          [:button {:on-click (fn []
                                (when (js/confirm "Submit these results for approval? You won't be able to edit them anymore.")
                                  (ajax/request {:method :post
                                                 :uri (str "/api/score-sheets/"
                                                           (:score-sheets/id sheet)
                                                           "/submit")
                                                 :credentials? true
                                                 :on-success (fn [_]
                                                               (swap!
                                                                 app-state
                                                                 assoc-in
                                                                 [:score-sheets
                                                                  active-sheet
                                                                  :score-sheets/status]
                                                                 "complete"))})))}
           "Submit results" ]
          "complete"
          [:span "Awaiting admin approval"]
          "approved"
          [:span "Results approved & in the database"]
          nil "")]])))

(defn upload-results-view
  []
  (r/with-let [back-listener (fn [_]
                               (let [path js/window.location.pathname]
                                 (if (= path"/members")
                                   (swap! app-state assoc :active-sheet nil)
                                   (when-let [[_ sheet-id] (re-matches #"^/members/sheet/(\d+)$" path)]
                                     (swap! app-state assoc :active-sheet (js/parseInt sheet-id 10))))))
               _ (.addEventListener js/window "popstate" back-listener)]
    (if (or (nil? (:active-sheet @app-state)) (nil? (:score-sheets @app-state)))
      [select-sheet-view]
      [sheet-view])
    (finally
      (.removeEventListener js/window "popstate" back-listener))))
