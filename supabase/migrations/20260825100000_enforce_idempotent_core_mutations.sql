-- Release-critical idempotency for every mutating contract exposed by Android.
-- Receipts contain identifiers and outcomes only; no message, room-title, or
-- invitation plaintext is persisted.

create or replace function private.can_access_message(p_message_id uuid)
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
      and message.created_at >= room_member.joined_at
      and not exists (
        select 1
        from private.message_deletion_requests as deletion_request
        where deletion_request.message_id = message.id
      )
      and private.current_device_id() is not null
      and (
        (
          message.current_revision = 0
          and exists (
            select 1
            from public.message_envelopes as message_envelope
            where message_envelope.message_id = message.id
              and message_envelope.recipient_device_id = private.current_device_id()
          )
        )
        or (
          message.current_revision > 0
          and exists (
            select 1
            from public.message_revisions as revision
            join public.message_revision_envelopes as revision_envelope
              on revision_envelope.revision_id = revision.id
            where revision.message_id = message.id
              and revision.revision_number = message.current_revision
              and revision.expires_at > statement_timestamp()
              and revision_envelope.recipient_device_id = private.current_device_id()
          )
        )
      )
  );
$$;

alter table private.runtime_configuration
add column invite_derivation_key bytea not null default extensions.gen_random_bytes(32),
add constraint runtime_configuration_invite_derivation_key_length
  check (octet_length(invite_derivation_key) = 32);

create table private.invite_issuance_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  invite_id uuid not null,
  invite_kind text not null,
  room_id uuid references public.rooms (id) on delete cascade,
  code_digest bytea not null,
  request_digest bytea not null,
  completed_at timestamptz not null,
  invite_expires_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  unique (invite_kind, invite_id),
  constraint invite_issuance_receipts_kind_valid check (
    invite_kind in ('ACCOUNT_REGISTRATION', 'ROOM_MEMBERSHIP')
  ),
  constraint invite_issuance_receipts_room_complete check (
    (invite_kind = 'ROOM_MEMBERSHIP') = (room_id is not null)
  ),
  constraint invite_issuance_receipts_code_digest_sha256 check (
    octet_length(code_digest) = 32
  ),
  constraint invite_issuance_receipts_request_digest_sha256 check (
    octet_length(request_digest) = 32
  ),
  constraint invite_issuance_receipts_expiry_valid check (
    invite_expires_at > completed_at
    and expires_at = invite_expires_at + interval '24 hours'
    and expires_at <= completed_at + interval '48 hours'
  )
);

create table private.room_retention_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  room_id uuid not null references public.rooms (id) on delete cascade,
  request_digest bytea not null,
  retention_seconds integer not null,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint room_retention_receipts_digest_sha256 check (octet_length(request_digest) = 32),
  constraint room_retention_receipts_period_supported check (
    retention_seconds in (300, 3600, 86400, 604800)
  ),
  constraint room_retention_receipts_expiry_valid check (
    expires_at = completed_at + interval '24 hours'
  )
);

create table private.room_member_role_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  room_id uuid not null references public.rooms (id) on delete cascade,
  member_user_id uuid not null references public.profiles (user_id) on delete cascade,
  request_digest bytea not null,
  member_role text not null,
  membership_epoch integer not null,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint room_member_role_receipts_digest_sha256 check (octet_length(request_digest) = 32),
  constraint room_member_role_receipts_role_valid check (member_role in ('ADMIN', 'MEMBER')),
  constraint room_member_role_receipts_epoch_valid check (
    membership_epoch between 1 and 2147483647
  ),
  constraint room_member_role_receipts_expiry_valid check (
    expires_at = completed_at + interval '24 hours'
  )
);

create table private.room_member_removal_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  room_id uuid not null references public.rooms (id) on delete cascade,
  removed_user_id uuid not null,
  request_digest bytea not null,
  membership_epoch integer not null,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint room_member_removal_receipts_digest_sha256 check (
    octet_length(request_digest) = 32
  ),
  constraint room_member_removal_receipts_epoch_valid check (
    membership_epoch between 1 and 2147483647
  ),
  constraint room_member_removal_receipts_expiry_valid check (
    expires_at = completed_at + interval '24 hours'
  )
);

create table private.message_deletion_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  message_id uuid not null,
  expected_revision integer not null,
  request_digest bytea not null,
  correlation_id uuid not null,
  deletion_state text not null,
  requested_at timestamptz not null,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint message_deletion_receipts_digest_sha256 check (octet_length(request_digest) = 32),
  constraint message_deletion_receipts_revision_valid check (expected_revision between 0 and 100),
  constraint message_deletion_receipts_state_valid check (
    deletion_state in ('DELETED', 'PURGE_PENDING')
  ),
  constraint message_deletion_receipts_expiry_valid check (
    requested_at <= completed_at
    and expires_at = completed_at + interval '24 hours'
  )
);

