# Synapse Chat Room Architecture

Status: Phase 1 implemented local vertical slice

Synapse Chat is one standalone Android application. Phase 1 extends the
original phone-local AI chat into app-local rooms with durable participants,
memberships, and attributed messages. It keeps the existing local inference
runtime, memory ledger, updater, Android package/signing lineage, and installed
Room data.

This architecture does not depend on OpenClaw, Wingman, a historical Synapse
governance runtime, or an external sidecar. “Synapse” is the product name in
this repository.

## Ownership Boundaries

- `domain/chat/ChatContracts.kt` owns provider-neutral room, participant,
  membership, message, sync-metadata, command, and receipt contracts.
- `domain/chat/RoomAiResponseRoutingPolicy.kt` owns the pure decision about
  whether the local Synapse member should respond.
- `data/chat/RoomConversationRepository.kt` owns transactional mapping between
  those contracts and Room rows.
- `data/db/SynapseEntities.kt`, `SynapseDaos.kt`, and `SynapseDatabase.kt` own
  local schema, SQL access, and migrations.
- `application/SynapseTurnCoordinator.kt` sequences human-message persistence,
  routing, optional AI-message creation, memory preparation, local inference,
  and generation diagnostics.
- `ui/` owns presentation and interaction. Compose does not decide whether the
  model is allowed to run.
- Authentication, remote transport, synchronization, and push notification
  providers are not Phase 1 owners and are not embedded in these models.

## Durable Local Model

### Rooms

The existing `chat_threads` table remains the room table so installed row IDs
and every existing foreign-key relationship survive the upgrade. Domain code
exposes those rows as `ChatRoomRecord`.

| Room kind | Local meaning | Default Synapse behavior |
| --- | --- | --- |
| `AI_CHAT` | Local owner chatting with Synapse | Automatic response |
| `DIRECT` | Local owner bound to exactly one placeholder human identity | Mention-only unless automatic response is enabled |
| `GROUP` | Local owner plus one or more placeholder humans | Mention-only unless automatic response is enabled |

Rooms retain title, pin/archive state, title-ownership flag, and timestamps.
They also carry provider-neutral `remoteId`, `revision`, and `syncState` fields.
Those fields are local contracts only; they do not claim a working remote sync
system.

### Participants

`chat_participants` stores stable profiles independently from any room:

- participant ID;
- `HUMAN`, `LOCAL_AI`, `REMOTE_AI`, or `SYSTEM` kind;
- display name and optional avatar URI/color metadata;
- provider-neutral sync metadata;
- creation and update timestamps.

Phase 1 has three deterministic built-in IDs:

- `participant-local-human`
- `participant-synapse-local-ai`
- `participant-system`

Placeholder humans receive stable local IDs. Migrated SMS senders receive a
stable `participant-sms-<hex-address>` ID and retain the sender-address mapping
in `sms_sender_threads`.

### Memberships

`room_memberships` uses `(roomId, participantId)` as its durable key. A row
stores:

- `OWNER` or `MEMBER` role;
- posting permission;
- joined and optional left timestamps;
- `NEVER`, `MENTION_ONLY`, or `AUTOMATIC` AI response policy;
- provider-neutral sync metadata.

Removing a member sets `leftAtEpochMillis`; it does not delete the participant
or invalidate historical authorship. Re-adding Synapse reactivates its existing
membership. The local owner cannot be removed, and Synapse cannot be removed
from an AI chat. A direct-room peer may leave, but the room remains historically
bound to that identity; a different peer requires a new direct room. Group
rooms may temporarily have no active placeholder humans after removals.

### Messages And Authorship

`chat_messages` retains all original message columns and IDs. It adds only
provider-neutral sync metadata.

`chat_message_authors` is a one-to-one ledger:

- `messageId` is the primary key and references `chat_messages`;
- `authorParticipantId` references `chat_participants`;
- deleting a message cascades to authorship;
- deleting a participant is restricted while authored messages remain.

`ConversationRole` remains on the message for prompt/runtime compatibility, but
it is no longer accepted as author identity. DAO reads start from
`chat_messages` and left-join authorship/profile rows. Repository mapping fails
closed if an author is missing instead of silently hiding an orphaned message.

Attachments, assistant generation traces, memory trace references, and SMS
receipt links continue to reference the original message IDs.

## Transactional Mutation Flows

### Create a room

One Room transaction:

1. validates title, kind, placeholder-human count, and Synapse options;
2. ensures the deterministic built-in participant profiles exist;
3. inserts the room;
4. adds the local human as active owner;
5. creates placeholder human profiles and memberships;
6. optionally adds Synapse with automatic or mention-only policy;
7. reads back the durable room and member rows.

### Submit a human message

One Room transaction:

1. verifies the room exists and is not archived;
2. verifies the author is an active human member with posting permission;
3. inserts one complete user-role message;
4. inserts exactly one immutable authorship row;
5. inserts attachment metadata when present;
6. updates the room summary/timestamp;
7. returns a `HumanMessageReceipt`.

This operation never creates an assistant message.

### Start an AI response

After routing approves a response, a separate Room transaction:

