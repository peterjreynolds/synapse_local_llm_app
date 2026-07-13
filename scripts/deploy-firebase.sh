#!/usr/bin/env bash
set -euo pipefail

project_id="${1:-synapse-chat-pjr-2026}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repo_root"
npm --prefix firebase ci
npm --prefix firebase/functions ci
npm --prefix firebase/functions test
npm --prefix firebase exec -- firebase emulators:exec \
  --project demo-synapse-chat \
  --only firestore,storage \
  'npm --prefix firebase test'
npm --prefix firebase exec -- firebase deploy \
  --project "$project_id" \
  --force \
  --only auth,firestore:rules,firestore:indexes,storage,functions
