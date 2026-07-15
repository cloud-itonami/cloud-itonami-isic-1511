# cloud-itonami-isic-1511: Tanning and dressing of leather

Open Business Blueprint for **ISIC Rev.5 1511**: tanning and dressing of leather — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office leather-**tannery plant operations**: production-batch data logging (hide-lot/tanning-process/leather-grade/output-quality), tanning-drum/finishing-line-equipment maintenance scheduling, safety-concern flagging, and outbound leather shipment coordination.

This repository designs a forkable OSS business for leather-tannery plant
operations: run by a qualified operator so a tannery keeps its own
operating records instead of renting a closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — hide-lot/tanning-process/output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — tanning-drum/finishing-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-hazard (chromium VI)/effluent/worker-safety concern (always escalates)
- `:coordinate-shipment` — outbound leather shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical and
environmentally-regulated domain** (chrome/vegetable tanning-drum
chemistry, chromium VI exposure risk, finishing-line equipment,
effluent discharge with real environmental impact):

- Does NOT control tanning drums, finishing-line equipment, or effluent-treatment equipment directly
- Does NOT make plant-safety or hazard decisions (that's the tannery supervisor's exclusive human authority)
- Does NOT authorize an effluent discharge (human tannery supervisor / environmental compliance officer decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`leathertanning.operation/build`, a langgraph-clj StateGraph):
1. **`leathertanning.advisor`** (sealed intelligence node, `LeatherTanningAdvisor`): proposes decisions only, never commits
2. **`leathertanning.governor`** (independent, `Leather Tanning Plant Operations Governor`): validates against domain rules, re-derived from `leathertanning.registry`'s pure functions and `leathertanning.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Tannery/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct tanning/finishing-line-equipment control)
     - Authorizing an effluent discharge (`:discharge-authorize? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped area past its own logged output area (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:grade` value on a production-batch patch
     - No physically implausible `:shrinkage-temperature-c` (hydrothermal stability, Ts) value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`leathertanning.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`leathertanning.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
