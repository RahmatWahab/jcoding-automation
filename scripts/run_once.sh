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

# Prefer the built application launcher. Rebuild it when it is missing OR stale:
# a partial/interrupted installDist or an edit-without-rebuild can leave the
# launcher present but its classes inconsistent with source, which surfaces at
# runtime as NoClassDefFoundError (observed in the field). Detect staleness by
# comparing the newest source/build-config file against the built jar and
# rebuild if anything is newer, so cron never launches a mismatched distribution.
LAUNCHER="$ROOT/build/install/jcoding-automation/bin/jcoding-automation"
BUILT_JAR="$(ls "$ROOT"/build/install/jcoding-automation/lib/jcoding-automation-*.jar 2>/dev/null | head -1 || true)"

needs_build=0
if [[ ! -x "$LAUNCHER" || -z "$BUILT_JAR" ]]; then
  needs_build=1
else
  # Newest input across sources and build configuration.
  newest_input="$(find "$ROOT/src" "$ROOT/build.gradle" "$ROOT/settings.gradle" \
      -type f -printf '%T@\n' 2>/dev/null | sort -n | tail -1 || echo 0)"
  jar_mtime="$(stat -c '%Y' "$BUILT_JAR" 2>/dev/null || echo 0)"
  # Compare as integers (truncate the fractional part of find's %T@).
  if [[ "${newest_input%.*}" -gt "${jar_mtime%.*}" ]]; then
    echo "Rebuilding: sources are newer than the built distribution (avoiding a stale/partial build)."
    needs_build=1
  fi
fi

if [[ "$needs_build" -eq 1 ]]; then
  echo "Building application distribution..."
  if [[ -x "$ROOT/gradlew" ]]; then
    "$ROOT/gradlew" --quiet clean installDist
  else
    gradle --quiet clean installDist
  fi
fi

# Fail fast rather than launch a broken/partial distribution.
if [[ ! -x "$LAUNCHER" ]]; then
  echo "BLOCKED: application launcher missing after build: $LAUNCHER" >&2
  exit 1
fi

echo "Log: $LOG_FILE (level=$LOG_LEVEL)"
# The Gradle application launcher forwards JAVA_OPTS to the JVM.
setsid env CODEX_AUTOMATION_LOG_FILE="$LOG_FILE" JAVA_OPTS="-Dlog.level=$LOG_LEVEL" \
  "$LAUNCHER" ${ARGS[@]+"${ARGS[@]}"} 2>&1 | tee "$LOG_FILE"
exit "${PIPESTATUS[0]}"
