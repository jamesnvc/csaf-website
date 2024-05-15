(ns csaf.server.routes
  (:require
   [com.rpl.specter :as x]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [bloom.omni.impl.crypto :as crypto]
   [huff2.core :as huff]
   [csaf.client.home]
   [csaf.client.athletes :as athletes]
   [csaf.client.games :as games]
   [csaf.client.layout :as layout]
   [csaf.util :refer [?>]]
   [csaf.server.db :as db]))

(defn tw->class
  [content]
  (->> content
       (x/transform
         [(x/walker map?) (x/pred (fn [m] (contains? m :tw)))]
         (fn [{tw :tw :as m}]
           (->> m
                (x/transform
                  :class
                  (fn [cs]
                    (str cs " "
                         (cond
                           (string? tw) tw
                           (seq tw) (string/join " " (remove nil? tw))))))
                (x/setval :tw x/NONE))))))

(defn page
  [content]
  (->> [:html {:lang "en"}
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
        [:body [:div#app (tw->class content)]]]
       (huff/page {:allow-raw true})))

(defn ->int [s]
  (try
    (Long. s)
    (catch java.lang.NumberFormatException _
      nil)))

(def class-names
  #{"juniors" "lightweight" "amateurs" "open" "masters" "womens" "womensmaster"})

(def routes
  [
   [[:get "/"]
    (fn [_]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (->> (csaf.client.home/home-view [:h1 "Coming Soon"])
                  layout/layout
                  page)})]
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
         :body (->> (-> (db/member athlete-id)
                        ;; TODO: use datafy/nav to do this?
                        (assoc :member/game-results (db/member-game-results athlete-id)
                               :member/prs (db/member-pr-results athlete-id)))
                    athletes/athlete-view
                    layout/layout
                    page)}
        {:status 404}))
    []]

   [[:get "/games"]
    (fn [{{:strs [filter-year class]} :query-params}]
      (let [avail-years (db/available-years-for-records)
            filter-year (or (->int filter-year)
                            (:year (first avail-years)))
            classes (->> (?> class string? vector)
                         (filter class-names)
                         seq)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (->> (games/games-history-view
                      {:available-years avail-years
                       :selected {"filter-year" (str filter-year)
                                  "class" classes}
                       :games (db/games-history
                                {:year filter-year
                                 :classes classes})})
                    layout/layout
                    page)}))]
   ])
