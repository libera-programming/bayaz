(ns irc.libera.chat.bayaz.operation.util
  (:require [clojure.string :as string]
            [clojure.core.memoize :as memo]
            [irc.libera.chat.bayaz.state :as state])
  (:import [org.pircbotx PircBotX User UserChannelDao]
           [org.pircbotx.hooks.events WhoisEvent]))

(let [make-prefixes (fn [command & prefixes]
                      (-> (map #(str (:command-prefix @state/global-config) %)
                               (cons command prefixes))
                          (zipmap (repeat command))))]
  ; Each command has a full form and a "prefixed" form. For example, the full
  ; `quiet` form may have a `!q` and `!quiet` prefixed form. Prefixed forms can be used
  ; in public channels without a bot mention, since they're meant to be distinct. Full
  ; forms can also be used, but only with a mention or via DM.
  (def prefixed-command->command (delay (merge (make-prefixes "help" "h")
                                               (make-prefixes "admins")
                                               (make-prefixes "warn" "w")
                                               (make-prefixes "quiet" "q")
                                               (make-prefixes "unquiet" "unq" "uq")
                                               (make-prefixes "ban" "b")
                                               (make-prefixes "unban" "unb" "ub")
                                               (make-prefixes "kickban" "kb")
                                               (make-prefixes "kick" "k")

                                               ; Public
                                               (make-prefixes "ops"))))
  (def commands (delay (into #{} (vals @prefixed-command->command)))))

(defn message->operation [message]
  ; TODO: Strip color codes.
  (let [message (string/trim message)
        nick-prefix (re-pattern (str "^" (:nick @state/global-config) "[:,#=]?\\s*"))
        contains-prefix? (boolean (re-seq nick-prefix message))
        ; Remove any highlight prefix containing the nick.
        message (string/replace-first message nick-prefix "")
        ; Tokenize the message, but support quotes to delimit composite tokens.
        ; https://regex101.com/r/GUHh5H/1
        [command & args :as parts] (->> (re-seq #"([^\r\n\t\f\v \"]+)|\"(.+?)\"" message)
                                        ; We want the last non-nil group.
                                        (map #(->> (reverse %)
                                                   (drop-while nil?)
                                                   first)))]
    {:parts (into [] parts)
     :command command
     :mention? contains-prefix?
     :args (into [] args)}))

(defn normalize-command
  "Determines if the operation contains a properly prefixed command and resolves the prefix, if
   necessary. Updates :command to be nil if the operation isn't correctly
   prefixed. Otherwise returns the operation with the command normalized to the
   full form."
  [operation]
  (let [prefixed? (string/starts-with? (:command operation) (:command-prefix @state/global-config))
        prefix-required? (and (= :public (:type operation)) (-> operation :mention? not))
        valid-command? (if prefixed?
                         (contains? @prefixed-command->command (:command operation))
                         (contains? @commands (:command operation)))]
    (cond
      (or (not valid-command?) (and prefix-required? (not prefixed?)))
      (assoc operation :command nil)

      prefixed?
      (update operation :command @prefixed-command->command)

      :else
      operation)))

(defn whois!*
  "Fetches a WhoisEvent for the specified user. This requires blocking on a promise until the
   response to the whois request is returned. Returns nil if this fails due to a timeout."
  ^WhoisEvent [^User user]
  (let [nick (.getNick user)
        new-promise (promise)
        pending-snapshot (swap! state/pending-event-requests
                                (fn [pev]
                                  (if (some? (get-in pev [:whois nick]))
                                    pev
                                    (assoc-in pev [:whois nick] new-promise))))
        pending-whois (get-in pending-snapshot [:whois nick])
        new-request? (identical? new-promise pending-whois)]
    (when new-request?
      (-> user .send .whois))
    (deref pending-whois 5000 nil)))
; Whois requests are rate limited, so we need a short cache for these.
(def whois! (memo/ttl whois!* {} :ttl/threshold (* 30 1000))) ; ms

(defn resolve-account!
  "Resolves an identifier to the most useful incarnation. These identifiers take three shapes:

   1. The nick of a registered user
   2. The nick of an unregistered user
   3. A hostmask

   The first shape is resolved to an account specifier, to cover all clients logged into that
   account, as well as name changes. The second shape is resolved into a hostmask which covers
   all users and nicks from that host. The third is passed through unchanged."
  [^String who]
  (let [^UserChannelDao user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)]
    (or (when (.containsUser user-channel-dao who)
          (when-some [whois-event (whois! (.getUser user-channel-dao who))]
            (let [account-name (.getRegisteredAs whois-event)]
              (cond
                (not (empty? account-name))
                (str "$a:" account-name)

                ; A failed whois may have an empty hostname, which would create a *!*@* hostmask.
                (some? (.getHostname whois-event))
                (str "*!*@" (.getHostname whois-event))

                :else
                nil))))
        who)))

(defn action! [& args]
  (let [user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
        channel (.getChannel user-channel-dao (:primary-channel @state/global-config))
        action (string/join " " args)]
    (-> (.send channel)
        (.action action))))

(defn set-user-mode!
  "Sets the mode for the specified user in the primary channel. `who` can be any valid user
   identifier and `mode` should be the modes to set, prefixed with + or - as necessary."
  [who mode]
  (let [user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
        channel (.getChannel user-channel-dao (:primary-channel @state/global-config))
        new-mode (str mode " " (resolve-account! who))]
    (-> (.send channel)
        (.setMode new-mode))))

(defn kick!
  "Kicks a user from the primary channel. Note that `who` has to be a nick in order for this
   to work."
  [^String who]
  (let [user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
        channel (.getChannel user-channel-dao (:primary-channel @state/global-config))]
    (when-some [user (.getUser user-channel-dao who)]
      (-> (.send channel)
          (.kick user)))))
