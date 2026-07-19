# Cinder Conversation Integration

Status: Firebase and Android source implementation complete; live deployment and
OpenClaw worker round trip not yet proven

Cinder is Peter's existing OpenClaw agent. OpenClaw owns Cinder's identity,
sessions, transcript continuity, workspace, tools, memory/Wingman context, and
response generation. Synapse owns the authenticated Firebase transport, durable
jobs and leases, Android presentation, and cached synchronization. This is not a
generic hosted-model provider and it does not route through phone-local Synapse
inference.

## Product Identity And Behavior

- Assistant ID: `cinder`.
- Dedicated conversation room ID: `assistant_cinder`.
- Human-room participant ID: `participant-cinder-remote-ai`.
- Trusted attribution: `REMOTE_AI`, `REMOTE_HOSTED`, and `OPENCLAW_CINDER`.
- Human-room response policy: `MENTION_ONLY` with an explicit, normalized
  `@Cinder` mention.
- The dedicated conversation accepts text only in this slice. Attachments,
  voice notes, and replies fail explicitly and are never silently discarded.
- Direct Cinder sends reach Firebase before any local message is created. A
  rejected or unavailable submission preserves the draft and creates no
  optimistic ghost or outbox row.
- Cinder replies use the existing remote thread and account-scoped Room cache.
  Human-room replies use the existing room message, latest-message,
  synchronization, and metadata-only notification paths.
- Removing Cinder from a human room prevents future claims and completions but
  preserves existing message history.

## Authenticated Android Callables

Every callable derives the account UID from Firebase Authentication and applies
the existing active, allowed, and forced-password-change account boundary.
Android never receives the worker credential.

### `getCinderAvailability`

Request: `{}`.

Returns protocol version 1, the server check time, the worker heartbeat expiry,
and an `available` boolean. A claim poll refreshes the worker heartbeat for two
minutes. Missing, expired, or malformed status is unavailable.

### `submitCinderMessage`

Request fields:

| Field | Contract |
| --- | --- |
| `assistantId` | Exactly `cinder`. |
| `roomId` | Exactly `assistant_cinder`. |
| `messageId` | Valid opaque Synapse message ID. |
| `idempotencyKey` | Valid opaque idempotency key; Android uses `messageId`. |
| `body` | Normalized UTF-8 text, 1-4,000 characters. |
| `clientCreatedAtMillis` | Metadata only; never ordering authority. |
| `attachmentIds` | Empty array. |
| `replyToMessageId` | `null`. |

The transaction persists the human turn and pending response job together. A
same-content replay returns `ALREADY_ACCEPTED`; reuse with different content
fails deterministically. Acceptance returns the message ID, room ID, revision,
and server-owned conversation sequence.

### `syncCinderMessages`

Request: `afterSequence` (zero or a previous cursor) and `limit` (1-100).
Returns an ordered page, `nextSequence`, and `hasMore`. Both human turns and
Cinder replies are persisted before appearing in this feed. Android validates
the exact assistant/provider/provenance identity and caches the messages in the
existing remote message table.

### `getCinderParticipant` and `setCinderParticipant`

Both accept a human direct or group `roomId`. An active direct-room member may
change participation. A group requires owner or administrator role.
`setCinderParticipant` also accepts `active`, updates the canonical participant
document and `aiParticipantIds`, and writes a bounded audit event.

## OpenClaw Worker Contract

The companion worker uses four HTTPS Functions endpoints:

- `claimCinderResponse`
- `completeCinderResponseJob`
- `failCinderResponseJob`
- `skipCinderResponseJob`

Requests must be `POST` with `Authorization: Bearer <CINDER_WORKER_TOKEN>`. The
secret is a Functions secret, is compared through a constant-time digest, and
must never be committed, logged, stored in Firestore, or returned to Android.
Successful responses use:

```json
{
  "protocolVersion": 1,
  "result": {}
}
```

Claim accepts `workerId`. A claim contains the job and lease IDs, one-time lease
token, lease expiry, account UID, room ID and kind, stable idempotency key,
participant/mention state, source author and body, server sequence/revision, and
up to 12 normalized context messages. The worker must dispatch that context
through the normal OpenClaw Cinder channel/runtime, not a new assistant stack.

Complete, fail, and skip require `jobId`, `leaseId`, `leaseToken`, and
`workerId`. Complete also requires the final visible body. Fail requires a
bounded failure code and retry policy. Skip requires a bounded reason. Leases
last two minutes, attempts are capped at three, expired leases are reclaimable,
and terminal outcomes replace the live job with an auditable receipt. Replaying
the same terminal command is idempotent; conflicting response content fails.

For human rooms, the message-created trigger queues only an active human
sender's message with the exact active Cinder participant metadata and an
explicit `@Cinder` mention. Claim and completion both re-check the source and
participant state. Completion creates one deterministic remote-AI room message;
removal or unavailable source state records a skip instead.

## Persistence, Rules, And Retention

- `cinderConversations/{accountUid}` and its `messages` subcollection own the
  dedicated durable conversation and server sequence.
- `cinderResponseJobs/{jobId}` owns pending/claimed work and lease state.
- `cinderResponseAudits/{jobId}` owns terminal completion/failure/skip receipts.
- `cinderWorkerStatus/current` owns the short-lived availability heartbeat.
- `cinderAuditEvents/{eventId}` records participant changes.
- Firestore clients cannot read or write any Cinder collection. Account access
  goes only through the authenticated, rate-limited callables; worker state,
  jobs, and audits remain server-only.
- Terminal response and participant audit records follow the operational
  90-day retention policy. Pending work is not deleted by generic retention.
- Android Room schema 17 adds nullable `serverSequence` plus an account/room
  index. Existing cached rows remain intact and dedicated Cinder messages order
  by server sequence.

## Exact Live Activation Steps

Source implementation does not prove a live backend. An end-to-end round trip
still requires all of the following:

1. Create a strong Firebase Functions secret named `CINDER_WORKER_TOKEN`; do
   not place its value in git, Android resources, logs, or Firestore.
2. Deploy the changed Firestore rules and indexes, then deploy the Cinder user
   callables, queue trigger, notification trigger, worker endpoints, and
   operational cleanup Function to the intended Firebase project.
3. Configure the companion OpenClaw outbound Synapse worker with the four
   deployed HTTPS endpoint URLs, the same secret, a stable `workerId`, and
   protocol version 1. Keep the worker in the OpenClaw-owned repository/lane.
4. Start the worker and verify repeated claim polls refresh
   `cinderWorkerStatus/current` without exposing the secret.
5. Sign in with an active allowed Synapse account, verify availability changes
   to available, send one dedicated Cinder text, and prove the persisted human
   turn, claimed job, OpenClaw response, terminal audit, synchronized reply, and
   metadata-only notification.
6. Add Cinder to an authorized test direct/group room, prove an ordinary message
   queues nothing, prove `@Cinder` produces one response, remove Cinder, and
   prove later mentions queue nothing while prior history remains.
7. Record the live Firebase and physical-device receipts in the release ledger
   before making any release-readiness claim.

No deployment, secret mutation, OpenClaw runtime change, release dispatch, or
APK publication is performed by the source implementation milestone.
