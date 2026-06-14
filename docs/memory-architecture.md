# Memory Architecture

Synapse uses one app-local memory truth: Room entities in `synapse.db`.

The memory pipeline is intentionally narrow:

1. Persist chat messages.
2. Append a user trace event.
3. Extract deterministic memory candidates from explicit user text.
4. Check storage health.
5. Admit or reject each candidate.
6. Persist a memory write receipt for every outcome.
7. Retrieve active prompt-visible memories for the next prompt.

The local model can be added later as a candidate proposer, but it must not directly write memory.

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
