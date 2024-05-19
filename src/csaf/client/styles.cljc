(ns csaf.client.styles
  (:require
   [garden.stylesheet]))

(def a-tw "text-red-800 hover:text-red-300 no-underline")

(defn styles []
  [
   ["@font-face"
    {:font-family "Urbanist"
     :src [["url(\"/fonts/Urbanist-VariableFont_wght.ttf\")" "format(\"opentype\")"]]
     :font-style "normal"}]

   (garden.stylesheet/at-keyframes
     "changeGalleryImage"
     (let [imgs (for [i (range 9)] (str "image_" i ".jpg"))
           step-per-image (float (/ 100 (count imgs)))]
       (into []
             (comp (map-indexed vector)
                   (mapcat (fn [[idx img]]
                             [[(str (* step-per-image idx) "%")
                               {:background-image
                                (str "url(\"/images/gallery/" img "\")")
                                :opacity 0}]
                              [(str (* step-per-image (+ idx 0.2)) "%")
                               {:background-image
                                (str "url(\"/images/gallery/" img "\")")
                                :opacity 1}]
                              [(str (* step-per-image (+ idx 0.7)) "%")
                               {:background-image
                                (str "url(\"/images/gallery/" img "\")")
                                :opacity 1}]
                              [(str (* step-per-image (+ idx 0.9)) "%")
                               {:background-image
                                (str "url(\"/images/gallery/" img "\")")
                                :opacity 0}]])))
             imgs)))

   [:body
    {:background-image "url(/images/celtic_knot_bg.jpg)"}]

   [:table
    [:tbody
     ["tr:nth-child(odd)" {:background-color "rgba(243,244,246,var(--gi-bg-opacity))"} ]]]

   [:button
    {:padding "0.25rem 0.5rem"
     :border-radius "0.25rem"
     :background-color "rgba(243,244,246,var(--gi-bg-opacity))"
     :border "1px solid black"}]

   [:.font-urbanist
    {:font-family "'Urbanist', sans-serif"
     :font-weight "200"}]

   [:.font-sans-serif
    {:font-family "sans-serif"}]

   ])
