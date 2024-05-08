(ns csaf.client.athletes
  (:require
   [clojure.string :as string]
   [csaf.client.styles :as styles]))

(defn all-athletes-view
  [athletes]
  [:div [:h1 "ATHLETES"]
   [:div {:tw "flex flex-col md:grid md:grid-cols-2 lg:grid-cols-3 gap-4"}
    (for [{:members/keys [id first-name last-name]} athletes]
      [:a {:href (str "/athletes/" id)
           :tw styles/a-tw}
       last-name ", " first-name])]] )

(defn ?>
  ([x pred then-f]
   (if (pred x) (then-f x) x))
  ([x pred then-f else-f]
   (if (pred x) (then-f x) (else-f x))))

(defn float= [x y]
  (let [[x y] (map float [x y])]
    (and (not (< x y)) (not (> x y)))))

(defn display-distance
  [dist-inches]
  (str (?> (int (/ (float dist-inches) 12)) zero? (constantly "") #(str % "′"))
       (?> (mod dist-inches 12) zero? (constantly "")
           #?(:cljs #(str % "″")
              :clj (fn [x]
                     (if (float= x (int x))
                       (format "%d″" (int x))
                       (format "%.1f″" x)))))))

(defn display-weight
  [weight]
  #?(:cljs (str weight "lbs")
     :clj (if (float= weight (int weight))
            (format "%dlbs" (int weight))
            (format "%.1flbs" (float weight)))))

(defn display-clock
  [clock-minutes]
  #?(:cljs (str (int (/ clock-minutes 60)) ":" (mod clock-minutes 60))
     :clj (format "%02d:%02d"
                  (?> (int (/ clock-minutes 60)) zero? (constantly 12))
                  (mod clock-minutes 60))))

(defn athlete-view
  [member]
  [:div {:tw "flex flex-col gap-4"}
   [:h2 {:tw "text-xl"} (:members/first-name member) " " (:members/last-name member)]
   (when-let [email (:members/email member)]
     [:a {:tw styles/a-tw :href (str "mailto:" email)} email])

   [:div.location
    (let [locale (->> (keep member [:members/city :members/province])
                      (string/join ", "))]
      (when-not (string/blank? locale)
        [:div locale]))
    [:div (:members/country member)]
    (when-let [postal (:members/postal-code member)]
      [:div postal])]

   [:div.stats
    (let [dt-tw "after:content-[\"::\"]"]
      [:dl {:tw "grid gap-1" :style {:grid-template-columns "max-content max-content"}}
       (when-let [height (:members/height member)]
         [:<> [:dt {:tw dt-tw} "Height "]
          [:dd height]])

       (when-let [weight (:members/weight member)]
         [:<> [:dt {:tw dt-tw} "Weight "]
          [:dd weight]])

       (when-let [tartan (:members/tartan member)]
         [:<> [:dt {:tw dt-tw} "Tartan "]
          [:dd tartan]])])]

   (when-let [bio (:members/biography member)]
     [:div.bio [:hiccup/raw-html bio]])

   [:div.prs
    [:h3 {:tw "text-lg text-gray-500"} "PERSONAL BESTS"]

    [:table {:tw "w-full"}
     [:thead
      [:tr [:th "Event"] [:th "Class"] [:th "Location"] [:th "Mark"] [:th "Date"]]]
     [:tbody
      (for [event-name ["braemar" "open" "sheaf" "caber" "lwfd" "hwfd" "lhmr" "hhmr" "wob"]
            :let [result (get-in member [:member/prs event-name])]]
        [:tr
         [:td event-name]
         [:td (string/capitalize (:class result))]
         [:td {:tw "text-sm"} (:games/name result)]
         [:td
          (->> [(when (= event-name "caber")
                  (some-> (:clock-minutes result) display-clock))
                (display-distance (:distance-inches result))
                (when (#{"braemar" "open" "sheaf" "caber"} event-name)
                  (some-> (:weight result) (?> zero? (constantly nil))
                          display-weight))]
               (remove nil?)
               (string/join " "))]
         [:td (str (:game-instances/date result))]])]]]


   [:div.record
    [:h3 {:tw "text-lg text-gray-500"} "GAMES RECORD"]
    [:table
     [:thead
      [:tr
       [:th "Date"]
       [:th "Location"]
       [:th "BRE"]
       [:th "STON"]
       [:th "SHF"]
       [:th "CABR"]
       [:th "LWFD"]
       [:th "HWFD"]
       [:th "LWFD"]
       [:th "HHMR"]
       [:th "WOB"]]]]]

   ]
  )
