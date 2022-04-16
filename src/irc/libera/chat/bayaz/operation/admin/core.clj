(ns irc.libera.chat.bayaz.operation.admin.core
  (:require [clojure.string :as string]
            [irc.libera.chat.bayaz.db.core :as db.core]
            [irc.libera.chat.bayaz.util :as util]
            [irc.libera.chat.bayaz.operation.util :as operation.util]))

(defn track-operation! [action admin-account who why]
  (let [who (clojure.string/lower-case who)
        why (clojure.string/join " " why)
        [hostname-ref] (operation.util/resolve-hostname! who)]
    (when (some? hostname-ref)
      (db.core/transact! [(merge {:db/id -1
                                  :user/hostname-ref hostname-ref
                                  :admin/account admin-account
                                  :admin/action action
                                  :time/when (System/currentTimeMillis)}
                                 (when (some? why)
                                   {:admin/reason why}))]))))

(defmulti process!
  (fn [op]
    (:command op)))

(defmethod process! "warn"
  [op]
  (let [[who & why] (:args op)]
    ; TODO: Ensure who is in the channel
    (track-operation! :admin/warn (:account op) who why)
    (operation.util/message! (str "This is a warning, " who ". " (when-not (empty? why)
                                                                   (string/join " " why))))))

(defmethod process! "quiet"
  [op]
  (let [[who & why] (:args op)]
    (track-operation! :admin/quiet (:account op) who why)
    (operation.util/set-user-mode! "+q" who)))

(defmethod process! "unquiet"
  [op]
  (let [; TODO: Validate
        [who] (:args op)]
    (track-operation! :admin/unquiet (:account op) who "")
    (operation.util/set-user-mode! "-q" who)))

(defmethod process! "ban"
  [op]
  (let [; TODO: Validate this input.
        [who why until] (:args op)]
    (track-operation! :admin/ban (:account op) who why)
    (operation.util/set-user-mode! "-q+b" who who)))

(defmethod process! "unban"
  [op]
  (let [; TODO: Validate
        [who] (:args op)]
    (track-operation! :admin/unban (:account op) who "")
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
    (track-operation! :admin/kick (:account op) who why)
    (operation.util/kick! who)))

(def max-history-lines 5)

(defmethod process! "history"
  [op]
  (let [[who] (:args op)
        who (clojure.string/lower-case who)
        [hostname-ref hostname] (operation.util/resolve-hostname! who)
        ; TODO: Optimize by limiting query; couldn't make it work with datalevin.
        actions (->> (db.core/query! '[:find [(pull ?a [*]) ...]
                                       :in $ ?hostname-ref
                                       :where
                                       [?a :user/hostname-ref ?hostname-ref]
                                       [?a :admin/action]]
                                     hostname-ref)
                     (sort-by :time/when #(compare %2 %1)))
        now (System/currentTimeMillis)
        response (->> (take max-history-lines actions)
                      (map (fn [action]
                             ; 3d ago - Ban from jeaye: Spamming
                             (str (util/relative-time-offset now (:time/when action))
                                  " - "
                                  (-> (db.core/entity (-> action :admin/action :db/id))
                                      :db/ident
                                      operation.util/admin-action->str)
                                  " from "
                                  (:admin/account action)
                                  (when-not (empty? (:admin/reason action))
                                    (str ": " (:admin/reason action)))))))
        remaining-actions (drop max-history-lines actions)
        footer (when-not (empty? remaining-actions)
                 (str "Not showing "
                      (count remaining-actions)
                      " action(s) going back to "
                      (util/relative-time-offset now (-> remaining-actions last :time/when))
                      "."))]
    (.respondWith (:event op)
                  (if (empty? actions)
                    (str "No admin operation history for " who)
                    (str "Admin operation history for " who
                         (when-not (or (= who hostname) (operation.util/hostmask? who))
                           (str " (latest hostname " hostname ")")))))
    (doseq [r response]
      (.respondWith (:event op) r))
    (when (some? footer)
      (.respondWith (:event op) footer))))

(defmethod process! :default
  [op]
  :not-handled)
