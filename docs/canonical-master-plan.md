# Synapse AI Canonical Master Plan v4

Status: active product roadmap

Synapse AI is a native Android, offline-first local LLM app: stable chatbot UI,
embedded `llama.cpp` runtime, evidence-backed memory, indexed research library,
offline voice, sandbox workspace tools, and optional Synapse Governance V3 mode.
The first-screen experience should feel like ChatGPT/Gemini/Claude: separate
recent chats, visible message history, keyboard-safe composer, attachments,
speech input, per-message playback, and no visible prompt scaffolding.

## Implemented

- Android-native chat shell with recent chats.
- Recent-chat management: pin to top, rename, archive, and delete from the
  long-press chat list menu.
- Embedded ARM64 `llama.cpp` runtime with Termux server fallback.
- Named debug APK output at `app/build/outputs/apk/synapse/Synapse-AI.apk`.
- App-local Room/SQLite chat and evidence-backed memory.
- Storage-pressure guardrails for memory writes.
- Synapse Guild branding.
- Persona and Custom Instructions settings.
- Legacy `systemPrompt` migration into Custom Instructions defaults.
- Quiet top-bar runtime status.
- Keyboard-aware chat layout and long-output scroll detach behavior.
- Rotating thinking indicator with 300 fast typewriter-style loading phrases.
- Composer owns keyboard insets; send hides the keyboard and keeps chat history visible above it.
- Stale streaming-message cleanup on app start.
- Assistant output filtering for hidden reasoning, fake role labels, and prompt leakage.
- Per-message speaker playback with play, pause, and resume controls.
- Basic attachment composer with text extraction for text-like files and explicit
  image/file metadata placeholders. Image understanding is not claimed until a
  multimodal runtime is added.
- Initial app-private Library/Workspace foundation: Markdown artifact creation,
  Room-backed artifact catalog metadata, durable write receipts, safe path
  handling, and basic PDF export cache generation.
- One-shot Android speech input through the system speech recognizer.
- Full app-state debug ZIP export, excluding GGUF model files.
- Persisted generation timing traces for diagnosing slow or blank local model responses.

## Remaining Major Tracks

### Prompt And Persona Contract

Goal: keep Synapse steerable without exposing raw prompt machinery in normal UI.

- Keep Persona separate from Custom Instructions.
- Persona defines who Synapse is: role, tone, style, and default identity.
- Custom Instructions define standing user preferences: answer style,
  formatting, workflow preferences, and behavior rules.
- Compose prompts in layers: app-owned core contract, Persona, Custom
  Instructions, verified memory context, future library/governance context, then
  recent conversation.
- Keep raw advanced prompt override hidden under an Advanced section if it
  remains available.
- Never display `<think>`, fake `User:` / `Assistant:` turns, `Thinking
  Process`, `Final Decision`, `Self-Correction`, or other internal scaffolding.

### Offline Voice System

Goal: make Synapse usable by voice without depending on Termux or cloud APIs.

- Add selectable offline assistant voices.
- Add voice settings: voice, speed, pitch, test voice.
- Store voice models separately from the APK, like GGUF model imports.
- Add full Voice Mode as a separate surface from per-message speaker playback:
  listen locally, transcribe locally, send to local LLM, speak locally, loop until stopped.
- Full Voice Mode owns turn-taking, silence detection, TTS interruption, and
  cancellation state. It must not interfere with per-message speaker buttons.
- Keep v1 push-to-talk / explicit voice mode only. No always-listening wake word.
- Always expose stop/interrupt controls.

### Attachments And Multimodal Input

Goal: preserve user-supplied files/photos safely and use what the current local
model can actually understand.

- Keep composer attachments for files and photos.
- Copy imported attachment originals into app-private storage when they must
  survive beyond the sending Android content URI.
- Extract readable text from text, Markdown, JSON, and later PDF/DOCX sources.
- Route research-style documents into the library ingestion path when the user
  wants them saved as reusable knowledge.
- Save photo/image attachments as chat artifacts with metadata first.
- Add real image understanding only after a supported multimodal `llama.cpp`
  path exists, such as a compatible vision model plus `mmproj`/vision projector.
- Make text-only limitations visible in diagnostics and receipts, not hidden in
  fake model confidence.

### Research Library And RAG

Goal: make Synapse a local librarian plus analyst, not just a chatbot with memory.

- Use the app-private Library/Workspace foundation in
  [`library-workspace-architecture.md`](library-workspace-architecture.md) as
  the storage/catalog base.
- Store original imported documents in app-private storage.
- Extract text from text/Markdown first, then PDF/DOCX, then exact
  user-provided URLs.
- Chunk extracted text.
- Generate local embeddings with a small on-device embedding model.
- Save document metadata: title, source, hash, import date, tags, project, topic, status.
- Build catalog indexes before deep retrieval, following the `frontend-playground` pattern.
- Search catalog metadata first, then retrieve matching chunks.
- Add keyword fallback when embeddings are unavailable.
- Inject cited evidence packs into chat prompts.
- Show source citations and retrieval receipts.
- Build relationships later: similarity, references, topic clusters, stale
  markers, contradictions, and potential conflicts.
