(ns irc.libera.chat.bayaz.operation.public.core
  (:require [clojure.string :as string]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.operation.util :as operation.util]))

(defmulti process!
  (fn [op]
    (:command op)))

(defmethod process! "ops"
  [op]
  (.respondWith (:event op)
                (str "Admins are: " (string/join ", " (:admins @state/global-config)))))

(defmethod process! :default
  [op]
  :not-handled)
