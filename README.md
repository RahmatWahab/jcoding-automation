# jcoding-automation

A stateful, cron-driven controller for bounded Codex CLI coding workflows,
implemented in Java 21. It drives a full software-delivery loop against a single
GitHub EPIC: it selects a linked story, runs the Codex CLI as a bounded worker
to implement, verify, and review it, opens a pull request, manages the review
cycle, and gates the final merge behind a human.

The controller owns all authority — scheduling, locking, durable state, GitHub
reconciliation, and the manual-merge gate. Codex runs only as a constrained,
time-boxed worker.

## Requirements

| Tool | Purpose |
|------|---------|
| Java 21 (JDK) | Build and run the application |
| `git` | Repository operations |
| `gh` (GitHub CLI) | Authenticated GitHub access |
| `jq` | JSON parsing in the cron/install scripts |
| `flock`, `setsid` | Lock and isolated process handling (Linux) |
| `codex` | The Codex CLI worker executable |

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

## Configure

All configuration is JSON.

1. Edit `config/config.json` — set the target repository, EPIC number, Codex
   model, budgets, and workflow flags. The example defaults to
   `automation.dry_run = true` and all mutation flags `false`, so nothing is
   pushed or commented until you explicitly enable it.

   The safety invariant `workflow.require_manual_merge` must be `true`; the
   controller refuses to start otherwise, and it never merges a PR itself.

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
environment file outside the repository (mode `600` is required):

```bash
mkdir -p "$HOME/.config/jcoding-automation"
printf '%s\n' 'GH_TOKEN=your-token' > "$HOME/.config/jcoding-automation/env"
chmod 600 "$HOME/.config/jcoding-automation/env"
```

The cron launcher loads this file automatically. Use
`CODEX_AUTOMATION_ENV_FILE=/path/to/file` to select another protected file.

## Run

```bash
# One-shot run; honors the job_start gate.
scripts/run_once.sh

# Bypass the gate for a manual run (still respects dry_run).
scripts/run_once.sh --force-run
```

You can also run the built launcher directly:

```bash
build/install/jcoding-automation/bin/jcoding-automation \
  --config config/config.json --job-control config/job-control.json
```

Command-line options: `--config`, `--job-control`, `--prompt`, `--force-run`.

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
  "automation": { "dry_run": false },
  "workflow": {
    "allow_push": true,
    "allow_pr_creation": true,
    "allow_comments": true,
    "allow_thread_resolution": true,
    "require_manual_merge": true
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
| `require_manual_merge` | must stay `true`; the automation never merges a PR itself |

and in `config/job-control.json`:

```json
{ "job_start": true }
```

> Note: this Java build's behavior is validated by an offline, mock-only test
> suite. Real `gh` responses and the real `codex` worker have not been exercised
> by automated tests, so a dry-run first launch is the recommended real-world
> integration check for the GitHub-read path.

## Cron

Install or update the cron entry from the policy file:

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
- `scripts/run_once.sh` — one-shot execution under `setsid` with timestamped logs.
- `scripts/install-cron.sh` — installs the marked crontab block from `cron-policy.json`.
- `scripts/healthcheck.sh` — non-mutating operational health check.
- `scripts/log-housekeeping.sh` — daily log rotation and bounded retention.

Run the health check any time:

```bash
scripts/healthcheck.sh
```

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
  its heartbeat, timeout, log path, and terminal result.
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
