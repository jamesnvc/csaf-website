(ns csaf.client.styles
  (:require
   [garden.stylesheet]))

(defn styles []
  [
   ["@font-face"
    {:font-family "Urbanist"
     :src [["url(\"/fonts/Urbanist-VariableFont_wght.ttf\")" "format(\"opentype\")"]]
     :font-style "normal"}]

   (garden.stylesheet/at-keyframes
     "changeGalleryImage"
     [[" 0%" {:background-image "url(\"/images/gallery/image_0.jpg\")"}]
      ["10%" {:background-image "url(\"/images/gallery/image_1.jpg\")"}]
      ["20%" {:background-image "url(\"/images/gallery/image_2.jpg\")"}]
      ["30%" {:background-image "url(\"/images/gallery/image_3.jpg\")"}]
      ["40%" {:background-image "url(\"/images/gallery/image_4.jpg\")"}]
      ["50%" {:background-image "url(\"/images/gallery/image_5.jpg\")"}]
      ["60%" {:background-image "url(\"/images/gallery/image_6.jpg\")"}]
      ["70%" {:background-image "url(\"/images/gallery/image_7.jpg\")"}]
      ["80%" {:background-image "url(\"/images/gallery/image_8.jpg\")"}]
      ["90%" {:background-image "url(\"/images/gallery/image_9.jpg\")"}]])

   [:body
    {:background-image "url(/images/celtic_knot_bg.jpg)"}]

   [:.font-urbanist
    {:font-family "'Urbanist', sans-serif"
     :font-weight "200"}]

   [:.font-sans-serif
    {:font-family "sans-serif"}]

   ])
