# Requirements Document

## Epic

- **Epic ID**: {To be assigned}
- **Status**: Draft
- **Author**: architecture review (jcoding-automation)
- **Created**: 2026-09-20

## Introduction

`jcoding-automation` is a stateful, cron-driven controller that drives a bounded
Codex CLI worker through a full software-delivery loop against a single GitHub
EPIC (select story → implement → verify → review → fix feedback → deliver →
merge gate). It works in production, but a sustained troubleshooting session
exposed that its **core control logic is fragile and prone to regression**.

This specification defines the **behavioral contract** the controller must
satisfy (which today exists only as improvised, undocumented code) and the
**architectural refactor** required to make that contract explicit, testable,
and robust to change. The goal is not new features — it is to fix the
fundamentals so the existing behavior becomes *specified, verifiable, and safe
to modify*.

### Problem statement (evidence-based)

Diagnosed root causes, in causal order:

1. **No behavioral specification.** There is no requirements/design document.
   The only artifacts are the README (operational usage) and per-phase worker
   prompts. The controller's own rules — what "progress" means, when a story is
   "stuck", loop-termination policy, terminal/park policy — exist **only** as
   scattered logic. Fixes were therefore *guessed* from logs, and each guess
   risked contradicting an earlier unstated rule.
2. **A god-method.** `WorkflowOrchestrator.reconcileAndExecute` is **1,156
   lines** (71% of its 1,621-line file) and concentrates story selection, PR
   adoption, feedback routing, reconciliation, anti-loop guards, delivery,
   merge, and thread resolution in one scope.
3. **Untyped, sprawling state.** The workflow is an implicit state machine
   driven by **~60 string-keyed flags** in a `Map<String,Object>`, with
   "reset flag X on condition Y but not Z" rules duplicated across ~6 code
   sites. This is the direct mechanism of fragility: no change can be reasoned
   about locally.
4. **Weak test isolation.** The suite is mock/offline only (the README states
   real `gh`/`codex` are never exercised). Integration tests are multi-tick and
   fragile; every production incident this session was in the untested
   boundary.

### Scope of this spec

- **In scope:** define the workflow state machine and its invariants; define
  the progress/loop/terminal policy; refactor the god-method and state handling
  into single-responsibility, independently testable components; add an
  end-to-end (still offline) harness that exercises full ticks; preserve all
  current externally-observable behavior unless a requirement explicitly
  changes it.
- **Out of scope:** new workflow features; changing the Codex worker prompts'
  intent; real network/GitHub/codex integration testing; multi-EPIC or
  multi-repo support; performance beyond removing known waste.
  *(Exception: Requirement 9 — the persisted plan/ledger/task-report discipline
  — is an additive, config-gated capability adopted alongside the hardening; it
  defaults off so existing behavior is preserved until enabled.)*

## Glossary

- **Controller** — the Java application (`Controller` + `WorkflowOrchestrator`)
  that owns scheduling, locking, state, GitHub reconciliation, phase dispatch,
  and the merge gate.
- **Worker** — the bounded `codex exec` process invoked per phase.
- **Phase** — a controller workflow state (e.g. `PLANNING`, `IMPLEMENTING`,
  `VERIFYING`, `REVIEWING_CODE`, `FIXING_FEEDBACK`, `RECONCILING`,
  `WAITING_FOR_CI`, `WAITING_FOR_CODEX_REVIEW`, `WAITING_FOR_MANUAL_MERGE`,
  `READY_TO_MERGE`, `BLOCKED_*`, `IDLE`, `EPIC_COMPLETE`).
- **Head** — the PR branch head commit SHA (`headRefOid`).
- **Progress** — an objective, observable advance in the delivery of the active
  story (see FR-4 for the authoritative definition).
- **Stall** — repeated attempts on the same head with no progress.
- **Terminal block** — a durable state in which the controller stops working a
  head until it advances or an operator acts.
- **Tick** — one cron-triggered controller run.
- **Progress Guard** — the proposed single component that owns all
  anti-loop / progress / stall / terminal decisions (see design).
- **Plan** — a committed `plan.md` decomposing a story into ordered tasks keyed
  to its acceptance criteria (Requirement 9).
- **Ledger** — a committed `ledger.md` tracking per-task status, commit, and
  notes; the durable "where am I" record a resumed worker reads (Requirement 9).
