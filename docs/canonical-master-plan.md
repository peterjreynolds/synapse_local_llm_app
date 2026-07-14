# Synapse Chat Canonical Master Plan v5

Status: active product roadmap

Synapse Chat is a native Android, offline-first room chat app with a phone-local
AI member, embedded `llama.cpp` runtime, evidence-backed memory, indexed
research library, offline voice, and sandbox workspace tools. The first-screen
experience should feel like a polished modern messenger: separate rooms,
visible attributed message history, keyboard-safe composer, attachments, speech
input, per-message playback, and no visible prompt scaffolding.

The app is standalone. Its runtime, Room database, memory, SMS automation, and
UI are app-owned and do not depend on OpenClaw, Wingman, a historical Synapse
governance runtime, or an external sidecar.

## Implemented

- Android-native Synapse Chat shell with AI chat, direct, and group rooms.
- Durable participant profiles, room memberships, and explicit message authors.
- Room management: create, select, pin, rename, archive, delete, inspect
  members, add placeholder humans, and add/remove Synapse where room policy
  allows.
- Room-aware AI routing: automatic in AI chats; mention-only or explicitly
  automatic in direct/group rooms; never in human-only rooms.
- Attributed multi-participant message bubbles and `@Synapse` composer support.
- Embedded ARM64 `llama.cpp` runtime with Termux server fallback.
- Named APK output at `app/build/outputs/apk/synapse/Synapse-AI.apk`.
- Rolling GitHub APK delivery through the `synapse-ai` release and `apk-latest`
  branch, plus in-app update checks that download the newest `Synapse-AI.apk`
  and hand it to Android's installer.
- Rolling APK updates preserve the early side-loaded Android package id
  `app.synapse.localllm.debug` until a deliberate app-data migration path exists.
  Future APK releases must keep package id, signing key, and monotonic
  `versionCode` stable or Android will reject in-place updates.
- App-local Room/SQLite chat and evidence-backed memory.
- Memory V8 generalized governed-claim foundation: structured memory kinds,
  scopes, domains, subjects, predicates, values, source quotes, write intents,
  sensitivity, claim keys, supersession, tombstone/delete commands, conservative
  implicit candidate proposal, strict JSON proposer validation boundary,
  review-needed states, intent-based retrieval routing, scored retrieval
  receipts, and visible memory metadata in diagnostics/review surfaces.
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
- Hands-free Voice Mode foundation using explicit Android speech recognition
  turns and TextToSpeech playback.
- Basic attachment composer with text extraction for text-like files and explicit
  image/file metadata placeholders. Image understanding is not claimed until a
  multimodal runtime is added.
- Initial app-private Library/Workspace foundation: Markdown artifact creation,
  Room-backed artifact catalog metadata, durable write receipts, safe path
  handling, and basic PDF export cache generation.
- One-shot Android speech input through the system speech recognizer.
- Voice Mode state-machine foundation; see
  [`voice-mode-architecture.md`](voice-mode-architecture.md).
- Redacted metadata-only debug ZIP export, excluding raw app state, content,
  private paths, credentials, tokens, and GGUF model files.
- Persisted generation timing traces for diagnosing slow or blank local model responses.
- Explicit SMS auto-reply toggle that receives inbound SMS, submits it through
  the local LLM turn coordinator, and queues the finalized assistant reply back
  to the originating sender with durable receipts and user-controlled SMS
  reply instructions.
- Additive Room database v8→v9 migration that preserves existing room/message
  IDs and all attachment, generation-trace, memory, pin/archive/title, and SMS
  relationships while backfilling participants, memberships, and authorship.
- Provider-neutral local sync metadata fields exist as contracts only; no fake
  remote synchronization or cloud-provider behavior is claimed.

## Synapse Chat Phase 1 — Complete

Phase 1 converted the installed Synapse app into Synapse Chat without creating a
second APK or resetting app-private state.

### Local room truth

