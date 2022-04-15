(ns irc.libera.chat.bayaz.operation.admin.core
  (:require [clojure.string :as string]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.operation.util :as operation.util]))

(defmulti process!
  (fn [op]
    (:command op)))

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
