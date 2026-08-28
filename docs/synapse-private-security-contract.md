# Synapse Private Security And Retention Contract

This document defines the acceptance boundary for the human-only Synapse
Private app. It is a testable engineering contract, not a promise of perfect
anonymity or instantaneous physical erasure from third-party infrastructure.

## Product Boundary

Synapse Private is a separate Android application with application ID
`app.synapse.privatechat`. It reuses the existing messenger presentation, but
it must not package or initialize local AI, Cinder, llama.cpp, model download,
Termux, AI memory, hosted inference, or SMS auto-reply code.

The app supports invited human participants only. Supabase owns temporary
ciphertext transport and authorization state. Phones own plaintext, encryption
keys, decrypted presentation, and local expiration.

## Current Rolling Release Scope

The current rolling build is a text-chat alpha. It includes invite-only
pseudonymous accounts, profiles, direct and group rooms, one-use room invites,
pairwise encrypted message fan-out, replies, reactions, edits,
delete-for-everyone, four timed-retention policies, archive, pin, mute, optional
presence, optional typing indicators, and optional read receipts.

The current build deliberately does **not** include attachments, voice notes,
voice/video calls, TURN configuration, push notifications, server-side or
persistent plaintext search, or an end-user safety-number verification screen.
These features must remain disabled rather than use a weaker fallback. A newly
joined device also shows a generic encrypted-room title until an authorized
current device publishes a new metadata envelope; automatic metadata backfill
is not implemented yet.

This build is not Signal and is not connected to the Signal service. It embeds
the pinned libsignal library as a cryptographic implementation detail. The
rolling alpha must not be described as independently audited or as providing
network-level anonymity.

## Honest Privacy Claims

The release may claim end-to-end encrypted message content only after the
cryptographic acceptance tests in this document pass. It must not claim that
participants are untraceable or that all metadata is anonymous.

Supabase and network providers can observe connection addresses, request time,
traffic volume, and the pseudonymous identifiers needed to authorize room
membership. Optional push delivery exposes a device token and delivery timing
to the push provider. A room participant can copy, photograph, or modify a
client to retain content already delivered to that device.

The supported claim is therefore:

> Synapse Private uses pseudonymous accounts, minimizes retained metadata, and
> encrypts supported message content between participant devices.

Any stronger anonymity claim requires a separately reviewed network-anonymity
transport and must not be inferred from content encryption.

## Pseudonymous Identity

- Registration requires a high-entropy, single-use, expiring invitation code.
- The app does not request a real email address, telephone number, address book,
  advertising identifier, or social identity.
- A display name is presentation metadata, not an authorization identifier and
  must not be globally enumerable.
- Supabase Auth uses a random internal account address and a client-generated
  high-entropy secret. The internal address is never shown as identity.
- Account and device identifiers are random UUIDs. Authorization uses the
  authenticated account ID plus authoritative database membership, never
  user-editable metadata.
- Device credentials and private identity keys are stored with Android
  Keystore protection.
- Recovery is explicit. Without a recovery credential or an approved
  device-to-device transfer, reinstalling the app loses the pseudonymous
  identity and undecryptable history is not recovered.
- Revocation fails closed. A revoked account or device cannot read room rows,
  attachment objects, key envelopes, call signaling, or realtime broadcasts.

## Content Encryption

- Use a maintained, reviewable messaging protocol implementation. Do not
  design a custom cryptographic protocol from low-level primitives.
- Every device owns a long-lived identity key and rotates signed/pre-key
  material according to the selected protocol.
- Direct messages provide authenticated sessions, forward secrecy, replay
  resistance, and out-of-order delivery support.
- Group rooms fan each message out through authenticated pairwise device
  sessions. A removed member receives no later envelope, and RLS denies later
  room rows. Shared group sender/epoch keys are not used in this release.
- The library can calculate identity fingerprints, but the current rolling
  release has no end-user safety-number verification surface and must not claim
  verified peer identity.
- The server stores versioned encrypted envelopes. It never receives message
  plaintext, notification previews, or plaintext search tokens.
- Encrypted room creation metadata is accepted only when its room, owner, and
  client mutation identifiers match the authoritative room row. Room, message,
  and reaction mutation receipts echo their request context, and the Android
  client rejects a receipt that does not match its request.
- An encrypted mutation is committed to the device outbox before dispatch. A
  poll resolves any ambiguous pending request with its original mutation ID
  and ciphertext before publishing a connected snapshot. If a later request
  reaches the outbox first, the earlier request is recovered and the later
  request is rejected until the caller refreshes, preventing duplicate sends
  or room creation under a new mutation ID.
