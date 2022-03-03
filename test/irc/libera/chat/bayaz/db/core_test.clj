(ns irc.libera.chat.bayaz.db.core-test
  (:require [clojure.test :as t]
            [datalevin.core :as datalevin]
            [irc.libera.chat.bayaz.db.core :as db.core]))

(t/deftest read+write
  (with-redefs [db.core/connection (-> (datalevin/db-with (datalevin/empty-db) db.core/txs)
                                       datalevin/conn-from-db
                                       delay)]
    (t/is (empty? (db.core/query! '[:find ?e
                                    :where
                                    [?e :user/hostname]])))
    (db.core/transact! [{:db/id -1
                         :user/hostname "meow"}])
    (t/is (= 1 (count (db.core/query! '[:find ?e
                                        :where
                                        [?e :user/hostname "meow"]]))))))
