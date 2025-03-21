(ns irc.libera.chat.bayaz.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.core.incubator :refer [dissoc-in]]
            [clojure.core.async :as async]
            [taoensso.timbre :as timbre]
            [irc.libera.chat.bayaz.util :as util]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.postgres.core :as postgres.core]
            [irc.libera.chat.bayaz.track.core :as track.core]
            [irc.libera.chat.bayaz.operation.admin.core :as operation.admin.core]
            [irc.libera.chat.bayaz.operation.public.core :as operation.public.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util])
  (:import [org.pircbotx PircBotX Configuration$Builder User]
           [org.pircbotx.cap SASLCapHandler EnableCapHandler]
           [org.pircbotx.delay StaticDelay]
           [org.pircbotx.hooks ListenerAdapter]
           [org.pircbotx.hooks.types GenericMessageEvent]
           [org.pircbotx.hooks.events MessageEvent PrivateMessageEvent
            WhoisEvent BanListEvent QuietListEvent JoinEvent PartEvent QuitEvent
            ServerResponseEvent NickChangeEvent]))

(defn track-with-tags! [nick hostname tags timestamp]
  (send-off track.core/queue
            (fn [_]
              (track.core/track-user! (merge tags {:nick nick
                                                   :hostname hostname})
                                      timestamp))))

(defn process-message! [^User user message message-type ^GenericMessageEvent event]
  (try
    (let [account (-> event .getV3Tags util/java-tags->clj-tags :account)]
      (track-with-tags! (.getNick user)
                        (-> event .getUserHostmask .getHostname)
                        (-> event .getV3Tags util/java-tags->clj-tags)
                        (.getTimestamp event))

      ; TODO: Test this check.
      (when-not (= (.getUserBot ^PircBotX @state/bot) user)
        (let [channel (util/event->channel event)
              from-admin? (state/admin? channel account)
              operation (-> (operation.util/message->operation message)
                            (assoc :type message-type
                                   :event event
                                   :account account)
                            (merge (when (= :public message-type)
                                     {:channel channel}))
                            operation.util/normalize-command)
              command? (-> operation :command some?)
              ; TODO: Is this needed, if we have the operation type on hand?
              not-handled-admin-op? (when (and from-admin? command? (state/feature-enabled? channel :admin))
                                      (= :not-handled (operation.admin.core/process! operation)))]
          (cond
            (not command?)
            (operation.public.core/process-message! operation)

            (or not-handled-admin-op? (= :public (:type operation)))
            (operation.public.core/process! operation)))))
    (catch Throwable e
      (timbre/error :exception e))))

(defn process-whois! [^WhoisEvent event]
  ; There's probably an operation waiting for the result of a whois request, so deliver
  ; on that promise, if possible.
  (when-some [pending (get-in @state/pending-event-requests [:whois (.getNick event)])]
    (swap! state/pending-event-requests dissoc-in [:whois (.getNick event)])
    (async/go
      (async/>! pending event))))

(defn process-ban-list! [^BanListEvent event]
  (timbre/info :ban-list (.getEntries event))
  (when-some [pending (get-in @state/pending-event-requests [:ban-list (util/event->channel event)])]
    (swap! state/pending-event-requests dissoc-in [:ban-list (util/event->channel event)])
    (deliver pending event)))

(defn process-quiet-list! [^QuietListEvent event]
  (timbre/info :quiet-list (.getEntries event))
  (when-some [pending (get-in @state/pending-event-requests [:quiet-list (util/event->channel event)])]
    (swap! state/pending-event-requests dissoc-in [:quiet-list (util/event->channel event)])
    (deliver pending event)))

(defn process-join! [^JoinEvent event]
  (let [channel-name (util/event->channel event)
        user (.getUser event)]
    (timbre/info :join :channel channel-name :nick (.getNick user))

    ; If we're joining a channel, send a WHOX to learn about every user in the channel. We only
    ; request for the useful info for tracking: hostname, nick, and account.
    (if (= (.getUserBot ^PircBotX @state/bot) user)
      (-> ^PircBotX @state/bot
          .sendRaw
          (.rawLine (str "WHO " (-> event .getChannel .getName) " %hna")))
      ; Some other user joining.
      (track-with-tags! (.getNick user)
                        (-> event .getUserHostmask .getHostname)
                        (-> event .getTags util/java-tags->clj-tags)
                        (.getTimestamp event)))))

(defn process-part! [^PartEvent event]
  (timbre/info :part :channel (-> event .getChannel .getName) :nick (-> event .getUser .getNick))
  (track-with-tags! (-> event .getUser .getNick)
                    (-> event .getUserHostmask .getHostname)
                    (-> event .getTags util/java-tags->clj-tags)
                    (.getTimestamp event)))

(defn process-quit! [^QuitEvent event]
  (timbre/info :quit :nick (-> event .getUser .getNick))
  (track-with-tags! (-> event .getUser .getNick)
                    (-> event .getUserHostmask .getHostname)
                    (-> event .getTags util/java-tags->clj-tags)
                    (.getTimestamp event)))

; TODO: This is never being triggered.
(defn process-nick-change! [^NickChangeEvent event]
  (timbre/info :nick-change
               :old-nick (-> event .getOldNick)
               :new-nick (-> event .getNewNick))
  (track-with-tags! (-> event .getNewNick)
                    (-> event .getUserHostmask .getHostname)
                    (-> event .getTags util/java-tags->clj-tags)
                    (.getTimestamp event)))

(def lock (Object.))

(defn process-server-response! [^ServerResponseEvent event]
  #_(locking lock
    (timbre/debug "raw" (.getCode event) (.getRawLine event)))
  (case (.getCode event)
    ; :molybdenum.libera.chat 354 client hostname nick account
    354
    (let [[_ _ _ hostname nick account] (string/split (.getRawLine event) #" ")]
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
                        ; XXX: We drop PMs, at this point.
                        nil
                        #_(process-message! (.getUser event) (.getMessage event) :private event))
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
  (timbre/swap-config! assoc :timestamp-opts {:pattern "yyyy-MM-dd HH:mm:ss"
                                              :locale :jvm-default
                                              :timezone :utc})
  (timbre/debug :global-config @state/global-config)
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
                       (.addAutoJoinChannels (keys (:channels @state/global-config)))
                       (.addListener event-listener)
                       ; We send our own WHOX, rather than the lib's WHO, so we
                       ; can get account info for everyone already in the
                       ; channel.
                       (.setOnJoinWhoEnabled false)
                       .buildConfiguration)]
    (reset! state/bot (PircBotX. bot-config))
    (postgres.core/connect!)
    (future (.startBot ^PircBotX @state/bot))))

(defn stop! []
  (try
    (postgres.core/disconnect!)
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
