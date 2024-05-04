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
