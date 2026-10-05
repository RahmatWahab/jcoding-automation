#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mkdir -p "$ROOT/logs"
LOG_FILE="$ROOT/logs/run-$(date -u +%Y%m%dT%H%M%SZ)-$$.log"

# Do not rebuild the distribution when the execution gate is disabled. Apart
# from wasting resources, rebuilding here can race a developer's test task
# because the controller's job_start check otherwise occurs only after the
# distribution has been built.
if [[ -f "$ROOT/config/job-control.json" ]] \
    && ! jq -e '.job_start == true' "$ROOT/config/job-control.json" >/dev/null 2>&1; then
  echo "SKIPPED: job_start is not true"
  exit 0
fi

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

command -v flock >/dev/null 2>&1 || {
  echo "BLOCKED: flock is required for runtime distribution publication" >&2
  exit 1
}

# Keep runtime outputs outside the developer's build/ tree. Only launchers
# take this lock; developer Gradle tasks can continue using their own outputs.
RUNTIME="$ROOT/out/launcher-runtime"
mkdir -p "$RUNTIME/distributions"
exec {BUILD_LOCK}>"$RUNTIME/build.lock"
flock -x "$BUILD_LOCK"

input_hash() {
  local input
  local inputs=(scripts/runtime-build.init.gradle)
  for input in src buildSrc gradle config/checkstyle gradlew gradlew.bat \
      build.gradle build.gradle.kts settings.gradle settings.gradle.kts \
      gradle.properties gradle.lockfile; do
    [[ ! -e "$input" ]] || inputs+=("$input")
  done
  # Hash paths as well as contents, so deletions/renames and edits with preserved
  # timestamps invalidate the cache. Exclude generated buildSrc outputs.
  find -L "${inputs[@]}" -type d \( -name build -o -name .gradle \) -prune -o \
    -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum | sha256sum | cut -d ' ' -f1
}

valid_distribution() {
  local dist="$1" jar
  [[ -x "$dist/bin/jcoding-automation" ]] || return 1
  local jars=("$dist"/lib/*.jar "$dist"/lib/jcoding-automation-*.jar)
  for jar in "${jars[@]}"; do
    [[ -s "$jar" ]] || return 1
  done
}

SOURCE_HASH="$(input_hash)"
DIST="$(readlink -f "$RUNTIME/current" || true)"
if ! valid_distribution "$DIST" || [[ ! -s "$DIST/.source-hash" ]] \
    || [[ "$(<"$DIST/.source-hash")" != "$SOURCE_HASH" ]]; then
  echo "Building isolated application distribution..."
  STAGING="$(mktemp -d "$RUNTIME/staging.XXXXXXXX")"
  GRADLE=(gradle)
  [[ ! -x "$ROOT/gradlew" ]] || GRADLE=("$ROOT/gradlew")
  "${GRADLE[@]}" --quiet --no-daemon --no-configuration-cache \
    --project-cache-dir "$STAGING/project-cache" \
    --init-script "$ROOT/scripts/runtime-build.init.gradle" \
    -Djcoding.runtime.buildDir="$STAGING/build" installDist

  # An editor need not hold our lock. Refuse to cache a build whose inputs
  # changed while Gradle ran; the next invocation retries from fresh output.
  if [[ "$(input_hash)" != "$SOURCE_HASH" ]]; then
    echo "BLOCKED: build inputs changed during runtime build; retry" >&2
    exit 1
  fi
  CANDIDATE="$STAGING/build/install/jcoding-automation"
  if ! valid_distribution "$CANDIDATE"; then
    echo "BLOCKED: incomplete application distribution: $CANDIDATE" >&2
    exit 1
  fi
  printf '%s\n' "$SOURCE_HASH" > "$CANDIDATE/.source-hash"
  DIST="$RUNTIME/distributions/$SOURCE_HASH-${STAGING##*.}"
  mv -T "$CANDIDATE" "$DIST"
  chmod -R a-w "$DIST"
  ln -s "$DIST" "$STAGING/current"
  mv -Tf "$STAGING/current" "$RUNTIME/current"
fi

# Pin the actual immutable directory, never the moving current symlink. Release
# and close the lock before setsid so a long-running JVM cannot retain it.
LAUNCHER="$DIST/bin/jcoding-automation"
flock -u "$BUILD_LOCK"
exec {BUILD_LOCK}>&-
# Old distributions (and failed staging output for diagnosis) are retained.

echo "Log: $LOG_FILE (level=$LOG_LEVEL)"
# The Gradle application launcher forwards JAVA_OPTS to the JVM.
setsid env CODEX_AUTOMATION_LOG_FILE="$LOG_FILE" JAVA_OPTS="-Dlog.level=$LOG_LEVEL" \
  "$LAUNCHER" ${ARGS[@]+"${ARGS[@]}"} 2>&1 | tee "$LOG_FILE"
exit "${PIPESTATUS[0]}"
