# S001 characterization evidence

The frozen Python reference is exactly commit
`c8eaaf2756df3e3dcaf946d995a1ed1db56e93bf`. The committed tree, rather than
the live operator checkout, is authoritative. The live checkout may
intentionally contain a runtime-only `job_start` override. A different
revision or a mismatch against the manifest invalidates this evidence. The
target baseline is `b58bf1f2d2bdc9593927542e234c13fc71aeff16`.

`parity-matrix.csv` is the machine-readable ownership map. Its source scope is
the complete tracked set under `src/codex_automation/`, `prompts/`, `scripts/`,
`config/`, `tests/`, plus `pyproject.toml`. The check harness compares that
complete set against the matrix, so an unlisted artifact fails closed.

Run from the Java repository:

```sh
bash docs/migration/characterization-check.sh
```

The harness performs no workflow, GitHub, Codex, or filesystem mutation in the
Python repository. It verifies the immutable revision and tree, validates the
configuration/prompt snapshot manifest, required scope roots, matrix
completeness, all replay-fixture domains, and the frozen Python regression
suite. The behavior inventory records the keys, phases, state, commands, side
effects, failures, and recovery contracts that later Java stories must
preserve. Fixtures must replay without external side effects.
