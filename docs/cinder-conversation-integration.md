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

1. Freeze a clean candidate SHA and retain it in every deployment and test
   receipt. Create the Functions secret interactively; never pass or print the
   value in a shell argument:

   ```bash
   PROJECT_ID="synapse-chat-pjr-2026"
   REGION="northamerica-northeast1"
   CANDIDATE_SHA="$(git rev-parse HEAD)"
   test -z "$(git status --short)"
   npm --prefix firebase exec -- firebase functions:secrets:set \
     CINDER_WORKER_TOKEN \
     --project "$PROJECT_ID"
   ```

   Provision the same 32-512 character visible-ASCII value to the OpenClaw
   worker through its secret provider. Do not use `functions:secrets:access`
   as part of activation or evidence collection.

2. Deploy only the required Firestore policy and indexes, without `--force`:

   ```bash
   npm --prefix firebase exec -- firebase deploy \
     --project "$PROJECT_ID" \
     --only "firestore:rules,firestore:indexes"
   ```

   Wait until both `cinderResponseJobs` composite indexes are ready before
   starting the worker.

3. Deploy the exact Functions surface that owns the Cinder transport and its
   existing notification, participant-preservation, and retention dependencies:

   ```bash
   npm --prefix firebase exec -- firebase deploy \
     --project "$PROJECT_ID" \
     --only "functions:synapse-chat:getCinderAvailability,functions:synapse-chat:submitCinderMessage,functions:synapse-chat:syncCinderMessages,functions:synapse-chat:getCinderParticipant,functions:synapse-chat:setCinderParticipant,functions:synapse-chat:queueCinderHumanRoomResponse,functions:synapse-chat:claimCinderResponse,functions:synapse-chat:completeCinderResponseJob,functions:synapse-chat:failCinderResponseJob,functions:synapse-chat:skipCinderResponseJob,functions:synapse-chat:notifyRemoteMessage,functions:synapse-chat:notifyCinderMessage,functions:synapse-chat:updateRoomAiConfiguration,functions:synapse-chat:cleanupExpiredOperationalData"
   ```

4. Discover the four worker URLs from deployed metadata instead of constructing
   hostnames. The result must contain exactly four `ACTIVE` rows in
   `northamerica-northeast1`, each with a non-empty HTTPS URI:

   ```bash
   npm --prefix firebase exec -- firebase functions:list \
     --project "$PROJECT_ID" \
     --json |
     jq -r '.result[] |
       select(.id | test("^(claimCinderResponse|completeCinderResponseJob|failCinderResponseJob|skipCinderResponseJob)$")) |
       [.id, .region, .state, .uri] | @tsv'
   ```

   Configure those discovered URIs as OpenClaw's claim, complete, fail, and skip
   URLs. Enable both the `synapse-chat` plugin entry and channel, use one stable
   valid `workerId`, and resolve the same secret through the OpenClaw secret
   provider. Keep this configuration and runtime in the OpenClaw-owned lane.

5. Start the normal Cinder/OpenClaw runtime. Its claim poll is the heartbeat;
   there is no separate heartbeat endpoint. Confirm the channel is running with
   no current error, then use an authenticated active Synapse account to obtain
   two `getCinderAvailability` receipts after separate claim polls. Both must be
   protocol version 1 and available, and the second `availableUntilMillis` must
   advance. A channel-status command alone does not prove Firebase heartbeat.

6. For the dedicated `assistant_cinder` round trip, background the receiving
   app, send a unique harmless text, and prove exactly one accepted human turn,
   one claimed `ASSISTANT` job, one normal OpenClaw Cinder dispatch, one terminal
   `COMPLETE` audit, one synchronized trusted Cinder reply, and one private
   metadata-only notification that opens `assistant_cinder`. Reopen and relaunch
   the app to prove cache and synchronization stability. Store only redacted
   identifiers, digests, counts, and timestamps in the receipt.

7. In an authorized direct or group test room, add Cinder and send a unique
   ordinary message without `@Cinder`; prove no Cinder job, audit, or reply is
   created. Send one unique normalized `@Cinder` message and prove exactly one
   claim, terminal audit, trusted remote-AI reply, synchronization event, and
   metadata-only notification.

8. Remove Cinder and prove the participant becomes inactive while prior history
   remains. A later unique `@Cinder` message must create no job or reply. Also
   exercise the removal race by removing Cinder after a claim but before
   completion; completion must return `SKIPPED` with `PARTICIPANT_REMOVED` and
   persist no room reply.

9. Execute the release ledger's complete physical-device checklist on both the
   Galaxy S9/API 29 class and a physical API 33+ device. In addition to the
   existing install, session, messaging, attachment, updater, notification, and
   App Check evidence, record Cinder offline draft preservation, advancing
   heartbeat availability, dedicated and mention-only round trips, removal,
   background notification routing, and persistence after relaunch. Do not make
   a release-readiness claim until every required gate passes for the same
   candidate SHA.

No deployment, secret mutation, OpenClaw runtime change, release dispatch, or
APK publication is performed by the source implementation milestone.
