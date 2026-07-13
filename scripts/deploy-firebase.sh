#!/usr/bin/env bash
set -euo pipefail

project_id="${1:-synapse-chat-pjr-2026}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repo_root"
npm --prefix firebase ci
npm --prefix firebase/functions ci
npm --prefix firebase/functions test
npx --yes firebase-tools@15.23.0 emulators:exec \
  --project demo-synapse-chat \
  --only firestore,storage \
  'npm --prefix firebase test'
npx --yes firebase-tools@15.23.0 deploy \
  --project "$project_id" \
  --only firestore:rules,firestore:indexes,storage,functions
