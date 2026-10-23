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

# 3. Verification + CI checks. Both are auto-detected from the repository; press
# Enter to accept the detected value. They are still REQUIRED by the controller
# (auto-merge without proof would merge unverified AI-written code), but you
# should not have to hand-type them.
bold "Verification & CI gates (auto-detected — press Enter to accept)"

detect_test_command() { # echoes a space-separated command, or nothing
  local d="$1"
  [[ -d "$d" ]] || return 0
  if   [[ -x "$d/gradlew" ]];                  then echo "./gradlew test"
  elif [[ -f "$d/pom.xml" ]];                  then echo "mvn -B test"
  elif [[ -f "$d/package.json" ]] && grep -q '"test"' "$d/package.json"; then echo "npm test"
  elif [[ -f "$d/go.mod" ]];                   then echo "go test ./..."
  elif [[ -f "$d/pyproject.toml" || -f "$d/pytest.ini" || -f "$d/setup.py" ]]; then echo "python3 -m pytest"
  elif [[ -f "$d/Makefile" ]] && grep -qE '^test:' "$d/Makefile"; then echo "make test"
  fi
}

detect_ci_checks() { # echoes comma-separated check-run names, or nothing
  local names=""
  # 1) Real check-run names from the base branch head (exact match to what the gate compares).
  names="$(gh api "repos/$OWNER/$NAME/commits/$BASE_BRANCH/check-runs?per_page=100" \
            --jq '[.check_runs[].name] | unique | join(",")' 2>/dev/null || true)"
  # 2) Fallback: job names declared in workflow files (needs python3 + PyYAML).
  if [[ -z "$names" && -d "$LOCAL_PATH/.github/workflows" ]] && command -v python3 >/dev/null 2>&1; then
    names="$(python3 - "$LOCAL_PATH/.github/workflows" <<'PY' 2>/dev/null || true
import sys, glob, os
try:
    import yaml
except ImportError:
    sys.exit(0)
out = []
for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.y*ml"))):
    try:
        doc = yaml.safe_load(open(f)) or {}
    except Exception:
        continue
    for job_id, job in (doc.get("jobs") or {}).items():
        out.append(str((job or {}).get("name") or job_id))
print(",".join(dict.fromkeys(out)))
PY
)"
  fi
  printf '%s' "$names"
}

DETECTED_CMD="$(detect_test_command "$LOCAL_PATH")"
if [[ -n "$DETECTED_CMD" ]]; then
  echo "Detected test command from $LOCAL_PATH: $DETECTED_CMD"
else
  warn "Could not detect a test command in $LOCAL_PATH (path missing or unknown build tool)."
fi
TEST_CMD="$(ask "Verification command" "${DETECTED_CMD:-./gradlew test}")"
# The controller runs the command as an argv array in a sandbox with NO network:
# build dependencies must already be cached locally (e.g. a prior ./gradlew test).
TEST_CMD_JSON="$(printf '%s\n' "$TEST_CMD" | jq -R 'split(" ") | map(select(length>0))')"
[[ "$TEST_CMD_JSON" != "[]" ]] || { err "Verification command cannot be empty."; exit 1; }
# '*' = this check covers every acceptance criterion in the story it verifies,
# so you never need to match per-story AC-N ids. The check must still pass.
CRITERIA_JSON='["*"]'

DETECTED_CI="$(detect_ci_checks)"
if [[ -n "$DETECTED_CI" ]]; then
  echo "Detected CI check(s) for $OWNER/$NAME: $DETECTED_CI"
else
  echo "No CI checks detected for $OWNER/$NAME on '$BASE_BRANCH' (no workflow runs, or gh not authenticated)."
