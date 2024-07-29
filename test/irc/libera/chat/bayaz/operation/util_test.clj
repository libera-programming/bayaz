(ns irc.libera.chat.bayaz.operation.util-test
  (:require [clojure.test :as t]
            [clojure.string]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]
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

; TODO
#_(t/deftest resolve-account!
  (with-redefs [db.core/connection (-> (datalevin/empty-db nil db.core/schema)
                                       (datalevin/db-with db.core/txs)
                                       datalevin/conn-from-db
                                       delay)]
    (t/testing "Pass through when not found"
      (t/is (= nick (track.core/resolve-account! nick))))

    (t/testing "Use account when found"
      (track.core/track-user! user 0)
      (t/is (= (str "$a:" account) (track.core/resolve-account! nick))))

    (t/testing "Case insensitive"
      (t/is (= (str "$a:" account) (track.core/resolve-account! (clojure.string/upper-case nick)))))

    (t/testing "Use latest account"
      (track.core/track-user! other-user 1)
      (t/is (= (str "$a:" other-account) (track.core/resolve-account! nick)))
      (track.core/track-user! user 2)
      (t/is (= (str "$a:" account) (track.core/resolve-account! nick))))

    (t/testing "Use hostname when there is no account association"
      (track.core/track-user! guest-user 0)
      (t/is (= guest-hostname (track.core/resolve-account! guest-nick))))

    (t/testing "Use latest hostname"
      (track.core/track-user! (assoc guest-user :hostname "other-guest-host") 1)
      (t/is (= "other-guest-host" (track.core/resolve-account! guest-nick))))))

(t/deftest resolve-hostname!
  (with-redefs [db.core/connection (-> (datalevin/empty-db nil db.core/schema)
                                       (datalevin/db-with db.core/txs)
                                       datalevin/conn-from-db
                                       delay)]
    (track.core/track-user! user 0)
    (t/testing "Hostmask"
      (t/is (match? [int? hostname] (track.core/resolve-hostname! (str "*!*@" hostname)))))

    (t/testing "Hostname"
      (t/is (match? [int? hostname] (track.core/resolve-hostname! hostname))))

    (t/testing "Nick"
      (t/is (match? [int? hostname] (track.core/resolve-hostname! nick))))

    (t/testing "Extended hostmask"
      (t/is (match? [int? hostname] (track.core/resolve-hostname! (str "$a:" account)))))))
