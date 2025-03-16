(ns irc.libera.chat.bayaz.operation.admin.core
  (:require [clojure.string :as string]
            [clojure.data.json :as json]
            [taoensso.timbre :as timbre]
            [honey.sql.helpers :refer [select from where order-by
                                       insert-into values]]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.postgres.core :as postgres.core]
            [irc.libera.chat.bayaz.util :as util]
            [irc.libera.chat.bayaz.operation.util :as operation.util]
            [irc.libera.chat.bayaz.track.core :as track.core])
  (:import [org.pircbotx PircBotX UserChannelDao]
           [org.pircbotx.hooks.types GenericMessageEvent]))

(defn track-operation! [channel action admin-account who why]
  (let [who (clojure.string/lower-case who)
        why (clojure.string/join " " why)
        [hostname-ref] (track.core/resolve-hostname! who)]
    (if (some? hostname-ref)
      (postgres.core/execute! (-> (insert-into :admin_action)
                                  (values [{:target_id hostname-ref
                                            :channel channel
                                            :admin_account admin-account
                                            :action (name action)
                                            :reason why
                                            :seen (System/currentTimeMillis)}])))
      (println "error: no hostname ref while tracking admin action"
               {:action action
                :channel channel
                :admin admin-account
                :who who
                :why why}))))

(def max-history-lines 5)

(defn find-admin-actions-for-hostname-ref! [channel hostname-ref]
  (postgres.core/execute! (-> (select :*)
                              (from :admin_action)
                              (where [:= :target_id hostname-ref]
                                     [:= :channel channel])
                              (order-by [:seen :desc]))))

(defmulti process!
  (fn [op]
    (:command op)))

