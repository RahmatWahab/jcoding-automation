{{COMMON}}

Phase: RECONCILE. The controller has rejected or could not safely continue the
previous action. Inspect the authoritative EPIC, current story, GitHub PR/CI
state, local branch, worktree, commits, lock/process state, and the exact
controller rejection. Decide the safest next direction; do not edit files,
commit, push, merge, delete, or resolve review threads in this phase.

Choose exactly one action:

- RESUME_CURRENT_STORY — current story/branch/worktree ownership is consistent.
- RESUME_IMPLEMENTATION — implementation should continue on the current story.
- RESUME_VERIFICATION — implementation exists and validation should continue.
- RESUME_REVIEW — implementation is ready for review, not another feature.
- PROCESS_FEEDBACK — an existing PR has actionable feedback.
- WAIT_FOR_CI — CI is still running.
- WAIT_FOR_HUMAN_MERGE — PR is ready and requires manual merge.
- PAUSE_QUOTA — Codex usage limit is active.
- BLOCK_REQUIREMENTS — requirements genuinely conflict or are missing.
- BLOCK_EXTERNAL — access or infrastructure is unavailable.

Do not choose BLOCK_REQUIREMENTS merely because the worktree is dirty if the
branch is the active story branch and the changes are controller-owned. Do not
choose a different story. Explain the evidence and the exact rejected action.

VALIDATION-BLOCKER ROUTING

When the rejection is a failure in a repository-owned test, fixture, script,
configuration, or test harness, classify it as an implementation problem when
the current story includes that behavior or its validation. Choose
RESUME_IMPLEMENTATION so the next worker can inspect and repair the repository
code or test, then rerun the failed checks. This includes deterministic test
ports colliding with unrelated containers, leaked test resources, missing
cleanup on failure or interruption, and non-isolated test commands.

For a port collision, do not stop, remove, restart, or modify a container that
the current test did not create. The implementation worker may change the
repository test to allocate a free host port or use Docker's ephemeral host
port mapping, while preserving the service's required internal container port.
The worker must verify the assigned port, use it for all probes, and clean up
only resources created by that test on success, failure, and interruption.

Choose BLOCK_EXTERNAL only when the required validation cannot be repaired or
isolated within the repository—for example, Docker is unavailable, credentials
are unavailable, a required external service is down, or an external resource
is required by the acceptance criteria and no documented safe substitute
exists. A conflict caused by the repository's fixed-port test setup is not
such a blocker.

DELIVER-THEN-CI ROUTING (avoid resume loops)

Do NOT keep choosing RESUME_IMPLEMENTATION when there is no implementation work
left and the only gap is a heavy end-to-end/integration lane that exceeded the
phase budget. That causes an infinite reconcile->implement->reconcile loop that
never commits. RESUME_IMPLEMENTATION is correct only when concrete code or test
work genuinely remains to be written or repaired on the current worktree.

When the controller-owned fix is already complete and in scope, CI on the
current head is green, and the sole remaining check is a heavy lane that timed
out (for example a Docker migration E2E), the fix must be delivered and the
heavy lane deferred to CI rather than retried locally forever. In that case
choose PROCESS_FEEDBACK (for review-feedback fixes) or RESUME_VERIFICATION, and
the follow-up worker must return RESULT_STATUS: SUCCESS with
RESULT_EVIDENCE: DEFERRED_TO_CI and RESULT_NEXT_PHASE: WAITING_FOR_CI so the
controller commits and pushes the fix and lets CI complete validation. The
manual-merge gate still protects the final merge; the automation never merges.
Only report a blocking action if the fix itself is not yet proven by the
strongest safe in-budget substitute.

Unit and mock/contract tests are sufficient evidence of a fix. If the fix is
proven by focused unit or mock/contract tests, do NOT choose
RESUME_IMPLEMENTATION merely because an E2E lane did not run, is impossible, or
is unavailable — there is no implementation work left in that case. Route it to
delivery (the follow-up worker reports RESULT_EVIDENCE: COMPLETE and the
controller commits). E2E absence is never, by itself, a reason to resume
implementation or to block.

DECISION_ACTION: one exact action from the list above
DECISION_REASON: concise evidence-based reason
BLOCKER_CLASS: NONE, IMPLEMENTATION, TEST_HARNESS, VALIDATION,
REVIEW_FEEDBACK, CI, QUOTA, PROCESS, EXTERNAL, or REQUIREMENTS
RECOVERY_ACTION: exact bounded action that should happen next
RESULT_STATUS: SUCCESS when the reconciliation decision is complete and
accepted, even if the selected next phase still has work to perform;
BLOCKED only when Codex cannot safely choose a direction; FAILED only when the
reconciliation operation itself failed
RESULT_NEXT_PHASE: RECONCILING|IMPLEMENTING|VERIFYING|REVIEWING_CODE|FIXING_FEEDBACK|WAITING_FOR_CI|WAITING_FOR_HUMAN_REVIEW|BLOCKED
RESULT_BLOCKER: NONE or a concise blocker
RESULT_EVIDENCE: COMPLETE when the decision is evidence-based; otherwise BLOCKED

Reconciliation SUCCESS means only "the next direction was determined." It does
not mean the story implementation, validation, review, or PR is complete.
The controller records that distinction as
`RECONCILIATION_ACCEPTED / DECISION_COMPLETE`.

The blocker class and recovery action are mandatory for reconciliation. Use
TEST_HARNESS or VALIDATION plus RESUME_IMPLEMENTATION when repository-owned
tests, fixtures, scripts, or configuration prevent the story from completing.
Use EXTERNAL only when the repository cannot safely repair or isolate the
dependency. Do not use BLOCK_REQUIREMENTS for a known story requirement that
is already clear in the issue and EPIC.

Re-verify every previously recorded blocker against the current files,
commands, process state, and GitHub state before reporting it as active. If a
later commit already repaired the condition, classify it as resolved evidence,
do not repeat it as the current blocker, and select the next required phase.
Never carry forward stale blocker text merely because it remains in an older
state or log record.
