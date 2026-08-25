create extension if not exists pgcrypto with schema extensions;

create schema if not exists private;

revoke all on schema private from public, anon, authenticated;
grant usage on schema private to authenticated, service_role;

alter default privileges in schema private revoke execute on functions from public, anon, authenticated;
alter default privileges in schema public revoke execute on functions from public, anon, authenticated;

create table public.profiles (
  user_id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null,
  presence_sharing_enabled boolean not null default false,
  typing_indicators_enabled boolean not null default false,
  read_receipts_enabled boolean not null default false,
  created_at timestamptz not null default statement_timestamp(),
  constraint profiles_display_name_valid check (
    display_name = btrim(display_name)
    and char_length(display_name) between 1 and 64
    and display_name !~ '[[:cntrl:]]'
  )
);

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (user_id) on delete cascade,
  protocol_adapter_version smallint not null,
  registration_id integer not null,
  signal_device_id smallint not null,
  identity_key bytea not null,
  signed_pre_key_id integer not null,
  signed_pre_key_public bytea not null,
  signed_pre_key_signature bytea not null,
  kyber_pre_key_id integer not null,
  kyber_pre_key_public bytea not null,
  kyber_pre_key_signature bytea not null,
  created_at timestamptz not null default statement_timestamp(),
  revoked_at timestamptz,
  constraint devices_protocol_adapter_version_valid check (protocol_adapter_version = 1),
  constraint devices_registration_id_valid check (registration_id between 1 and 16380),
  constraint devices_signal_device_id_valid check (signal_device_id between 1 and 127),
  constraint devices_identity_key_wire_format check (octet_length(identity_key) = 33 and get_byte(identity_key, 0) = 5),
  constraint devices_signed_pre_key_id_valid check (signed_pre_key_id between 0 and 16777215),
  constraint devices_signed_pre_key_wire_format check (octet_length(signed_pre_key_public) = 33 and get_byte(signed_pre_key_public, 0) = 5),
  constraint devices_signed_pre_key_signature_wire_format check (octet_length(signed_pre_key_signature) = 64),
  constraint devices_kyber_pre_key_id_valid check (kyber_pre_key_id between 0 and 16777215),
  constraint devices_kyber_pre_key_wire_format check (octet_length(kyber_pre_key_public) = 1569 and get_byte(kyber_pre_key_public, 0) = 8),
  constraint devices_kyber_pre_key_signature_wire_format check (octet_length(kyber_pre_key_signature) = 64),
  constraint devices_revocation_time_valid check (revoked_at is null or revoked_at >= created_at),
  constraint devices_user_signal_device_unique unique (user_id, signal_device_id)
);

create table public.device_one_time_prekeys (
  device_id uuid not null references public.devices (id) on delete cascade,
  pre_key_id integer not null,
  public_key bytea not null,
  created_at timestamptz not null default statement_timestamp(),
  primary key (device_id, pre_key_id),
  constraint device_one_time_prekeys_id_valid check (pre_key_id between 0 and 16777215),
  constraint device_one_time_prekeys_public_key_wire_format check (octet_length(public_key) = 33 and get_byte(public_key, 0) = 5)
);

create table private.device_sessions (
  session_id uuid primary key,
  device_id uuid not null references public.devices (id) on delete cascade,
  bound_at timestamptz not null default statement_timestamp(),
  constraint device_sessions_device_session_unique unique (device_id, session_id)
);

create table private.device_registration_reservations (
  auth_session_id uuid primary key references auth.sessions (id) on delete cascade,
  user_id uuid not null references public.profiles (user_id) on delete cascade,
  device_id uuid not null,
  signal_device_id smallint not null,
  reserved_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null default (statement_timestamp() + interval '15 minutes'),
  constraint device_registration_reservations_signal_id_valid check (signal_device_id between 1 and 127),
  constraint device_registration_reservations_expiry_valid check (
    expires_at > reserved_at and expires_at <= reserved_at + interval '15 minutes'
  )
);

