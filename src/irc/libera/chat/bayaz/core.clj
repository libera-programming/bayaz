(ns irc.libera.chat.bayaz.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.core.incubator :refer [dissoc-in]]
            [clojure.core.async :as async]
            [irc.libera.chat.bayaz.util :as util]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.db.core :as db.core]
            [irc.libera.chat.bayaz.track.core :as track.core]
            [irc.libera.chat.bayaz.operation.admin.core :as operation.admin.core]
            [irc.libera.chat.bayaz.operation.public.core :as operation.public.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util])
  (:import [org.pircbotx PircBotX Configuration$Builder User]
           [org.pircbotx.cap SASLCapHandler EnableCapHandler]
           [org.pircbotx.delay StaticDelay]
           [org.pircbotx.hooks ListenerAdapter]
           [org.pircbotx.hooks.events MessageEvent PrivateMessageEvent WhoisEvent
            BanListEvent QuietListEvent JoinEvent PartEvent QuitEvent
            ServerResponseEvent NickChangeEvent]))

(defn admin? [account]
  (and (some? account) (contains? (:admins @state/global-config) (string/lower-case account))))

(defn track-with-tags! [nick hostname tags timestamp]
  (send-off track.core/queue
            (fn [_]
              (track.core/track-user! (merge tags {:nick nick
                                                   :hostname hostname})
                                      timestamp))))

(defn process-message! [^User user message message-type event]
  (let [account (-> event .getTags util/java-tags->clj-tags :account)]
    (track-with-tags! (.getNick user)
                      (-> event .getUserHostmask .getHostname)
                      (-> event .getTags util/java-tags->clj-tags)
                      (.getTimestamp event))

    ; TODO: Test this check.
    (when-not (= (.getUserBot ^PircBotX @state/bot) user)
      (let [from-admin? (admin? account)
            operation (-> (operation.util/message->operation message)
                          (assoc :type message-type
                                 :event event
                                 :account account)
                          (merge (when (= :public message-type)
                                   {:channel (.getName (.getChannel event))}))
                          operation.util/normalize-command)
            command? (-> operation :command some?)
            not-handled-admin-op? (when (and from-admin? command? (state/feature-enabled? :admin))
                                    (= :not-handled (operation.admin.core/process! operation)))]
        (cond
          (not command?)
          (operation.public.core/process-message! operation)

          (or not-handled-admin-op? (= :public (:type operation)))
          (operation.public.core/process! operation))))))

(defn process-whois! [^WhoisEvent event]
  ; There's probably an operation waiting for the result of a whois request, so deliver
  ; on that promise, if possible.
  (when-some [pending (get-in @state/pending-event-requests [:whois (.getNick event)])]
    (swap! state/pending-event-requests dissoc-in [:whois (.getNick event)])
    (async/go
      (async/>! pending event))))

(defn process-ban-list! [^BanListEvent event]
  (println (str "ban list: " (.getEntries event)))
  (when-some [pending (get-in @state/pending-event-requests [:ban-list (.getName (.getChannel event))])]
    (swap! state/pending-event-requests dissoc-in [:ban-list (.getName (.getChannel event))])
    (deliver pending event)))

(defn process-quiet-list! [^QuietListEvent event]
  (println (str "quiet list: " (.getEntries event)))
  ; TODO: Check this event usage; it's using clj keywords.
  (when-some [pending (get-in @state/pending-event-requests [:quiet-list (:channel-name event)])]
    (swap! state/pending-event-requests dissoc-in [:quiet-list (:channel-name event)])
    (deliver pending event)))

(defn process-join! [^JoinEvent event]
  (let [channel-name (-> event .getChannel .getName)
        user (.getUser event)]
    (println "process join" channel-name (.getNick user))

    ; If we're joining a channel, send a WHOX to learn about every user in the channel. We only
    ; request for the useful info for tracking: hostname, nick, and account.
    (if (= (.getUserBot ^PircBotX @state/bot) user)
      (-> @state/bot
          .sendRaw
          (.rawLine (str "WHO " (-> event .getChannel .getName) " %hna")))
      ; Some other user joining.
      (track-with-tags! (.getNick user)
                        (-> event .getUserHostmask .getHostname)
                        (-> event .getTags util/java-tags->clj-tags)
                        (.getTimestamp event)))))

