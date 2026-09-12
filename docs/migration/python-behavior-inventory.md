# Frozen Python behavior inventory

This inventory is derived from frozen revision `c8eaaf2756df3e3dcaf946d995a1ed1db56e93bf`.
The parity matrix maps every tracked artifact; this document records the
cross-cutting contracts that are otherwise easy to miss during migration.

## Configuration keys

| File | Keys |
|---|---|
| `config/config.toml.example` | `repository.local_path`, `repository.owner`, `repository.name`, `repository.base_branch`, `epic.number`, `github.token_environment_variable`, `github.cli`, `github.timeout_seconds`, `automation.dry_run`, `automation.stale_lock_seconds`, `automation.quota_retry_seconds`, `automation.watchdog_observations`, `automation.watchdog_grace_seconds`, `automation.delivery_verification_attempts`, `automation.delivery_verification_delay_seconds`, `codex.executable`, `codex.model`, `codex.reasoning_effort`, `codex.approval_policy`, `codex.dangerously_bypass_sandbox`, `codex.execution_timeout_seconds`, `codex.progress_interval_seconds`, `budgets.implementing`, `budgets.verifying`, `budgets.reviewing_code`, `budgets.fixing_feedback`, `budgets.reconciling`, `workflow.allow_push`, `workflow.allow_pr_creation`, `workflow.allow_comments`, `workflow.allow_thread_resolution`, `workflow.require_manual_merge` |
| `config/cron-policy.toml` | `interval_minutes`, `cron_entry`, `housekeeping_cron_entry`, `job_control_file`, `skip_when_running`, `stale_lock_seconds`, `terminate_long_running_process`, `log_directory` |
| `config/job-control.toml` | `job_start` |

## Workflow phases and result contracts

The implementation recognizes: `IDLE`, `IMPLEMENTING`, `VERIFYING`,
`REVIEWING_CODE`, `WAITING_FOR_CI`, `WAITING_FOR_CODEX_REVIEW`,
`WAITING_FOR_HUMAN_REVIEW`, `CHANGES_REQUESTED`, `FIXING_FEEDBACK`,
`READY_TO_MERGE`, `WAITING_FOR_MANUAL_MERGE`, `PAUSED_QUOTA`,
`BLOCKED_REQUIREMENTS`, `BLOCKED_EXTERNAL`, `CONFLICT_DETECTED`,
`RECONCILING`, `EPIC_COMPLETE`, `FAILED_RECOVERABLE`, and `CODEX_FAILURE`.

Worker results require `RESULT_STATUS`, `RESULT_NEXT_PHASE`,
`RESULT_BLOCKER`, and `RESULT_EVIDENCE`. Reconciliation additionally requires
`DECISION_ACTION`, `DECISION_REASON`, `BLOCKER_CLASS`, and `RECOVERY_ACTION`.
The controller accepts only enumerated values and complete evidence.

## State fields

The durable JSON compatibility state and SQLite journal cover execution and
ownership (`execution_id`, `pid`, `worker_status`, `current_job`, `phase`,
`phase_started_at`, `phase_attempt`, `heartbeat_at`), story/PR identity
(`current_story`, `current_story_url`, `current_story_title`, `current_pr`,
`current_pr_url`, `current_pr_head`, `branch`, `base_branch`), delivery and
review cursors (`last_processed_commit`, `last_processed_review_id`,
`last_feedback_id`, `last_review_trigger_commit`,
`last_review_trigger_verified_commit`, `review_gate_head`,
`last_connector_review_commit`, `review_trigger_pending`, `thread_updates`),
outcomes (`result_status`, `result_evidence`, `exit_code`, `error`,
`raw_error`, `pr_state`, `pr_review_decision`, `ci_status`), recovery
(`blockers`, `blocker_class`, `last_resolved_blockers`, `reconciliation_attempts`,
`worktree_recovery`, `watchdog_event`, `retry_at`, `quota_state_repaired`),
and completion (`completed_stories`, `unresolved_feedback`, `updated_at`).

## External commands and side effects

| Boundary | Commands/operations | Required safety behavior |
|---|---|---|
| GitHub | `gh issue view`, `gh pr list`, `gh pr view`, `gh pr comment`, GraphQL review/thread queries and mutations | token from environment, timeout, redaction, idempotent comments, explicit merge/review/thread state |
| Git | fetch, branch/worktree inspection, commit, push, branch creation | synchronized base, focused clean diff, no force-push, delivery head equality |
| Codex | bounded `codex exec` subprocess | phase budget, heartbeat, timeout/signal classification, quota pause, structured result |
| Filesystem | TOML/JSON config, JSON state, SQLite WAL journal, JSONL events, lock directory, logs | atomic writes, durable-before-side-effect state, ownership checks, no secrets in logs |
| Host tools | Python/pytest, shell, `docker` when project validation requires it | classify unavailable infrastructure accurately; never claim skipped checks passed |

## Failure and recovery rules

- Missing credentials, inaccessible GitHub, unavailable required tools, and
  unrecoverable infrastructure are external blockers.
- Dirty or ambiguous story ownership, malformed state, stale-head data, and
  missing evidence fail closed and require reconciliation.
- Codex signals/timeouts are recoverable only after repository and PR
  reconciliation; quota errors pause until the recorded retry time.
- A running job owns the lock through heartbeat; abandoned jobs are recovered
  only after the stale threshold and fact-based inspection.
- A story advances only after GitHub explicitly reports its PR merged.
- A PR is ready only for manual merge; the automation never merges, closes
  issues, deletes data, or bypasses controls.