create table private.runtime_configuration (
  singleton boolean primary key default true,
  project_url text,
  username_hmac_pepper bytea not null,
  rate_limit_hmac_pepper bytea not null,
  purge_capability bytea not null,
  created_at timestamptz not null default statement_timestamp(),
  configured_at timestamptz,
  constraint runtime_configuration_singleton check (singleton),
  constraint runtime_configuration_project_url_valid check (
    project_url is null
    or project_url ~ '^https://[a-z0-9]{20}[.]supabase[.]co$'
    or project_url ~ '^http://(127[.]0[.]0[.]1|localhost|kong)(:[0-9]{1,5})?$'
  ),
  constraint runtime_configuration_username_pepper_length check (octet_length(username_hmac_pepper) = 32),
  constraint runtime_configuration_rate_limit_pepper_length check (octet_length(rate_limit_hmac_pepper) = 32),
  constraint runtime_configuration_purge_capability_length check (octet_length(purge_capability) = 32),
  constraint runtime_configuration_configured_time_complete check (
    (project_url is null) = (configured_at is null)
  )
);

insert into private.runtime_configuration (
  username_hmac_pepper,
  rate_limit_hmac_pepper,
  purge_capability
) values (
  extensions.gen_random_bytes(32),
  extensions.gen_random_bytes(32),
  extensions.gen_random_bytes(32)
);

create table public.rooms (
  id uuid primary key default gen_random_uuid(),
  owner_user_id uuid not null references public.profiles (user_id) on delete restrict,
  room_kind text not null,
  retention_seconds integer not null default 86400,
  membership_epoch integer not null default 1,
  created_at timestamptz not null default statement_timestamp(),
  constraint rooms_kind_valid check (room_kind in ('DIRECT', 'GROUP')),
  constraint rooms_retention_supported check (retention_seconds in (300, 3600, 86400, 604800)),
  constraint rooms_membership_epoch_valid check (membership_epoch between 1 and 2147483647)
);

create table public.room_members (
  room_id uuid not null references public.rooms (id) on delete cascade,
  user_id uuid not null references public.profiles (user_id) on delete restrict,
  member_role text not null,
  joined_at timestamptz not null default statement_timestamp(),
  primary key (room_id, user_id),
  constraint room_members_role_valid check (member_role in ('OWNER', 'ADMIN', 'MEMBER'))
);

create table public.messages (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.rooms (id) on delete cascade,
  sender_user_id uuid not null references public.profiles (user_id) on delete cascade,
  sender_device_id uuid references public.devices (id) on delete set null,
  client_message_id uuid not null,
  membership_epoch integer not null,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  constraint messages_membership_epoch_valid check (membership_epoch between 1 and 2147483647),
  constraint messages_expiry_valid check (expires_at > created_at),
  constraint messages_sender_client_id_unique unique (sender_user_id, client_message_id)
);

create table public.message_envelopes (
  message_id uuid not null references public.messages (id) on delete cascade,
  recipient_device_id uuid not null references public.devices (id) on delete cascade,
  protocol_adapter_version smallint not null,
  signal_message_type text not null,
  ciphertext bytea not null,
  created_at timestamptz not null default statement_timestamp(),
  primary key (message_id, recipient_device_id),
  constraint message_envelopes_protocol_version_valid check (protocol_adapter_version = 1),
  constraint message_envelopes_type_valid check (signal_message_type in ('PREKEY', 'WHISPER')),
  constraint message_envelopes_ciphertext_bounded check (octet_length(ciphertext) between 1 and 262144)
);

create table public.message_reply_links (
  message_id uuid primary key references public.messages (id) on delete cascade,
  replied_to_message_id uuid not null references public.messages (id) on delete cascade,
  constraint message_reply_links_not_self check (message_id <> replied_to_message_id)
);

create table public.reactions (
  id uuid primary key default gen_random_uuid(),
  message_id uuid not null references public.messages (id) on delete cascade,
  sender_user_id uuid not null references public.profiles (user_id) on delete cascade,
  sender_device_id uuid references public.devices (id) on delete set null,
  client_reaction_id uuid not null,
  membership_epoch integer not null,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  constraint reactions_membership_epoch_valid check (membership_epoch between 1 and 2147483647),
  constraint reactions_expiry_valid check (expires_at > created_at),
  constraint reactions_sender_client_id_unique unique (sender_user_id, client_reaction_id)
);

