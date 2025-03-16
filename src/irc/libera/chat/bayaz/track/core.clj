(ns irc.libera.chat.bayaz.track.core
  (:require [clojure.string :refer [lower-case includes? split starts-with?]]
            [clojure.set :refer [difference]]
            [taoensso.timbre :as timbre]
            [honey.sql :as sql]
            [honey.sql.helpers :refer [select from where limit order-by join union
                                       insert-into values on-conflict do-update-set returning]]
            [irc.libera.chat.bayaz.postgres.core :as postgres.core]))

(def queue (agent []
                  :error-handler (fn [_ exception]
                                   (timbre/error :agent-error :exception exception))
                  :error-mode :continue))

(defn track-hostname! [hostname]
  (-> (postgres.core/execute! (-> (insert-into :hostname)
                                  (values [{:hostname hostname}])
                                  (on-conflict :hostname)
                                  (do-update-set :hostname)
                                  (returning :id)))
      first
      :id))

(defn track-nick! [nick hostname-ref timestamp]
  (-> (postgres.core/execute! (-> (insert-into :nick_association)
                                  (values [{:nick nick
                                            :hostname_id hostname-ref
                                            :first_seen timestamp
                                            :last_seen timestamp}])
                                  (on-conflict :nick :hostname_id)
                                  (do-update-set {:last_seen timestamp})
                                  (returning :id)))
      first
      :id))

(defn track-account! [account hostname-ref timestamp]
  (-> (postgres.core/execute! (-> (insert-into :account_association)
                                  (values [{:account account
                                            :hostname_id hostname-ref
                                            :first_seen timestamp
                                            :last_seen timestamp}])
                                  (on-conflict :account :hostname_id)
                                  (do-update-set {:last_seen timestamp})
                                  (returning :id)))
      first
      :id))

(comment
  (let [hostname-ref (track-hostname! "user/jeaye")]
    (track-account! "jeaye" hostname-ref 4)))

(defn track-user! [{:keys [nick hostname account]} timestamp]
  (let [nick (lower-case nick)
        hostname (lower-case hostname)
        account (when (some? account)
                  (lower-case account))
        ;_ (timbre/info :track-user :hostname hostname :nick nick :account account)
        hostname-ref (track-hostname! hostname)]
    (track-nick! nick hostname-ref timestamp)
    (when-not (empty? account)
      (track-account! account hostname-ref timestamp)))
  nil)

(defn find-hostname-by-ref! [hostname-ref]
  (-> (postgres.core/execute! (-> (select :*)
                                  (from :hostname)
                                  (where [:= :id hostname-ref])
                                  (limit 1)))
      first))

(defn find-hostname! [hostname]
  (-> (postgres.core/execute! (-> (select :*)
                                  (from :hostname)
                                  (where [:= :hostname hostname])
                                  (limit 1)))
      first))

(defn find-latest-nick! [who]
  (-> (postgres.core/execute! (-> (select :*)
                                  (from :nick_association)
                                  (where [:= :nick who])
                                  (limit 1)
                                  (order-by [:last_seen :desc])))
      first))

(defn find-latest-account! [who]
  (-> (postgres.core/execute! (-> (select :*)
                                  (from :account_association)
                                  (where [:= :account who])
                                  (limit 1)
                                  (order-by [:last_seen :desc])))
      first))

(defn find-latest-account-for-hostname-ref! [hostname-ref timestamp]
  (-> (postgres.core/execute! (-> (select :*)
                                  (from :account_association)
                                  (where [:= :hostname_id hostname-ref]
                                         [:= :last_seen timestamp])
                                  (limit 1)
                                  (order-by [:last_seen :desc])))
      first))

(defn find-latest-hostname-by-nick! [who]
  (-> (postgres.core/execute! (-> (select :hostname.id :hostname.hostname)
                                  (from :nick_association)
                                  (where [:= :nick who])
                                  (limit 1)
                                  (order-by [:last_seen :desc])
                                  (join :hostname [:= :hostname.id :nick_association.hostname_id])))
      first))

(defn find-latest-hostname-by-account! [who]
  (-> (postgres.core/execute! (-> (select :hostname.id :hostname.hostname)
                                  (from :account_association)
                                  (where [:= :account who])
                                  (limit 1)
                                  (order-by [:last_seen :desc])
                                  (join :hostname [:= :hostname.id :account_association.hostname_id])))
      first))

