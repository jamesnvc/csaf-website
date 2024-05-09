(ns csaf.server.db
  (:require
   [clojure.java.io :as io]
   [com.rpl.specter :as x]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as jdbc.rs]
   [hikari-cp.core :as hikari]
   [csaf.config :as config]
   [jsonista.core :as json]
   [camel-snake-kebab.core :refer [->kebab-case]])
  (:import
   (org.postgresql.util PGobject)))

(def mapper (json/object-mapper {:decode-key-fn keyword}))
(def <-json #(json/read-value % mapper))

(defn <-pgobject
  [^org.postgresql.util.PGobject v]
  (let [type (.getType v)
        value (.getValue v)]
    (if (#{"json" "jsonb"} type)
      (when value
        (with-meta (<-json value) {:pgtype type}))
      value)))

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
  )

(defn init-db!
  []
  (jdbc/execute!
    @datasource
    [(slurp (io/resource "sql/create_tables.sql"))]))

;; [TODO] update the unhashed passwords in the database

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

(defn member-game-results
  [member-id]
  (->> (jdbc/execute!
         @datasource
         ["select json_agg(row_to_json(game_member_results.*)) as result,
        game_instances.\"date\",
        max(games.name) as game_name
      from game_member_results
      join game_instances on game_instances.id = game_member_results.game_instance
      join games on games.id = game_instances.game_id
      where member_id = ?
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
  {:result {"wob" {:member-id 958, :distance-inches 132.0, :game-instance 1236, :event "wob", :weight 56.0, :id 72532, :class "open", :score 628.5714, :clock-minutes nil}, "caber" {:member-id 958, :distance-inches 0.0, :game-instance 1236, :event "caber", :weight 0.0, :id 72525, :class "open", :score 0.0, :clock-minutes 0}, "sheaf" {:member-id 958, :distance-inches 240.0, :game-instance 1236, :event "sheaf", :weight 16.0, :id 72530, :class "open", :score 586.7971, :clock-minutes nil}, "hwfd" {:member-id 958, :distance-inches 171.5, :game-instance 1236, :event "hwfd", :weight 56.0, :id 72527, :class "open", :score 305.7041, :clock-minutes nil}, "lwfd" {:member-id 958, :distance-inches 465.0, :game-instance 1236, :event "lwfd", :weight 28.0, :id 72529, :class "open", :score 424.6575, :clock-minutes nil}, "lhmr" {:member-id 958, :distance-inches 866.5, :game-instance 1236, :event "lhmr", :weight 16.0, :id 72528, :class "open", :score 494.0137, :clock-minutes nil}, "braemar" {:member-id 958, :distance-inches 265.0, :game-instance 1236, :event "braemar", :weight 28.5, :id 72524, :class "open", :score 513.5659, :clock-minutes nil}, "open" {:member-id 958, :distance-inches 287.5, :game-instance 1236, :event "open", :weight 22.0, :id 72531, :class "open", :score 592.9395, :clock-minutes nil}, "hhmr" {:member-id 958, :distance-inches 624.0, :game-instance 1236, :event "hhmr", :weight 22.0, :id 72526, :class "open", :score 433.7852, :clock-minutes nil}}, :game-instances/date #inst "2014-10-19T04:00:00.000-00:00", :game-name "Grafton Fall Brawl"}
  )

(defn member-pr-results
  [member-id]
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
