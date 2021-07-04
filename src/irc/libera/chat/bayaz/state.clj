(ns irc.libera.chat.bayaz.state
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string]))

(defonce bot (atom nil))
; This is a general event queue of sorts, with which system can subscribe to upcoming events.
; A simple example is a WHOIS lookup from an operation needing a block until the result has
; been read.
(defonce pending-event-requests (atom {:whois {}}))

; TODO: Validate and merge with default config.
(def global-config (delay (-> (io/resource "config.edn")
                              slurp
                              edn/read-string)))
