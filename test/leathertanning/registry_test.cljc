(ns leathertanning.registry-test
  (:require [clojure.test :refer [deftest is]]
            [leathertanning.registry :as r]))

;; ----------------------------- equipment-verified? / equipment-registered? / equipment-ready? -----------------------------

(deftest equipment-is-verified-when-flagged
  (is (true? (r/equipment-verified? {:id "e1" :verified? true}))))

(deftest equipment-is-not-verified-when-false-or-missing
  (is (false? (r/equipment-verified? {:id "e1" :verified? false})))
  (is (false? (r/equipment-verified? {:id "e1"}))))

(deftest equipment-is-registered-when-flagged
  (is (true? (r/equipment-registered? {:registered? true}))))

(deftest equipment-is-not-registered-when-false-or-missing
  (is (false? (r/equipment-registered? {:registered? false})))
  (is (false? (r/equipment-registered? {}))))

(deftest equipment-ready-requires-both
  (is (true? (r/equipment-ready? {:verified? true :registered? true})))
  (is (false? (r/equipment-ready? {:verified? true :registered? false})))
  (is (false? (r/equipment-ready? {:verified? false :registered? true})))
  (is (false? (r/equipment-ready? {}))))

;; ----------------------------- batch-verified? / batch-registered? / batch-ready? -----------------------------

(deftest batch-is-verified-when-flagged
  (is (true? (r/batch-verified? {:id "b1" :verified? true}))))

(deftest batch-is-not-verified-when-false-or-missing
  (is (false? (r/batch-verified? {:id "b1" :verified? false})))
  (is (false? (r/batch-verified? {:id "b1"}))))

(deftest batch-is-registered-when-flagged
  (is (true? (r/batch-registered? {:registered? true}))))

(deftest batch-is-not-registered-when-false-or-missing
  (is (false? (r/batch-registered? {:registered? false})))
  (is (false? (r/batch-registered? {}))))

(deftest batch-ready-requires-both
  (is (true? (r/batch-ready? {:verified? true :registered? true})))
  (is (false? (r/batch-ready? {:verified? true :registered? false})))
  (is (false? (r/batch-ready? {:verified? false :registered? true})))
  (is (false? (r/batch-ready? {}))))

;; ----------------------------- shipment-area-exceeded? -----------------------------

(deftest small-shipment-within-area-does-not-exceed
  (is (false? (r/shipment-area-exceeded?
               {:area-sqm 500.0 :shipped-area-sqm 100.0} 50.0))))

(deftest shipment-that-pushes-past-area-exceeds
  (is (true? (r/shipment-area-exceeded?
              {:area-sqm 80.0 :shipped-area-sqm 75.0} 10.0))))

(deftest shipment-exactly-at-area-does-not-exceed
  (is (false? (r/shipment-area-exceeded?
               {:area-sqm 80.0 :shipped-area-sqm 75.0} 5.0))
      "exactly at area is not over, only strictly beyond"))

(deftest missing-area-is-not-flagged-exceeded
  (is (false? (r/shipment-area-exceeded? {} 100.0)))
  (is (false? (r/shipment-area-exceeded? {:area-sqm 800.0} nil))))

;; ----------------------------- grade-valid? -----------------------------

(deftest known-grades-are-valid
  (doseq [g [:chrome-tanned-wet-blue :vegetable-tanned-crust
             :full-grain :top-grain :corrected-grain
             :split-leather :suede :nubuck :patent-leather :bonded-leather]]
    (is (r/grade-valid? g))))

(deftest fabricated-grade-is-invalid
  (is (not (r/grade-valid? :premium-plus-select)))
  (is (not (r/grade-valid? nil))))

;; ----------------------------- shrinkage-temperature-valid? -----------------------------

(deftest typical-shrinkage-temperature-is-valid
  (is (r/shrinkage-temperature-valid? 118.0))
  (is (r/shrinkage-temperature-valid? 82.0))
  (is (r/shrinkage-temperature-valid? 55.0))
  (is (r/shrinkage-temperature-valid? 150.0)))

(deftest non-positive-shrinkage-temperature-is-invalid
  (is (not (r/shrinkage-temperature-valid? 0.0)))
  (is (not (r/shrinkage-temperature-valid? -1.0))))

(deftest excessive-shrinkage-temperature-is-invalid
  (is (not (r/shrinkage-temperature-valid? 250.0)))
  (is (not (r/shrinkage-temperature-valid? 150.01))))

(deftest non-numeric-or-missing-shrinkage-temperature-is-invalid
  (is (not (r/shrinkage-temperature-valid? nil)))
  (is (not (r/shrinkage-temperature-valid? "118.0"))))

;; ----------------------------- register-maintenance -----------------------------

(deftest maintenance-is-a-draft-not-a-real-actuation
  (let [result (r/register-maintenance "mnt-1" "equip-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest maintenance-assigns-maintenance-number
  (let [result (r/register-maintenance "mnt-1" "equip-001" 7)]
    (is (= (get result "maintenance_number") "MNT-000007"))
    (is (= (get-in result ["record" "maintenance_id"]) "mnt-1"))
    (is (= (get-in result ["record" "equipment_id"]) "equip-001"))
    (is (= (get-in result ["record" "kind"]) "maintenance-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest maintenance-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "" "equip-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "equip-001" -1))))

;; ----------------------------- register-shipment -----------------------------

(deftest shipment-is-a-draft-not-a-real-dispatch
  (let [result (r/register-shipment "ship-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest shipment-assigns-shipment-number
  (let [result (r/register-shipment "ship-1" 7)]
    (is (= (get result "shipment_number") "SHP-000007"))
    (is (= (get-in result ["record" "shipment_id"]) "ship-1"))
    (is (= (get-in result ["record" "kind"]) "shipment-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest shipment-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "ship-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-maintenance "mnt-1" "equip-001" 0)
        hist (r/append [] c1)
        c2 (r/register-maintenance "mnt-2" "equip-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "MNT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "MNT-000001" (get-in hist2 [1 "record_id"])))))
