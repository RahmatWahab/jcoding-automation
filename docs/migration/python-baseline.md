# Frozen Python characterization baseline

This baseline is the behavioral reference for the Java migration.

| Field | Value |
|---|---|
| Repository | `/home/algo/codex-coding-automation` |
| Revision | `c8eaaf2756df3e3dcaf946d995a1ed1db56e93bf` |
| Revision tree hash | `bc438d564ecc01fd7d0ba5691aba4d3868843e4c` |
| Revision subject | `fix: recover stale story selection blockers` |
| Captured | 2026-09-12 (Europe/Berlin) |
| Java target baseline | `b58bf1f2d2bdc9593927542e234c13fc71aeff16` |
| Python status | immutable committed baseline; live runtime overrides are excluded |
| Runtime contract | Python project metadata in `pyproject.toml`; TOML configuration; SQLite state |

The revision, configuration/prompt snapshot manifest, environment snapshot,
behavior inventory, scripts, source modules, and tests listed in
`parity-matrix.csv` are the characterization scope. Java
implementation status is intentionally `PENDING` until the owning story adds
the compatible implementation and evidence. No Python behavior is changed by
this baseline.

The immutable revision and tree hash are checked by
`docs/migration/characterization-check.sh`; the check also exercises the
negative cases in `docs/migration/replay-fixtures/job-control.json` and runs
the complete frozen Python regression suite.