fi
echo "CI checks are OPTIONAL. Leave blank to run without a CI gate."
CI_CHECKS_RAW="$(ask "Required CI check name(s), comma-separated (blank = no CI gate)" "$DETECTED_CI")"
CI_CHECKS_JSON="$(printf '%s\n' "$CI_CHECKS_RAW" | jq -R 'split(",") | map(gsub("^\\s+|\\s+$";"")) | map(select(length>0))')"
if [[ "$CI_CHECKS_JSON" == "[]" ]]; then
  ALLOW_NO_CHECKS="true"
  warn "No CI gate: merges will rely only on the controller's own verification ($TEST_CMD),"
  warn "Codex review, and unresolved-thread checks. Add CI later and re-run this script to tighten it."
  CI_CHECKS_RAW="(none — CI gate disabled)"
else
  ALLOW_NO_CHECKS="false"
fi
rule

# 4. Workflow permissions (true/false), with safe guidance.
bold "Workflow permissions"
ALLOW_PUSH="$(ask_bool "Allow committing & pushing worker changes" true)"
ALLOW_PR="$(ask_bool "Allow opening pull requests" true)"
ALLOW_COMMENTS="$(ask_bool "Allow posting review-trigger & fix comments" true)"
ALLOW_THREADS="$(ask_bool "Allow resolving review threads after a verified fix" true)"
INTERNAL_REVIEW="$(ask_bool "Enable pre-commit specialist internal review" true)"
DRY_RUN="$(ask_bool "Dry-run (no real GitHub mutations)" false)"
if [[ "$DRY_RUN" == true ]]; then
  warn "Dry-run only previews: no commit, push, PR or comment is made, so the canary story can"
  warn "never pass CI/review and the canary receipt can NOT be earned. Turn dry-run off to reach autonomous."
fi
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

# 5b. How the sandboxed Codex worker authenticates to the model.
bold "Codex authentication"
echo "  1) API key via isolated gateway (recommended). The worker never sees a credential and has"
echo "     no network of its own. Needs OPENAI_API_KEY (platform.openai.com, billed separately)."
echo "  2) Your Codex CLI login (no API key). The worker holds a COPY of your login token and the"
echo "     Codex process has outbound network."
AUTH_MODE=""
while [[ -z "$AUTH_MODE" ]]; do
  case "$(ask "Choose 1 (API key) or 2 (Codex login)" "1")" in
    1|gateway) AUTH_MODE="gateway" ;;
    2|login|codex_login) AUTH_MODE="codex_login" ;;
    *) warn "Enter 1 or 2." ;;
  esac
done
if [[ "$AUTH_MODE" == "codex_login" ]]; then
  CODEX_BIN="$(jq -r '.codex.executable // "codex"' "$CONFIG")"
  if ! command -v "$CODEX_BIN" >/dev/null 2>&1 || ! "$CODEX_BIN" login status >/dev/null 2>&1; then
    err "'$CODEX_BIN login status' failed. Run '$CODEX_BIN login' as this user first, then re-run."
    exit 1
  fi
  warn "Codex login mode weakens the isolation. Be aware that:"
  warn "  - model-driven code in the worker can READ the login token (it cannot send it out from"
  warn "    Codex's tool sandbox, but it could write it into a file the controller would commit);"
  warn "  - the controller blocks delivery if a changed file contains the token (best effort: it"
  warn "    checks the plain value and base64 forms only);"
  warn "  - the Codex process has full outbound network, not just a gateway to one host;"
  warn "  - unattended use of a ChatGPT-plan login may be subject to your plan's terms."
  read -r -p "Type I ACCEPT to use your Codex login for unattended runs: " CONFIRM || true
  if [[ "$CONFIRM" != "I ACCEPT" ]]; then
    err "Not accepted. Re-run and choose 1 (API key), or type exactly: I ACCEPT"
    exit 1
  fi
fi
echo "Using auth mode: $AUTH_MODE"
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
  --argjson allow_no_checks "$ALLOW_NO_CHECKS" \
  --argjson test_cmd "$TEST_CMD_JSON" \
  --argjson criteria "$CRITERIA_JSON" \
  --arg approval "$APPROVAL_POLICY" \
  --arg auth_mode "$AUTH_MODE" \
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
  | .codex.auth = $auth_mode
  | .workflow.allow_push = $allow_push
  | .workflow.allow_pr_creation = $allow_pr
  | .workflow.allow_comments = $allow_comments
  | .workflow.allow_thread_resolution = $allow_threads
  | .workflow.internal_review = $internal_review
  | .workflow.auto_merge = $auto_merge
  | .workflow.allow_no_checks = $allow_no_checks
  | .workflow.required_checks = $ci_checks
  | .intent.require_acceptance_criteria = true
  | .deployment.mode = $mode
  | .deployment.canary_story = $canary_story
  | .verification.required = true
  | .verification.checks = [ { "id": "unit", "command": $test_cmd, "criteria": $criteria } ]
  ' "$CONFIG" > "$TMP"
