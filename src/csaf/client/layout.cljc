(ns csaf.client.layout)

(defn layout
  [content logged-in-user]
  [:div {:tw "bg-white min-h-100vh w-80vw max-w-1000px mx-auto font-sans-serif flex flex-col gap-4"}
   [:div.top-bar {:tw "flex flex-row items-center"}
    [:a {:href "/"}
     [:img {:src "/images/csaf_logo.jpg" :alt "CSAF Logo"
            :tw "w-10rem"}]]
    [:div {:tw "flex-grow flex flex-col"}
     [:div {:style {:container-type "inline-size"}}
      [:h1 {:tw "font-urbanist text-gray-500 text-center"
            :style {:font-size "5cqi"}}
       "Canadian " [:span {:tw "text-black"} "Scottish Athletic"] " Federation"]]
     (let [a-tw "inline-block text-center hover:text-red-800 no-underline text-black h-2.5rem"
           details-tw "group text-center"
           details-content-tw "absolute border-red-800 border-1px border-solid text-left invisible group-hover:visible bg-white z-100"]
       [:nav {:tw "flex flex-col md:grid grid-cols-6 text-xs"}
        [:a {:tw a-tw :href "/"} "Home"]
        [:details {:open true :tw details-tw}
         [:summary {:tw a-tw :href "/"}
          "Athletes"]
         [:div {:tw details-content-tw}
          [:ul {:tw "list-none p-2 w-full"}
          [:li [:a {:tw a-tw :href "/athletes"} "Athlete Bios"]]
          [:li [:a {:tw a-tw :href "/page/champions"} "Champions"]]
          [:li [:a {:tw a-tw :href "/page/hall-of-fame"} "Hall of Fame"]]]]]
        [:details {:open true :tw details-tw}
         [:summary {:tw a-tw :href "/"}
          "Competitions"]
         [:div {:tw details-content-tw}
          [:ul {:tw "list-none p-2 w-full"}
          [:li [:a {:tw a-tw :href "/page/rules"} "CSAF Rules"]]
          [:li [:a {:tw a-tw :href "/page/setup"} "Games Setup"]]
          [:li [:a {:tw a-tw :href "/games"} "View Games History"]]
          [:li [:a {:tw a-tw :href "/rankings"} "Rankings"]]
          [:li [:a {:tw a-tw :href "/records"} "Records"]]]]]
        [:a {:tw a-tw :href "/page/information"} "Information"]
        [:a {:tw a-tw :href "/page/about-csaf"} "About CSAF"]
        [:a {:tw a-tw :href "/page/contact-us"} "Contact Us"]])]]
   [:div {:tw "flex-grow"} content]
   [:footer {:tw "self-end mx-4 flex flex-row gap-3 items-center"}
    [:a {:href "/members"} "Upload Results"]
    (when logged-in-user
      [:<>
       [:span "Logged in as " (:members/login logged-in-user)]
       [:form {:method :post :action "/api/logout"}
        [:button "Log Out"]]])]])