create table public.reaction_envelopes (
  reaction_id uuid not null references public.reactions (id) on delete cascade,
  recipient_device_id uuid not null references public.devices (id) on delete cascade,
  protocol_adapter_version smallint not null,
  signal_message_type text not null,
  ciphertext bytea not null,
  created_at timestamptz not null default statement_timestamp(),
  primary key (reaction_id, recipient_device_id),
  constraint reaction_envelopes_protocol_version_valid check (protocol_adapter_version = 1),
  constraint reaction_envelopes_type_valid check (signal_message_type in ('PREKEY', 'WHISPER')),
  constraint reaction_envelopes_ciphertext_bounded check (octet_length(ciphertext) between 1 and 16384)
);

create table public.message_receipts (
  message_id uuid not null references public.messages (id) on delete cascade,
  recipient_device_id uuid not null references public.devices (id) on delete cascade,
  receipt_kind text not null,
  membership_epoch integer not null,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  primary key (message_id, recipient_device_id, receipt_kind),
  constraint message_receipts_membership_epoch_valid check (membership_epoch between 1 and 2147483647),
  constraint message_receipts_kind_valid check (receipt_kind in ('DELIVERED', 'READ')),
  constraint message_receipts_expiry_valid check (expires_at > created_at)
);

create table public.typing_state (
  room_id uuid not null references public.rooms (id) on delete cascade,
  device_id uuid not null references public.devices (id) on delete cascade,
  membership_epoch integer not null,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  primary key (room_id, device_id),
  constraint typing_state_membership_epoch_valid check (membership_epoch between 1 and 2147483647),
  constraint typing_state_expiry_valid check (expires_at > created_at)
);

create table public.presence_state (
  device_id uuid primary key references public.devices (id) on delete cascade,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  constraint presence_state_expiry_valid check (expires_at > created_at)
);

create table public.attachments (
  id uuid primary key default gen_random_uuid(),
  message_id uuid not null references public.messages (id) on delete cascade,
  uploader_device_id uuid references public.devices (id) on delete set null,
  encrypted_header bytea not null,
  ciphertext_digest bytea not null,
  ciphertext_byte_count integer not null,
  object_path text not null unique,
  encrypted_thumbnail_header bytea,
  thumbnail_ciphertext_digest bytea,
  thumbnail_ciphertext_byte_count integer,
  thumbnail_object_path text unique,
  created_at timestamptz not null default statement_timestamp(),
  constraint attachments_header_bounded check (octet_length(encrypted_header) between 1 and 4096),
  constraint attachments_digest_sha256 check (octet_length(ciphertext_digest) = 32),
  constraint attachments_size_bounded check (ciphertext_byte_count between 1 and 20971520),
  constraint attachments_thumbnail_header_bounded check (encrypted_thumbnail_header is null or octet_length(encrypted_thumbnail_header) between 1 and 4096),
  constraint attachments_thumbnail_digest_sha256 check (thumbnail_ciphertext_digest is null or octet_length(thumbnail_ciphertext_digest) = 32),
  constraint attachments_thumbnail_size_bounded check (thumbnail_ciphertext_byte_count is null or thumbnail_ciphertext_byte_count between 1 and 1048576),
  constraint attachments_thumbnail_complete check (
    (encrypted_thumbnail_header is null)
    = (thumbnail_ciphertext_digest is null)
    and (encrypted_thumbnail_header is null)
    = (thumbnail_ciphertext_byte_count is null)
    and (encrypted_thumbnail_header is null)
    = (thumbnail_object_path is null)
  )
);

create table private.account_credentials (
  user_id uuid primary key references public.profiles (user_id) on delete cascade,
  username_digest bytea not null unique,
  internal_email text not null unique,
  created_at timestamptz not null default statement_timestamp(),
  constraint account_credentials_username_digest_sha256 check (octet_length(username_digest) = 32),
  constraint account_credentials_internal_email_valid check (
    internal_email = lower(internal_email)
    and internal_email ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@identity[.]synapse-private[.]invalid$'
  )
);