- **Head** — the PR branch head commit SHA (`headRefOid`).
- **Progress** — an objective, observable advance in the delivery of the active
  story (see FR-4 for the authoritative definition).
- **Stall** — repeated attempts on the same head with no progress.
- **Terminal block** — a durable state in which the controller stops working a
  head until it advances or an operator acts.
- **Tick** — one cron-triggered controller run.
- **Progress Guard** — the proposed single component that owns all
  anti-loop / progress / stall / terminal decisions (see design).

## Requirements

### Requirement 1: Documented workflow state machine

**User Story:** As a maintainer, I want the controller's phases and legal
transitions defined in one authoritative place, so that any change can be
checked against the intended behavior instead of reverse-engineered from a
1,156-line method.

#### Acceptance Criteria (EARS)

- WHEN the system defines its workflow, THE SYSTEM SHALL enumerate every phase,
  its entry condition, its exit transitions, and the prompt it dispatches, in a
  single design artifact and a single code owner.
- WHEN a phase transition occurs, THE SYSTEM SHALL only allow transitions
  declared legal by the state machine; an undeclared transition SHALL be a
  detectable error, not silent behavior.
- WHERE the current implementation and the documented state machine disagree,
  THE SYSTEM SHALL treat the discrepancy as a defect to reconcile (behavior is
  captured from current code first, then corrected only via an explicit
  requirement).

### Requirement 2: Single-responsibility decomposition of the orchestration

**User Story:** As a maintainer, I want the god-method decomposed into focused
handlers, so that a change to one phase cannot silently break another.

#### Acceptance Criteria (EARS)

- THE SYSTEM SHALL reduce `reconcileAndExecute` to a thin dispatcher that
  delegates each phase to a dedicated, independently testable handler.
- THE SYSTEM SHALL keep each handler and the new components below approved
  complexity thresholds (method length, cyclomatic complexity, fan-out) with
  reviewed exceptions only.
- THE SYSTEM SHALL preserve all current externally-observable behavior across
  the refactor, proven by characterization tests captured before refactoring.

### Requirement 3: Typed, owned workflow state

**User Story:** As a maintainer, I want workflow state to be a typed object with
clear ownership, so that the "~60 loose flags with scattered reset rules"
fragility is eliminated.

#### Acceptance Criteria (EARS)

- THE SYSTEM SHALL replace ad-hoc `state.get/put` string keys for control logic
  with a typed state model (records/enums) whose fields have documented
  lifecycles and single mutation points.
- WHEN a story completes or is parked, THE SYSTEM SHALL reset all story-scoped
  state through one method, so no state can leak into the next story.
- THE SYSTEM SHALL persist and load this typed state to/from the durable store
  with backward-compatible migration from the current JSON shape.

### Requirement 4: Explicit progress definition and loop-termination policy

**User Story:** As an operator, I want the controller to keep working a PR that
is making genuine progress and to stop only when it is truly stalled, so that a
healthy review cycle is never falsely blocked and a pathological loop never runs
forever.

#### Acceptance Criteria (EARS)

- THE SYSTEM SHALL define **progress** objectively as at least one of:
  (a) the PR head advanced AND a review thread was resolved, or
  (b) a fix was committed and pushed that advanced the head.
  *(Field evidence: PR #187 legitimately advanced ~40 heads and resolved ~200
  threads; the prior story-scoped counter falsely declared it unrecoverable.)*
- WHILE the active story is making progress, THE SYSTEM SHALL NOT declare it
  stalled or terminal, regardless of elapsed attempts.
- WHEN the same head is retried more than a configurable bound WITHOUT progress,
  THE SYSTEM SHALL take exactly one bounded action (deliver committed work to
  CI if deliverable exists; otherwise a single terminal block), and SHALL NOT
  re-increment or re-enter the loop on subsequent ticks for that unchanged head.
- WHEN a head advances, THE SYSTEM SHALL reset all no-progress counters.
- THE SYSTEM SHALL never loop unbounded and never dead-end silently: every
  non-progress path resolves to deliver-to-CI, terminal-block, or park (FR-5).

### Requirement 5: Terminal/stuck policy — autonomous progress across the EPIC

**User Story:** As an operator of a fully autonomous system, I want a genuinely
unrecoverable story to be parked and the controller to continue with the next
eligible story, so that one stuck story does not halt the entire EPIC.

#### Acceptance Criteria (EARS)

