# Autonomous delivery hardening

Scope: preserve the existing SQLite journal, checkpoints and bounded worker model;
make completion evidence-driven and keep all mutations under controller policy.
The pre-existing working-tree changes are part of the baseline. Live job settings
must not be enabled by this implementation.

## Implementation ledger

- [x] Correct phase-specific outcomes, CI classification and story association.
- [x] Centralize mutation policy, bind merge to head, make dry-run read-only.
- [x] Add durable intent contracts and story-bound, versioned task plans.
- [x] Execute independent validation with revision-bound evidence and scope checks.
- [x] Isolate workers and remove inherited delivery credentials.
- [x] Bound waits, subprocesses, recovery and cancellation.
- [x] Enforce workflow transitions and reject unknown dispatch states.
- [x] Add bounded recovery memory, adaptive strategy and auditable metrics.
- [x] Route only registered story intents and deliver idempotent clarification requests.
- [x] Preserve cancellation outcomes, conservatively account for missing token usage,
  enforce worker resource ceilings, and explicitly deny network access to tool subprocesses.
- [x] Bind worker usage records to prompt, policy and runtime configuration digests.
- [x] Update mock coverage for fail-closed semantics; full regression suite intentionally not run per user instruction.
- [x] Document configuration, rollout requirements and known limits.

## Baseline

Baseline offline JUnit run before implementation: 380 tests, 13 failures. An
intermediate run after the first implementation pass reported 393 tests and 21
failures, largely from legacy assertions that expected delivery or review-finding
closure after retry exhaustion. The final intent-registry and documentation
changes were not tested, per user instruction. Mock coverage now includes intent
authority, validation, recovery, waits, process isolation, and delivery gates.
No live GitHub/model evaluation has been performed.

## Acceptance invariants

1. Planning success never authorizes code delivery.
2. Missing/failed required CI is never interpreted as success.
3. Dry-run cannot mutate target Git or GitHub state.
4. Merge must atomically match the verified PR head.
5. Plans and verification evidence are tied to the story and requirement version.
6. Worker declarations cannot substitute for required executable checks.
7. Cancellation, time, retry and resource limits apply across restart boundaries.
8. Recovery may change strategy but cannot weaken permissions or completion gates.
9. Unknown phases and invalid transitions fail closed.
10. Existing durable state and unfinished work remain recoverable.

## Operational limitations

- The example configuration deliberately disables mutations and has an empty
  verifier check list. Configure checks and repository protections before
  enabling delivery; the defaults remain fail-closed.
- Bubblewrap is the default worker isolation. Host support for namespace
  creation is an operational prerequisite and must be checked on the deployment
  host before enabling a live job.
- Mock tests do not validate actual GitHub, Codex, or host sandbox integration.
- Clarification comments use the existing `workflow.allow_comments` permission and
  are emitted once per requirement hash. Replies should update the issue body;
  that remains the authoritative input consumed by the controller.
- Bubblewrap workers now use a private network namespace. The model worker
  connects only to a per-run Unix-socket gateway, which restricts upstream
  origin, model, request count and hosted tools. The provider credential stays
  in the controller-side gateway; workers receive only a short-lived run token.
- A passing offline release gate is necessary but does not establish live
  GitHub, model-provider, or host-namespace behavior. Canary mode restricts
  the controller to one configured story, disallows merge, and records a
  revision- and configuration-bound receipt only after independent verification,
  CI, and review gates pass. Autonomous mode requires that receipt.
- Plans now advance through a durable task ledger. A worker result moves a task
  to awaiting verification; only controller-owned checks can mark it verified.
  Delivery still requires full-story verification. External mutations use an
  effect journal and authoritative read-back. Uncertain non-idempotent effects
  are held instead of replayed blindly.
- The example remains intentionally non-operational. Deployment must supply
  checks mapped to acceptance criteria, named CI checks, a provider credential,
  a canary story, and explicit mutation permissions. The host must support
  bubblewrap user/network namespaces and the configured model API.

## New rollout and recovery controls

- `--readiness` checks both policy completeness and local host prerequisites,
  including credential presence and a private-network loopback probe. A
  successful result means host-ready; it does not make a live model call.
- `--provider-canary` is an explicit, read-only integration operation. It
  validates the scoped gateway's streamed response and credential exclusion,
  then checks the actual Codex provider configuration. A passing receipt is
  bound to the runtime/policy fingerprint and is required for canary and
  autonomous admission. It expires after the configured validity window.
- Ambiguous issue intent remains blocked. Advisory ranked candidates cannot
  authorize work. A `jcoding-intent` contract may declare a two-capability
  ordered sequence; the validated task plan must follow that order. Trusted
  maintainer clarification comments are bound to the pending request hash.
- The task ledger permits bounded plan revisions after exhausted task retries.
  Only unchanged verified tasks whose dependencies remain verified survive a
  revision. A byte-identical replan is rejected; the goal and verifier policy
  remain controller-owned.
- Local commits and pushes have durable effect records. Uncertain external
  effects can be listed with `--list-effects`; an operator can grant exactly
  one replay after independently proving absence, with a recorded reason.
- The release gate requires the business-value scenario class and the controller
  repository's required check must be configured in GitHub branch protection.
  `scripts/check-release-protection.sh` verifies that external setting without
  changing it.

These changes were compiled and statically checked. Per user instruction, the
business-value mock and live canary operations were not executed in this pass.
- A configured token budget reserves its full remaining allowance when provider
  usage is unavailable, preventing a second unmetered call. The first such call
  still depends on the provider/runtime enforcing its own output ceiling.