create index invite_issuance_mutation_receipts_expiry_idx
on private.invite_issuance_mutation_receipts (expires_at, actor_user_id, client_mutation_id);
create index invite_issuance_mutation_receipts_room_id_idx
on private.invite_issuance_mutation_receipts (room_id);
create index room_retention_mutation_receipts_expiry_idx
on private.room_retention_mutation_receipts (expires_at, actor_user_id, client_mutation_id);
create index room_retention_mutation_receipts_room_id_idx
on private.room_retention_mutation_receipts (room_id);
create index room_member_role_mutation_receipts_expiry_idx
on private.room_member_role_mutation_receipts (expires_at, actor_user_id, client_mutation_id);
create index room_member_role_mutation_receipts_room_id_idx
on private.room_member_role_mutation_receipts (room_id);
create index room_member_role_mutation_receipts_member_user_idx
on private.room_member_role_mutation_receipts (member_user_id);
create index room_member_removal_mutation_receipts_expiry_idx
on private.room_member_removal_mutation_receipts (expires_at, actor_user_id, client_mutation_id);
create index room_member_removal_mutation_receipts_room_id_idx
on private.room_member_removal_mutation_receipts (room_id);
create index message_deletion_mutation_receipts_expiry_idx
on private.message_deletion_mutation_receipts (expires_at, actor_user_id, client_mutation_id);
create index message_deletion_mutation_receipts_message_id_idx
on private.message_deletion_mutation_receipts (message_id);

drop function public._edge_initialize_runtime_configuration(text);
drop function private.initialize_runtime_configuration(text);

