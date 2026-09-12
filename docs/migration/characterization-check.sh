#!/usr/bin/env bash
set -euo pipefail

# Deterministic S001 evidence check. The Python checkout is read-only input;
# this script never runs a workflow or invokes GitHub/Codex side effects.
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
JAVA_REPOSITORY=$(cd -- "$SCRIPT_DIR/../.." && pwd)
cd "$JAVA_REPOSITORY"
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

git -C "$REFERENCE" rev-parse --git-dir >/dev/null 2>&1 || {
  echo "missing Python git checkout: $REFERENCE" >&2; exit 2;
}
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

snapshot=$(mktemp -d)
trap 'rm -f "$expected" "$actual"; rm -rf "$snapshot"' EXIT
git -C "$REFERENCE" archive "$REVISION" | tar -x -C "$snapshot"

# Replay the fixtures against the pinned source export, never against the live
# reference worktree. The expected values are therefore checked semantically,
# not merely accepted as well-formed JSON.
PYTHONPATH="$snapshot/src" python3 - "$JAVA_REPOSITORY" <<'PY'
import json
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

root = Path(sys.argv[1])
from codex_automation.controller import acquire_lock, read_job_control, release_lock
from codex_automation.engine import apply_codex_result, classify_pr, reconcile, select_story

def load(name):
    return json.loads((root / "docs/migration/replay-fixtures" / f"{name}.json").read_text())

for case in load("story-selection")["cases"]:
    actual = select_story(case["stories"], set(case["completed"]))
    actual = actual["number"] if actual else None
    if actual != case["expected"]:
        raise SystemExit(f"story-selection replay mismatch: {case}")
for case in load("state-transitions")["cases"]:
    actual = list(reconcile({}, case["pr"], case["feedback"]))
    if actual != case["expected"]:
        raise SystemExit(f"state-transitions replay mismatch: {case}")
for case in load("github-review-ci")["cases"]:
    if classify_pr(case) != case["expected"]:
        raise SystemExit(f"github-review-ci replay mismatch: {case}")
for case in load("codex-results")["cases"]:
    actual = apply_codex_result({}, case["exit_code"], case["output"], retry_seconds=1)["phase"]
    if actual != case["expected"]:
        raise SystemExit(f"codex-results replay mismatch: {case}")
print("semantic replay evidence complete: story/state/GitHub/Codex domains")

with tempfile.TemporaryDirectory() as directory:
    for index, case in enumerate(load("locks")["cases"]):
        path = Path(directory) / f"controller-{index}.lock"
        if case["lock"] == "owned":
            age = case["heartbeat_age_seconds"]
            stamp = (datetime.now(timezone.utc) - timedelta(seconds=age)).isoformat()
            path.mkdir()
            (path / "owner.json").write_text(json.dumps({
                "pid": os.getpid() if age < 900 else 999999999,
                "started_at": stamp,
                "heartbeat_at": stamp,
                "owner_token": "fixture-owner",
            }))
        elif case["lock"] == "malformed":
            path.mkdir()
            (path / "owner.json").write_text("not-json")
        acquired = acquire_lock(path, 900, watchdog_observations=3, watchdog_grace_seconds=0)
        actual = "SKIP" if acquired is None else ("ACQUIRE" if case["lock"] == "absent" else "RECONCILE_STALE")
        if actual != case["expected"]:
            raise SystemExit(f"locks replay mismatch: {case}")
        if acquired is not None:
            release_lock(path)

for case in load("workflow-gates")["cases"]:
    actual = classify_pr(case["pr"])
    if actual != case["expected"]:
        raise SystemExit(f"workflow-gates replay mismatch: {case}")
print("semantic replay evidence complete: locks/workflow-gates domains")

with tempfile.TemporaryDirectory() as directory:
    for index, case in enumerate(load("job-control")["cases"]):
        path = Path(directory) / f"job-control-{index}.toml"
        if "job_start" in case:
            value = case["job_start"]
            if isinstance(value, bool):
                literal = "true" if value else "false"
            elif isinstance(value, str):
                literal = json.dumps(value)
            else:
                literal = json.dumps(value)
            path.write_text(f"job_start = {literal}\n")
        actual = "RUN" if read_job_control(path) else "SKIP"
        if actual != case["expected"]:
            raise SystemExit(f"job-control replay mismatch: {case}")
print("semantic replay evidence complete: job-control domain")
PY

PYTHONPATH="$snapshot/src" python3 -m pytest -q "$snapshot/tests"
echo "S001 characterization evidence complete: $REVISION"
