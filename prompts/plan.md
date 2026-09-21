{{COMMON}}

[Planning Story Stage]
- Read story ticket #<number> and EPIC ticket #<ticket> in full before planning.
- Understand the scope, acceptance criteria, and dependencies. No guessing, no
  assuming, no half-baking.
- Produce a durable, ordered task plan that will guide implementation.
- Is my instruction clear? If you understand, then proceed.

Phase: PLANNING. You do NOT implement, edit source, run builds, or create a PR
in this phase. You produce (or reconcile) the durable plan artifacts on the
current story branch, commit them when delivery is enabled, and report. The
controller owns commit/push; only produce the files and report the outcome.

Inspect the authoritative EPIC, the current story, its acceptance criteria, the
repository architecture (AGENTS.md first), existing branches, and any prior plan
directory for this story. Separate fact from inference: state what the story and
repository actually require versus what you infer.

PLAN ARTIFACTS (deterministic, per story)

Resolve the plan on the current story branch as two files at the repository
root: `plan.md` and `ledger.md` (a single plan per story — do not create
per-task report files; the ledger note column carries per-task detail). Produce:

- `plan.md` — the implementation plan:
  - Goal: one paragraph tying the plan to the story's business outcome.
  - Global constraints: what must not change; safety/scope boundaries.
  - Assumptions: each assumption paired with a concrete re-runnable CHECK
    command the implementer can execute to confirm it still holds before acting.
  - Tasks: an ordered checkbox list (`- [ ] Task N: ...`). Each task is small,
    independently verifiable, maps to one or more acceptance criteria, and names
    the strongest safe test evidence that will prove it (per the COMMON
    BUSINESS-VALUE TESTING POLICY).
- `ledger.md` — the execution ledger:
  - A status table with one row per task: `# | Step | Status | Commit | Notes`.
  - Status legend: `pending | in-progress | done | blocked`.
  - Initialize every task to `pending` with empty commit/notes.
  - A "Resume instructions" section telling a future worker how to continue from
    the Status column alone.

RESUME RECONCILIATION (mandatory)

If `plan.md`/`ledger.md` already exist for this story, do NOT blindly trust a
`done` ledger. Re-validate the existing plan against the CURRENT acceptance
criteria:
- If the criteria are unchanged and tasks remain, keep the plan and report the
  next `pending`/`in-progress` task — no rewrite.
- If the criteria changed or new work is required, APPEND or adjust tasks (and
  their ledger rows) to cover the delta; never silently discard completed work
  or re-plan from scratch. Record what changed and why in `plan.md`.
- If all tasks are genuinely `done` and the criteria are fully satisfied, report
  that planning is complete and the next phase is IMPLEMENTING (which will
  confirm) or the appropriate downstream phase.

Do not create a superficial plan to appear to progress. Every task must
correspond to real, required work with concrete acceptance-criteria evidence.

PLAN_ARTIFACTS: relative paths of the plan/ledger files produced or reconciled
PLAN_TASK_COUNT: total number of tasks in the ledger
PLAN_NEXT_TASK: the number and title of the next actionable task, or NONE
PLAN_RECONCILED: NONE for a fresh plan; otherwise a concise note of what changed

RESULT_STATUS: SUCCESS when the plan and ledger are complete, evidence-based,
and committed (or produced for the controller to commit); BLOCKED when the story
requirements or state are genuinely ambiguous; FAILED only when the planning
operation itself failed
RESULT_NEXT_PHASE: IMPLEMENTING when a plan with actionable tasks exists;
RECONCILING when state is inconsistent; BLOCKED when requirements are unclear
RESULT_BLOCKER: NONE or a concise blocker
RESULT_EVIDENCE: COMPLETE when the plan maps every acceptance criterion to at
least one task with named test evidence; otherwise BLOCKED

Planning SUCCESS means only "a valid, current, ordered plan exists." It does not
mean the story is implemented, verified, reviewed, or merged.