create function private.initialize_runtime_configuration(p_project_url text)
returns table (
  username_hmac_pepper text,
  rate_limit_hmac_pepper text,
  invite_derivation_key text,
  purge_secret text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  normalized_project_url text := rtrim(p_project_url, '/');
  runtime_configuration private.runtime_configuration;
begin
  if normalized_project_url is null
    or (
      normalized_project_url !~ '^https://[a-z0-9]{20}[.]supabase[.]co$'
      and normalized_project_url !~ '^http://(127[.]0[.]0[.]1|localhost|kong)(:[0-9]{1,5})?$'
    )
  then
    raise exception using errcode = '22023', message = 'the Supabase project URL is invalid';
  end if;

  select configuration.*
    into strict runtime_configuration
  from private.runtime_configuration as configuration
  where configuration.singleton
  for update;

  if runtime_configuration.project_url is null then
    update private.runtime_configuration as configuration
    set project_url = normalized_project_url,
        configured_at = statement_timestamp()
    where configuration.singleton
    returning configuration.* into strict runtime_configuration;
  elsif runtime_configuration.project_url <> normalized_project_url then
    raise exception using errcode = '42501', message = 'the runtime project URL cannot be rebound';
  end if;

  return query
  select
    encode(runtime_configuration.username_hmac_pepper, 'hex'),
    encode(runtime_configuration.rate_limit_hmac_pepper, 'hex'),
    encode(runtime_configuration.invite_derivation_key, 'hex'),
    rtrim(translate(encode(runtime_configuration.purge_capability, 'base64'), '+/', '-_'), '=');
end;
$$;

create function public._edge_initialize_runtime_configuration(p_project_url text)
returns table (
  username_hmac_pepper text,
  rate_limit_hmac_pepper text,
  invite_derivation_key text,
  purge_secret text
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.initialize_runtime_configuration(p_project_url);
$$;

create function private.derive_invite_code_digest(
  p_actor_user_id uuid,
  p_invite_kind text,
  p_room_id uuid,
  p_client_mutation_id uuid
)
returns bytea
language sql
stable
security definer
set search_path = ''
as $$
  select extensions.digest(
    convert_to(
      rtrim(
        translate(
          encode(
            extensions.hmac(
              convert_to(
                'synapse-private/invite/v1' || chr(31)
                || p_actor_user_id::text || chr(31)
                || p_invite_kind || chr(31)
                || coalesce(p_room_id::text, 'NO_ROOM') || chr(31)
                || p_client_mutation_id::text,
                'UTF8'
              ),
              convert_to(encode(configuration.invite_derivation_key, 'hex'), 'UTF8'),
              'sha256'
            ),
            'base64'
          ),
          '+/',
          '-_'
        ),
        '='
      ),
      'UTF8'
    ),
    'sha256'
  )
  from private.runtime_configuration as configuration
  where configuration.singleton;
$$;

create or replace function private.assert_invite_code_available(p_code_digest bytea)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if octet_length(p_code_digest) <> 32 then
    raise exception using errcode = '22023', message = 'invite digest must be SHA-256';
  end if;

  perform pg_advisory_xact_lock(hashtextextended(encode(p_code_digest, 'hex'), 0));

  if exists (select 1 from private.bootstrap_capabilities where code_digest = p_code_digest)
    or exists (select 1 from private.account_registration_invites where code_digest = p_code_digest)
    or exists (select 1 from private.room_membership_invites where code_digest = p_code_digest)
    or exists (select 1 from private.account_registration_receipts where code_digest = p_code_digest)
    or exists (select 1 from private.room_membership_invite_receipts where code_digest = p_code_digest)
    or exists (
      select 1
      from private.invite_issuance_mutation_receipts
      where code_digest = p_code_digest and expires_at > statement_timestamp()
    )
  then
    raise exception using errcode = '23505', message = 'invite capability already exists';
  end if;
end;
$$;

drop function public._edge_issue_account_registration_invite(uuid, uuid, bytea, integer);
drop function public._edge_issue_room_membership_invite(uuid, uuid, uuid, bytea, integer);
drop function private.issue_account_registration_invite(uuid, uuid, bytea, integer);
drop function private.issue_room_membership_invite(uuid, uuid, uuid, bytea, integer);

create function private.issue_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
  p_client_mutation_id uuid,
  p_invite_kind text,
  p_room_id uuid,
  p_code_digest bytea,
  p_expires_in_seconds integer
)
returns table (invite_id uuid, invite_kind text, room_id uuid, expires_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  persisted_receipt private.invite_issuance_mutation_receipts;
  created_invite_id uuid;
  created_invite_expiry timestamptz;
  mutation_time timestamptz := statement_timestamp();
  request_digest bytea;
begin
  if p_actor_user_id is null
    or p_auth_session_id is null
    or p_client_mutation_id is null
    or p_invite_kind not in ('ACCOUNT_REGISTRATION', 'ROOM_MEMBERSHIP')
    or (p_invite_kind = 'ROOM_MEMBERSHIP') <> (p_room_id is not null)
    or p_expires_in_seconds not between 60 and 86400
    or octet_length(p_code_digest) <> 32
  then
    raise exception using errcode = '22023', message = 'invite issuance request is invalid';
  end if;
  if not private.edge_actor_session_is_active(p_actor_user_id, p_auth_session_id) then
    raise exception using errcode = '42501', message = 'invite issuance is not authorized';
  end if;

  request_digest := extensions.digest(
    convert_to(
      p_invite_kind || chr(31)
      || coalesce(p_room_id::text, 'NO_ROOM') || chr(31)
      || p_expires_in_seconds::text || chr(31)
      || encode(p_code_digest, 'hex'),
      'UTF8'
    ),
    'sha256'
  );

  perform pg_advisory_xact_lock(
    hashtextextended(p_actor_user_id::text || '/invite-issuance/' || p_client_mutation_id::text, 0)
  );

  select receipt.*
    into persisted_receipt
  from private.invite_issuance_mutation_receipts as receipt
  where receipt.actor_user_id = p_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > mutation_time;

  if found then
    if persisted_receipt.request_digest <> request_digest then
      raise exception using errcode = '23505', message = 'invite mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.invite_id,
      persisted_receipt.invite_kind,
      persisted_receipt.room_id,
      persisted_receipt.invite_expires_at;
    return;
  end if;

  delete from private.invite_issuance_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = p_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= mutation_time;

  if private.derive_invite_code_digest(
    p_actor_user_id,
    p_invite_kind,
    p_room_id,
    p_client_mutation_id
  ) <> p_code_digest then
    raise exception using errcode = '42501', message = 'invite derivation is invalid';
  end if;

  if p_invite_kind = 'ACCOUNT_REGISTRATION' then
    if not exists (
      select 1
      from public.room_members as room_member
      where room_member.user_id = p_actor_user_id
        and room_member.member_role in ('OWNER', 'ADMIN')
    ) then
      raise exception using errcode = '42501', message = 'account invite issuance is not authorized';
    end if;
  elsif not exists (
    select 1
    from public.room_members as room_member
    where room_member.room_id = p_room_id
      and room_member.user_id = p_actor_user_id
      and room_member.member_role in ('OWNER', 'ADMIN')
  ) then
    raise exception using errcode = '42501', message = 'room invite issuance is not authorized';
  end if;

  perform private.assert_invite_code_available(p_code_digest);
  created_invite_expiry := mutation_time + make_interval(secs => p_expires_in_seconds);

  if p_invite_kind = 'ACCOUNT_REGISTRATION' then
    insert into private.account_registration_invites (
      issued_by_user_id,
      code_digest,
      created_at,
      expires_at
    ) values (
      p_actor_user_id,
      p_code_digest,
      mutation_time,
      created_invite_expiry
    ) returning id into strict created_invite_id;
  else
    insert into private.room_membership_invites (
      room_id,
      issued_by_user_id,
      code_digest,
      created_at,
      expires_at
    ) values (
      p_room_id,
      p_actor_user_id,
      p_code_digest,
      mutation_time,
      created_invite_expiry
    ) returning id into strict created_invite_id;
  end if;

  insert into private.invite_issuance_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    invite_id,
    invite_kind,
    room_id,
    code_digest,
    request_digest,
    completed_at,
    invite_expires_at,
    expires_at
  ) values (
    p_actor_user_id,
    p_client_mutation_id,
    created_invite_id,
    p_invite_kind,
    p_room_id,
    p_code_digest,
    request_digest,
    mutation_time,
    created_invite_expiry,
    created_invite_expiry + interval '24 hours'
  ) returning * into strict persisted_receipt;

  return query
  select
    persisted_receipt.invite_id,
    persisted_receipt.invite_kind,
    persisted_receipt.room_id,
    persisted_receipt.invite_expires_at;
end;
$$;

create function public._edge_issue_account_registration_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
  p_client_mutation_id uuid,
  p_code_digest bytea,
  p_expires_in_seconds integer
)
returns table (invite_id uuid, invite_kind text, expires_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select issued.invite_id, issued.invite_kind, issued.expires_at
  from private.issue_invite(
    p_actor_user_id,
    p_auth_session_id,
    p_client_mutation_id,
    'ACCOUNT_REGISTRATION',
    null,
    p_code_digest,
    p_expires_in_seconds
  ) as issued;
$$;

create function public._edge_issue_room_membership_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
  p_client_mutation_id uuid,
  p_room_id uuid,
  p_code_digest bytea,
  p_expires_in_seconds integer
)
returns table (invite_id uuid, invite_kind text, room_id uuid, expires_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.issue_invite(
    p_actor_user_id,
    p_auth_session_id,
    p_client_mutation_id,
    'ROOM_MEMBERSHIP',
    p_room_id,
    p_code_digest,
    p_expires_in_seconds
  );
$$;

drop function public.update_room_retention(uuid, integer);
drop function private.update_room_retention(uuid, integer);

create function private.update_room_retention(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_retention_seconds integer
)
returns table (room_id uuid, retention_seconds integer, updated_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  persisted_receipt private.room_retention_mutation_receipts;
  locked_room public.rooms;
  mutation_time timestamptz := statement_timestamp();
  request_digest bytea;
begin
  if mutation_actor_user_id is null or private.current_device_id() is null or p_client_mutation_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;
  if p_retention_seconds not in (300, 3600, 86400, 604800) then
    raise exception using errcode = '22023', message = 'retention period is unsupported';
  end if;

  request_digest := extensions.digest(
    convert_to(p_room_id::text || chr(31) || p_retention_seconds::text, 'UTF8'),
    'sha256'
  );
  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/room-retention/' || p_client_mutation_id::text, 0)
  );

  select receipt.* into persisted_receipt
  from private.room_retention_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > mutation_time;

  if found then
    if persisted_receipt.request_digest <> request_digest then
      raise exception using errcode = '23505', message = 'room retention mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.room_id,
      persisted_receipt.retention_seconds,
      persisted_receipt.completed_at;
    return;
  end if;

  delete from private.room_retention_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= mutation_time;

  select room.* into locked_room
  from public.rooms as room
  where room.id = p_room_id
  for update;

  if not found or not private.can_manage_room(p_room_id) then
    raise exception using errcode = '42501', message = 'room management is not authorized';
  end if;

  if locked_room.retention_seconds <> p_retention_seconds then
    update public.rooms as room
    set retention_seconds = p_retention_seconds
    where room.id = p_room_id;
  end if;

  insert into private.room_retention_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    room_id,
    request_digest,
    retention_seconds,
    completed_at,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_mutation_id,
    p_room_id,
    request_digest,
    p_retention_seconds,
    mutation_time,
    mutation_time + interval '24 hours'
  ) returning * into strict persisted_receipt;

  return query
  select
    persisted_receipt.room_id,
    persisted_receipt.retention_seconds,
    persisted_receipt.completed_at;