(defmethod process! "warn"
  [op]
  (let [[who & why] (:args op)
        ^UserChannelDao user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (if-not (.containsUser user-channel-dao ^String who)
      (.respond ^GenericMessageEvent (:event op) "Warn syntax is: !w <nick> [reason]")
      (do
        (track-operation! channel :admin/warn (:account op) who why)
        (operation.util/message! channel
                                 (str "This is a warning, " who ". " (when-not (empty? why)
                                                                       (string/join " " why))))))))

(defmethod process! "warnall"
  [op]
  (let [[& why] (:args op)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (operation.util/message! channel
                             (str "Everyone, this is a warning. " (when-not (empty? why)
                                                                    (string/join " " why))))))

(defmethod process! "quiet"
  [op]
  (timbre/debug :quiet op)
  (let [[who & why] (:args op)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (track-operation! channel :admin/quiet (:account op) who why)
    (operation.util/set-user-mode! channel "+q" who)))

(defmethod process! "unquiet"
  [op]
  (let [; TODO: Validate
        [who & why] (:args op)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (track-operation! channel :admin/unquiet (:account op) who why)
    (operation.util/set-user-mode! channel "-q" who)))

(defmethod process! "ban"
  [op]
  (let [; TODO: Validate this input.
        [who & why] (:args op)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (track-operation! channel :admin/ban (:account op) who why)
    (operation.util/set-user-mode! channel "-q+b" who who)))

(defmethod process! "unban"
  [op]
  (let [; TODO: Validate
        [who & why] (:args op)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (track-operation! channel :admin/unban (:account op) who why)
    (operation.util/set-user-mode! channel "-b" who)))

(defmethod process! "kickban"
  [op]
  (let [; TODO: Validate this input.
        [who] (:args op)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (process! (assoc op :command "ban"))
    (operation.util/kick! channel who)))

(defmethod process! "kick"
  [op]
  (let [; TODO: Validate this input.
        [who & why] (:args op)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (track-operation! channel :admin/kick (:account op) who why)
    (operation.util/kick! channel who)))

(defmethod process! "note"
  [op]
  (timbre/debug :note op)
  (let [[who & why] (:args op)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))]
    (track-operation! channel :admin/note (:account op) who why)
    (.respondWith ^GenericMessageEvent (:event op) "Noted.")))

(defmethod process! "history"
  [op]
  (let [[who] (:args op)
        who (clojure.string/lower-case who)
        [hostname-ref hostname] (track.core/resolve-hostname! who)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))
        actions (find-admin-actions-for-hostname-ref! channel hostname-ref)
        now (System/currentTimeMillis)
        response (->> (take max-history-lines actions)
                      (map (fn [action]
                             ; 3d ago - Ban from jeaye: Spamming
                             (str (util/relative-time-offset now (:seen action))
                                  " - "
                                  (-> action :action operation.util/admin-action->str)
                                  " from "
                                  (:admin_account action)
                                  (when-not (empty? (:reason action))
                                    (str ": " (:reason action)))))))
        remaining-actions (drop max-history-lines actions)
        footer (when-not (empty? remaining-actions)
                 (str "Not showing "
                      (count remaining-actions)
                      " action(s) going back to "
                      (util/relative-time-offset now (-> remaining-actions last :seen))
                      "."))]
    (.respondWith ^GenericMessageEvent (:event op)
                  (str (if (empty? actions)
                         "No admin operation history for "
                         "Admin operation history for ")
                       who
                       (when-not (or (= who hostname) (track.core/hostmask? who))
                         (str " (latest hostname " hostname ")"))))
    (doseq [r response]
      (.respondWith ^GenericMessageEvent (:event op) r))
    (when (some? footer)
      (.respondWith ^GenericMessageEvent (:event op) footer))))

(defmethod process! "deephistory"
  [op]
  (let [[who] (:args op)
        who (clojure.string/lower-case who)
        channel (state/target-channel-for-channel (util/event->channel (:event op)))
        associations (track.core/deep-whois! who)
        actions (loop [associations associations
                       seen-hostname? #{}
                       actions []]
                  (let [association (first associations)]
                    (cond
                      (empty? associations)
                      (sort-by :seen > actions)

                      (seen-hostname? (:hostname_id association))
                      (recur (rest associations) seen-hostname? actions)

                      :else
                      (recur (rest associations)
                             (conj seen-hostname? (:hostname_id association))
                             (into actions (map #(assoc % :hostname (:hostname association))
                                                (find-admin-actions-for-hostname-ref! channel
                                                                                      (:hostname_id association))))))))
        now (System/currentTimeMillis)
        lines (->> actions
                   (map (fn [action]
                          (str "|" (util/relative-time-offset now (:seen action))
                               "|`" (:hostname action) "`"
                               "|" (-> action :action operation.util/admin-action->str)
                               " from " "`" (:admin_account action) "`"
                               "|" (:reason action)
                               "|"))))
        markdown (str "|When|Who|Action|Reason|\n"
                      "|---|---|---|---|\n"
                      (clojure.string/join "\n" lines))
        gist-result (operation.util/upload-gist! (str "!deephistory " who) markdown)
        error? (not= 201 (:status gist-result))]
    (if error?
      (.respondWith ^GenericMessageEvent (:event op) (str "Unable to upload gist: " (:body gist-result)))
      (let [body (json/read-str (:body gist-result))]
        (.respondWith ^GenericMessageEvent (:event op)
                      (str "Results for deep history on " who ": " (get body "html_url")))))))

(defmethod process! "whois"
  [op]
  (let [[who] (:args op)
        who (clojure.string/lower-case who)
        [hostname-ref hostname] (track.core/resolve-hostname! who)
        associations (track.core/whois! hostname-ref)
        now (System/currentTimeMillis)
        response (->> (take max-history-lines associations)
                      (map (fn [association]
                             ; 1y 2mo ago -> 3d ago - Used nick foo with account bar
                             (str (util/relative-time-offset now (:first_seen association))
                                  " -> "
                                  (util/relative-time-offset now (:last_seen association))
                                  " - Used "
                                  (if-some [nick (:nick association)]
                                    (str "nick " nick
                                         (if-some [account (:account association)]
                                           (str " with account " account)))
                                    (str "account " (:account association)))))))
        remaining (drop max-history-lines associations)
        footer (when-not (empty? remaining)
                 (str "Not showing "
                      (count remaining)
                      " association"
                      (when (< 1 (count remaining))
                        "s")
                      " going back to "
                      (util/relative-time-offset now (-> remaining last :last_seen))
                      "."))]
    (.respondWith ^GenericMessageEvent (:event op)
                  (str (if (empty? associations)
                         "No tracking history for "
                         "Recent nicks and accounts on current hostname ")
                       who
                       (when (and (not (empty? associations))
                                  (not (or (= who hostname) (track.core/hostmask? who))))
                         (str " (hostname " hostname ")"))))
    (doseq [r response]
      (.respondWith ^GenericMessageEvent (:event op) r))
    (when (some? footer)
      (.respondWith ^GenericMessageEvent (:event op) footer))))

(defmethod process! "deepwhois"
  [op]
  (let [[who] (:args op)
        _ (.respondWith ^GenericMessageEvent (:event op) (str "Running deep whois on " who ". This can take a while..."))
        results (track.core/deep-whois! who)
        now (System/currentTimeMillis)
        response (->> results
                      (map (fn [association]
                             (str "|`" (:hostname association) "`"
                                  "|`" (:nick association) "`"
                                  "|`" (:account association) "`"
                                  "|" (util/relative-time-offset now (:first_seen association))
                                  "|" (util/relative-time-offset now (:last_seen association))
                                  "|"))))
        markdown (str "|Hostname|Nick|Account|First Time|Last Time|\n"
                      "|---|---|---|---|---|\n"
                      (clojure.string/join "\n" response))
        gist-result (operation.util/upload-gist! (str "!deepwhois " who) markdown)
        error? (not= 201 (:status gist-result))]
    (if error?
      (.respondWith ^GenericMessageEvent (:event op) (str "Unable to upload gist: " (:body gist-result)))
      (let [body (json/read-str (:body gist-result))]
        (.respondWith ^GenericMessageEvent (:event op)
                      (str "Results for deep whois on " who ": " (get body "html_url")))))))

(defmethod process! :default
  [_op]
  :not-handled)
