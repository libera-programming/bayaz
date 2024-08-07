(ns irc.libera.chat.bayaz.operation.public.clojure-eval
  (:require [clojure.string :as string]
            [taoensso.timbre :as timbre]))

(def default-timeout-s 2)
(def max-output-lines 10)
(def code-prefix "(use 'clojure.repl) (println (pr-str (do %s)))")

(defn temp-file! []
  (java.nio.file.Files/createTempFile (str (java.util.UUID/randomUUID))
                                      (str (java.util.UUID/randomUUID))
                                      (make-array java.nio.file.attribute.FileAttribute 0)))

(defn eval [code]
  (let [source-file (.toFile (temp-file!))
        output-file (.toFile (temp-file!))]
    (try
      (let [_ (spit source-file (format "(require '[sci.core])
                                        (try
                                          (sci.core/eval-string \"%s\")
                                          (catch Exception e
                                            (println (.getMessage e))))"
                                        ; Escape all quotes, since we'll run the code from a string.
                                        ; This will prevent any injections.
                                        (format code-prefix (string/replace code #"\"" "\\\\\""))))
            cmd ["/run/current-system/sw/bin/bash" "-c"
                 ; We use bash's timeout feature to kill the process for us.
                 ; It'll set the exit code to 124 if the command times out.
                 (format "timeout %ds bb %s" default-timeout-s source-file)]
            pb (doto
                 (java.lang.ProcessBuilder. (into-array ^String cmd))
                 ; We write output to a file, rather than keep it in memory. We can't write enough
                 ; in a couple of seconds to fill the drive and the temp files will be deleted right
                 ; away.
                 (.redirectErrorStream true)
                 (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo output-file)))
            process (.start pb)
            ; Using time `timeout` above, we shouldn't ever need a timeout here, but
            ; we use twice the normal timeout just to be safe.
            _ (.waitFor process (* 2 default-timeout-s) java.util.concurrent.TimeUnit/SECONDS)
            _ (.destroyForcibly process)
            exit-code (.exitValue process)
            output-lines (string/split-lines (slurp output-file))
            max-output-lines (if (= 124 exit-code)
                               (dec max-output-lines)
                               max-output-lines)
            taken-output-lines (into [] (take (dec max-output-lines) output-lines))
            skipped-lines (- (count output-lines) (count taken-output-lines))
            has-skipped-lines? (< 0 skipped-lines)
            info-lines (cond-> []
                         has-skipped-lines?
                         (conj (format "< skipping %d additional line%s >"
                                       skipped-lines
                                       (if (< 1 skipped-lines)
                                         "s"
                                         "")))

                         (= 124 exit-code)
                         (conj (format "Process timed out after %ds" default-timeout-s)))
            output (string/join "\n" (lazy-cat taken-output-lines info-lines))]
        output)
      (finally
        (.delete source-file)
        (.delete output-file)))))

(comment

  (println (eval "(str 1 2)"))
  (println (eval "\") (println 42) (do \"1")))