end;
$$;

create function public.update_room_retention(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_retention_seconds integer
)
returns table (room_id uuid, retention_seconds integer, updated_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.update_room_retention(p_room_id, p_client_mutation_id, p_retention_seconds);
$$;

drop function public.update_room_member_role(uuid, uuid, text);
drop function private.update_room_member_role(uuid, uuid, text);

create function private.update_room_member_role(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_member_user_id uuid,
  p_member_role text
)
returns table (
  room_id uuid,
  member_user_id uuid,
  member_role text,
  new_membership_epoch integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  locked_room public.rooms;
  target_member public.room_members;
  persisted_receipt private.room_member_role_mutation_receipts;
  advanced_membership_epoch integer;
  mutation_time timestamptz := statement_timestamp();
  request_digest bytea;
begin
  if mutation_actor_user_id is null or private.current_device_id() is null or p_client_mutation_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;
  if p_member_role not in ('ADMIN', 'MEMBER') then
    raise exception using errcode = '22023', message = 'member role is invalid';
  end if;

  request_digest := extensions.digest(
    convert_to(
      p_room_id::text || chr(31) || p_member_user_id::text || chr(31) || p_member_role,
      'UTF8'
    ),
    'sha256'
  );
  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/member-role/' || p_client_mutation_id::text, 0)
  );

  select receipt.* into persisted_receipt
  from private.room_member_role_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > mutation_time;

  if found then
    if persisted_receipt.request_digest <> request_digest then
      raise exception using errcode = '23505', message = 'member role mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.room_id,
      persisted_receipt.member_user_id,
      persisted_receipt.member_role,
      persisted_receipt.membership_epoch;
    return;
  end if;

  delete from private.room_member_role_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= mutation_time;

  select room.* into locked_room
  from public.rooms as room
  where room.id = p_room_id
  for update;

  if not found
    or locked_room.room_kind <> 'GROUP'
    or locked_room.owner_user_id <> mutation_actor_user_id
    or p_member_user_id = mutation_actor_user_id
  then
    raise exception using errcode = '42501', message = 'member role change is not authorized';
  end if;

  select room_member.* into target_member
  from public.room_members as room_member
  where room_member.room_id = p_room_id
    and room_member.user_id = p_member_user_id
  for update;

  if not found or target_member.member_role = 'OWNER' then
    raise exception using errcode = '22023', message = 'room member is unavailable';
  end if;

  if target_member.member_role = p_member_role then
    advanced_membership_epoch := locked_room.membership_epoch;
  else
    update public.room_members as room_member
    set member_role = p_member_role
    where room_member.room_id = p_room_id
      and room_member.user_id = p_member_user_id;

    update public.rooms as room
    set membership_epoch = room.membership_epoch + 1
    where room.id = p_room_id
      and room.membership_epoch < 2147483647
    returning room.membership_epoch into advanced_membership_epoch;
    if not found then
      raise exception using errcode = '54000', message = 'room membership epoch is exhausted';
    end if;
  end if;

  insert into private.room_member_role_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    room_id,
    member_user_id,
    request_digest,
    member_role,
    membership_epoch,
    completed_at,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_mutation_id,
    p_room_id,
    p_member_user_id,
    request_digest,
    p_member_role,
    advanced_membership_epoch,
    mutation_time,
    mutation_time + interval '24 hours'
  ) returning * into strict persisted_receipt;

  return query
  select
    persisted_receipt.room_id,
    persisted_receipt.member_user_id,
    persisted_receipt.member_role,
    persisted_receipt.membership_epoch;
