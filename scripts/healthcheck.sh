#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0
check() {
  if "$@" >/dev/null 2>&1; then
    echo "PASS: $*"
  else
    echo "FAIL: $*"
    fail=1
  fi
}

check test -f config/config.json
check test -f config/job-control.json
check test -f config/cron-policy.json
check command -v java
check command -v codex
check command -v gh
check command -v jq
check command -v flock
check command -v setsid

if [[ -n "${GH_TOKEN:-}" ]]; then
  echo "PASS: GH_TOKEN is set"
else
  echo "FAIL: GH_TOKEN is not set"
  fail=1
fi

# JSON configuration must parse.
if jq -e . config/config.json >/dev/null 2>&1 \
   && jq -e . config/job-control.json >/dev/null 2>&1 \
   && jq -e . config/cron-policy.json >/dev/null 2>&1; then
  echo "PASS: JSON configuration parses"
else
  echo "FAIL: JSON configuration does not parse"
  fail=1
fi

repo="$(jq -er '.repository.local_path' config/config.json 2>/dev/null || echo "")"
check test -d "$repo/.git"
check git -C "$repo" diff --quiet
check git -C "$repo" diff --cached --quiet

if [[ "$fail" -eq 0 ]]; then
  echo "HEALTHCHECK PASSED"
else
  echo "HEALTHCHECK FAILED"
fi
exit "$fail"