mv "$TMP" "$CONFIG"

bold "Wrote $CONFIG"
echo "  repo          : $OWNER/$NAME ($LOCAL_PATH)"
echo "  epic          : #$EPIC"
echo "  canary story  : #$CANARY_STORY"
echo "  verify command: $TEST_CMD  (covers all story criteria)"
echo "  CI checks     : $CI_CHECKS_RAW"
echo "  goal          : $GOAL  (starting in canary to earn the receipt)"
echo "  codex auth    : $AUTH_MODE"
echo "  dry_run       : $DRY_RUN"
rule

# ---------------------------------------------------------------------------
# Host preflight: the worker sandbox (bubblewrap with a private network
# namespace and working loopback). On Ubuntu 23.10+/24.04 AppArmor strips the
# capabilities of unprivileged user namespaces, which makes bwrap fail with
# "loopback: Failed RTM_NEWADDR". The narrow fix is an AppArmor profile that
# lets only /usr/bin/bwrap use user namespaces. It needs root, so we ask first.
SYSCTL_ROOT="${JCODING_SYSCTL_ROOT:-/proc/sys}"
APPARMOR_DIR="${JCODING_APPARMOR_DIR:-/etc/apparmor.d}"

bwrap_probe() { # mirrors HostReadiness.privateNetworkProbeCommand(); prints bwrap's own error
  # Bind the same system paths as the real sandbox. Binding only /usr fails on merged-/usr
  # hosts (Ubuntu/Debian): the dynamic loader is /lib64/ld-linux-*, so python3 cannot exec.
  local mounts=() p
  for p in /usr /bin /sbin /lib /lib64; do
    [[ -e "$p" ]] && mounts+=(--ro-bind "$p" "$p")
  done
  timeout 10 bwrap --die-with-parent --unshare-all "${mounts[@]}" --dev /dev --proc /proc -- \
    /usr/bin/python3 -c "import socket; s=socket.socket(); s.bind(('127.0.0.1',0)); s.listen(1); c=socket.socket(); c.connect(s.getsockname()); c.close(); s.close()" 2>&1
}

