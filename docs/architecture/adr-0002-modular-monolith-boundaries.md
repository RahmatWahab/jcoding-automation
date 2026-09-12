# ADR-0002: Modular Monolith and Dependency Inversion

## Status

Accepted for S002.

## Context

The automation is a local, cron-driven workflow. EPIC #1 requires future
extension to providers such as Kiro and GitLab, but the current operational
model does not justify distributed services.

## Decision

Implement the Java migration as one Gradle-built modular monolith with small
packages and explicit interfaces for external boundaries.

Domain decisions must not invoke `git`, `gh`, `codex`, the filesystem, process
execution, SQLite, or GitHub directly. Those side effects belong behind
interfaces such as `GitClient`, `GitHubClient`, `CodexClient`, `ProcessRunner`,
`StateRepository`, `EventJournal`, `LockManager`, `Clock`, `ValidationRunner`,
`PromptRenderer`, and `AutomationProvider`.

## Consequences

- Workflow behavior can be tested with fakes and replay fixtures.
- Later stories can add provider implementations without changing workflow
  decision classes.
- Architecture fitness checks fail if direct process execution appears outside
  the execution boundary, required packages are missing, package cycles appear,
  or domain packages depend directly on infrastructure packages.

## Evidence

- Required package boundaries exist under
  `src/main/java/com/rahmatwahab/codexautomation`.
- `architectureFitness` runs as part of `check`.
