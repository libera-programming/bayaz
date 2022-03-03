(ns irc.libera.chat.bayaz.track.core
  (:require [clojure.core.async :as async]
            [irc.libera.chat.bayaz.db.core :as db.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util])
  (:import [org.pircbotx User]
           [org.pircbotx.hooks.events WhoisEvent]))

; Tracking is slow and passive. We queue up functions single-file, add delays to prevent network
; spam, and then ultimately take note of users' hostname, nick, and account associations in our db.

(def queue
  "A queue of functions to run asynchronously for tracking users. The queue is
  useful for funneling all concurrent requests so no duplicate work done and we
  don't need to worry much about transactions. With our scaling requirements,
  it's not a concern until proven otherwise."
  (atom nil))

(defn start-listener! []
  (when (nil? @queue)
    ; Unbounded channel, so nothing is dropped.
    (reset! queue (async/chan Long/MAX_VALUE))
    (async/go-loop
      []
      (let [event-data (async/<! @queue)
            event-result (try
                           (async/<! ((:fn event-data)))
                           (catch Exception e
                             (println e)
                             nil))]
        (async/>! (:chan event-data) event-result))

      ; Loop forever.
      (recur))))

(defn track-user!* [hostname nick account timestamp]
  ; TODO: Check the last update and skip if it's too recent.
  (let [; TODO: Lower-case this?
        _ (db.core/transact! [{:db/id -1
                               :user/hostname hostname}])
        [existing-hostname] (db.core/query-first! '[:find ?h
                                                    :in $ ?hostname
                                                    :where
                                                    [?h :user/hostname ?hostname]]
                                                  hostname)
        track! (fn [association-keyword association]
                 (let [q '[:find ?e
                           :in $ ?hostname ?association-keyword ?association
                           :where
                           [?h :user/hostname ?hostname]
                           [?e :user/hostname-ref ?h]
                           [?e ?association-keyword ?association]]
                       [existing-assoc] (db.core/query-first! q
                                                              hostname
                                                              association-keyword
                                                              association)]
                   (db.core/transact! [(if (some? existing-assoc)
                                         {:db/id existing-assoc
                                          :time/when timestamp}
                                         {:user/hostname-ref existing-hostname
                                          association-keyword association
                                          :time/when timestamp})])))]
    (track! :user/nick-association nick)
    (when-not (empty? account)
      (track! :user/account-association account))))

(def track-user-delay-ms (* 60 1000))
(defn track-user! [^User user timestamp]
  (let [result-chan (async/chan)]
    (async/go
      (async/>! @queue
                {:chan result-chan
                 :fn (fn []
                       (async/go
                         ; We delay before each whois event for tracking, since we don't want to spam
                         ; the network. The queue each of these goes through ensures they're done one
                         ; at a time, too.
                         (async/<! (async/timeout track-user-delay-ms))

                         (println "tracking" (.getNick user))
                         (let [^WhoisEvent whois-event (async/<! (operation.util/whois! user))]
                           (track-user!* (.getHostname whois-event)
                                         (.getNick whois-event)
                                         (.getRegisteredAs whois-event)
                                         timestamp))))})
      (async/<! result-chan))))
