# jcoding-automation

A stateful, cron-driven controller for bounded Codex CLI coding workflows,
implemented in Java 21. It drives a full software-delivery loop against a single
GitHub EPIC: it selects a linked story, runs the Codex CLI as a bounded worker
to implement, independently verify, and review it, opens a pull request, and
manages the review cycle. Final merge is either held for a human or performed
under the configured evidence and mutation gates.

The controller owns all authority — scheduling, locking, durable state, GitHub
reconciliation, and the merge gate. Codex runs only as a constrained,
time-boxed worker.

## What it does

- **Picks up work from a GitHub EPIC.** It reads one EPIC issue and the stories
  linked to it, selects the next eligible story, and drives it through the whole
  delivery loop automatically on a cron tick.
- **Understands intent before acting.** Each story is captured as a versioned
  goal contract (requirements, acceptance criteria, permissions). Supported
  intents are `implement_story` and `repair_story`; an unknown or ambiguous
  intent is blocked for clarification instead of guessed.
- **Plans, then implements.** When planning is enabled it builds a task plan
  bound to the requirements, then runs the Codex worker to implement each task
  in order, persisting a checkpoint after each one.
- **Runs the worker in a sandbox.** The Codex worker executes inside Bubblewrap
  isolation with a private network, resource limits, and no delivery
  credentials; it reaches the model only through a controller-side scoped
  gateway.
- **Verifies independently.** Delivery requires the controller to run your
  configured verification commands (tests, linters, builds) against the exact
  delivered revision — a worker saying "done" is never accepted as proof.
- **Opens and manages the pull request.** It creates the PR, triggers Codex
  review, and runs the feedback → fix → re-review cycle, optionally through a
  pre-commit panel of specialist reviewers (`internal_review`).
- **Gates the merge.** With `auto_merge: false` (default) it posts
  `READY TO MERGE` and waits for a human. With `auto_merge: true` it merges only
  after verification, required CI, review, and unresolved-thread gates all pass
  and the PR head still matches the verified delivery.
- **Is durable and recoverable.** All state lives in SQLite; an interrupted run
  resumes from the last safe checkpoint, with bounded budgets, locking, and
  automatic reconciliation.
- **Can also run a local request** (`--request-file`) to implement a task in a
  sibling worktree without touching GitHub.

## What it does not do

- It does not bypass GitHub branch protection or any configured gate.
- It does not merge by default, and never force-merges.
- It does not hand the model provider credential to the worker.
- It does not act on an ambiguous or unsupported intent — it asks first.
- It does not make any commit, push, comment, PR, or merge while
  `automation.dry_run = true` or the relevant `workflow.allow_*` flag is `false`.

> This build is validated by an offline, mock-only test suite; real `gh` and the
> real `codex` worker are exercised by your first dry-run, not by the tests.

## Requirements

| Tool | Purpose |
|------|---------|
| Java 21 (JDK) | Build and run the application |
| `git` | Repository operations |
| `gh` (GitHub CLI) | Authenticated GitHub access |
| `jq` | JSON parsing in the cron/install scripts |
| `flock`, `setsid` | Lock and isolated process handling (Linux) |
| `codex` | The Codex CLI worker executable |
| `bwrap`, `prlimit`, Python 3 | Private-network worker, resource limits, and scoped model gateway |

You also need two credentials in your environment before a live run (not for
build or test):

| Credential | Purpose | How to get it |
|------------|---------|---------------|
| `GH_TOKEN` | GitHub access for `gh` (read issues/PRs, and push/comment/merge when enabled) | Create a GitHub Personal Access Token with `repo` scope at GitHub → Settings → Developer settings → Fine-grained/Classic tokens |
| `OPENAI_API_KEY` | The model provider key used by the Codex worker gateway (name is configurable via `codex.gateway.credential_environment_variable`) | Create an API key in your OpenAI account dashboard |

On Debian/Ubuntu you can install the OS tools with:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk git jq util-linux bubblewrap python3
# `flock`, `setsid`, and `prlimit` are part of the `util-linux` package.
# Install the GitHub CLI (`gh`) and the Codex CLI (`codex`) from their own
# official instructions — they are not in the default apt repositories.
```

No network access is required to build or test the project; the test suite is
fully offline and does not contact GitHub or launch `codex`.

## Install

```bash
git clone <this-repo-url> jcoding-automation
cd jcoding-automation

