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
            :re-write-batched-inserts true})))

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

(comment
  (jdbc/execute!
    @datasource
    ["select * from members where first_name = 'Cash'"]
    )
  )
