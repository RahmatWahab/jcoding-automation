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
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ ! "$line" =~ ^(GH_TOKEN|OPENAI_API_KEY|CODEX_API_KEY)=(.*)$ ]]; then
      echo "BLOCKED: unsupported environment assignment in $ENV_FILE" >&2
      exit 1
    fi
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if [[ "$value" == \"*\" && "$value" == *\" ]]; then value="${value:1:${#value}-2}"; fi
    if [[ "$value" == \'*\' && "$value" == *\' ]]; then value="${value:1:${#value}-2}"; fi
    printf -v "$key" '%s' "$value"
    export "$key"
  done < "$ENV_FILE"
fi

exec "$ROOT/scripts/run_once.sh"
