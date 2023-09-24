(ns irc.libera.chat.bayaz.track.core
  (:require [clojure.string]
            [irc.libera.chat.bayaz.db.core :as db.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util]))

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

(defn deep-whois! [who]
  (loop [whos #{(clojure.string/lower-case who)}
         seen-hosts #{}
         results []]
    (if (empty? whos)
      results
      (let [[hostname-ref hostname] (operation.util/resolve-hostname! (first whos))]
        (if (contains? seen-hosts hostname)
          (recur (disj whos (first whos)) seen-hosts results)
          (let [; TODO: Optimize by limiting query; couldn't make it work with datalevin.
                nicks (db.core/query! '[:find [(pull ?a [*]) ...]
                                        :in $ ?hostname-ref
                                        :where
                                        [?a :user/hostname-ref ?hostname-ref]
                                        [?a :user/nick-association ?nick]]
                                      hostname-ref)
                accounts (db.core/query! '[:find [(pull ?a [*]) ...]
                                           :in $ ?hostname-ref
                                           :where
                                           [?a :user/hostname-ref ?hostname-ref]
                                           [?a :user/account-association ?account]]
                                         hostname-ref)
                combined (map #(assoc % :user/hostname hostname) (lazy-cat nicks accounts))]
            (let [seen (conj seen-hosts hostname)]
              (recur (clojure.set/difference (into (into (disj whos (first whos))
                                                         (mapcat (comp #(map second %)
                                                                       operation.util/nick->hostnames!
                                                                       :user/nick-association)
                                                                 nicks))
                                                   (mapcat (comp #(map second %)
                                                                 operation.util/account->hostnames!
                                                                 #(str "$a:" (:user/account-association %)))
                                                           accounts))
                                             seen)
                     seen
                     (lazy-cat results combined)))))))))

(defn collapse-whois-results [results]
  (reduce (fn [acc action]
            (if (empty? acc)
              (conj acc action)
              (let [prev (last acc)]
                ; We combine nick and account associations together, if they're
                ; within a reasonable amount of time.
                (if (and (= (:user/hostname-ref prev) (:user/hostname-ref action))
                         (<= (Math/abs (- (:time/when prev) (:time/when action))) 5)
                         (or (and (contains? prev :user/nick-association)
                                  (contains? action :user/account-association))
                             (and (contains? action :user/nick-association)
                                  (contains? prev :user/account-association))))
                  (update acc (dec (count acc)) merge action)
                  (conj acc action)))))
          []
          (sort-by :time/when results)))
