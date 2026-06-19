#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <gradle task...>" >&2
  exit 64
fi

attempt=1
max_attempts=3

while true; do
  echo "Gradle attempt ${attempt}/${max_attempts}: ./gradlew $*"
  if ./gradlew "$@"; then
    exit 0
  fi

  if [ "$attempt" -ge "$max_attempts" ]; then
    echo "Gradle command failed after ${max_attempts} attempts: ./gradlew $*" >&2
    exit 1
  fi

  attempt=$((attempt + 1))
  sleep_seconds=$((attempt * 20))
  echo "Gradle command failed; retrying in ${sleep_seconds}s."
  sleep "$sleep_seconds"
done
