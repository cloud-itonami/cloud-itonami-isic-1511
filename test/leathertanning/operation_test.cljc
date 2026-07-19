(ns leathertanning.operation-test
  "Smoke tests for the compiled LeatherTanningOperationActor graph
  itself (build + one happy path per op). The governor's full rule
  contract (HARD holds, escalation, phase gating) is exercised in
  `leathertanning.governor-contract-test`; the Store contract in
  `leathertanning.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [leathertanning.operation :as op]
            [leathertanning.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :tannery-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "LeatherTanningOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-production-batch-logging-proposal
  (testing "Proposing a production-batch log auto-commits when clean (phase 3, no physical/environmental risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-production-batch :effect :propose :subject "batch-001"
                           :patch {:grade :full-grain}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-maintenance-scheduling
  (testing "Maintenance scheduling always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                           :value {:equipment-id "equip-001" :maintenance-type :tanning-drum-inspection
                                   :scheduled-date "2026-08-01" :discharge-authorize? false}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-safety-concern-escalation
  (testing "Safety concerns always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :flag-safety-concern :effect :propose :subject "concern-1"
                           :value {:equipment-id "equip-001" :severity :moderate :description "chromium VI odor near tanning drum"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-shipment-coordination-proposal
  (testing "Shipment coordination proposal is submitted and (when within area) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :coordinate-shipment :effect :propose :subject "ship-1"
                           :value {:batch-id "batch-001" :area-sqm 50.0
                                   :destination "buyer-tannery-north"}}
                          coordinator)]
      (is (some? result))
      (is (= :interrupted (:status result))))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "The domain-agnostic commit-record! path stores a raw record by :id"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest test-commit-node-writes-real-store-state-not-just-the-ledger
  (testing ":commit really calls store/commit-record! against the SSoT
  read side (batch fields / draft-record histories), not merely the
  audit ledger -- regression guard for the isic-4322-class bug where a
  graph's :commit node claimed (in a comment) to be 'the ONLY node
  that writes the SSoT' but never actually called the real store-write
  fn, so no operation's effect ever reached the real store through any
  real graph run and only the audit ledger looked populated. Drives
  the REAL wired StateGraph via `g/run*` (through exec-op/approve!,
  not the governor/phase fns in isolation) on both the auto-commit
  path and the escalate->approve->commit path."
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          before-grade (:grade (store/batch s "batch-001"))
          before-shipped (:shipped-area-sqm (store/batch s "batch-001"))]
      ;; auto-commit path (phase-3 :auto op, no human involved)
      (exec-op actor "tA"
               {:op :log-production-batch :effect :propose :subject "batch-001"
                :patch {:grade :top-grain}}
               coordinator)
      (is (not= before-grade (:grade (store/batch s "batch-001")))
          "batch-001's own :grade field must actually change in the store, not just appear in the ledger")
      (is (= :top-grain (:grade (store/batch s "batch-001"))))
      ;; escalate -> approve -> commit path
      (exec-op actor "tB"
               {:op :coordinate-shipment :effect :propose :subject "ship-regress-1"
                :value {:batch-id "batch-001" :area-sqm 25.0 :destination "buyer-regress"}}
               coordinator)
      (approve! actor "tB")
      (is (= (+ before-shipped 25.0) (:shipped-area-sqm (store/batch s "batch-001")))
          "batch-001's own :shipped-area-sqm must be independently bumped by the real commit-record! effect")
      (is (some #(= "ship-regress-1" (get % "shipment_id")) (store/shipment-history s))
          "a real SHP-... draft record must land in shipment-history, not just an audit-ledger entry"))))
