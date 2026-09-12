#!/usr/bin/env bash
set -euo pipefail

# Deterministic S001 evidence check. The Python checkout is read-only input;
# this script never runs a workflow or invokes GitHub/Codex side effects.
REFERENCE=${PYTHON_REFERENCE:-/home/algo/codex-coding-automation}
REVISION=c8eaaf2756df3e3dcaf946d995a1ed1db56e93bf
TREE_HASH=bc438d564ecc01fd7d0ba5691aba4d3868843e4c
ROOTS=(src/codex_automation prompts scripts config tests pyproject.toml)
FIXTURE=docs/migration/replay-fixtures/job-control.json
FIXTURE_SHA256=cda60f84133ae7a31aea067fb088abfda13dbbbbdb732bfae81c3811c709d9d5
MATRIX_SHA256=5e2239e3499211e824df7fd989e097ff614c7869ec5fb55b92d8c04622faf20a
MANIFEST=docs/migration/python-baseline-manifest.tsv
INVENTORY=docs/migration/python-behavior-inventory.md
ENVIRONMENT=docs/migration/baseline-environment.md
FIXTURES=(story-selection state-transitions github-review-ci codex-results locks workflow-gates)

[[ -d "$REFERENCE/.git" ]] || { echo "missing Python git checkout: $REFERENCE" >&2; exit 2; }
[[ "$(git -C "$REFERENCE" rev-parse HEAD)" == "$REVISION" ]] || {
  echo "reference revision mismatch" >&2; exit 3;
}
[[ "$(git -C "$REFERENCE" rev-parse "$REVISION^{tree}")" == "$TREE_HASH" ]] || {
  echo "reference tree hash mismatch" >&2; exit 3;
}
# The committed revision is the immutable baseline. The live reference checkout
# may contain an operator-controlled runtime override (for example job_start),
# so never mutate it or confuse that override with the frozen source. Snapshots
# below are always read from REVISION.

for root in "${ROOTS[@]}"; do
  git -C "$REFERENCE" cat-file -e "$REVISION:$root" || {
    echo "missing required baseline artifact: $root" >&2; exit 5;
  }
done

[[ "$(sha256sum "$FIXTURE" | awk '{print $1}')" == "$FIXTURE_SHA256" ]] || {
  echo "replay fixture hash mismatch" >&2; exit 6;
}
[[ "$(sha256sum docs/migration/parity-matrix.csv | awk '{print $1}')" == "$MATRIX_SHA256" ]] || {
  echo "parity matrix hash mismatch" >&2; exit 6;
}
[[ -s "$MANIFEST" && -s "$INVENTORY" && -s "$ENVIRONMENT" ]] || {
  echo "baseline manifest, inventory, or environment snapshot is missing" >&2; exit 6;
}

python3 - "$MANIFEST" "$REFERENCE" "$REVISION" <<'PY'
import hashlib
import pathlib
import subprocess
import sys

manifest, reference, revision = sys.argv[1:]
rows = [line.rstrip("\n").split() for line in pathlib.Path(manifest).read_text().splitlines()]
if not rows or rows[0] != ["path", "sha256"] or any(len(row) != 2 for row in rows[1:]):
    raise SystemExit("invalid baseline manifest format")
expected = {path: digest for path, digest in rows[1:]}
if len(expected) != len(rows) - 1 or not expected:
    raise SystemExit("baseline manifest contains duplicate or no artifacts")
for path, digest in expected.items():
    content = subprocess.check_output(["git", "-C", reference, "show", f"{revision}:{path}"])
    if hashlib.sha256(content).hexdigest() != digest:
        raise SystemExit(f"baseline snapshot hash mismatch: {path}")
print(f"baseline snapshots verified: {len(expected)} artifacts")
PY

for fixture_name in "${FIXTURES[@]}"; do
  fixture_path="docs/migration/replay-fixtures/${fixture_name}.json"
  [[ -s "$fixture_path" ]] || { echo "missing replay fixture: $fixture_path" >&2; exit 6; }
  python3 - "$fixture_path" "$fixture_name" <<'PY'
import json
import sys

path, expected_name = sys.argv[1:]
value = json.load(open(path, encoding="utf-8"))
if value.get("fixture") != expected_name or not isinstance(value.get("cases"), list) or not value["cases"]:
    raise SystemExit(f"invalid replay fixture: {path}")
for index, case in enumerate(value["cases"]):
    if not isinstance(case, dict) or "expected" not in case:
        raise SystemExit(f"missing expected outcome in {path} case {index}")
print(f"replay fixture verified: {path} ({len(value['cases'])} cases)")
PY
done

for required in 'Configuration keys' 'Workflow phases and result contracts' 'State fields' 'External commands and side effects' 'Failure and recovery rules'; do
  grep -Fq "## $required" "$INVENTORY" || { echo "inventory section missing: $required" >&2; exit 6; }
done

# The matrix must cover every tracked artifact in the frozen scope. This makes
# additions fail closed instead of silently becoming undocumented behavior.
expected=$(mktemp)
actual=$(mktemp)
trap 'rm -f "$expected" "$actual"' EXIT
git -C "$REFERENCE" ls-tree -r --name-only "$REVISION" -- \
  src/codex_automation prompts scripts config tests pyproject.toml | sort > "$expected"
awk -F, 'NR > 1 && $3 != "" {print $3}' docs/migration/parity-matrix.csv | sort -u > "$actual"
comm -23 "$expected" "$actual" | sed 's/^/unmapped artifact: /' | grep . && exit 6 || true
comm -13 "$expected" "$actual" \
  | grep -Ev '^(state/ and logs/|tests/)$' \
  | sed 's/^/matrix artifact outside frozen scope: /' \
  | grep . && exit 6 || true

# Replay the exact gate contract, including negative and missing-value cases.
# `is True` matches the Python implementation and rejects truthy lookalikes.
python3 - "$FIXTURE" "$REVISION" <<'PY'
import json
import sys

fixture_path, revision = sys.argv[1:]
fixture = json.load(open(fixture_path, encoding="utf-8"))
if fixture.get("reference_revision") != revision or fixture.get("fixture") != "job-control-gate":
    raise SystemExit("invalid job-control replay metadata")
cases = fixture.get("cases")
if not isinstance(cases, list) or not cases:
    raise SystemExit("job-control replay has no cases")
for index, case in enumerate(cases):
    decision = "RUN" if case.get("job_start") is True else "SKIP"
    if decision != case.get("expected"):
        raise SystemExit(f"job-control replay mismatch at case {index}")
if not any(case.get("job_start") is True and case.get("expected") == "RUN" for case in cases):
    raise SystemExit("job-control replay lacks positive case")
if not any(case.get("job_start") is not True and case.get("expected") == "SKIP" for case in cases):
    raise SystemExit("job-control replay lacks negative case")
print(f"job-control replay evidence complete: {len(cases)} cases")
PY

python3 -m pytest -q "$REFERENCE/tests"
echo "S001 characterization evidence complete: $REVISION"
