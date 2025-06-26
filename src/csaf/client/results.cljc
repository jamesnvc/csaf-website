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
             #?(:cljs #(str (.toFixed % 2) "″")
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

(defn display-class
  [cls]
  (cond-> cls
    (string/starts-with? cls "womens")
    (string/replace #"^womens" "women's ")
    (re-matches #".*\d+\+$" cls)
    (string/replace #"(\d+\+)$" " $1")
    true (string/replace #"(\S+)\b" (fn [[_ w]] (string/capitalize w)))))

(comment
  (map display-class classes-in-order)
  )

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
  ["open" "masters" "lightweight" "juniors" "womens" "womensmaster" "womensyouth" "amateurs"
   "womensjunior" "youth" "masters50+" "masters60+" "womensmaster50+" "womensmaster60+"])

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
   "womenmaster" "womensmaster"
   "youth female" "womensyouth"
   "womensyouth" "womensyouth"
   "masters50+" "masters50+"
   "masters60+" "masters60+"
   "womensmaster50+" "womensmaster50+"
   "womensmaster60+" "womensmaster60+"
   "youth" "youth"
   "womensjunior" "womensjunior"})

(defn parse-clock-minutes
  [s]
  (when-let [[_ hrs mins] (re-matches #"(\d+):(\d{2})" s)]
    (+ (* 60 (->int hrs)) (->int mins))))

(defn parse-distance
  [s]
  (if (re-matches #"\d+" s)
    (->float s)
    (let [[_ ft ins] (re-matches #"(\d+)['’](?:(\d+(?:[.]\d+)?)?\")?" s)
          d (when (->int ft)
              (+ (* 12 (->int ft))
                 (or (some-> ins (->float)) 0)))]
      d)))

(comment
  (string/replace "50'" #"[^0-9]+$" "")
  (->int "50'")
  (parse-distance "16’")
  )

(defn result-row->game-results
  [headers row]
  (let [row-keys (zipmap (map string/lower-case headers) row)
        base {:name (get row-keys "name")
              :placing (->int (get row-keys "placing"))
              ;; TODO validate class
              :class (class-names (string/lower-case (get row-keys "class")))
              :country (get row-keys "country" "Canada")}
        abbrev->name (into {}
                           (map (fn [[k v]] [(string/lower-case v) k]))
                           abbrev-event-name)]
    (->> (reduce
           (fn [res evt]
             (if (and (if (= evt "cabr")
                        (and (get row-keys evt)
                             (re-matches #"\d{1,2}:\d{2}" (get row-keys evt))
                             (get row-keys (str evt "_length"))
                             (not= 0 (->float (get row-keys (str evt "_length")))))
                        (or (and (get row-keys evt)
                                 (not (string/blank? (get row-keys evt))))
                            (and (get row-keys (str evt "_feet"))
                                 (get row-keys (str evt "_inches"))
                                 (not (string/blank?
                                        (get row-keys (str evt "_feet")))))))
                      (get row-keys (str evt "_weight"))
                      (not (string/blank? (get row-keys (str evt "_weight"))))
                      (not= 0 (->float (get row-keys (str evt "_weight")))))
               (assoc-in res [:events (abbrev->name evt)]
                         (cond-> {:weight (->float (get row-keys (str evt "_weight")))}
                           (= evt "cabr")
                           (-> (assoc :clock-minutes (parse-clock-minutes (get row-keys evt)))
                               (assoc :distance-inches
                                      (parse-distance (get row-keys "cabr_length"))))

                           (and (not= evt "cabr") (get row-keys (str evt "_feet")))
                           (assoc :distance-inches
                                  (+ (* 12 (->int (string/replace (get row-keys (str evt "_feet"))
                                                                  #"[^0-9]*$" "")))
                                     (or (->float
                                           (string/replace (get row-keys (str evt "_inches"))
                                                           #"[^0-9]*$" "")) 0)))

                           (and (not= evt "cabr") (get row-keys evt))
                           (assoc :distance-inches (parse-distance (get row-keys evt)))))
               res))
           base
           (map string/lower-case (vals abbrev-event-name))))))

(comment
  (result-row->game-results ["name" "country" "class" "placing" "brae_feet" "brae_inches" "brae_weight"]
                            ["James" "Canada" "Amateurs" "1" "10'" "10\"" "20"])
  (->float "10\"")
  )