end;
$$;

create function public.update_room_member_role(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_member_user_id uuid,
  p_member_role text
)
returns table (
  room_id uuid,
  member_user_id uuid,
  member_role text,
  new_membership_epoch integer
)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.update_room_member_role(
    p_room_id,
    p_client_mutation_id,
    p_member_user_id,
    p_member_role
  );
$$;

drop function public.remove_room_member(uuid, uuid);
drop function private.remove_room_member(uuid, uuid);

create function private.remove_room_member(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_removed_user_id uuid
)
returns table (room_id uuid, removed_user_id uuid, new_membership_epoch integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_role text;
  removed_role text;
  persisted_receipt private.room_member_removal_mutation_receipts;
  advanced_membership_epoch integer;
  mutation_time timestamptz := statement_timestamp();
  request_digest bytea;
begin
  if mutation_actor_user_id is null or private.current_device_id() is null or p_client_mutation_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  request_digest := extensions.digest(
    convert_to(p_room_id::text || chr(31) || p_removed_user_id::text, 'UTF8'),
    'sha256'
  );
  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/member-removal/' || p_client_mutation_id::text, 0)
  );

  select receipt.* into persisted_receipt
  from private.room_member_removal_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > mutation_time;

  if found then
    if persisted_receipt.request_digest <> request_digest then
      raise exception using errcode = '23505', message = 'member removal mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.room_id,
      persisted_receipt.removed_user_id,
      persisted_receipt.membership_epoch;
    return;
  end if;

  delete from private.room_member_removal_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= mutation_time;

  perform 1 from public.rooms as room where room.id = p_room_id for update;
  if not found then
    raise exception using errcode = '42501', message = 'member removal is not authorized';
  end if;

  select room_member.member_role into actor_role
  from public.room_members as room_member
  where room_member.room_id = p_room_id
    and room_member.user_id = mutation_actor_user_id;
  if not found then
    raise exception using errcode = '42501', message = 'member removal is not authorized';
  end if;

  select room_member.member_role into removed_role
  from public.room_members as room_member
  where room_member.room_id = p_room_id
    and room_member.user_id = p_removed_user_id
  for update;
  if not found then
    raise exception using errcode = '22023', message = 'room member is unavailable';
  end if;

  if p_removed_user_id = mutation_actor_user_id
    or removed_role = 'OWNER'
    or actor_role not in ('OWNER', 'ADMIN')
    or (actor_role = 'ADMIN' and removed_role <> 'MEMBER')
  then
    raise exception using errcode = '42501', message = 'member removal is not authorized';
  end if;

  delete from public.room_members as room_member
  where room_member.room_id = p_room_id
    and room_member.user_id = p_removed_user_id;

  update public.rooms as room
  set membership_epoch = room.membership_epoch + 1
  where room.id = p_room_id
    and room.membership_epoch < 2147483647
  returning room.membership_epoch into advanced_membership_epoch;
  if not found then
    raise exception using errcode = '54000', message = 'room membership epoch is exhausted';
  end if;

  insert into private.room_member_removal_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    room_id,
    removed_user_id,
    request_digest,
    membership_epoch,
    completed_at,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_mutation_id,
    p_room_id,
    p_removed_user_id,
    request_digest,
    advanced_membership_epoch,
    mutation_time,
    mutation_time + interval '24 hours'
  ) returning * into strict persisted_receipt;

  return query
  select
    persisted_receipt.room_id,
    persisted_receipt.removed_user_id,
    persisted_receipt.membership_epoch;