- WHEN a story reaches a terminal `UNRECOVERABLE` state on its current head,
  THE SYSTEM SHALL record it as parked (with head, reason, timestamp) and, if
  comments are enabled, post exactly one operator notification on the PR.
- WHILE a story is parked on a given head, THE SYSTEM SHALL exclude it from
  story selection and SHALL NOT re-read/re-process it every tick.
- WHEN a parked story's head advances (a new commit lands), THE SYSTEM SHALL
  automatically un-park it and resume the normal cycle.
- WHERE parking a story would leave no eligible work, THE SYSTEM SHALL enter a
  quiet idle/backoff state rather than busy-looping.
- **[DECISION REQUIRED]** Default terminal policy: `park-and-advance` (proposed)
  vs `hold-on-current-story` (current). Confirm during review.

### Requirement 6: Observability and log hygiene as a contract

**User Story:** As an operator, I want logs that make control decisions
diagnosable without reading the source, and no repeated-line or worker-output
flooding, so that troubleshooting is fast and cheap.

#### Acceptance Criteria (EARS)

- THE SYSTEM SHALL log each control decision with its reason code and the
  inputs that drove it (phase, head, progress signal, counters vs caps).
- THE SYSTEM SHALL NOT emit repeated identical lines every tick for a stable
  waiting/terminal state (deduped/back-off), and SHALL keep worker stdout out of
  the default log level.
- THE SYSTEM SHALL surface WARN/ERROR only for genuine faults; expected
  hand-offs (e.g. lost lock, quiet terminal hold) SHALL be INFO.

### Requirement 7: Test strategy that isolates control logic

**User Story:** As a maintainer, I want the control policy covered by fast,
deterministic unit tests and the full loop covered by an offline end-to-end
harness, so that regressions in the fragile area are caught before deploy.

#### Acceptance Criteria (EARS)

- THE SYSTEM SHALL cover the Progress Guard and each phase handler with pure
  unit tests (no git/GitHub/process mocks) enumerating every transition.
- THE SYSTEM SHALL provide an offline end-to-end harness that runs many full
  ticks against a scripted fake `gh` and fake `codex`, reproducing the PR #187
  lifecycle (multi-head, multi-thread progress, stall, terminal, park).
- THE SYSTEM SHALL retain the existing behavior via characterization tests
  captured before refactoring and re-run after, proving parity.

### Requirement 8: Safe, incremental delivery of the refactor

**User Story:** As a stakeholder, I want the fundamentals fixed without a risky
big-bang rewrite, so that the working system stays working throughout.

#### Acceptance Criteria (EARS)

- THE SYSTEM SHALL be refactored behind unchanged external behavior in small,
  independently mergeable steps, each green on the full suite and `installDist`.
- THE SYSTEM SHALL follow characterize → extract → verify parity for each step.
- WHERE a step must change behavior (e.g. FR-5 park policy), THE SYSTEM SHALL
  gate it behind config defaulting to current behavior until explicitly enabled.

### Requirement 9: Durable per-story plan and ledger (resumable planning)

**User Story:** As an operator of a bounded, resumable worker, I want each story
decomposed into a committed plan with a status ledger, so that a timed-out or
freshly-spawned worker resumes from a written record instead of re-deriving
scope every tick, and progress is objective.

*(Today the story's task breakdown lives only inside a single `IMPLEMENT` phase
in the worker's head — nothing is persisted, so a resumed worker re-plans from
scratch. Adapted, simplified, from agentic-consumer's `plan.md`/`ledger.md`
convention. This is a worker-artifact discipline, not a review mechanism —
review stays the existing single Codex review trigger.)*

#### Acceptance Criteria (EARS)

- WHEN a story enters implementation for the first time AND planning is enabled,
  THE SYSTEM SHALL run a `PLANNING` phase that produces `plan.md` (goal,
  constraints, ordered checkbox task list keyed to acceptance criteria) and
  `ledger.md` (one row per task, status `pending`), committed on the story
  branch when delivery is enabled.
- WHILE a story has a valid committed plan, THE SYSTEM SHALL skip planning on
  resume and continue at the next `pending`/`in-progress` task; validity SHALL
  be judged from committed state only, and any parse ambiguity SHALL route to
  the existing reconcile phase (fail closed) rather than silently re-planning.
- WHEN a worker advances a task, THE SYSTEM SHALL require it to update that
  task's ledger row (status, commit SHA, note) atomically with the code change.
