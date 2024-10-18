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
    [:thead [:tr {:background-color "white"}]]
    [:tbody
     [:tr {:background-color "white"}]
     ["tr:nth-child(odd)" {:background-color "rgba(243,244,246,var(--gi-bg-opacity))"} ]]]

   [:table.results-upload-sheet
    [:.valid-member
     {:background-color "rgba(187,247,208,var(--gi-bg-opacity))"}]
    [:.missing-member
     {:background-color "rgba(254,226,226,var(--gi-bg-opacity))"}]]

   [:button
    {:padding "0.25rem 0.5rem"
     :border-radius "0.25rem"
     :background-color "rgba(243,244,246,var(--gi-bg-opacity))"
     :border "1px solid black"}]

   [:dl.expected-format
    [:dt {:font-weight "bold"}]]

   [:.font-urbanist
    {:font-family "'Urbanist', sans-serif"
     :font-weight "200"}]

   [:.font-sans-serif
    {:font-family "sans-serif"}]

   [:.font-smallcaps
    {:font-variant "small-caps"}]

   [:.page
    {:margin "1.5rem"}
    ["h1, h2, h3, h4, h5"
     {:color "rgba(126, 126, 126)"
      :margin-top "1rem"}]
    [:h1
     {:font-size "1.75rem"}]
    [:h2
     {:font-size "1.5rem"}]
    [:h3
     {:font-size "1.25rem"}]
    [:h4
     {:font-size "1.125rem"}]

    [:p
     {:margin "1rem 0"}]

    [:a
     {:color "rgba(153,27,27,1)"}
     [:&:hover
      {:color "rgba(252,165,165,1)"}]]

    [:ol {:list-style-type "decimal"}]
    [:ul {:list-style-type "disc"}]
    ["li > ol, li > ul"
     {:margin-inline-start "2rem"
      :list-style-type "lower-alpha"}]]
   ])
