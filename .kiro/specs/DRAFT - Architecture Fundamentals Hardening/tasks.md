# Tasks Document

## Epic

- **Epic ID**: {To be assigned}
- **Status**: Draft
- **Related**: requirements.md, design.md

## Delivery principles

- Behavior-preserving and incremental (characterize → extract → verify parity).
- Every story includes implementation **and** tests; each is independently
  mergeable and must leave the full suite + `installDist` green.
- Story 1 establishes the safety net (characterization tests) before any
  refactor — this is the CI/CD-foundation equivalent for a refactor epic.

## Story map

```mermaid
flowchart LR
  S1[S1 Characterization<br/>safety net] --> S2[S2 ProgressGuard extract]
  S1 --> S3[S3 Typed state + codec]
  S2 --> S4[S4 Feedback+Reconcile handlers]
  S3 --> S4
  S4 --> S5[S5 Delivery+MergeGate+Wait+Select handlers]
  S5 --> S6[S6 WorkflowMachine transition table]
  S2 --> S7[S7 Offline E2E harness]
  S5 --> S7
  S6 --> S8[S8 Park-and-advance policy (config-gated)]
  S7 --> S8
  S8 --> S9[S9 Cleanup, docs, thresholds]
  S6 --> S10[S10 PLANNING phase + PlanLedger (config-gated)]
  S7 --> S10
  S5 --> S11[S11 WorkerSupervisor + worker contract]
  S3 --> S11
  S8 --> S12[S12 WorkflowSupervisor end-to-end health (config-gated)]
  S7 --> S12
```

## Stories

