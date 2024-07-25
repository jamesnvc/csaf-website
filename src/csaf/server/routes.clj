(ns csaf.server.routes
  (:require
   [com.rpl.specter :as x]
   [clojure.java.io :as io]
   [clojure.data.csv :as csv]
   [clojure.string :as string]
   [bloom.omni.impl.crypto :as crypto]
   [huff2.core :as huff]
   [csaf.client.home]
   [csaf.client.athletes :as athletes]
   [csaf.client.games :as games]
   [csaf.client.rankings :as rankings]
   [csaf.client.results :as results]
   [csaf.client.records :as records]
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

(defn logged-in-user
  [req]
  (some-> (get-in req [:session :user-id])
          (db/member)))

(def routes
  [
   [[:get "/"]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (-> (csaf.client.home/home-view)
                 (layout/layout (logged-in-user req))
                 page)})]

   [[:get "/athletes"]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (-> (db/all-members)
                 athletes/all-athletes-view
                 (layout/layout (logged-in-user req))
                 page)})
    []]

   [[:get "/athletes/:id"]
    (fn [req]
      (if-let [athlete-id (->int (get-in req [:params :id]))]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (-> (db/member athlete-id)
                   ;; TODO: use datafy/nav to do this?
                   (assoc :member/game-results (db/member-game-results athlete-id)
                          :member/prs (db/member-pr-results athlete-id))
                   athletes/athlete-view
                   (layout/layout (logged-in-user req))
                   page)}
        {:status 404}))
    []]

   [[:get "/records"]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (-> (db/current-records)
                 records/records-view
                 (layout/layout (logged-in-user req))
                 page)})
    []]

   [[:get "/games"]
    (fn [{{:strs [filter-year class filter-event]} :query-params :as req}]
      (let [avail-years (db/available-years-for-records)
            filter-year (or (->int filter-year)
                            (:year (first avail-years)))
            filter-event (some->> filter-event
                           ((set results/events-in-order)))
            classes (->> (?> class string? vector)
                         (filter class-names)
                         seq)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (-> (games/games-history-view
                     {:available-years avail-years
                      :selected {"filter-year" (str filter-year)
                                 "class" classes
                                 "filter-event" filter-event}
                      :games (db/games-history
                               {:year filter-year
                                :classes classes
                                :event filter-event})})
                    (layout/layout (logged-in-user req))
                    page)}))]

   [[:get "/rankings"]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (-> (rankings/rankings-view
                   {:rankings-by-class
                    (db/rankings-for-year (+ 1900 (.getYear (java.util.Date.))))})
                 (layout/layout (logged-in-user req))
                 page)})]

   [[:get "/rankings/:year"]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (let [year (->int (get-in req [:params :year]))]
               (-> (rankings/rankings-view
                     {:rankings-by-class (db/rankings-for-year year)
                      :year year})
                   (layout/layout (logged-in-user req))
                   page))})]

   [[:post "/api/checkauth"]
    (fn [req]
      (if (get-in req [:session :user-id])
        {:status 200}
        {:status 401}))]

   [[:post "/api/authenticate"]
    (fn [req]
      (if-let [user-id (db/authenticate-user {:login (get-in req [:body-params :username])
                                              :password (get-in req [:body-params :password])})]
        {:status 200
         :session (assoc (:session req) :user-id user-id)}
        {:status 401}))]

   [[:post "/api/logout"]
    (fn [req]
      {:status 302
       :headers {"Location" "/"}
       :session (dissoc (req :session) :user-id)})]

   [[:get "/api/init-data"]
    (fn [req]
      (if-let [user-id (get-in req [:session :user-id])]
        (let [user (logged-in-user req)]
          {:status 200
           :body (cond-> {:sheets (db/member-score-sheets user-id)
                          :logged-in-user user
                          :members (db/all-members)
                          :games (db/all-games-names)}
                   (= "admin" (:members/site-code user))
                   (assoc :submitted-sheets
                          (db/submitted-score-sheets)))})
        {:status 403}))]

   [[:post "/api/score-sheets/new"]
    (fn [req]
      (if-let [user-id (get-in req [:session :user-id])]
        {:status 200
         :body (db/add-new-score-sheet! user-id)}
        {:status 403}))]

   [[:post "/api/score-sheets/:id"]
    (fn [req]
      (if-let [user-id (get-in req [:session :user-id])]
        (if-let [sheet-id (->int (get-in req [:params :id]))]
          {:status 200
           :body (db/update-sheet-for-user
                   {:sheet-id sheet-id
                    :user-id user-id
                    :sheet (get-in req [:body-params :sheet])})}
          {:status 400})
        {:status 403}))]

   [[:post "/api/score-sheets/:id/submit"]
    (fn [req]
      (if-let [user-id (get-in req [:session :user-id])]
        (if-let [sheet-id (->int (get-in req [:params :id]))]
          {:status 200
           :body (db/submit-sheet-for-approval
                   {:sheet-id sheet-id
                    :user-id user-id})}
          {:status 400})
        {:status 403}))]

   [[:post "/api/games/new"]
    (fn [req]
      (if (some? (logged-in-user req))
        (let [game-name (get-in req [:body-params :game-name])]
          (if (not (string/blank? game-name))
            {:status 200
             :body {:new-game (db/add-new-game! game-name)}}
            {:status 400}))
        {:status 403}))]

   [[:post "/api/csvify"]
    (fn [req]
      (when (get-in req [:session :user-id])
        {:status 200
         :body {:data (csv/read-csv (get-in req [:body-params :csv-text]))}}))]
   ])
