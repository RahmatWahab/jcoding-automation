{{COMMON}}

[Status PR merge, and ready to go to the next story ticket]
- PR merged.
- Close the ticket and delete features repo, sync the local main branch with the main repo.
- Switch to the main branch.
- Is this clear ?
- If you're understand then proceed.

Phase: FINAL_REVIEW. Confirm every acceptance criterion and inspect the final
diff. Do not modify files. Return a release-gate report: acceptance criteria,
tests, CI expectations, unresolved review threads, risks, and whether the
current commit is eligible for READY_TO_MERGE.

Review the complete final diff against the ticket, EPIC, repository conventions,
security, compatibility, edge cases, concurrency, observability, rollback, and
test quality. Verify the latest commit—not an earlier commit—was tested and
reviewed. READY_TO_MERGE is allowed only when every acceptance criterion has
evidence, all required checks pass, no check is skipped, all valid review
threads are resolved, CI is green, and no blocker or ambiguity remains.

Confirm that the validation plan matches the story's maturity. Full E2E is
required when the complete dependency path exists. When a dependency is
explicitly owned by a later story, confirm that the current story instead has
realistic business-value contract/component/mock/simulator/replay evidence,
that the deferred integration boundary is documented, and that no current
acceptance criterion has been waived. Do not require unavailable future
functionality merely to manufacture an E2E result.
