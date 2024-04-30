(ns csaf.config
  (:require
   [bloom.commons.config :as conf]))

(def config
  (delay
    (conf/read
      "config.edn"
      [:map
       [:omni/http-port integer?]
       [:omni/environment [:enum :prod :dev]]
       [:csaf/cookie-secret string?]
       [:csaf/token-secret string?]
       [:csaf/database-url [:re #"^postgresql://.*$"]]])))

(defn conf [key]
  (get @config key))
