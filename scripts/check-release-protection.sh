#!/usr/bin/env bash
set -euo pipefail

# Read-only verification of the controller repository's required CI check.
repo="$(gh repo view --json nameWithOwner --jq '.nameWithOwner')"
branch="${1:-main}"
required='Business value mocks and static policy'
json="$(gh api "repos/${repo}/branches/${branch}/protection/required_status_checks")"
if ! jq -e --arg required "$required" '
  (.contexts // [] | index($required) != null)
  or (.checks // [] | any(.context == $required))
' <<<"$json" >/dev/null; then
  printf 'Required release gate is missing from %s branch protection: %s\n' "$branch" "$required" >&2
  exit 1
fi
printf 'Required release gate is protected on %s/%s\n' "$repo" "$branch"
