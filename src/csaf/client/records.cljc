(ns csaf.client.records
  (:require
   [clojure.math :as math]
   [clojure.string :as string]
   [com.rpl.specter :as x]
   [csaf.client.results :as results]
   [csaf.util :as util]))

(defn real-records-view
  [records]
  [:div {:tw "flex flex-col gap-4"}
     [:p "Records marked with ** indicate a current world record"]
     (for [cls results/classes-in-order
           :let [class-records (get records cls)]
           :when (seq class-records)]
       [:div
        [:h2 {:tw "text-center text-lg text-gray-500 font-smallcaps"}
         (results/display-class cls)]
        [:table
         [:thead
          [:th "Event"]
          [:th "Athlete"]
          [:th "Distance"]
          [:th "Weight"]
          [:th "Year"]
          [:th "Comment"]]
         [:tbody
          (for [event results/events-in-order
                :let [event-records (get class-records event)]
                record event-records
                :when (some? record)]
            [:tr
             [:td (results/display-event-name event)]
             [:td (:event-record/athlete-name record)]
             [:td (results/display-distance
                    (:event-record/distance-inches record))]
             [:td (results/display-weight (:event-record/weight record))]
             [:td (:event-record/year record)]
             [:td {:tw "text-sm"} (:event-record/comment record)]])]]])])

(defn top-non-record-results
  [{:keys [records top-results]}]
  (let [non-record-results (->> top-results
                                (x/setval
                                  [x/MAP-VALS x/MAP-VALS
                                   x/ALL
                                   (x/if-path
                                     (fn [res]
                                       (some? (get-in records [(:game-member-results/class res)
                                                               (:game-member-results/event res)])))
                                     x/STAY)]
                                  x/NONE)
                                (x/setval
                                  [x/MAP-VALS
                                   (x/if-path (x/pred #(every? empty? (vals %)))
                                              x/STAY)]
                                  x/NONE))]
    [:div {:tw "flex flex-col gap-4"}
     [:p "Current top results that are not official records"]
     (for [cls results/classes-in-order
           :let [class-results (get non-record-results cls)]
           :when (seq class-results)]
       [:div
        [:h2 {:tw "text-center text-lg text-gray-500 font-smallcaps"}
         (results/display-class cls)]
        [:table
         [:thead
          [:th "Event"]
          [:th "Athlete"]
          [:th "Distance"]
          [:th "Weight"]
          [:th "Date"]]
         [:tbody
          (for [event results/events-in-order
                :let [event-results (get class-results event)]
                result event-results
                :when (some? result)]
            [:tr
             [:td (results/display-event-name event)]
             [:td (:members/last-name result) ", " (:members/first-name result)]
             [:td (results/display-distance
                    (:game-member-results/distance-inches result))]
             [:td (results/display-weight
                    (:game-member-results/weight result))]
             [:td (str (:game-instances/date result))]])]]])]))

(defn records-view
  [{:keys [records top-results]}]
  [:div {:tw "flex flex-col gap-4"}
   [real-records-view records]
   [:hr]
   [top-non-record-results {:top-results top-results
                            :records records}]])

(defn submit-record-view
  [{:keys [members error message data approving?]}]
  [:div {:tw "flex flex-col gap-4"}
   (if approving?
     [:h1 "Approve a new record"]
     [:h1 "Submit a new record"])
   (when error
     [:div {:tw "text-red-500"} error])
   (when message [:div {:tw "text-blue-500"} message])
   [:form {:action (if (and approving?
                            (some? (get-in data [:event-record-submissions/id])))
                     (str "/records/"
                          (get-in data [:event-record-submissions/id])
                          "/approve")
                     "/records/submit")
           :method "post"
           :tw "flex flex-col md:grid md:grid-cols-2 mx-auto gap-3"}
    [:label "Athlete Name"
     [:input {:type "text" :name "name"  :list "member-names"
              :placeholder "Thrower McLastname"
              :tw "border border-black block w-full" :required true
              :value (:event-record-submissions/athlete-name data)}]]
    [:datalist {:id "member-names"}
     (for [{:members/keys [first-name last-name]} members]
       [:option {:value (str first-name " " last-name)}])]

    [:label "Year Set"
     [:input {:type "number" :name "year" :min "1990"
              :max (util/current-year) :tw "block"
              :value (or (:event-record-submissions/year data)
                       (util/current-year))
              :required true}]]

    [:label "Class"
     [:select {:name "class" :tw "block" :required true}
      (for [cls results/classes-in-order]
        [:option {:value cls
                  :selected (= cls (:event-record-submissions/class data))}
         (string/capitalize cls)])]]

    [:label "Event"
     [:select {:name "event" :tw "block" :required true}
      (for [event results/events-in-order
            :when (not= event "caber")]
        [:option {:value event
                  :selected (= event (:event-record-submissions/event data))}
         (results/display-event-name event)])]]

    [:fieldset
     [:legend "Distance"]
     [:label
      [:input
       {:type "number" :name "distance-feet" :placeholder "123"
        :tw "w-4rem text-right" :required true
        :value (when-let [inches (some-> data
                                     :event-record-submissions/distance-inches
                                     float)]
                 (int (math/floor (/ inches 12))))}]
      " ft "]
     [:label
      [:input {:type "number" :name "distance-inches" :step "0.01"
               :placeholder "4.56" :tw "w-4rem text-right" :required true
               :value (when-let [inches (some-> data
                                                :event-record-submissions/distance-inches
                                                float)]
                        (math/floor (mod inches 12))) }]
      " in"]]

    [:label "Implement Weight (lb)"
     [:input {:type "number" :name "weight" :step "0.01" :placeholder "56"
              :tw "text-right w-4rem block" :required true
              :value (some-> data
                             :event-record-submissions/weight
                             float)}]]

    [:label {:tw "col-span-2"} "Comments"
     [:textarea {:tw "block min-h-5rem border border-black w-full"
                 :name "comment"}
      (:event-record-submissions/comment data)]]

    [:button {:tw "col-span-2"}
     (if approving?
       "Approve"
       "Submit for approval")]]])
