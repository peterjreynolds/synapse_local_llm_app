# Synapse Local LLM App Instructions

This repository follows `/home/notsolikely/AGENTS.md` and the universal engineering doctrine loaded from `/home/notsolikely/.codex`.

## Project Boundary

- Synapse is a standalone Android app for talking to a phone-local LLM.
- Synapse memory is app-local Room/SQLite state. Do not depend on OpenClaw, Wingman sidecars, or Synapse governance runtime.
- The word "Synapse" here names the product. It does not activate historical Synapse governance unless a future repo-local policy explicitly says so.
- Autonomous outbound SMS is allowed only behind the explicit in-app SMS auto-reply toggle.
  When enabled, inbound SMS may be submitted to the local LLM and queued back to
  the originating sender without per-message review. Keep this path auditable,
  permission-gated, app-local, and receipt-backed.

## Engineering Rules

- Keep runtime, memory, storage, persistence, and UI as separate owners.
- Treat llama-server, Termux, Android intents, attachments, and speech APIs as external boundaries.
- Treat inbound SMS broadcasts and outbound SMS sends as external boundaries.
- Memory writes require evidence and receipts.
- Storage pressure must pause memory writes before corrupting local state.
- Do not commit signing keys, model files, generated APKs, or user chat exports.
- APK distribution must use the `apk-latest` orphan branch with one canonical
  `APK/Synapse-AI.apk`. Do not commit APK binaries to `main`.
