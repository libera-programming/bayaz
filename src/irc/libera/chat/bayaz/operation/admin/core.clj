(ns irc.libera.chat.bayaz.operation.admin.core
  (:require [clojure.string :as string]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.operation.util :as operation.util]))

(defmulti process!
  (fn [op]
    (:command op)))

; TODO: Move this to a markdown file in the repo and just link to it.
(def command->help {"help" {:order 0
                            :description "Show this help output."}
                    "warn" {:order 2
                            :description "Shows a public warning to the nick, with an optional message."
                            :syntax "warn <nick> [message]"}
                    "quiet" {:order 3
                             :description "Set mode +q on the target."
                             :syntax "quiet <target>"}
                    "unquiet" {:order 4
                               :description "Set mode -q on the target."
                               :syntax "unquiet <target>"}
                    "ban" {:order 5
                           :description "Set mode +b on the target."
                           :syntax "ban <target>"}
                    "unban" {:order 6
                             :description "Set mode -b on the target."
                             :syntax "unban <target>"}
                    "kick" {:order 7
                            :description "Removes the nick from the channel."
                            :syntax "kick <nick>"}
                    "kickban" {:order 8
                               :description "Removes the nick from the channel and then sets mode +b."
                               :syntax "kickban <nick>"}})

(defn build-help-message []
  (let [I (:nick @state/global-config)
        msg (str I " supports various admin commands. From any channel or DM, all of his mode changes "
                 "will only affect " (:primary-channel @state/global-config)
                 ". By default, you should use nicks as the target of your mode changes. " I " will "
                 "update the mode of the whole account whenever possible, but " I " will fall back to "
                 "hostmask when necessary. You could also just specify a hostmask, if you want."
                 " \n \n"
                 "Supported admin commands:\n")
        command->prefixes (reduce (fn [acc [prefixed command]]
                                    (update acc command (fnil conj []) prefixed))
                                  {}
                                  @operation.util/prefixed-command->command)
        commands (->> command->prefixes
                      (sort-by (comp :order command->help first))
                      (reduce (fn [acc [command prefixes]]
                                (str acc
                                     "  " command " (aliases: "
                                     (->> (sort-by count prefixes)
                                          (string/join ", "))
                                     "): " (-> command command->help :description) "\n"
                                     (when-some [syntax (-> command command->help :syntax)]
                                       (str "    e.g. " syntax "\n"))))
                              ""))]
    (str msg commands)))

(defmethod process! "help"
  [op]
  (let [lines (string/split-lines (build-help-message))]
    (doseq [line lines]
      (.respondWith (:event op) line))))

(defmethod process! "warn"
  [op]
  (operation.util/action! (str "warns " (-> op :args first)
                               (when-not (empty? (-> op :args rest))
                                 (str ": " (string/join " " (-> op :args rest)))))))

(defmethod process! "quiet"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (operation.util/set-user-mode! "+q" who)))

(defmethod process! "unquiet"
  [op]
  (let [; TODO: Validate
        [who] (:args op)]
    (operation.util/set-user-mode! "-q" who)))

(defmethod process! "ban"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (operation.util/set-user-mode! "-q+b" who who)))

(defmethod process! "unban"
  [op]
  (let [; TODO: Validate
        [who] (:args op)]
    (operation.util/set-user-mode! "-b" who)))

(defmethod process! "kickban"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (process! (assoc op :command "ban"))
    (operation.util/kick! who)))

(defmethod process! "kick"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (operation.util/kick! who)))

(defmethod process! :default
  [op]
  :not-handled)
