(ns csaf.client.results
  (:require
   [clojure.string :as string]
   [csaf.util :refer [?> nan?]]))

(defn float= [x y]
  (let [[x y] (map float [x y])]
    (and (not (< x y)) (not (> x y)))))

(defn display-distance
  [dist-inches]
  (when (some? dist-inches)
    (str (?> (int (/ (float dist-inches) 12)) zero? (constantly "") #(str % "′"))
         (?> (mod dist-inches 12) zero? (constantly "")
             #?(:cljs #(str % "″")
                :clj (fn [x]
                       (if (float= x (int x))
                         (format "%d″" (int x))
                         (format "%.1f″" x))))))))

(defn display-weight
  [weight]
  #?(:cljs (str weight "lb")
     :clj (if (float= weight (int weight))
            (format "%dlb" (int weight))
            (format "%.1flb" (float weight)))))

(defn display-clock
  [clock-minutes]
  #?(:cljs (str (int (/ clock-minutes 60)) ":" (mod clock-minutes 60))
     :clj (format "%02d:%02d"
                  (?> (int (/ clock-minutes 60)) zero? (constantly 12))
                  (mod clock-minutes 60))))

(def display-event-name
  {"braemar" "Braemar Stone"
   "open" "Open Stone"
   "sheaf" "Sheaf"
   "caber" "Caber"
   "lwfd" "Light Weight for Distance"
   "hwfd" "Heavy Weight for Distance"
   "lhmr" "Light Hammer"
   "hhmr" "Heavy Hammer"
   "wob" "Weight Over Bar"})

(def abbrev-event-name
  {"braemar" "BRAE"
   "open" "STON"
   "sheaf" "SHF"
   "caber" "CABR"
   "lwfd" "LWFD"
   "hwfd" "HWFD"
   "lhmr" "LHMR"
   "hhmr" "HHMR"
   "wob" "WOB"})

(def events-in-order
  ["braemar" "open" "sheaf" "caber" "lwfd" "hwfd" "lhmr" "hhmr" "wob"])

(def classes-in-order
  ["open" "masters" "lightweight" "juniors" "womens" "womensmasters" "amateurs"])

(defn ->int
  [s]
  #?(:cljs (js/parseInt s 10)
     :clj (try (Long. s) (catch NumberFormatException _ nil))))

(defn ->float
  [s]
  #?(:cljs (?> (js/parseFloat s) nan? (constantly nil))
     :clj (try (Float. s) (catch NumberFormatException _ nil))))

(def class-names
  {"juniors" "juniors"
   "junior" "juniors"
   "lightweight" "lightweight"
   "amateurs" "amateurs"
   "amateur" "amateurs"
   "open" "open"
   "masters" "masters"
   "master" "masters"
   "womens" "womens"
   "women" "womens"
   "womensmaster" "womensmaster"
   "womensmasters" "womensmaster"
   "womenmasters" "womensmaster"
   "womenmaster" "womensmaster"})

(defn parse-clock-minutes
  [s]
  (let [[_ hrs mins] (re-matches #"(\d+):(\d{2})" s)]
    (+ (* 60 (->int hrs)) (->int mins))))

(defn parse-distance
  [s]
  (if (re-matches #"\d+" s)
    (->float s)
    (let [[_ ft ins] (re-matches #"(\d+)'(?:(\d+(?:[.]\d+)?)?\")?" s)
          d (+ (* 12 (->int ft))
               (or (some-> ins (->float)) 0))]
      d)))

(defn result-row->game-results
  [headers row]
  (let [row-keys (zipmap (map string/lower-case headers) row)
        base {:name (get row-keys "name")
              :placing (->int (get row-keys "placing"))
              ;; TODO validate class
              :class (class-names (string/lower-case (get row-keys "class")))}
        abbrev->name (into {}
                           (map (fn [[k v]] [(string/lower-case v) k]))
                           abbrev-event-name)]
    (->> (reduce
           (fn [res evt]
             (if (and (get row-keys evt)
                      (not (string/blank? (get row-keys evt)))
                      (get row-keys (str evt "_weight"))
                      (not (string/blank? (get row-keys (str evt "_weight"))))
                      (not= 0 (->float (get row-keys (str evt "_weight")))))
               (assoc-in res [:events (abbrev->name evt)]
                         (cond-> {:weight (->float (get row-keys (str evt "_weight")))}
                           (= evt "cabr")
                           (-> (assoc :clock-minutes (parse-clock-minutes (get row-keys evt)))
                               (assoc :distance-inches
                                      (parse-distance (get row-keys "cabr_length"))))
                           (not= evt "cabr")
                           (assoc :distance-inches (parse-distance (get row-keys evt)))))
               res))
           base
           (map string/lower-case (vals abbrev-event-name))))))
