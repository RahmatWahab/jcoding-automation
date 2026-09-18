#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mkdir -p "$ROOT/logs"
LOG_FILE="$ROOT/logs/run-$(date -u +%Y%m%dT%H%M%SZ)-$$.log"

command -v setsid >/dev/null 2>&1 || {
  echo "BLOCKED: setsid is required for isolated controller process cleanup" >&2
  exit 1
}

# Prefer the built application launcher; build it once if missing.
LAUNCHER="$ROOT/build/install/jcoding-automation/bin/jcoding-automation"
if [[ ! -x "$LAUNCHER" ]]; then
  echo "Building application distribution (first run)..."
  if [[ -x "$ROOT/gradlew" ]]; then
    "$ROOT/gradlew" --quiet installDist
  else
    gradle --quiet installDist
  fi
fi

echo "Log: $LOG_FILE"
setsid env CODEX_AUTOMATION_LOG_FILE="$LOG_FILE" \
  "$LAUNCHER" "$@" 2>&1 | tee "$LOG_FILE"
exit "${PIPESTATUS[0]}"