create table private.account_registration_invites (
  id uuid primary key default gen_random_uuid(),
  issued_by_user_id uuid not null references public.profiles (user_id) on delete cascade,
  code_digest bytea not null unique,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  constraint account_registration_invites_code_digest_sha256 check (octet_length(code_digest) = 32),
  constraint account_registration_invites_expiry_bounded check (
    expires_at > created_at and expires_at <= created_at + interval '24 hours'
  )
);

create table private.bootstrap_capabilities (
  singleton boolean primary key default true,
  code_digest bytea not null unique,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  constraint bootstrap_capabilities_singleton check (singleton),
  constraint bootstrap_capabilities_code_digest_sha256 check (octet_length(code_digest) = 32),
  constraint bootstrap_capabilities_expiry_bounded check (
    expires_at > created_at and expires_at <= created_at + interval '24 hours'
  )
);

create table private.account_registration_receipts (
  redemption_id uuid primary key,
  code_digest bytea not null unique,
  registration_kind text not null,
  user_id uuid not null references public.profiles (user_id) on delete cascade,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  constraint account_registration_receipts_code_digest_sha256 check (octet_length(code_digest) = 32),
  constraint account_registration_receipts_kind_valid check (registration_kind in ('BOOTSTRAP', 'ACCOUNT_REGISTRATION')),
  constraint account_registration_receipts_expiry_valid check (expires_at > completed_at)
);

create table private.room_membership_invites (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.rooms (id) on delete cascade,
  issued_by_user_id uuid not null references public.profiles (user_id) on delete cascade,
  code_digest bytea not null unique,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  constraint room_membership_invites_code_digest_sha256 check (octet_length(code_digest) = 32),
  constraint room_membership_invites_expiry_bounded check (
    expires_at > created_at and expires_at <= created_at + interval '24 hours'
  )
);

create table private.room_membership_invite_receipts (
  redemption_id uuid primary key,
  code_digest bytea not null unique,
  room_id uuid not null references public.rooms (id) on delete cascade,
  user_id uuid not null references public.profiles (user_id) on delete cascade,
  membership_epoch integer not null,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  constraint room_membership_invite_receipts_code_digest_sha256 check (octet_length(code_digest) = 32),
  constraint room_membership_invite_receipts_epoch_valid check (membership_epoch between 1 and 2147483647),
  constraint room_membership_invite_receipts_expiry_valid check (expires_at > completed_at)
);

create table private.account_access_rate_limits (
  source_digest bytea not null,
  operation text not null,
  window_started_at timestamptz not null,
  attempt_count integer not null,
  expires_at timestamptz not null,
  primary key (source_digest, operation, window_started_at),
  constraint account_access_rate_limits_source_digest_sha256 check (octet_length(source_digest) = 32),
  constraint account_access_rate_limits_operation_valid check (operation in ('REGISTER', 'SIGN_IN', 'ROOM_REDEEM')),
  constraint account_access_rate_limits_attempt_count_valid check (attempt_count between 1 and 10),
  constraint account_access_rate_limits_expiry_valid check (expires_at > window_started_at)
);

create table private.purge_receipts (
  correlation_id uuid primary key,
  started_at timestamptz not null,
  completed_at timestamptz not null,
  messages_deleted integer not null,
  attachment_objects_deleted integer not null,
  typing_rows_deleted integer not null,
  presence_rows_deleted integer not null,
  invites_deleted integer not null,
  device_reservations_deleted integer not null,
  constraint purge_receipts_time_valid check (completed_at >= started_at),
  constraint purge_receipts_counts_valid check (
    messages_deleted >= 0
    and attachment_objects_deleted >= 0
    and typing_rows_deleted >= 0
    and presence_rows_deleted >= 0
    and invites_deleted >= 0
    and device_reservations_deleted >= 0
  )
);

