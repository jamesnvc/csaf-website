(defproject csaf "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/clojurescript "1.11.60"]

                 [io.github.escherize/huff "0.2.12"]
                 [girouette/girouette "0.0.11-SNAPSHOT"]
                 [org.slf4j/slf4j-api "1.7.36"]
                 [org.clojure/data.xml "0.2.0-alpha6"]
                 [com.fasterxml.jackson.core/jackson-core "2.11.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.11.0"]
                 [riddley "0.1.15"]
                 [org.apache.httpcomponents/httpcore "4.4.13"]
                 [io.bloomventures/omni "0.34.0"
                  :exclusions [riddley]]
                 [io.bloomventures/commons "0.14.3"
                  :exclusions [riddley reagent org.clojure/data.xml
                               ]]

                 [com.github.seancorfield/next.jdbc "1.3.847"]
                 [hikari-cp "2.13.0"]
                 [org.postgresql/postgresql "42.2.10"]

                 [crypto-password "0.3.0"]
                 [org.clojure/data.csv "1.1.0"]
                 ]

  :repl-options {:init-ns csaf.core}

  :main csaf.core
  :plugins [[io.bloomventures/omni "0.32.3"]]
  :omni-config csaf.core/config)
