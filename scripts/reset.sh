#!/usr/bin/env bash
#
# reset.sh — safely stop the automation and reset its durable state.
#
# What it does (in order):
#   1. Sets config/job-control.json to { "job_start": false } so no new run starts.
#   2. Gracefully stops any active controller (Main) and codex worker process.
#   3. Backs up and removes the per-EPIC durable state (sqlite3, json, events, lock).
#
# It does NOT touch your target repository, branches, or GitHub PRs by default —
# those are your work and are only cleaned up with explicit opt-in flags.
#
# Usage:
#   scripts/reset.sh                 # stop + reset automation state (asks to confirm)
#   scripts/reset.sh --yes           # no interactive confirmation
#   scripts/reset.sh --keep-gate     # do NOT change job-control.json
#   scripts/reset.sh --no-stop       # do not kill running processes
#   scripts/reset.sh --clean-repo    # ALSO switch target repo to base branch and sync (safe)
#   scripts/reset.sh --delete-branch # ALSO delete the current story branch (DESTRUCTIVE, implies --clean-repo)
#   scripts/reset.sh --close-pr <N>  # ALSO close PR #N on GitHub (DESTRUCTIVE)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ASSUME_YES=0
KEEP_GATE=0
NO_STOP=0
CLEAN_REPO=0
DELETE_BRANCH=0
CLOSE_PR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes|-y) ASSUME_YES=1 ;;
    --keep-gate) KEEP_GATE=1 ;;
    --no-stop) NO_STOP=1 ;;
    --clean-repo) CLEAN_REPO=1 ;;
    --delete-branch) DELETE_BRANCH=1; CLEAN_REPO=1 ;;
    --close-pr) shift; CLOSE_PR="${1:-}";;
    -h|--help)
      sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

command -v jq >/dev/null 2>&1 || { echo "BLOCKED: jq is required" >&2; exit 1; }

CONFIG="$ROOT/config/config.json"
JOB_CONTROL="$ROOT/config/job-control.json"
STATE_DIR="$ROOT/state"

# Resolve the EPIC number and target repo path from config (best-effort).
EPIC="$(jq -er '.epic.number' "$CONFIG" 2>/dev/null || echo "")"
REPO_PATH="$(jq -er '.repository.local_path' "$CONFIG" 2>/dev/null || echo "")"
BASE_BRANCH="$(jq -er '.repository.base_branch // "main"' "$CONFIG" 2>/dev/null || echo "main")"

if [[ -z "$EPIC" ]]; then
  echo "BLOCKED: could not read epic.number from $CONFIG" >&2
  exit 1
fi

echo "About to STOP and RESET jcoding-automation:"
echo "  EPIC:        #$EPIC"
echo "  State dir:   $STATE_DIR (epic-$EPIC.*)"
echo "  Gate off:    $([[ $KEEP_GATE -eq 1 ]] && echo no || echo yes)"
echo "  Stop procs:  $([[ $NO_STOP -eq 1 ]] && echo no || echo yes)"
echo "  Clean repo:  $([[ $CLEAN_REPO -eq 1 ]] && echo "yes ($REPO_PATH -> $BASE_BRANCH)" || echo no)"
echo "  Delete branch (DESTRUCTIVE): $([[ $DELETE_BRANCH -eq 1 ]] && echo yes || echo no)"
echo "  Close PR (DESTRUCTIVE):      ${CLOSE_PR:-no}"

if [[ $ASSUME_YES -ne 1 ]]; then
  read -r -p "Proceed? [y/N] " reply
  [[ "$reply" == "y" || "$reply" == "Y" ]] || { echo "Aborted."; exit 0; }
fi

# 1) Stop new runs via the job-control gate.
if [[ $KEEP_GATE -ne 1 ]]; then
  echo '{ "job_start": false }' > "$JOB_CONTROL"
  echo "GATE: job_start set to false"
fi