(defn find-all-hostnames-by-nick! [who]
  (postgres.core/execute! (-> (select :hostname.id :hostname.hostname)
                              (from :nick_association)
                              (where [:= :nick who])
                              (order-by [:last_seen :desc])
                              (join :hostname [:= :hostname.id :nick_association.hostname_id]))))

(defn find-all-hostnames-by-nicks! [whos]
  (when-not (empty? whos)
    (postgres.core/execute! (-> (select :hostname.id :hostname.hostname)
                                (from :nick_association)
                                (where [:in :nick (seq whos)])
                                (order-by [:last_seen :desc])
                                (join :hostname [:= :hostname.id :nick_association.hostname_id])))))

(defn find-all-hostnames-by-nicks-and-accounts! [nicks accounts]
  (let [nick-query (when-some [whos (seq nicks)]
                       (-> (select :hostname.id :hostname.hostname)
                           (from :nick_association)
                           (where [:in :nick whos])
                           (join :hostname [:= :hostname.id :nick_association.hostname_id])))
        account-query (when-some [whos (seq accounts)]
                        (-> (select :hostname.id :hostname.hostname)
                            (from :account_association)
                            (where [:in :account whos])
                            (join :hostname [:= :hostname.id :account_association.hostname_id])))]
    (postgres.core/execute! (cond
                              (and nick-query account-query)
                              (union nick-query account-query)

                              nick-query
                              nick-query

                              account-query
                              account-query))))

