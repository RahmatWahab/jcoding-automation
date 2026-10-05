#!/usr/bin/env bash
# No Gradle/JVM/model calls. Run with: bash scripts/tests/run_once_test.sh
set -euo pipefail
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d /tmp/jcoding-launcher-test.XXXXXXXX)"
trap 'chmod -R u+w "$TEST_ROOT"; rm -rf -- "$TEST_ROOT"' EXIT
export PATH="$TEST_ROOT/commands:$PATH"
mkdir -p "$TEST_ROOT/commands"
cp "$SCRIPTS/tests/fixtures/runtime-setsid" "$TEST_ROOT/commands/setsid"
chmod +x "$TEST_ROOT/commands/setsid"

fixture() {
  CASE_ROOT="$TEST_ROOT/$1"
  mkdir -p "$CASE_ROOT/scripts" "$CASE_ROOT/src/main" "$CASE_ROOT/config" \
    "$CASE_ROOT/gradle/wrapper" "$CASE_ROOT/build/classes"
  cp "$SCRIPTS/run_once.sh" "$SCRIPTS/runtime-build.init.gradle" "$CASE_ROOT/scripts/"
  cp "$SCRIPTS/tests/fixtures/runtime-gradlew" "$CASE_ROOT/gradlew"
  cp "$SCRIPTS/tests/fixtures/runtime-application" "$CASE_ROOT/fake-application"
  chmod +x "$CASE_ROOT/gradlew"
  printf 'original\n' > "$CASE_ROOT/src/main/input.txt"
  printf 'developer output\n' > "$CASE_ROOT/build/classes/sentinel"
  printf 'build config\n' > "$CASE_ROOT/build.gradle"
  printf 'settings\n' > "$CASE_ROOT/settings.gradle"
  printf 'wrapper\n' > "$CASE_ROOT/gradle/wrapper/gradle-wrapper.properties"
}
run() { bash "$CASE_ROOT/scripts/run_once.sh" "$@"; }
count() { wc -l < "$CASE_ROOT/build-count"; }
current() { readlink -f "$CASE_ROOT/out/launcher-runtime/current"; }
wait_file() {
  local attempt
  for ((attempt=0; attempt<500; attempt++)); do
    [[ ! -e "$1" ]] || return 0
    sleep 0.02
  done
  echo "Timed out waiting for $1" >&2
  return 1
}
fails_with() {
  local expected="$1" actual=0
  shift
  "$@" || actual=$?
  [[ "$actual" == "$expected" ]]
}

fixture gate
printf '{"job_start":false}\n' > "$CASE_ROOT/config/job-control.json"
run
[[ ! -e "$CASE_ROOT/out" && ! -e "$CASE_ROOT/build-count" ]]
printf 'invalid json\n' > "$CASE_ROOT/config/job-control.json"
run
[[ ! -e "$CASE_ROOT/out" ]]
echo 'PASS disabled and malformed job gate'

fixture 'arguments and cache'
printf '{"job_start":true}\n' > "$CASE_ROOT/config/job-control.json"
run --verbose 'two words' '' '--literal=*'
printf '%s\0' 'two words' '' '--literal=*' > "$CASE_ROOT/expected-args"
cmp "$CASE_ROOT/expected-args" "$CASE_ROOT/launched-args"
[[ "$(<"$CASE_ROOT/launched-java-opts")" == '-Dlog.level=DEBUG' ]]
rg -q 'fake application output' "$(<"$CASE_ROOT/launched-log")"
first="$(current)"
[[ "$(<"$CASE_ROOT/launched-dist")" == "$first" ]]
[[ -z "$(find "$first" -perm /222 -print -quit)" ]]
run
[[ "$(count)" == 1 && "$(current)" == "$first" ]]
[[ "$(<"$CASE_ROOT/launched-java-opts")" == '-Dlog.level=INFO' ]]
APP_EXIT=17 fails_with 17 run
[[ "$(<"$CASE_ROOT/build/classes/sentinel")" == 'developer output' ]]
echo 'PASS cache, immutable path, args, setsid, logs, exit status, developer outputs'

