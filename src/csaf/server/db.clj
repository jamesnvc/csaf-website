(ns csaf.server.db
  (:require
   [clojure.java.io :as io]
   [clojure.math :as math]
   [com.rpl.specter :as x]
   [next.jdbc :as jdbc]
   [next.jdbc.prepare :as jdbc.prepare]
   [next.jdbc.result-set :as jdbc.rs]
   [hikari-cp.core :as hikari]
   [csaf.config :as config]
   [jsonista.core :as json]
   [camel-snake-kebab.core :refer [->kebab-case]]
   [crypto.password.bcrypt :as bcrypt])
  (:import
   (org.postgresql.util PGobject)
   (java.sql PreparedStatement)))

;;; Configuration

(def mapper (json/object-mapper {:decode-key-fn keyword}))
(def ->json json/write-value-as-string)
(def <-json #(json/read-value % mapper))

(defn ->pgobject
  [x]
  (let [pgtype (or (:pgtype (meta x)) "jsonb")]
    (doto (PGobject.)
      (.setType pgtype)
      (.setValue (->json x)))))

(defn <-pgobject
  [^org.postgresql.util.PGobject v]
  (let [type (.getType v)
        value (.getValue v)]
    (if (#{"json" "jsonb"} type)
      (when value
        (with-meta (<-json value) {:pgtype type}))
      value)))

(extend-protocol jdbc.prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (.setObject s i (->pgobject m)))

  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject v))))

(extend-protocol jdbc.rs/ReadableColumn
  org.postgresql.util.PGobject
  (read-column-by-label [^org.postgresql.util.PGobject v _]
    (<-pgobject v))
  (read-column-by-index [^org.postgresql.util.PGobject v _2 _3]
    (<-pgobject v)))

