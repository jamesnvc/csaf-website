(defproject csaf "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/clojurescript "1.11.60"]

                 [io.bloomventures/commons "0.14.3"]
                 [io.bloomventures/omni "0.34.0"]

                 [com.github.seancorfield/next.jdbc "1.3.847"]
                 [hikari-cp "2.13.0"]
                 [org.postgresql/postgresql "42.2.10"]
                 ]

  :repl-options {:init-ns csaf.core}

  :main csaf.core
  :plugins [[io.bloomventures/omni "0.32.3"]]
  :omni-config csaf.core/config)
