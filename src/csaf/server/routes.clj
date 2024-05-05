(ns csaf.server.routes
  (:require
   [com.rpl.specter :as x]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [bloom.omni.impl.crypto :as crypto]
   [hiccup.core :as hiccup]
   [hiccup.page :as hiccup-page]
   [csaf.client.athletes :as athletes]
   [csaf.client.layout :as layout]
   [csaf.server.db :as db]))

(defn reagent->hiccup
  [content]
  (->> content
       (x/transform
         [(x/walker map?)
          (x/pred (fn [m]
                    (or (contains? m :tw)
                        (contains? m :style))))]
         (fn [{tw :tw :as m}]
           (->> m
                (x/transform
                  :class
                  (fn [cs]
                    (str cs " "
                         (cond
                           (string? tw) tw
                           (seq tw) (string/join " " (remove nil? tw))))))
                (x/setval :tw x/NONE)
                (x/transform
                  [:style (x/pred map?)]
                  (fn [style]
                    (->> style
                         (map (fn [[k v]] (str (name k) ": " v ";")))
                      (string/join " ")))))))))

(defn page
  [content]
  (->> [:html
        [:head
         [:title "CSAF"]
         [:meta {:name "viewport"
                 :content "user-scalable=no, initial-scale=1, maximum-scale=1, minimum-scale=1, width=device-width"}]
         (when (io/resource "public/manifest.webmanifest")
           [:link {:rel "manifest" :href "/manifest.webmanifest"}])
         (->> ["public/css/twstyles.css" "public/css/styles.css"]
              (map (fn [path]
                     (let [digest (crypto/sha256-file (io/resource path))
                           digest-gz (crypto/sha256-file (io/resource (str path ".gz")))]
                       [:link {:rel "stylesheet"
                               :href (str (string/replace path "public" "") "?v=" digest)
                               :media "screen"
                               :integrity (->> [digest digest-gz]
                                               (remove nil?)
                                               (map (fn [d] (str "sha256-" d)))
                                               (string/join " "))}]))))]
        [:body [:div#app (reagent->hiccup content)]]]
       (hiccup/html (hiccup-page/doctype :html5))))

(defn ->int [s]
  (try
    (Long. s)
    (catch java.lang.NumberFormatException _
      nil)))

(def routes
  [
   [[:get "/athletes"]
    (fn [_]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (->> (db/all-members)
                  athletes/all-athletes-view
                  layout/layout
                  page)})
    []]

   [[:get "/athletes/:id"]
    (fn [req]
      (if-let [athlete-id (->int (get-in req [:params :id]))]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (->> (db/member athlete-id)
                    athletes/athlete-view
                    layout/layout
                    page)}
        {:status 404}))
    []]
   ])