(defonce datasource
  (delay (hikari/make-datasource
           {:jdbc-url
            (str "jdbc:"
                 (config/conf :csaf/database-url))
            #_#_:re-write-batched-inserts true})))

(comment
  (hikari/close-datasource @datasource)
  (jdbc/execute! @datasource ["drop table game_results_placing"])
  )

;;; Init

(defn init-db!
  []
  (jdbc/execute!
    @datasource
    [(slurp (io/resource "sql/create_tables.sql"))]))

;;; Migrations

(defn migrate-member-passwords!
  "Hash user passwords"
  []
  (jdbc/execute! @datasource ["alter table members add column old_password text"])
  (jdbc/execute! @datasource ["update members set old_password = password_hash"])
  (->> (jdbc/plan @datasource ["select id, old_password from members"])
       (run! (fn [row]
               (jdbc/execute!
                 @datasource
                 ["update members set password_hash = ? where id = ?"
                  (bcrypt/encrypt (:old_password row))
                  (:id row)]))))
  (jdbc/execute! @datasource ["alter table members drop column old_password"]))

(defn migrate-membership-enum!
  "Add 'admin' variant membership_site_code enum"
  []
  (jdbc/execute!
    @datasource
    ["alter type membership_site_code add value if not exists 'admin'"]))

(defn migrate-member-roles!
  []
  (jdbc/execute!
    @datasource
    ["insert into members_roles (member_id, role)
      select id, site_code from members"]))

;;; member queries

(defn authenticate-user
  [{:keys [login password]}]
  (when-let [{:members/keys [id password-hash]}
             (jdbc/execute-one!
               @datasource
               ["select id, password_hash from members where login = ?"
                login]
               jdbc/snake-kebab-opts)]
       (when (bcrypt/check password password-hash)
         id)))

(defn all-members
  []
  (jdbc/execute!
    @datasource
    ["select id, first_name, last_name from members
      where status = 'active' and site_code = 'member'
      order by last_name asc, first_name asc"]
    jdbc/snake-kebab-opts))

(defn member
  [id]
  (jdbc/execute-one!
    @datasource
    ["select * from members where id = ?" id]
    jdbc/snake-kebab-opts))

(comment
  (member 958)
  (jdbc/execute!
    @datasource
    ["select * from members where first_name = 'Cash'"]
    )
  )

(defn member-roles
  [member-id]
  (jdbc/execute!
    @datasource
    ["select \"role\" from members_roles where \"member_id\" = ?"
     member-id]))

(defn member-game-results
  [member-id]
  (->> (jdbc/execute!
         @datasource
         ["select json_agg(row_to_json(game_member_results.*)) as result,
        game_instances.\"date\",
        min(game_results_placing.placing) as placing,
        max(games.name) as game_name
      from game_member_results
      join game_instances on game_instances.id = game_member_results.game_instance
      join games on games.id = game_instances.game_id
      join game_results_placing on game_member_results.member_id = game_results_placing.member_id and game_results_placing.game_instance_id = game_instances.id
      where game_member_results.member_id = ?
      group by game_instances.id
      order by game_instances.date desc" member-id]
         jdbc/snake-kebab-opts)
       (x/transform
         [x/ALL :result x/ALL x/MAP-KEYS]
         ->kebab-case)
       (x/transform
         [x/ALL
          :result]
         (fn [results]
           (into {} (map (fn [r] [(:event r) r]))
                 results)))))

(comment
  (first (member-game-results 958))
  )

(defn member-pr-results
  [member-id]
  ;; TODO: switch to plan/transduce
  (->> (jdbc/execute!
         @datasource
         ["with prs as (select distinct on (event)
             event,
             last_value(class) over wnd as class,
             last_value(game_instance) over wnd as game_id,
             last_value(distance_inches) over wnd as distance_inches,
             last_value(clock_minutes) over wnd as clock_minutes,
             last_value(weight) over wnd as weight,
             last_value(score) over wnd as score
      from game_member_results
      where member_id = ? and event <> 'caber' and score > 0
      window wnd as (
        partition by event order by distance_inches, weight
        rows between unbounded preceding and unbounded following
      )
      union all
      select distinct on (event)
             event,
             last_value(class) over wnd as class,
             last_value(game_instance) over wnd as game_id,
             last_value(distance_inches) over wnd as distance_inches,
             last_value(clock_minutes) over wnd as clock_minutes,
             last_value(weight) over wnd as weight,
             last_value(score) over wnd as score
      from game_member_results
      where member_id = ? and event = 'caber' and score > 0
      window wnd as (
        partition by event order by score
        rows between unbounded preceding and unbounded following
      )
      )
      select prs.event, prs.class, prs.distance_inches, prs.clock_minutes, prs.weight,
             prs.score, games.name, game_instances.date
        from prs prs
        join game_instances on game_instances.id = prs.game_id
        join games on games.id = game_instances.game_id
      "
          member-id member-id]
         jdbc/snake-kebab-opts)
       (into {} (map (fn [pr] [(:event pr) pr])))))

(comment
  (member-pr-results 958)
  (-> (member-pr-results 6)
      (get "caber") :game-instances/date class)

  )

;;; Games queries

(defn available-years-for-records
  []
  (jdbc/execute!
    @datasource
    ["select distinct extract(year from \"date\") as year
      from game_instances order by year desc"]))

(declare event-records-for-year-by-class)
(declare event-top-weights-for-year-by-class)
(declare score-for-result)

(defn games-history
  [{:keys [year classes event]}]
  (let [bests (event-records-for-year-by-class year)
        weights (event-top-weights-for-year-by-class year)]
    (->>
           (jdbc/plan
             @datasource
             (cond->
                 [(str
                    "select games.id as games_id,
         games.name,
         to_char(game_instances.date, 'YYYY-MM-DD') as date,
         game_results_placing.placing,
         members.id, members.first_name, members.last_name,
         game_member_results.event, game_member_results.distance_inches,
            game_member_results.clock_minutes, game_member_results.weight,
            game_member_results.class, game_member_results.score
      from game_instances
      join games on games.id = game_instances.game_id
      join game_member_results on game_member_results.game_instance = game_instances.id
      join game_results_placing on game_results_placing.game_instance_id = game_instances.id and game_results_placing.member_id = game_member_results.member_id
      join members on game_member_results.member_id = members.id
      where true
      "
                    (when year
                      " and extract(year from \"date\") = ? ")
                    (when (seq classes)
                      " and game_member_results.class = any(cast(? as membership_class_code[]))")
                    (when event
                      " and game_member_results.event = cast(? as game_event_type)"))]
                 (some? year) (conj year)
                 (seq classes) (conj (into-array java.lang.String classes))
                 (some? event) (conj event))
             jdbc/snake-kebab-opts)
           (reduce
             (fn [acc row]
               (cond-> acc
                 (not (contains? (get acc (:date row)) (:games_id row)))
                 (assoc-in [(:date row) (:games_id row)]
                           {:games/name (:name row) :results {}})

                 (not (contains? (get-in acc [(:date row) (:games_id row) :results])
                                 (:id row)))
                 (assoc-in [(:date row) (:games_id row) :results (:id row)]
                           {:members/id (:id row)
                            :members/first-name (:first_name row)
                            :members/last-name (:last_name row)
                            :game-results-placing/placing (:placing row)
                            :game-member-results/class (:class row)
                            :events {}})

                 true
                 (assoc-in [(:date row) (:games_id row) :results (:id row) :events (:event row)]
                           {:game-member-results/event (:event row)
                            :game-member-results/class (:class row)
                            :game-member-results/score (:score row)
                            :game-member-results/clock-minutes (:clock_minutes row)
                            :game-member-results/distance-inches (:distance_inches row)
                            :game-member-results/weight (:weight row)})

                 true (update-in [(:date row) (:games_id row) :results (:id row) :events (:event row)]
                                 (fn [result]
                                   (assoc result :calculated-score
                                          (score-for-result
                                            (:class row)
                                            result
                                            bests
                                            weights))))))
             {}))))

(comment

  (time (count (games-history {:year 2023 :classes ["lightweight" "masters"]})))
  )

(defn add-new-game!
  [game-name]
  (jdbc/execute-one!
    @datasource
    ["insert into games (name) values (?) returning *" game-name]
    jdbc/snake-kebab-opts))

;;; Score sheets

(defn all-games-names
  []
  (jdbc/execute!
    @datasource
    ["select id, name from games order by name"]
    jdbc/snake-kebab-opts))

(defn member-score-sheets
  [member-id]
  (jdbc/execute!
    @datasource
    ["select * from score_sheets where submitted_by = ?"
     member-id]
    jdbc/snake-kebab-opts))

(defn add-new-score-sheet!
  [member-id]
  (jdbc/execute-one!
    @datasource
    ["insert into score_sheets (submitted_by) values (?)
      returning *"
     member-id]
    jdbc/snake-kebab-opts))

(defn update-sheet-for-user
  [{:keys [user-id sheet-id sheet]}]
  (jdbc/execute!
    @datasource
    ["update score_sheets
      set games_id = ?,
          games_date = ?,
          data = ?
      where id = ? and submitted_by = ?"
     (:score-sheets/games-id sheet)
     (some-> (:score-sheets/games-date sheet)
             (.getTime) (java.sql.Date.))
     (:score-sheets/data sheet)
     sheet-id user-id]))

(comment
  (java.sql.Date. (.getTime (java.util.Date.)))
  )

(defn submit-sheet-for-approval
  [{:keys [sheet-id user-id]}]
  (jdbc/execute!
    @datasource
    ["update score_sheets
      set status = 'complete'
      where id = ? and submitted_by = ? and status = 'pending'"
     sheet-id user-id]))

;; Records

(defn current-records
  []
  (->> (jdbc/plan
         @datasource
         ["select distinct last_value(id) over wnd as id,
             last_value(canadian) over wnd as canadian,
             last_value(class) over wnd as class,
             last_value(event) over wnd as event,
             last_value(athlete_name) over wnd as athlete_name,
             last_value(distance_inches) over wnd as distance_inches,
             last_value(weight) over wnd as weight,
             last_value(year) over wnd as year,
             last_value(comment) over wnd as comment,
             last_value(status) over wnd as status
           from event_records where should_display
           window wnd as (partition by class, event, weight order by year
            rows between unbounded preceding and unbounded following)"]
         jdbc/snake-kebab-opts)
       (reduce
         (fn [acc row]
           (->> #:event-record{:id (:id row)
                               :canadian? (:canadian row)
                               :class (:class row)
                               :event (:event row)
                               :athlete-name (:athlete_name row)
                               :distance-inches (:distance_inches row)
                               :weight (:weight row)
                               :year (:year row)
                               :comment (:comment row)
                               :status (:status row)}
                (update-in acc [(:class row) (:event row)]
                           (fnil conj []))))
         {})))


(defn- event-records-for-year-by-class
  [year]
  (jdbc/execute!
    @datasource
    ["select distinct first_value(class) over wnd as class,
        first_value(event) over wnd as event,
        first_value(distance_inches) over wnd as distance_inches,
        first_value(weight) over wnd as weight,
        first_value(year) over wnd as year
      from event_records
      where status = 'verified'
      window wnd as (partition by class, event, weight order by
         (case when year < ? then year else -year end) desc
        rows between unbounded preceding and unbounded following)
      order by distance_inches desc"
     year]
    jdbc/snake-kebab-opts))

(defn- event-top-weights-for-year-by-class
  [year]
  (jdbc/execute!
    @datasource
    ["select distinct first_value(class) over wnd as class,
        first_value(event) over wnd as event,
        first_value(weight) over wnd as weight,
        first_value(year) over wnd as year
      from event_records
      where status = 'verified'
      window wnd as (partition by class, event order by
         (case when year < ? then year else -year end) desc, weight desc
        rows between unbounded preceding and unbounded following) "
     year]
    jdbc/snake-kebab-opts))

(comment
  (event-records-for-year-by-class 2023)
  (event-records-for-year-by-class 1960)
  )

(def event-weight-limits
  {"open" {"open" 16
           "amateurs" 16
           "masters" 16
           "juniors" 12
           "womens" 8
           "womensmaster" 8
           "lightweight" 16}})

(defn score-for-result
  ([class {:keys [year result]}]
   (score-for-result
     class
     result
     (event-records-for-year-by-class year)
     (event-top-weights-for-year-by-class year)))
  ([class {:game-member-results/keys [event weight distance-inches clock-minutes]}
    event-records event-top-weights]
   (let [class (case class
                 "womensmaster" "womens"
                 "amateurs" "open"
                 class)]
     (cond
       (or (nil? distance-inches) (nil? weight)
           (and (= "caber" event) (nil? clock-minutes))
           (zero? distance-inches) (zero? weight))
       0

       (= "caber" event)
       (+ (* 500 (/ (float weight) 160))
          (* 500 (/ (float distance-inches) 258))
          (- (/ (abs (float clock-minutes)) 2)))

       (and (= "open" class) (= "open" event))
       (let [light-best (x/select-first
                          [x/ALL
                           (x/if-path [:class (x/pred= class)] x/STAY)
                           (x/if-path [:event (x/pred= event)] x/STAY)
                           (x/if-path [:weight (x/view float) (x/pred= 17.0)] x/STAY)]
                          event-records)
             heavy-best (x/select-first
                          [x/ALL
                           (x/if-path [:class (x/pred= class)] x/STAY)
                           (x/if-path [:event (x/pred= event)] x/STAY)
                           (x/if-path [:weight (x/view float) (x/pred= 20.0)] x/STAY)]
                          event-records)]
         (if (and light-best heavy-best
                  weight (<= (get-in event-weight-limits [event class]) (float weight)))
           (let [factor (/ (- (float (:distance-inches light-best))
                              (float (:distance-inches heavy-best)))
                           (- 20 17))]
             (-> (/ (+ (float distance-inches) (* (- factor 1) (- weight 17)))
                    (float (:distance-inches light-best)))
                 (* 1000)))
           0))

       (and (not= "masters" class) (= "open" event))
       (let [best (x/select-first
                    [x/ALL
                     (x/if-path [:class (x/pred= class)] x/STAY)
                     (x/if-path [:event (x/pred= event)] x/STAY)]
                    event-records)
             top-weight (x/select-one
                          [x/ALL
                           (x/if-path [:class (x/pred= class)] x/STAY)
                           (x/if-path [:event (x/pred= event)] x/STAY)
                           :weight]
                          event-top-weights)
             class-weight-limit (get-in event-weight-limits [event class])]
         (if (and best top-weight class-weight-limit)
           ;; this formula is bizarre, but shrug
           (* 1000 (/ (+ (* 12 (+ (math/floor (/ (float distance-inches) 12))
                                  (float weight)
                                  (- class-weight-limit)))
                         (mod (float distance-inches) 12))
                      (+ (* 12 (+ (math/floor (/ (float (:distance-inches best)) 12))
                                  (float top-weight)
                                  (- class-weight-limit)))
                         (mod (float (:distance-inches best)) 12))))
           0))

       (and (not= "womens" class) (= "braemar" event))
       (let [best (x/select-first
                    [x/ALL
                     (x/if-path [:class (x/pred= class)] x/STAY)
                     (x/if-path [:event (x/pred= event)] x/STAY)
                     :distance-inches]
                    event-records)
             top-weight (x/select-one
                          [x/ALL
                           (x/if-path [:class (x/pred= class)] x/STAY)
                           (x/if-path [:event (x/pred= event)] x/STAY)
                           :weight]
                          event-top-weights)
             event-weight-limit (case class "womens" 18 22)]
         (if (and best top-weight weight distance-inches
                  (<= event-weight-limit weight))
           (* 1000 (/ (+ (* 12 (+ (math/floor (/ (float distance-inches) 12))
                                  weight
                                  (- event-weight-limit)))
                         (mod (float distance-inches) 12))
                      (+ (* 12 (+ (math/floor (/ (float best) 12))
                                  top-weight
                                  (- event-weight-limit)))
                         (mod (float best) 12))))
           0))

       :else
       (-> (/ (float distance-inches)
              (or (some-> (x/select-first
                            [x/ALL
                             (x/if-path [:class (x/pred= class)] x/STAY)
                             (x/if-path [:event (x/pred= event)]
                                        x/STAY)
                             :distance-inches]
                            event-records)
                          float)
                  ##Inf))
           (* 1000)
           (max 0))))))

(comment
  (jdbc/execute!
    @datasource
    ["select * from game_member_results where event = 'caber' and score <> 0 limit 1"]
    jdbc/snake-kebab-opts)


  (jdbc/execute! @datasource ["select * from game_instances where id = 639"])

  (score-for-result
    "womens"
    {:result #:game-member-results{:member-id 175, :distance-inches 226.0M, :weight 18.00M, :class "womens", :score 812.9500M, :game-instance 831, :clock-minutes nil, :event "braemar", :id 33}
     :year 2005})

  (score-for-result
    "open"
    {:result #:game-member-results{:distance-inches 200M :weight 18M :class "open"
                                   :event "open"}})

  (score-for-result
    "womens"
    {:result #:game-member-results{:member-id 410, :distance-inches 192.0M, :weight 50.00M, :class "womens", :score 528.3430M, :game-instance 639, :clock-minutes 0, :event "caber", :id 20899}
     :year 2009})

(jdbc/execute!
  @datasource
  ["select * from game_member_results where event = 'caber' and score <> 0 limit 1"]
  jdbc/snake-kebab-opts)
  )

;; Rankings

(defn rankings-for-year
  [year]
  (->>
    (jdbc/plan
      @datasource
      ["select distinct
        last_value(game_member_results.class) over wnd as class,
        last_value(game_member_results.score) over wnd as score,
        last_value(game_member_results.event) over wnd as event,
        last_value(game_member_results.member_id) over wnd as member_id,
        last_value(game_member_results.distance_inches) over wnd as distance_inches,
        last_value(game_member_results.clock_minutes) over wnd as clock_minutes,
        last_value(game_member_results.weight) over wnd as weight,
        last_value(members.first_name) over wnd as first_name,
        last_value(members.last_name) over wnd as last_name
     from game_member_results
     join members on game_member_results.member_id = members.id
     join game_instances on game_member_results.game_instance = game_instances.id
     where extract(\"year\" from game_instances.\"date\") = ?
       and members.country = any(ARRAY['Canada', ''])
       and members.status = 'active'
       and game_member_results.event <> 'sheaf'
       and game_member_results.event <> 'braemar'
       and game_member_results.score > 0
       and game_member_results.class = any('{juniors, amateurs, womens, masters, open, lightweight}')
     window wnd as (partition by game_member_results.member_id, game_member_results.event
        order by score rows between unbounded preceding and unbounded following)"
       year])
    (reduce
      (fn [acc row]
        (let [cls (:class row)
              cls (if (= cls "amateurs") "open" cls)]
          ;; for the open class, get the best masters result & re-score it as open, use if better
          ;; for the masters class, if the athlete is masters age, get best open result, calculate as masters, use if better
          (-> acc
              (assoc-in [cls (:member_id row) :members/first-name] (:first_name row))
              (assoc-in [cls (:member_id row) :members/last-name] (:last_name row))
              (assoc-in [cls (:member_id row) :events (:event row)]
                        {:distance-inches (:distance_inches row)
                         :weight (:weight row)
                         :clock-minutes (:clock_minutes row)
                         :score (:score row)})
              (update-in [cls (:member_id row) :score]
                         (fnil + 0) (:score row)))))
      {})))

(comment

  (first (rankings-for-year 2023))
  (get-in (rankings-for-year 2023)
          ["open" 958])

  (prn (map :score (vals (get-in (rankings-for-year 2023)
                      [:open 958 :events]))))
  )
