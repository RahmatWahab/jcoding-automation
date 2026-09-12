# S001 characterization evidence

The frozen Python reference is exactly commit
`c8eaaf2756df3e3dcaf946d995a1ed1db56e93bf`. The reference checkout must be
clean; a different revision or any local modification invalidates this
evidence. The target baseline is `b58bf1f2d2bdc9593927542e234c13fc71aeff16`.

`parity-matrix.csv` is the machine-readable ownership map. Its source scope is
the complete tracked set under `src/codex_automation/`, `prompts/`, `scripts/`,
`config/`, `tests/`, plus `pyproject.toml`. The check harness compares that
complete set against the matrix, so an unlisted artifact fails closed.

Run from the Java repository:

```sh
bash docs/migration/characterization-check.sh
```

The harness performs no workflow, GitHub, Codex, or filesystem mutation in the
Python repository. It verifies revision and cleanliness, validates required
scope roots, checks matrix completeness, and runs the frozen Python regression
suite. `replay-fixtures/job-control.json` records the exact boolean gate
contract, including non-boolean negative cases; later Java stories must replay
these cases without external side effects.