(comment
  (let [nicks ["guest63"]
        accounts []]
    (println (sql/format (union (-> (select :hostname.id :hostname.hostname)
                                    (from :nick_association)
                                    (where [:in :nick (seq nicks)])
                                    (join :hostname [:= :hostname.id :nick_association.hostname_id]))
                                (-> (select :hostname.id :hostname.hostname)
                                    (from :account_association)
                                    (where [:in :account (seq accounts)])
                                    (join :hostname [:= :hostname.id :account_association.hostname_id])))
                         {:pretty true})))
  (find-all-hostnames-by-nicks! #{"guest63"}))

(defn find-all-hostnames-by-account! [who]
  (postgres.core/execute! (-> (select :hostname.id :hostname.hostname)
                              (from :account_association)
                              (where [:= :account who])
                              (order-by [:last_seen :desc])
                              (join :hostname [:= :hostname.id :account_association.hostname_id]))))

(defn find-all-hostnames-by-accounts! [whos]
  (when-not (empty? whos)
    (postgres.core/execute! (-> (select :hostname.id :hostname.hostname)
                                (from :account_association)
                                (where [:in :account (seq whos)])
                                (order-by [:last_seen :desc])
                                (join :hostname [:= :hostname.id :account_association.hostname_id])))))

(defn find-all-nicks-by-hostname-ref! [hostname-ref]
  (postgres.core/execute! (-> (select :*)
                              (from :nick_association)
                              (where [:= :hostname_id hostname-ref])
                              (order-by [:last_seen :desc]))))

(defn find-all-accounts-by-hostname-ref! [hostname-ref]
  (postgres.core/execute! (-> (select :*)
                              (from :account_association)
                              (where [:= :hostname_id hostname-ref])
                              (order-by [:last_seen :desc]))))

(defn find-all-nicks-and-accounts-by-hostname-ref! [hostname-ref]
  (postgres.core/execute! (-> (select :*)
                              (from :account_association)
                              (where [:= :account_association.hostname_id hostname-ref])
                              (join :nick_association [:= :nick_association.hostname_id hostname-ref]))))

(defn hostmask? [who]
  (includes? who "@"))

(defn extended-hostmask? [who]
  (starts-with? who "$a:"))

(defn resolve-hostname!
  "Resolves an identifier to a hostname-ref/hostname pair. The identifiers match these cases:

  1. A nick
  2. A hostname
  3. A hostmask
  4. An extended $a:foo hostmask"
  [^String who]
  (let [who (lower-case who)
        ; If we're given a hostmask, resolve it to a hostname.
        who (if (hostmask? who)
              (last (split who #"@"))
              who)
        ; If we're given a $a:foo account, resolve it to the account.
        [who account?] (if (extended-hostmask? who)
                         [(last (split who #":")) true]
                         [who false])
        hostname (or (if account?
                       (find-latest-hostname-by-account! who)
                       (find-latest-hostname-by-nick! who))
                     (find-hostname! who))]
    [(:id hostname) (:hostname hostname)]))

(defn resolve-account!
  "Resolves an identifier to the most useful incarnation. These identifiers match three cases:

  1. The nick of a registered user
  2. The nick of an unregistered user
  3. A hostmask

  The first case is resolved to an account specifier, to cover all clients logged into that
  account, as well as name changes. The second case is resolved into a hostmask which covers
  all users and nicks from that host. The third is passed through unchanged."
  [^String who]
  (let [who (lower-case who)
        ; A nick associated with multiple hostnames or accounts will always
        ; resolve to the most recent.
        latest-nick (find-latest-nick! who)
        account (when (some? latest-nick)
                  ; We require the timestamp of the nick and account associations to match.
                  ; This means the nick and account are being used together.
                  (find-latest-account-for-hostname-ref! (:hostname_id latest-nick)
                                                         (:last_seen latest-nick)))]
    (cond
      (some? account)
      (str "$a:" (:account account))

      (some? latest-nick)
      (:hostname (find-hostname-by-ref! (:hostname_id latest-nick)))

      ; Assume it's a hostmask.
      :else
      who)))

(defn collapse-whois-results [results]
  (reduce (fn [acc action]
            (if (empty? acc)
              (conj acc action)
              (let [prev (last acc)]
                ; We combine nick and account associations together, if they're
                ; within a reasonable amount of time.
                (if (and (= (:hostname_id prev) (:hostname_id action))
                         (<= (Math/abs ^long (- (:last_seen prev) (:last_seen action))) 5)
                         (or (and (contains? prev :nick)
                                  (contains? action :account))
                             (and (contains? action :nick)
                                  (contains? prev :account))))
                  (update acc (dec (count acc)) merge action)
                  (conj acc action)))))
          ; TODO: Transient.
          []
          results))

(defn whois! [hostname-ref]
  (let [nicks (find-all-nicks-by-hostname-ref! hostname-ref)
        accounts (find-all-accounts-by-hostname-ref! hostname-ref)]
    (collapse-whois-results (lazy-cat nicks accounts))))

(comment
  (find-all-nicks-by-hostname-ref! 2))

; Results: 7118
; 1. Lazy seqs: Stack overflow
; 2. Eager seqs: 76672.657998 msecs, 77139.264026 msecs
; 3. Tranducers: 76004.788454 msecs, 77533.08113 msecs
; 4. Remove redundant hostname lookups: 37738.311656 msecs, 37960.517238 msecs
; 5. Result transducer: 35530.720391 msecs, 35736.725686 msecs

(defn row->seen* [row]
  [(:id row) (:hostname row)])

; TODO: Sort by last-seen
(defn deep-whois! [who]
  (-> (loop [remaining-hostname-pairs #{(resolve-hostname! (lower-case who))}
             seen-hostname-pairs #{}
             results []]
        (if (empty? remaining-hostname-pairs)
          results
          (let [[hostname-ref hostname :as pair] (first remaining-hostname-pairs)
                nicks (find-all-nicks-by-hostname-ref! hostname-ref)
                accounts (find-all-accounts-by-hostname-ref! hostname-ref)
                seen-hostname-pairs (conj seen-hostname-pairs pair)]
            (recur (-> (disj remaining-hostname-pairs pair)
                       (into (map row->seen*)
                             (find-all-hostnames-by-nicks-and-accounts! (map :nick nicks)
                                                                        (map #(str "$a:" (:account %))
                                                                             accounts)))
                       (difference seen-hostname-pairs))
                   seen-hostname-pairs
                   (into results
                         (map #(assoc % :hostname hostname))
                         (lazy-cat nicks accounts))))))
      collapse-whois-results))

(comment
  *e
  (time (count (deep-whois! "guest63")))

  (find-all-nicks-by-hostname-ref! 17551)

  (require '[clj-async-profiler.core :as prof])

  (prof/profile (time (count (deep-whois! "Guest63"))))

  (prof/serve-ui 8080)
  )
