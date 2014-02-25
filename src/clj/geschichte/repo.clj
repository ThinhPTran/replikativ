(ns ^:shared geschichte.repo
  "Implementing core repository functions.
   Use this namespace to manage your repositories.

   Metadata is designed as a commutative replicative data type, so it
   can be synched between different servers without coordination. Don't
   add fields as this is part of the network specification."
  (:refer-clojure :exclude [merge])
  (:require [clojure.set :as set]
            [geschichte.platform :refer [uuid now]]
            [geschichte.protocols :refer [-coerce]]
            [geschichte.meta :refer [lowest-common-ancestors
                                     merge-ancestors inline-meta
                                     isolate-branch]]))


(def ^:dynamic *id-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA.
   Determines unique ids, possibly from a value.
   UUID is defined as public format."
  uuid)



(def ^:dynamic *date-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA."
  now)


(defn new-repository
  "Create a (unique) repository for an initial value. Returns a map with
   new metadata and value + inline metadata. You can add fields to
   *inline* metadata as long as you keep them namespaced with globally
   unique names."
  [author schema description is-public init-value]
  (let [trans-val {:transactions [[init-value
                                   '(fn replace [old params] params)]]
                   :parents #{}
                   :author author
                   :schema schema}
        trans-id (*id-fn* trans-val)
        ts (*date-fn*)
        repo-id (*id-fn*)
        new-meta  {:id repo-id
                   :description description
                   :schema {:type "http://github.com/ghubber/geschichte"
                            :version 1}
                   :public is-public
                   :causal-order {trans-id #{}}
                   :branches {"master" #{trans-id}}
                   :head "master"
                   :last-update ts
                   :pull-requests {}}]
    {:meta new-meta
     :author author
     :schema schema
     :transactions []

     :type :new-meta
     :new-values {trans-id trans-val}}))


(defn clone
  "Clone a remote branch as your working copy.
   Pull in more branches as needed separately."
  [remote-meta branch is-public author schema]
  (let [heads ((:branches remote-meta) branch)
        meta {:id (:id remote-meta)
              :description (:description remote-meta)
              :schema (:schema remote-meta)
              :causal-order (isolate-branch remote-meta branch)
              :branches {branch heads}
              :head branch
              :last-update (*date-fn*)
              :pull-requests {}}]
    {:meta meta
     :author author
     :schema schema
     :transactions []

     :type :new-meta}))


(defn- branch-heads [{:keys [head branches]}]
  (get branches head))


(defn- raw-commit
  "Commits to meta in branch with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [{:keys [meta author schema transactions] :as stage} parents]
  (let [branch (:head meta)
        branch-heads (branch-heads meta)
        trans-value {:transactions transactions
                     :parents parents
                     :author author
                     :schema schema}
        id (*id-fn* trans-value)
        ts (*date-fn*)
        new-meta (-> meta
                     (assoc-in [:causal-order id] parents)
                     (update-in [:branches branch] set/difference parents)
                     (update-in [:branches branch] conj id)
                     (assoc-in [:last-update] ts))]
    (assoc stage
      :meta new-meta
      :transactions []

      :type :meta-up
      :new-values {id trans-value})))

(defn commit
  "Commits to meta in branch with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [stage]
  (let [heads (branch-heads (:meta stage))]
    (if (= (count heads) 1)
      (raw-commit stage (set heads))
      {:error "Branch has multiple heads."})))


(defn branch
  "Create a new branch with parent."
  [{:keys [meta] :as stage} name parent]
  (let [new-meta (-> meta
                     (assoc-in [:branches name] #{parent})
                     (assoc-in [:last-update] (*date-fn*)))]

    (assoc stage
      :meta new-meta
      :type :meta-up)))


(defn checkout
  "Checkout a branch."
  [{:keys [meta] :as stage} branch]
  (let [new-meta (assoc (:meta stage)
                   :head branch
                   :last-update (*date-fn*))]
    (assoc stage
      :meta new-meta
      :type :meta-up)))


(defn- multiple-branch-heads?
  "Checks whether branch has multiple heads."
  [meta branch]
  (> (count ((:branches meta) branch)) 1))


(defn- merge-necessary?
  "Determines whether branch-head is ancestor."
  [cut branch-head]
  (not (cut branch-head)))




;; TODO error handling for conflicts
(defn pull
  "Pull all commits into branch from remote-tip (only its ancestors)."
  [{:keys [meta] :as stage} remote-meta remote-tip]
  (let [branch-heads (branch-heads meta)
        branch (:head meta)
        {:keys [cut returnpaths-b]} (lowest-common-ancestors (:causal-order meta) branch-heads
                                                             (:causal-order remote-meta) #{remote-tip})
        new-meta (-> meta
                     (update-in [:causal-order]
                                merge-ancestors cut returnpaths-b)
                     (update-in [:branches branch] set/difference branch-heads)
                     (update-in [:branches branch] conj remote-tip))]
    (assoc stage
      :meta new-meta
      :type :meta-up)))


(defn merge
  "Merge source and target heads into source branch with value as commit."
  [{:keys [meta] :as stage} target-meta target-heads]
  (let [source-heads (branch-heads meta)
        lcas (lowest-common-ancestors (:causal-order meta)
                                      source-heads
                                      (:causal-order target-meta)
                                      target-heads)
        new-causal (merge-ancestors (:causal-order meta) (:cut lcas) (:returnpaths-b lcas))]
    (raw-commit (assoc-in stage [:meta :causal-order] new-causal)
                (set/union source-heads target-heads))))
