(ns goldrausch.core
  (:gen-class :main true)
  (:require [goldrausch.twitter :refer [new-twitter-collector get-all-tweets]]
            #_[goldrausch.okcoin :refer [new-okcoin-collector]]
            [goldrausch.okcoin-rest :refer [new-okcoin-rest-collector]]
            [goldrausch.bitfinex :refer [new-bitfinex-collector]]
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

   :twitter-collector
   (component/using
    (new-twitter-collector (config :twitter))
    {:db :db})

   :okcoin-rest-collector
   (component/using
    (new-okcoin-rest-collector (config :okcoin))
    {:db :db})

   :bitfinex-collector
   (component/using
    (new-bitfinex-collector (config :bitfinex))
    {:db :db})))

(defn -main [config-filename & args]
  (-> (slurp config-filename)
      read-string
      prod-system
      component/start))
