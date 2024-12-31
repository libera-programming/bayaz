(ns irc.libera.chat.bayaz.postgres.core
  (:require [clojure.pprint]
            [pg.pool :as pgp]
            [pg.honey :as pgh]
            [pg.migration.core :as pgm]
            [pg.migration.fs]
            [honey.sql :as sql]
            [taoensso.timbre :as timbre]
            [environ.core :refer [env]]
            [irc.libera.chat.bayaz.state :as state]))

(def config (delay {:host "127.0.0.1"
                    :port 5432
                    :user (:postgres-user @state/global-config (:user env))
                    :password (:postgres-pass @state/global-config "")
                    :database "bayaz"
                    :migrations-table :migrations
                    :migrations-path "migrations"}))

(defonce pool (atom nil))

(defn disconnect! []
  (when-some [p @pool]
    (pgp/close p))
  (reset! pool nil))

(defn connect! []
  (disconnect!)
  (reset! pool (pgp/pool @config))

  (let [url (pg.migration.fs/path->url "migrations")]
    (clojure.pprint/pprint (pg.migration.core/url->migrations url)))

  (pgm/migrate-all @config))

(defn query! [honey]
  (timbre/trace :query (sql/format honey {:pretty true}))
  (pgp/with-connection [conn @pool]
    (pgh/query conn honey)))

(defn execute! [honey]
  (timbre/trace :execute (sql/format honey {:pretty true}))
  (pgp/with-connection [conn @pool]
    (pgh/execute conn honey)))
