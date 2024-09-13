(ns csaf.util)

(defn ?>
  ([x pred then-f]
   (if (pred x) (then-f x) x))
  ([x pred then-f else-f]
   (if (pred x) (then-f x) (else-f x))))

(defn nan?
  [x]
  #?(:cljs (js/isNaN x)
     :clj (Double/isNaN x)))

(defn current-year
  []
  #?(:clj (+ 1900 (.getYear (java.util.Date.)))
     :cljs (+ 1900 (.getYear (js/Date.)))))
