# Private registration device-lifetime repair

## Failure and ownership

A transport UUID is an account-bound device identifier, not a permanent phone
identifier. The previous sign-out retained both that UUID and the Signal identity.
A later account could be created successfully, then fail device reservation because
the UUID already belonged to the prior account. A new invite then hit the existing
username credential instead of recovering the committed registration.

The session repository owns durable account-attempt identity; the gateway orders
account/session transitions; the Signal repository owns cryptographic erasure; the
adapter owner discards its cached adapter after successful erasure. The database
continues to reject cross-account and revoked-device reservations unchanged.

## Durable transitions and upgrade

Vault V3 stores a transport installation UUID, a separate registration retry seed,
and the canonical username of an unfinished account attempt. It stores no password
or invite. Tokens still become durable only after a matching device-binding receipt.

- First access persists the pending username before any network request.
- Same-username retries retain transport UUID, redemption seed and Signal identity,
  including after restart, a lost HTTP response, or failed session persistence.
- Switching an unfinished attempt retires its UUID durably before erasing its
  Signal state. The retry seed remains, so the original invite can still replay.
- Sign-out first purges dependent chat state, commits a new UUID and signed-out
  vault, then destroys Signal keys and discards the adapter. Remote logout remains
  explicitly best effort. If erasure fails or the process stops, the durable
  signed-out state cannot restore the retired account. Startup/access must finish
  erasure before another account attempt can proceed.
- V2 active sessions keep their device and tokens on upgrade. Signed-out V1/V2
  vaults receive a fresh transport UUID but retain the old UUID as their redemption
  seed. This preserves the exact original invite redemption ID while avoiding the
  old account's device. No manual clear-app-data step is required.

Signal storage now uses the existing two-slot cryptographically erasable storage.
The original key alias and authenticated context remain the legacy migration slot.
Migration preserves active identities and sessions; retirement destroys both key
slots and the encrypted file. Unexpected disappearance of an already committed
snapshot fails closed rather than recreating it from stale memory.

## Partial registration recovery and public errors

The existing Edge registration endpoint already replays a committed registration
when supplied the same invite and redemption ID, verifies the original account
fields, and requires password authentication before returning a device reservation.
The fix retains that redemption ID independently of device rotation. The original
receipt is available for 24 hours. After that, ordinary sign-in with the original
username/password can bind the fresh device without another invite.

All registration denials show the same retry/sign-in guidance, including invalid
invites and rejected device binding. This does not reveal whether an arbitrary
username exists. Transport failures remain explicit and retryable; neither a
partial account creation nor a reservation is reported as a completed login.
There is no automatic password reset, account deletion, device transfer, or
fallback around server authorization.

The already-created Beka account remains untouched. Its reported invalid password
cannot be repaired by changing a device UUID. Recovery still requires the correct
original credentials or a separately authorized, identity-verified account recovery
operation. Creating more invites or deleting the account is not this code repair.

## Delivery boundary

This is an Android update with local encrypted-vault migration. No Supabase schema
migration or Edge deployment is required. Database changes in this branch are tests
only. No production data, main merge, release publication, or apk-latest change is
part of this work.

The broader security gate initially failed four source-inspection assertions left
stale by the existing activity-visibility migration. A separate test-only commit
makes them inspect both policy delegation and private predicate contents, retaining
the shared-room, opt-in and initplan requirements.

Local SQL validation uses PostgreSQL 16 with a Supabase platform fixture for Auth,
Storage and cron schemas. It exercises the repository migrations, pgTAP behavior
and security checks, including account commit / failed device reservation / exact
redemption replay / fresh reservation. It does not exercise hosted GoTrue password
HTTP, cron execution, Storage HTTP, or physical two-phone messaging.

## Validation receipt — 2026-10-04

- JDK 21, Android SDK 36; Gradle used `--no-daemon --max-workers=2
  -Pkotlin.compiler.execution.strategy=in-process` to avoid a stale local Kotlin
  daemon connection.
- `:privatechat:test :privatechat:ktlintCheck :privatechat:lintDebug
  :privatechat:assembleDebug :privatechat:assembleRolling`: passed; 240 JVM tests,
  zero failures/errors/skips.
- Deno `task check`: formatting, lint and all six Edge entrypoint type checks passed.
- Deno `task test`: 15 passed.
- Local pgTAP: activity visibility 5/5, backend behavior 105/105, backend security
  96/96, registration recovery 7/7; 213 total. Local fixture server stopped afterward.
- `node --test scripts/ci/verify-synapse-private-jni.test.mjs`: 4/4 passed.
- Packaged rolling APK JNI verification: all six literal native callback descriptors
  passed using the documented apkanalyzer verifier.
- No ADB device was connected; the optional on-device instrumentation probe was not run.
- Final diff reviewed for account/device boundaries, retained retry identity,
  erasure ordering, legacy compatibility, enumeration-safe errors, and unrelated churn.
  `git diff --check` passed. No dependency or runtime SQL changes were introduced.
- The unrelated untracked `docs/synapse-private-device-sharing-plan.md` remained
  untouched and unstaged, SHA-256
  `3ad328659375de8d695ac76d239a6628eee14d4ade6c79411b52b7d6e15a94b7`.

Validation APK: `privatechat/build/outputs/apk/rolling/privatechat-rolling.apk`;
application ID `app.synapse.privatechat`; version **0.1.2031 (2031)**, using the
repository's default local version, not a new published release version.
SHA-256: `3855ed04d87472953999e46e8d322c8d746b243b2d75a25e195e69fa768ab9c9`.
The APK is a build artifact and is not committed.

## Changed files

- `docs/synapse-private-registration-repair.md`
- `privatechat/src/main/java/app/synapse/privatechat/crypto/SignalProtocolAdapterOwner.kt`
- `privatechat/src/main/java/app/synapse/privatechat/crypto/SignalProtocolStateRepository.kt`
- `privatechat/src/main/java/app/synapse/privatechat/crypto/storage/AndroidSignalProtocolStateRepositoryFactory.kt`
- `privatechat/src/main/java/app/synapse/privatechat/crypto/storage/EncryptedSignalProtocolStateRepository.kt`
- `privatechat/src/main/java/app/synapse/privatechat/data/account/PrivateSignalDeviceBootstrapper.kt`
- `privatechat/src/main/java/app/synapse/privatechat/data/account/SupabasePrivateAccountGateway.kt`
- `privatechat/src/main/java/app/synapse/privatechat/data/session/EncryptedPrivateSessionRepository.kt`
- `privatechat/src/main/java/app/synapse/privatechat/data/session/PrivateSessionContracts.kt`
- `privatechat/src/main/java/app/synapse/privatechat/data/session/PrivateSessionVaultCodec.kt`
- `privatechat/src/test/java/app/synapse/privatechat/crypto/InMemorySignalProtocolStateRepository.kt`
- `privatechat/src/test/java/app/synapse/privatechat/crypto/storage/EncryptedSignalProtocolStateRepositoryTest.kt`
- `privatechat/src/test/java/app/synapse/privatechat/data/account/SupabasePrivateAccountGatewayTest.kt`
- `privatechat/src/test/java/app/synapse/privatechat/data/session/EncryptedPrivateSessionRepositoryTest.kt`
- `supabase/tests/database/backend_behavior.test.sql`
- `supabase/tests/database/backend_security.test.sql`
- `supabase/tests/database/registration_recovery.test.sql`
