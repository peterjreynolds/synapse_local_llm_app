# Memory Architecture

Synapse uses one app-local memory truth: Room entities in `synapse.db`.

The memory pipeline is receipt-backed and app-owned:

1. Persist chat messages.
2. Append a user trace event.
3. Interpret explicit memory commands.
4. Extract memory candidates from explicit user text when the command is not a
   delete/forget command.
5. Check storage health.
6. Admit or reject each candidate.
7. Persist a memory write receipt for every outcome.
8. Retrieve active prompt-visible memories for future prompts.

The local model can be added later as a structured candidate proposer, but it
must not directly write memory. A model suggestion must still pass schema
validation, evidence checks, storage checks, and the admission gate before
Synapse is allowed to say it saved anything.

## Memory V7 Governed Claims

Synapse now stores memory versions with structured metadata:

- `kind`: identity, preference, project, appointment, relationship, commitment,
  procedure, instruction, correction, summary, gist, archive, or trace.
- `scope`: global, project, or thread.
- `subject`: the user, a project, a relationship, or another explicit subject.
- `keywords`: small retrieval hints derived from the source text.
- `claimKey`: stable key for single-value facts such as `user.full_name` or
  `user.favorite.food`.
- evidence: trace events linked through `memory_supports`.
- receipts: every write outcome is persisted in `memory_write_receipts`.

Claim keys let Synapse update one durable fact instead of stacking conflicting
duplicates. If the user first saves one full name and later corrects it, the old
active memory is marked `SUPERSEDED`; it remains reviewable but is no longer
eligible for prompt injection. Re-saving the exact same claim refreshes the
existing active memory and records a `MEMORY_UPDATED` receipt.

Memory statuses are:

- `ACTIVE`
- `ARCHIVED`
- `SUPERSEDED`
- `CONFLICTED`
- `QUARANTINED`
- `TOMBSTONED`

Write receipts can now distinguish trace-only events, durable writes, updates,
supersessions, tombstones, rejected candidates, quarantine/confirmation outcomes
reserved for future review flows, and storage-paused writes. Synapse is only
allowed to claim a save/delete happened after the repository writes a durable
receipt.

The deterministic extractor is broader than the first narrow preference-only
pass. It recognizes:

- explicit `remember`, `save`, and `don't forget` commands
- identity facts such as full name, nickname, role, location, or contact fields
- preferences and favorites
- project facts and project-scoped rules
- appointments, meetings, calls, deadlines, and due dates
- relationships
- commitments and always/never procedures
- generic explicit memory requests when no narrower classifier matches

Forget/delete commands do not create new memories. `PatternMemoryCommandInterpreter`
routes them to `tombstoneMemoriesMatching`, which marks matching active memories
as `TOMBSTONED` and records `MEMORY_TOMBSTONED` receipts. Save-like wording such
as `don't forget` still flows through extraction as an explicit save.

## Retrieval Router

Retrieval is no longer only lexical overlap. Before each generation, Synapse
classifies the user query into memory intents:

- identity recall
- preference recall
- project recall
- appointment/deadline recall
- instruction/workflow recall
- general saved-memory review

The router combines lexical overlap, memory kind, memory scope, subject,
claim-key overlap, confidence, and keywords. Retrieval receipts store the
classified intent, selected memory IDs, reason codes, rank scores, and injected
prompt block. Prompt context remains small and verified:

```text
- [project / project / Walby] All new proposals for Project Walby should be reviewed by Roberto Moreno.
```

If verified memory does not contain the fact needed for a personal, project,
appointment, or preference question, the prompt contract tells Synapse to say it
does not know instead of guessing.

## Memory Review Screen

The Memory panel is a review surface, not a prompt dump. It can:

- list active memories by default
- switch between Active, Inactive, and All filters
- search the current review set
- show kind, status, scope, subject, claim key, confidence, source evidence
  count, and retrieval rank where available
- tombstone active memories through the same repository receipt path

Inactive memories include superseded, conflicted, quarantined, archived, and
tombstoned claims. They remain available for diagnostics and review but are not
retrieved into chat prompts.

## What This Is Not Yet

This is not a full Claude/ChatGPT-class memory system yet. Remaining work:

- LLM-assisted candidate extraction with strict JSON schema validation.
- Explicit conflict review UI for same-key facts that should not auto-supersede.
- Rolling daily, chat, and project summaries.
- Project memory screens and project-scoped review controls.
- Citations from memory answers back to original chat turns.

## Storage Guardrail

`StorageHealthGovernor` checks free device storage, database size, and attachment
cache size. If free storage drops below the configured floor, memory writes pause
and chat continues without durable memory mutation.

Default thresholds:

- pause memory writes below 2 GB free device storage
- warn above 512 MB memory database size
- warn above 1 GB attachment cache size

## No Split Brain

V1 does not implement proactive outbound messages. The only assistant messages
are created in response to a user-submitted turn.

Any future proactive feature must use a single ledger:

```text
OutreachDecision -> OutboxItem -> DeliveryReceipt
```

No scheduler, model output, memory lane, or research lane may directly send user-visible messages.
