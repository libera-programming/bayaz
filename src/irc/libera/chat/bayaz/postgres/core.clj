(ns irc.libera.chat.bayaz.postgres.core
  (:require [pg.pool :as pgp]
            [pg.honey :as pgh]
            [pg.migration.core :as pgm]
            [environ.core :refer [env]]))

(def config {:host "127.0.0.1"
             :port 5432
             :user (:user env)
             :password (:bayaz-db-pass env "")
             :database "bayaz"
             :migrations-table :migrations
             :migrations-path "migrations"})

(defonce pool (atom nil))

(defn disconnect! []
  (when-some [p @pool]
    (pgp/close p))
  (reset! pool nil))

(defn connect! []
  (disconnect!)
  (reset! pool (pgp/pool config))
  (pgm/migrate-all config))

(defn query! [honey]
  (pgp/with-connection [conn @pool]
    (pgh/query conn honey)))

(defn execute! [honey]
  (pgp/with-connection [conn @pool]
    (pgh/execute conn honey)))
