(ns csaf.client.styles)

(defn styles []
  [
   ["@font-face"
    {:font-family "Urbanist"
     :src [["url(\"/fonts/Urbanist-VariableFont_wght.ttf\")" "format(\"opentype\")"]]
     :font-style "normal"}]

   [:body
    {:background-image "url(/images/celtic_knot_bg.jpg)"}]

   [:.font-urbanist
    {:font-family "'Urbanist', sans-serif"
     :font-weight "200"}]

   ])
