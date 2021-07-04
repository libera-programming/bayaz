(ns irc.libera.chat.bayaz.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string])
  (:import [org.pircbotx PircBotX Configuration$Builder User]
           [org.pircbotx.cap SASLCapHandler]
           [org.pircbotx.hooks ListenerAdapter]
           [org.pircbotx.hooks.events MessageEvent PrivateMessageEvent WhoisEvent]))

(defonce bot (atom nil))
(defonce pending-event-requests (atom {:whois {}}))

; TODO: Validate and merge with default config.
(def global-config (delay (-> (io/resource "config.edn")
                              slurp
                              edn/read-string)))

(defn admin? [user-login]
  (contains? (:admins @global-config) user-login))

(defn message->operation [message]
  ; TODO: Strip color codes.
  (let [message (-> (clojure.string/trim message)
                    ; Remove any highlight prefix containing the nick.
                    (clojure.string/replace-first (re-pattern (str "^" (:nick @global-config) "[:,#=]?")) ""))
        ; Tokenize the message, but support quotes to delimit composite tokens.
        ; https://regex101.com/r/GUHh5H/1
        [command & args] (->> (re-seq #"([^\r\n\t\f\v \"]+)|\"(.+?)\"" message)
                              ; We want the last non-nil group.
                              (map #(->> (reverse %)
                                         (drop-while nil?)
                                         first)))]
    {:command command
     :args (into [] args)}))

(defn whois! [^User user]
  (let [nick (.getNick user)
        new-promise (promise)
        pending-snapshot (swap! pending-event-requests
                                (fn [pev]
                                  (if (some? (get-in pev [:whois nick]))
                                    pev
                                    (assoc-in pev [:whois nick] new-promise))))
        pending-whois (get-in pending-snapshot [:whois nick])
        new-request? (identical? new-promise pending-whois)]
    (when new-request?
      (-> user
          .send
          .whoisDetail))
    (deref pending-whois 5000 nil)))

(defmulti process-operation!
  (fn [op]
    (:command op)))

(defn resolve-account!
  "Resolves an identifier to the most useful incarnation. These identifiers take three shapes:

   1. The nick of a registered user
   2. The nick of an unregistered user
   3. A hostmask

   The first shape is resolved to an account specifier, to cover all clients logged into that
   account, as well as name changes. The second shape is resolved into a hostmask which covers
   all users and nicks from that host. The third is passed through unchanged."
  [who]
  (let [user-channel-dao (.getUserChannelDao @bot)]
    (or (when (.containsUser user-channel-dao who)
          (when-some [whois-event (whois! (.getUser user-channel-dao who))]
            (let [account-name (.getRegisteredAs whois-event)]
              (if-not (empty? account-name)
                (str "$a:" account-name)
                (str "*!*@" (.getHostname whois-event))))))
        who)))

(defn set-user-mode! [who mode]
  (let [user-channel-dao (.getUserChannelDao @bot)
        channel (.getChannel user-channel-dao (:primary-channel @global-config))
        new-mode (str mode " " (resolve-account! who))]
    (-> (.send channel)
        (.setMode new-mode))))

(defmethod process-operation! "quiet"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (set-user-mode! who "+q")))

(defmethod process-operation! "unquiet"
  [op]
  (let [[who] (:args op)]
    (set-user-mode! who "-q")))

(defmethod process-operation! "ban"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (set-user-mode! who "+b")))

(defmethod process-operation! "unban"
  [op]
  (let [[who] (:args op)]
    (set-user-mode! who "-b")))

(defn on-channel-message [^MessageEvent event]
  (when (admin? (.getLogin (.getUser event)))
    (let [operation (assoc (message->operation (.getMessage event)) :event event)]
      (process-operation! operation))))

(defn on-private-message [^PrivateMessageEvent event]
  (when (admin? (.getLogin (.getUser event)))
    (let [operation (assoc (message->operation (.getMessage event)) :event event)]
      (process-operation! operation))))

(defn on-whois [^WhoisEvent event]
  (when-some [pending (get-in @pending-event-requests [:whois (.getNick event)])]
    (deliver pending event)))

(def event-listener (proxy [ListenerAdapter] []
                      (onMessage [^MessageEvent event]
                        (on-channel-message event))
                      (onPrivateMessage [^PrivateMessageEvent event]
                        (on-private-message event))
                      (onWhois [^WhoisEvent event]
                        (on-whois event))))

(defn start! [& args]
  (let [bot-config (-> (Configuration$Builder.)
                       (.setName (:nick @global-config))
                       (.setLogin (:nick @global-config))
                       (.addServer (:server @global-config))
                       (.addCapHandler (SASLCapHandler. (:nick @global-config)
                                                        (:pass @global-config)))
                       (.addAutoJoinChannels (seq (:auto-join @global-config)))
                       (.addListener event-listener)
                       .buildConfiguration)]
    (reset! bot (PircBotX. bot-config))
    (future (.startBot @bot))))

(defn stop! []
  (try
    (when-some [^PircBotX bot @bot]
      (.stopBotReconnect bot)
      (.quitServer (.sendIRC bot) (:quit-message @global-config))
      (.close bot))
    (catch Exception _
      ))
  (reset! bot nil))

(defn restart! []
  (stop!)
  (start!))

(comment
  (stop!)
  (restart!))

(defn -main [& args]
  (start!))
