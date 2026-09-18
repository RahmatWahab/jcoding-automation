#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POLICY_FILE="${CODEX_CRON_POLICY:-$ROOT/config/cron-policy.json}"
MARKER="# BEGIN JCODING-AUTOMATION"
END_MARKER="# END JCODING-AUTOMATION"

if [[ ! -f "$POLICY_FILE" ]]; then
  echo "ERROR: cron policy not found: $POLICY_FILE" >&2
  exit 1
fi

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required to read the JSON cron policy" >&2; exit 1; }

read_entry() {
  local key="$1"
  local entry
  entry="$(jq -er ".${key}" "$POLICY_FILE" 2>/dev/null || true)"
  if [[ -z "$entry" || "$entry" == "null" ]]; then
    echo "ERROR: ${key} is missing from cron policy" >&2
    exit 1
  fi
  # A valid cron command has at least 6 whitespace-separated fields.
  if [[ "$(wc -w <<<"$entry")" -lt 6 ]]; then
    echo "ERROR: ${key} is not a valid cron command" >&2
    exit 1
  fi
  printf '%s\n' "$entry"
}

CRON_ENTRY="$(read_entry cron_entry)"
HOUSEKEEPING_ENTRY="$(read_entry housekeeping_cron_entry)"

CURRENT="$(crontab -l 2>/dev/null || true)"

# Remove only the block previously installed by this application.
CLEANED="$(printf '%s\n' "$CURRENT" | awk -v start="$MARKER" -v end="$END_MARKER" '
  $0 == start { skip=1; next }
  $0 == end { skip=0; next }
  !skip { print }
')"

TMP_FILE="$(mktemp)"
trap 'rm -f "$TMP_FILE"' EXIT

{
  printf '%s\n' "$CLEANED"
  printf '%s\n' "$MARKER"
  printf '%s\n' "$CRON_ENTRY"
  printf '%s\n' "$HOUSEKEEPING_ENTRY"
  printf '%s\n' "$END_MARKER"
} | sed '/^$/N;/^$\n$/D' > "$TMP_FILE"

crontab "$TMP_FILE"
echo "Installed jcoding-automation cron policy from: $POLICY_FILE"
echo "$CRON_ENTRY"
echo "$HOUSEKEEPING_ENTRY"
