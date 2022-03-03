(ns irc.libera.chat.bayaz.db.core
  (:require [datalevin.core :as datalevin]
            [environ.core :refer [env]]))

(comment
  (def entities [; User by hostname.
                 {:user/hostname "jeaye!jeaye@pastespace.org"}

                 ; User nick and/or account association with hostname. (on join or nick change)
                 {:user/hostname-ref :ref-to-hostname
                  :user/nick-association "jeaye"
                  :user/account-association "jeaye"
                  :time/when 0}

                 ; Admin action to ban/quiet.
                 {:user/user-hostname-ref :ref-to-hostname
                  :admin/account "inphase"
                  :admin/action :admin/ban ; or :admin/unban or :admin/quiet or :admin/unquiet
                  :admin/reason "something here"
                  :time/when 0}]))

(def schema {:user/hostname {:db/valueType :db.type/string
                             :db/unique :db.unique/identity}
             :user/hostname-ref {:db/valueType :db.type/ref}
             :user/nick-association {:db/valueType :db.type/string}
             :user/account-association {:db/valueType :db.type/string}

             :time/when {:db/valueType :db.type/long}

             :admin/account {:db/valueType :db.type/string}
             :admin/action {:db/valueType :db.type/ref}
             :admin/reason {:db/valueType :db.type/string}})
(def txs [{:db/ident :admin/ban}
          {:db/ident :admin/unban}
          {:db/ident :admin/quiet}
          {:db/ident :admin/unquiet}])

(def connection (atom nil))

(defn connect! []
  (reset! connection (datalevin/get-conn (:bayaz-db env "db") schema))
  (datalevin/transact! @connection txs))

(defn disconnect! []
  (try
    (when-some [conn @connection]
      (datalevin/close conn))
    (catch Exception _
      )
    (finally
      (reset! connection nil))))

(defn transact! [txs]
  (datalevin/transact! @connection txs))

(defn query! [q & inputs]
  (apply datalevin/q q (datalevin/db @connection) inputs))

(def query-first! (comp first query!))

(comment
  (connect!)

  (transact! [{:db/id -1
               :user/hostname "user/jeaye"}])

  (query! '[:find (pull ?e [*])
            :where
            [?e]])
  (query! '[:find (pull ?e [*])
            :where
            [?e :user/hostname "user/jeaye"]])
  (query! '[:find (pull ?e [*])
            :where
            [?e :user/nick-association]]))