ensure_sandbox_ready() {
  bold "Host check: worker sandbox (bubblewrap)"
  if ! command -v bwrap >/dev/null 2>&1; then
    err "bwrap is not installed. Install it with:  sudo apt-get install -y bubblewrap"
    return 1
  fi
  local out
  if out="$(bwrap_probe)"; then
    echo "Sandbox OK (private network namespace with working loopback)."
    return 0
  fi
  warn "Sandbox probe failed: ${out:-no output}"

  local restrict
  restrict="$(cat "$SYSCTL_ROOT/kernel/apparmor_restrict_unprivileged_userns" 2>/dev/null || echo 0)"
  if [[ "$restrict" != "1" ]] || ! grep -qiE 'RTM_NEWADDR|No permissions|Operation not permitted|Permission denied' <<<"$out" \
      || ! command -v apparmor_parser >/dev/null 2>&1; then
    err "This is not the known Ubuntu AppArmor restriction, so it cannot be fixed automatically."
    err "Check: user.max_user_namespaces, kernel.unprivileged_userns_clone, and whether the VPS allows namespaces."
    return 1
  fi

  echo "Cause: kernel.apparmor_restrict_unprivileged_userns=1 (Ubuntu) removes the capabilities bwrap needs."
  echo "Fix  : install an AppArmor profile that lets ONLY /usr/bin/bwrap use user namespaces:"
  echo "         $APPARMOR_DIR/bwrap   (then: apparmor_parser -r)"
  echo "       Other programs stay restricted. Requires root (sudo)."
  local profile="$APPARMOR_DIR/bwrap"
  if [[ -e "$profile" ]]; then
    err "$profile already exists; not overwriting it. Review it, make sure it has a 'userns,' rule, then re-run."
    return 1
  fi
  local sudo_cmd="${JCODING_SUDO-sudo}"
  [[ "$EUID" -eq 0 ]] && sudo_cmd=""
  if [[ -n "$sudo_cmd" ]] && ! command -v "$sudo_cmd" >/dev/null 2>&1; then
    err "sudo is not available. Run the two steps above as root, then re-run this script."
    return 1
  fi
  if [[ "$(ask_bool "Install this AppArmor profile now?" false)" != true ]]; then
    err "Not applied. The worker sandbox will not start until this is fixed."
    return 1
  fi

  local tmp; tmp="$(mktemp)"
  cat > "$tmp" <<'EOF'
abi <abi/4.0>,
include <tunables/global>

profile bwrap /usr/bin/bwrap flags=(unconfined) {
  userns,

  include if exists <local/bwrap>
}
EOF
  # shellcheck disable=SC2086
  if ! $sudo_cmd install -m 0644 "$tmp" "$profile" || ! $sudo_cmd apparmor_parser -r "$profile"; then
    rm -f "$tmp"; err "Could not install/load the profile."; return 1
  fi
  rm -f "$tmp"
  echo "Installed $profile. Re-checking the sandbox..."
  if out="$(bwrap_probe)"; then
    echo "Sandbox OK."
    return 0
  fi
  err "Sandbox still failing: ${out:-no output}"
  return 1
}

# ---------------------------------------------------------------------------
# Credentials for unattended (cron) runs. Cron does not inherit shell exports;
# scripts/cron-launcher.sh reads ONLY this mode-600 file. Values are never printed.
ENV_FILE="${CODEX_AUTOMATION_ENV_FILE:-$HOME/.config/jcoding-automation/env}"

env_file_has() { [[ -f "$ENV_FILE" ]] && grep -qE "^$1=.+" "$ENV_FILE"; }

save_env_value() { # $1 = variable name, $2 = value (never echoed)
  local dir tmp; dir="$(dirname "$ENV_FILE")"
  ( umask 077; mkdir -p "$dir"; touch "$ENV_FILE" )
  chmod 600 "$ENV_FILE"
  tmp="$(mktemp "$dir/.env.XXXXXX")"; chmod 600 "$tmp"
  { grep -vE "^$1=" "$ENV_FILE" || true; printf '%s=%s\n' "$1" "$2"; } > "$tmp"
  mv "$tmp" "$ENV_FILE"; chmod 600 "$ENV_FILE"
}