# Build the runnable application distribution.
./gradlew installDist

# Create your local configuration from the template.
cp config/config.json.example config/config.json
mkdir -p state logs
chmod +x scripts/*.sh
```

The built launcher is at
`build/install/jcoding-automation/bin/jcoding-automation`. The scripts under
`scripts/` build it automatically on first run if it is missing.

## Quick start: guided setup (`scripts/go-autonomous.sh`)

The easiest way to configure and roll out the controller is the interactive
wizard. It writes `config/config.json` for you (using `jq`), so you do not have
to hand-edit the JSON or decode readiness errors.

```bash
scripts/go-autonomous.sh
```

On the first run it copies `config/config.json.example` to `config/config.json`.
On later runs it updates the existing file and saves a timestamped backup
(`config/config.json.bak-<UTC timestamp>`).

It asks for:

| Prompt | Notes |
|--------|-------|
| GitHub repository URL | `https://github.com/<owner>/<name>` (a `.git` suffix is accepted) |
| Local checkout path and base branch | Must be a separate tree from this installation |
| Goal: `canary` or `autonomous` | See below |
| EPIC number | The EPIC the controller drives |
| Canary story number | One story under the EPIC, used for the one-time canary |
| Verification command | Auto-detected from the checkout (Gradle, Maven, npm, Go, pytest, `make test`); press Enter to accept. Runs in a sandbox with **no network**, so dependencies must already be cached locally |
| Required CI check name(s) | Auto-detected from the base branch's real GitHub check runs (fallback: job names in `.github/workflows`); press Enter to accept. If the repo has no CI, add a workflow first — unattended auto-merge needs at least one named check |
| Workflow flags (true/false) | push, PR creation, comments, thread resolution, internal review, dry-run |
| Safety settings | `codex.approval_policy` and `codex.dangerously_bypass_sandbox` |

Then it walks the rollout sequence: readiness check → provider canary → canary
story → flip to autonomous. Every step after the readiness check asks for
confirmation.

Things to know:

- **Autonomous always starts with a canary.** Autonomous mode refuses to run
  without a canary receipt, and the receipt can only be earned in canary mode on
  a real story (CI, review, and verification must pass). Choosing "autonomous"
  therefore starts in `canary` with `auto_merge: false`. After the canary PR
  passes, run the script again and answer yes to the final step; it sets
  `deployment.mode: autonomous` and `workflow.auto_merge: true`. From then on the
  controller selects stories from the EPIC by itself.
- **Safety settings stay at their only accepted values.** The controller rejects
  any `approval_policy` other than `never` and any
  `dangerously_bypass_sandbox: true` (`BLOCKED_CONFIGURATION`). The wizard
  warns and keeps the safe values if you try to change them.
- **Changing config later can invalidate the receipt.** Flipping `auto_merge` is
  safe, but changing the repository, codex, verification, workflow, intent, or
  automation settings requires a new canary. The receipt also expires after
  `deployment.canary_valid_hours`.
- **Before the canary story runs**, set `config/job-control.json` to
  `{ "job_start": true }` and provide `GH_TOKEN` and `OPENAI_API_KEY` (see
  below).
- The wizard writes the check's `criteria` as `["*"]`, meaning "this check covers
  every acceptance criterion in the story being verified". The check must still
  run and exit 0. List explicit IDs (`["AC-1","AC-2"]`) instead if you want
  per-criterion checks; an ID the story does not define fails verification.

## Configure

All configuration is JSON. The wizard above produces a valid file; the sections
below describe each setting if you prefer to edit it by hand.

1. Edit `config/config.json` — set the target repository, EPIC number, Codex
   model, budgets, and workflow flags. The example defaults to
   `automation.dry_run = true` and all mutation flags `false`, so nothing is
   pushed or commented until you explicitly enable it.

   The merge policy is the single flag `workflow.auto_merge` (default `false`).
   When `false`, the controller never merges: it posts a `READY TO MERGE`
   comment on a ready PR and waits for a maintainer to merge. When `true`, the
   controller may merge only after independent configured verification, required
   CI, review, and unresolved-thread gates pass, and only if the PR head still
   matches the verified delivery. This flag does not bypass repository branch
   protection or any other gate.

2. `config/job-control.json` — the execution gate:

   ```json
   { "job_start": true }
   ```

   Any value other than boolean `true` skips execution safely.

3. `config/cron-policy.json` — the cron schedule and housekeeping policy,
   consumed by `scripts/install-cron.sh`.

### GitHub token

Set the token in the environment; never put it in configuration or source.

```bash
export GH_TOKEN="your-token"
gh auth status
```

Cron does not inherit interactive-shell exports. For cron, create a protected
environment file outside the repository (mode `600` is required). Put **both**
credentials in it:

```bash
mkdir -p "$HOME/.config/jcoding-automation"
cat > "$HOME/.config/jcoding-automation/env" <<'EOF'
GH_TOKEN=ghp_your_github_token_here
OPENAI_API_KEY=sk-your_openai_key_here
EOF
chmod 600 "$HOME/.config/jcoding-automation/env"
```

The cron launcher loads this file automatically. Use
`CODEX_AUTOMATION_ENV_FILE=/path/to/file` to select another protected file. The
launcher only accepts the keys `GH_TOKEN`, `OPENAI_API_KEY`, and `CODEX_API_KEY`
and refuses a file that is not mode `600`.

### Model provider key (OPENAI_API_KEY)

The Codex worker reaches the model only through a controller-side gateway, and
that gateway needs the provider key. Supply it to the **controller** environment
(the worker never receives it — it gets a short-lived scoped token instead):

```bash
export OPENAI_API_KEY="sk-your-openai-key"
```

The environment variable name is configurable with
`codex.gateway.credential_environment_variable` in `config/config.json` (default
`OPENAI_API_KEY`). A live run or `--readiness` check fails closed if this key is
missing.

## Run

```bash
# One-shot run; honors the job_start gate.
scripts/run_once.sh

# Bypass the gate for a manual run (still respects dry_run).
scripts/run_once.sh --force-run

# Verbose (DEBUG) logging, including per-call GitHub durations.
scripts/run_once.sh --verbose
scripts/run_once.sh --verbose --force-run
```

You can also run the built launcher directly:

```bash
build/install/jcoding-automation/bin/jcoding-automation \
  --config config/config.json --job-control config/job-control.json
```

Command-line options: `--config`, `--job-control`, `--prompt`, `--force-run`,
`--readiness`, `--provider-canary`, `--list-effects`, `--retry-effect`, and
`--effect-reason`.

For unattended operation, declare one registered intent on each story with an
`intent:implement_story` or `intent:repair_story` label, or a `jcoding-intent`
JSON block in the issue body. An absent or conflicting intent blocks the story
for clarification. An explicit `intent.default_intent` is available for a
repository whose stories all use the same contract. Acceptance criteria must
have IDs and each configured executable verifier must list the IDs it covers.
The controller executes each planned task in order, persists task checkpoints,
and verifies its result before advancing. The final delivery gate reruns the
full configured verification.

The example configuration intentionally lacks repository-specific checks and
CI names. Before a live run, provide `verification.checks` with executable
commands and `criteria` mappings, plus `workflow.required_checks` with exact
GitHub check names. Supply `OPENAI_API_KEY` (or the environment variable named
by `codex.gateway.credential_environment_variable`) to the controller only.
The isolated worker receives a short-lived gateway token, not that credential.

Use `deployment.mode: canary` with `deployment.canary_story` set to one story
number and `workflow.auto_merge: false` for the first controlled integration
run. Inspect `--readiness`, run the explicit read-only `--provider-canary`,
and require the offline `autonomyReleaseGate` before the
canary. The canary records a receipt after executable verification, CI, and
review gates pass. Only then change `deployment.mode` to `autonomous`; any
change to the bound policy, runtime, model, or verifier invalidates the receipt.
The provider canary makes two bounded model calls: one validates streaming and
credential isolation in the private-network gateway, and one validates the
actual Codex CLI provider configuration. It writes only controller state and
expires after `deployment.provider_canary_valid_hours` (default 24).
Repository branch protection must require the `Business value mocks and static
policy` check. Run `scripts/check-release-protection.sh main` after configuring
that rule in GitHub; the script is read-only and fails if the check is absent.

An uncertain external mutation is listed with `--list-effects`. After checking
the remote issue, PR, or thread and proving the effect absent, an operator may
use `--retry-effect <sha256-key> --effect-reason '<evidence>'`. That one-shot
grant is consumed before the retry; the controller still performs remote
read-back before it acts. Issue clarification can also be answered by a full
`jcoding-intent` JSON block in a maintainer comment containing the exact
`jcoding-answer` marker printed in the controller's clarification request.

### First run (recommended: dry-run)

Deploy safely by leaving `automation.dry_run = true` and setting
`config/job-control.json` to `{ "job_start": true }`. A dry-run reads real
GitHub state (EPIC, stories, PRs) and prints the phase it *would* run, but makes
**no** commits, pushes, comments, PRs, or thread changes. Confirm the health
check passes first:

```bash
scripts/healthcheck.sh   # verifies config parses, target repo exists and is clean, tools present
scripts/run_once.sh      # first manual dry-run
```

### Enable live execution

Only after reviewing a dry-run, enable real GitHub actions. Each action is
gated by a distinct flag; enable the ones your workflow needs. To run the full
implement → PR → review → feedback loop, set in `config/config.json`:

```json
{
  "automation": {
    "dry_run": false,
    "max_feedback_batch_size": 3
  },
  "workflow": {
    "allow_push": true,
    "allow_pr_creation": true,
    "allow_comments": true,
    "allow_thread_resolution": true,
    "auto_merge": false
  }
}
```

What each flag unlocks (all default to `false`):

| Flag | Enables |
|------|---------|
| `allow_push` | committing and pushing worker changes |
| `allow_pr_creation` | opening a pull request for a ready story |
| `allow_comments` | posting the `@codex review` trigger and fix replies |
| `allow_thread_resolution` | resolving review threads after a verified fix |
| `automation.max_feedback_batch_size` | maximum number of compatible unresolved findings revalidated and fixed in one worker execution (default `3`) |
| `auto_merge` | merge policy (single flag). `false` (default): the controller never merges — it posts `READY TO MERGE` and waits for a maintainer. `true`: the controller merges only after configured executable verification, required CI, clean review, no unresolved threads, and a verified PR head that still matches at merge time. Missing checks or verification configuration fail closed. It never force-merges or bypasses branch protection. Optional `merge_method` selects `squash` (default), `merge`, or `rebase` — the method must be enabled in the repo's merge settings. |
| `internal_review` | pre-commit specialist review gate (default `false`). When `true`, after a worker `SUCCESS` on an implement/feedback phase the controller runs a sequential panel of specialist reviews (general, test, and — diff-gated — simplification, architecture, security, performance, database, deployment, resilience) against the **local, uncommitted diff** before committing/pushing. Each specialist runs review → bounded fix → re-review; issues are caught privately instead of surfacing as post-PR Codex review threads. Fully autonomous and bounded: a specialist that cannot be satisfied within `automation.internal_review_max_fix_attempts` (default `2`) is carried forward as advisory and delivery still proceeds — the post-PR Codex review remains the final authority. Requires `allow_push`/`allow_pr_creation` for the eventual delivery; under `dry_run` it makes no mutations. |

and in `config/job-control.json`:

```json
{ "job_start": true }
```

> **Commits only happen with `dry_run: false` and `allow_push: true`.** A
> mutating worker proposes typed file operations from a read-only sandbox. The
> controller applies authorized operations in its isolated worktree, then checks
> the configured verification commands. Dry runs cannot apply worker edits.

For a local request, pass `--request-file /absolute/path/request.json`. The
JSON needs a title and acceptance criteria (`[{"id":"AC-1","text":"..."}]`),
and may include stable requirement IDs and `supersedes`. With `dry_run: false`,
Bubblewrap isolation, and configured verifier checks, the controller writes
`state/local-result-<story>.json` and a content bundle in `state/`. It never
pushes a local request. An interrupted request resumes from SQLite; an ambiguous
partially applied tool effect blocks for inspection. The configured repository
is used only as the source of a persistent sibling worktree, so staged,
unstaged, and untracked edits in the operator checkout remain untouched.

### Internal review gate (pre-commit specialist review)

When `workflow.internal_review = true`, the controller runs a **sequential panel
of specialist reviewers against the local, uncommitted diff** after a worker
`SUCCESS` on an implement/feedback phase, before anything is committed or pushed.
The intent is to catch issues privately so they do not surface as a flood of
post-PR Codex review threads.

- **Sequential, one specialist at a time** (the controller is single-threaded per
  run): general → simplification → architecture → test → security → performance
  → database → deployment → resilience. Each specialist runs
  review → bounded fix → re-review → pass, then the next begins.
- **Diff-gated**: `general` and `test` always run; the rest run only when the
  changed files match their trigger patterns (e.g. `security` on auth/input/crypto
  paths, `database` on migrations/entities/SQL, `deployment` on Docker/Helm/CI).
- **Bounded and evidence-based**: each specialist's fix loop is capped by
  `automation.internal_review_max_fix_attempts` (default `2`). Findings are sent
  verbatim to the fix worker and retained with the exact reviewed diff identity.
  After any mutation, applicable reviewers run again against the new diff.
- **Fail-closed**: missing prompts, failed reviewer processes, unstable diff
  identity, or unresolved findings prevent commit and push. The story is held
  with its checkpoint; changed repository evidence wakes recovery. Under
  `dry_run` it makes no mutations.

Each specialist is a prompt pair under `prompts/` (`review-<name>.md` and
`review-<name>-fix.md`). Enabling this gate adds one bounded `codex` review pass
per relevant specialist per story (plus fix passes when findings are raised), so
it trades extra worker time for fewer post-PR review threads.

### Intent, plans, and independent verification

Each selected issue is captured as a versioned goal contract containing the
repository, issue, full requirements, acceptance criteria, questions, controller
permissions, allowed actions, and a requirements hash. The worker cannot expand
that authority. A fenced `jcoding-intent` JSON block may declare an intent and
criteria; supported intents are `implement_story` and `repair_story`. Unknown
intents and missing required criteria enter `BLOCKED_REQUIREMENTS` with a
clarification request rather than being guessed at. Requirement edits invalidate
the saved plan and verification evidence.

When planning is enabled, a schema-versioned `plan.json` must bind to the story,
requirements hash, and base revision. Its dependency graph must be acyclic and
its tasks must cover the acceptance criteria and configured check IDs. Planning
success only authorizes implementation planning; it never authorizes delivery.

Delivery requires independently executed commands listed in
`verification.checks`, each with a stable ID and command. Configure meaningful
checks and any required GitHub `workflow.required_checks` before enabling push or
merge. Checks run with a bounded timeout and output, restricted filesystem view,
and no network; evidence is tied to the source revision and policy hash. The
verifier also enforces allowed/protected paths and a changed-file ceiling. With
the template's empty check list, required verification intentionally blocks
delivery until checks are configured. A worker's `SUCCESS` response is never a
substitute for executable evidence.

Worker isolation defaults to Bubblewrap in the example configuration. Install
`bwrap` and ensure the host permits its user and network namespaces. If the
isolation cannot be established, the worker fails closed. `native` isolation is
available for controlled development only; use it only where the host boundary
is already trusted. Worker processes do not receive GitHub delivery credentials.

The intent contract and plan are durable in the existing story state, while
verification evidence is revision-bound. The controller resumes from persisted
wait deadlines and recovery budgets, honors job cancellation at action
boundaries, and changes recovery strategy without relaxing permissions.

### Worker budgets and recovery

Every model call, including supervisor and specialist calls, is counted against
a persistent per-story budget. Defaults are `automation.max_story_worker_calls`
`24` and `automation.max_story_active_seconds` `7200`. Set
`automation.max_story_tokens` to a positive ceiling to enforce reported input
plus output tokens; `0` disables the token ceiling. Missing provider usage is
recorded as unavailable, while call and elapsed-time limits still apply. A
crashed call consumes its reserved timeout so restarts cannot evade the budget.
Set `automation.recovery_epoch` to a larger integer only when intentionally
granting an existing story a fresh budget; the previous ledger is retained.

Each Codex JSON event stream is parsed for final turn usage and concise failure
diagnostics. Per-call duration, prompt size, exit code, token usage, redacted
output, and diagnostics are stored in SQLite's `worker_usage` table. This makes
token/time comparisons auditable and separates the model's final answer from
tool chatter.

### Worktree handoff and preflight recovery

Parking a story preserves staged, unstaged, and untracked source changes in a
local Git stash pinned by `refs/jcoding/checkpoints/story-<number>-<id>` before
clearing its active state. `.codex/` metadata stays in place. The durable
`worktree_checkpoints` journal records ownership, base commit, paths, backup ref,
and capture/restore status. Existing parked runs are recovered the same way
before branch checkout. Backups are never pushed or automatically dropped.

On resuming the original story, its checkpoint is restored only on the recorded
branch and base commit. Interrupted captures are recovered from the named stash;
interrupted restores and changed bases require inspection when exact restoration
cannot be established. Unowned changes are never carried to a different branch
or silently hidden in an anonymous stash.

If preparation is still blocked, a separate **read-only preflight supervisor**
runs without first requiring a writable story worktree. It receives the actual
and expected branch, persisted blocker, story/PR context, and previous diagnosis.
It cannot authorize delivery or waive a guard. The controller records its root
cause and next action in `preflight_failure`, then rechecks the prerequisite on
the next tick. Known parked-work handoffs recover automatically; ambiguous work,
changed-base restoration, and unavailable prerequisites remain protected.

`automation.max_preflight_diagnoses` defaults to `2`, with
`automation.preflight_diagnosis_seconds` defaulting to `120` per call, also subject
to the cumulative story budget. Unchanged failures then enter
`WAIT_FOR_PREFLIGHT_EVIDENCE` without further model calls. Changed branch, worktree,
blocker, or configuration wakes diagnosis; operators can increment
`automation.recovery_epoch` after repairing an external prerequisite. GitHub
read success alone no longer erases the original blocker.

### Delivery recovery

Delivery failures persist in `state.delivery_failure` with the failed operation,
protected paths (when applicable), error evidence, and a retry budget independent
of PR feedback. The next run routes this blocker to the supervisor before normal
feedback processing. An accepted resume decision dispatches `delivery-repair.md`
to validate or repair the active story and retry delivery. A supervisor decision
does not count as delivery or resolve review threads.

`automation.max_delivery_recovery_attempts` defaults to `4` total supervisor and
repair dispatches. Unchanged failures then hold without launching more workers;
changed worktree contents, PR head, or a corrected protected-path policy wake
recovery automatically. Access failures may require credentials/infrastructure
repair and an operator to clear `delivery_failure` before retrying if repository
evidence has not changed. The worker must not bypass guards or modify a controller
outside its active repository. State is cleared after successful delivery or when
the story is cleared.

Staging checks allow credential-related source filenames under `src/`, `tests/`,
`test/`, and `scripts/`, including `test_manager_api_token_provisioning.sh`.
Secret directories, `.env` paths, key/certificate files, credential-like data
files, and credential-named symlinks remain blocked. This is a path heuristic,
not content-based secret detection. The preflight runs before specialist reviews
and is checked again immediately before staging. Recovery also retries a normal
push when a prior commit succeeded but its push failed, then verifies the head.

### Per-phase model and reasoning effort

The `codex` section sets the base `model` and `reasoning_effort` used for every
worker phase. An optional `codex.phase_overrides` object lets you tune the model
and/or reasoning effort **per workflow phase**, so cheap mechanical phases can run
fast while reasoning-heavy phases (reconcile, planning, review) run stronger:

```json
"codex": {
  "model": "gpt-5.6-luna",
  "reasoning_effort": "medium",
  "phase_overrides": {
    "IMPLEMENTING":   { "reasoning_effort": "medium" },
    "REVIEWING_CODE": { "reasoning_effort": "high" },
    "RECONCILING":    { "model": "gpt-5.6-luna", "reasoning_effort": "high" },
    "FIXING_FEEDBACK":{ "reasoning_effort": "low" }
  }
}
```

- **Partial overrides**: a phase entry may set only `reasoning_effort` (or only
  `model`) and inherit the rest from the base `codex` section.
- **Backward-compatible**: omit `phase_overrides` entirely and every phase uses
  the base settings exactly as before.
- **Validated at load**: override keys must be known workflow phases and any
  `reasoning_effort` must be a valid value (`low`, `medium`, `high`, `xhigh`,
  `none`); an unknown phase or invalid effort fails config validation with a
  clear message. Override keys that do not dispatch a `codex` worker (waiting/
  terminal phases) are accepted but have no effect.
- The effective model and effort for each run are printed in the `CODEX START`
  log line, so per-phase resolution is observable.

The example config lists all `codex`-invoking phases pre-seeded to the base
defaults, ready to edit.

### Working tree and branch handling

Before each worker phase the controller checks out the active story branch and
classifies the working tree, so it never validates or commits while sitting on
the default branch:

- **Clean** or **controller-owned changes on the story branch** → proceed
  (interrupted work is resumed in place).
- **Uncommitted changes on the default branch**, or changes on a branch that is
  not the active story branch → routed to bounded reconciliation
  (`reconcile.md`) with a clear operator message, **not** a permanent block.

For the automation to cut a fresh story branch, the base branch (e.g. `main`)
must be clean. Stray untracked or modified files on the base branch are reported
for reconciliation; commit them to a branch or remove them, then rerun.

### Deliver-then-CI for heavy validation

Some fixes are complete but cannot finish a heavy end-to-end/integration lane
within the phase budget. In that case the worker returns
`RESULT_STATUS: SUCCESS`, `RESULT_EVIDENCE: DEFERRED_TO_CI`, and
`RESULT_NEXT_PHASE: WAITING_FOR_CI`. The controller then commits and pushes the
fix and waits for CI to complete the remaining lane, instead of looping on an
unvalidated-but-uncommitted change. CI and review still gate the PR, and the
configured merge policy remains in force. A worker must use `DEFERRED_TO_CI`
only when the fix itself is proven by the strongest safe
in-budget substitute; an unproven fix must report `BLOCKED`.

Recovery is bounded and evidence-gated. Repeated resume decisions never count
as proof that implementation or validation succeeded, and reaching a retry cap
does not authorize a commit, PR-thread resolution, or story completion. When no
safe recovery is supported by current evidence, the controller retains the
story and worktree in a held or blocked state. New commits, changed worktree
evidence, or corrected configuration can wake the story automatically.

> Note: this Java build's behavior is validated by an offline, mock-only test
> suite. Real `gh` responses and the real `codex` worker have not been exercised
> by automated tests, so a dry-run first launch is the recommended real-world
> integration check for the GitHub-read path.

## Cron

Before installing, open `config/cron-policy.json` and replace the example
absolute paths with **your** install location. The file ships with placeholder
paths under `/path/to/jcoding-automation/...`; the cron entries must point at
the real `scripts/cron-launcher.sh` and `scripts/log-housekeeping.sh` on this
machine. For example, if you cloned the project to
`/home/alice/jcoding-automation`, the `cron_entry` should read:

```
*/1 * * * * /home/alice/jcoding-automation/scripts/cron-launcher.sh >> /home/alice/jcoding-automation/logs/cron-system.log 2>&1
```

Tip: print your absolute path with `pwd` from inside the project directory and
paste it in. Then install or update the cron entry from the policy file:

```bash
scripts/install-cron.sh
```

The installer replaces only its own marked block and preserves unrelated
crontab entries. It also installs a daily housekeeping entry that rotates and
prunes generated logs. The controller skips a run when `job_start` is false or
another run owns the atomic lock, pauses on quota errors, and preserves the
manual merge gate.

## Operational scripts

- `scripts/cron-launcher.sh` — loads secrets from the mode-600 env file and launches a run.
- `scripts/go-autonomous.sh` — guided wizard that writes `config/config.json` and walks the readiness → provider canary → canary → autonomous rollout.
- `scripts/run_once.sh` — one-shot execution under `setsid` with timestamped logs (`--verbose` for DEBUG, `--force-run` to bypass the job gate).
- `scripts/install-cron.sh` — installs the marked crontab block from `cron-policy.json`.
- `scripts/healthcheck.sh` — non-mutating operational health check.
- `scripts/log-housekeeping.sh` — daily log rotation and bounded retention.
- `scripts/reset.sh` — stop any active run and reset durable state (backs up state first; opt-in `--clean-repo`/`--delete-branch`/`--close-pr` for repo/GitHub cleanup).

Run the health check any time:

```bash
scripts/healthcheck.sh
```

## Logging and observability

Logging uses SLF4J with Logback to the console (captured by `run_once.sh` into a
timestamped file under `logs/`). Every line carries a UTC ISO-8601 timestamp,
level, and logger name:

```
2026-09-18T13:15:23.500Z DEBUG GitHub - GH CALL: issue view took 642 ms (exit 0, 123808 bytes)
2026-09-18T13:15:25.904Z INFO  WorkflowOrchestrator - PHASE ENTER: VERIFYING story=#45 pr=#177 attempt=2 budget=1800s prompt=verify.md
```

Default level is `INFO`. Enable `DEBUG` for a single run with the `--verbose`
flag, or set the JVM property directly:

```bash
scripts/run_once.sh --verbose
# or, running the launcher directly:
JAVA_OPTS="-Dlog.level=DEBUG" build/install/jcoding-automation/bin/jcoding-automation ...
```

What the logs surface:

- **GitHub call durations** — each `gh` call logs its subcommand and elapsed
  time (`GH CALL: ...`). Calls slower than 5 s log a `GH CALL SLOW` warning, and
  a timed-out call logs `GH CALL TIMEOUT` with the elapsed time. Useful for
  spotting slow or hanging API calls.
- **Phase context and duration** — `PHASE ENTER` records the phase, story, PR,
  attempt, and budget; `PHASE EXIT` records the exit code and elapsed time.
- **Wait reasons** — `WAITING: ... reason=... reviewDecision=... ciChecks=...`
  explains why the workflow is waiting without inspecting the state file.
- **Recovery and safety events** — lock recovery, watchdog termination, quota
  pauses, conflicts, and blocked states are logged at `INFO`/`ERROR`.

## Build and test

```bash
./gradlew test          # run the full JUnit 5 suite (offline, mock-only)
./gradlew installDist   # build the runnable application distribution
./gradlew clean         # remove build outputs
```

All tests are mock/offline: no real GitHub, no network, and no `codex` process
is launched. External boundaries (GitHub CLI, git, the Codex worker, the lock
filesystem, the environment) are injected as fakes, and every test asserts a
concrete behavioral outcome.

## State and durability

Runtime state and locks are generated automatically per EPIC under `state/`:

- `state/epic-<number>.sqlite3` — the authoritative durable state store (SQLite,
  WAL) recording workflow phases, recovery metadata, and each worker job with
  its heartbeat, timeout, log path, and terminal result, plus per-call model
  usage and redacted worker evidence in `worker_usage`.
- `state/epic-<number>.json` — a human-readable export view.
- `state/epic-<number>.events.jsonl` — a compact operator audit trail.
- `state/epic-<number>.lock` — the atomic run lock.

A restart marks only abandoned jobs as cancelled and resumes from the last
safely persisted phase. A timed-out worker enters `FAILED_RECOVERABLE` and the
next run reconciles the branch before retrying.

## Architecture

The workflow is composed of focused, single-responsibility classes wired via
constructor injection:

- `Engine`, `PhaseModel` — pure workflow decision logic (phase classification,
  story selection, result parsing).
- `StateStore` — SQLite-authoritative durable state, JSON export, event journal,
  and resumable job journal.
- `GitHub` — `gh` CLI and GraphQL operations behind an injectable command runner.
- `Config` — JSON configuration loading and validation.
- `PromptRenderer` — deterministic phase-prompt composition.
- `StateRecovery` — durable-state repair and reconciliation-decision application.
- `ReviewCoordinator` — review-thread and Codex-review-trigger coordination.
- `WorkflowLock` — atomic single-owner lock with heartbeat and watchdog recovery.
- `GitOperations` — branch, worktree, commit, push, and delivery verification.
- `CodexRunner` — bounded `codex exec` execution and credential redaction.
- `Controller` + `WorkflowOrchestrator` — top-level orchestration and phase dispatch.

## Documentation

Project documentation lives under `docs/` and `audit/`:

- `docs/autonomy-implementation.md` — how the autonomy, canary, and release-gate
  controls are implemented.
- `docs/spec/` — spec-driven design (SDD) documents, one directory per spec.
  Each spec directory contains `requirements.md`, `design.md`, and `tasks.md`
  (read in that order) plus a short `README.md` index. The directory name is a
  lowercase, hyphenated slug; draft status is recorded in each document's header,
  not in the directory name. Current spec:
  [`docs/spec/architecture-fundamentals-hardening/`](docs/spec/architecture-fundamentals-hardening/README.md).
- `audit/` — point-in-time architecture and security assessment:
  `01-current-architecture.md`, `02-gap-analysis.md`, `03-target-architecture.md`,
  and `agent-security-notes.md`.

The worker phase prompts under `prompts/` are runtime inputs, not documentation:
the controller loads them (e.g. `verify.md`, `review-security.md`) during the
workflow, and a missing prompt fails the corresponding phase closed.

## License

This project is free and open-source software released under the
[MIT License](LICENSE).
