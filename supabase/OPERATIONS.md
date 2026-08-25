# Synapse Private backend operations

This backend is for a new, dedicated Supabase project. The first migration
fails if `auth.users` already contains any rows so an unrelated or legacy Auth
identity cannot become an implicit Synapse Private account.

## Deployment order

1. Apply every file in `migrations/` in timestamp order.
2. Deploy `redeem-invite`, `sign-in`, `register-device`, `issue-invite`,
   `redeem-room-invite`, and `purge-expired-data` with the JWT settings in
   `config.toml`.
3. Apply the Auth settings in `config.toml`: public, email, SMS, and anonymous
   signup remain disabled; the before-user-created and custom-access-token
   Postgres hooks remain enabled. Verify these hosted settings explicitly when
   the deployment mechanism does not push `config.toml`.
4. Create the one-time bootstrap capability as described below.
5. Complete every post-deploy receipt before distributing an APK.

The database-side `auth.users` identity trigger is the authoritative direct
signup guard. It rejects inserts and identity-changing updates unless the
identity uses a generated internal address, has no phone number, is
non-anonymous, and carries the server-only registration marker in
`app_metadata`. A client signup request cannot set `app_metadata`. The official
Auth hooks and disabled-signup settings are additional fail-closed layers.

## Runtime secrets

Do not create custom Edge secrets for this backend. The first migration
generates independent 32-byte username, rate-limit, and purge capabilities in
`private.runtime_configuration`. No raw value appears in source, migration
arguments, seed data, or operator logs.

Edge Functions use only Supabase-provided runtime variables:

- `SUPABASE_URL`
- `SUPABASE_SECRET_KEYS` with its `default` secret key, or the legacy
  `SUPABASE_SERVICE_ROLE_KEY`

On its first request, an Edge Function calls the service-role-only
`public._edge_initialize_runtime_configuration` RPC. That transaction binds
the generated configuration to the exact hosted project URL once. A later URL
change is rejected. Do not call this RPC manually or print its result: it
returns capabilities intended only for the Edge runtime.

The Storage purge cron job remains fail closed until the project URL is bound.
It derives the same database-owned purge capability inside its job command;
the caller cannot choose a lease, batch, object path, or purge capability.

## Two-phase account and device binding

Registration and normal sign-in accept a client-generated durable transport
device UUID but no Signal bundle. After password authentication, the Edge
Function reserves the lowest collision-free Signal device ID in `1..127` for
that user, Auth session, and transport UUID. The response contains the Auth
session, user UUID, transport UUID, allocated Signal ID, and reservation
expiry.

The client then initializes its durable Signal state with the returned user
UUID and Signal ID and calls `register-device` with the matching public bundle.
The database consumes the unexpired reservation, persists the bundle, and
binds that exact Auth session to the device. Until this succeeds,
`private.current_device_id()` is null and every app-data RLS path stays closed.

Reservations expire after 15 minutes. Retry with the same session and transport
UUID is idempotent. A new Auth session may reserve an already registered active
device, while a revoked device can never reserve or rebind. Explicit revocation
removes all bound sessions and outstanding reservations for that device.

## First account bootstrap

Generate a cryptographically random 32-byte URL-safe invite in a trusted local
tool. Keep the raw 43-character code only long enough to deliver it to the
first user. Compute SHA-256 locally, then call
`private.configure_bootstrap_capability` as the database owner with only the
32-byte digest and an expiry from 60 seconds through 24 hours.

Never place the raw invite in SQL, shell arguments, CI output, screenshots,
issue trackers, or logs. The capability is single-use, and bootstrap closes
permanently after the first profile is created. `seed.sql` deliberately creates
no account and contains no bootstrap secret.

## Retention and hard deletion

Message expiry is server-derived from the room policy. Expired messages become
unreadable through RLS immediately, even before physical purge. Typing and
presence rows are opt-in and server-expire after 15 and 60 seconds. Turning an
opt-in off makes the existing state unreadable immediately.

Three cron jobs are installed:

- `synapse-private-relational-purge` runs each minute.
- `synapse-private-storage-purge` runs each minute.
- `synapse-private-cron-history-prune` removes old `pg_cron` history and purge
  receipts daily. `pg_net` independently expires response rows after its
  configured TTL (six hours by default on hosted Supabase).

Relational purge deletes overdue content and transient capabilities in bounded
`SKIP LOCKED` batches and records exact counts. Attachment messages use a
five-minute database lease. The Edge Function receives only database-owned
canonical object paths, removes them through the Storage API in batches, and
can finalize relational deletion only with the active lease ID. A failed
Storage deletion leaves the message unreadable and retryable; already-absent
objects are treated idempotently by the Storage API path.

Delete-for-everyone immediately deletes a message without attachments. For a
message with attachments, it immediately makes the message unreadable and
queues the same leased Storage purge path. There is no trash table, content
history table, caller-supplied deletion path, or delayed recovery copy.

If a Free-plan project pauses, expiry-aware RLS denies overdue rows as soon as
the database resumes. The next cron run discovers every overdue row without a
backfill cursor.

## Realtime and Data API contract

No content-bearing table is added to `supabase_realtime`. Postgres Changes can
emit DELETE events without applying row filters, so Android uses authenticated,
expiry-aware polling for ciphertext. A future private Broadcast channel needs
a separate authorization review.

The migrations do not alter the locked `realtime` schema. They explicitly
revoke and grant every public table/function privilege rather than relying on
automatic Data API exposure. `public` remains in the configured API schemas so
only those explicit grants are reachable.

## Required post-deploy receipts

Record results, timestamps, and project reference without recording tokens,
invite codes, peppers, purge capabilities, passwords, internal account email,
or ciphertext.

- Migration history contains all three migration versions in order.
- Direct client signup, email signup, SMS signup, and anonymous signup fail.
- An unauthorized direct insert and identity-changing update on `auth.users`
  fail through the database trigger.
- The before-user-created and custom-access-token hooks are enabled.
- All six Edge Functions are deployed with the `verify_jwt` values in
  `config.toml`; no custom secret is required.
- Phase-one registration returns a reservation but cannot read profiles, rooms,
  or messages before `register-device` succeeds.
- Two devices on one account receive distinct Signal IDs; retry, expiry,
  prekey-republication, and explicit revocation behave as specified.
- Every public Synapse Private table has RLS enabled, `anon` has no grants, and
  service-only RPCs are not executable by `authenticated`.
- No Synapse Private table appears in `supabase_realtime`; no migration alters
  the `realtime` schema.
- The `encrypted-attachments` bucket is private and accepts only
  `application/octet-stream` up to 20 MiB.
- All three cron jobs are active and have recent successful runs. Failed
  `cron.job_run_details` or `net._http_response` rows are investigated before
  release.
- The purge Edge health response reports every field true. Do not obtain that
  receipt by exposing or printing the underlying purge capability.
- An expired attachment canary is absent from both Storage and relational
  tables after the leased purge; retry produces a valid idempotent receipt.
- Database pgTAP behavior/security suites pass in the deployed project.
- Supabase security and performance advisors have no unresolved finding for
  this slice, or each exception is narrowly documented before release.
