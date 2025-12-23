(ns csaf.client.calendar)

(defn admin-calendar-view
  [entries]
  [:div.admin-calendar
   [:form {:method "POST" :action "/admin/calendar"}
    [:label {:class "block"}
     "Date" [:input {:type "date" :name "date" :required true}]]
    [:label {:class "block"}
     "Location" [:input {:type "text" :name "location" :placeholder "Da Rock Zone"
                         :required true}]]
    [:label {:class "block"}
     "Title" [:input {:type "text" :name "title" :placeholder "Awesome Games"
                      :required true}]]
    [:label {:class "block"}
     "Description" [:input {:type "text" :name "description" :placeholder "Open to all"}]]
    [:button "Save"]]
   [:div
    [:h3 "Upcoming Entries"]
    [:ol
     (for [{:calendar-entry/keys [id date location title description]} entries]
       [:li
        [:div (str date " - " title)]
        [:div (str "At " location)]
        [:div description]
        [:div.controls
         [:form {:method "POST" :action (str "/admin/calendar/" id "/delete")}
          [:button "Delete"]]]])]]])
