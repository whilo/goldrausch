(ns goldrausch.aggregator
  "Datomic free (h2) has problems with keeping up, try to naively
  aggregate new transaction data to make transaction granularity
  coarser and not insert into the time based index too often."
  (:require [clojure.core.async :as async
             :refer [<!! >!! <! >! timeout chan alt! go go-loop close!]]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [taoensso.timbre :as timbre]))


(timbre/refer-timbre)

(defn prepare-trans! [agg txs]
  (swap! (:cache agg) concat txs))


(defrecord TransactionAggregator [timeout cache db]
  component/Lifecycle
  (start [component]
    (if @cache
      component
      (do
        (reset! cache [])
        (go-loop []
          (let [txs @cache]
            (debug "transacting aggregated txs")
            (try
              ;; TODO [old new] state atom, atm. race condition!
              (reset! cache [])
              (d/transact (:conn db) txs)
              (catch Exception e
                (error "Transactions failed: " e txs))))
          (<! (async/timeout (* timeout 1000)))
          (when @cache
            (recur)))
        component)))
  (stop [component]
    (if-not @cache
      component
      (do
        (reset! cache nil)
        component))))


(defn new-trans-aggregator [{:keys [timeout] :or {timeout 30}}]
  (map->TransactionAggregator {:timeout timeout
                               :cache (atom nil)}))
