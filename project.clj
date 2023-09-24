(defproject irc.libera.chat/bayaz "0.1.0-SNAPSHOT"
  :repositories [["jitpack" "https://jitpack.io"]]
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/core.memoize "1.0.257"]
                 [org.clojure/core.async "1.5.648"]
                 [org.clojure/data.json "2.4.0"]
                 [com.github.pircbotx/pircbotx "-SNAPSHOT"]
                 ;[com.jeaye/pircbotx "2.5"]
                 [clj-http "3.12.3"]
                 [reaver "0.1.3"]
                 [datalevin "0.5.31"]
                 [dev.weavejester/medley "1.7.0"]
                 [environ "1.2.0"]]
  :plugins [[lein-environ "1.2.0"]
            [com.jakemccrary/lein-test-refresh "0.25.0"]
            [lein-cloverage "1.2.2"]]
  :main ^:skip-aot irc.libera.chat.bayaz.core
  :global-vars {*warn-on-reflection* true}
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[nubank/matcher-combinators "3.5.0"]]
                   :env {:bayaz-db "dev-resources/db"}}
             :test {:test-refresh {:focus-flag :focus}}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