### S1 — Characterization test safety net
- **Validates:** R7, R8
- **Description:** Capture current externally-observable behavior of
  `reconcileAndExecute` for the key scenarios (select story, feedback→deliver,
  reconcile PROCESS_FEEDBACK, dry-run terminal, live progress, waiting/merge
  gate, PR #187 multi-head/thread lifecycle) as golden tests against the
  existing (mock-based) harness.
- **Acceptance:**
  - [ ] A `CharacterizationTest` suite asserts current outcomes for ≥8
        scenarios including the PR #187 lifecycle.
  - [ ] Suite is green on the current code and runs in < 1 min.
- **Depends:** none · **Estimate:** 2 days

### S2 — Extract ProgressGuard (pure decision engine)
- **Validates:** R4, R2
- **Description:** Move all anti-loop / progress / stall / terminal logic out of
  `reconcileAndExecute` into a pure `ProgressGuard` with `observe`/`decide` and
  a `ProgressState` record; wire the orchestrator to call it. No behavior change.
- **Acceptance:**
  - [ ] `ProgressGuard` + `ProgressState` created; the scattered `stuck_head*`,
        `resume_*`, `feedback_attempts` control logic is removed from the
        orchestrator and delegated to the guard.
  - [ ] `ProgressGuardTest` (pure, no mocks) covers TS-1..TS-3 and every
        transition in the design state diagram.
  - [ ] Characterization suite (S1) still green (parity).
- **Depends:** S1 · **Estimate:** 3 days

### S3 — Typed WorkflowState + backward-compatible codec
- **Validates:** R3
- **Description:** Introduce a typed `WorkflowState` record and
  `WorkflowStateCodec` mapping legacy flat JSON ↔ typed model; route control
  reads/writes through it while preserving unknown keys.
- **Acceptance:**
  - [ ] `WorkflowStateCodec` round-trips a real legacy `epic-<n>.json` with no
        data loss (TS-7).
  - [ ] Single `clearStoryScoped()` reset point; no duplicated reset lists.
  - [ ] Characterization suite still green.
- **Depends:** S1 · **Estimate:** 3 days

### S4 — Extract Feedback + Reconcile handlers
- **Validates:** R2, R1
- **Description:** Extract `FeedbackHandler` and `ReconcileHandler` from the
  god-method; each consumes typed state + ProgressGuard and returns a
  `PhaseOutcome`.
- **Acceptance:**
  - [ ] Both handlers < ~80 lines; unit-tested with typed inputs (no mocks).
  - [ ] `reconcileAndExecute` no longer contains feedback/reconcile branching.
  - [ ] Characterization suite still green.
- **Depends:** S2, S3 · **Estimate:** 3 days

### S5 — Extract Delivery, MergeGate, Wait, SelectStory handlers
- **Validates:** R2
- **Description:** Extract the remaining phase logic into focused handlers.
- **Acceptance:**
  - [ ] Each handler < ~80 lines with unit tests.
  - [ ] `reconcileAndExecute` reduced to a thin dispatcher (< ~120 lines).
  - [ ] Characterization suite still green.
- **Depends:** S4 · **Estimate:** 4 days

### S6 — WorkflowMachine transition table
- **Validates:** R1
- **Description:** Introduce a declarative `phase → prompt / legal-next /
  handler` table; dispatcher enforces legal transitions.
- **Acceptance:**
  - [ ] All phases + transitions declared in one artifact; illegal transition
        raises a detectable error (TS-6).
  - [ ] Design "one-page state machine" matches the table.
  - [ ] Characterization suite still green.
- **Depends:** S5 · **Estimate:** 2 days

### S7 — Offline end-to-end tick harness
- **Validates:** R7
- **Description:** Build `WorkflowE2EHarness` running N full ticks against a
  scripted fake `gh` + fake `codex`; encode field scenarios.
- **Acceptance:**
  - [ ] Harness replays the PR #187 lifecycle (TS-10) and the stall/terminal
        cases (TS-2..TS-5) deterministically, no per-method mocks.
  - [ ] Runs in CI offline in < 2 min.
- **Depends:** S2, S5 · **Estimate:** 3 days

### S8 — Park-and-advance terminal policy (config-gated)
- **Validates:** R5
- **Description:** Implement park-and-advance: park a terminally-stuck story,
  notify once, exclude from selection, auto-unpark on head advance, and select
  the next eligible story. Gated by config defaulting to current behavior.
- **Acceptance:**
  - [ ] TS-4, TS-5 pass; parked stories excluded from selection and un-parked
        on head advance.
  - [ ] Default config preserves current `hold` behavior until enabled.
  - [ ] Full suite + `installDist` green.
- **Depends:** S6, S7 · **Estimate:** 3 days · **[DECISION REQUIRED before start]**

### S9 — Cleanup, thresholds, documentation
- **Validates:** R2 (NFR), R6
- **Description:** Enforce complexity thresholds on the control path, remove
  dead legacy keys, finalize decision-reason logging and dedupe (R6), and update
  README/spec to match the shipped design.
- **Acceptance:**
  - [ ] No control-path method > ~80 lines; static-analysis gate added.
  - [ ] Decision logs include reason codes (TS-9); no repeated flood lines.
  - [ ] README + this spec reflect the final **core refactor** architecture
        (S1–S8). The gated capabilities (S10 PLANNING, S11 WorkerSupervisor,
        S12 WorkflowSupervisor) document themselves as they land.
- **Depends:** S8 · **Estimate:** 2 days

### S10 — PLANNING phase + PlanLedger (config-gated)
- **Validates:** R9
- **Description:** Add a `PLANNING` phase and `prompts/plan.md` that commit a
  `docs/plans/<story>/{plan.md,ledger.md}` on the story branch; extend
  IMPLEMENT/FEEDBACK prompts to update the ledger + write `task-<n>-report.md`
  per task. Add a thin `PlanLedgerReader` (status column only) and wire the
  resume-skip gate into `WorkflowMachine`, the resume-reconciliation rule, and
  an optional `tasksDoneAdvanced` progress input to `ProgressGuard`. Controller
  stays free of task semantics. Gated by config defaulting to current
  DISCOVER→IMPLEMENT behavior.
- **Acceptance:**
  - [ ] TS-11, TS-12, TS-13 pass; `PlanLedgerReader` unit-tested (pure, no mocks)
        including malformed/empty/partial ledgers.
  - [ ] Resume with a valid plan skips PLANNING; changed-AC resume reconciles
        (does not trust stale `done` ledger).
  - [ ] Default config preserves current behavior until enabled; controller
        writes no plan files and parses no task bodies.
  - [ ] Full suite + `installDist` green; README + this spec document the phase.
- **Depends:** S6, S7 · **Estimate:** 3 days · **[DECISION REQUIRED before start]**

### S11 — WorkerSupervisor + worker execution contract
- **Validates:** R10, R2
- **Description:** Extract the inlined worker-execution + heartbeat slice from
  the dispatcher into a `WorkerSupervisor` returning a typed `WorkerResult`. Add
  the structured `result-<epic>.json` artifact (with `RESULT_*` stdout fallback),
  the deadline handoff file, and a bounded salvage on budget/watchdog
  termination. `CodexRunner`/`WorkflowLock`/`Controller` monitoring roles
  unchanged.
- **Acceptance:**
  - [ ] `WorkerSupervisor` unit-tested; `WorkerResult` parsing covers artifact
        and stdout-fallback paths (TS-14).
  - [ ] Salvage persists ledger/result + commits controller-owned changes when
        delivery enabled, else fails closed; never merges/force-pushes (TS-15).
  - [ ] `reconcileAndExecute` no longer contains worker-run/heartbeat plumbing.
  - [ ] Characterization suite still green; manual-merge gate/dry-run unchanged.
- **Depends:** S3, S5 · **Estimate:** 3 days

### S12 — WorkflowSupervisor (end-to-end health + self-remediation, config-gated)
- **Validates:** R11
- **Description:** Add a `WorkflowSupervisor` that detects stuck signals (pure,
  reusing ProgressGuard's progress definition), dispatches a read-only
  `SUPERVISING` Codex phase (`prompts/supervise.md`) for root-cause + one
  whitelisted remediation, and applies it ONLY via the existing fail-closed
  `StateRecovery.applyReconciliationDecision`. Bounded by
  `max_supervision_attempts` → park/block. Gated OFF by default (current reactive
  reconcile behavior preserved).
- **Acceptance:**
  - [ ] `WorkflowSupervisor.detect` unit-tested (pure) for every StuckSignal;
        remediation dispatch + application covered (TS-16).
  - [ ] Out-of-whitelist/unevidenced/over-budget proposals are rejected and
        escalate to durable BLOCKED/park; no busy-loop (TS-17).
  - [ ] Cannot merge, force-push, bypass the gate, or edit control logic;
        decisions logged with signal + root-cause + reason code (R6).
  - [ ] Default config preserves current behavior; full suite + `installDist`
        green.
  - [ ] README + this spec document the SUPERVISING phase and its config flags.
- **Depends:** S6, S7, S8 · **Estimate:** 4 days · **[DECISION REQUIRED before start]**

## Summary

| Story | Focus | Depends | Est. |
|------|-------|---------|-----:|
| S1 | Characterization safety net | — | 2d |
| S2 | ProgressGuard extraction | S1 | 3d |
| S3 | Typed state + codec | S1 | 3d |
| S4 | Feedback+Reconcile handlers | S2,S3 | 3d |
| S5 | Remaining handlers | S4 | 4d |
| S6 | WorkflowMachine table | S5 | 2d |
| S7 | Offline E2E harness | S2,S5 | 3d |
| S8 | Park-and-advance (gated) | S6,S7 | 3d |
| S9 | Cleanup/thresholds/docs | S8 | 2d |
| S10 | PLANNING phase + PlanLedger (gated) | S6,S7 | 3d |
| S11 | WorkerSupervisor + worker contract | S3,S5 | 3d |
| S12 | WorkflowSupervisor end-to-end (gated) | S6,S7,S8 | 4d |

**Total: ~35 days.** Highest-value, lowest-risk first: S1 (safety net) → S2
(the component that broke repeatedly). S8 is the only behavior *change*, and
S8, S10, and S12 are config-gated and decision-gated (default off, current
behavior preserved). S11 (WorkerSupervisor) is behavior-preserving extraction.

## Notes

- S2 alone removes the specific fragility that caused this session's regression
  chain; if capacity is limited, S1→S2→S7 delivers most of the robustness gain.
- Every story preserves the manual-merge gate, lock/watchdog recovery, dry-run
  safety, and fail-closed mutation flags.
