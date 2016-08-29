(ns replikativ.crdt.materialize
  (:require [konserve.core :as k]
            [replikativ.crdt :refer [map->CDVCS map->SimpleGSet map->SimpleORMap]]
            [replikativ.protocols :refer [-downstream -handshake]]
            ;; loading protocol extensions
            [replikativ.crdt.cdvcs.impl] 
            [replikativ.crdt.simple-gset.impl]
            [replikativ.crdt.simple-ormap.impl]
            #?(:clj [full.async :refer [<? go-try]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! go put! go-loop sub unsub pub close!]]
                    :cljs [cljs.core.async :as async
                           :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.async :refer [<? go-try]])))

;; incognito handlers
(def crdt-read-handlers {'replikativ.crdt.CDVCS map->CDVCS
                         'replikativ.crdt.SimpleGSet map->SimpleGSet
                         'replikativ.crdt.SimpleORMap map->SimpleORMap})

(def crdt-write-handlers {})


(defmulti key->crdt "This is needed to instantiate records of the CRDT
  type where their protocols are needed. This is somewhat redundant,
  but this multimethod is only here to allow the definition of such
  constructors for empty (bootstrapped) CRDTs externally." identity)

(defmethod key->crdt :cdvcs
  [_]
  (map->CDVCS {:version 1}))

(defmethod key->crdt :simple-gset
  [_]
  (map->SimpleGSet {:version 1}))

(defmethod key->crdt :simple-ormap
  [_]
  (map->SimpleORMap {:version 1}))

(defmethod key->crdt :default
  [crdt-type]
  (throw (ex-info "Cannot materialize CRDT for publication."
                  {:crdt-type crdt-type})))


;; TODO refactor and move
(defn get-crdt [cold-store mem-store [user crdt-id]]
  (go-try
   (let [mem-val (<? (k/get-in mem-store [[user crdt-id]]))]
     (if mem-val mem-val
         (let [log-id (second (<? (k/get-in cold-store [[user crdt-id :log]])))
               new-id (hasch.core/uuid)
               ;; last log entry
               {{:keys [crdt]} :elem
                prev :prev}
               (<? (k/get-in cold-store [log-id]))]
           (when crdt
             (let [cold-val (<? (k/reduce-log cold-store
                                              [user crdt-id :log]
                                              (fn [acc pub]
                                                (-downstream acc (:op pub)))
                                              (key->crdt crdt)))
                   new-val (second (<? (k/update-in mem-store [[user crdt-id] :state]
                                                    (fn [old]
                                                      (if old old ;; only update if we don't have it in memory yet
                                                          cold-val)))))]
               ;; replace log by most recent state value
               #_(<? (k/assoc-in cold-store
                               [new-id]
                               {:elem {:crdt crdt
                                       :method :handshake
                                       :op (-handshake new-val)}
                                :next nil}))
               ;; prune crdt log with state, (can be racing)
               #_(<? (k/assoc-in cold-store [[user crdt-id :log]] [:append-log new-id new-id]))
               {:crdt crdt
                :state new-val})))))))

(defn ensure-crdt [cold-store store [user crdt-id] pub]
  (go-try (if-let [s (:state (<? (get-crdt cold-store store [[user crdt-id]])))]
            s
            (key->crdt (:crdt pub)))))
