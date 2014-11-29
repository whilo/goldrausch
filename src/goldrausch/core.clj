(ns goldrausch.core
  (:gen-class :main true)
  (:require [goldrausch.twitter :refer [new-twitter-collector get-all-tweets]]
            [goldrausch.okcoin :refer [new-okcoin-collector]]
            [goldrausch.bitfinex :refer [new-bitfinex-collector]]
            [goldrausch.aggregator :refer [new-trans-aggregator]]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [clojure.java.io :as io]
            [clojure.core.async :refer [go go-loop <! >! <!! >!!]]
            [aprint.core :refer [aprint]]
            [taoensso.timbre :as timbre]
            (system.components [datomic :refer [new-datomic-db]])))

(timbre/set-config! [:appenders :spit :enabled?] true)
#_(timbre/set-config! [:shared-appender-config :spit-filename] "/tmp/goldrausch.log")

(defn prod-system [config]
  (component/system-map
   :db (new-datomic-db (or (get-in config [:datomic :uri])
                           (str "datomic:mem://" (d/squuid))))
   :aggregator
   (component/using
    (new-trans-aggregator (config :trans-aggregator))
    {:db :db})
   :twitter-collector
   (component/using
    (new-twitter-collector (config :twitter))
    {:db :db
     :aggregator :aggregator})
   :okcoin-collector
   (component/using
    (new-okcoin-collector (config :okcoin))
    {:db :db
     :aggregator :aggregator})
   :bitfinex-collector
   (component/using
    (new-bitfinex-collector (config :bitfinex))
    {:db :db
     :aggregator :aggregator})))

(defn -main [config-filename & args]
  (-> (slurp config-filename)
      read-string
      prod-system
      component/start))
