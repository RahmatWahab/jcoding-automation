#!/usr/bin/env bash
# go-autonomous.sh — guided setup for jcoding-automation.
#
# Interactively collects what only YOU know (GitHub repo, EPIC/story, test
# command, CI check names, workflow permissions) and writes a valid
# config/config.json using jq. Safe defaults are pre-filled; the two settings
# the controller hard-rejects (codex.approval_policy and
# codex.dangerously_bypass_sandbox) default to their only working values and
# require an explicit, loud override to change.
#
# It then walks the SAFE rollout sequence the controller enforces:
#   readiness -> provider canary -> one canary story -> flip to autonomous.
# There is no shortcut around the canary: autonomous mode cannot run without a
# canary receipt, and that receipt can only be earned in canary mode.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CONFIG="$ROOT/config/config.json"
EXAMPLE="$ROOT/config/config.json.example"
JOB_CONTROL="$ROOT/config/job-control.json"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required but not installed." >&2; exit 1; }

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
warn()  { printf '\033[33m%s\033[0m\n' "$*" >&2; }
err()   { printf '\033[31m%s\033[0m\n' "$*" >&2; }
rule()  { printf '%s\n' "------------------------------------------------------------"; }

ask() { # ask "Prompt" "default" -> echoes answer
  local prompt="$1" def="${2:-}" ans
  if [[ -n "$def" ]]; then
    read -r -p "$prompt [$def]: " ans || true
    echo "${ans:-$def}"
  else
    read -r -p "$prompt: " ans || true
    echo "$ans"
  fi
}

ask_bool() { # ask_bool "Prompt" "true|false" -> echoes true/false
  local prompt="$1" def="${2:-false}" ans
  while true; do
    read -r -p "$prompt (y/n) [$([[ $def == true ]] && echo y || echo n)]: " ans || true
    ans="${ans:-$([[ $def == true ]] && echo y || echo n)}"
    case "$ans" in
      y|Y|yes|YES) echo true; return ;;
      n|N|no|NO)   echo false; return ;;
      *) warn "Please answer y or n." ;;
    esac
  done
}

# ---------------------------------------------------------------------------
bold "jcoding-automation — guided setup"
rule

# 0. First-run bootstrap from the example.
if [[ ! -f "$CONFIG" ]]; then
  [[ -f "$EXAMPLE" ]] || { err "Missing $EXAMPLE — cannot bootstrap config."; exit 1; }
  cp "$EXAMPLE" "$CONFIG"
  echo "Created $CONFIG from config.json.example (first-time setup)."
else
  echo "Updating existing $CONFIG (a timestamped backup is taken)."
fi
BACKUP="$CONFIG.bak-$(date -u +%Y%m%dT%H%M%SZ)"
cp "$CONFIG" "$BACKUP"
echo "Backup: $BACKUP"
rule

