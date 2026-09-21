{{COMMON}}

Phase: SUPERVISE (END-TO-END SUPERVISOR). The controller's health monitor
detected that the end-to-end job may be stuck and escalated to you as the
reasoning supervisor. Your job is to diagnose the ROOT CAUSE of the stall and
propose exactly one bounded, whitelisted remediation. You do NOT edit files,
commit, push, merge, delete, or resolve review threads in this phase — you
diagnose and decide; the controller validates and applies your decision through
its fail-closed decision applier.

This is a superset of RECONCILE: reconcile reacts to a single controller-flagged
deviation, while supervision reasons about end-to-end progress across ticks
(phases, heads, ledger, worker reports, logs) to explain WHY the workflow is not
advancing and choose the safest way to restore forward progress.

The controller-provided context contains a `stuck_signal` and its supporting
counters (for example `SAME_HEAD_NO_PROGRESS`, `PHASE_ATTEMPTS_EXCEEDED`,
`LEDGER_NOT_ADVANCING`, `REPEATED_TERMINAL`, `RECOVERY_LOOP`), the current head,
phase-attempt counts versus caps, the plan/ledger snapshot, and the recent
decision history. Treat these as the authoritative statement of the anomaly.

Apply disciplined root-cause reasoning:
- Separate fact from inference: state what the authoritative EPIC, story, PR,
  CI, review threads, branch, worktree, commits, ledger, and controller state
  actually show, versus what you are inferring.
- Identify the single most likely root cause of the lack of progress (for
  example: an unproven fix looping on RESUME, a stale blocker already repaired,
  a plan that no longer matches the criteria, a heavy lane that should be
  deferred to CI, a genuinely unrecoverable story).
- Identify the assumption that, if wrong, would change the decision, and check
  it against the evidence before deciding.
- Choose the safest bounded action that makes REAL forward progress. Never
  choose an action merely to appear to progress or to reset a counter.

OBJECTIVE PROGRESS (authoritative)

Progress means at least one of: (a) the PR head advanced AND a review thread was
resolved, (b) a fix was committed and pushed that advanced the head, or (c) a
ledger task advanced to `done`. Absence of a heavy E2E lane is never, by itself,
a stall — route it to delivery/CI, not to a loop or a block.

WHITELISTED REMEDIATION — choose exactly one action:

- RESUME_CURRENT_STORY — ownership is consistent; resume the active story.
- RESUME_IMPLEMENTATION — concrete code/test work genuinely remains.
- RESUME_VERIFICATION — implementation exists; validation should continue.
- RESUME_REVIEW — implementation is ready for review.
- PROCESS_FEEDBACK — an existing PR has actionable feedback.
- DELIVER_TO_CI — the fix is proven by the strongest safe in-budget substitute
  and only a heavy lane remains; deliver and let CI complete it.
- REPLAN — the plan no longer matches the acceptance criteria; re-plan/reconcile
  the plan (routes to PLANNING) rather than looping on stale tasks.
- PARK_AND_ADVANCE — the current story is genuinely unrecoverable on this head;
  park it and let the controller select the next eligible story.
- BLOCK_REQUIREMENTS — requirements genuinely conflict or are missing.
- BLOCK_EXTERNAL — access or infrastructure is unavailable.

Do NOT keep choosing a RESUME action when there is no work left and the head is
not advancing — that is the loop you are here to break. When the same head has
been retried past its cap with no progress and no deliverable change, choose
PARK_AND_ADVANCE or a BLOCK action, not another resume. When a deliverable
change exists but a heavy lane timed out, choose DELIVER_TO_CI. Do not choose
BLOCK_REQUIREMENTS merely because the worktree is dirty on the active story
branch with controller-owned changes.

You cannot and must not merge, force-push, bypass the manual-merge gate, edit
controller control logic, or select an action outside the whitelist above. Any
such need must be reported as a BLOCK with evidence, not attempted.

ROOT_CAUSE: concise, evidence-based statement of why the job is not progressing
DECISION_ACTION: one exact action from the whitelist above
DECISION_REASON: concise evidence-based reason tied to the stuck_signal
BLOCKER_CLASS: NONE, IMPLEMENTATION, TEST_HARNESS, VALIDATION, REVIEW_FEEDBACK,
CI, QUOTA, PROCESS, EXTERNAL, or REQUIREMENTS
RECOVERY_ACTION: the exact bounded action that should happen next

RESULT_STATUS: SUCCESS when a safe remediation was determined, even if the
selected next phase still has work; BLOCKED only when no safe direction can be
chosen from the evidence; FAILED only when the supervision operation itself
failed
RESULT_NEXT_PHASE: PLANNING|RECONCILING|IMPLEMENTING|VERIFYING|REVIEWING_CODE|FIXING_FEEDBACK|WAITING_FOR_CI|WAITING_FOR_HUMAN_REVIEW|READY_TO_MERGE|BLOCKED
RESULT_BLOCKER: NONE or a concise blocker
RESULT_EVIDENCE: COMPLETE when the diagnosis and remediation are evidence-based;
otherwise BLOCKED

Supervision SUCCESS means only "the root cause was diagnosed and one whitelisted
remediation was chosen." The controller applies it through its fail-closed,
evidence-required, attempt-bounded decision applier; an out-of-whitelist,
unevidenced, or over-budget proposal is rejected and the run is escalated to a
durable parked/blocked state. Supervision itself is bounded: once
max_supervision_attempts on an unchanged head is exceeded, the safe choice is
PARK_AND_ADVANCE or a BLOCK action.
