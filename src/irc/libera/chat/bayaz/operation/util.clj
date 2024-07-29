(ns irc.libera.chat.bayaz.operation.util
  (:require [clojure.string :as string]
            [clojure.core.async :as async]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.track.core :as track.core])
  (:import [org.pircbotx PircBotX User UserChannelDao]))

(let [make-prefixes (fn [command & prefixes]
                      (-> (map #(str (:command-prefix @state/global-config) %)
                               (cons command prefixes))
                          (zipmap (repeat command))))]
  ; Each command has a full form and a "prefixed" form. For example, the full
  ; `quiet` form may have a `!q` and `!quiet` prefixed form. Prefixed forms can be used
  ; in public channels without a bot mention, since they're meant to be distinct. Full
  ; forms can also be used, but only with a mention or via DM.
  (def prefixed-command->command (delay (merge (make-prefixes "warn" "w")
                                               (make-prefixes "warnall" "wa")
                                               (make-prefixes "quiet" "q" "+q")
                                               (make-prefixes "unquiet" "uq" "-q")
                                               (make-prefixes "ban" "b" "+b")
                                               (make-prefixes "unban" "ub" "-b")
                                               (make-prefixes "kickban" "kb")
                                               (make-prefixes "kick" "k")
                                               (make-prefixes "history" "h")
                                               (make-prefixes "whois" "who")
                                               (make-prefixes "deepwhois" "dwho")

                                               ; TODO: Differentiate between admin/public commands.
                                               ; Public
                                               (make-prefixes "bayaz")
                                               (make-prefixes "ops"))))
  (def commands (delay (into #{} (vals @prefixed-command->command)))))

(def admin-action->str {"ban" "Ban"
                        "unban" "Unban"
                        "quiet" "Quiet"
                        "unquiet" "Unquiet"
                        "warn" "Warn"
                        "kick" "Kick"})

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

(defn whois!
  "Asynchronously fetches a WhoisEvent for the specified user. Returns the
  WhoisEvent. If the fetch times out, nil is returned."
  [^User user]
  (async/go
    (let [nick (.getNick user)
          new-chan (async/chan)
          timeout-chan (async/timeout 5000)
          pending-snapshot (swap! state/pending-event-requests
                                  (fn [pev]
                                    (if (some? (get-in pev [:whois nick]))
                                      pev
                                      (assoc-in pev [:whois nick] new-chan))))
          pending-whois (get-in pending-snapshot [:whois nick])
          new-request? (identical? new-chan pending-whois)]
      (when new-request?
        (-> user .send .whois))

      (let [[v _port] (async/alts! [pending-whois timeout-chan] :priority true)]
        v))))

(defn message! [& args]
  (let [^UserChannelDao user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
        channel (.getChannel user-channel-dao (:primary-channel @state/global-config))
        message (string/join " " args)]
    (-> (.send channel)
        (.message message))))

(defn action! [& args]
  (let [^UserChannelDao user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
        channel (.getChannel user-channel-dao (:primary-channel @state/global-config))
        action (string/join " " args)]
    (-> (.send channel)
        (.action action))))

(defn set-user-mode!
  "Sets the modes for the specified users in the primary channel. `who` is a
  sequence of any valid user identifier and `modes` should be the modes to set,
  prefixed with + or - as necessary."
  [modes & who]
  (let [^UserChannelDao user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
        channel (.getChannel user-channel-dao (:primary-channel @state/global-config))
        ; TODO: This could be optimized to not resolve the same user more than once.
        new-modes (clojure.string/join " " (cons modes (map track.core/resolve-account! who)))]
    (println "set-user-mode" (pr-str {:who who
                                      :modes modes
                                      :new-modes new-modes}))
    (-> (.send channel)
        (.setMode new-modes))))

(defn kick!
  "Kicks a user from the primary channel. Note that `who` has to be a nick in order for this
  to work."
  [^String who]
  (let [^UserChannelDao user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
        channel (.getChannel user-channel-dao (:primary-channel @state/global-config))]
    (when-some [user (.getUser user-channel-dao who)]
      (-> (.send channel)
          (.kick user)))))