- Exact URL import means user-supplied URLs only. No autonomous free browsing in
  v1.

### Synapse Governance V3 Mode

Goal: let the user intentionally enter governed planning mode without turning the
whole app into a giant prompt or hidden runtime.

- Import V3 doctrine as a high-authority local library collection.
- Add a deliberate Synapse Mode toggle or planning entry point.
- Implement app-owned governance routing and state; the LLM assists but does not own state.
- Start with Governance Preflight, Planning, Research Capture, Subject Data layout,
  Control Sync, and Snapshots.
- Add Guild Orders and Quest drafting after the base planner is stable.
- Store governance outputs as explicit app-owned artifacts with templates,
  schemas, receipts, versioning, and export paths.
- Keep Officer Mode read-only/audit by default unless explicitly delegated.
- Do not add autonomous governance daemons, watchers, or outbound messages.

### Sandbox Workspace Tools

Goal: give Synapse useful local file output without unsafe phone filesystem access.

- Create an app-private Synapse Workspace.
- Allow creating folders, Markdown notes, summaries, research maps, exports, and code/text files.
- Add receipts for file writes.
- Block arbitrary phone filesystem edits.
- Block silent overwrite and deletion without explicit confirmation.

### Diagnostics And Phone Debuggability

Goal: make phone-only failures debuggable from Codex without needing live access
to the device.

- Keep `Export Debug ZIP` in Settings/Diagnostics.
- Include database, settings, chats, memories, receipts, prompt metadata,
  runtime state, model metadata, build/device info, UI/window metrics,
  readable database summaries, and generation timing traces.
- Include app-private attachment/library/workspace metadata and small diagnostic
  manifests.
- Exclude actual GGUF model weights by default.
- Warn that a full export contains private chats, memory, prompts, settings, and
  imported document metadata.

### Model And Runtime Hardening

Goal: make embedded local inference feel reliable on Samsung S25 Ultra.

- Add clearer embedded model health and load receipts.
- Add model metadata/hashing during import.
- Add performance presets for speed/battery/quality.
- Add first-run model downloader/importer with hash verification.
- Keep normal APKs free of GGUF model weights.
- Consider optional release packs with APK and model as separate assets.
- Add clear receipts for model import, hash verification, runtime start, runtime
  stop, and failed model loads.

### Product Flavors

Goal: share one solid core between Synapse AI and later characters/products.

- Keep Synapse AI as the first product.
- Add Pickle AI later as a separate product flavor.
- Share runtime, chat, memory, library, retrieval, workspace, attachments, and voice core.
- Override branding, persona, bundled docs, and default instructions per flavor.
- Pickle AI is a later brand/persona/docs split, not a forked engine.

## Build Order

1. Finish phone QA of the current chat/prompt/diagnostics stabilization slice.
2. Harden attachment persistence and debug export coverage.
3. Offline voice selection and local speaker voice import.
4. Full offline Voice Mode.
5. Library core schema and text/Markdown ingestion.
6. PDF/DOCX/exact-URL ingestion.
7. Retrieval and citation injection.
8. Research graph relationships.
9. Synapse Governance V3 planning mode.
10. Sandbox workspace tools.
11. Model downloader/importer hardening.
12. Pickle AI product flavor.

## Test Plan

- UI tests for keyboard visibility, composer behavior, auto-follow, scroll
  detach, and typing indicator behavior.
- Regression tests for stale `STREAMING` cleanup.
- Prompt tests for Persona plus Custom Instructions composition and legacy
  `systemPrompt` migration.
- Output tests for no hidden reasoning, fake role labels, or diagnostic leakage.
- Top-bar tests for hiding healthy runtime status and showing actionable issues.
- Diagnostics ZIP tests for app-state inclusion and GGUF exclusion.
- Attachment tests for text extraction, URI failure handling, and future
  app-private original preservation.
- Voice tests for per-message play/pause/resume state and future Voice Mode
  interruption.
- Manual S25 Ultra QA for long streaming answers, old chats, keyboard typing,
  debug export, model import, and voice playback.

## Non-Negotiable Boundaries

- No autonomous outbound messaging in v1.
- Memory, research, workspace files, and governance artifacts are separate concerns.
- Research documents are not memory.
- GGUF model files are not committed and are not included in normal APKs.
- Debug exports may include private app state, but not model weights by default.
- Synapse Governance V3 is app-owned routing and artifacts, not a giant system prompt.
- Exact URL import is user-directed. No free autonomous web browsing in v1.
- Visible chat must never show internal prompts, hidden reasoning, or diagnostic scaffolding.
- Samsung S25 Ultra is the primary target device for manual QA.
- All important mutations need receipts.