- Existing `chat_threads.id` values remain the durable room IDs.
- Rooms have explicit `AI_CHAT`, `DIRECT`, or `GROUP` kind.
- Stable participants have `HUMAN`, `LOCAL_AI`, `REMOTE_AI`, or `SYSTEM` kind,
  display metadata, and provider-neutral sync metadata.
- Membership rows own room role, posting permission, join/leave timestamps, and
  AI response policy.
- `chat_message_authors` is a one-to-one authorship ledger for every message.
  `ConversationRole` remains compatibility/rendering metadata.
- Human-message persistence and AI-response creation are separate operations.
  A routed no-response turn persists only the human message.

### Upgrade behavior

- Room v9 is additive: the migration does not drop or rebuild the existing
  `chat_threads` or `chat_messages` parent tables.
- Existing rooms become AI chats with the local-human owner and Synapse local-AI
  memberships.
- Existing user, assistant, and system roles backfill to deterministic built-in
  participant IDs. Unexpected legacy roles fail migration closed.
- Existing SMS senders receive stable local human profiles and room
  memberships while sender/thread/receipt correlations remain intact.
- Migration regression coverage snapshots all representative v8 values before
  and after migration, verifies one author per message, and runs
  `foreign_key_check`.

### Routing and diagnostics

- `RoomAiResponseRoutingPolicy` returns an explicit typed decision: AI-chat
  automatic, room automatic, Synapse mentioned, Synapse absent, or mention
  required.
- Generation rows and generation diagnostics start only after routing approves
  an AI response. Human-only turns do not manufacture empty streaming rows.
- Existing generation timing traces, stale-stream cleanup, and SMS receipts
  remain in place. The debug ZIP reports only bounded table counts and excludes
  raw Room/DataStore state and content-bearing rows.

### Identity and delivery

- Visible product copy is Synapse Chat.
- Android namespace and rolling debug application ID remain
  `app.synapse.localllm` and `app.synapse.localllm.debug` respectively.
- Signing lineage, `synapse-ai` release tag, `apk-latest` branch, and
  `Synapse-AI.apk` asset contract are unchanged.

See [`synapse-chat-room-architecture.md`](synapse-chat-room-architecture.md) for
the durable model and boundary details.

## Synapse Chat Phase 2 — Entry Blockers

These are the only blockers carried from Phase 1 into Synapse Chat Phase 2:

1. Authentication that establishes real remote human identity without changing
   the local participant model or Android package identity.
2. A provider-neutral remote sync contract covering IDs, revisions, ordering,
   conflicts, retries, and durable sync receipts before any provider adapter is
   selected.
3. A push-notification provider adapter behind an app-owned notification
   boundary, added only after authentication and sync semantics are defined.

No cloud-provider SDK, backend secret, or fake synchronization belongs in the
domain model before those contracts exist.

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
- Current first pass uses Android `RecognizerIntent` and `TextToSpeech`; fully
  offline STT and selectable local voices remain future work.
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

### Memory V8 Expansion

Goal: make memory behave like a governed local assistant memory system, not a
bag of regex hits.

- Keep conversation traces as evidence, not prompt stuffing.
- Keep durable saved memories separate from research/library documents.
- Maintain structured memory metadata: kind, status, claim key, scope, domain,
  subject, predicate, value, source quote, write intent, durability score, future
  usefulness score, sensitivity, keywords, confidence, evidence, and receipts.
- Expand memory kinds across identity, preferences, projects, appointments,
  relationships, commitments, procedures, instructions, corrections, summaries,
  gist, trace, and archive.
- Use deterministic extraction for explicit user commands and high-confidence
  patterns.
- Use conservative implicit candidate proposal after the user turn for narrow,
  source-backed project/routine continuity without blocking first-token latency.
- Add local-model candidate extraction later behind an experimental setting, but
  require strict JSON validation, exact source quotes, evidence checks, conflict
  checks, and admission-gate approval.
