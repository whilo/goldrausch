(ns goldrausch.okcoin-rest
  (:require [clojure.core.async :as async
             :refer [<!! >!! <! >! timeout chan alt! go go-loop]]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [http.async.client :as cli]
            [taoensso.timbre :as timbre]))


(timbre/refer-timbre)

;; https://www.bitfinex.com/pages/api
;; use separate schema

(def client (cli/create-client))

(def schema [{:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/timestamp
              :db/valueType :db.type/instant
              :db/cardinality :db.cardinality/one
              :db/index true
              :db.install/_attribute :db.part/db}

             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/buy
              :db/valueType :db.type/float
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/sell
              :db/valueType :db.type/float
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/high
              :db/valueType :db.type/float
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/low
              :db/valueType :db.type/float
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/last
              :db/valueType :db.type/float
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/vol
              :db/valueType :db.type/float
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/provider
              :db/valueType :db.type/string
              :db/cardinality :db.cardinality/one
              :db/doc "Unique name of the coin data provider."
              :db/index true
              :db.install/_attribute :db.part/db}

             ;; btcusd60
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/bids
              :db/valueType :db.type/ref
              :db/cardinality :db.cardinality/many
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/asks
              :db/valueType :db.type/ref
              :db/cardinality :db.cardinality/many
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/bid
              :db/valueType :db.type/double
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/ask
              :db/valueType :db.type/double
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}
             {:db/id #db/id[:db.part/db]
              :db/ident :okcoin-btcusd/depth
              :db/valueType :db.type/double
              :db/cardinality :db.cardinality/one
;              :db/index true
              :db.install/_attribute :db.part/db}])




(defn btcusd-tick->trans []
  (let [{{buy "buy" sell "sell" high "high" low "low" last "last" vol "vol"} "ticker"
         timestamp "date"}
        (-> (cli/GET client "https://www.okcoin.com/api/v1/ticker.do?symbol=btc_usd")
            cli/await
            cli/string
            json/read-str)
        id (d/tempid :db.part/user)]
    [[:db/add id :okcoin-btcusd/buy (Float/parseFloat buy)]
     [:db/add id :okcoin-btcusd/sell (Float/parseFloat sell)]
     [:db/add id :okcoin-btcusd/high (Float/parseFloat high)]
     [:db/add id :okcoin-btcusd/low (Float/parseFloat low)]
     [:db/add id :okcoin-btcusd/last (Float/parseFloat last)]
     [:db/add id :okcoin-btcusd/vol (Float/parseFloat (str/replace vol #"," ""))]
     [:db/add id :okcoin-btcusd/timestamp (java.util.Date. (* (Long/parseLong timestamp) 1000))]]))


(defn btcusd-depth60->trans []
  (let [{bids "bids" asks "asks"}
        (-> (cli/GET client "https://www.okcoin.com/api/v1/depth.do")
            cli/await
            cli/string
            json/read-str)
        id (d/tempid :db.part/user)]
    (vec (concat (mapcat (fn [[bid depth]]
                           (let [bid-id (d/tempid :db.part/user)]
                             [[:db/add id :okcoin-btcusd/bids bid-id]
                              [:db/add bid-id :okcoin-btcusd/bid (double bid)]
                              [:db/add bid-id :okcoin-btcusd/depth (double depth)]])) bids)
                 (mapcat (fn [[ask depth]]
                           (let [ask-id (d/tempid :db.part/user)]
                             [[:db/add id :okcoin-btcusd/asks ask-id]
                              [:db/add ask-id :okcoin-btcusd/ask (double ask)]
                              [:db/add ask-id :okcoin-btcusd/depth (double depth)]])) asks)
                 [[:db/add id :okcoin-btcusd/timestamp (java.util.Date.)]]))))


(def supported-chans {"ok_btcusd_ticker" btcusd-tick->trans
                      "ok_btcusd_depth60" btcusd-depth60->trans})


(defrecord OkCoinRestCollector [subscribed-chans db init-schema?]
  component/Lifecycle
  (start [component]
    (if (:close-ch component) ;; idempotent
      component
      (let [conn (:conn db)
            close-ch (chan)]
        (when init-schema?
          (debug "initializing schema:" schema)
          (d/transact conn schema))

        (go-loop []
          (debug "polling okcoin")
          (alt! close-ch
                :done
                (timeout 2000)
                (do
                  (doseq [c subscribed-chans]
                    (try
                      (d/transact conn ((supported-chans c)))
                      (catch Exception e
                        (debug "transaction failed: " c (.printStackTrace e)))))
                  (recur))))
        (assoc component :close-ch close-ch))))
  (stop [component]
    (if-not (:close-ch component)
      component
      (do
        (async/close! (:close-ch component))
        (dissoc component :close-ch)))))




(defn new-okcoin-rest-collector [{:keys [subscribed-chans] :as config}]
  (when-not (set/superset? (set (keys supported-chans)) (set subscribed-chans))
    (throw (ex-info "Some channels to subscribe are unknown:"
                    {:supported-chans (set (keys supported-chans))
                     :subscribed-chans subscribed-chans})))
  (map->OkCoinRestCollector config))


(comment
  (def foo (-> (cli/GET client "https://www.okcoin.com/api/v1/ticker.do?symbol=btc_usd
")
               cli/await
               cli/string
               json/read-str)))
