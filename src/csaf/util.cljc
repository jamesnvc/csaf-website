(ns csaf.util)

(defn ?>
  ([x pred then-f]
   (if (pred x) (then-f x) x))
  ([x pred then-f else-f]
   (if (pred x) (then-f x) (else-f x))))
