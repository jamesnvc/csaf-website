(ns csaf.core
  (:gen-class)
  (:require
   [bloom.omni.core :as omni]
   [taoensso.timbre :as timbre]
   [csaf.config :as conf]
   [csaf.server.db :as db]
   [csaf.server.routes :refer [routes]]))

(def config
  {:omni/title "CSAF"
   :omni/environment (conf/conf :omni/environment)
   :omni/css {:styles "csaf.client.styles/styles"
              :tailwind? true
              :tailwind-opts {:garden-fn 'girouette.tw.default-api/tw-v3-class-name->garden
                              :base-css-rules []
                              :retrieval-method :comprehensive}}
   :omni/cljs {:main "csaf.client.core"}
   :omni/api-routes #'routes
   :omni/http-port (conf/conf :omni/http-port)
   :omni/auth {:cookie {:secret (conf/conf :csaf/cookie-secret)}
               :token {:secret (conf/conf :csaf/token-secret)}}})
(defn start!
  []
  (timbre/info "Starting")
  (db/init-db!)
  (omni/start! omni/system config))

(defn stop!
  []
  (omni/stop!))

(defn -main [& _]
  (start!))
