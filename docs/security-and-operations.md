# Security And Operations Boundary

This document records the implemented Synapse Chat remote-service boundary. It
is an engineering receipt, not a claim that the draft branch has passed its
physical-device or release gates.

## Trust Boundary

- Remote chat uses Firebase Authentication, callable Cloud Functions,
  Firestore, Cloud Storage, and Firebase Cloud Messaging.
- Remote chat is **not end-to-end encrypted**. Firebase and authorized project
  operators remain inside the trust boundary. Push payloads contain routing
  identifiers, not message bodies.
- Phone-local rooms, local model execution, Room memory, SMS receipts, and local
  diagnostics remain app-local and are not uploaded by the remote-chat layer.
- Hosted AI is disabled. No provider credential or paid service is configured.
  A future provider key must be supplied through Google Secret Manager and must
  never enter Android resources, Firestore, source, logs, receipts, diagnostics,
  analytics, or PR text.

## Authorization And Abuse Controls

- Custom claims provide a fast role/account-state gate; each privileged server
  path also reloads the authoritative profile. A mismatch fails closed.
- Sensitive owner password/account deletion and group deletion paths require an
  authentication time no older than five minutes.
- Password reset, account disable/delete, and explicit session revocation first
  persist `sessionsRevokedAt`, then revoke Firebase refresh tokens. Callable
  authorization rejects an ID token whose `auth_time` is at or before that
  marker.
- Invite registration is limited to 10 attempts per source address per 15
  minutes. Authenticated callables use hashed actor/bucket identifiers with
  bounded fixed windows. Firebase Authentication sign-in itself is provider
  owned rather than a custom callable; password-change completion is still
  callable-rate-limited and recent-authenticated.
- Firestore and Storage rules default-deny. Client writes are limited to
  validated human-message creation, bounded typing presence, owned profile
  presentation, and authorized Storage uploads. Other mutations are
  server-authoritative.
- FCM recipients are recomputed from active profiles, active memberships,
  notification preferences, and room state. Invalid or unregistered Firebase
  installations are disabled after a send response identifies them.
- Local remote-chat state is account-scoped. A session generation owns Firebase
  listeners and transfers; account change or sign-out cancels those resources,
  clears active projections, and terminates local authentication even when
  remote device cleanup fails or times out.

## App Check Sideload Compatibility Assessment

Status on this branch: the App Check Android SDK is not installed and App Check
enforcement is not enabled. Authentication, authorization, rate limits, and
rules remain mandatory without it.

Firebase's Play Integrity provider supports apps distributed outside Google
Play, but the default recognition policy is not suitable for this APK channel.
For an exclusively outside-Play app, Firebase documents `PLAY_RECOGNIZED` and
`LICENSED` as not required and recommends the device-integrity level. See the
[Firebase Play Integrity provider setup](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
and [Play Integrity verdict definitions](https://developer.android.com/google/play/integrity/verdicts).

App Check may be introduced only through this rollout:

1. Register the exact rolling package ID and signing-certificate SHA-256 in the
   Firebase App Check console and link the same project in Play Console.
2. Configure the outside-Play policy explicitly; do not accept default
   `PLAY_RECOGNIZED` enforcement.
3. Ship the provider SDK with enforcement off and observe App Check metrics for
   Functions, Firestore, Storage, and Authentication.
4. Prove valid tokens on the Galaxy S9/API 29 sideload, the modern API 33+
   sideload, the updater path, and the release signer. Debug-provider tokens are
   limited to emulator/CI and must never be committed.
5. Enable enforcement service by service only after the installed population
   has the provider-enabled build and the physical receipts show no valid
   sideload denial. Roll back enforcement if legitimate devices fail.

Enabling enforcement before those steps would break existing clients that do
not send App Check tokens, so it is explicitly outside current source
acceptance and remains a release gate.

## Deletion And Retention

The daily cleanup jobs delete at most 100 matching documents per policy per run
and write durable owner-visible job receipts. Backlogs therefore drain in
bounded batches rather than through an unbounded function invocation.

| Data | Policy |
| --- | --- |
| Typing presence | Client expiry is at most 15 seconds; expired documents are deleted daily. |
| Callable and registration rate-limit state | 2 days after the window starts. |
| Failed/incomplete registration reservations | 30 days after creation; the live reservation expires after 10 minutes. |
| Invitations | 30 days after invitation expiry. |
| Notification delivery receipts | 30 days after send start. |
| Remote-AI configuration/response audit events | 90 days. |
| Invite redemption receipts | 180 days. |
| Security audit events | 365 days. |
| Pending attachment upload | Expires after 24 hours. |
| Finalized but unattached upload | Expires after 1 hour. |
| Attachment on deleted message | Object cleanup is triggered by the message tombstone. |

Active accounts, rooms, and messages are durable product records and do not
expire by age. Owner-confirmed permanent account deletion removes Auth,
profile, username reservation, devices, blocks, and deletion request; historical
room membership is deactivated so existing message authorship remains
referentially coherent. Message deletion immediately removes its body and
reactions, retains a tombstone for reply/history integrity, and triggers media
cleanup. Group deletion requires recent authentication and an exact-title
confirmation, deactivates all memberships, and retains an inaccessible room
tombstone and message history for integrity. No automated hard purge of those
tombstones is claimed.

## Diagnostics And Operational Receipts

The owner surface reports backend revision/health, app/release channel,
registered-device and room counts, local outbox failures, notification delivery
counts, bounded room/member integrity findings, and cleanup job status. Test
push actions and owner mutations are authorized and rate-limited.

The Android debug ZIP is metadata-only. Canary tests assert that raw Room and
DataStore files, message/memory content, prompts, SMS/account data, credentials,
tokens, exception reasons, model/private paths, and UI content do not enter the
archive. Aggregate usage metadata can still be sensitive, so the archive asks
the user to review it before sharing.

## Release Boundary

This branch must stay draft. App Check activation, live Firebase acceptance,
signer comparison, and the two physical-device matrices remain release gates.
No merge, `apk-latest` replacement, or GitHub release publication is authorized
until those receipts exist.