1. verifies the room and active local-AI membership;
2. inserts one streaming assistant-role message;
3. inserts its immutable Synapse authorship row;
4. returns an `AiResponseStartReceipt`.

Token append, completion, and failure updates require the message to resolve to
a local-AI author. This prevents human or remote-AI rows from entering the
phone-local streaming path accidentally.

## AI Response Routing

`RoomAiResponseRoutingPolicy` is deterministic and independently tested.

| Condition | Decision |
| --- | --- |
| Synapse is not an active local-AI member | Do not respond: `SYNAPSE_NOT_A_MEMBER` |
| Room kind is `AI_CHAT` | Respond: `AI_CHAT_AUTOMATIC` |
| Synapse membership policy is `AUTOMATIC` | Respond: `ROOM_AUTOMATIC` |
| Message contains case-insensitive `@Synapse` | Respond: `SYNAPSE_MENTIONED` |
| Otherwise | Do not respond: `MENTION_REQUIRED` |

A no-response decision returns a human-message-only outcome. It does not start
the runtime, create an empty streaming bubble, or create a generation trace.
The ViewModel can surface the typed reason without duplicating policy in UI
conditionals.

## SMS Boundary

SMS remains an explicit exception with its own external-boundary policy:

- inbound broadcasts and sender addresses remain untrusted Android input;
- outbound messages still require the in-app SMS auto-reply toggle and Android
  `RECEIVE_SMS`/`SEND_SMS` permissions;
- each sender has a stable human participant and per-sender room link;
- inbound SMS persists as a message authored by that sender participant;
- SMS may require a Synapse response without `@Synapse` only inside the
  already-authorized auto-reply flow and only while Synapse is an active room
  member;
- durable SMS receipts continue to correlate sender, room, human message, AI
  message, queue outcome, and failure state;
- SMS-originated turns continue to disable memory writes because third-party
  inbound text is not an owner memory command.

Room auto-response policy alone never grants permission to send an SMS.

## Additive Room v8→v9 Migration

The migration does not drop or rebuild `chat_threads` or `chat_messages`.
Avoiding parent-table rebuilds preserves attachments, generation traces, memory
references, and SMS receipt foreign keys.

Migration behavior:

1. Reject any legacy message role outside `USER`, `ASSISTANT`, and `SYSTEM`.
2. Add room kind and provider-neutral sync columns to existing room/message
   tables.
3. Create participant, membership, and authorship tables.
4. Add nullable `participantId` to `sms_sender_threads`.
5. Insert deterministic local-human, Synapse local-AI, and system profiles.
6. Classify every old room as `AI_CHAT`.
7. Add local-human owner and automatic Synapse memberships to every old room.
8. Add system membership to rooms containing system messages.
9. Backfill message authors:

   | Legacy role | Participant |
   | --- | --- |
   | `USER` | `participant-local-human` |
   | `ASSISTANT` | `participant-synapse-local-ai` |
   | `SYSTEM` | `participant-system` |

10. Create stable human profiles/memberships for existing SMS senders and
    backfill their sender mapping.
11. Assert that no legacy message lacks authorship.

The migration test seeds pinned, archived, normal, and SMS rooms; all legacy
message roles; an attachment; a generation trace; memory evidence links; and an
SMS receipt. It snapshots legacy values before/after migration, verifies v9
defaults and deterministic backfills, asserts one author per message, and
requires an empty `PRAGMA foreign_key_check` result.

## Diagnostics And Receipts

- Human-message, AI-response-start, room, membership, and SMS mutations return
  explicit receipts or affected-row evidence.
- Routing returns an `AiResponseDecisionReason` even when inference is skipped.
- Existing assistant generation timing traces remain attached to explicit AI
  messages.
- Startup cleanup still closes interrupted streaming rows while protecting
  recent active SMS work.
- The debug ZIP contains bounded Room/DataStore counts and health metadata only.
  Raw state, content-bearing rows, private paths, prompt configuration, account
  data, credentials, tokens, and model weights remain excluded.

## Android And Update Identity

Phase 1 changes visible product copy, not install identity:

- namespace: `app.synapse.localllm`
- rolling debug application ID: `app.synapse.localllm.debug`
- release tag: `synapse-ai`
- APK asset: `Synapse-AI.apk`
- distribution branch: `apk-latest`
- minimum Android API: `28`
- APK ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`

Signing lineage and monotonic `versionCode` requirements remain unchanged. The
v8→v9 Room migration is what preserves installed app data after an in-place APK
upgrade. Embedded llama.cpp stays ARM64-only; compatibility ABIs retain chat,
Cinder, notification, calling, and server-runtime behavior without loading the
unsupported native inference engine.

## Phase 2 Entry Blockers

Only three blockers carry forward into Synapse Chat Phase 2:

1. Authentication for real remote human identity.
2. A provider-neutral remote sync contract for identity mapping, ordering,
   revisions, conflicts, retries, and durable receipts.
3. A push-notification provider adapter behind an app-owned notification
   boundary.

Provider SDK types, backend secrets, and optimistic “synced” claims must not
leak into the local room domain before those contracts exist.