# 2) Stop any active controller and codex worker (graceful TERM, then KILL).
if [[ $NO_STOP -ne 1 ]]; then
  stopped=0
  for pat in 'com.rahmatwahab.codexautomation.Main' "codex --cd $REPO_PATH" 'codex-code-mode-host'; do
    if pgrep -f "$pat" >/dev/null 2>&1; then
      pkill -TERM -f "$pat" && stopped=1 || true
    fi
  done
  if [[ $stopped -eq 1 ]]; then
    echo "STOP: sent TERM to active processes; waiting up to 10s..."
    for _ in $(seq 1 10); do
      pgrep -f 'com.rahmatwahab.codexautomation.Main' >/dev/null 2>&1 || break
      sleep 1
    done
    # Force-kill anything still alive.
    for pat in 'com.rahmatwahab.codexautomation.Main' "codex --cd $REPO_PATH" 'codex-code-mode-host'; do
      pkill -KILL -f "$pat" 2>/dev/null || true
    done
  fi
  echo "STOP: no controller process remaining"
fi

# 3) Back up and remove the durable state for this EPIC.
if compgen -G "$STATE_DIR/epic-$EPIC.*" >/dev/null; then
  backup="$STATE_DIR/backup-epic-$EPIC-$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$backup"
  for f in "$STATE_DIR/epic-$EPIC.sqlite3" "$STATE_DIR/epic-$EPIC.json" \
           "$STATE_DIR/epic-$EPIC.events.jsonl"; do
    [[ -f "$f" ]] && cp -p "$f" "$backup/" || true
  done
  echo "BACKUP: state copied to $backup"
  rm -f "$STATE_DIR/epic-$EPIC.sqlite3" \
        "$STATE_DIR/epic-$EPIC.json" \
        "$STATE_DIR/epic-$EPIC.events.jsonl" \
        "$STATE_DIR/epic-$EPIC.lock"
  rm -f "$STATE_DIR/epic-$EPIC.lock.watchdog.json" 2>/dev/null || true
  echo "RESET: removed durable state for EPIC #$EPIC (backup retained)"
else
  echo "RESET: no state files found for EPIC #$EPIC (already clean)"
fi

# 4) Optional, opt-in repository cleanup (your work — only when requested).
if [[ $CLEAN_REPO -eq 1 && -d "$REPO_PATH/.git" ]]; then
  current_branch="$(git -C "$REPO_PATH" branch --show-current 2>/dev/null || echo "")"
  echo "REPO: current branch is '$current_branch'"
  git -C "$REPO_PATH" switch "$BASE_BRANCH" 2>/dev/null || git -C "$REPO_PATH" checkout "$BASE_BRANCH"
  git -C "$REPO_PATH" fetch origin "$BASE_BRANCH" || true
  git -C "$REPO_PATH" pull --ff-only origin "$BASE_BRANCH" || \
    echo "REPO: warning — could not fast-forward $BASE_BRANCH (diverged or dirty)"
  echo "REPO: switched to $BASE_BRANCH and synced"

  if [[ $DELETE_BRANCH -eq 1 && -n "$current_branch" && "$current_branch" != "$BASE_BRANCH" ]]; then
    echo "REPO: deleting story branch '$current_branch' (local + origin)"
    git -C "$REPO_PATH" branch -D "$current_branch" 2>/dev/null || true
    git -C "$REPO_PATH" push origin --delete "$current_branch" 2>/dev/null || \
      echo "REPO: warning — could not delete remote branch (may not exist)"
  fi
fi

# 5) Optional, opt-in PR close (destructive GitHub action).
if [[ -n "$CLOSE_PR" ]]; then
  owner="$(jq -er '.repository.owner' "$CONFIG")"
  name="$(jq -er '.repository.name' "$CONFIG")"
  echo "GITHUB: closing PR #$CLOSE_PR on $owner/$name"
  gh pr close "$CLOSE_PR" --repo "$owner/$name" || \
    echo "GITHUB: warning — could not close PR #$CLOSE_PR"
fi

echo "DONE. To resume later: set config/job-control.json to { \"job_start\": true } and run scripts/run_once.sh"
