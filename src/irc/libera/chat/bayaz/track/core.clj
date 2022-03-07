(ns irc.libera.chat.bayaz.track.core
  (:require [clojure.string]
            [irc.libera.chat.bayaz.db.core :as db.core]))

(def queue (agent []
                  :error-handler (fn [_ exception]
                                   (println "tracking error" exception))
                  :error-mode :continue))

(defn track-user! [{:keys [nick hostname account]} timestamp]
  (let [nick (clojure.string/lower-case nick)
        hostname (clojure.string/lower-case hostname)
        account (when (some? account)
                  (clojure.string/lower-case account))
        _ (println "track user" hostname nick account)
        _ (db.core/transact! [{:db/id -1
                               :user/hostname hostname}])
        [existing-hostname] (db.core/query-first! '[:find ?h
                                                    :in $ ?hostname
                                                    :where
                                                    [?h :user/hostname ?hostname]]
                                                  hostname)
        track! (fn track! [association-keyword association]
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
      (track! :user/account-association account)))
  nil)
