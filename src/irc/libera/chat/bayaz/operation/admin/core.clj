(ns irc.libera.chat.bayaz.operation.admin.core
  (:require [clojure.string :as string]
            [clojure.data.json :as json]
            [medley.core :refer [distinct-by]]
            [clj-http.client :as http]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.db.core :as db.core]
            [irc.libera.chat.bayaz.util :as util]
            [irc.libera.chat.bayaz.operation.util :as operation.util]
            [irc.libera.chat.bayaz.track.core :as track.core])
  (:import [org.pircbotx PircBotX UserChannelDao]))

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
  (let [[who & why] (:args op)
        ^UserChannelDao user-channel-dao (.getUserChannelDao ^PircBotX @state/bot)]
    (if-not (.containsUser user-channel-dao who)
      (.respond (:event op) "Warn syntax is: !w <nick> [reason]")
      (do
        (track-operation! :admin/warn (:account op) who why)
        (operation.util/message! (str "This is a warning, " who ". " (when-not (empty? why)
                                                                       (string/join " " why))))))))

(defmethod process! "warnall"
  [op]
  (let [[& why] (:args op)]
    (operation.util/message! (str "Everyone, this is a warning. " (when-not (empty? why)
                                                                    (string/join " " why))))))

(defmethod process! "quiet"
  [op]
  (let [[who & why] (:args op)]
    (track-operation! :admin/quiet (:account op) who why)
    (operation.util/set-user-mode! "+q" who)))

(defmethod process! "unquiet"
  [op]
  (let [; TODO: Validate
        [who & why] (:args op)]
    (track-operation! :admin/unquiet (:account op) who why)
    (operation.util/set-user-mode! "-q" who)))

(defmethod process! "ban"
  [op]
  (let [; TODO: Validate this input.
        [who & why] (:args op)]
    (track-operation! :admin/ban (:account op) who why)
    (operation.util/set-user-mode! "-q+b" who who)))

(defmethod process! "unban"
  [op]
  (let [; TODO: Validate
        [who & why] (:args op)]
    (track-operation! :admin/unban (:account op) who why)
    (operation.util/set-user-mode! "-b" who)))

(defmethod process! "kickban"
  [op]
  (let [; TODO: Validate this input.
        [who] (:args op)]
    (process! (assoc op :command "ban"))
    (operation.util/kick! who)))

(defmethod process! "kick"
  [op]
  (let [; TODO: Validate this input.
        [who & why] (:args op)]
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
                  (str (if (empty? actions)
                         "No admin operation history for "
                         "Admin operation history for ")
                       who
                       (when-not (or (= who hostname) (operation.util/hostmask? who))
                         (str " (latest hostname " hostname ")"))))
    (doseq [r response]
      (.respondWith (:event op) r))
    (when (some? footer)
      (.respondWith (:event op) footer))))

(defmethod process! "whois"
  [op]
  (let [[who] (:args op)
        who (clojure.string/lower-case who)
        [hostname-ref hostname] (operation.util/resolve-hostname! who)
        ; TODO: Optimize by limiting query; couldn't make it work with datalevin.
        nicks (db.core/query! '[:find [(pull ?a [*]) ...]
                                :in $ ?hostname-ref
                                :where
                                [?a :user/hostname-ref ?hostname-ref]
                                [?a :user/nick-association ?nick]]
                              hostname-ref)
        accounts (db.core/query! '[:find [(pull ?a [*]) ...]
                                   :in $ ?hostname-ref
                                   :where
                                   [?a :user/hostname-ref ?hostname-ref]
                                   [?a :user/account-association ?account]]
                                 hostname-ref)
        combined (track.core/collapse-whois-results (lazy-cat nicks accounts))
        _ (clojure.pprint/pprint combined)
        actions (sort-by :time/when #(compare %2 %1) combined)
        now (System/currentTimeMillis)
        response (->> actions ;(take max-history-lines actions)
                      (map (fn [action]
                             ; 3d ago - Last use nick foo with account bar
                             (str (util/relative-time-offset now (:time/when action))
                                  " - Last used "
                                  (if-some [nick (:user/nick-association action)]
                                    (str "nick " nick
                                         (if-some [account (:user/account-association action)]
                                           (str " with account " account)))
                                    (str "account " (:user/account-association action)))))))
        remaining-actions (drop max-history-lines actions)
        footer (when-not (empty? remaining-actions)
                 (str "Not showing "
                      (count remaining-actions)
                      " action(s) going back to "
                      (util/relative-time-offset now (-> remaining-actions last :time/when))
                      "."))]
    (.respondWith (:event op)
                  (str (if (empty? actions)
                         "No tracking history for "
                         "Tracking history for ")
                       who
                       (when-not (or (= who hostname) (operation.util/hostmask? who))
                         (str " (latest hostname " hostname ")"))))
    (doseq [r response]
      (.respondWith (:event op) r))
    (when (some? footer)
      (.respondWith (:event op) footer))))

(defmethod process! "deepwhois"
  [op]
  (let [[who] (:args op)
        _ (.respondWith (:event op) (str "Running deep whois on " who ". This can take a while..."))
        results (->> (track.core/deep-whois! who)
                     track.core/collapse-whois-results
                     (sort-by :time/when #(compare %2 %1)))
        now (System/currentTimeMillis)
        response (->> results
                      (map (fn [action]
                             (str "|" (:user/hostname action)
                                  "|" (:user/nick-association action)
                                  "|" (:user/account-association action)
                                  "|" (util/relative-time-offset now (:time/when action))
                                  "|"))))
        markdown (str "|Hostname|Nick|Account|Time|\n"
                      "|---|---|---|---|\n"
                      (clojure.string/join "\n" response))
        http-opts {:throw-exceptions false
                   :ignore-unknown-host? true
                   :max-redirects 5
                   :redirect-strategy :graceful
                   :socket-timeout 2000
                   :connection-timeout 2000
                   :accept :json
                   :headers {"X-GitHub-Api-Version" "2022-11-28"
                             "Authorization" (str "Bearer " (:gitlab-token @state/global-config))}
                   :body (json/write-str {:public false
                                          :description (str "bayaz !deepwhois " who)
                                          :files {"bayaz-deepwhois.md" {:content markdown}}})}
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