- Cryptographic library versions and artifact hashes are pinned. Dependency and
  license review is a release gate.

## Ephemeral Message Lifecycle

Every message has an immutable server-validated `expires_at` bounded by the
room policy. Initial supported policies are five minutes, one hour, twenty-four
hours, and seven days. Burn-after-read may shorten that deadline after every
active recipient acknowledges delivery, but it must never extend the original
deadline.

Server lifecycle:

1. Accept a bounded ciphertext envelope and immutable expiry.
2. Authorize delivery only to current active room members.
3. Make expired rows unreadable at `expires_at` in every policy, independently
   of background-job timing.
4. Attempt physical database purge every minute while the hosted project and
   scheduler are running, then catch up immediately after a paused Free project
   resumes.
5. Cascade deletion to reactions, receipts, encrypted envelopes, reply links,
   and typing state.
6. Retain only aggregate purge receipts that contain no room, account, message,
   object-path, network, or content identifiers.

Hosted Supabase backups and provider recovery systems may retain deleted
ciphertext for their infrastructure-defined window. They never receive
plaintext or message keys, and client key destruction makes those remnants
cryptographically unreadable. A requirement that even expired ciphertext
never enter or remain in provider backups requires a separately operated
backend with backups disabled; hosted Supabase cannot honestly make that
stronger guarantee.

Client lifecycle:

1. Persist server ciphertext plus only a bounded Android-Keystore-encrypted
   payload cache; never persist unprotected plaintext.
2. Decrypt only for active presentation or an explicitly requested export.
3. Keep decrypted media in bounded private temporary storage and remove it when
   the viewer closes or the message expires.
4. Remove expired ciphertext, derived previews, reply snippets, reactions,
   receipts, thumbnails, and cached media.
5. Destroy obsolete message keys so residual ciphertext is cryptographically
   unreadable.

Deletion is a hard-delete command. Synapse Private does not implement a trash
folder, message tombstone archive, deleted-message recovery API, permanent edit
history, server-side full-text index, or server-side conversation export.

## Local Storage

- Android backup and device-to-device data extraction are disabled for private
  app data.
- The current client has no plaintext message database. Its bounded decrypted
  payload cache is encrypted at rest with rotating Android Keystore keys.
- No persistent plaintext FTS table, room preview, reply preview, clipboard
  history, notification content, widget content, or diagnostic snapshot is
  allowed.
- Android secure-window protection blocks ordinary screenshots and recent-app
  previews. Leaving the foreground synchronously closes invitation overlays,
  clears displayed one-use codes, and cancels in-flight invitation requests.
- Conversation search scans only the currently presented, unexpired confirmed
  room titles, senders, and latest-message previews in memory. Search queries
  and indexes are not persisted or sent to Supabase.
- Debug archives and logs contain bounded state and correlation identifiers,
  never usernames, message content, keys, tokens, invite codes, object paths,
  call descriptions, ICE candidates, or network addresses.
- The account-session vault and decrypted payload cache use two fixed Android
  Keystore aliases. Credential replacement, session clearing, cache purge, and
  legacy migration commit retained state under a fresh alternate key, destroy
  the retired key, and complete interrupted retired-key cleanup on restart.
- Sign-out destroys locally decrypted conversation state before rotating the
  session vault, then attempts remote session revocation. A remote outage or
  rejection must be reported as unconfirmed revocation without pretending the
  durable local session still exists.
- Session invalidation clears recent in-memory polling state, cryptographically
  rotates or destroys retained-cache keys, removes pending Signal mutations,
  resets their affected peer sessions, and then destroys device-local envelope
  keys before the session vault is erased. Flash wear-leveling prevents an
  honest guarantee that every overwritten byte is physically erased
  immediately.

## Future Attachments And Voice Notes

Attachments and voice notes are disabled in the current rolling release. If
enabled later, all of the following become release gates:

- Encrypt content and thumbnails on the sender before upload with independent
  random file keys.
- Store only ciphertext in a private Supabase Storage bucket.
- Deliver file keys through the message protocol, never Storage metadata.
- Bind the authenticated attachment header to room, message, sender, media
  kind, byte count, and ciphertext digest.
- Enforce conservative size and duration limits before upload.
- Expiry or deletion removes the database intent, Storage objects, encrypted
  local cache, decrypted temporary files, waveform data, and poster frames.
- CDN or provider remnants contain ciphertext only; destroying all delivered
  file keys supplies cryptographic erasure.

## Presence, Receipts, And Notifications

