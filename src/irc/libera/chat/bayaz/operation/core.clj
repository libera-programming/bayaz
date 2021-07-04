(ns irc.libera.chat.bayaz.operation.core
  (:require [irc.libera.chat.bayaz.operation.util :as operation.util]))

(defmulti process!
  (fn [op]
    (:command op)))

(defmethod process! "quiet"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (operation.util/set-user-mode! who "+q")))

(defmethod process! "unquiet"
  [op]
  (let [[who] (:args op)]
    (operation.util/set-user-mode! who "-q")))

(defmethod process! "ban"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (operation.util/set-user-mode! who "+b")))

(defmethod process! "unban"
  [op]
  (let [[who] (:args op)]
    (operation.util/set-user-mode! who "-b")))

(defmethod process! "kickban"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (process! (assoc op :command "ban"))
    (operation.util/kick! who)))
