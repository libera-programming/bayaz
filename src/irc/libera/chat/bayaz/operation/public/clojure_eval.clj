(ns irc.libera.chat.bayaz.operation.public.clojure-eval
  (:require [clojure.string :as string]
            [taoensso.timbre :as timbre]
            [embroidery.api :as embroidery]
            [sci.core :as sci]))

(def default-timeout-in-sec 2)
(def code-prefix "(use 'clojure.repl)")
(def sci-opts {})

(defn eval* [code]
  (try
    (let [sw (java.io.StringWriter.)
          result (sci/binding [sci/out sw
                               sci/err sw]
                   ; Make sure we stringify the result inside sci/binding,
                   ; to force de-lazying of the result of evaluating code
                   (pr-str (sci/eval-string (str code-prefix "\n" code) sci-opts)))]
      (merge {:result result}
             (when-let [output (when-not (string/blank? (str sw))
                                 (str sw))]
               {:output output})))
    (catch Throwable t
      {:error t})))

(defn eval
  "Evaluates the given Clojure code, with a timeout on execution (default is 2 seconds).
  Result is a map which may contain these keys:

  :output any output sent to stdout or stderr
  :result the last result returned by the evaluated code
  :error  an error (either a string or a Throwable), if an error occurred"
  ([code] (eval code default-timeout-in-sec))
  ([code timeout-in-sec]
   (when code
     (timbre/debug "Evaluating Clojure forms:" code)
     (let [result (try
                    (let [f (embroidery/future* (eval* code))
                          eval-result (deref f
                                             (* 1000 timeout-in-sec)
                                             {:error (str "Execution terminated after " timeout-in-sec "s.")})]
                      (when-not (future-done? f) (future-cancel f))
                      eval-result)
                    (catch Throwable t
                      {:error t}))]
       (timbre/debug "Eval result" result)
       result))))