create table private.attachment_purge_leases (
  message_id uuid primary key references public.messages (id) on delete cascade,
  lease_id uuid not null,
  leased_at timestamptz not null,
  lease_expires_at timestamptz not null,
  constraint attachment_purge_leases_expiry_valid check (lease_expires_at > leased_at)
);

create table private.message_deletion_requests (
  message_id uuid primary key references public.messages (id) on delete cascade,
  correlation_id uuid not null unique default gen_random_uuid(),
  requested_by_user_id uuid not null references public.profiles (user_id) on delete restrict,
  requested_at timestamptz not null default statement_timestamp()
);

create index devices_user_id_idx on public.devices (user_id);
create index devices_active_user_idx on public.devices (user_id, id) where revoked_at is null;
create index device_sessions_device_id_idx on private.device_sessions (device_id);
create index device_registration_reservations_user_device_idx
on private.device_registration_reservations (user_id, device_id, expires_at);
create index device_registration_reservations_expiry_idx
on private.device_registration_reservations (expires_at, auth_session_id);
create index room_members_user_room_idx on public.room_members (user_id, room_id);
create unique index room_members_single_owner_idx on public.room_members (room_id) where member_role = 'OWNER';
create index messages_room_expiry_idx on public.messages (room_id, expires_at, id);
create index messages_expiry_idx on public.messages (expires_at, id);
create index message_envelopes_recipient_idx on public.message_envelopes (recipient_device_id, message_id);
create index reactions_message_expiry_idx on public.reactions (message_id, expires_at);
create index reaction_envelopes_recipient_idx on public.reaction_envelopes (recipient_device_id, reaction_id);
create index message_receipts_expiry_idx on public.message_receipts (expires_at, message_id);
create index typing_state_expiry_idx on public.typing_state (expires_at, room_id);
create index presence_state_expiry_idx on public.presence_state (expires_at, device_id);
create index attachments_message_idx on public.attachments (message_id);
create index account_registration_invites_expiry_idx on private.account_registration_invites (expires_at, id);
create index account_registration_receipts_expiry_idx on private.account_registration_receipts (expires_at, redemption_id);
create index room_membership_invites_expiry_idx on private.room_membership_invites (expires_at, id);
create index room_membership_invite_receipts_expiry_idx on private.room_membership_invite_receipts (expires_at, redemption_id);
create index account_access_rate_limits_expiry_idx on private.account_access_rate_limits (expires_at);
create index attachment_purge_leases_lease_idx on private.attachment_purge_leases (lease_id, message_id);
create index attachment_purge_leases_expiry_idx on private.attachment_purge_leases (lease_expires_at);
create index message_deletion_requests_requested_at_idx on private.message_deletion_requests (requested_at, message_id);

create function private.session_belongs_to_user(p_session_id uuid, p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from auth.sessions as auth_session
    join auth.users as auth_user on auth_user.id = auth_session.user_id
    where auth_session.id = p_session_id
      and auth_session.user_id = p_user_id
      and auth_user.is_anonymous is false
  );
$$;

create function private.auth_identity_is_synapse_private_authorized(
  p_email text,
  p_phone text,
  p_is_anonymous boolean,
  p_app_metadata jsonb
)
returns boolean
language sql
immutable
security invoker
set search_path = ''
as $$
  select lower(coalesce(p_email, '')) ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@identity[.]synapse-private[.]invalid$'
    and coalesce(p_phone, '') = ''
    and coalesce(p_is_anonymous, false) is false
    and coalesce(
      p_app_metadata ->> 'synapse_private_registration_authority' = 'true',
      false
    );
$$;

create function private.enforce_synapse_private_auth_user_identity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not private.auth_identity_is_synapse_private_authorized(
    new.email,
    new.phone,
    new.is_anonymous,
    new.raw_app_meta_data
  ) then
    raise exception using errcode = '42501', message = 'Registration is not authorized.';
  end if;
  return new;
end;
$$;

do $$
begin
  if exists (select 1 from auth.users) then
    raise exception using
      errcode = '55000',
      message = 'Synapse Private requires an empty Auth user directory before initial migration';
  end if;
