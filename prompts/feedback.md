{{COMMON}}

[Validate PR comments thread]
- There are feedback in PR, are those valid ?
- Why we missed out the bug and what lesson we can learn to avoid the same mistake again, and keep the lesson learn in your memory ?
- If its valid then fix it fully, no half-baking, no guessing and no assuming. after complete, run a full deep review use your code-review skills and testing skills. then re-commit to PR and I will need to review the codes.
- Update threads comments with the fix information and close/resolve those thread.
- Add in the PR the new comments "@codex review".
- Did I make a clear ?
- If you're understand then proceed.

Phase: FEEDBACK. Process only the supplied new PR feedback or CI failure.
Verify each finding against the story and source. Fix valid findings completely
and add regression tests where appropriate. Do not implement ambiguous feedback.
Run relevant validation and report the result.

Treat each finding as an independent work item. First identify the affected
file, behavior, and business risk. Run a focused regression test for that
behavior before launching any broad suite. If it reproduces the finding, fix it
and rerun that test before broader validation. If it does not reproduce,
inspect the implementation and review context rather than guessing.

Do not rerun a full E2E lane when a deterministic unit, component, contract, or
fixture test proves the affected property. E2E is never required to commit a
feedback fix: if a focused unit or mock/contract test proves the finding is
resolved, that is sufficient to report RESULT_STATUS: SUCCESS with
RESULT_EVIDENCE: COMPLETE, and the controller will commit the fix. Only run E2E
when it is genuinely achievable and cheap enough for the budget; when it is
impossible, unavailable, or owned by a later story, record it as a deferred
note and still report COMPLETE. Process unresolved findings attached to the
supplied PR head, but do not rework a finding already proven fixed on a newer
delivered head. Report the review-thread ID, original commit, current head,
decision, and test evidence for every finding.

Do not commit or push changes; the external controller performs those actions
only when its configuration explicitly permits pushing.

Process every unresolved valid finding, including inline comments from review
bots. Preserve unrelated behavior. For each finding, record VALID, INVALID, or
BLOCKED with file/line evidence and reasoning. After fixes, rerun the complete
relevant verification and deep review; a partial fix or a skipped check is not
SUCCESS. Do not resolve a review thread merely because code was changed; it may
be resolved only after the finding is demonstrably addressed.
