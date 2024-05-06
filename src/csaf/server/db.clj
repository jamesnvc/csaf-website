(ns csaf.server.db
  (:require
   [clojure.java.io :as io]
   [com.rpl.specter :as x]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as jdbc.rs]
   [hikari-cp.core :as hikari]
   [csaf.config :as config]) )

(defonce datasource
  (delay (hikari/make-datasource
           {:jdbc-url
            (str "jdbc:"
                 (config/conf :csaf/database-url))
            #_#_:re-write-batched-inserts true})))

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
  (jdbc/execute!
    @datasource
    ["select * from game_member_results
      where member_id = ? order by game_instance" member-id]))

(defn member-pr-results
  [member-id]
  (jdbc/execute!
    @datasource
    ["select distinct on (event)
             event,
             last_value(game_instance) over wnd,
             last_value(distance_inches) over wnd,
             last_value(weight) over wnd
      from game_member_results
      where member_id = ?
      window wnd as (
        partition by event order by distance_inches
        rows between unbounded preceding and unbounded following
      )
      "
     member-id]))

(comment
  (member-game-results 958)
  (member-pr-results 958)
  )
