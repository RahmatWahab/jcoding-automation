#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mkdir -p "$ROOT/logs"
LOG_FILE="$ROOT/logs/run-$(date -u +%Y%m%dT%H%M%SZ)-$$.log"

# Extract the local --verbose flag (maps to DEBUG logging) and pass the rest
# of the arguments through to the application unchanged.
LOG_LEVEL="INFO"
ARGS=()
for arg in "$@"; do
  if [[ "$arg" == "--verbose" ]]; then
    LOG_LEVEL="DEBUG"
  else
    ARGS+=("$arg")
  fi
done

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

echo "Log: $LOG_FILE (level=$LOG_LEVEL)"
# The Gradle application launcher forwards JAVA_OPTS to the JVM.
setsid env CODEX_AUTOMATION_LOG_FILE="$LOG_FILE" JAVA_OPTS="-Dlog.level=$LOG_LEVEL" \
  "$LAUNCHER" ${ARGS[@]+"${ARGS[@]}"} 2>&1 | tee "$LOG_FILE"
exit "${PIPESTATUS[0]}"
