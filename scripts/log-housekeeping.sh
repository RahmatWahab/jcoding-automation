#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/logs"
ACTIVE="$LOG_DIR/cron-system.log"
LOCK_FILE="$ROOT/state/log-housekeeping.lock"

mkdir -p "$LOG_DIR" "$ROOT/state"
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "SKIPPED: log housekeeping is already running"; exit 0; }

if [[ -f "$ACTIVE" && -s "$ACTIVE" ]]; then
  archive="$LOG_DIR/cron-system-$(date -u +%Y%m%d).log"
  cp --preserve=mode,timestamps "$ACTIVE" "$archive"
  : > "$ACTIVE"
  echo "ROTATED: $ACTIVE -> $archive"
else
  echo "ROTATED: active cron log is empty or absent"
fi

# Delete only generated automation logs older than one day. Never remove
# state, source, lock directories, .gitkeep, or unrelated log files.
find "$LOG_DIR" -maxdepth 1 -type f \
  \( -name 'run-*.log' -o -name 'cron-system-*.log' -o -name 'housekeeping-*.log' \) \
  -mtime +1 -print -delete
echo "HOUSEKEEPING COMPLETE: $(date -u +%FT%TZ)"
