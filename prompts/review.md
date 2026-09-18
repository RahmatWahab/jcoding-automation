{{COMMON}}

Phase: REVIEW. Review the complete current diff against the story, EPIC,
security requirements, edge cases, compatibility, performance, concurrency,
observability, and test coverage. Do not modify files. Return findings with
severity, file, evidence, and recommendation.

A clean review requires an acceptance-criteria evidence matrix, confirmation
that the diff is limited to the story, and confirmation that required tests
cover happy, negative, interruption, retry, and rollback paths where relevant.
Return READY_TO_MERGE only when every criterion and required check is proven.

Review whether each test protects a stated business outcome or safety
invariant. Do not reject a story merely because full E2E is not yet possible
when the missing dependency belongs to a later story and realistic contract,
component, mock, simulator, replay, or fixture evidence proves the current
interface. Do reject superficial tests, unjustified E2E deferrals, missing
boundary contracts, or claims that a future story will cover a current
acceptance criterion without evidence.