- WHEN a worker resumes an existing plan AND the acceptance criteria changed,
  THE SYSTEM SHALL reconcile the plan (append/adjust tasks) rather than trust a
  stale `done` ledger. *(Field evidence: agentic-consumer issue #47.)*
- THE SYSTEM SHALL keep the controller free of task semantics: it reads only the
  ledger status counts as a cheap progress signal (Requirement 10) and never
  parses or schedules individual tasks; the worker authors all plan content.
- WHERE delivery is disabled (dry-run or `allow_push:false`), THE SYSTEM SHALL
  still allow the worker to write the plan in the working tree and SHALL NOT
  treat an uncommitted plan as a blocker.
- **[DECISION REQUIRED]** Plan default: ships config-gated OFF (current
  DISCOVER→IMPLEMENT path preserved) until proven on a real dry-run.

### Requirement 10: One progress counter (consolidate anti-loop mechanisms)

**User Story:** As a maintainer, I want a single source of truth for
"attempts-without-progress", so that the scattered, independently-reset counters
(`reconciliation_attempts`, `max_resume_attempts`, `max_feedback_attempts`, and
any planning/stall counter) can never disagree — the exact fragility Requirement
3 targets.

#### Acceptance Criteria (EARS)

- THE SYSTEM SHALL maintain exactly one per-head no-progress counter, owned by
  the Progress Guard (Requirement 4); the reconcile, resume, feedback, and
  planning caps SHALL be policies (thresholds) over that one counter, not their
  own independent counters.
- WHEN a head advances (progress per FR-4), THE SYSTEM SHALL reset that single
  counter, and all dependent caps SHALL observe the reset without separate
  bookkeeping.
- WHERE proactive stall handling is needed, THE SYSTEM SHALL reuse the existing
  reactive `RECONCILE` phase and its fail-closed, capped decision applier — no
  new supervising phase, agent, or counter is introduced.

## Non-Functional Requirements

- **Robustness:** control logic changes must be locally reasoned about; no
  change to one phase may require understanding another. Measured by: no shared
  mutable control state across handlers.
- **Maintainability:** no method > ~80 lines in the refactored control path;
  the workflow state machine expressible on one page.
- **Determinism:** all control decisions are pure functions of typed inputs
  (state + observations), enabling exhaustive unit testing.
- **Backward compatibility:** durable state files from the current version load
  without manual migration.
- **Stability preservation:** the manual-merge gate, lock/watchdog recovery,
  fail-closed mutation flags, and dry-run safety are unchanged.
- **Offline testability:** no test contacts real GitHub or launches real codex.

## Dependencies

- Java 21, Gradle, JUnit 5, existing collaborators (`GitHub`, `GitOperations`,
  `CodexRunner`, `StateStore`, `WorkflowLock`, `Engine`, `ReviewCoordinator`).
- Existing durable state format under `state/epic-<n>.*`.

## Assumptions

- Current externally-observable behavior (post the fixes made in this session)
  is the baseline to preserve, except where a requirement explicitly changes it.
- The Codex worker's ability to resolve a given finding is out of the
  controller's control; the controller's job is to bound and route, not to fix.

## Constraints

- No big-bang rewrite; behavior-preserving, incremental only.
- All work stays within the offline, mock-based test discipline.
- The controller remains the sole authority for scheduling, locking, state, and
  the merge gate; Codex remains a constrained worker.

## Open decisions (require review sign-off)

1. **FR-5 terminal policy:** `park-and-advance` (recommended) vs current
   `hold-on-current-story`.
2. **FR-4 caps:** exact no-progress bound per head and any absolute per-story
   ceiling (proposed: keep current `max_feedback_attempts` / `max_resume_attempts`
   but drive them off the new progress signal).
3. **FR-3 migration:** typed-state now vs a phased introduction (typed control
   substate first, full typed state later).
4. **FR-9 plan:** ships config-gated OFF (current DISCOVER→IMPLEMENT path
   preserved) until proven on a real dry-run. Plan committed on the story branch
   as a single `plan.md` + `ledger.md` (no per-task report files).
5. **FR-10 counters:** confirm collapsing `reconciliation_attempts`,
   `max_resume_attempts`, `max_feedback_attempts`, and any planning cap into one
   Progress-Guard-owned counter with per-policy thresholds.
