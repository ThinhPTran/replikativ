(ns replikativ.crdt.cdvcs.impl
  "Implementation of the CRDT replication protocol."
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id]]
            [replikativ.protocols :refer [PHasIdentities -identities -select-identities
                                          POpBasedCRDT -apply-downstream! -downstream
                                          PExternalValues -missing-commits -commit-value
                                          PPullOp -pull]]
            [replikativ.platform-log :refer [debug info error]]
            #?(:clj [full.async :refer [go-try go-loop-try go-for <?]])
            [replikativ.crdt.cdvcs.repo :as repo]
            [replikativ.crdt.cdvcs.meta :refer [downstream isolate-branch]]
            [konserve.core :as k]
            #?(:clj [clojure.core.async :as async
                    :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [go-try go-loop-try go-for <?]])))


;; fetching related ops
(defn- all-commits
  [commit-graph]
  (set (keys commit-graph)))

(defn- missing-commits [store op graph]
  (let [missing (set/difference (all-commits (:commit-graph op))
                                (all-commits graph))]
    ;; TODO why does not throw?
    (->> (go-for [m missing
                  :when (not (<? (k/exists? store m)))]
                 m)
         (async/into #{}))))


;; pull-hook
(defn inducing-conflict-pull!? [atomic-pull-store [user cdvcs branch] pulled-op b-cdvcs]
  (go-try
   (let [[old new] (<? (k/update-in atomic-pull-store [user cdvcs]
                                    ;; ensure updates inside atomic swap
                                    #(cond (not %) b-cdvcs
                                           (repo/multiple-branch-heads?
                                            (-downstream % pulled-op) branch) %
                                           :else (-downstream % pulled-op))))]
     ;; not perfectly elegant to reconstruct the value of inside the transaction, but safe
     (when (= old new) (not= (-downstream old pulled-op) new)))))


(defn pull-cdvcs!
  [store atomic-pull-store
   [[a-user _ a-branch a-cdvcs]
    [b-user b-cdvcs-id b-branch b-cdvcs]
    integrity-fn
    allow-induced-conflict?]]
  (go-try
   (let [conflicts (get-in a-cdvcs [:branches a-branch])
         [head-a head-b] (seq conflicts)]
     (if head-b
       (do (debug "Cannot pull from conflicting CRDT: " (dissoc a-cdvcs :store) a-branch ": " conflicts)
           :rejected)
       (let [pulled (try
                      (repo/pull {:state b-cdvcs} b-branch a-cdvcs head-a allow-induced-conflict? false)
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                        (let [{:keys [type]} (ex-data e)]
                          (if (or (= type :multiple-branch-heads)
                                  (= type :not-superset)
                                  (= type :conflicting-meta)
                                  (= type :pull-unnecessary))
                            (do (debug e) :rejected)
                            (do (debug e) (throw e))))))
             new-commits (set/difference (-> pulled :state :commit-graph keys set)
                                         (-> b-cdvcs :commit-graph keys set))]
         (cond (= pulled :rejected)
               :rejected

               (and (not allow-induced-conflict?)
                    (<? (inducing-conflict-pull!? atomic-pull-store
                                                  [b-user b-cdvcs-id b-branch]
                                                  (:downstream pulled)
                                                  b-cdvcs)))
               (do
                 (debug "Pull would induce conflict: " b-user b-cdvcs-id (:state pulled))
                 :rejected)

               (<? (integrity-fn store new-commits))
               [[b-user b-cdvcs-id] (:downstream pulled)]

               :else
               (do
                 (debug "Integrity check on " new-commits " pulled from " a-user a-cdvcs " failed.")
                 :rejected)))))))


(defn- optimize [store cursor state]
  (go-try (when (>= (count (:commit-graph state)) 100)
            (let [{cg :commit-graph hist-id :history} state
                  id (*id-fn* cg)
                  new-hist (conj (or (<? (k/get-in store [hist-id])) []) id)
                  new-hist-id (*id-fn* new-hist)]
              (debug "Serializing partial commit graph as" id)
              (<? (k/assoc-in store [id] cg))
              (<? (k/assoc-in store [new-hist-id] new-hist))
              ;; TODO avoid (uncritical) double additions
              (<? (k/update-in store cursor #(let [curr-cg (:commit-graph %)
                                                  diff (set/difference (set (keys curr-cg)) (set (keys cg)))]
                                              (assoc % :commit-graph (select-keys curr-cg diff)
                                                     :history new-hist-id))))))))

(extend-type replikativ.crdt.CDVCS
  PHasIdentities
  (-identities [this] (set (keys (:branches this))))
  (-select-identities [this branches op]
    (let [branches-graph (apply set/union
                                (map (comp set keys (partial isolate-branch op))
                                     branches))]
      (-> op
          (update-in [:commit-graph] select-keys branches-graph)
          (update-in [:branches] select-keys branches))))

  POpBasedCRDT
  (-downstream [this op] (downstream this op))
  (-apply-downstream! [this op]
    ;; just return, do not update state root itself, but allow to do this in a transaction over multiple CRDTs
    (go-try #_(let [[old new] (<? (k/update-in (:store this) (:cursor this) #(dissoc (downstream % op) :store)))]
                #_(<? (optimize (:store this) (:cursor this) new))
                ;; return unoptimized to allow equality reasoning
                [old new])
            (dissoc (downstream this op) :store :cursor)))

  PExternalValues
  (-missing-commits
    ([this out fetched-ch]
     (missing-commits (:store this) this nil))
    ([this out fetched-ch op] (missing-commits (:store this) this op)))
  (-commit-value [this commit]
    (select-keys commit #{:transactions :parents}))

  PPullOp
  (-pull [this atomic-pull-store hooks]
    (pull-cdvcs! (:store this) atomic-pull-store hooks)))




(comment
  (require '[replikativ.crdt.materialize :refer [pub->crdt]]
           '[konserve.memory :refer [new-mem-store]])

  (<!! (pub->crdt (<!! (new-mem-store)) ["a" 1] :repo))

  (<!! (-downstream (<!! (pub->crdt (<!! (new-mem-store)) ["a" 1] :repo)) {:method :foo
                                                                           :commit-graph {1 []
                                                                                          2 [1]}
                                                                           :branches {"master" #{2}}}))


  )
