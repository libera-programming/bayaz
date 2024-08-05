(ns irc.libera.chat.bayaz.operation.admin.core
  (:require [clojure.string :as string]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [taoensso.timbre :as timbre]
            [honey.sql :as sql]
            [honey.sql.helpers :refer [select from where limit order-by join
                                       insert-into values on-conflict do-update-set returning]]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.postgres.core :as postgres.core]
            [irc.libera.chat.bayaz.util :as util]
            [irc.libera.chat.bayaz.operation.util :as operation.util]
            [irc.libera.chat.bayaz.track.core :as track.core])
  (:import [org.pircbotx PircBotX UserChannelDao]))

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
        channel (state/target-channel-for-channel (.getName (.getChannel (:event op))))]
    (if-not (.containsUser user-channel-dao who)
      (.respond (:event op) "Warn syntax is: !w <nick> [reason]")
      (do
        (track-operation! channel :admin/warn (:account op) who why)
        (operation.util/message! channel
                                 (str "This is a warning, " who ". " (when-not (empty? why)
                                                                       (string/join " " why))))))))

(defmethod process! "warnall"
  [op]
  (let [[& why] (:args op)
        channel (state/target-channel-for-channel (.getName (.getChannel (:event op))))]
    (operation.util/message! channel
                             (str "Everyone, this is a warning. " (when-not (empty? why)
                                                                    (string/join " " why))))))

(defmethod process! "quiet"
  [op]
  (timbre/debug :quiet op)
  (let [[who & why] (:args op)
        channel (state/target-channel-for-channel (.getName (.getChannel (:event op))))]
    (track-operation! channel :admin/quiet (:account op) who why)
    (operation.util/set-user-mode! channel "+q" who)))

(defmethod process! "unquiet"
  [op]
  (let [; TODO: Validate
        [who & why] (:args op)
        channel (state/target-channel-for-channel (.getName (.getChannel (:event op))))]
    (track-operation! channel :admin/unquiet (:account op) who why)
    (operation.util/set-user-mode! channel "-q" who)))

(defmethod process! "ban"
  [op]
  (let [; TODO: Validate this input.
        [who & why] (:args op)
        channel (state/target-channel-for-channel (.getName (.getChannel (:event op))))]
    (track-operation! channel :admin/ban (:account op) who why)
    (operation.util/set-user-mode! channel "-q+b" who who)))

(defmethod process! "unban"
  [op]
  (let [; TODO: Validate
        [who & why] (:args op)
        channel (state/target-channel-for-channel (.getName (.getChannel (:event op))))]
    (track-operation! channel :admin/unban (:account op) who why)
    (operation.util/set-user-mode! channel "-b" who)))

(defmethod process! "kickban"
  [op]
  (let [; TODO: Validate this input.
        [who] (:args op)
        channel (.getName (.getChannel (:event op)))]
    (process! (assoc op :command "ban"))
    (operation.util/kick! channel who)))

(defmethod process! "kick"
  [op]
  (let [; TODO: Validate this input.
        [who & why] (:args op)
        channel (.getName (.getChannel (:event op)))]
    (track-operation! channel :admin/kick (:account op) who why)
    (operation.util/kick! channel who)))

(defmethod process! "history"
  [op]
  (let [[who] (:args op)
        who (clojure.string/lower-case who)
        [hostname-ref hostname] (track.core/resolve-hostname! who)
        channel (state/target-channel-for-channel (.getName (.getChannel (:event op))))
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
    (.respondWith (:event op)
                  (str (if (empty? actions)
                         "No admin operation history for "
                         "Admin operation history for ")
                       who
                       (when-not (or (= who hostname) (track.core/hostmask? who))
                         (str " (latest hostname " hostname ")"))))
    (doseq [r response]
      (.respondWith (:event op) r))
    (when (some? footer)
      (.respondWith (:event op) footer))))

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
    (.respondWith (:event op)
                  (str (if (empty? associations)
                         "No tracking history for "
                         "Recent nicks and accounts on current hostname ")
                       who
                       (when (and (not (empty? associations))
                                  (not (or (= who hostname) (track.core/hostmask? who))))
                         (str " (hostname " hostname ")"))))
    (doseq [r response]
      (.respondWith (:event op) r))
    (when (some? footer)
      (.respondWith (:event op) footer))))

(defmethod process! "deepwhois"
  [op]
  (let [[who] (:args op)
        _ (.respondWith (:event op) (str "Running deep whois on " who ". This can take a while..."))
        results (track.core/deep-whois! who)
        now (System/currentTimeMillis)
        response (->> results
                      (map (fn [association]
                             (str "|" (:hostname association)
                                  "|" (:nick association)
                                  "|" (:account association)
                                  "|" (util/relative-time-offset now (:first_seen association))
                                  "|" (util/relative-time-offset now (:last_seen association))
                                  "|"))))
        markdown (str "|Hostname|Nick|Account|First Time|Last Time|\n"
                      "|---|---|---|---|---|\n"
                      (clojure.string/join "\n" response))
        http-opts {:throw-exceptions false
                   :ignore-unknown-host? true
                   :max-redirects 5
                   :redirect-strategy :graceful
                   :socket-timeout 20000
                   :connection-timeout 2000
                   :accept :json
                   :headers {"X-GitHub-Api-Version" "2022-11-28"
                             "Authorization" (str "Bearer " (:gitlab-token @state/global-config))}
                   :body (json/write-str {:public false
                                          :description (str "!deepwhois " who)
                                          :files {"deepwhois.md" {:content markdown}}})}
        gist-result (http/post "https://api.github.com/gists" http-opts)
        error? (not= 201 (:status gist-result))]
    (if error?
      (.respondWith (:event op) (str "Unable to upload gist: " (:body gist-result)))
      (let [body (json/read-str (:body gist-result))]
        (.respondWith (:event op) (str "Results for deep whois on " who ": "
                                       (get body "html_url")))))))

(defmethod process! :default
  [_op]
  :not-handled)