# Contents, added/deleted paths, wrapper and build configuration all invalidate.
touch -r "$CASE_ROOT/src/main/input.txt" "$CASE_ROOT/old-time"
printf 'changed\n' > "$CASE_ROOT/src/main/input.txt"
touch -r "$CASE_ROOT/old-time" "$CASE_ROOT/src/main/input.txt"
run
[[ "$(count)" == 2 && "$(current)" != "$first" && -d "$first" ]]
for input in build.gradle settings.gradle gradle.properties \
    gradle/wrapper/gradle-wrapper.properties scripts/runtime-build.init.gradle; do
  previous="$(current)"
  printf '\nchange\n' >> "$CASE_ROOT/$input"
  run
  [[ "$(current)" != "$previous" && -d "$previous" ]]
done
printf 'added\n' > "$CASE_ROOT/src/main/extra.txt"
run
previous="$(current)"
rm "$CASE_ROOT/src/main/extra.txt"
run
[[ "$(current)" != "$previous" ]]
echo 'PASS content and build-input hashing, additions/deletions, version retention'

previous="$(current)"
printf 'retry\n' > "$CASE_ROOT/src/main/input.txt"
BUILD_FAIL=1 fails_with 42 run
[[ "$(current)" == "$previous" ]]
BUILD_PARTIAL=1 fails_with 1 run
[[ "$(current)" == "$previous" ]]
BUILD_INTERRUPT=1 fails_with 143 run
[[ "$(current)" == "$previous" ]]
run
[[ "$(current)" != "$previous" ]]
echo 'PASS failed/partial/interrupted build does not publish and next run retries'

fixture fallback
cp "$CASE_ROOT/gradlew" "$TEST_ROOT/commands/gradle"
chmod -x "$CASE_ROOT/gradlew"
run
[[ "$(count)" == 1 ]]
echo 'PASS system gradle fallback uses isolated build arguments'

fixture concurrency
BUILD_HOLD="$CASE_ROOT/build-barrier" run > "$CASE_ROOT/first.log" 2>&1 &
first_pid=$!
wait_file "$CASE_ROOT/build-barrier.started"
run > "$CASE_ROOT/second.log" 2>&1 &
second_pid=$!
[[ ! -e "$CASE_ROOT/out/launcher-runtime/current" ]]
touch "$CASE_ROOT/build-barrier.release"
wait "$first_pid"
wait "$second_pid"
[[ "$(count)" == 1 ]]
echo 'PASS concurrent cold starts serialize build/publication and reuse result'

APP_HOLD="$CASE_ROOT/app-barrier" run > "$CASE_ROOT/held.log" 2>&1 &
held_pid=$!
wait_file "$CASE_ROOT/app-barrier.started"
old="$(current)"
printf 'new version\n' > "$CASE_ROOT/src/main/input.txt"
run
[[ "$(current)" != "$old" && -d "$old" ]]
touch "$CASE_ROOT/app-barrier.release"
wait "$held_pid"
[[ -f "$CASE_ROOT/app-barrier.verified" ]]
echo 'PASS live old process keeps its distribution and does not hold build lock'

previous="$(current)"
printf 'before build\n' > "$CASE_ROOT/src/main/input.txt"
BUILD_HOLD="$CASE_ROOT/edit-barrier" run > "$CASE_ROOT/edit.log" 2>&1 &
edit_pid=$!
wait_file "$CASE_ROOT/edit-barrier.started"
printf 'during build\n' > "$CASE_ROOT/src/main/input.txt"
touch "$CASE_ROOT/edit-barrier.release"
fails_with 1 wait "$edit_pid"
[[ "$(current)" == "$previous" ]]
run
[[ "$(current)" != "$previous" ]]
echo 'PASS edit during build refuses publication and retries'
