(defproject irc.libera.chat/bayaz "0.1.0-SNAPSHOT"
  :repositories [["jitpack" "https://jitpack.io"]]
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/core.memoize "1.1.266"]
                 [org.clojure/core.async "1.7.701"]
                 [org.clojure/data.json "2.5.1"]
                 [com.github.pircbotx/pircbotx "2.3.1"]
                 [com.taoensso/timbre "6.6.1"]
                 [clj-http "3.13.0"]
                 [org.jsoup/jsoup "1.18.3"]
                 [com.github.igrishaev/pg2-core "0.1.30"]
                 [com.github.igrishaev/pg2-honey "0.1.30"]
                 [com.github.igrishaev/pg2-migration "0.1.30"]
                 [dev.weavejester/medley "1.8.1"]
                 [environ "1.2.0"]]
  :plugins [[lein-environ "1.2.0"]
            [com.jakemccrary/lein-test-refresh "0.26.0"]
            [lein-cloverage "1.2.4"]
            [io.taylorwood/lein-native-image "0.3.1"]]
  :main ^:skip-aot irc.libera.chat.bayaz.core
  :global-vars {*warn-on-reflection* true}
  :target-path "target/%s"
  :source-paths ["src" "third-party/reaver/src"]
  :profiles {:dev {:dependencies [[nubank/matcher-combinators "3.9.1"]
                                  [com.clojure-goes-fast/clj-async-profiler "1.5.1"]]
                   :env {:bayaz-db "dev-resources/db"}
                   :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
                              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
                              "-Djdk.attach.allowAttachSelf"]}
             :test {:test-refresh {:focus-flag :focus}}
             :native-image {:aot :all
                            :jvm-opts ["-Dclojure.compiler.direct-linking=true"]
                            :native-image {:name "bayaz"
                                           ;:graal-bin "/usr/lib/jvm/java-19-graalvm/bin"
                                           :opts ["--report-unsupported-elements-at-runtime"
                                                  "--initialize-at-build-time"
                                                  "--no-server"
                                                  "--initialize-at-run-time=org.apache"
                                                  "-H:+StaticExecutableWithDynamicLibC"
                                                  "-H:ResourceConfigurationFiles=resource-config.json"
                                                  "-H:ReflectionConfigurationFiles=reflection.json"
                                                  "--enable-url-protocols=http,https"
                                                  "--enable-http"
                                                  "--enable-https"
                                                  "--enable-all-security-services"
                                                  "--verbose"
                                                  "--no-fallback"]}}
             :uberjar {:aot :all
                       :global-vars {*warn-on-reflection* true}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
