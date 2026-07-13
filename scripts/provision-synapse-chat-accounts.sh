#!/usr/bin/env bash
set -euo pipefail

project_id="${1:-synapse-chat-pjr-2026}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repo_root"
export GOOGLE_CLOUD_PROJECT="$project_id"
npm --prefix firebase/functions ci
npm --prefix firebase/functions run provision
