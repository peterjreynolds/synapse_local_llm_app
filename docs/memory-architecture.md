# Memory Architecture

Synapse uses one app-local memory truth: Room entities in `synapse.db`.

The memory pipeline is receipt-backed and app-owned:

1. Persist chat messages.
2. Append a user trace event.
3. Interpret explicit memory commands.
4. Extract deterministic memory candidates from explicit user text when the
   command is not a delete/forget command.
5. Propose conservative implicit candidates when no explicit deterministic
   candidate exists.
6. Normalize every candidate into the same governed claim format.
7. Check storage health.
8. Admit, quarantine, require confirmation, trace, or reject each candidate.
9. Persist a memory write receipt for every outcome.
10. Retrieve active prompt-visible memories for future prompts.

The local model can be added later as a structured candidate proposer, but it
must not directly write memory. A model suggestion must still pass schema
validation, evidence checks, storage checks, and the admission gate before
Synapse is allowed to say it saved anything.

## Memory V8 Generalized Governed Claims

Synapse now stores memory versions with structured metadata:

- `kind`: identity, preference, project, appointment, relationship, commitment,
  procedure, instruction, correction, summary, gist, archive, or trace.
- `scope`: global, project, or thread.
- `domain`: identity, preference, relationship, project, task, appointment,
  routine, instruction, correction, summary, alias, constraint, workspace, gist,
  trace, or archive.
- `subject`: the user, a project, a relationship, or another explicit subject.
- `predicate`: the normalized relationship being claimed, such as `full_name`,
  `favorite`, `priority`, `deadline`, or `default`.
- `value`: the normalized claim value when a single value can be isolated.
- `sourceQuote`: the user-text span that supports the claim.
- `writeIntent`: explicit save, explicit correction, implicit candidate,
  summary, or imported.
- `durabilityScore` and `futureUsefulnessScore`: conservative admission inputs.
- `sensitivity`: low, medium, or high.
- `keywords`: small retrieval hints derived from the source text.
- `claimKey`: stable key for single-value facts using the generalized shape
  `<scope>.<domain>.<subject>.<predicate>`, such as
  `user.identity.self.full_name`, `user.preference.food.favorite`, and
  `project.project.stuart.priority`.
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

The deterministic extractor remains the first authority. It recognizes:

- explicit `remember`, `save`, and `don't forget` commands
- identity facts such as full name, nickname, role, location, or contact fields
- preferences and favorites
- project facts and project-scoped rules
- appointments, meetings, calls, deadlines, and due dates
- relationships
- commitments and always/never procedures
- generic explicit memory requests when no narrower classifier matches

Explicit deterministic candidates are normalized into the same V8 claim shape as
implicit candidates before admission. Sensitive facts such as addresses, phone
numbers, emails, credentials, health details, and financial details are not
silently activated; they require review/confirmation.

## Implicit Candidate Proposal

Synapse now has the first conservative implicit proposer. It is rule-based, not
a local-model call, and only runs after a user turn when deterministic extraction
found no explicit candidate. This avoids doubling first-token latency while
starting the broader ChatGPT/Claude/Gemini-style memory path.

Current implicit coverage is intentionally narrow:

- project continuity statements such as
  `For Stuart, diarization is the main priority`
- simple user routine statements such as `I usually ...`

Vague chat such as `that was sick` stays trace-only. Assistant-origin text is
never extracted as memory.

Implicit candidates pass through `MemoryImplicitScorer`:

- score `>= 0.85`: active durable memory when low-sensitivity and source-backed
- score `0.55..0.84`: stored as `QUARANTINED` for review
- score `< 0.55`: `TRACE_ONLY`

Implicit same-key contradictions do not overwrite active memories. They are
stored as `CONFLICTED` review-needed records, leaving the prior active claim
prompt-visible until the user resolves it.

## Local-Model Proposer Boundary

`JsonMemoryCandidateParser` defines the future local-model proposer boundary.
The model may propose strict JSON only. The parser rejects:

- malformed JSON
- unknown fields
- unsupported enum values
- missing `source_quote`
- quotes that are not exact spans from the user turn
- assistant-origin text

Parsed proposals are still untrusted. They must pass normalization, storage
health, admission scoring, conflict policy, and repository receipts before
Synapse may claim anything was saved.

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
- switch between Active, Review Needed, Inactive, and All filters
- search the current review set by text, kind, status, scope, domain, subject,
  predicate, value, source quote, write intent, sensitivity, claim key, or keywords
- show kind, status, scope, domain, subject, predicate, claim key, sensitivity,
  confidence, source quote, source evidence count, and retrieval rank where available
- tombstone active memories through the same repository receipt path

Review Needed memories include conflicted and quarantined claims. Inactive
memories include superseded, archived, and tombstoned claims. They remain
available for diagnostics and review but are not retrieved into chat prompts.

## What This Is Not Yet

This is not a full Claude/ChatGPT-class memory system yet. Remaining work:

- Explicit conflict review UI for same-key facts that should not auto-supersede.
- Rolling daily, chat, and project summaries.
- Project memory screens and project-scoped review controls.
- Citations from memory answers back to original chat turns.
- Local-model implicit proposal behind an experimental setting after phone QA
  proves latency and JSON quality are acceptable.

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
