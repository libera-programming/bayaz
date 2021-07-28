(defproject irc.libera.chat/bayaz "0.1.0-SNAPSHOT"
  :repositories [["jitpack" "https://jitpack.io"]]
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/core.memoize "1.0.236"]
                 ;[com.github.pircbotx/pircbotx "2.2"]
                 [org.pircbotx/pircbotx "2.3-SNAPSHOT-jeaye"]
                 ]
  :plugins [[com.jakemccrary/lein-test-refresh "0.24.1"]
            [lein-cloverage "1.2.2"]]
  :main ^:skip-aot irc.libera.chat.bayaz.core
  :global-vars {*warn-on-reflection* true}
  :target-path "target/%s"
  :profiles {:test {:test-refresh {:focus-flag :focus}}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
