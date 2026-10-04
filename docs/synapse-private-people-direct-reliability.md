# People, direct conversations, and activity reliability

Implementation branch: `codex/private-people-direct-reliability-20261004`.
Base: `7a3c143029903d6d4d7b6516a76843b5b3cdf885` (published 0.1.2048 repair).
This work is feature-branch implementation only. No production migration, Edge
function deployment, main merge, or APK release publication was performed.

## Confirmed causes

- The old direct creation UI accepted a title and called the room-creation path
  that inserts only the creator as OWNER. It then required a separate invitation.
  People → Chat now supplies a target account and receives a two-member receipt.
- Read-only production logs for 2026-10-04 18:44–18:52 UTC show all three supplied
  failures (18:45:23.550, 18:45:54.883, 18:50:20.027) reporting SQLSTATE 42501:
  `new row violates row-level security policy (USING expression) for table "presence_state"`.
  The previous SELECT policy hides an expired own row. UPSERT's conflict UPDATE
  requires that row to be visible, even though the INSERT/UPDATE device ownership
  predicate is satisfied. The old 60-second publication cadence also matched the
  60-second server expiry, making expiry likely before renewal. The new pgTAP
  suite reproduces the exact production error with a valid, opted-in, bound
  device, and proves the narrowly scoped own-row SELECT repair.
- Before a first shared room exists, UPSERT RETURNING can also fail because no
  own row passes the peer-only SELECT predicate. That case is covered separately.
- Device authorization remains `private.current_device_id()`, resolved from the
  authenticated JWT session and server-owned device/session binding. No new
  caller-controlled device header or weakened ownership check was introduced.
- Activity polling previously isolated only HTTP 403. Other activity HTTP,
  transport, or parse failures could escape the shared polling operation and be
  reported as message transport unavailability. Activity feeds now explicitly
  report unavailable without discarding successfully fetched message state.
  Cancellation still propagates. Publication itself remains independently owned.

## Ownership and contracts

The domain exposes `PrivatePeopleGateway`, directory people, and a direct-room
receipt. `SupabasePrivatePeopleGateway` owns strict RPC parsing and authenticated
session execution. `PrivatePeopleCoordinator` owns foreground lifecycle,
short-lived presentation, independent directory/heartbeat status, and receipt
validation. The existing chat view model navigates only after the confirmed room
also appears in an authoritative room feed. Chats/People are top-level tabs;
People supports filtering, active-first ordering, and a person → Chat dialog.
The misleading solo-direct creation choice is removed; group creation and legacy
invitation redemption remain available.

`list_directory_people` exposes exactly `user_id`, `display_name`, and
`active_for_seconds` (0–60), excluding self. Pagination uses an account-ID cursor
in pages of 100; the Android adapter loads all pages and validates exact fields,
IDs, duplicates, cursor progress, labels, and bounded activity duration. It never
selects global Auth emails, credential material, device keys, or invite records.
There is no broader SELECT grant on profiles/devices. Anonymous, unbound,
revoked-device, deleted-session, and banned-account callers fail closed.

Directory activity is intentionally separate from optional conversation
presence. Every signed-in foreground account publishes every 25 seconds,
regardless of the conversation presence toggle. A private table retains one
expiry/session row per device, no last-seen history or location. The server fixes
expiry at 60 seconds; revoked devices/sessions and banned accounts do not appear
active. A cron job removes expired rows every minute. The client anchors activity
expiry before each request and expires labels even when directory refresh fails.
Background/sign-out cancels publication. People explains this behavior visibly.
Conversation presence still requires opt-in and shared-room membership for peers;
the new SELECT policy only permits the current bound device to read its own row.
Optional presence publication also renews every 25 seconds instead of 60.

`open_direct_conversation` checks actor/target availability and target device
eligibility, then locks the unordered account pair for the transaction. A unique
private pair mapping and one transaction create exactly one DIRECT room with two
members (one OWNER, one MEMBER). Repeated or opposite-direction calls return the
same room. Self, unknown, banned, and device-unavailable targets fail closed.
Failed calls leave no solo room. Internal tables have RLS, no client table grants,
indexed foreign keys, and private security-definer implementations with empty
search paths behind authenticated invoker RPCs.

The direct RPC creates routing/membership only: no plaintext custom title,
message, or fabricated encrypted payload. The client derives the label from the
peer's already-public profile and marks it `PARTICIPANT_LABEL`, not decrypted
metadata. The existing Signal/libsignal message, recipient-device, prekey claim,
complete-envelope validation, encrypted outbox, and decrypt/cache paths are
unchanged. Directory visibility alone does not authorize prekey claims; creating
membership enables the existing secure room APIs. Legacy direct rooms remain
separate and are not adopted or merged; encrypted histories are never combined.
Older clients may display a generic label for the new routing-only room.

