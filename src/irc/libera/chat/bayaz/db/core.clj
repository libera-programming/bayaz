(ns irc.libera.chat.bayaz.db.core
  (:require [datalevin.core :as datalevin]
            [datalevin.entity]
            [environ.core :refer [env]]))

(comment
  (def entities [; User by hostname.
                 {:user/hostname "user/jeaye"}

                 ; User nick and/or account association with hostname. (on join or nick change)
                 {:user/hostname-ref :ref-to-hostname
                  :user/nick-association "jeaye"
                  :user/account-association "jeaye"
                  :time/when 0}

                 ; Admin action to ban/quiet.
                 {:user/hostname-ref :ref-to-hostname
                  :admin/account "inphase"
                  :admin/action :admin/ban
                  :admin/reason "something here"
                  :time/when 0}]))

(def schema {:user/hostname {:db/valueType :db.type/string
                             :db/unique :db.unique/identity}
             :user/hostname-ref {:db/valueType :db.type/ref}
             :user/nick-association {:db/valueType :db.type/string}
             :user/account-association {:db/valueType :db.type/string}

             ; Milliseconds.
             :time/when {:db/valueType :db.type/long}

             :admin/account {:db/valueType :db.type/string}
             :admin/action {:db/valueType :db.type/ref}
             :admin/reason {:db/valueType :db.type/string}})
(def txs [{:db/ident :admin/ban}
          {:db/ident :admin/unban}
          {:db/ident :admin/quiet}
          {:db/ident :admin/unquiet}
          {:db/ident :admin/warn}
          {:db/ident :admin/kick}])

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

(defn entity [id]
  (datalevin.entity/entity (datalevin/db @connection) id))

(comment
  (connect!)

  (do
    @(future (transact! [{:db/id -1
               :user/hostname "user/pyzozord"}])))

  (query! '[:find (pull ?e [*])
            :where
            [?e]])
  (query! '[:find (pull ?e [*])
            :where
            [?e :admin/action]])
  (query! '[:find (pull ?e [*])
            :where
            [?e :user/nick-association "VIle`"]]))
