{{COMMON}}

Phase: INTERNAL REVIEW FIX (test). A pre-commit test review found blocking
(BLOCKER/CRITICAL) issues in the current LOCAL, uncommitted diff. Fix ONLY those
blocking findings in the working tree. Do NOT commit, push, or open a PR, and do
NOT expand scope beyond fixing the reported issues.

Keep the fix minimal and focused. Preserve existing behavior except where the
finding requires a change. Update or add tests where the fix changes behavior.

RESULT_STATUS: SUCCESS | BLOCKED
RESULT_BLOCKER: NONE | <what could not be fixed and why>
RESULT_EVIDENCE: <what was changed>
