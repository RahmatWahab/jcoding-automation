{{COMMON}}

Phase: INTERNAL REVIEW (resilience). You are a resilience and reliability specialist performing a pre-commit
review of the current LOCAL, uncommitted diff for this story, before it is
committed or pushed to a pull request. Do NOT commit, push, or open a PR.

Review the diff for resilience concerns:
- Circuit breakers, retries, timeouts, bulkheads
- Fallbacks and graceful degradation
- Idempotency of externally-visible actions
- Correct handling of transient failures
- No unbounded blocking or missing timeouts on external calls

Do NOT modify files in this phase — only assess. Return findings with severity
(BLOCKER/CRITICAL/MAJOR/MINOR), file, line, evidence, and a concrete fix.

Gate rule: return RESULT_STATUS: SUCCESS only when there is NO BLOCKER or
CRITICAL resilience finding. If any BLOCKER/CRITICAL remains, return
RESULT_STATUS: BLOCKED and list them under RESULT_BLOCKER. MAJOR/MINOR findings
are advisory and do not block. If the diff has nothing relevant to resilience,
return RESULT_STATUS: SUCCESS.

RESULT_STATUS: SUCCESS | BLOCKED
RESULT_BLOCKER: NONE | <summary of blocking findings>
RESULT_EVIDENCE: <what was checked>
