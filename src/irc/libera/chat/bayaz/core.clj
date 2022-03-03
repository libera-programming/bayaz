(ns irc.libera.chat.bayaz.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.core.incubator :refer [dissoc-in]]
            [clojure.core.async :as async]
            [datalevin.core :as datalevin]
            [irc.libera.chat.bayaz.util :as util]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.db.core :as db.core]
            [irc.libera.chat.bayaz.track.core :as track.core]
            [irc.libera.chat.bayaz.operation.admin.core :as operation.admin.core]
            [irc.libera.chat.bayaz.operation.public.core :as operation.public.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util])
  (:import [org.pircbotx PircBotX Configuration$Builder User]
           [org.pircbotx.cap SASLCapHandler]
           [org.pircbotx.delay StaticDelay]
           [org.pircbotx.hooks ListenerAdapter]
           [org.pircbotx.hooks.events MessageEvent PrivateMessageEvent WhoisEvent
            UserListEvent BanListEvent QuietListEvent JoinEvent]))

(defn admin? [user-login]
  (contains? (:admins @state/global-config) (string/lower-case user-login)))

(defn admin?! [^User user]
  ; We first check the login name, which needs to match. Then we do the expensive whois
  ; to be absolutely certain. We remove the ~ prefix from the login to match that account name.
  (async/go
    (or (when (admin? (subs (.getLogin user) 1))
          (when-some [^WhoisEvent whois-event (async/<! (operation.util/whois! user))]
            (admin? (.getRegisteredAs whois-event))))
        false)))

(defn process-user-list! [^UserListEvent event]
  ; We get two UserListEvents when joining a channel. One from NAMES, which is
  ; not complete, meaning it lacks hostname and login info for each user, and
  ; one from WHO, which has all of that and is complete.
  (when (.isComplete event)
    (doseq [user (.getUsers event)]
      (println "process user list" (.getNick user))
      (track.core/track-user! user (.getTimestamp event)))))

(defn fetch-ban-lists! [^UserListEvent event]
  (when (and (= (:primary-channel @state/global-config) (.getName (.getChannel event)))
             (.isComplete event))
    (let [channel (.getChannel event)]
      ; Request the quiet and ban lists.
      (.setMode (.send channel) "q")
      (.setMode (.send channel) "b"))))

(defn process-message! [^User user message message-type event]
  ; TODO: Test this check.
  (when-not (= (.getUserBot ^PircBotX @state/bot) user)
    (async/go
      (let [from-admin? (async/<! (admin?! user))
            operation (-> (operation.util/message->operation message)
                          (assoc :type message-type
                                 :event event)
                          (merge (when (= :public message-type)
                                   {:channel (.getName (.getChannel event))}))
                          operation.util/normalize-command)
            command? (-> operation :command some?)
            not-handled-admin-op? (when (and from-admin? command?)
                                    (= :not-handled (operation.admin.core/process! operation)))]
        (cond
          (not command?)
          (operation.public.core/process-message! operation)

          not-handled-admin-op?
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
  (println "process join" (-> event .getUser .getNick))
  (track.core/track-user! (.getUser event) (.getTimestamp event)))

(def event-listener (proxy [ListenerAdapter] []
                      ; Each of these calls out to a standalone function for the primary purpose
                      ; of live code reloading during dev. Clojure functions can be easily
                      ; redefined, but this proxy class will be immutable inside the bot.
                      (onUserList [^UserListEvent event]
                        (process-user-list! event)
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
                        (process-quiet-list! event))
                      (onJoin [^JoinEvent event]
                        (process-join! event))))

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
    (db.core/connect!)
    (track.core/start-listener!)
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
  (restart!))

(defn -main [& args]
  (start!))
