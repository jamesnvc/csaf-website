(ns csaf.client.athletes)

(defn all-athletes-view
  [athletes]
  [:div [:h1 "ATHLETES"]
   [:div {:tw "flex flex-col md:grid md:grid-cols-2 lg:grid-cols-3 gap-4"}
    (for [{:members/keys [id first-name last-name]} athletes]
      [:a {:href (str "/athletes/" id)
           :tw "text-red-800 hover:text-red-300 no-underline"}
       last-name ", " first-name])]] )
