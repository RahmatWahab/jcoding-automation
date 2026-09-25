{{COMMON}}

Phase: INTERNAL REVIEW FIX (general). The pre-commit general review found
BLOCKER/CRITICAL findings in the current LOCAL, uncommitted diff. Fix ONLY those
blocking findings, in the working tree. Do NOT commit, push, or open a PR.

Rules:
- Address every BLOCKER/CRITICAL finding the general review reported; ignore
  advisory MAJOR/MINOR unless trivially safe.
- Make the SMALLEST change that resolves each finding. Preserve existing behavior
  except where the finding itself requires a correctness/security change.
- Do NOT expand scope, refactor unrelated code, change public APIs, or add
  dependencies beyond what the fix strictly needs.
- If a fix changes behavior, add or update a test that proves the corrected
  behavior and would fail on the old code.
- If a reported finding cannot be safely fixed within scope, leave it and explain
  why under RESULT_BLOCKER rather than forcing an unsafe change.

The general review will run again after this fix to confirm the findings are
resolved.

RESULT_STATUS: SUCCESS | BLOCKED
RESULT_BLOCKER: NONE | <any finding that could not be safely fixed and why>
RESULT_EVIDENCE: <what was changed, per finding>