- Keep deterministic chat-driven forget/update commands that tombstone or
  supersede durable memories instead of writing correction text as a new fact.
- Add a stricter conflict review UI for ambiguous same-key facts that should not
  auto-supersede.
- Expand the current Memory panel beyond Active/Review/Inactive/All into a full
  approve/reject/edit review workflow for quarantined and conflicted claims.
- Add rolling daily, chat, and project summaries so Synapse can answer what was
  discussed yesterday or where a project was left.
- Add project memory spaces similar to Claude project memory.
- Add memory citations back to source chats when previous conversations are
  referenced.
- Never let the assistant claim a memory was saved unless a durable write
  receipt exists.

### Synapse Governance V3 Mode

Goal: let the user intentionally enter governed planning mode without turning the
whole app into a giant prompt or hidden runtime.

This is an independent future app-owned artifact mode, not a Synapse Chat Phase
2 dependency and not activation of a historical governance runtime.

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
- Expand the first-run model downloader/importer beyond the built-in Qwen GGUF
  catalog entry to a remote HTTPS catalog.
- Harden in-app APK updates with optional remote update metadata, richer
  installer failure guidance, and background-friendly update downloads if
  Android permits that without weakening install approval.
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

Synapse Chat Phase 2 begins only after the three entry blockers above are
designed in order: authentication, provider-neutral sync contract, then push
provider adapter. The other product tracks in this plan remain independent work
and are not hidden prerequisites for remote chat.

## Test Plan

- UI tests for keyboard visibility, composer behavior, auto-follow, scroll
  detach, and typing indicator behavior.
- Room tests for creation rules, member add/remove/reactivation, attributed
  messages, and separate human-message/AI-response persistence.
- AI routing tests for AI-chat automatic response, room automatic response,
  case-insensitive `@Synapse`, mention-required, and human-only rooms.
- Room v8→v9 migration tests for exact legacy row preservation, deterministic
  participant/membership/authorship backfill, SMS correlations, and foreign-key
  integrity.
- Regression tests for stale `STREAMING` cleanup.
- Prompt tests for Persona plus Custom Instructions composition and legacy
  `systemPrompt` migration.
- Output tests for no hidden reasoning, fake role labels, or diagnostic leakage.
- Top-bar tests for hiding healthy runtime status and showing actionable issues.
- Diagnostics ZIP tests for app-state inclusion and GGUF exclusion.
- Memory tests for structured metadata, explicit remember commands, identity,
  project, appointment, preference, retrieval intent routing, storage-paused
  writes, and no fake saved-memory claim without receipts.
- Attachment tests for text extraction, URI failure handling, and future
  app-private original preservation.
- Voice tests for per-message play/pause/resume state, Voice Mode state
  transitions, and future Voice Mode interruption.
- Manual S25 Ultra QA for long streaming answers, old chats, keyboard typing,
  debug export, model import, and voice playback.

## Non-Negotiable Boundaries

- Autonomous outbound SMS exists only behind the explicit SMS auto-reply toggle
  and Android SMS permissions.
- Synapse Chat remains a standalone Android app with app-local Room truth. It
  does not require a historical Synapse governance runtime or sidecar.
- Package ID, signing lineage, release tag, update asset, and monotonic
  `versionCode` remain update-compatibility boundaries.
- Remote authentication, sync, and push must enter through provider-neutral
  contracts; provider SDK types do not belong in room/member/message domain
  records.
- Memory, research, workspace files, and governance artifacts are separate concerns.
- Research documents are not memory.
- GGUF model files are not committed and are not included in normal APKs.
- Debug exports may include private app state, but not model weights by default.
- Synapse Governance V3 is app-owned routing and artifacts, not a giant system prompt.
- Exact URL import is user-directed. No free autonomous web browsing in v1.
- Visible chat must never show internal prompts, hidden reasoning, or diagnostic scaffolding.
- Samsung S25 Ultra is the primary target device for manual QA.
- All important mutations need receipts.
