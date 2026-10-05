{{COMMON}}

Phase: IMPLEMENTING — targeted delivery recovery.

The controller's state.delivery_failure records the failed operation, paths,
evidence and recovery attempt count. The supervisor's decision_reason and
recovery_action describe the bounded repair it selected. Inspect current facts
and implement that repair within the active story repository. Reuse still-valid
test evidence, then verify any changed behavior with focused tests.

For a protected-path rejection, distinguish source code about credentials from
actual credential material. Never rename a secret to evade the guard, disable
protection, delete user data, or omit a required test to make delivery succeed.
If the path guard now permits a legitimate test/source file, validate the
existing change and report SUCCESS. If a controller defect remains outside the
active repository, report PROCESS with the exact path/rule and needed controller
repair. Do not change the controller from another repository's worker.

For commit/push or head-verification failures, inspect local and remote heads.
Preserve existing commits and uncommitted work. The controller owns staging,
commit, normal push, head verification and PR updates; do not perform those
operations yourself. Never force-push or reset to resolve a divergence. Report
unavailable credentials or access as EXTERNAL, without printing secret values.

SUCCESS requires current evidence that the repair is complete. Return the normal
RESULT_* footer and FIXED_THREAD lines only for findings actually verified.
Choose WAITING_FOR_CI or WAITING_FOR_CODEX_REVIEW after a verified repair so the
controller can retry delivery. If no safe in-scope repair exists, report BLOCKED
with the exact remaining condition; do not claim that repeated retries fix it.
