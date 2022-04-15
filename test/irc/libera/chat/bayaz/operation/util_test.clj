(ns irc.libera.chat.bayaz.operation.util-test
  (:require [clojure.test :as t]
            [datalevin.core :as datalevin]
            [irc.libera.chat.bayaz.db.core :as db.core]
            [irc.libera.chat.bayaz.track.core :as track.core]
            [irc.libera.chat.bayaz.operation.util :as operation.util]))

(def nick "user")
(def hostname "host")
(def account "account")
(def user {:nick nick
           :hostname hostname
           :account account})
(def other-account "other-account")
(def other-user {:nick nick
                 :hostname hostname
                 :account other-account})
(def guest-nick "guest")
(def guest-hostname "guest-host")
(def guest-user {:nick guest-nick
                 :hostname guest-hostname})

(t/deftest resolve-account!
  (with-redefs [db.core/connection (-> (datalevin/empty-db nil db.core/schema)
                                       (datalevin/db-with db.core/txs)
                                       datalevin/conn-from-db
                                       delay)]
    (t/testing "Pass through when not found"
      (t/is (= nick (operation.util/resolve-account! nick))))

    (t/testing "Use account when found"
      (track.core/track-user! user 0)
      (t/is (= (str "$a:" account) (operation.util/resolve-account! nick))))

    (t/testing "Use latest account"
      (track.core/track-user! other-user 1)
      (t/is (= (str "$a:" other-account) (operation.util/resolve-account! nick)))
      (track.core/track-user! user 2)
      (t/is (= (str "$a:" account) (operation.util/resolve-account! nick))))

    (t/testing "Use hostname when there is no account association"
      (track.core/track-user! guest-user 0)
      (t/is (= guest-hostname (operation.util/resolve-account! guest-nick))))

    (t/testing "Use latest hostname"
      (track.core/track-user! (assoc guest-user :hostname "other-guest-host") 1)
      (t/is (= "other-guest-host" (operation.util/resolve-account! guest-nick))))))
