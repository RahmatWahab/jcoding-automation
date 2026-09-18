#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Interactive profiles normally add these directories, but cron does not.
export PATH="$HOME/.local/bin:$HOME/bin:$PATH"

# Cron does not inherit interactive-shell exports. Load secrets only from the
# operator-selected file; never print its contents or store them in config.
ENV_FILE="${CODEX_AUTOMATION_ENV_FILE:-$HOME/.config/jcoding-automation/env}"
if [[ -f "$ENV_FILE" ]]; then
  mode="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || true)"
  if [[ "$mode" != "600" ]]; then
    echo "BLOCKED: environment file must have mode 600: $ENV_FILE" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

# Support operators who intentionally export GH_TOKEN from .bashrc, without
# sourcing all of .bashrc (interactive guards and arbitrary commands are unsafe).
if [[ -z "${GH_TOKEN:-}" && -f "$HOME/.bashrc" ]]; then
  set +u
  while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*export[[:space:]]+GH_TOKEN= ]]; then
      eval "$line"
      break
    fi
  done < "$HOME/.bashrc"
  set -u
fi

exec "$ROOT/scripts/run_once.sh"
