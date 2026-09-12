#!/usr/bin/env bash
set -euo pipefail

# Deterministic S001 evidence check. The Python checkout is read-only input;
# this script never runs a workflow or invokes GitHub/Codex side effects.
REFERENCE=${PYTHON_REFERENCE:-/home/algo/codex-coding-automation}
REVISION=c8eaaf2756df3e3dcaf946d995a1ed1db56e93bf
TREE_HASH=bc438d564ecc01fd7d0ba5691aba4d3868843e4c
ROOTS=(src/codex_automation prompts scripts config tests pyproject.toml)

[[ -d "$REFERENCE/.git" ]] || { echo "missing Python git checkout: $REFERENCE" >&2; exit 2; }
[[ "$(git -C "$REFERENCE" rev-parse HEAD)" == "$REVISION" ]] || {
  echo "reference revision mismatch" >&2; exit 3;
}
[[ "$(git -C "$REFERENCE" rev-parse "$REVISION^{tree}")" == "$TREE_HASH" ]] || {
  echo "reference tree hash mismatch" >&2; exit 3;
}
[[ -z "$(git -C "$REFERENCE" status --porcelain)" ]] || {
  echo "reference worktree is dirty" >&2; exit 4;
}

for root in "${ROOTS[@]}"; do
  git -C "$REFERENCE" cat-file -e "$REVISION:$root" || {
    echo "missing required baseline artifact: $root" >&2; exit 5;
  }
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

python3 -m pytest -q "$REFERENCE/tests"
echo "S001 characterization evidence complete: $REVISION"
