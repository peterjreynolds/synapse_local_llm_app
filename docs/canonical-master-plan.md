# Synapse AI Canonical Master Plan

Status: active product roadmap

Synapse AI is a native Android, offline-first local LLM app: stable chatbot UI,
embedded `llama.cpp` runtime, evidence-backed memory, indexed research library,
offline voice, sandbox workspace tools, and optional Synapse Governance V3 mode.

## Implemented

- Android-native chat shell with recent chats.
- Embedded ARM64 `llama.cpp` runtime with Termux server fallback.
- Named debug APK output at `app/build/outputs/apk/synapse/Synapse-AI.apk`.
- App-local Room/SQLite chat and evidence-backed memory.
- Storage-pressure guardrails for memory writes.
- Synapse Guild branding.
- Persona and Custom Instructions settings.
- Quiet top-bar runtime status.
- Keyboard-aware chat layout and long-output scroll detach behavior.
- Rotating thinking indicator.
- Stale streaming-message cleanup on app start.
- Assistant output filtering for hidden reasoning, fake role labels, and prompt leakage.
- Per-message speaker playback with play, pause, and resume controls.
- Full app-state debug ZIP export, excluding GGUF model files.

## Remaining Major Tracks

### Offline Voice System

Goal: make Synapse usable by voice without depending on Termux or cloud APIs.

- Add selectable offline assistant voices.
- Add voice settings: voice, speed, pitch, test voice.
- Store voice models separately from the APK, like GGUF model imports.
- Add full Voice Mode as a separate surface from per-message speaker playback:
  listen locally, transcribe locally, send to local LLM, speak locally, loop until stopped.
- Keep v1 push-to-talk / explicit voice mode only. No always-listening wake word.
- Always expose stop/interrupt controls.

### Research Library And RAG

Goal: make Synapse a local librarian plus analyst, not just a chatbot with memory.

- Store original imported documents in app-private storage.
- Extract text from text/Markdown first, then PDF, then exact user-provided URLs.
- Chunk extracted text.
- Generate local embeddings with a small on-device embedding model.
- Save document metadata: title, source, hash, import date, tags, project, topic, status.
- Build catalog indexes before deep retrieval, following the `frontend-playground` pattern.
- Search catalog metadata first, then retrieve matching chunks.
- Add keyword fallback when embeddings are unavailable.
- Inject cited evidence packs into chat prompts.
- Show source citations and retrieval receipts.
- Build relationships later: similarity, topic clusters, stale markers, potential conflicts.

### Synapse Governance V3 Mode

Goal: let the user intentionally enter governed planning mode without turning the
whole app into a giant prompt or hidden runtime.

- Import V3 doctrine as a high-authority local library collection.
- Add a deliberate Synapse Mode toggle or planning entry point.
- Implement app-owned governance routing and state; the LLM assists but does not own state.
- Start with Governance Preflight, Planning, Research Capture, Subject Data layout,
  Control Sync, and Snapshots.
- Add Guild Orders and Quest drafting after the base planner is stable.
- Keep Officer Mode read-only/audit by default unless explicitly delegated.
- Do not add autonomous governance daemons, watchers, or outbound messages.

### Sandbox Workspace Tools

Goal: give Synapse useful local file output without unsafe phone filesystem access.

- Create an app-private Synapse Workspace.
- Allow creating folders, Markdown notes, summaries, research maps, exports, and code/text files.
- Add receipts for file writes.
- Block arbitrary phone filesystem edits.
- Block silent overwrite and deletion without explicit confirmation.

### Model And Runtime Hardening

Goal: make embedded local inference feel reliable on Samsung S25 Ultra.

- Add clearer embedded model health and load receipts.
- Add model metadata/hashing during import.
- Add performance presets for speed/battery/quality.
- Add first-run model downloader/importer with hash verification.
- Keep normal APKs free of GGUF model weights.
- Consider optional release packs with APK and model as separate assets.

### Product Flavors

Goal: share one solid core between Synapse AI and later characters/products.

- Keep Synapse AI as the first product.
- Add Pickle AI later as a separate product flavor.
- Share runtime, chat, memory, library, retrieval, workspace, attachments, and voice core.
- Override branding, persona, bundled docs, and default instructions per flavor.

## Build Order

1. Finish phone QA of the current chat/prompt/diagnostics stabilization slice.
2. Offline voice selection and local speaker voice import.
3. Full offline Voice Mode.
4. Library core schema and text/Markdown ingestion.
5. Retrieval and citation injection.
6. Research graph relationships.
7. Synapse Governance V3 planning mode.
8. Sandbox workspace tools.
9. Model downloader/importer hardening.
10. Pickle AI product flavor.

## Non-Negotiable Boundaries

- No autonomous outbound messaging in v1.
- Memory, research, workspace files, and governance artifacts are separate concerns.
- Research documents are not memory.
- GGUF model files are not committed and are not included in normal APKs.
- Debug exports may include private app state, but not model weights by default.
- Synapse Governance V3 is app-owned routing and artifacts, not a giant system prompt.
- All important mutations need receipts.