# 1. GitHub repository.
GH_URL="$(ask "GitHub repository URL (https://github.com/<owner>/<name>)")"
# Accept URL or owner/name; strip protocol, host, trailing .git and slashes.
SLUG="${GH_URL#http://}"; SLUG="${SLUG#https://}"; SLUG="${SLUG#github.com/}"
SLUG="${SLUG%.git}"; SLUG="${SLUG%/}"
OWNER="${SLUG%%/*}"; NAME="${SLUG##*/}"
if [[ "$SLUG" != */* || -z "$OWNER" || -z "$NAME" ]]; then
  err "Could not parse owner/name from '$GH_URL'. Expected https://github.com/owner/name."
  exit 1
fi
LOCAL_PATH="$(ask "Local checkout path of that repo (must be a separate tree from this installation)" "/local/$NAME")"
BASE_BRANCH="$(ask "Base branch" "main")"
echo "Parsed repo: owner=$OWNER name=$NAME"
rule

# 2. Mode: canary or autonomous (autonomous still runs the canary once).
bold "Deployment goal"
echo "  1) canary      — prove the pipeline on ONE story first (no merge)."
echo "  2) autonomous  — EPIC-driven, hands-off, auto-merge."
echo "     (autonomous still earns its canary receipt once, automatically)."
GOAL=""
while [[ "$GOAL" != "canary" && "$GOAL" != "autonomous" ]]; do
  case "$(ask "Choose 1 (canary) or 2 (autonomous)" "2")" in
    1|canary) GOAL="canary" ;;
    2|autonomous) GOAL="autonomous" ;;
    *) warn "Enter 1 or 2." ;;
  esac
done

EPIC="$(ask "EPIC issue number (the EPIC the controller drives)")"
[[ "$EPIC" =~ ^[0-9]+$ && "$EPIC" -gt 0 ]] || { err "EPIC number must be a positive integer."; exit 1; }

# The canary story is ALWAYS needed: either as the explicit canary target, or
# as the one-time proof before autonomous is unlocked.
if [[ "$GOAL" == "canary" ]]; then
  CANARY_STORY="$(ask "Story number to run as the canary (must belong to EPIC $EPIC)")"
else
  echo "Autonomous mode must pass a one-time canary before it can merge on its own."
  CANARY_STORY="$(ask "One story number under EPIC $EPIC to use for that single canary run")"
fi
[[ "$CANARY_STORY" =~ ^[0-9]+$ && "$CANARY_STORY" -gt 0 ]] || { err "Canary story must be a positive integer."; exit 1; }
rule

# 3. Verification + CI checks (repo-specific; cannot be defaulted safely).
bold "Verification & CI gates (required for unattended execution)"
TEST_CMD="$(ask "Executable test/verify command for $NAME" "./gradlew test")"
AC_ID="$(ask "Acceptance-criterion ID this check covers" "AC-1")"
echo "Enter the EXACT GitHub check-run name(s) that must be green before merge."
echo "Tip: list them with  gh pr checks --repo $OWNER/$NAME <open-pr-number>"
CI_CHECKS_RAW="$(ask "CI check name(s), comma-separated")"
[[ -n "$CI_CHECKS_RAW" ]] || { err "At least one CI check name is required for unattended rollout."; exit 1; }
# Build a JSON array from the comma-separated list.
CI_CHECKS_JSON="$(printf '%s' "$CI_CHECKS_RAW" | jq -R 'split(",") | map(gsub("^\\s+|\\s+$";"")) | map(select(length>0))')"
rule

# 4. Workflow permissions (true/false), with safe guidance.
bold "Workflow permissions"
ALLOW_PUSH="$(ask_bool "Allow committing & pushing worker changes" true)"
ALLOW_PR="$(ask_bool "Allow opening pull requests" true)"
ALLOW_COMMENTS="$(ask_bool "Allow posting review-trigger & fix comments" true)"
ALLOW_THREADS="$(ask_bool "Allow resolving review threads after a verified fix" true)"
INTERNAL_REVIEW="$(ask_bool "Enable pre-commit specialist internal review" true)"
DRY_RUN="$(ask_bool "Dry-run (no real GitHub mutations)" false)"
rule

# 5. Safety-critical codex settings — defaulted to the only accepted values.
bold "Safety settings (recommended: keep defaults)"
APPROVAL_POLICY="never"
BYPASS_SANDBOX="false"
if [[ "$(ask_bool "Change codex.approval_policy away from 'never'? (NOT recommended — controller rejects anything else)" false)" == true ]]; then
  warn "The controller BLOCKS any approval_policy other than 'never'. Keeping 'never'."
fi
if [[ "$(ask_bool "Disable the worker sandbox (dangerously_bypass_sandbox=true)? (NOT recommended — controller rejects it)" false)" == true ]]; then
  warn "The controller BLOCKS dangerously_bypass_sandbox=true. Keeping it false."
fi
echo "Using: approval_policy=$APPROVAL_POLICY, dangerously_bypass_sandbox=$BYPASS_SANDBOX, isolation=bubblewrap"
rule

# ---------------------------------------------------------------------------
# Compose config.json. Canary phase values first; autonomous flip comes after
# the receipt is earned. In canary mode auto_merge MUST be false.
if [[ "$GOAL" == "canary" ]]; then
  INIT_MODE="canary";    INIT_AUTOMERGE="false"
else
  # Start in canary to earn the receipt; we flip to autonomous after it passes.
  INIT_MODE="canary";    INIT_AUTOMERGE="false"
fi

TMP="$(mktemp)"
jq \
  --arg local_path "$LOCAL_PATH" \
  --arg owner "$OWNER" \
  --arg name "$NAME" \
  --arg base "$BASE_BRANCH" \
  --argjson epic "$EPIC" \
  --argjson canary_story "$CANARY_STORY" \
  --arg mode "$INIT_MODE" \
  --argjson dry_run "$DRY_RUN" \
  --argjson allow_push "$ALLOW_PUSH" \
  --argjson allow_pr "$ALLOW_PR" \
  --argjson allow_comments "$ALLOW_COMMENTS" \
  --argjson allow_threads "$ALLOW_THREADS" \
  --argjson internal_review "$INTERNAL_REVIEW" \
  --argjson auto_merge "$INIT_AUTOMERGE" \
  --argjson ci_checks "$CI_CHECKS_JSON" \
  --arg test_cmd "$TEST_CMD" \
  --arg ac_id "$AC_ID" \
  --arg approval "$APPROVAL_POLICY" \
  --argjson bypass "$BYPASS_SANDBOX" \
  '
  .repository.local_path = $local_path
  | .repository.owner = $owner
  | .repository.name = $name
  | .repository.base_branch = $base
  | .epic.number = $epic
  | .automation.dry_run = $dry_run
  | .automation.enable_planning = true
  | .codex.approval_policy = $approval
  | .codex.dangerously_bypass_sandbox = $bypass
  | .codex.isolation = "bubblewrap"
  | .workflow.allow_push = $allow_push
  | .workflow.allow_pr_creation = $allow_pr
  | .workflow.allow_comments = $allow_comments
  | .workflow.allow_thread_resolution = $allow_threads
  | .workflow.internal_review = $internal_review
  | .workflow.auto_merge = $auto_merge
  | .workflow.allow_no_checks = false
  | .workflow.required_checks = $ci_checks
  | .intent.require_acceptance_criteria = true
  | .deployment.mode = $mode
  | .deployment.canary_story = $canary_story
  | .verification.required = true
  | .verification.checks = [ { "id": "unit", "command": $test_cmd, "criteria": [ $ac_id ] } ]
  ' "$CONFIG" > "$TMP"
mv "$TMP" "$CONFIG"

bold "Wrote $CONFIG"
echo "  repo          : $OWNER/$NAME ($LOCAL_PATH)"
echo "  epic          : #$EPIC"
echo "  canary story  : #$CANARY_STORY"
echo "  verify command: $TEST_CMD  (criteria: $AC_ID)"
echo "  CI checks     : $CI_CHECKS_RAW"
echo "  goal          : $GOAL  (starting in canary to earn the receipt)"
echo "  dry_run       : $DRY_RUN"
rule

# ---------------------------------------------------------------------------
# Guided rollout sequence.
bold "Next: the controller-enforced rollout sequence"
echo "This script will now check readiness. Each later step is OPT-IN so you"
echo "stay in control; nothing merges during the canary."
rule

echo "Step 1/4 — readiness check (read-only)"
if scripts/run_once.sh --readiness; then
  echo "Readiness OK."
else
  err "Readiness reported gaps above. Fix them (re-run this script) before continuing."
  exit 1
fi
rule

if [[ "$(ask_bool "Step 2/4 — run the read-only provider canary now?" true)" == true ]]; then
  scripts/run_once.sh --provider-canary || { err "Provider canary failed — see log above."; exit 1; }
  echo "Provider canary recorded."
fi
rule

if [[ "$(ask_bool "Step 3/4 — run the canary story (#$CANARY_STORY) now? (job_start must be true)" false)" == true ]]; then
  jq -e '.job_start == true' "$JOB_CONTROL" >/dev/null 2>&1 \
    || warn "job-control.json is not {\"job_start\": true}; the run will be SKIPPED until you enable it."
  echo "Driving the canary story. This runs the full implement -> PR -> CI -> review loop."
  echo "Re-run 'scripts/run_once.sh' on each tick (or let cron do it) until the PR passes"
  echo "required CI + review; the controller then records the canary receipt."
  scripts/run_once.sh || true
fi
rule

if [[ "$GOAL" == "autonomous" ]]; then
  bold "Step 4/4 — flip to autonomous"
  echo "Only AFTER the canary receipt exists (the canary PR passed CI + review),"
  echo "flip the mode and enable auto-merge. Flipping auto_merge does NOT invalidate"
  echo "the receipt; changing checks/codex/repo/intent/automation WOULD."
  if [[ "$(ask_bool "Flip deployment.mode=autonomous and workflow.auto_merge=true now?" false)" == true ]]; then
    TMP="$(mktemp)"
    jq '.deployment.mode = "autonomous" | .workflow.auto_merge = true' "$CONFIG" > "$TMP" && mv "$TMP" "$CONFIG"
    echo "Flipped to autonomous + auto_merge. Verifying readiness..."
    scripts/run_once.sh --readiness || warn "Readiness gaps remain — autonomous will block until resolved."
    echo "From now on the controller is EPIC-driven and hands-off on each cron tick."
  else
    echo "Left in canary mode. Re-run this script and choose 'flip' once the receipt is earned."
  fi
fi
rule
bold "Done."
echo "Backup of your previous config: $BACKUP"
