(ns leathertanning.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (leathertanning.operation ->
  leathertanning.governor -> leathertanning.store). No invented
  numbers, no timestamps, byte-identical across reruns."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [leathertanning.store :as store]
            [leathertanning.operation :as op]
            [leathertanning.phase :as phase]
            [leathertanning.governor :as governor]
            [leathertanning.registry :as registry]
            [langgraph.graph :as g]))

(def ^:private operator {:actor-id "op-1" :actor-role :tannery-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real LeatherTanningOperationActor StateGraph
  (`leathertanning.operation/build`) through a scenario built directly
  from `leathertanning.store/sample-data!`'s real seeded batches
  (batch-001 verified+registered with shipping headroom, batch-002
  verified+registered but nearly fully shipped, batch-003 UNVERIFIED/
  unregistered) and equipment (equip-001 verified+registered tanning
  drum, equip-002 UNVERIFIED/unregistered finishing equipment), and
  `leathertanning.governor`'s real rules. This repo's own
  `leathertanning.sim` (`clojure -M:dev:run`) was run and cross-checked
  against `store.cljc`'s seed data and `governor.cljc`'s own rule
  functions and found trustworthy -- every id/op below mirrors its
  scenario (kept here as a smaller, self-contained subset rather than
  reusing `sim/-main` directly):

    1. `:log-production-batch` batch-001 (clean grade patch) -- the
       ONLY op in phase 3's `:auto` set (`leathertanning.phase`) ->
       auto-commits, no human involved.
    2. `:flag-safety-concern` concern-1 on equip-001 --
       `:stake :coordination/safety-concern` is in
       `governor/high-stakes`, so this ALWAYS escalates even though
       equip-001 is verified/registered and the governor itself is
       clean -> human tannery supervisor approves -> commits.
    3. `:schedule-maintenance` mnt-1 on equip-001 (verified,
       registered) -- `:schedule-maintenance` is deliberately absent
       from every phase's `:auto` set (see `leathertanning.phase`
       docstring), so a governor-clean proposal still escalates ->
       approve -> commits (drafts a real MNT-... record via
       `leathertanning.registry`, and independently bumps equip-001's
       own `:last-scheduled-maintenance-date`).
    4. `:coordinate-shipment` ship-1 on batch-001 (50 sqm, within
       batch-001's own recorded 500 sqm capacity minus the 100 sqm it
       has already shipped) -- escalates (write-op, never auto-eligible
       at any phase) -> approve -> commits (drafts a real SHP-...
       record, and independently bumps batch-001's own
       `:shipped-area-sqm` from 100.0 to 150.0 -- read straight off the
       store below, not invented).

  Then four DISTINCT real HARD-hold scenarios, read off
  `leathertanning.governor/check`'s own hard-violation functions, each
  never reaching a human:

    5. `:schedule-maintenance` mnt-2 on equip-002 -- equip-002 is
       seeded UNVERIFIED/unregistered -> rule `:equipment-not-verified`.
    6. `:coordinate-shipment` ship-2 on batch-003 -- batch-003 is
       seeded UNVERIFIED/unregistered -> rule `:batch-not-verified`.
    7. `:coordinate-shipment` ship-3 on batch-002 (10 sqm) --
       batch-002's own recorded area is 80 sqm and it has already
       shipped 75 sqm, so 75+10 > 80 -> rule `:shipment-area-exceeded`.
    8. `:log-production-batch` batch-002 with a fabricated
       `:grade :premium-plus-select` (not in
       `leathertanning.registry/valid-grades`) -> rule `:invalid-grade`.

  Returns the resulting `db` (a `leathertanning.store/MemStore`) --
  every field `render` reads below is real governor/store output after
  these graph runs actually executed."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (exec! actor "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:grade :full-grain :last-assessed "2026-07-18"}})

    (exec! actor "t2" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                        :value {:equipment-id "equip-001" :severity :moderate
                                :description "タンニングドラム付近でクロム(VI)臭気の上昇"}})
    (approve! actor "t2")

    (exec! actor "t3" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "equip-001" :maintenance-type :tanning-drum-inspection
                                :scheduled-date "2026-08-01" :discharge-authorize? false}})
    (approve! actor "t3")

    (exec! actor "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                        :value {:batch-id "batch-001" :area-sqm 50.0
                                :destination "buyer-tannery-north"}})
    (approve! actor "t4")

    (exec! actor "t5" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                        :value {:equipment-id "equip-002" :maintenance-type :finishing-line-service
                                :scheduled-date "2026-08-01" :discharge-authorize? false}})

    (exec! actor "t6" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :area-sqm 10.0
                                :destination "buyer-tannery-south"}})

    (exec! actor "t7" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :area-sqm 10.0
                                :destination "buyer-tannery-east"}})

    (exec! actor "t8" {:op :log-production-batch :effect :propose :subject "batch-002"
                        :patch {:grade :premium-plus-select}})

    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc
  "Minimal HTML-escape -- every rendered string passes through this."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- last-fact-for
  "The most recent ledger fact for `subject-id`, off the real
  subject-key field this repo's `commit-fact`/`hold-fact` records use:
  `:subject` (see `leathertanning.operation/commit-fact` and
  `leathertanning.governor/hold-fact`)."
  [ledger subject-id]
  (last (filter #(= subject-id (:subject %)) ledger)))

(defn- status-cell
  "[css-class label] for the last known ledger fact of a subject."
  [fact]
  (cond
    (nil? fact)                        ["muted" "no activity"]
    (= :committed (:t fact))           ["ok" "committed"]
    (= :approval-granted (:t fact))    ["ok" "approved"]
    (= :governor-hold (:t fact))       ["critical" (str "HARD hold: " (str/join "," (map name (:basis fact))))]
    (= :approval-rejected (:t fact))   ["err" "approval-rejected"]
    (= :approval-requested (:t fact))  ["warn" "awaiting approval"]
    :else                              ["muted" "in progress"]))

(defn- batches-table [db]
  (let [ledger (store/ledger db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>id</th><th>grade</th><th>hide source</th><th>tanning process</th>"
     "<th>area (sqm)</th><th>shipped (sqm)</th><th>shrinkage T (C)</th>"
     "<th>verified?</th><th>registered?</th><th>ready?</th><th>last log-production-batch status</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [b (store/all-batches db)
            :let [fact (last-fact-for ledger (:id b))
                  [cls label] (status-cell fact)]]
        (str "<tr>"
             "<td><code>" (esc (:id b)) "</code></td>"
             "<td><code>" (esc (:grade b)) "</code></td>"
             "<td>" (esc (:hide-source b)) "</td>"
             "<td><code>" (esc (:tanning-process b)) "</code></td>"
             "<td>" (esc (:area-sqm b)) "</td>"
             "<td>" (esc (:shipped-area-sqm b)) "</td>"
             "<td>" (esc (:shrinkage-temperature-c b)) "</td>"
             "<td>" (if (:verified? b) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if (:registered? b) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if (registry/batch-ready? b) "<span class=\"ok\">yes</span>" "<span class=\"warn\">no</span>") "</td>"
             "<td class=\"" cls "\">" (esc label) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- equipment-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>id</th><th>kind</th><th>verified?</th><th>registered?</th><th>ready?</th>"
   "<th>last maintenance date</th><th>last SCHEDULED maintenance date</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [eq (store/all-equipment db)]
      (str "<tr>"
           "<td><code>" (esc (:id eq)) "</code></td>"
           "<td><code>" (esc (:kind eq)) "</code></td>"
           "<td>" (if (:verified? eq) "yes" "<span class=\"critical\">no</span>") "</td>"
           "<td>" (if (:registered? eq) "yes" "<span class=\"critical\">no</span>") "</td>"
           "<td>" (if (registry/equipment-ready? eq) "<span class=\"ok\">yes</span>" "<span class=\"warn\">no</span>") "</td>"
           "<td>" (esc (or (:last-maintenance-date eq) "-")) "</td>"
           "<td>" (esc (or (:last-scheduled-maintenance-date eq) "-")) "</td>"
           "</tr>")))
   "\n</tbody></table>"))

(defn- committed-records-table [db]
  (let [maintenances (store/maintenance-history db)
        shipments (store/shipment-history db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>record_id</th><th>kind</th><th>maintenance_id / shipment_id</th><th>equipment_id / -</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [r (concat maintenances shipments)]
        (str "<tr>"
             "<td><code>" (esc (get r "record_id")) "</code></td>"
             "<td>" (esc (get r "kind")) "</td>"
             "<td><code>" (esc (or (get r "maintenance_id") (get r "shipment_id"))) "</code></td>"
             "<td><code>" (esc (or (get r "equipment_id") "-")) "</code></td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- action-gate-table
  "Static op-contract description, sourced from the real
  `leathertanning.phase/phases` (phase 3, this actor's `default-phase`)
  and `leathertanning.governor/high-stakes` -- not invented, just
  rendered."
  []
  (let [ph (get phase/phases phase/default-phase)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>op</th><th>phase-" phase/default-phase " write allowed?</th><th>auto-eligible?</th><th>always escalates (high-stakes)?</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [op (sort phase/write-ops)]
        (str "<tr>"
             "<td><code>" (esc op) "</code></td>"
             "<td>" (if (contains? (:writes ph) op) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (contains? (:auto ph) op) "<span class=\"ok\">yes</span>" "no") "</td>"
             "<td>" (if (contains? governor/high-stakes op)
                      "<span class=\"critical\">yes (always, unconditional)</span>"
                      (if (= op :flag-safety-concern)
                        "<span class=\"critical\">yes (via :stake, unconditional)</span>"
                        "no"))
             "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- audit-ledger-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>t</th><th>op</th><th>subject</th><th>disposition</th><th>basis / rule</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [f (store/ledger db)]
      (str "<tr>"
           "<td>" (esc (:t f)) "</td>"
           "<td><code>" (esc (:op f)) "</code></td>"
           "<td><code>" (esc (:subject f)) "</code></td>"
           "<td class=\""
           (case (:disposition f) :commit "ok" :hold "err" "muted")
           "\">" (esc (:disposition f)) "</td>"
           "<td>" (if (seq (:basis f))
                    (str/join ", " (map (comp esc name) (:basis f)))
                    "&mdash;")
           "</td>"
           "</tr>")))
   "\n</tbody></table>"))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 980px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render [db]
  (str
   "<!doctype html>\n"
   "<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n"
   "<title>leathertanning.render-html -- Leather Tanning Plant Operations Governor operator console</title>\n"
   "<style>"
   (jp-go-dds.skin/dds+skin)
   "</style>\n"
   "</head>\n<body>\n"
   "<header class=\"bar\"><h1>Leather Tanning Plant Operations Governor -- Operator Console</h1>"
   "<span class=\"badge\">ISIC 1511 &middot; phase " phase/default-phase " (" (:label (get phase/phases phase/default-phase)) ")</span>"
   "</header>\n"
   "<main>\n"
   "<div class=\"card\">\n<h2>Production batches</h2>\n" (batches-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Equipment</h2>\n" (equipment-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Committed drafts (maintenance-schedule / shipment-coordination)</h2>\n" (committed-records-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Action gate (leathertanning.phase &middot; leathertanning.governor/high-stakes)</h2>\n" (action-gate-table) "\n</div>\n"
   "<div class=\"card\">\n<h2>Audit ledger</h2>\n" (audit-ledger-table db) "\n</div>\n"
   "</main>\n"
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
