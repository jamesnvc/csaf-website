(ns csaf.client.results
  (:require
   [csaf.util :refer [?>]]))

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
