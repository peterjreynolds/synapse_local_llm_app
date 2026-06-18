# Memory Architecture

Synapse uses one app-local memory truth: Room entities in `synapse.db`.

The memory pipeline is receipt-backed and app-owned:

1. Persist chat messages.
2. Append a user trace event.
3. Extract memory candidates from explicit user text.
4. Check storage health.
5. Admit or reject each candidate.
6. Persist a memory write receipt for every outcome.
7. Retrieve active prompt-visible memories for future prompts.

The local model can be added later as a structured candidate proposer, but it
must not directly write memory. A model suggestion must still pass schema
validation, evidence checks, storage checks, and the admission gate before
Synapse is allowed to say it saved anything.

## Memory V6 Foundation

Synapse now stores memory versions with structured metadata:

- `kind`: identity, preference, project, appointment, relationship, commitment,
  procedure, instruction, correction, summary, gist, archive, or trace.
- `scope`: global, project, or thread.
- `subject`: the user, a project, a relationship, or another explicit subject.
- `keywords`: small retrieval hints derived from the source text.
- evidence: trace events linked through `memory_supports`.
- receipts: every write outcome is persisted in `memory_write_receipts`.

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

Forget/delete commands do not create new memories. They are currently prevented
from becoming accidental durable facts; full chat-driven deletion is a future
command path layered on top of the existing tombstone repository mutation.

## Retrieval Router

Retrieval is no longer only lexical overlap. Before each generation, Synapse
classifies the user query into memory intents:

- identity recall
- preference recall
- project recall
- appointment/deadline recall
- instruction/workflow recall
- general saved-memory review

The router combines lexical overlap, memory kind, memory scope, subject, and
keywords. Prompt context remains small and verified:

```text
- [project / project / Walby] All new proposals for Project Walby should be reviewed by Roberto Moreno.
```

If verified memory does not contain the fact needed for a personal, project,
appointment, or preference question, the prompt contract tells Synapse to say it
does not know instead of guessing.

## What This Is Not Yet

This is not a full Claude/ChatGPT-class memory system yet. Remaining work:

- LLM-assisted candidate extraction with strict JSON schema validation.
- Conflict detection and superseding older claims.
- Chat-driven forget/update commands that tombstone or revise matching memories.
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
