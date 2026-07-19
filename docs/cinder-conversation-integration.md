# Cinder Conversation Integration

Status: app-side conversation door implemented; authenticated backend transport not configured

Cinder is an app-owned remote-assistant endpoint that uses the same Synapse Chat
conversation list, message thread, composer, Room cache, draft lifecycle, and
outbox as other remote conversations. It is not a settings panel, terminal, or
second message store.

## Implemented App Boundary

- Stable assistant ID: `cinder`.
- Stable participant ID: `participant-cinder-remote-ai`.
- Stable app-owned room ID: `assistant_cinder`.
- Room kind: `ASSISTANT`, distinct from human `DIRECT` and `GROUP` rooms.
- The app ensures the Cinder room in the account-scoped remote Room cache and
  preserves it when authoritative Firebase human-room synchronization removes
  rooms that are no longer authorized.
- Selecting Cinder opens the normal remote message thread and composer.
- A text send creates one normal optimistic cached human message and one normal
  idempotent outbox operation. The current unavailable adapter then marks that
  operation failed with an actionable not-configured reason.
- The conversation gateway router keeps Firebase human rooms on their existing
  gateway and routes only registered assistant room IDs to the app-owned
  assistant gateway seam.
- Cinder messages may use `REMOTE_AI` only with the registered participant ID
  and `REMOTE_HOSTED` provenance.
- Cinder rooms are rejected from the phone-local Synapse inference path. The
  app does not generate canned Cinder replies or treat Cinder as Synapse.

No Cinder URL, token, API key, callable, Firestore collection, or successful
reply is claimed by this milestone.

## Required Backend Contract

The next milestone needs one deployed adapter behind
`RemoteAssistantConversationGateway`. Its external API may be a Firebase
callable or a separately hosted HTTPS service, but it must satisfy the same
semantic contract.

### Authentication and authorization

- Authenticate every request with the current Firebase account session. If the
  chosen edge supports App Check, validate the App Check assertion there too.
- Resolve the account UID from the verified authentication context; never trust
  an account UID supplied as request data.
- Require the same active-account, allowed-account, and forced-password-change
  checks as existing remote chat before accepting a turn.
- Keep provider credentials server-side. No Cinder credential belongs in the
  APK, Room database, logs, notification payloads, or user-visible receipts.

### Submit request

The authenticated adapter must accept the following validated fields:

| Field | Contract |
| --- | --- |
| `assistantId` | Exactly `cinder` for this endpoint. |
| `roomId` | Exactly `assistant_cinder`. |
| `messageId` | The existing Synapse client message ID. |
| `idempotencyKey` | The existing outbox key; currently equal to `messageId`. |
| `body` | UTF-8 text, trimmed, 1-4,000 characters for the first backend slice. |
| `clientCreatedAt` | Client timestamp used as metadata, never as server ordering authority. |

Attachments remain unsupported until the backend can authorize the existing
attachment metadata and object ownership rules. The adapter must reject them
explicitly rather than silently omit them.

### Acceptance receipt and idempotency

The service must durably key acceptance by
`(authenticatedAccountUid, assistantId, idempotencyKey)` before dispatching a
turn. A repeated request with the same key and identical content returns the
same receipt and does not invoke Cinder again. Reuse with different content
fails closed.

The receipt must include:

- the original `messageId` and `idempotencyKey`;
- a server-owned turn ID;
- `ACCEPTED` or `ALREADY_ACCEPTED`;
- a server acceptance timestamp;
- a per-account Cinder conversation sequence number.

### Reply and ordering

Each successful reply must be persisted before notification and expose:

- a unique server message ID;
- room ID `assistant_cinder`;
- sender/participant ID `participant-cinder-remote-ai`;
- author kind `REMOTE_AI` and provenance `REMOTE_HOSTED`;
- the originating human `messageId` as the reply correlation;
- the server turn ID, server timestamp, and monotonically increasing
  per-conversation sequence;
- a visible response body no longer than the app/backend limit.

The app adapter must order synchronized messages by server sequence and use the
message ID as a deterministic tie-breaker. Client timestamps cannot decide
remote order.

### Push and synchronization

- Push only opaque routing data such as `roomId`, `messageId`, and an unread
  count. Do not place Cinder prompt or reply plaintext in FCM payloads.
- Opening a push must still pass the app's authorized-room check before
  navigation.
- The adapter must fetch replies through the authenticated gateway, map them to
  `RemoteCachedMessage`, and persist them through the existing account-scoped
  cache. It must not introduce another database or UI thread.
- Cursor/revision state and retry receipts must be durable so process death,
  reconnects, and duplicate pushes cannot duplicate a reply.

## Exact Remaining Blocker

This repository contains no deployed authenticated Cinder ingress, durable
turn/reply store, provider credential, response synchronization feed, or push
producer. Until those server owners exist, the app-side gateway remains
deliberately unavailable and every queued Cinder send ends in the explicit
not-connected state.
