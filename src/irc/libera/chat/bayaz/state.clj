(ns irc.libera.chat.bayaz.state
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [taoensso.timbre :as timbre]))

(defonce bot (atom nil))
; This is a general event queue of sorts, with which system can subscribe to upcoming events.
; A simple example is a WHOIS lookup from an operation needing a block until the result has
; been read.
(defonce pending-event-requests (atom {:whois {}}))

(defn read-config [resource]
  (try
    (-> resource
        slurp
        edn/read-string)
    (catch Exception e
      (timbre/error :error-reading-config resource e)
      {})))

; TODO: Validate and merge with default config.
(def global-config (delay (merge (read-config (io/resource "base-config.edn"))
                                 (read-config "config.edn"))))

(defn feature-enabled? [channel feature]
  (contains? (get-in @global-config [:channels channel :features] #{}) feature))

(defn target-channel-for-channel [channel]
  (get-in @global-config [:channels channel :feature/admin-remote-for] channel))

(defn admin? [channel account]
  (and (some? account)
       (contains? (get-in @global-config
                          [:channels (target-channel-for-channel channel) :admins]
                          #{})
                  (string/lower-case account))))
