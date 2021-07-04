(ns irc.libera.chat.bayaz.core
  (:gen-class)
  (:require [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.operation.core :as operation.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util])
  (:import [org.pircbotx PircBotX Configuration$Builder User]
           [org.pircbotx.cap SASLCapHandler]
           [org.pircbotx.hooks ListenerAdapter]
           [org.pircbotx.hooks.events MessageEvent PrivateMessageEvent WhoisEvent]))

(defn admin? [user-login]
  (contains? (:admins @state/global-config) user-login))

(defn on-message [^User user message message-type event]
  (when (admin? (.getLogin user))
    (when-some [operation (-> (operation.util/message->operation message)
                              (assoc :type message-type
                                     :event event)
                              operation.util/normalize-command)]
      (operation.core/process! operation))))

(defn on-whois [^WhoisEvent event]
  ; There's probably an operation waiting for the result of a whois request, so deliver
  ; on that promise, if possible.
  (when-some [pending (get-in @state/pending-event-requests [:whois (.getNick event)])]
    (deliver pending event)))

(def event-listener (proxy [ListenerAdapter] []
                      ; Each of these calls out to a standalone function for the primary purpose
                      ; of live code reloading during dev. Clojure functions can be easily
                      ; redefined, but this proxy class will be immutable inside the bot.
                      (onMessage [^MessageEvent event]
                        (on-message (.getUser event) (.getMessage event) :public event))
                      (onPrivateMessage [^PrivateMessageEvent event]
                        (on-message (.getUser event) (.getMessage event) :private event))
                      (onWhois [^WhoisEvent event]
                        (on-whois event))))

(defn start! []
  (let [bot-config (-> (Configuration$Builder.)
                       (.setName (:nick @state/global-config))
                       (.setLogin (:nick @state/global-config))
                       (.addServer ^String (:server @state/global-config))
                       (.addCapHandler (SASLCapHandler. (:nick @state/global-config)
                                                        (:pass @state/global-config)))
                       (.addAutoJoinChannels (seq (:auto-join @state/global-config)))
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
