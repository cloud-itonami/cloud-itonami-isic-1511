# ADR-0001: LeatherTanningAdvisor ⊣ Leather Tanning Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1511` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1511` publishes an OSS blueprint for leather-
tannery **plant operations coordination** (production-batch hide-lot/
tanning-process/leather-grade/output-quality data logging,
tanning-drum/finishing-line-equipment maintenance scheduling,
safety-concern flagging, and outbound leather shipment coordination).
Like every actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-1701` (Manufacture of
pulp, paper and paperboard): both are back-office coordination actors
for a fixed processing PLANT (not a field site) with heavy chemical-
process equipment, a real physical-safety dimension, and a central
ground-truth **production batch** entity independently gated alongside
an **equipment** entity. Leather tanning differs in the specific
chemical hazard that shapes this design: 1701's is pulping-liquor
chemistry and paper-machine mechanical hazard; 1511's is chrome
(chromium VI) or vegetable tanning-liquor chemical hazard, plus a
distinct, regulated effluent-discharge concern with real environmental
impact -- both chrome and vegetable tanning produce process effluent
that must be treated before release. This actor's second PERMANENT
governor block therefore targets effluent-discharge AUTHORIZATION
specifically (mirroring 1701's own effluent-discharge block
structurally, applied to this domain's own tanning-drum/finishing-line
equipment), rather than a generic "finalize" flag.

This vertical has NO pre-existing `kotoba-lang/leathertanning`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`leathertanning.registry` (equipment/batch verification, shipment-area
recompute, leather-grade validation, shrinkage-temperature
plausibility validation) are re-verified independently by the
governor, the same "ground truth, not self-report" discipline
established across prior actors (most directly
`cloud-itonami-isic-1701`'s `pulppaper.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:leather-tanning-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "leather-tanning" --owner cloud-itonami`,
zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external leathertanning capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
leather-tanning vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-area / grade /
shrinkage-temperature validation functions live as pure functions in
`leathertanning.registry` and are re-verified independently by
`leathertanning.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-1701`'s `pulppaper.registry`).

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of leather-tannery
plant operations. It does NOT:
- Control tanning drums, finishing-line equipment, or effluent-treatment equipment directly
- Make plant-safety or hazard decisions (exclusive to the human tannery supervisor)
- Authorize an effluent discharge (human tannery supervisor/environmental compliance officer decides)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human tannery-
supervisor approval. This is not a replacement for the supervisor's
authority -- it is a proposal-screening and documentation layer.

**CRITICAL SAFETY/ENVIRONMENTAL BOUNDARY**: leather tanning is a
safety-critical AND environmentally-regulated domain (chrome/
vegetable tanning-liquor chemical hazard, chromium VI exposure risk,
finishing-line mechanical hazard, effluent-discharge environmental
impact). Safety-concern flagging NEVER auto-commits. All safety
concerns escalate immediately to human review, and effluent-discharge
authorization is permanently blocked regardless of confidence or
approval.

### Decision 3: Safety-concern escalation -- always human sign-off

`:flag-safety-concern` (chromium VI/chemical-liquor hazard, effluent-
discharge concern, tanning-drum/finishing-line mechanical hazard, crew
exposure) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" -- it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Mirroring `cloud-itonami-isic-1701`'s own structure, this vertical has
TWO entity kinds each gating a different op: `:schedule-maintenance`
independently verifies the referenced **equipment** unit's own
`:verified?`/`:registered?` fields; `:coordinate-shipment`
independently verifies the referenced **batch**'s own
`:verified?`/`:registered?` fields. Both are the same "tannery/batch
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-shipment` additionally independently
recomputes whether a batch's own recorded shipped-to-date area plus
the proposal's own claimed area would exceed the batch's own recorded
output area -- never taken on the advisor's self-report.

### Decision 5: A distinct second PERMANENT block -- effluent-discharge authorization, not equipment finalization

This vertical's task brief calls for a PERMANENT block covering both
tanning/finishing-line-equipment control AND effluent-discharge
authorization. Two independent HARD checks jointly implement this:
`equipment-control-blocked-violations` (the effect-allowlist check,
catches any hallucinated direct-actuation effect such as a
`:tanning-drum/actuate` or `:finishing-line/actuate`) and
`discharge-authorize-blocked-violations` (the domain-specific flag
check, catches an authorize-shaped proposal that would otherwise pass
the effect-allowlist check because it is still nominally
`:maintenance/schedule`).

### Decision 6: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`leathertanning.governor`, mirroring `cloud-itonami-isic-1701`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Tannery/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's area must independently recompute within the batch's own logged output area
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct tanning-drum/finishing-line-equipment control or effluent-discharge authorization is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Leather-tannery plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human tannery-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or effluent-discharge authorization.
Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical AND environmental-compliance discipline is
explicit: safety-concern flagging cannot be rate-limited, suppressed,
or auto-decided by phase gate; effluent-discharge authorization is
blocked by two independent layers (the closed-effect allowlist and the
domain-specific flag check).

(+) Hide-sourcing traceability is preserved administratively: the
production-batch record carries an informational `:hide-source` field
(analogous to a sibling actor's own `:species` field), logged but not
governor-validated, so a hide-lot's provenance travels with the
batch record through maintenance scheduling and shipment coordination.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and effluent-discharge decisions
remain human-controlled via external channels.

(-) No integration with real tannery-management databases (equipment
telemetry, batch tracking, freight dispatch, environmental-compliance
reporting systems) -- this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-1511`: `clojure -M:test` green (see the
  superproject ADR and `kotoba-lang/industry` registry entry for the
  exact re-verification output, run from an independent fresh clone at
  the merge commit), `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-area-exceeded,
  discharge-authorize-blocked, already-scheduled, invalid-grade,
  invalid-shrinkage-temperature).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