end;
$$;

create function public.remove_room_member(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_removed_user_id uuid
)
returns table (room_id uuid, removed_user_id uuid, new_membership_epoch integer)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.remove_room_member(p_room_id, p_client_mutation_id, p_removed_user_id);
$$;

drop function public.delete_message_for_everyone(uuid);
drop function private.delete_message_for_everyone(uuid);

create function private.delete_message_for_everyone(
  p_message_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer
)
returns table (
  message_id uuid,
  deleted_revision integer,
  correlation_id uuid,
  deletion_state text,
  requested_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  persisted_message public.messages;
  persisted_request private.message_deletion_requests;
  persisted_receipt private.message_deletion_mutation_receipts;
  deletion_time timestamptz := statement_timestamp();
  request_digest bytea;
begin
  if mutation_actor_user_id is null
    or actor_device_id is null
    or p_client_mutation_id is null
    or p_expected_revision is null
    or p_expected_revision not between 0 and 100
  then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  request_digest := extensions.digest(
    convert_to(p_message_id::text || chr(31) || p_expected_revision::text, 'UTF8'),
    'sha256'
  );
  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/message-deletion/' || p_client_mutation_id::text, 0)
  );

  select receipt.* into persisted_receipt
  from private.message_deletion_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > deletion_time;

  if found then
    if persisted_receipt.request_digest <> request_digest then
      raise exception using errcode = '23505', message = 'message deletion mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.message_id,
      persisted_receipt.expected_revision,
      persisted_receipt.correlation_id,
      persisted_receipt.deletion_state,
      persisted_receipt.requested_at;
    return;
  end if;

  delete from private.message_deletion_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= deletion_time;

  select message.* into persisted_message
  from public.messages as message
  where message.id = p_message_id
    and message.sender_user_id = mutation_actor_user_id
    and message.expires_at > deletion_time
  for update;
  if not found then
    raise exception using errcode = '42501', message = 'message deletion is not authorized';
  end if;
  if persisted_message.current_revision <> p_expected_revision then
    raise exception using errcode = '40001', message = 'message revision changed';
  end if;

  select deletion_request.* into persisted_request
  from private.message_deletion_requests as deletion_request
  where deletion_request.message_id = persisted_message.id;

  if found then
    insert into private.message_deletion_mutation_receipts (
      actor_user_id,
      client_mutation_id,
      message_id,
      expected_revision,
      request_digest,
      correlation_id,
      deletion_state,
      requested_at,
      completed_at,
      expires_at
    ) values (
      mutation_actor_user_id,
      p_client_mutation_id,
      persisted_message.id,
      p_expected_revision,
      request_digest,
      persisted_request.correlation_id,
      'PURGE_PENDING',
      persisted_request.requested_at,
      deletion_time,
      deletion_time + interval '24 hours'
    ) returning * into strict persisted_receipt;

    return query
    select
      persisted_receipt.message_id,
      persisted_receipt.expected_revision,
      persisted_receipt.correlation_id,
      persisted_receipt.deletion_state,
      persisted_receipt.requested_at;
    return;
  end if;

  if exists (
    select 1
    from public.attachments as attachment
    where attachment.message_id = persisted_message.id
  ) then
    insert into private.message_deletion_requests (
      message_id,
      requested_by_user_id,
      requested_at
    ) values (
      persisted_message.id,
      mutation_actor_user_id,
      deletion_time
    ) returning * into strict persisted_request;

    insert into private.message_deletion_mutation_receipts (
      actor_user_id,
      client_mutation_id,
      message_id,
      expected_revision,
      request_digest,
      correlation_id,
      deletion_state,
      requested_at,
      completed_at,
      expires_at
    ) values (
      mutation_actor_user_id,
      p_client_mutation_id,
      persisted_message.id,
      p_expected_revision,
      request_digest,
      persisted_request.correlation_id,
      'PURGE_PENDING',
      persisted_request.requested_at,
      deletion_time,
      deletion_time + interval '24 hours'
    ) returning * into strict persisted_receipt;
  else
    persisted_request.correlation_id := gen_random_uuid();
    persisted_request.requested_at := deletion_time;

    delete from public.messages as message
    where message.id = persisted_message.id;

    insert into private.purge_receipts (
      correlation_id,
      started_at,
      completed_at,
      messages_deleted,
      attachment_objects_deleted,
      typing_rows_deleted,
      presence_rows_deleted,
      invites_deleted,
      device_reservations_deleted,
      mutation_receipts_deleted
    ) values (
      persisted_request.correlation_id,
      deletion_time,
      clock_timestamp(),
      1,
      0,
      0,
      0,
      0,
      0,
      0
    );

    insert into private.message_deletion_mutation_receipts (
      actor_user_id,
      client_mutation_id,
      message_id,
      expected_revision,
      request_digest,
      correlation_id,
      deletion_state,
      requested_at,
      completed_at,
      expires_at
    ) values (
      mutation_actor_user_id,
      p_client_mutation_id,
      persisted_message.id,
      p_expected_revision,
      request_digest,
      persisted_request.correlation_id,
      'DELETED',
      persisted_request.requested_at,
      deletion_time,
      deletion_time + interval '24 hours'
    ) returning * into strict persisted_receipt;
  end if;

  return query
  select
    persisted_receipt.message_id,
    persisted_receipt.expected_revision,
    persisted_receipt.correlation_id,
    persisted_receipt.deletion_state,
    persisted_receipt.requested_at;
