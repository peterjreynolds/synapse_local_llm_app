#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <idempotent release command...>" >&2
  exit 64
fi

max_attempts="${SYNAPSE_RELEASE_MAX_ATTEMPTS:-6}"
base_delay_seconds="${SYNAPSE_RELEASE_RETRY_BASE_DELAY_SECONDS:-5}"

if ! [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]] || [ "$max_attempts" -gt 10 ]; then
  echo "SYNAPSE_RELEASE_MAX_ATTEMPTS must be an integer from 1 through 10." >&2
  exit 64
fi
if ! [[ "$base_delay_seconds" =~ ^[0-9]+$ ]] || [ "$base_delay_seconds" -gt 60 ]; then
  echo "SYNAPSE_RELEASE_RETRY_BASE_DELAY_SECONDS must be an integer from 0 through 60." >&2
  exit 64
fi

operation_name="${SYNAPSE_RELEASE_OPERATION:-$1}"
attempt=1

while true; do
  printf 'Release operation %s, attempt %s/%s.\n' "$operation_name" "$attempt" "$max_attempts" >&2
  if "$@"; then
    exit 0
  else
    command_status=$?
  fi

  if [ "$attempt" -ge "$max_attempts" ]; then
    printf 'Release operation %s failed after %s attempts.\n' "$operation_name" "$max_attempts" >&2
    exit "$command_status"
  fi

  sleep_seconds=$((base_delay_seconds * attempt))
  printf 'Release operation %s failed; retrying in %s seconds.\n' "$operation_name" "$sleep_seconds" >&2
  sleep "$sleep_seconds"
  attempt=$((attempt + 1))
done
