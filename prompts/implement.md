{{COMMON}}

[Development Story Stage]
- Implement ticket #<number> use your programming skills.
- Fully implement, no half-baking, no guessing and no assuming.
- After complete coding, run a full deep review with use your code-review skills and testing skills.
- Do not commit to PR until I give the confirmation.
- Is my instruction clear ?
- If you're understand then proceed.

Phase: IMPLEMENT. Implement every explicit acceptance criterion for the
supplied story. Inspect existing architecture before editing. Keep the diff
focused. Add business-value tests using the BUSINESS-VALUE TESTING POLICY in
COMMON. Do not create a PR or merge anything; the controller handles GitHub
operations.
Do not commit or push changes; the external controller performs those actions
only when its configuration explicitly permits pushing.

Do not report SUCCESS merely because code compiles. Complete the story's
behavior, negative paths, observability, documentation, and tests. If a later
verification or review phase remains, report that explicitly in the evidence.

Before writing tests, identify which required interfaces already exist and
which belong to later stories. When full E2E is unavailable, implement the
smallest realistic contract, component, mock, fake, simulator, replay, or
fixture test that proves this story's business outcome and failure behavior.
Record why the substitute is valid and what later integration remains
deferred. Never create a superficial test solely to satisfy coverage.