end;
$$;

create trigger enforce_synapse_private_auth_user_identity
before insert or update on auth.users
for each row execute function private.enforce_synapse_private_auth_user_identity();

create function private.authorize_synapse_private_user_creation(event jsonb)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if private.auth_identity_is_synapse_private_authorized(
    event -> 'user' ->> 'email',
    event -> 'user' ->> 'phone',
    coalesce(event -> 'user' ->> 'is_anonymous' = 'true', false),
    event -> 'user' -> 'app_metadata'
  ) then
    return '{}'::jsonb;
  end if;

  return jsonb_build_object(
    'error',
    jsonb_build_object(
      'http_code', 403,
      'message', 'Registration is not authorized.'
    )
  );
end;
$$;

revoke all on function private.auth_identity_is_synapse_private_authorized(text, text, boolean, jsonb)
from public, anon, authenticated;
revoke all on function private.enforce_synapse_private_auth_user_identity()
from public, anon, authenticated;

create function private.authorize_synapse_private_access_token(event jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  requested_user_id uuid;
  claims jsonb := event -> 'claims';
begin
  if jsonb_typeof(claims) <> 'object'
    or coalesce(event ->> 'user_id', '') !~ '^[0-9a-fA-F-]{36}$'
  then
    return jsonb_build_object(
      'error',
      jsonb_build_object('http_code', 403, 'message', 'Account access is not authorized.')
    );
  end if;

  requested_user_id := (event ->> 'user_id')::uuid;
  if exists (
    select 1
    from private.account_credentials as credential
    join auth.users as auth_user on auth_user.id = credential.user_id
    where credential.user_id = requested_user_id
      and claims ->> 'sub' = requested_user_id::text
      and lower(claims ->> 'email') = credential.internal_email
      and coalesce(claims ->> 'phone', '') = ''
      and coalesce(claims ->> 'is_anonymous', 'false') = 'false'
      and coalesce(
        auth_user.raw_app_meta_data ->> 'synapse_private_registration_authority' = 'true',
        false
      )
  ) then
    return jsonb_build_object('claims', claims - 'app_metadata' - 'user_metadata');
  end if;

  return jsonb_build_object(
    'error',
    jsonb_build_object('http_code', 403, 'message', 'Account access is not authorized.')
  );
exception
  when invalid_text_representation then
    return jsonb_build_object(
      'error',
      jsonb_build_object('http_code', 403, 'message', 'Account access is not authorized.')
    );
end;
$$;

create function private.current_device_id()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select device_session.device_id
  from private.device_sessions as device_session
  join public.devices as device on device.id = device_session.device_id
  join auth.sessions as auth_session
    on auth_session.id = device_session.session_id
   and auth_session.user_id = device.user_id
  join auth.users as auth_user on auth_user.id = device.user_id
  where device_session.session_id = nullif(auth.jwt() ->> 'session_id', '')::uuid
    and device.user_id = auth.uid()
    and device.revoked_at is null
    and auth_user.is_anonymous is false
  limit 1;
$$;

create function private.is_active_room_member(p_room_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.current_device_id() is not null
    and exists (
      select 1
      from public.room_members as room_member
      where room_member.room_id = p_room_id
        and room_member.user_id = auth.uid()
    );
$$;

create function private.can_manage_room(p_room_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.current_device_id() is not null
    and exists (
      select 1
      from public.room_members as room_member
      where room_member.room_id = p_room_id
        and room_member.user_id = auth.uid()
        and room_member.member_role in ('OWNER', 'ADMIN')
    );
$$;

create function private.can_access_message(p_message_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.messages as message
    join public.room_members as room_member
      on room_member.room_id = message.room_id
     and room_member.user_id = auth.uid()
    where message.id = p_message_id
      and message.expires_at > statement_timestamp()
      and not exists (
        select 1
        from private.message_deletion_requests as deletion_request
        where deletion_request.message_id = message.id
      )
      and private.current_device_id() is not null
  );
$$;

create function private.shares_room_with(p_other_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.current_device_id() is not null
    and (
      p_other_user_id = auth.uid()
      or exists (
        select 1
        from public.room_members as own_membership
        join public.room_members as other_membership
          on other_membership.room_id = own_membership.room_id
        where own_membership.user_id = auth.uid()
          and other_membership.user_id = p_other_user_id
      )
    );
$$;

create function private.set_message_expiry()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  room_retention_seconds integer;
  room_membership_epoch integer;
begin
  select room.retention_seconds, room.membership_epoch
    into strict room_retention_seconds, room_membership_epoch
  from public.rooms as room
  where room.id = new.room_id;

  new.created_at := statement_timestamp();
  new.expires_at := new.created_at + make_interval(secs => room_retention_seconds);
  new.membership_epoch := room_membership_epoch;
  return new;
end;
$$;

create function private.assert_reply_link_invariant()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  source_room_id uuid;
  target_room_id uuid;
  source_expiry timestamptz;
  target_expiry timestamptz;
begin
  select source_message.room_id, source_message.expires_at
    into strict source_room_id, source_expiry
  from public.messages as source_message
  where source_message.id = new.message_id;

  select target_message.room_id, target_message.expires_at
    into strict target_room_id, target_expiry
  from public.messages as target_message
  where target_message.id = new.replied_to_message_id;

  if source_room_id <> target_room_id
    or source_expiry <= statement_timestamp()
    or target_expiry <= statement_timestamp()
    or exists (
      select 1
      from private.message_deletion_requests as deletion_request
      where deletion_request.message_id in (new.message_id, new.replied_to_message_id)
    )
  then
    raise exception using errcode = '23514', message = 'reply links require unexpired messages in the same room';
  end if;
  return new;
end;
$$;

create function private.set_reaction_expiry()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  parent_expiry timestamptz;
  parent_room_id uuid;
  current_membership_epoch integer;
begin
  select message.expires_at, message.room_id
    into strict parent_expiry, parent_room_id
  from public.messages as message
  where message.id = new.message_id
    and message.expires_at > statement_timestamp()
    and not exists (
      select 1
      from private.message_deletion_requests as deletion_request
      where deletion_request.message_id = message.id
    );

  select room.membership_epoch
    into strict current_membership_epoch
  from public.rooms as room
  where room.id = parent_room_id
  for share;

  new.created_at := statement_timestamp();
  new.expires_at := parent_expiry;
  new.membership_epoch := current_membership_epoch;
  return new;
end;
$$;

create function private.set_receipt_expiry()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  parent_expiry timestamptz;
  parent_room_id uuid;
  current_membership_epoch integer;
begin
  select message.expires_at, message.room_id
    into strict parent_expiry, parent_room_id
  from public.messages as message
  where message.id = new.message_id
    and message.expires_at > statement_timestamp()
    and not exists (
      select 1
      from private.message_deletion_requests as deletion_request
      where deletion_request.message_id = message.id
    );

  select room.membership_epoch
    into strict current_membership_epoch
  from public.rooms as room
  where room.id = parent_room_id
  for share;

  new.created_at := statement_timestamp();
  new.expires_at := parent_expiry;
  new.membership_epoch := current_membership_epoch;
  return new;
end;
$$;

create function private.set_typing_expiry()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_membership_epoch integer;
begin
  select room.membership_epoch
    into strict current_membership_epoch
  from public.rooms as room
  where room.id = new.room_id
  for share;

  if not exists (
    select 1
    from public.devices as device
    join public.profiles as profile on profile.user_id = device.user_id
    where device.id = new.device_id
      and device.revoked_at is null
      and profile.typing_indicators_enabled
  ) then
    raise exception using errcode = '42501', message = 'typing indicators are not enabled';
  end if;

  new.created_at := statement_timestamp();
  new.expires_at := new.created_at + interval '15 seconds';
  new.membership_epoch := current_membership_epoch;
  return new;
end;
$$;

create function private.set_presence_expiry()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not exists (
    select 1
    from public.devices as device
    join public.profiles as profile on profile.user_id = device.user_id
    where device.id = new.device_id
      and device.revoked_at is null
      and profile.presence_sharing_enabled
  ) then
    raise exception using errcode = '42501', message = 'presence sharing is not enabled';
  end if;

  new.created_at := statement_timestamp();
  new.expires_at := new.created_at + interval '60 seconds';
  return new;
end;
$$;

create function private.prepare_attachment_paths()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  attachment_room_id uuid;
  attachment_message_epoch integer;
  current_membership_epoch integer;
  attachment_count integer;
begin
  perform 1
  from public.messages as locked_message
  where locked_message.id = new.message_id
    and locked_message.expires_at > statement_timestamp()
    and not exists (
      select 1
      from private.message_deletion_requests as deletion_request
      where deletion_request.message_id = locked_message.id
    )
  for update;

  select message.room_id, message.membership_epoch
    into strict attachment_room_id, attachment_message_epoch
  from public.messages as message
  where message.id = new.message_id;

  select room.membership_epoch
    into strict current_membership_epoch
  from public.rooms as room
  where room.id = attachment_room_id
  for share;

  if attachment_message_epoch <> current_membership_epoch then
    raise exception using errcode = '23514', message = 'attachments cannot be added after room membership changes';
  end if;

  select count(*)
    into attachment_count
  from public.attachments as attachment
  where attachment.message_id = new.message_id;

  if attachment_count >= 8 then
    raise exception using errcode = 'check_violation', message = 'attachment count exceeds eight per message';
  end if;

  new.created_at := statement_timestamp();
  new.object_path := attachment_room_id::text || '/' || new.message_id::text || '/' || new.id::text || '.ciphertext';
  if new.encrypted_thumbnail_header is not null then
    new.thumbnail_object_path := attachment_room_id::text || '/' || new.message_id::text || '/' || new.id::text || '.thumbnail.ciphertext';
  else
    new.thumbnail_object_path := null;
  end if;
  return new;
end;
$$;

create function private.assert_device_session_active()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not exists (
    select 1
    from public.devices as device
    where device.id = new.device_id
      and device.revoked_at is null
  ) then
    raise exception using errcode = '23514', message = 'revoked devices cannot bind auth sessions';
  end if;
  return new;
end;
$$;

create trigger messages_set_expiry
before insert on public.messages
for each row execute function private.set_message_expiry();

create trigger message_reply_links_assert_invariant
before insert or update on public.message_reply_links
for each row execute function private.assert_reply_link_invariant();

create trigger reactions_set_expiry
before insert on public.reactions
for each row execute function private.set_reaction_expiry();

create trigger message_receipts_set_expiry
before insert on public.message_receipts
for each row execute function private.set_receipt_expiry();

create trigger typing_state_set_expiry
before insert or update on public.typing_state
for each row execute function private.set_typing_expiry();

create trigger presence_state_set_expiry
before insert or update on public.presence_state
for each row execute function private.set_presence_expiry();

create trigger attachments_prepare_paths
before insert on public.attachments
for each row execute function private.prepare_attachment_paths();

create trigger device_sessions_assert_active
before insert or update on private.device_sessions
for each row execute function private.assert_device_session_active();

revoke all on all tables in schema private from public, anon, authenticated;
revoke all on all sequences in schema private from public, anon, authenticated;
revoke all on all functions in schema private from public, anon, authenticated;

grant execute on function private.current_device_id() to authenticated;
grant execute on function private.is_active_room_member(uuid) to authenticated;
grant execute on function private.can_manage_room(uuid) to authenticated;
grant execute on function private.can_access_message(uuid) to authenticated;
grant execute on function private.shares_room_with(uuid) to authenticated;
grant execute on function private.session_belongs_to_user(uuid, uuid) to service_role;
grant usage on schema private to supabase_auth_admin;
grant execute on function private.auth_identity_is_synapse_private_authorized(text, text, boolean, jsonb)
to supabase_auth_admin;
grant execute on function private.authorize_synapse_private_user_creation(jsonb) to supabase_auth_admin;
grant execute on function private.authorize_synapse_private_access_token(jsonb) to supabase_auth_admin;