end;
$$;

create function public.delete_message_for_everyone(
  p_message_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer
)
returns table (
  message_id uuid,
  deleted_revision integer,
  correlation_id uuid,
  deletion_state text,
  requested_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.delete_message_for_everyone(
    p_message_id,
    p_client_mutation_id,
    p_expected_revision
  );
$$;

alter function private.purge_expired_relational_data(integer, uuid)
rename to purge_expired_relational_data_without_core_mutation_receipts;

create function private.purge_expired_relational_data(
  p_batch_limit integer default 500,
  p_correlation_id uuid default gen_random_uuid()
)
returns table (
  correlation_id uuid,
  messages_deleted integer,
  typing_rows_deleted integer,
  presence_rows_deleted integer,
  invites_deleted integer,
  device_reservations_deleted integer,
  mutation_receipts_deleted integer,
  completed_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  base_receipt record;
  core_receipts_deleted integer;
begin
  select * into strict base_receipt
  from private.purge_expired_relational_data_without_core_mutation_receipts(
    p_batch_limit,
    p_correlation_id
  );

  with expired_invite_issuance_receipts as (
    delete from private.invite_issuance_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.invite_issuance_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_room_retention_receipts as (
    delete from private.room_retention_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.room_retention_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_room_member_role_receipts as (
    delete from private.room_member_role_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.room_member_role_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_room_member_removal_receipts as (
    delete from private.room_member_removal_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.room_member_removal_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_message_deletion_receipts as (
    delete from private.message_deletion_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.message_deletion_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  )
  select
    (select count(*) from expired_invite_issuance_receipts)
    + (select count(*) from expired_room_retention_receipts)
    + (select count(*) from expired_room_member_role_receipts)
    + (select count(*) from expired_room_member_removal_receipts)
    + (select count(*) from expired_message_deletion_receipts)
  into core_receipts_deleted;

  update private.purge_receipts as purge_receipt
  set mutation_receipts_deleted = purge_receipt.mutation_receipts_deleted + core_receipts_deleted
  where purge_receipt.correlation_id = base_receipt.correlation_id;

  return query
  select
    base_receipt.correlation_id,
    base_receipt.messages_deleted,
    base_receipt.typing_rows_deleted,
    base_receipt.presence_rows_deleted,
    base_receipt.invites_deleted,
    base_receipt.device_reservations_deleted,
    base_receipt.mutation_receipts_deleted + core_receipts_deleted,
    base_receipt.completed_at;
end;
$$;

revoke all on table
  private.invite_issuance_mutation_receipts,
  private.room_retention_mutation_receipts,
  private.room_member_role_mutation_receipts,
  private.room_member_removal_mutation_receipts,
  private.message_deletion_mutation_receipts
from public, anon, authenticated;

revoke all on function private.initialize_runtime_configuration(text)
from public, anon, authenticated;
revoke all on function public._edge_initialize_runtime_configuration(text)
from public, anon, authenticated;
revoke all on function private.derive_invite_code_digest(uuid, text, uuid, uuid)
from public, anon, authenticated, service_role;
revoke all on function private.assert_invite_code_available(bytea)
from public, anon, authenticated;
revoke all on function private.issue_invite(uuid, uuid, uuid, text, uuid, bytea, integer)
from public, anon, authenticated;
revoke all on function public._edge_issue_account_registration_invite(
  uuid, uuid, uuid, bytea, integer
) from public, anon, authenticated;
revoke all on function public._edge_issue_room_membership_invite(
  uuid, uuid, uuid, uuid, bytea, integer
) from public, anon, authenticated;

revoke all on function private.update_room_retention(uuid, uuid, integer)
from public, anon, authenticated;
revoke all on function public.update_room_retention(uuid, uuid, integer)
from public, anon, authenticated;
revoke all on function private.update_room_member_role(uuid, uuid, uuid, text)
from public, anon, authenticated;
revoke all on function public.update_room_member_role(uuid, uuid, uuid, text)
from public, anon, authenticated;
revoke all on function private.remove_room_member(uuid, uuid, uuid)
from public, anon, authenticated;
revoke all on function public.remove_room_member(uuid, uuid, uuid)
from public, anon, authenticated;
revoke all on function private.delete_message_for_everyone(uuid, uuid, integer)
from public, anon, authenticated;
revoke all on function public.delete_message_for_everyone(uuid, uuid, integer)
from public, anon, authenticated;
revoke all on function private.purge_expired_relational_data(integer, uuid)
from public, anon, authenticated;

grant execute on function private.initialize_runtime_configuration(text) to service_role;
grant execute on function public._edge_initialize_runtime_configuration(text) to service_role;
grant execute on function private.issue_invite(uuid, uuid, uuid, text, uuid, bytea, integer)
to service_role;
grant execute on function public._edge_issue_account_registration_invite(
  uuid, uuid, uuid, bytea, integer
) to service_role;
grant execute on function public._edge_issue_room_membership_invite(
  uuid, uuid, uuid, uuid, bytea, integer
) to service_role;

grant execute on function private.update_room_retention(uuid, uuid, integer) to authenticated;
grant execute on function public.update_room_retention(uuid, uuid, integer) to authenticated;
grant execute on function private.update_room_member_role(uuid, uuid, uuid, text) to authenticated;
grant execute on function public.update_room_member_role(uuid, uuid, uuid, text) to authenticated;
grant execute on function private.remove_room_member(uuid, uuid, uuid) to authenticated;
grant execute on function public.remove_room_member(uuid, uuid, uuid) to authenticated;
grant execute on function private.delete_message_for_everyone(uuid, uuid, integer) to authenticated;
grant execute on function public.delete_message_for_everyone(uuid, uuid, integer) to authenticated;

-- The issue-invite bundle and every Edge Function that imports readRuntimeSecrets
-- must be redeployed after this migration. No table is added to Postgres Changes,
-- and the locked realtime schema remains untouched.
