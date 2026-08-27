# Synapse Private backend operations

This backend belongs to a new, dedicated Supabase project. The first migration
fails when `auth.users` already contains a row, preventing an unrelated or
legacy Auth identity from becoming an implicit Synapse Private account.

## Deployment order

1. Apply these migrations in timestamp order:
   - `20260820092448_create_synapse_private_backend.sql`
   - `20260820092927_enforce_synapse_private_access.sql`
   - `20260820092929_schedule_synapse_private_retention.sql`
   - `20260825090000_add_encrypted_chat_mutations.sql`
   - `20260825100000_enforce_idempotent_core_mutations.sql`
   - `20260825115540_bind_encrypted_chat_context_and_capacity.sql`
   - `20260825143000_disambiguate_envelope_capacity_release.sql`
   - `20260827040000_allow_bound_auth_identity_creation.sql`
   - `20260827042500_make_bootstrap_deletes_safeupdate_compatible.sql`
2. Deploy `redeem-invite`, `sign-in`, `register-device`, `issue-invite`,
   `redeem-room-invite`, and `purge-expired-data` with the JWT settings in
   `config.toml`.
3. Apply the Auth settings in `config.toml`: public email, SMS, and anonymous
   signup remain disabled; the before-user-created and custom-access-token
   Postgres hooks remain enabled. Verify hosted Auth settings explicitly when
   the deployment mechanism does not push `config.toml`.
4. Create the one-time bootstrap capability as described below.
5. Complete every post-deploy receipt before distributing an APK.

Every Edge Function must be redeployed after the sixth migration because all
six import the shared runtime-secret reader. `issue-invite` also has a changed
request and database RPC contract.

The before-user-created Auth hook is the authority-marker guard. The
database-side `auth.users` trigger independently requires every insert and
identity-changing update to bind the caller-selected Auth UUID to the same
generated internal address, with no phone number and no anonymous identity.
That UUID binding remains valid while Auth writes role, confirmation state, and
admin-supplied `app_metadata` in separate updates. Registration redemption and
the custom-access-token hook both require the persisted server-only marker. A
client signup request can neither choose the Auth UUID nor set `app_metadata`;
disabled public signup adds another fail-closed layer.

## Runtime secrets

Do not create custom Edge secrets. Migrations generate four independent
32-byte values in `private.runtime_configuration`:

- username HMAC pepper
- rate-limit HMAC pepper
- invitation derivation key
- purge capability

No raw value appears in source, migration arguments, seed data, or operator
logs. Edge Functions use only Supabase-provided runtime variables:

- `SUPABASE_URL`
- `SUPABASE_SECRET_KEYS` with its `default` secret key, or the legacy
  `SUPABASE_SERVICE_ROLE_KEY`

On its first request, an Edge Function calls the service-role-only
`public._edge_initialize_runtime_configuration` RPC. That transaction binds
the generated configuration to the exact hosted project URL. A later URL
change is rejected. Do not call the RPC manually or print its result: it
returns capabilities intended only for Edge runtime memory.

The Storage purge cron remains fail closed until the project URL is bound. Its
job derives the same database-owned purge capability. A caller cannot choose a
lease, batch, object path, or purge capability.

## Two-phase account and device binding

Registration and sign-in accept a client-generated durable transport device
UUID but no Signal bundle. After password authentication, Edge reserves the
lowest collision-free Signal device ID in `1..127` for that user, Auth session,
and transport UUID. The phase-one response contains the Auth session, user UUID,
transport UUID, allocated Signal device ID, and reservation expiry.

The client then initializes durable Signal state with the returned user UUID
and Signal device ID and calls `register-device` with the matching public
bundle. The database consumes the unexpired reservation, persists or safely
re-publishes the bundle and offered one-time prekey, and binds that Auth session
to the device. Its receipt contains `user_id`, `device_id`,
`signal_device_id`, `display_name`, and `bound_at`. Until this receipt exists,
`private.current_device_id()` is null and all app-data RLS remains closed.

Reservations expire after 15 minutes. Retry with the same session and transport
UUID is idempotent. A new Auth session may reserve an already registered active
device; a revoked device can never reserve or rebind. Explicit revocation
removes all bound sessions and outstanding reservations for that device.

## First account bootstrap

Generate a random 32-byte URL-safe invite in a trusted local tool. Keep its raw
43-character code only long enough to deliver it to the first user. Compute
SHA-256 locally, then call `private.configure_bootstrap_capability` as database
owner with only the digest and an expiry from 60 seconds through 24 hours.

Never place the raw invite in SQL, shell arguments, CI output, screenshots,
issue trackers, or logs. The capability is single-use, and bootstrap closes
permanently after the first profile is created. `seed.sql` creates no account
and contains no bootstrap secret.

## Encrypted chat data contract

The database stores ciphertext envelopes and routing metadata only. It has no
room-title, message-body, reaction-value, shared-key, or plaintext content
column.

An authenticated sender first enumerates recipients through one of these
active-device-only RPCs:

- `public.list_current_account_recipient_devices()` for atomic room creation
- `public.list_room_recipient_devices(p_room_id uuid)` for an existing room

Both return `device_id`, `user_id`, `protocol_adapter_version`, and
`signal_device_id`. They include the caller's current device and fail when the
complete active-device set would exceed 129 devices. A mutation must supply
exactly one envelope for every returned device: the current sender device uses
`LOCAL_AEAD`; the other devices use `PREKEY` or `WHISPER`. There can be at most
128 peer envelopes plus the one self envelope.

Room creation is atomic and idempotent:

```text
public.create_room_with_metadata(
  p_room_id uuid,
  p_room_kind text,
  p_client_mutation_id uuid,
  p_envelopes jsonb,
  p_retention_seconds integer
)
-> room_id, client_mutation_id, room_kind, retention_seconds,
   membership_epoch, metadata_revision, created_at, metadata_updated_at
```

Its request digest and receipt expire after 24 hours and are physically purged;
the created room and ciphertext remain governed by their normal lifecycle.

The client chooses the room UUID before encryption. The creation ciphertext is
authenticated to actor, room UUID, mutation ID, room kind, retention, and its
encrypted title payload. The server inserts that exact UUID and echoes the
complete request context before the client clears its durable outbox.
Subsequent metadata revisions bind the same room UUID and expected revision:

```text
public.set_room_metadata(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer,
  p_envelopes jsonb
)
-> room_id, metadata_revision, updated_at
```

Room metadata is current-only. A newly joined member or newly registered device
has no title envelope until an existing authorized device publishes the next
complete metadata revision. Android must show a non-sensitive placeholder in
that interval; it must not infer, fetch, or persist a plaintext fallback.

Message edits replace ciphertext rather than preserve history:

```text
public.edit_message(
  p_message_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer,
  p_envelopes jsonb
)
-> message_id, revision_id, revision_number, edited_at, expires_at
```

The transaction physically removes the original/prior envelope rows before
committing the current revision. Only bounded, non-content mutation receipts
remain. RLS exposes only the current revision and denies expired or deleted
content immediately. Message access also requires that the current device has
an envelope for the initial or current revision and that the message was created
after the user's current room membership began. A newly joined member or newly
registered device therefore cannot observe undecryptable history, replies,
reactions, revisions, receipts, or attachments. Reply and reaction mutations
apply the same access predicate, so guessing a pre-join message UUID cannot
create a broken or privacy-leaking graph edge. Android may keep a device-local
encrypted ephemeral cache so an already-consumed Signal envelope survives
process restart; it must purge that cache when the server row is absent,
deleted, or expired.

Private transactional ledgers bound each recipient device to 750 envelopes and
768 KiB globally, with 250-envelope and 256 KiB sub-limits per room and sending
account. The sender partition prevents one invited account from exhausting a
recipient across otherwise unrelated rooms. Ledger rows and immutable envelope
contributions are private, and any quota rejection rolls back the complete
content mutation atomically.

Reaction removal is idempotent:

```text
public.remove_reaction(p_reaction_id uuid, p_client_mutation_id uuid)
-> reaction_id, removed_at
```

Its non-content receipt remains replayable for a full 24 hours even when the
removed reaction would otherwise have expired sooner.

Room preferences update atomically for the current user:

```text
public.set_room_preferences(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_archive_state text,
  p_pin_state text,
  p_mute_state text,
  p_muted_until timestamptz
)
-> room_id, archive_state, pin_state, mute_state, muted_until, updated_at
```

Valid states are `ACTIVE|ARCHIVED`, `UNPINNED|PINNED`, and
`UNMUTED|MUTED_FOREVER|MUTED_UNTIL`. A timed mute requires a future
`p_muted_until`; other states require it to be null.

## Retry-safe core mutations

Each contract below requires a non-null client-generated mutation UUID. An
exact retry within the 24-hour receipt window returns the original receipt.
Reusing the UUID with changed input raises a conflict. Receipts contain no
content and are purged in bounded retention batches.

```text
public.update_room_retention(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_retention_seconds integer
)
-> room_id, retention_seconds, updated_at

public.update_room_member_role(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_member_user_id uuid,
  p_member_role text
)
-> room_id, member_user_id, member_role, new_membership_epoch

public.remove_room_member(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_removed_user_id uuid
)
-> room_id, removed_user_id, new_membership_epoch

public.delete_message_for_everyone(
  p_message_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer
)
-> message_id, deleted_revision, correlation_id, deletion_state, requested_at
```

`deletion_state` is `DELETED` when relational deletion completed or
`PURGE_PENDING` when encrypted attachment deletion is leased to Storage purge.
The mutation locks the message and compares `p_expected_revision` before any
deletion. A stale revision raises a serialization conflict; the confirmed
revision is returned as `deleted_revision`. Retry works even after the message
row has been physically removed.

Invite issuance is an authenticated Edge endpoint, not a client-callable SQL
function. Exact request shapes are:

