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
   [csaf.client.pages-editor :as pages-editor]
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
  ([content] (page content {}))
  ([content opts]
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
                                                (string/join " "))}]))))
          (for [tag (opts :head)]
            tag)]
         [:body [:div#app (tw->class content)]
          (for [tag (opts :after-body)]
            tag)]]
        (huff/page {:allow-raw true}))))

(defn ->int [s]
  (try
    (Long. s)
    (catch java.lang.NumberFormatException _
      nil)))

(defn ->float [s]
  (try
    (Double/parseDouble s)
    (catch java.lang.NumberFormatException _
      nil)))

(comment
  (map ->float ["123.4" "123" "" "abc" ])
  )

(def class-names
  #{"juniors" "lightweight" "amateurs" "open" "masters" "womens" "womensmaster"})

(defn logged-in-user
  [req]
  (some-> (get-in req [:session :user-id])
          (db/member)))

(defn user-is-admin?
  [req]
  (some-> req
          (get-in [:session :user-id])
          (db/member-roles)
          (contains? :admin)
          boolean))

(def routes
  [
   [[:get "/"]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (-> (if-let [content (db/load-page "home")]
                   [:div.page [:hiccup/raw-html (:pages/content content)]]
                   (csaf.client.home/home-view))
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

   [[:get "/records/submit"]
    (fn [req]
      (if (some? (logged-in-user req))
        (cond->
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (-> (records/submit-record-view {:members (db/all-members)
                                                    :message (get-in req [:session :flash])})
                       (layout/layout (logged-in-user req))
                       page)}
          (some? (get-in req [:session :flash]))
          (assoc :session (dissoc (req :session) :flash)))
        {:status 401}))]

   [[:post "/records/submit"]
    (fn [req]
      (let [data (get-in req [:params])
            year (->int (:year data))
            distance-feet (->int (:distance-feet data))
            distance-inches (->float (:distance-inches data))
            weight (->float (:weight data))]
        (if (or (some nil? [year distance-feet distance-inches weight])
                (string/blank? (:name data))
                (not (contains? (set results/classes-in-order) (:class data)))
                (not (contains? (disj (set results/events-in-order) "caber")
                                (:event data))))
          {:status 400
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (-> (records/submit-record-view
                       {:members (db/all-members)
                        :error "Missing required data"
                        :data data})
                     (layout/layout (logged-in-user req))
                     page)}
          (do (db/submit-new-record-for-approval
                {:class (:class data)
                 :event (:event data)
                 :athlete-name (:name data)
                 :distance-inches (+ distance-inches (* 12 distance-feet))
                 :weight weight
                 :year year
                 :comment (:comment data)})
              {:status 303
               :headers {"Location" "/records/submit"}
               :session (assoc (:session req) :flash "Record submitted")}))))]

   [[:get "/records/:id/approve"]
    (fn [req]
      (let [submission (some-> (get-in req [:params :id])
                               ->int
                               (db/record-submission))]
        (if (user-is-admin? req)
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (-> (records/submit-record-view
                       {:members (db/all-members)
                        :data submission
                        :approving? true})
                     (layout/layout (logged-in-user req))
                     page)}
          {:status 403})))]

   [[:post "/records/:id/approve"]
    (fn [req]
      (let [data (get-in req [:params])
            id (->int (:id data))
            year (->int (:year data))
            distance-feet (->int (:distance-feet data))
            distance-inches (->float (:distance-inches data))
            weight (->float (:weight data))]
        (if (or (some nil? [id year distance-feet distance-inches weight])
                (string/blank? (:name data))
                (not (contains? (set results/classes-in-order) (:class data)))
                (not (contains? (disj (set results/events-in-order) "caber")
                                (:event data))))
          {:status 400
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (-> (records/submit-record-view
                       {:members (db/all-members)
                        :error "Missing required data"
                        :approving? true
                        :approving true
                        :data (some-> id (db/record-submission))})
                     (layout/layout (logged-in-user req))
                     page)}
          (do
            ;; passing data in again to allow editing. Does this make sense?
            ;; should we also edit the submission?
            (db/submit-new-record!
              {:class (:class data)
               :event (:event data)
               :athlete-name (:name data)
               :distance-inches (+ distance-inches (* 12 distance-feet))
               :weight weight
               :year year
               :comment (:comment data)})
            (db/approve-submission! id)
            {:status 303
             :headers {"Location" "/members"}}))))]

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
                    (db/rankings-for-year (+ 1900 (.getYear (java.util.Date.))))
                    :available-years (db/available-years-for-records)})
                 (layout/layout (logged-in-user req))
                 page)})]

   [[:get "/rankings/:year"]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (let [year (->int (get-in req [:params :year]))]
               (-> (rankings/rankings-view
                     {:rankings-by-class (db/rankings-for-year year)
                      :year year
                      :available-years (db/available-years-for-records)})
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
                   (user-is-admin? req)
                   (-> (assoc :submitted-sheets
                              (db/submitted-score-sheets))
                       (assoc :pending-records
                              (->> (db/record-submissions)
                                   (x/transform
                                     [x/ALL
                                      (x/multi-path
                                        :event-record-submissions/weight
                                        :event-record-submissions/distance-inches)]
                                     float)))))})
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

   [[:post "/api/score-sheets/:id/cancel-submit"]
    (fn [req]
      (if-let [user-id (get-in req [:session :user-id])]
        (if-let [sheet-id (->int (get-in req [:params :id]))]
          {:status 200
           :body (db/cancel-sheet-approval
                   {:sheet-id sheet-id
                    :user-id user-id})}
          {:status 400})
        {:status 403}))]

   [[:post "/api/score-sheets/:id/approve"]
    (fn [req]
      (if (user-is-admin? req)
        (if-let [sheet-id (->int (get-in req [:params :id]))]
          {:status 200
           :body (db/approve-sheet! {:sheet-id sheet-id})}
          {:status 400})
        {:status 403}))]

   [[:post "/api/score-sheets/:id/retract"]
    (fn [req]
      (if (user-is-admin? req)
        (if-let [sheet-id (->int (get-in req [:params :id]))]
          {:status 200
           :body (db/retract-sheet! sheet-id)}
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

   [[:get "/admin/pages"]
    (fn [req]
      (if (user-is-admin? req)
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (-> (pages-editor/pages-editor-view (db/all-page-titles))
                   (layout/layout (logged-in-user req))
                   page)}
        {:status 403}))]

   [[:post "/admin/pages"]
    (fn [req]
      (if (user-is-admin? req)
        (let [title (get-in req [:params :title])]
          (try
            (db/create-page! title)
            {:status 303
             :headers {"Location" (str "/admin/pages/" title)}}
            (catch org.postgresql.util.PSQLException _
              {:status 303
               :headers {"Location" "/admin/pages"}
               :body (-> (pages-editor/pages-editor-view (db/all-page-titles))
                         (layout/layout (logged-in-user req))
                         page)})))
        {:status 403}))]

   [[:get "/admin/pages"]
    (fn [req]
      (if (user-is-admin? req)
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (-> (pages-editor/pages-editor-view (db/all-page-titles))
                   (layout/layout (logged-in-user req))
                   page)}
        {:status 403}))]

   [[:get "/admin/pages/:title"]
    (fn [req]
      (let [title (get-in req [:params :title])]
        (if (user-is-admin? req)
          (if-let [loaded-page (db/load-page title)]
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body  (-> (pages-editor/page-editor-view {:title title
                                                        :content (:pages/content loaded-page)})
                        (layout/layout (logged-in-user req))
                        (page {:head [[:link {:href "/css/quill-2.0.2-snow.css" :rel "stylesheet"}]]}))}
            {:status 404})
          {:status 403})))]

   [[:post "/admin/pages/:title"]
    (fn [req]
      (let [title (get-in req [:params :title])]
        (if (user-is-admin? req)
          (if (some? (db/load-page title))
            (do (db/set-page-content! title (get-in req [:params :content]))
                {:status 303
                 :headers {"Location" (str "/admin/pages/" title)}})
            {:status 404})
          {:status 403})))]

   [[:get "/page/:page"]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (-> (if-let [content (db/load-page (get-in req [:params :page]))]
                   [:div.page [:hiccup/raw-html (:pages/content content)]]
                   (csaf.client.home/home-view))
                 (layout/layout (logged-in-user req))
                 page)})]
   ])
