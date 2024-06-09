(ns csaf.server.db
  (:require
   [clojure.java.io :as io]
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

(defn games-history
  [{:keys [year classes event]}]
  (->>
    (jdbc/plan
      @datasource
      (cond->
          [(str
             "select games.name, to_char(game_instances.date, 'YYYY-MM-DD') as date,
         game_results_placing.placing,
         members.id, members.first_name, members.last_name,
         game_member_results.event, game_member_results.distance_inches,
            game_member_results.clock_minutes, game_member_results.weight,
            game_member_results.class
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
          (not (contains? acc (:date row)))
          (assoc (:date row) {:games/name (:name row)
                              :results {}})

          (not (contains? (get-in acc [(:date row) :results]) (:id row)))
          (assoc-in [(:date row) :results (:id row)]
                    {:members/id (:id row)
                     :members/first-name (:first_name row)
                     :members/last-name (:last_name row)
                     :game-results-placing/placing (:placing row)
                     :game-member-results/class (:class row)
                     :events {}})

          true
          (assoc-in [(:date row) :results (:id row) :events (:event row)]
                    {:game-member-results/event (:event row)
                     :game-member-results/class (:class row)
                     :game-member-results/clock-minutes (:clock_minutes row)
                     :game-member-results/distance-inches (:distance_inches row)
                     :game-member-results/weight (:weight row)}))) {})))

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
