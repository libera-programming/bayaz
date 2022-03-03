(ns irc.libera.chat.bayaz.track.core-test
  (:require [clojure.test :as t]
            [clojure.core.async :as async]
            [clojure.pprint]
            [datalevin.core :as datalevin]
            [irc.libera.chat.bayaz.db.core :as db.core]
            [irc.libera.chat.bayaz.track.core :as track.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util])
  (:import [org.pircbotx PircBotX Configuration$Builder User]
           [org.pircbotx.delay StaticReadonlyDelay]
           [org.pircbotx.hooks.managers GenericListenerManager]
           [org.pircbotx.hooks.events WhoisEvent]))

(t/use-fixtures :each (fn [f]
                        (track.core/start-listener!)
                        (f)))

(defn generate-bot! []
    (-> (Configuration$Builder.)
        (.addServer "127.1.1.1")
        (.setListenerManager (GenericListenerManager.))
        (.setName "TestBot")
        (.setMessageDelay (StaticReadonlyDelay. 0))
        (.setShutdownHookEnabled false)
        (.setAutoReconnect false)
        (.setCapEnabled false)
        .buildConfiguration
        PircBotX.))

(defn generate-user! [^PircBotX bot hostmask]
  (-> bot
      .getConfiguration
      .getBotFactory
      (.createUserHostmask bot hostmask)))

(def nick "test-user-nick")
(def account "test-user-account")
(def hostname "test-user-hostname")
(def hostmask (str nick "!" nick "@" hostname))
(def other-nick "other-test-user-nick")
(def other-account "other-test-user-account")
(def other-hostmask (str other-nick "!" other-nick "@" hostname))
(def initial-timestamp 7777)
(def updated-timestamp 3229832)

(t/deftest track-user!
  (let [^PircBotX bot (generate-bot!)]
    (with-redefs [db.core/connection (-> (datalevin/empty-db nil db.core/schema)
                                         (datalevin/db-with db.core/txs)
                                         datalevin/conn-from-db
                                         delay)
                  track.core/track-user-delay-ms 0
                  operation.util/whois! (fn [^User user]
                                          (async/go
                                            (-> (WhoisEvent/builder)
                                                (.nick (.getNick user))
                                                (.hostname (.getHostname user))
                                                (.registeredAs account)
                                                (.generateEvent bot))))]
      (t/testing "Initial entry"
        (t/is (empty? (db.core/query! '[:find ?e
                                        :where
                                        [?e :user/hostname]])))
        (async/<!! (track.core/track-user! (generate-user! bot hostmask) initial-timestamp))
        (t/is (= 1 (count (db.core/query! '[:find ?h
                                            :in $ ?hostname ?nick ?account
                                            :where
                                            [?h :user/hostname ?hostname]
                                            [?n :user/nick-association ?nick]
                                            [?n :user/hostname-ref ?h]
                                            [?a :user/account-association ?account]
                                            [?a :user/hostname-ref ?h]]
                                          hostname
                                          nick
                                          account)))))

      (t/testing "Upsert entry"
        (async/<!! (track.core/track-user! (generate-user! bot hostmask) updated-timestamp))
        (let [q '[:find ?h
                  :in $ ?hostname ?nick ?account ?timestamp
                  :where
                  [?h :user/hostname ?hostname]
                  [?n :user/nick-association ?nick]
                  [?n :user/hostname-ref ?h]
                  [?n :time/when ?timestamp]
                  [?a :user/account-association ?account]
                  [?a :user/hostname-ref ?h]
                  [?a :time/when ?timestamp]]]
          (t/is (empty? (db.core/query! q hostname nick account initial-timestamp)))
          (t/is (= 1 (count (db.core/query! q hostname nick account updated-timestamp))))))

      (t/testing "Unique hostname"
        (let [q '[:find (pull ?h [*])
                  :in $ ?hostname
                  :where
                  [?h :user/hostname ?hostname]]]
          (t/is (= 1 (count (db.core/query! q hostname))))))

      (t/testing "Multiple nick associations"
        (async/<!! (track.core/track-user! (generate-user! bot other-hostmask) updated-timestamp))
        (let [other-q '[:find ?h
                        :in $ ?hostname ?nick ?account ?timestamp
                        :where
                        [?h :user/hostname ?hostname]
                        [?n :user/nick-association ?nick]
                        [?n :user/hostname-ref ?h]
                        [?n :time/when ?timestamp]
                        [?a :user/account-association ?account]
                        [?a :user/hostname-ref ?h]
                        [?a :time/when ?timestamp]]
              nick-q '[:find ?n
                       :in $ ?hostname
                       :where
                       [?h :user/hostname ?hostname]
                       [?n :user/nick-association]
                       [?n :user/hostname-ref ?h]]]
          (t/is (= 1 (count (db.core/query! other-q hostname other-nick account updated-timestamp))))
          (t/is (= 2 (count (db.core/query! nick-q hostname))))))

      (t/testing "Multiple account associations"
        (with-redefs [account other-account]
          (async/<!! (track.core/track-user! (generate-user! bot hostmask) updated-timestamp)))
        (let [other-q '[:find ?h
                        :in $ ?hostname ?nick ?account ?timestamp
                        :where
                        [?h :user/hostname ?hostname]
                        [?n :user/nick-association ?nick]
                        [?n :user/hostname-ref ?h]
                        [?n :time/when ?timestamp]
                        [?a :user/account-association ?account]
                        [?a :user/hostname-ref ?h]
                        [?a :time/when ?timestamp]]
              account-q '[:find ?n
                          :in $ ?hostname
                          :where
                          [?h :user/hostname ?hostname]
                          [?n :user/account-association]
                          [?n :user/hostname-ref ?h]]]
          (t/is (= 1 (count (db.core/query! other-q hostname nick other-account updated-timestamp))))
          (t/is (= 2 (count (db.core/query! account-q hostname)))))))))