ensure_credentials() {
  bold "Credentials for unattended (cron) runs"
  echo "Cron cannot see your shell exports. It reads only: $ENV_FILE (mode 600)."
  local gh_var key_var name value ok=0 names=()
  gh_var="$(jq -r '.github.token_environment_variable // "GH_TOKEN"' "$CONFIG")"
  key_var="$(jq -r '.codex.gateway.credential_environment_variable // "OPENAI_API_KEY"' "$CONFIG")"
  names=("$gh_var")
  if [[ "$(jq -r '.codex.auth // "gateway"' "$CONFIG")" == "codex_login" ]]; then
    echo "  Codex worker auth: your Codex login ($(command -v codex >/dev/null 2>&1 && echo 'found' || echo 'codex not on PATH')); no API key needed."
    key_var=""
  else
    names+=("$key_var")
  fi
  for name in "${names[@]}"; do
    case "$name" in
      GH_TOKEN|OPENAI_API_KEY|CODEX_API_KEY) ;;
      *) warn "cron-launcher.sh only accepts GH_TOKEN, OPENAI_API_KEY, CODEX_API_KEY; '$name' would be rejected."; ok=1; continue ;;
    esac
    if env_file_has "$name"; then echo "  $name: present in env file"; continue; fi
    warn "  $name: MISSING from the env file"
    if [[ "$name" == "$key_var" ]]; then
      echo "  Why: unattended workers run in a bubblewrap sandbox with an isolated home, so they"
      echo "  cannot use your 'codex login'. The controller's gateway calls the OpenAI API with this"
      echo "  API key and gives the worker only a short-lived token. It is an API key from"
      echo "  platform.openai.com (billed separately from a ChatGPT plan)."
    fi
    value=""
    if [[ -n "${!name:-}" ]]; then
      if [[ "$(ask_bool "  Save $name from your current shell to the env file?" true)" == true ]]; then value="${!name}"; fi
    elif [[ "$name" == "$gh_var" ]] && command -v gh >/dev/null 2>&1 && gh auth token >/dev/null 2>&1; then
      if [[ "$(ask_bool "  Use the token from 'gh auth token'?" true)" == true ]]; then value="$(gh auth token)"; fi
    fi
    if [[ -z "$value" ]]; then
      read -r -s -p "  Paste $name (input hidden, blank to skip): " value || true; echo
    fi
    value="${value//[$'\r\n ']/}"
    if [[ -z "$value" ]]; then err "  $name not provided; the controller will block without it."; ok=1; continue; fi
    save_env_value "$name" "$value"; echo "  $name saved to $ENV_FILE"
  done
  # Make this script's own runs see exactly what cron will see.
  local k v
  while IFS='=' read -r k v; do
    case "$k" in GH_TOKEN|OPENAI_API_KEY|CODEX_API_KEY) export "$k=$v" ;; esac
  done < <([[ -f "$ENV_FILE" ]] && cat "$ENV_FILE" || true)
  return "$ok"
}

canary_receipt_exists() { # read-only look at the durable state; true only if a canary receipt is recorded
  local db="$ROOT/state/epic-$EPIC.sqlite3"
  [[ -f "$db" ]] || return 1
  python3 - "$db" <<'PY' 2>/dev/null
import sqlite3, json, sys
con = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
row = con.execute("SELECT payload FROM workflow_state WHERE id = 1").fetchone()
state = json.loads(row[0]) if row else {}
sys.exit(0 if isinstance(state.get("canary_evidence"), dict) else 1)
PY
}

# ---------------------------------------------------------------------------
# Guided rollout sequence.
bold "Next: the controller-enforced rollout sequence"
echo "This script will now check readiness. Each later step is OPT-IN so you"
echo "stay in control; nothing merges during the canary."
rule

ensure_credentials || { err "Provide the missing credential(s) above, then re-run this script."; exit 1; }
rule
ensure_sandbox_ready || { err "Fix the sandbox issue above, then re-run this script."; exit 1; }
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
  if ! canary_receipt_exists; then
    warn "No canary receipt is recorded yet, so the flip is skipped."
    warn "The receipt appears only after the canary story's PR passes verification, review"
    warn "(and CI, if configured) with dry-run OFF. Cron ticks drive the canary; check progress with:"
    warn "  tail -f logs/cron-system.log"
    warn "Then re-run this script and answer 'y' here."
  else
    echo "Canary receipt found. Flipping does NOT invalidate it (auto_merge is excluded from the"
    echo "fingerprint); changing checks/codex/repo/intent/automation WOULD."
    if [[ "$(ask_bool "Flip deployment.mode=autonomous and workflow.auto_merge=true now?" false)" == true ]]; then
      TMP="$(mktemp)"
      jq '.deployment.mode = "autonomous" | .workflow.auto_merge = true' "$CONFIG" > "$TMP" && mv "$TMP" "$CONFIG"
      echo "Flipped to autonomous + auto_merge. Verifying readiness..."
      scripts/run_once.sh --readiness || warn "Readiness gaps remain — autonomous will block until resolved."
      echo "From now on the controller is EPIC-driven and hands-off on each cron tick."
    else
      echo "Left in canary mode. Re-run this script and choose 'flip' when you are ready."
    fi
  fi
fi
rule
bold "Done."
echo "Backup of your previous config: $BACKUP"