(defn process-part! [^PartEvent event]
  (println "process part" (-> event .getChannel .getName) (-> event .getUser .getNick))
  (track-with-tags! (-> event .getUser .getNick)
                    (-> event .getUserHostmask .getHostname)
                    (-> event .getTags util/java-tags->clj-tags)
                    (.getTimestamp event)))

(defn process-quit! [^QuitEvent event]
  (println "process quit" (-> event .getChannel .getName) (-> event .getUser .getNick))
  (track-with-tags! (-> event .getUser .getNick)
                    (-> event .getUserHostmask .getHostname)
                    (-> event .getTags util/java-tags->clj-tags)
                    (.getTimestamp event)))

(defn process-nick-change! [^NickChangeEvent event]
  (println "process nick change"
           (-> event .getChannel .getName)
           (-> event .getUser .getOldNick)
           "->"
           (-> event .getUser .getNewNick))
  (track-with-tags! (-> event .getUser .getNewNick)
                    (-> event .getUserHostmask .getHostname)
                    (-> event .getTags util/java-tags->clj-tags)
                    (.getTimestamp event)))

(def lock (Object.))

(defn process-server-response! [^ServerResponseEvent event]
  #_(locking lock
    (println "raw" (.getCode event) (.getRawLine event)))
  (case (.getCode event)
    ; :molybdenum.libera.chat 354 client hostname nick account
    354
    (let [[_ _ _ hostname nick account] (clojure.string/split (.getRawLine event) #" ")]
      (track-with-tags! nick hostname
                        (merge {}
                               (when-not (= "0" account)
                                 {:account account}))
                        (.getTimestamp event)))))

(def event-listener (proxy [ListenerAdapter] []
                      ; Each of these calls out to a standalone function for the primary purpose
                      ; of live code reloading during dev. Clojure functions can be easily
                      ; redefined, but this proxy class will be immutable inside the bot.
                      (onMessage [^MessageEvent event]
                        (process-message! (.getUser event) (.getMessage event) :public event))
                      (onPrivateMessage [^PrivateMessageEvent event]
                        (process-message! (.getUser event) (.getMessage event) :private event))
                      (onWhois [^WhoisEvent event]
                        (process-whois! event))
                      (onBanList [^BanListEvent event]
                        (process-ban-list! event))
                      (onQuietList [^QuietListEvent event]
                        (process-quiet-list! event))
                      (onJoin [^JoinEvent event]
                        (process-join! event))
                      (onPart [^PartEvent event]
                        (process-part! event))
                      (onQuit [^QuitEvent event]
                        (process-quit! event))
                      (onNickChange [^NickChangeEvent event]
                        (process-nick-change! event))
                      (onServerResponse [^ServerResponseEvent event]
                        (process-server-response! event))))

(defn start! []
  (let [bot-config (-> (Configuration$Builder.)
                       (.setName (:nick @state/global-config))
                       (.setRealName (:real-name @state/global-config))
                       (.setLogin (:nick @state/global-config))
                       (.addServer ^String (:server @state/global-config))
                       (.setMessageDelay (StaticDelay. 0))
                       (.addCapHandler (SASLCapHandler. (:nick @state/global-config)
                                                        (:pass @state/global-config)))
                       ; This is magic sauce; it asks the network to include an IRCv3 @account= tag
                       ; on messages/actions/mode sets from registered users. It means we don't need
                       ; to whois everyone.
                       (.addCapHandler (EnableCapHandler. "account-tag"))
                       (.addAutoJoinChannels (seq (cons (:primary-channel @state/global-config)
                                                        (:additional-channels @state/global-config))))
                       (.addListener event-listener)
                       ; We send our own WHOX, rather than the lib's WHO, so we
                       ; can get account info for everyone already in the
                       ; channel.
                       (.setOnJoinWhoEnabled false)
                       .buildConfiguration)]
    (reset! state/bot (PircBotX. bot-config))
    (db.core/connect!)
    (future (.startBot ^PircBotX @state/bot))))

(defn stop! []
  (try
    (db.core/disconnect!)
    (when-some [^PircBotX bot @state/bot]
      (.stopBotReconnect bot)
      (.quitServer (.sendIRC bot) (:quit-message @state/global-config))
      (.close bot))
    (catch Exception _
      )
    (finally
      (reset! state/bot nil))))

(defn restart! []
  (stop!)
  (start!))

(comment
  (stop!)
  (restart!)

  (-> @state/bot
      .sendRaw
      (.rawLine "WHO ##programming-bots %hna")))

(defn -main [& args]
  (start!))
