(ns irc.libera.chat.bayaz.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.core.incubator :refer [dissoc-in]]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.operation.admin.core :as operation.admin.core]
            [irc.libera.chat.bayaz.operation.public.core :as operation.public.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util])
  (:import [org.pircbotx PircBotX Configuration$Builder User]
           [org.pircbotx.cap SASLCapHandler]
           [org.pircbotx.delay StaticDelay]
           [org.pircbotx.hooks ListenerAdapter]
           [org.pircbotx.hooks.events TopicEvent MessageEvent PrivateMessageEvent WhoisEvent
            BanListEvent QuietListEvent ServerResponseEvent]))

(defn admin? [user-login]
  (contains? (:admins @state/global-config) (string/lower-case user-login)))

(defn admin?! [^User user]
  ; We first check the login name, which needs to match. Then we do the expensive whois
  ; to be absolutely certain. We remove the ~ prefix from the login to match that account name.
  (or (when (admin? (subs (.getLogin user) 1))
        (when-some [^WhoisEvent whois-event (operation.util/whois! user)]
          (admin? (.getRegisteredAs whois-event))))
      false))

(defn fetch-ban-lists! [event]
  (when (and (= (:primary-channel @state/global-config) (.getName (.getChannel event)))
             (.isComplete event))
    (let [channel (.getChannel event)]
    ;(let [user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
    ;      channel (.getChannel user-channel-dao (:primary-channel @state/global-config))]
      ; Request the quiet and ban lists.
      (.setMode (.send channel) "q")
      (.setMode (.send channel) "b"))))

(defn process-message! [^User user message message-type event]
  (let [from-admin? (admin?! user)
        operation (-> (operation.util/message->operation message)
                      (assoc :type message-type
                             :event event)
                      operation.util/normalize-command)]
    (if (and from-admin? (some? operation))
      (when (= :not-handled (operation.admin.core/process! operation))
        (operation.public.core/process! operation))
      (operation.public.core/process! operation))))

(defn process-whois! [^WhoisEvent event]
  ; There's probably an operation waiting for the result of a whois request, so deliver
  ; on that promise, if possible.
  (when-some [pending (get-in @state/pending-event-requests [:whois (.getNick event)])]
    (swap! state/pending-event-requests dissoc-in [:whois (.getNick event)])
    (deliver pending event)))

(defn process-ban-list! [^BanListEvent event]
  (println (str "ban list: " (.getEntries event)))
  (when-some [pending (get-in @state/pending-event-requests [:ban-list (.getName (.getChannel event))])]
    (swap! state/pending-event-requests dissoc-in [:ban-list (.getName (.getChannel event))])
    (deliver pending event)))

(defn process-quiet-list! [clj-event]
  (println (str "quiet list: " (.getEntries clj-event)))
  (when-some [pending (get-in @state/pending-event-requests [:quiet-list (:channel-name clj-event)])]
    (swap! state/pending-event-requests dissoc-in [:quiet-list (:channel-name clj-event)])
    (deliver pending clj-event)))

(def event-listener (proxy [ListenerAdapter] []
                      ; Each of these calls out to a standalone function for the primary purpose
                      ; of live code reloading during dev. Clojure functions can be easily
                      ; redefined, but this proxy class will be immutable inside the bot.
                      (onUserList [event]
                        (fetch-ban-lists! event))
                      (onMessage [^MessageEvent event]
                        (process-message! (.getUser event) (.getMessage event) :public event))
                      (onPrivateMessage [^PrivateMessageEvent event]
                        (process-message! (.getUser event) (.getMessage event) :private event))
                      (onWhois [^WhoisEvent event]
                        (process-whois! event))
                      (onBanList [^BanListEvent event]
                        (process-ban-list! event))
                      (onQuietList [^QuietListEvent event]
                        (process-quiet-list! event))))

(defn start! []
  (let [bot-config (-> (Configuration$Builder.)
                       (.setName (:nick @state/global-config))
                       (.setRealName (:real-name @state/global-config))
                       (.setLogin (:nick @state/global-config))
                       (.addServer ^String (:server @state/global-config))
                       (.setMessageDelay (StaticDelay. 0))
                       (.addCapHandler (SASLCapHandler. (:nick @state/global-config)
                                                        (:pass @state/global-config)))
                       (.addAutoJoinChannels (seq (cons (:primary-channel @state/global-config)
                                                        (:additional-channels @state/global-config))))
                       (.addListener event-listener)
                       .buildConfiguration)]
    (reset! state/bot (PircBotX. bot-config))
    (future (.startBot ^PircBotX @state/bot))))

(defn stop! []
  (try
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
  (restart!))

(defn -main [& args]
  (start!))