- Online status, last-seen state, typing indicators, and read receipts are off
  by default and independently opt-in because they reveal activity metadata.
- Typing state expires within seconds and is never written to a durable audit.
- Receipts expire with their message.
- Push notifications are optional and content-free. A push may carry a random
  wake-up event identifier but no sender, room title, message body, attachment
  name, reaction, call description, or encryption key.
- The app has no analytics or advertising SDK. Crash reporting is disabled
  unless a later privacy review proves that its payload and retention meet this
  contract.

## Future Calls

Calling is disabled in the current rolling release. It may be enabled only
after a trusted TURN service is configured and all of the following pass:

- Call invitations and WebRTC session descriptions are encrypted for the
  participant devices before entering Supabase.
- Media uses authenticated DTLS-SRTP and the UI exposes the verified peer
  identity associated with the messaging session.
- Anonymous call mode uses `relay` ICE transport policy. Host and server-
  reflexive candidates are not published because direct peer-to-peer WebRTC can
  reveal participant network addresses.
- If no trusted TURN relay is available, anonymous call setup fails closed. It
  must not silently fall back to direct candidates.
- TURN credentials are short-lived and issued to current room members. Logs do
  not retain session descriptions, candidates, participant identifiers, or
  network addresses beyond the infrastructure minimum.
- Call records are ephemeral status, not durable conversation history, and are
  purged after their bounded terminal window.

## Supabase Authorization Boundary

- Enable RLS on every table in an exposed schema.
- Explicitly grant Data API access only to required tables and functions.
- An authenticated database role alone is never authorization; every policy
  checks the active account/device and current room membership.
- Authorization does not depend on `user_metadata` or stale role claims.
- Invitation hashes, rate limits, administrative mutation receipts, and purge
  coordination live outside exposed schemas.
- Private transactional ledgers cap each device at 750 durable encrypted
  envelopes and 768 KiB of ciphertext across all polling tables, plus 250
  envelopes and 256 KiB per room and per sending account. Message sends are
  limited to 30 per actor and room per minute, so one invited participant cannot
  make a valid polling response exceed the client's fail-closed budget or
  exhaust capacity reserved for unrelated senders and rooms.
- Any required `SECURITY DEFINER` function lives in a non-exposed schema, fixes
  `search_path`, verifies the caller inside the function, revokes `PUBLIC`
  execution, and grants only the exact entry point required.
- Storage objects are private and authorized by current active membership plus
  immutable attachment metadata.
- The publishable key may be packaged in the app. Secret and service-role keys
  must never enter source, Gradle properties, APK resources, logs, tests, or
  release artifacts.

## Current Rolling Alpha Acceptance Proof

The text-only rolling alpha may be published only when automated or
reproducible checks prove all of the following for the exact commit:

1. Libsignal session establishment, out-of-order delivery, exact encrypted
   retry, process-restart recovery, and local-envelope erasure pass focused
   regression tests.
2. The Supabase schema exposes ciphertext payloads only, and cross-room reads
   and writes fail for non-members.
3. Reusing, racing, guessing, or redeeming an expired invite fails without
   creating an active account.
4. Revocation blocks an existing session at the authoritative database gate.
5. Removing a group member denies later rows and omits that member's devices
   from every later message fan-out.
6. Expiry and delete-for-everyone remove related rows and make removed
   device-local ciphertext undecryptable after its DEK is erased.
7. A phone that stays offline past expiry cannot recover the expired message
   when it reconnects.
8. Process death, logout, account switching, reinstall, and local wipe leave no
   recoverable plaintext cache.
9. Logs, diagnostics, database rows, and APK resources pass plaintext and
   secret canary scans.
10. The APK contains no llama.cpp native library, model/runtime code, Cinder
   endpoint, Termux/SMS permission, AI string, or AI background component.
11. Android unit tests, lint, formatting, assembly, Supabase tests, and security
    advisors pass, and the published version, signer, ABI set, source commit,
    APK digest, and update metadata are verified.

A production-grade or independently audited E2EE claim remains blocked on
multi-device adversarial field testing, an end-user identity-verification UX,
and independent review. Attachments and calls require their respective future
sections to pass before either feature can be enabled or advertised.

## Deliberate Non-Goals

- Recovering expired or deleted content
- Server-side plaintext moderation or search
- Silent account recovery through email or telephone number
- Guaranteeing that another participant did not photograph or modify their
  device to retain delivered content
- Claiming network-level anonymity while clients connect directly to Supabase,
  push providers, or a TURN relay
- Trading authorization or cryptographic verification for graceful fallback