```json
{"kind":"ACCOUNT_REGISTRATION","client_mutation_id":"uuid","expires_in_seconds":300}
```

```json
{"kind":"ROOM_MEMBERSHIP","client_mutation_id":"uuid","room_id":"uuid","expires_in_seconds":300}
```

`expires_in_seconds` is optional and defaults to 86400; otherwise it must be
`60..86400`. A successful response is:

```json
{
  "invite": {
    "id": "uuid",
    "kind": "ACCOUNT_REGISTRATION|ROOM_MEMBERSHIP",
    "room_id": "uuid|null",
    "code": "43-character-base64url",
    "expires_at": "timestamptz"
  }
}
```

Edge derives the raw code with a domain-separated HMAC of actor, kind, room (or
`NO_ROOM`), and client mutation UUID. SQL verifies its SHA-256 digest before
creating the invite. Neither SQL nor Edge persistence stores the raw code. An
exact retry reproduces the same code and receipt without creating another live
credential; changed expiry, kind, or room conflicts.

## Retention and hard deletion

Message expiry is server-derived from room policy. Expired messages become
unreadable through RLS immediately, before physical purge. Typing and presence
rows are opt-in and server-expire after 15 and 60 seconds. Turning an opt-in off
makes existing state unreadable immediately.

Three cron jobs are installed:

- `synapse-private-relational-purge` runs each minute.
- `synapse-private-storage-purge` runs each minute.
- `synapse-private-cron-history-prune` removes old `pg_cron` history and purge
  receipts daily. `pg_net` independently expires response rows after its
  configured TTL (six hours by default on hosted Supabase).

Relational purge deletes overdue content, transient capabilities, and bounded
mutation receipts in `SKIP LOCKED` batches and records exact counts. Attachment
messages use a five-minute database lease. Edge receives only canonical
database-owned object paths, removes them through Storage in batches, and may
finalize relational deletion only with the active lease ID. A failed Storage
deletion leaves the message unreadable and retryable. An already-absent object
is an idempotent success.

Delete-for-everyone immediately deletes a message without attachments. A
message with attachments becomes unreadable immediately and enters the leased
Storage purge path. There is no trash table, content history table,
caller-supplied deletion path, or delayed recovery copy.

If a Free-plan project pauses, expiry-aware RLS denies overdue rows as soon as
the database resumes. The next cron run discovers every overdue row without a
backfill cursor.

## Realtime and Data API contract

No content-bearing table is added to `supabase_realtime`. Postgres Changes can
emit DELETE events without applying row filters, so Android uses authenticated,
expiry-aware polling for ciphertext. A future private Broadcast channel
requires a separate authorization review.

Migrations never alter the locked `realtime` schema. They explicitly revoke and
grant every public table/function privilege rather than relying on automatic
Data API exposure. `public` remains in `config.toml` API schemas; only explicitly
granted objects are reachable. Extension declarations intentionally contain no
version clause because hosted Supabase ignores/deprecates extension pinning in
migrations.

## Required post-deploy receipts

Record results, timestamps, and project reference without tokens, invite codes,
peppers, derivation keys, purge capabilities, passwords, internal email, or
ciphertext.

- Migration history contains all nine versions in order.
- Direct client, email, SMS, and anonymous signup fail.
- Unauthorized insert and identity-changing update on `auth.users` fail through
  the database trigger.
- Before-user-created and custom-access-token hooks are enabled.
- All six Edge Functions are redeployed with `verify_jwt` from `config.toml` and
  no custom secret.
- Phase-one registration returns a reservation but cannot read profiles, rooms,
  or messages before `register-device` succeeds.
- Two devices on one account receive distinct Signal IDs; retry, expiry,
  prekey-republication, and explicit revocation behave as specified.
- Atomic room creation leaves no metadata-revision-zero orphan on failed
  ciphertext fan-out.
- Sender self envelopes survive polling/restart without plaintext persistence;
  revoked sender crypto context is readable only while accessible unexpired
  ciphertext references it.
- Metadata/edit RLS exposes only current ciphertext, and a confirmed edit leaves
  no earlier ciphertext row.
- Exact mutation retries return identical receipts; changed-input retries fail;
  lost invite responses create no second live credential.
- Every public Synapse Private table has RLS, `anon` has no grants, and
  service-only RPCs are not executable by `authenticated`.
- No Synapse Private table appears in `supabase_realtime`; no migration alters
  the `realtime` schema.
- The `encrypted-attachments` bucket is private and accepts only
  `application/octet-stream` up to 20 MiB.
- All three cron jobs are active with recent successful runs. Investigate failed
  `cron.job_run_details` or `net._http_response` rows before release.
- Purge health reports every field true without exposing the purge capability.
- An expired attachment canary disappears from Storage and relational tables;
  retry returns a valid idempotent receipt.
- Hosted pgTAP behavior/security suites pass.
- Supabase security and performance advisors have no unresolved finding for
  this slice, or each exception is narrowly documented before release.