Message/social observers share a small polling retry policy, with independent
per-observer delays of 5, 10, 20, then at most 30 seconds after failure. A valid
snapshot resets the delay and restores CONNECTED. Last confirmed messages remain
visible. Directory refresh uses its own bounded 20–80-second failure backoff;
heartbeat failure has its own status and fixed 25-second retry cadence. Existing
HTTP retries remain bounded at 100/300 ms for eligible idempotent requests.

## Validation receipt — 2026-10-04

- All requested Gradle gates passed using JDK 21 / Android SDK 36:
  `:privatechat:test :privatechat:ktlintCheck :privatechat:lintDebug
  :privatechat:assembleDebug :privatechat:assembleRolling`.
  Flags: `--no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`.
- JVM: 253 tests, zero failures/errors/skips. New coverage includes paginated
  strict directory parsing, unexpected private-field rejection, device/target
  receipt validation, foreground cancellation, automatic activity without opt-in,
  expiry during refresh failure, independent recovery, direct-room navigation,
  preserved messages through reconnect, return to CONNECTED, peer labels, and
  bounded/resetting poll delays.
- PostgreSQL 16 + existing local Supabase platform fixture: 260 pgTAP checks
  passed (47 People/direct/activity, 105 backend behavior, 96 backend security,
  5 activity visibility, 7 registration recovery). The fixture's Auth user schema
  was extended locally with the real `banned_until` and `deleted_at` columns.
- Twenty concurrent opposite-direction direct RPC calls in a separate disposable
  database all returned one room; database counts were one room and two members.
- Deno `task check` passed (format, lint, six Edge entrypoint type checks);
  `task test` passed all 15 tests. No Edge source changes were required.
- JNI verifier tests: 4/4 passed. Actual rolling APK: all six required native
  callback descriptors passed. APK signature verification passed with the stable
  certificate SHA-256 `6f762970e8c29b2c810cb790c1e08dbebf80e40f60a03516b7ca665964a14e7b`.
- Rolling artifact: `privatechat/build/outputs/apk/rolling/privatechat-rolling.apk`.
  Local default version `0.1.2031` / code `2031`; package `app.synapse.privatechat`.
  SHA-256 `f7a37c7447d1c0056806070d40228813abf53b544030f4a32df6787c440579e4`.
  This is a local validation artifact, not the published APK or release version.
- Complete diff reviewed for owner boundaries, authorization, cancellation,
  expiry, recovery, unchanged encrypted payload paths, and unrelated churn.
  No dependency additions or new domain-to-adapter/UI imports. `git diff --check`
  passed. The unrelated device-sharing plan was not modified or staged.

## Rollout requirements and limits

Apply `20261004190453_private_people_direct_activity.sql` before shipping the
Android feature. It adds three authenticated RPCs, private directory/pair tables,
expiry cleanup, and the narrow presence policy. No Edge deployment is needed.
Deployment and APK publication require a later explicit owner instruction.

No ADB device was connected. Physical two-phone messaging, hosted RPC HTTP
acceptance, and scheduled cron execution were not run; the local database fixture
verifies SQL behavior rather than the hosted platform runtime. Calling/media is
not implemented and no Call action was added. The app remains a messenger with
existing Signal encryption; this work does not claim a calling stack.

## Changed files

Paths below are relative to the repository; Android package paths share
`privatechat/src/main/java/app/synapse/privatechat/`:

- `PrivateChatCompositionRoot.kt`
- `domain/chat/PrivatePeopleGateway.kt`
- `domain/chat/PrivateChatSnapshots.kt`
- `data/chat/SupabasePrivatePeopleGateway.kt`
- `data/chat/PrivateChatPollingFlow.kt`
- `data/chat/PrivateChatBackendContracts.kt`
- `data/chat/PrivateChatSnapshotAssembler.kt`
- `data/chat/SupabasePrivateChatGateway.kt`
- `data/chat/SupabasePrivateSocialGateway.kt`
- `data/chat/SupabasePrivateChatPollingApi.kt`
- `ui/chat/PrivatePeopleCoordinator.kt`
- `ui/chat/PrivatePeoplePane.kt`
- `ui/chat/PrivateChatRoute.kt`
- `ui/chat/PrivateChatScreen.kt`
- `ui/chat/PrivateChatViewModel.kt`
- `ui/chat/PrivateConversationHeader.kt`
- `ui/chat/PrivateCreateConversationDialog.kt`
- `ui/chat/PrivatePresencePublisher.kt`

Tests share `privatechat/src/test/java/app/synapse/privatechat/`:

- `data/chat/PrivateChatPollingFlowTest.kt`
- `data/chat/PrivateChatPollingDecoderTest.kt`
- `data/chat/SupabasePrivateChatPollingApiTest.kt`
- `data/chat/SupabasePrivatePeopleGatewayTest.kt`
- `ui/chat/PrivatePeopleCoordinatorTest.kt`
- `ui/chat/PrivateChatViewModelHealthTest.kt`

Other files:

- `supabase/migrations/20261004190453_private_people_direct_activity.sql`
- `supabase/tests/database/people_direct_activity.test.sql`
- `docs/synapse-private-people-direct-reliability.md` (this report)
