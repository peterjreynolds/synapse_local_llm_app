alter table public.message_envelopes
drop constraint message_envelopes_type_valid;

alter table public.message_envelopes
add constraint message_envelopes_type_valid check (
  signal_message_type in ('LOCAL_AEAD', 'PREKEY', 'WHISPER')
  and (signal_message_type <> 'LOCAL_AEAD' or octet_length(ciphertext) >= 29)
);

alter table public.reaction_envelopes
drop constraint reaction_envelopes_type_valid;

alter table public.reaction_envelopes
add constraint reaction_envelopes_type_valid check (
  signal_message_type in ('LOCAL_AEAD', 'PREKEY', 'WHISPER')
  and (signal_message_type <> 'LOCAL_AEAD' or octet_length(ciphertext) >= 29)
);

create or replace function private.assert_complete_signal_envelopes(
  p_room_id uuid,
  p_sender_device_id uuid,
  p_envelopes jsonb,
  p_maximum_ciphertext_bytes integer
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  envelope jsonb;
  supplied_device_ids uuid[] := array[]::uuid[];
  recipient_device_id uuid;
  expected_device_ids uuid[];
  decoded_ciphertext bytea;
  message_type text;
begin
  if p_envelopes is null
    or jsonb_typeof(p_envelopes) <> 'array'
    or jsonb_array_length(p_envelopes) not between 1 and 129
  then
    raise exception using errcode = '22023', message = 'envelopes must contain between one and 129 entries';
  end if;

  if (
    select count(*)
    from public.room_members as room_member
    join public.devices as device on device.user_id = room_member.user_id
    where room_member.room_id = p_room_id
      and device.revoked_at is null
      and device.id <> p_sender_device_id
  ) > 128 then
    raise exception using errcode = '23514', message = 'a room cannot require more than 128 peer envelopes';
  end if;

  for envelope in select value from jsonb_array_elements(p_envelopes)
  loop
    if jsonb_typeof(envelope) <> 'object'
      or exists (
        select 1
        from jsonb_object_keys(envelope) as supplied_key
        where supplied_key not in ('recipient_device_id', 'protocol_adapter_version', 'signal_message_type', 'ciphertext_hex')
      )
      or not (envelope ?& array['recipient_device_id', 'protocol_adapter_version', 'signal_message_type', 'ciphertext_hex'])
    then
      raise exception using errcode = '22023', message = 'each envelope must have exactly the supported fields';
    end if;

    recipient_device_id := (envelope ->> 'recipient_device_id')::uuid;
    if recipient_device_id = any(supplied_device_ids) then
      raise exception using errcode = '22023', message = 'recipient devices must be unique';
    end if;
    supplied_device_ids := array_append(supplied_device_ids, recipient_device_id);

    if (envelope ->> 'protocol_adapter_version')::integer <> 1 then
      raise exception using errcode = '22023', message = 'protocol adapter version is invalid';
    end if;

    message_type := envelope ->> 'signal_message_type';
    decoded_ciphertext := private.decode_bounded_hex(
      envelope ->> 'ciphertext_hex',
      case when recipient_device_id = p_sender_device_id then 29 else 1 end,
      p_maximum_ciphertext_bytes,
      'ciphertext_hex'
    );
    if recipient_device_id = p_sender_device_id and message_type <> 'LOCAL_AEAD' then
      raise exception using errcode = '22023', message = 'the sender device requires a LOCAL_AEAD envelope';
    end if;
    if recipient_device_id <> p_sender_device_id and message_type not in ('PREKEY', 'WHISPER') then
      raise exception using errcode = '22023', message = 'peer devices require Signal envelopes';
    end if;
    if octet_length(decoded_ciphertext) > p_maximum_ciphertext_bytes then
      raise exception using errcode = '22023', message = 'ciphertext_hex has an invalid byte length';
    end if;
  end loop;

  select coalesce(array_agg(device.id order by device.id), array[]::uuid[])
    into expected_device_ids
  from public.room_members as room_member
  join public.devices as device on device.user_id = room_member.user_id
  where room_member.room_id = p_room_id
    and device.revoked_at is null;

  select coalesce(array_agg(supplied_device_id order by supplied_device_id), array[]::uuid[])
    into supplied_device_ids
  from unnest(supplied_device_ids) as supplied_device_id;

  if supplied_device_ids <> expected_device_ids then
    raise exception using errcode = '22023', message = 'one envelope is required for every active room device';
  end if;
end;
$$;

alter table public.rooms
add column metadata_revision integer not null default 0,
add column metadata_updated_at timestamptz,
add constraint rooms_metadata_revision_valid check (metadata_revision between 0 and 2147483647),
add constraint rooms_metadata_state_complete check (
  (metadata_revision = 0) = (metadata_updated_at is null)
);

alter table public.messages
add column current_revision integer not null default 0,
add constraint messages_current_revision_valid check (current_revision between 0 and 100);

create table public.room_metadata_envelopes (
  room_id uuid not null references public.rooms (id) on delete cascade,
  metadata_revision integer not null,
  sender_user_id uuid references public.profiles (user_id) on delete set null,
  sender_device_id uuid references public.devices (id) on delete set null,
  recipient_device_id uuid not null references public.devices (id) on delete cascade,
  protocol_adapter_version smallint not null,
  signal_message_type text not null,
  ciphertext bytea not null,
  created_at timestamptz not null default statement_timestamp(),
  primary key (room_id, recipient_device_id),
  constraint room_metadata_envelopes_revision_valid check (metadata_revision between 1 and 2147483647),
  constraint room_metadata_envelopes_protocol_version_valid check (protocol_adapter_version = 1),
  constraint room_metadata_envelopes_type_valid check (
    signal_message_type in ('LOCAL_AEAD', 'PREKEY', 'WHISPER')
    and (signal_message_type <> 'LOCAL_AEAD' or octet_length(ciphertext) >= 29)
    and (
      sender_device_id is null
      or (recipient_device_id = sender_device_id and signal_message_type = 'LOCAL_AEAD')
      or (recipient_device_id <> sender_device_id and signal_message_type in ('PREKEY', 'WHISPER'))
    )
  ),
  constraint room_metadata_envelopes_ciphertext_bounded check (
    octet_length(ciphertext) between 1 and 16384
  )
);

create table public.message_revisions (
  id uuid primary key default gen_random_uuid(),
  message_id uuid not null unique references public.messages (id) on delete cascade,
  editor_user_id uuid references public.profiles (user_id) on delete set null,
  editor_device_id uuid references public.devices (id) on delete set null,
  revision_number integer not null,
  membership_epoch integer not null,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  constraint message_revisions_number_valid check (revision_number between 1 and 100),
  constraint message_revisions_membership_epoch_valid check (membership_epoch between 1 and 2147483647),
  constraint message_revisions_expiry_valid check (expires_at > created_at)
);

create table public.message_revision_envelopes (
  revision_id uuid not null references public.message_revisions (id) on delete cascade,
  recipient_device_id uuid not null references public.devices (id) on delete cascade,
  protocol_adapter_version smallint not null,
  signal_message_type text not null,
  ciphertext bytea not null,
  created_at timestamptz not null default statement_timestamp(),
  primary key (revision_id, recipient_device_id),
  constraint message_revision_envelopes_protocol_version_valid check (protocol_adapter_version = 1),
  constraint message_revision_envelopes_type_valid check (
    signal_message_type in ('LOCAL_AEAD', 'PREKEY', 'WHISPER')
    and (signal_message_type <> 'LOCAL_AEAD' or octet_length(ciphertext) >= 29)
  ),
  constraint message_revision_envelopes_ciphertext_bounded check (
    octet_length(ciphertext) between 1 and 262144
  )
);

create table public.room_member_preferences (
  room_id uuid not null,
  user_id uuid not null,
  archive_state text not null default 'ACTIVE',
  pin_state text not null default 'UNPINNED',
  mute_state text not null default 'UNMUTED',
  muted_until timestamptz,
  updated_at timestamptz not null default statement_timestamp(),
  primary key (room_id, user_id),
  foreign key (room_id, user_id)
    references public.room_members (room_id, user_id)
    on delete cascade,
  constraint room_member_preferences_archive_state_valid check (
    archive_state in ('ACTIVE', 'ARCHIVED')
  ),
  constraint room_member_preferences_pin_state_valid check (
    pin_state in ('PINNED', 'UNPINNED')
  ),
  constraint room_member_preferences_mute_state_valid check (
    mute_state in ('UNMUTED', 'MUTED_UNTIL', 'MUTED_FOREVER')
  ),
  constraint room_member_preferences_mute_state_complete check (
    (mute_state = 'MUTED_UNTIL') = (muted_until is not null)
  )
);

create table private.room_metadata_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  room_id uuid not null references public.rooms (id) on delete cascade,
  expected_revision integer not null,
  metadata_revision integer not null,
  request_digest bytea not null,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint room_metadata_mutation_receipts_revision_valid check (
    expected_revision between 0 and 2147483646
    and metadata_revision = expected_revision + 1
  ),
  constraint room_metadata_mutation_receipts_digest_sha256 check (
    octet_length(request_digest) = 32
  ),
  constraint room_metadata_mutation_receipts_expiry_valid check (
    expires_at > completed_at and expires_at <= completed_at + interval '24 hours'
  )
);

create table private.message_revision_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  message_id uuid not null references public.messages (id) on delete cascade,
  revision_id uuid not null,
  expected_revision integer not null,
  revision_number integer not null,
  request_digest bytea not null,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint message_revision_mutation_receipts_revision_valid check (
    expected_revision between 0 and 99
    and revision_number = expected_revision + 1
  ),
  constraint message_revision_mutation_receipts_digest_sha256 check (
    octet_length(request_digest) = 32
  ),
  constraint message_revision_mutation_receipts_expiry_valid check (
    expires_at > completed_at
  )
);

create table private.reaction_removal_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  reaction_id uuid not null,
  removed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint reaction_removal_receipts_expiry_valid check (
    expires_at = removed_at + interval '24 hours'
  )
);

create table private.room_preference_mutation_receipts (
  actor_user_id uuid not null,
  client_mutation_id uuid not null,
  room_id uuid not null,
  requested_archive_state text not null,
  requested_pin_state text not null,
  requested_mute_state text not null,
  requested_muted_until timestamptz,
  archive_state text not null,
  pin_state text not null,
  mute_state text not null,
  muted_until timestamptz,
  completed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  foreign key (room_id, actor_user_id)
    references public.room_members (room_id, user_id)
    on delete cascade,
  constraint room_preference_mutation_receipts_requested_archive_valid check (
    requested_archive_state in ('ACTIVE', 'ARCHIVED')
  ),
  constraint room_preference_mutation_receipts_requested_pin_valid check (
    requested_pin_state in ('PINNED', 'UNPINNED')
  ),
  constraint room_preference_mutation_receipts_requested_mute_valid check (
    requested_mute_state in ('UNMUTED', 'MUTED_UNTIL', 'MUTED_FOREVER')
  ),
  constraint room_preference_mutation_receipts_requested_mute_complete check (
    (requested_mute_state = 'MUTED_UNTIL') = (requested_muted_until is not null)
  ),
  constraint room_preference_mutation_receipts_archive_state_valid check (
    archive_state in ('ACTIVE', 'ARCHIVED')
  ),
  constraint room_preference_mutation_receipts_pin_state_valid check (
    pin_state in ('PINNED', 'UNPINNED')
  ),
  constraint room_preference_mutation_receipts_mute_state_valid check (
    mute_state in ('UNMUTED', 'MUTED_UNTIL', 'MUTED_FOREVER')
  ),
  constraint room_preference_mutation_receipts_mute_state_complete check (
    (mute_state = 'MUTED_UNTIL') = (muted_until is not null)
  ),
  constraint room_preference_mutation_receipts_expiry_valid check (
    expires_at > completed_at and expires_at <= completed_at + interval '24 hours'
  )
);

create index room_metadata_envelopes_recipient_idx
on public.room_metadata_envelopes (recipient_device_id, room_id);
create index room_metadata_envelopes_sender_device_idx
on public.room_metadata_envelopes (sender_device_id, room_id);
create index message_revisions_expiry_idx
on public.message_revisions (expires_at, message_id);
create index message_revision_envelopes_recipient_idx
on public.message_revision_envelopes (recipient_device_id, revision_id);
create index room_metadata_mutation_receipts_expiry_idx
on private.room_metadata_mutation_receipts (expires_at, actor_user_id, client_mutation_id);
create index message_revision_mutation_receipts_expiry_idx
on private.message_revision_mutation_receipts (expires_at, actor_user_id, client_mutation_id);
create index reaction_removal_receipts_expiry_idx
on private.reaction_removal_receipts (expires_at, actor_user_id, client_mutation_id);
create index room_preference_mutation_receipts_expiry_idx
on private.room_preference_mutation_receipts (expires_at, actor_user_id, client_mutation_id);

insert into public.room_member_preferences (room_id, user_id)
select room_member.room_id, room_member.user_id
from public.room_members as room_member
on conflict (room_id, user_id) do nothing;

create function private.initialize_room_member_preferences()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.room_member_preferences (room_id, user_id)
  values (new.room_id, new.user_id)
  on conflict (room_id, user_id) do nothing;
  return new;
end;
$$;

create trigger room_members_initialize_preferences
after insert on public.room_members
for each row execute function private.initialize_room_member_preferences();

create function private.remove_departed_room_metadata_envelopes()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  delete from public.room_metadata_envelopes as metadata_envelope
  using public.devices as departed_device
  where metadata_envelope.room_id = old.room_id
    and metadata_envelope.recipient_device_id = departed_device.id
    and departed_device.user_id = old.user_id;
  return old;
end;
$$;

create trigger room_members_remove_metadata_envelopes
after delete on public.room_members
for each row execute function private.remove_departed_room_metadata_envelopes();

create function private.remove_revoked_device_metadata_recipient()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if old.revoked_at is null and new.revoked_at is not null then
    delete from public.room_metadata_envelopes as metadata_envelope
    where metadata_envelope.recipient_device_id = new.id;
  end if;
  return new;
end;
$$;

create trigger devices_remove_revoked_metadata_recipient
after update of revoked_at on public.devices
for each row execute function private.remove_revoked_device_metadata_recipient();

alter table public.room_metadata_envelopes enable row level security;
alter table public.message_revisions enable row level security;
alter table public.message_revision_envelopes enable row level security;
alter table public.room_member_preferences enable row level security;

create policy room_metadata_envelopes_select_recipient
on public.room_metadata_envelopes
for select
to authenticated
using (
  recipient_device_id = private.current_device_id()
  and private.is_active_room_member(room_id)
  and exists (
    select 1
    from public.rooms as room
    where room.id = room_metadata_envelopes.room_id
      and room.metadata_revision = room_metadata_envelopes.metadata_revision
  )
);

create policy message_revisions_select_current_member
on public.message_revisions
for select
to authenticated
using (
  expires_at > statement_timestamp()
  and private.can_access_message(message_id)
  and exists (
    select 1
    from public.messages as message
    where message.id = message_revisions.message_id
      and message.current_revision = message_revisions.revision_number
  )
);

create policy message_revision_envelopes_select_recipient
on public.message_revision_envelopes
for select
to authenticated
using (
  recipient_device_id = private.current_device_id()
  and exists (
    select 1
    from public.message_revisions as revision
    join public.messages as message on message.id = revision.message_id
    where revision.id = message_revision_envelopes.revision_id
      and revision.expires_at > statement_timestamp()
      and message.current_revision = revision.revision_number
      and private.can_access_message(message.id)
  )
);

create policy room_member_preferences_select_self
on public.room_member_preferences
for select
to authenticated
using (
  user_id = (select auth.uid())
  and private.current_device_id() is not null
  and private.is_active_room_member(room_id)
);

create function private.can_read_device_bundle(p_device_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.current_device_id() is not null
    and exists (
      select 1
      from public.devices as target_device
      where target_device.id = p_device_id
        and (
          (target_device.revoked_at is null and private.shares_room_with(target_device.user_id))
          or exists (
            select 1
            from public.messages as message
            where message.sender_device_id = target_device.id
              and private.can_access_message(message.id)
          )
          or exists (
            select 1
            from public.reactions as reaction
            where reaction.sender_device_id = target_device.id
              and reaction.expires_at > statement_timestamp()
              and private.can_access_message(reaction.message_id)
          )
          or exists (
            select 1
            from public.message_revisions as revision
            where revision.editor_device_id = target_device.id
              and revision.expires_at > statement_timestamp()
              and private.can_access_message(revision.message_id)
          )
          or exists (
            select 1
            from public.room_metadata_envelopes as metadata_envelope
            join public.rooms as room on room.id = metadata_envelope.room_id
            where metadata_envelope.sender_device_id = target_device.id
              and metadata_envelope.recipient_device_id = private.current_device_id()
              and metadata_envelope.metadata_revision = room.metadata_revision
              and private.is_active_room_member(metadata_envelope.room_id)
          )
        )
    );
$$;

drop policy devices_select_shared_room on public.devices;

create policy devices_select_for_current_crypto_context
on public.devices
for select
to authenticated
using (private.can_read_device_bundle(id));

create function private.set_room_metadata(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer,
  p_envelopes jsonb
)
returns table (
  room_id uuid,
  metadata_revision integer,
  updated_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  persisted_receipt private.room_metadata_mutation_receipts;
  current_metadata_revision integer;
  next_metadata_revision integer;
  mutation_time timestamptz := statement_timestamp();
  request_digest bytea;
begin
  if mutation_actor_user_id is null
    or actor_device_id is null
    or p_client_mutation_id is null
    or p_expected_revision is null
    or p_expected_revision not between 0 and 2147483646
  then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  request_digest := extensions.digest(
    convert_to(
      p_room_id::text || chr(31) || p_expected_revision::text || chr(31) || p_envelopes::text,
      'UTF8'
    ),
    'sha256'
  );

  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/room-metadata/' || p_client_mutation_id::text, 0)
  );

  select receipt.*
    into persisted_receipt
  from private.room_metadata_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > mutation_time;

  if found then
    if persisted_receipt.room_id <> p_room_id
      or persisted_receipt.expected_revision <> p_expected_revision
      or persisted_receipt.request_digest <> request_digest
    then
      raise exception using errcode = '23505', message = 'room metadata mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.room_id,
      persisted_receipt.metadata_revision,
      persisted_receipt.completed_at;
    return;
  end if;

  delete from private.room_metadata_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= mutation_time;

  select room.metadata_revision
    into current_metadata_revision
  from public.rooms as room
  where room.id = p_room_id
  for update;

  if not found or not private.can_manage_room(p_room_id) then
    raise exception using errcode = '42501', message = 'room metadata management is not authorized';
  end if;
  if current_metadata_revision <> p_expected_revision then
    raise exception using errcode = '40001', message = 'room metadata revision changed';
  end if;

  perform private.assert_complete_signal_envelopes(
    p_room_id,
    actor_device_id,
    p_envelopes,
    16384
  );

  next_metadata_revision := current_metadata_revision + 1;
  update public.rooms as room
  set
    metadata_revision = next_metadata_revision,
    metadata_updated_at = mutation_time
  where room.id = p_room_id;

  delete from public.room_metadata_envelopes as metadata_envelope
  where metadata_envelope.room_id = p_room_id;

  insert into public.room_metadata_envelopes (
    room_id,
    metadata_revision,
    sender_user_id,
    sender_device_id,
    recipient_device_id,
    protocol_adapter_version,
    signal_message_type,
    ciphertext,
    created_at
  )
  select
    p_room_id,
    next_metadata_revision,
    mutation_actor_user_id,
    actor_device_id,
    envelope.recipient_device_id,
    envelope.protocol_adapter_version,
    envelope.signal_message_type,
    private.decode_bounded_hex(envelope.ciphertext_hex, 1, 16384, 'ciphertext_hex'),
    mutation_time
  from jsonb_to_recordset(p_envelopes) as envelope(
    recipient_device_id uuid,
    protocol_adapter_version smallint,
    signal_message_type text,
    ciphertext_hex text
  );

  insert into private.room_metadata_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    room_id,
    expected_revision,
    metadata_revision,
    request_digest,
    completed_at,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_mutation_id,
    p_room_id,
    p_expected_revision,
    next_metadata_revision,
    request_digest,
    mutation_time,
    mutation_time + interval '24 hours'
  ) returning * into strict persisted_receipt;

  return query select p_room_id, next_metadata_revision, mutation_time;
end;
$$;

create function public.set_room_metadata(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer,
  p_envelopes jsonb
)
returns table (
  room_id uuid,
  metadata_revision integer,
  updated_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.set_room_metadata(
    p_room_id,
    p_client_mutation_id,
    p_expected_revision,
    p_envelopes
  );
$$;

create function private.edit_message(
  p_message_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer,
  p_envelopes jsonb
)
returns table (
  message_id uuid,
  revision_id uuid,
  revision_number integer,
  edited_at timestamptz,
  expires_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  persisted_receipt private.message_revision_mutation_receipts;
  parent_message public.messages;
  current_membership_epoch integer;
  created_revision public.message_revisions;
  mutation_time timestamptz := statement_timestamp();
  request_digest bytea;
begin
  if mutation_actor_user_id is null
    or actor_device_id is null
    or p_client_mutation_id is null
    or p_expected_revision is null
    or p_expected_revision not between 0 and 99
  then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  request_digest := extensions.digest(
    convert_to(
      p_message_id::text || chr(31) || p_expected_revision::text || chr(31) || p_envelopes::text,
      'UTF8'
    ),
    'sha256'
  );

  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/message-edit/' || p_client_mutation_id::text, 0)
  );

  select receipt.*
    into persisted_receipt
  from private.message_revision_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > mutation_time;

  if found then
    if persisted_receipt.message_id <> p_message_id
      or persisted_receipt.expected_revision <> p_expected_revision
      or persisted_receipt.request_digest <> request_digest
    then
      raise exception using errcode = '23505', message = 'message edit mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.message_id,
      persisted_receipt.revision_id,
      persisted_receipt.revision_number,
      persisted_receipt.completed_at,
      persisted_receipt.expires_at;
    return;
  end if;

  delete from private.message_revision_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= mutation_time;

  select message.*
    into parent_message
  from public.messages as message
  where message.id = p_message_id
  for update;

  if not found
    or parent_message.sender_user_id <> mutation_actor_user_id
    or parent_message.expires_at <= mutation_time
    or exists (
      select 1
      from private.message_deletion_requests as deletion_request
      where deletion_request.message_id = p_message_id
    )
    or not private.is_active_room_member(parent_message.room_id)
  then
    raise exception using errcode = '42501', message = 'message editing is not authorized';
  end if;

  select room.membership_epoch
    into strict current_membership_epoch
  from public.rooms as room
  where room.id = parent_message.room_id
  for share;

  if parent_message.current_revision <> p_expected_revision then
    raise exception using errcode = '40001', message = 'message revision changed';
  end if;

  perform private.assert_complete_signal_envelopes(
    parent_message.room_id,
    actor_device_id,
    p_envelopes,
    262144
  );

  update public.messages as message
  set current_revision = p_expected_revision + 1
  where message.id = parent_message.id;

  delete from public.message_envelopes as initial_envelope
  where initial_envelope.message_id = parent_message.id;
  delete from public.message_revisions as previous_revision
  where previous_revision.message_id = parent_message.id;
  delete from public.message_receipts as previous_receipt
  where previous_receipt.message_id = parent_message.id;

  insert into public.message_revisions (
    message_id,
    editor_user_id,
    editor_device_id,
    revision_number,
    membership_epoch,
    created_at,
    expires_at
  ) values (
    parent_message.id,
    mutation_actor_user_id,
    actor_device_id,
    p_expected_revision + 1,
    current_membership_epoch,
    mutation_time,
    parent_message.expires_at
  ) returning * into strict created_revision;

  insert into public.message_revision_envelopes (
    revision_id,
    recipient_device_id,
    protocol_adapter_version,
    signal_message_type,
    ciphertext,
    created_at
  )
  select
    created_revision.id,
    envelope.recipient_device_id,
    envelope.protocol_adapter_version,
    envelope.signal_message_type,
    private.decode_bounded_hex(envelope.ciphertext_hex, 1, 262144, 'ciphertext_hex'),
    mutation_time
  from jsonb_to_recordset(p_envelopes) as envelope(
    recipient_device_id uuid,
    protocol_adapter_version smallint,
    signal_message_type text,
    ciphertext_hex text
  );

  insert into private.message_revision_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    message_id,
    revision_id,
    expected_revision,
    revision_number,
    request_digest,
    completed_at,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_mutation_id,
    parent_message.id,
    created_revision.id,
    p_expected_revision,
    created_revision.revision_number,
    request_digest,
    mutation_time,
    parent_message.expires_at
  ) returning * into strict persisted_receipt;

  return query
  select
    parent_message.id,
    created_revision.id,
    created_revision.revision_number,
    created_revision.created_at,
    created_revision.expires_at;
end;
$$;

create function public.edit_message(
  p_message_id uuid,
  p_client_mutation_id uuid,
  p_expected_revision integer,
  p_envelopes jsonb
)
returns table (
  message_id uuid,
  revision_id uuid,
  revision_number integer,
  edited_at timestamptz,
  expires_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.edit_message(
    p_message_id,
    p_client_mutation_id,
    p_expected_revision,
    p_envelopes
  );
$$;

create function private.remove_reaction(
  p_reaction_id uuid,
  p_client_mutation_id uuid
)
returns table (reaction_id uuid, removed_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  persisted_receipt private.reaction_removal_receipts;
  persisted_reaction public.reactions;
  removal_time timestamptz := statement_timestamp();
begin
  if mutation_actor_user_id is null or actor_device_id is null or p_client_mutation_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/reaction-removal/' || p_client_mutation_id::text, 0)
  );

  select receipt.*
    into persisted_receipt
  from private.reaction_removal_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > removal_time;

  if found then
    if persisted_receipt.reaction_id <> p_reaction_id then
      raise exception using errcode = '23505', message = 'reaction removal mutation id was already used';
    end if;
    return query select persisted_receipt.reaction_id, persisted_receipt.removed_at;
    return;
  end if;

  delete from private.reaction_removal_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= removal_time;

  select reaction.*
    into persisted_reaction
  from public.reactions as reaction
  where reaction.id = p_reaction_id
  for update;

  if not found
    or persisted_reaction.sender_user_id <> mutation_actor_user_id
    or persisted_reaction.expires_at <= removal_time
    or not private.can_access_message(persisted_reaction.message_id)
  then
    raise exception using errcode = '42501', message = 'reaction removal is not authorized';
  end if;

  delete from public.reactions as reaction
  where reaction.id = p_reaction_id;

  insert into private.reaction_removal_receipts (
    actor_user_id,
    client_mutation_id,
    reaction_id,
    removed_at,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_mutation_id,
    p_reaction_id,
    removal_time,
    removal_time + interval '24 hours'
  ) returning * into strict persisted_receipt;

  return query select persisted_receipt.reaction_id, persisted_receipt.removed_at;
end;
$$;

create function public.remove_reaction(
  p_reaction_id uuid,
  p_client_mutation_id uuid
)
returns table (reaction_id uuid, removed_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select * from private.remove_reaction(p_reaction_id, p_client_mutation_id);
$$;

create function private.set_room_preferences(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_archive_state text,
  p_pin_state text,
  p_mute_state text,
  p_muted_until timestamptz
)
returns table (
  room_id uuid,
  archive_state text,
  pin_state text,
  mute_state text,
  muted_until timestamptz,
  updated_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  persisted_preference public.room_member_preferences;
  persisted_receipt private.room_preference_mutation_receipts;
  mutation_time timestamptz := statement_timestamp();
begin
  if mutation_actor_user_id is null or actor_device_id is null or p_client_mutation_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  if p_archive_state is null
    or p_pin_state is null
    or p_mute_state is null
    or p_archive_state not in ('ACTIVE', 'ARCHIVED')
    or p_pin_state not in ('PINNED', 'UNPINNED')
    or p_mute_state not in ('UNMUTED', 'MUTED_UNTIL', 'MUTED_FOREVER')
    or (p_mute_state = 'MUTED_UNTIL') <> (p_muted_until is not null)
  then
    raise exception using errcode = '22023', message = 'room preference mutation is invalid';
  end if;

  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/room-preference/' || p_client_mutation_id::text, 0)
  );

  select receipt.*
    into persisted_receipt
  from private.room_preference_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > mutation_time;

  if found then
    if persisted_receipt.room_id <> p_room_id
      or persisted_receipt.requested_archive_state <> p_archive_state
      or persisted_receipt.requested_pin_state <> p_pin_state
      or persisted_receipt.requested_mute_state <> p_mute_state
      or persisted_receipt.requested_muted_until is distinct from p_muted_until
    then
      raise exception using errcode = '23505', message = 'room preference mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.room_id,
      persisted_receipt.archive_state,
      persisted_receipt.pin_state,
      persisted_receipt.mute_state,
      persisted_receipt.muted_until,
      persisted_receipt.completed_at;
    return;
  end if;

  delete from private.room_preference_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= mutation_time;

  if not private.is_active_room_member(p_room_id) then
    raise exception using errcode = '42501', message = 'current room membership is required';
  end if;
  if p_mute_state = 'MUTED_UNTIL'
    and (
      p_muted_until <= mutation_time
      or p_muted_until > mutation_time + interval '1 year'
    )
  then
    raise exception using errcode = '22023', message = 'mute expiry must be within one year';
  end if;

  select preference.*
    into persisted_preference
  from public.room_member_preferences as preference
  where preference.room_id = p_room_id
    and preference.user_id = mutation_actor_user_id
  for update;

  if not found then
    insert into public.room_member_preferences (room_id, user_id)
    values (p_room_id, mutation_actor_user_id)
    returning * into strict persisted_preference;
  end if;

  update public.room_member_preferences as preference
  set
    archive_state = p_archive_state,
    pin_state = p_pin_state,
    mute_state = p_mute_state,
    muted_until = p_muted_until,
    updated_at = mutation_time
  where preference.room_id = p_room_id
    and preference.user_id = mutation_actor_user_id
  returning preference.* into strict persisted_preference;

  insert into private.room_preference_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    room_id,
    requested_archive_state,
    requested_pin_state,
    requested_mute_state,
    requested_muted_until,
    archive_state,
    pin_state,
    mute_state,
    muted_until,
    completed_at,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_mutation_id,
    p_room_id,
    p_archive_state,
    p_pin_state,
    p_mute_state,
    p_muted_until,
    persisted_preference.archive_state,
    persisted_preference.pin_state,
    persisted_preference.mute_state,
    persisted_preference.muted_until,
    mutation_time,
    mutation_time + interval '24 hours'
  ) returning * into strict persisted_receipt;

  return query
  select
    persisted_preference.room_id,
    persisted_preference.archive_state,
    persisted_preference.pin_state,
    persisted_preference.mute_state,
    persisted_preference.muted_until,
    persisted_preference.updated_at;
end;
$$;

create function public.set_room_preferences(
  p_room_id uuid,
  p_client_mutation_id uuid,
  p_archive_state text,
  p_pin_state text,
  p_mute_state text,
  p_muted_until timestamptz default null
)
returns table (
  room_id uuid,
  archive_state text,
  pin_state text,
  mute_state text,
  muted_until timestamptz,
  updated_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.set_room_preferences(
    p_room_id,
    p_client_mutation_id,
    p_archive_state,
    p_pin_state,
    p_mute_state,
    p_muted_until
  );
$$;

create function private.list_room_recipient_devices(p_room_id uuid)
returns table (
  device_id uuid,
  user_id uuid,
  protocol_adapter_version smallint,
  signal_device_id smallint
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  recipient_count integer;
begin
  if private.current_device_id() is null or not private.is_active_room_member(p_room_id) then
    raise exception using errcode = '42501', message = 'current room membership is required';
  end if;

  select count(*)
    into recipient_count
  from public.room_members as room_member
  join public.devices as device on device.user_id = room_member.user_id
  where room_member.room_id = p_room_id
    and device.revoked_at is null;

  if recipient_count not between 1 and 129 then
    raise exception using errcode = '23514', message = 'room recipient device count is invalid';
  end if;

  return query
  select
    device.id,
    device.user_id,
    device.protocol_adapter_version,
    device.signal_device_id
  from public.room_members as room_member
  join public.devices as device on device.user_id = room_member.user_id
  where room_member.room_id = p_room_id
    and device.revoked_at is null
  order by device.user_id, device.signal_device_id, device.id;
end;
$$;

create function public.list_room_recipient_devices(p_room_id uuid)
returns table (
  device_id uuid,
  user_id uuid,
  protocol_adapter_version smallint,
  signal_device_id smallint
)
language sql
stable
security invoker
set search_path = ''
as $$
  select * from private.list_room_recipient_devices(p_room_id);
$$;

create function private.list_current_account_recipient_devices()
returns table (
  device_id uuid,
  user_id uuid,
  protocol_adapter_version smallint,
  signal_device_id smallint
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  actor_user_id uuid := auth.uid();
  recipient_count integer;
begin
  if actor_user_id is null or private.current_device_id() is null then
    raise exception using errcode = '42501', message = 'an active device session is required';
  end if;

  select count(*)
    into recipient_count
  from public.devices as device
  where device.user_id = actor_user_id
    and device.revoked_at is null;

  if recipient_count not between 1 and 129 then
    raise exception using errcode = '54000', message = 'active account device fan-out exceeds 129 devices';
  end if;

  return query
  select
    device.id,
    device.user_id,
    device.protocol_adapter_version,
    device.signal_device_id
  from public.devices as device
  where device.user_id = actor_user_id
    and device.revoked_at is null
  order by device.signal_device_id, device.id;
end;
$$;

create function public.list_current_account_recipient_devices()
returns table (
  device_id uuid,
  user_id uuid,
  protocol_adapter_version smallint,
  signal_device_id smallint
)
language sql
stable
security invoker
set search_path = ''
as $$
  select * from private.list_current_account_recipient_devices();
$$;

create table private.room_creation_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  room_id uuid not null unique references public.rooms (id) on delete cascade,
  request_digest bytea not null,
  membership_epoch integer not null,
  metadata_revision integer not null,
  created_at timestamptz not null,
  metadata_updated_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint room_creation_mutation_receipts_digest_sha256 check (
    octet_length(request_digest) = 32
  ),
  constraint room_creation_mutation_receipts_epoch_valid check (
    membership_epoch between 1 and 2147483647
  ),
  constraint room_creation_mutation_receipts_metadata_revision_valid check (
    metadata_revision between 1 and 2147483647
  ),
  constraint room_creation_mutation_receipts_expiry_valid check (
    expires_at = metadata_updated_at + interval '24 hours'
  )
);

create index room_creation_mutation_receipts_expiry_idx
on private.room_creation_mutation_receipts (expires_at, actor_user_id, client_mutation_id);

create table private.message_send_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  message_id uuid not null unique references public.messages (id) on delete cascade,
  room_id uuid not null references public.rooms (id) on delete cascade,
  reply_to_message_id uuid,
  request_digest bytea not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint message_send_mutation_receipts_digest_sha256 check (
    octet_length(request_digest) = 32
  )
);

create table private.reaction_send_mutation_receipts (
  actor_user_id uuid not null references public.profiles (user_id) on delete cascade,
  client_mutation_id uuid not null,
  reaction_id uuid not null unique references public.reactions (id) on delete cascade,
  message_id uuid not null references public.messages (id) on delete cascade,
  request_digest bytea not null,
  expires_at timestamptz not null,
  primary key (actor_user_id, client_mutation_id),
  constraint reaction_send_mutation_receipts_digest_sha256 check (
    octet_length(request_digest) = 32
  )
);

create function private.create_room_with_metadata(
  p_room_kind text,
  p_retention_seconds integer,
  p_client_mutation_id uuid,
  p_envelopes jsonb
)
returns table (
  room_id uuid,
  membership_epoch integer,
  metadata_revision integer,
  created_at timestamptz,
  metadata_updated_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  persisted_receipt private.room_creation_mutation_receipts;
  created_room public.rooms;
  mutation_time timestamptz := statement_timestamp();
  request_digest bytea;
begin
  if mutation_actor_user_id is null or actor_device_id is null or p_client_mutation_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  request_digest := extensions.digest(
    convert_to(
      p_room_kind || chr(31) || p_retention_seconds::text || chr(31) || p_envelopes::text,
      'UTF8'
    ),
    'sha256'
  );

  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/room-creation/' || p_client_mutation_id::text, 0)
  );

  select receipt.*
    into persisted_receipt
  from private.room_creation_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_mutation_id
    and receipt.expires_at > mutation_time;

  if found then
    if persisted_receipt.request_digest <> request_digest then
      raise exception using errcode = '23505', message = 'room creation mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.room_id,
      persisted_receipt.membership_epoch,
      persisted_receipt.metadata_revision,
      persisted_receipt.created_at,
      persisted_receipt.metadata_updated_at;
    return;
  end if;

  delete from private.room_creation_mutation_receipts as expired_receipt
  where expired_receipt.actor_user_id = mutation_actor_user_id
    and expired_receipt.client_mutation_id = p_client_mutation_id
    and expired_receipt.expires_at <= mutation_time;

  insert into public.rooms (
    owner_user_id,
    room_kind,
    retention_seconds,
    metadata_revision,
    metadata_updated_at
  ) values (
    mutation_actor_user_id,
    p_room_kind,
    p_retention_seconds,
    1,
    mutation_time
  ) returning * into strict created_room;

  insert into public.room_members (room_id, user_id, member_role)
  values (created_room.id, mutation_actor_user_id, 'OWNER');

  perform private.assert_complete_signal_envelopes(
    created_room.id,
    actor_device_id,
    p_envelopes,
    16384
  );

  insert into public.room_metadata_envelopes (
    room_id,
    metadata_revision,
    sender_user_id,
    sender_device_id,
    recipient_device_id,
    protocol_adapter_version,
    signal_message_type,
    ciphertext,
    created_at
  )
  select
    created_room.id,
    1,
    mutation_actor_user_id,
    actor_device_id,
    envelope.recipient_device_id,
    envelope.protocol_adapter_version,
    envelope.signal_message_type,
    private.decode_bounded_hex(envelope.ciphertext_hex, 1, 16384, 'ciphertext_hex'),
    mutation_time
  from jsonb_to_recordset(p_envelopes) as envelope(
    recipient_device_id uuid,
    protocol_adapter_version smallint,
    signal_message_type text,
    ciphertext_hex text
  );

  insert into private.room_creation_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    room_id,
    request_digest,
    membership_epoch,
    metadata_revision,
    created_at,
    metadata_updated_at,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_mutation_id,
    created_room.id,
    request_digest,
    created_room.membership_epoch,
    1,
    created_room.created_at,
    mutation_time,
    mutation_time + interval '24 hours'
  ) returning * into strict persisted_receipt;

  return query
  select
    created_room.id,
    created_room.membership_epoch,
    1,
    created_room.created_at,
    mutation_time;
end;
$$;

create function public.create_room_with_metadata(
  p_room_kind text,
  p_client_mutation_id uuid,
  p_envelopes jsonb,
  p_retention_seconds integer default 86400
)
returns table (
  room_id uuid,
  membership_epoch integer,
  metadata_revision integer,
  created_at timestamptz,
  metadata_updated_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.create_room_with_metadata(
    p_room_kind,
    p_retention_seconds,
    p_client_mutation_id,
    p_envelopes
  );
$$;

create or replace function private.send_message(
  p_room_id uuid,
  p_client_message_id uuid,
  p_reply_to_message_id uuid,
  p_envelopes jsonb
)
returns table (message_id uuid, expires_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  locked_room public.rooms;
  persisted_message public.messages;
  persisted_receipt private.message_send_mutation_receipts;
  request_digest bytea;
begin
  if mutation_actor_user_id is null or actor_device_id is null or p_client_message_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  request_digest := extensions.digest(
    convert_to(
      p_room_id::text || chr(31)
      || coalesce(p_reply_to_message_id::text, '') || chr(31)
      || p_envelopes::text,
      'UTF8'
    ),
    'sha256'
  );

  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/message-send/' || p_client_message_id::text, 0)
  );

  select receipt.*
    into persisted_receipt
  from private.message_send_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_message_id
    and receipt.expires_at > statement_timestamp();

  if found then
    if persisted_receipt.room_id <> p_room_id
      or persisted_receipt.reply_to_message_id is distinct from p_reply_to_message_id
      or persisted_receipt.request_digest <> request_digest
    then
      raise exception using errcode = '23505', message = 'message mutation id was already used';
    end if;
    return query select persisted_receipt.message_id, persisted_receipt.expires_at;
    return;
  end if;

  if exists (
    select 1
    from public.messages as existing_message
    where existing_message.sender_user_id = mutation_actor_user_id
      and existing_message.client_message_id = p_client_message_id
  ) then
    raise exception using errcode = '23505', message = 'message mutation lacks a verifiable receipt';
  end if;

  select room.*
    into strict locked_room
  from public.rooms as room
  where room.id = p_room_id
  for share;

  if not private.is_active_room_member(p_room_id) then
    raise exception using errcode = '42501', message = 'current room membership is required';
  end if;

  if p_reply_to_message_id is not null and not exists (
    select 1
    from public.messages as replied_to
    where replied_to.id = p_reply_to_message_id
      and replied_to.room_id = p_room_id
      and replied_to.expires_at > statement_timestamp()
      and private.can_access_message(replied_to.id)
      and not exists (
        select 1
        from private.message_deletion_requests as deletion_request
        where deletion_request.message_id = replied_to.id
      )
  ) then
    raise exception using errcode = '22023', message = 'reply target is unavailable';
  end if;

  perform private.assert_complete_signal_envelopes(p_room_id, actor_device_id, p_envelopes, 262144);

  insert into public.messages (
    room_id,
    sender_user_id,
    sender_device_id,
    client_message_id,
    membership_epoch,
    expires_at
  ) values (
    locked_room.id,
    mutation_actor_user_id,
    actor_device_id,
    p_client_message_id,
    locked_room.membership_epoch,
    statement_timestamp() + make_interval(secs => locked_room.retention_seconds)
  ) returning * into strict persisted_message;

  insert into public.message_envelopes (
    message_id,
    recipient_device_id,
    protocol_adapter_version,
    signal_message_type,
    ciphertext
  )
  select
    persisted_message.id,
    envelope.recipient_device_id,
    envelope.protocol_adapter_version,
    envelope.signal_message_type,
    private.decode_bounded_hex(envelope.ciphertext_hex, 1, 262144, 'ciphertext_hex')
  from jsonb_to_recordset(p_envelopes) as envelope(
    recipient_device_id uuid,
    protocol_adapter_version smallint,
    signal_message_type text,
    ciphertext_hex text
  );

  if p_reply_to_message_id is not null then
    insert into public.message_reply_links (message_id, replied_to_message_id)
    values (persisted_message.id, p_reply_to_message_id);
  end if;

  insert into private.message_send_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    message_id,
    room_id,
    reply_to_message_id,
    request_digest,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_message_id,
    persisted_message.id,
    p_room_id,
    p_reply_to_message_id,
    request_digest,
    persisted_message.expires_at
  );

  return query select persisted_message.id, persisted_message.expires_at;
end;
$$;

create or replace function private.send_reaction(
  p_message_id uuid,
  p_client_reaction_id uuid,
  p_envelopes jsonb
)
returns table (reaction_id uuid, expires_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  mutation_actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  parent_message public.messages;
  locked_room public.rooms;
  persisted_reaction public.reactions;
  persisted_receipt private.reaction_send_mutation_receipts;
  request_digest bytea;
begin
  if mutation_actor_user_id is null or actor_device_id is null or p_client_reaction_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  request_digest := extensions.digest(
    convert_to(p_message_id::text || chr(31) || p_envelopes::text, 'UTF8'),
    'sha256'
  );

  perform pg_advisory_xact_lock(
    hashtextextended(mutation_actor_user_id::text || '/reaction-send/' || p_client_reaction_id::text, 0)
  );

  select receipt.*
    into persisted_receipt
  from private.reaction_send_mutation_receipts as receipt
  where receipt.actor_user_id = mutation_actor_user_id
    and receipt.client_mutation_id = p_client_reaction_id
    and receipt.expires_at > statement_timestamp();

  if found then
    if persisted_receipt.message_id <> p_message_id
      or persisted_receipt.request_digest <> request_digest
    then
      raise exception using errcode = '23505', message = 'reaction mutation id was already used';
    end if;
    return query select persisted_receipt.reaction_id, persisted_receipt.expires_at;
    return;
  end if;

  if exists (
    select 1
    from public.reactions as existing_reaction
    where existing_reaction.sender_user_id = mutation_actor_user_id
      and existing_reaction.client_reaction_id = p_client_reaction_id
  ) then
    raise exception using errcode = '23505', message = 'reaction mutation lacks a verifiable receipt';
  end if;

  select message.*
    into parent_message
  from public.messages as message
  where message.id = p_message_id
    and message.expires_at > statement_timestamp()
    and not exists (
      select 1
      from private.message_deletion_requests as deletion_request
      where deletion_request.message_id = message.id
    );

  if not found or not private.can_access_message(p_message_id) then
    raise exception using errcode = '42501', message = 'message reaction is not authorized';
  end if;

  select room.*
    into strict locked_room
  from public.rooms as room
  where room.id = parent_message.room_id
  for share;

  if not private.is_active_room_member(parent_message.room_id) then
    raise exception using errcode = '42501', message = 'current room membership is required';
  end if;

  perform private.assert_complete_signal_envelopes(
    parent_message.room_id,
    actor_device_id,
    p_envelopes,
    16384
  );

  insert into public.reactions (
    message_id,
    sender_user_id,
    sender_device_id,
    client_reaction_id,
    membership_epoch,
    expires_at
  ) values (
    parent_message.id,
    mutation_actor_user_id,
    actor_device_id,
    p_client_reaction_id,
    locked_room.membership_epoch,
    parent_message.expires_at
  ) returning * into strict persisted_reaction;

  insert into public.reaction_envelopes (
    reaction_id,
    recipient_device_id,
    protocol_adapter_version,
    signal_message_type,
    ciphertext
  )
  select
    persisted_reaction.id,
    envelope.recipient_device_id,
    envelope.protocol_adapter_version,
    envelope.signal_message_type,
    private.decode_bounded_hex(envelope.ciphertext_hex, 1, 16384, 'ciphertext_hex')
  from jsonb_to_recordset(p_envelopes) as envelope(
    recipient_device_id uuid,
    protocol_adapter_version smallint,
    signal_message_type text,
    ciphertext_hex text
  );

  insert into private.reaction_send_mutation_receipts (
    actor_user_id,
    client_mutation_id,
    reaction_id,
    message_id,
    request_digest,
    expires_at
  ) values (
    mutation_actor_user_id,
    p_client_reaction_id,
    persisted_reaction.id,
    p_message_id,
    request_digest,
    persisted_reaction.expires_at
  );

  return query select persisted_reaction.id, persisted_reaction.expires_at;
end;
$$;

create index rooms_owner_user_id_idx on public.rooms (owner_user_id);
create index messages_sender_device_id_idx on public.messages (sender_device_id);
create index reactions_sender_device_id_idx on public.reactions (sender_device_id);
create index message_reply_links_replied_to_message_id_idx
on public.message_reply_links (replied_to_message_id);
create index message_receipts_recipient_device_id_idx
on public.message_receipts (recipient_device_id, message_id);
create index typing_state_device_id_idx on public.typing_state (device_id, room_id);
create index attachments_uploader_device_id_idx on public.attachments (uploader_device_id);
create index account_registration_invites_issued_by_user_id_idx
on private.account_registration_invites (issued_by_user_id);
create index account_registration_receipts_user_id_idx
on private.account_registration_receipts (user_id);
create index room_membership_invites_room_id_idx
on private.room_membership_invites (room_id);
create index room_membership_invites_issued_by_user_id_idx
on private.room_membership_invites (issued_by_user_id);
create index room_membership_invite_receipts_room_id_idx
on private.room_membership_invite_receipts (room_id);
create index room_membership_invite_receipts_user_id_idx
on private.room_membership_invite_receipts (user_id);
create index message_deletion_requests_requested_by_user_id_idx
on private.message_deletion_requests (requested_by_user_id);
create index room_metadata_envelopes_sender_user_id_idx
on public.room_metadata_envelopes (sender_user_id, room_id);
create index message_revisions_editor_user_id_idx
on public.message_revisions (editor_user_id, message_id);
create index message_revisions_editor_device_id_idx
on public.message_revisions (editor_device_id, message_id);
create index room_metadata_mutation_receipts_room_id_idx
on private.room_metadata_mutation_receipts (room_id);
create index message_revision_mutation_receipts_message_id_idx
on private.message_revision_mutation_receipts (message_id);
create index room_preference_mutation_receipts_room_user_idx
on private.room_preference_mutation_receipts (room_id, actor_user_id);
create index message_send_mutation_receipts_room_id_idx
on private.message_send_mutation_receipts (room_id);
create index message_send_mutation_receipts_reply_to_idx
on private.message_send_mutation_receipts (reply_to_message_id);
create index reaction_send_mutation_receipts_message_id_idx
on private.reaction_send_mutation_receipts (message_id);

drop policy profiles_update_self on public.profiles;
create policy profiles_update_self
on public.profiles
for update
to authenticated
using (
  user_id = (select auth.uid())
  and private.current_device_id() is not null
)
with check (
  user_id = (select auth.uid())
  and private.current_device_id() is not null
);

drop policy message_receipts_insert_recipient on public.message_receipts;
create policy message_receipts_insert_recipient
on public.message_receipts
for insert
to authenticated
with check (
  recipient_device_id = private.current_device_id()
  and private.can_access_message(message_id)
  and exists (
    select 1
    from public.message_envelopes as message_envelope
    where message_envelope.message_id = message_receipts.message_id
      and message_envelope.recipient_device_id = message_receipts.recipient_device_id
    union all
    select 1
    from public.message_revisions as revision
    join public.message_revision_envelopes as revision_envelope
      on revision_envelope.revision_id = revision.id
    join public.messages as message on message.id = revision.message_id
    where revision.message_id = message_receipts.message_id
      and revision_envelope.recipient_device_id = message_receipts.recipient_device_id
      and message.current_revision = revision.revision_number
  )
  and (
    receipt_kind = 'DELIVERED'
    or exists (
      select 1
      from public.profiles as profile
      where profile.user_id = (select auth.uid())
        and profile.read_receipts_enabled
    )
  )
);

drop policy presence_state_select_room_peer_before_expiry on public.presence_state;
create policy presence_state_select_room_peer_before_expiry
on public.presence_state
for select
to authenticated
using (
  expires_at > statement_timestamp()
  and exists (
    select 1
    from public.devices as present_device
    join public.profiles as present_profile on present_profile.user_id = present_device.user_id
    join public.room_members as present_membership on present_membership.user_id = present_device.user_id
    join public.room_members as viewer_membership
      on viewer_membership.room_id = present_membership.room_id
     and viewer_membership.user_id = (select auth.uid())
    where present_device.id = presence_state.device_id
      and present_device.revoked_at is null
      and present_profile.presence_sharing_enabled
  )
  and private.current_device_id() is not null
);

drop policy attachments_insert_sender on public.attachments;
create policy attachments_insert_sender
on public.attachments
for insert
to authenticated
with check (
  uploader_device_id = private.current_device_id()
  and exists (
    select 1
    from public.messages as message
    where message.id = attachments.message_id
      and message.sender_user_id = (select auth.uid())
      and message.sender_device_id = private.current_device_id()
      and message.expires_at > statement_timestamp()
  )
);

alter table private.purge_receipts
add column mutation_receipts_deleted integer not null default 0,
add constraint purge_receipts_mutation_count_valid check (mutation_receipts_deleted >= 0);

drop function private.purge_expired_relational_data(integer, uuid);

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
  purge_started_at timestamptz := clock_timestamp();
  purge_completed_at timestamptz;
  purged_message_count integer;
  purged_typing_count integer;
  purged_presence_count integer;
  purged_invite_count integer;
  purged_device_reservation_count integer;
  purged_mutation_receipt_count integer;
begin
  if p_batch_limit not between 1 and 1000 then
    raise exception using errcode = '22023', message = 'purge batch limit must be between one and 1000';
  end if;

  with expired_messages as (
    select message.id
    from public.messages as message
    where (
        message.expires_at <= statement_timestamp()
        or exists (
          select 1
          from private.message_deletion_requests as deletion_request
          where deletion_request.message_id = message.id
        )
      )
      and not exists (
        select 1
        from public.attachments as attachment
        where attachment.message_id = message.id
      )
    order by message.expires_at, message.id
    for update skip locked
    limit p_batch_limit
  ), deleted_messages as (
    delete from public.messages as message
    using expired_messages
    where message.id = expired_messages.id
    returning message.id
  )
  select count(*) into purged_message_count from deleted_messages;

  with expired_typing_rows as (
    select typing.ctid
    from public.typing_state as typing
    where typing.expires_at <= statement_timestamp()
    order by typing.expires_at
    for update skip locked
    limit p_batch_limit
  ), deleted_typing_rows as (
    delete from public.typing_state as typing
    using expired_typing_rows
    where typing.ctid = expired_typing_rows.ctid
    returning typing.room_id
  )
  select count(*) into purged_typing_count from deleted_typing_rows;

  with expired_presence_rows as (
    select presence.ctid
    from public.presence_state as presence
    where presence.expires_at <= statement_timestamp()
    order by presence.expires_at
    for update skip locked
    limit p_batch_limit
  ), deleted_presence_rows as (
    delete from public.presence_state as presence
    using expired_presence_rows
    where presence.ctid = expired_presence_rows.ctid
    returning presence.device_id
  )
  select count(*) into purged_presence_count from deleted_presence_rows;

  with expired_account_invites as (
    delete from private.account_registration_invites
    where id in (
      select invite.id
      from private.account_registration_invites as invite
      where invite.expires_at <= statement_timestamp()
      order by invite.expires_at, invite.id
      for update skip locked
      limit p_batch_limit
    )
    returning id
  ), expired_room_invites as (
    delete from private.room_membership_invites
    where id in (
      select invite.id
      from private.room_membership_invites as invite
      where invite.expires_at <= statement_timestamp()
      order by invite.expires_at, invite.id
      for update skip locked
      limit p_batch_limit
    )
    returning id
  ), expired_bootstrap_capabilities as (
    delete from private.bootstrap_capabilities
    where expires_at <= statement_timestamp()
    returning singleton
  ), expired_account_receipts as (
    delete from private.account_registration_receipts
    where redemption_id in (
      select receipt.redemption_id
      from private.account_registration_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.redemption_id
      for update skip locked
      limit p_batch_limit
    )
    returning redemption_id
  ), expired_room_receipts as (
    delete from private.room_membership_invite_receipts
    where redemption_id in (
      select receipt.redemption_id
      from private.room_membership_invite_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.redemption_id
      for update skip locked
      limit p_batch_limit
    )
    returning redemption_id
  )
  select
    (select count(*) from expired_account_invites)
    + (select count(*) from expired_room_invites)
    + (select count(*) from expired_bootstrap_capabilities)
    + (select count(*) from expired_account_receipts)
    + (select count(*) from expired_room_receipts)
  into purged_invite_count;

  delete from private.account_access_rate_limits
  where expires_at <= statement_timestamp();

  with expired_device_reservations as (
    delete from private.device_registration_reservations
    where auth_session_id in (
      select reservation.auth_session_id
      from private.device_registration_reservations as reservation
      where reservation.expires_at <= statement_timestamp()
      order by reservation.expires_at, reservation.auth_session_id
      for update skip locked
      limit p_batch_limit
    )
    returning auth_session_id
  )
  select count(*)
    into purged_device_reservation_count
  from expired_device_reservations;

  with expired_room_creation_receipts as (
    delete from private.room_creation_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.room_creation_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_room_metadata_receipts as (
    delete from private.room_metadata_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.room_metadata_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_message_revision_receipts as (
    delete from private.message_revision_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.message_revision_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_reaction_removal_receipts as (
    delete from private.reaction_removal_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.reaction_removal_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_room_preference_receipts as (
    delete from private.room_preference_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.room_preference_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_message_send_receipts as (
    delete from private.message_send_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.message_send_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  ), expired_reaction_send_receipts as (
    delete from private.reaction_send_mutation_receipts
    where (actor_user_id, client_mutation_id) in (
      select receipt.actor_user_id, receipt.client_mutation_id
      from private.reaction_send_mutation_receipts as receipt
      where receipt.expires_at <= statement_timestamp()
      order by receipt.expires_at, receipt.actor_user_id, receipt.client_mutation_id
      for update skip locked
      limit p_batch_limit
    )
    returning client_mutation_id
  )
  select
    (select count(*) from expired_room_creation_receipts)
    + (select count(*) from expired_room_metadata_receipts)
    + (select count(*) from expired_message_revision_receipts)
    + (select count(*) from expired_reaction_removal_receipts)
    + (select count(*) from expired_room_preference_receipts)
    + (select count(*) from expired_message_send_receipts)
    + (select count(*) from expired_reaction_send_receipts)
  into purged_mutation_receipt_count;

  delete from private.device_sessions as device_session
  where not exists (
    select 1
    from auth.sessions as auth_session
    where auth_session.id = device_session.session_id
  );

  purge_completed_at := clock_timestamp();
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
    p_correlation_id,
    purge_started_at,
    purge_completed_at,
    purged_message_count,
    0,
    purged_typing_count,
    purged_presence_count,
    purged_invite_count,
    purged_device_reservation_count,
    purged_mutation_receipt_count
  );

  return query
  select
    p_correlation_id,
    purged_message_count,
    purged_typing_count,
    purged_presence_count,
    purged_invite_count,
    purged_device_reservation_count,
    purged_mutation_receipt_count,
    purge_completed_at;
end;
$$;

revoke all on table
  public.room_metadata_envelopes,
  public.message_revisions,
  public.message_revision_envelopes,
  public.room_member_preferences
from public, anon, authenticated;

grant select on public.room_metadata_envelopes to authenticated;
grant select on public.message_revisions to authenticated;
grant select on public.message_revision_envelopes to authenticated;
grant select on public.room_member_preferences to authenticated;

revoke all on table
  private.room_metadata_mutation_receipts,
  private.message_revision_mutation_receipts,
  private.reaction_removal_receipts,
  private.room_preference_mutation_receipts,
  private.room_creation_mutation_receipts,
  private.message_send_mutation_receipts,
  private.reaction_send_mutation_receipts
from public, anon, authenticated;

revoke all on function private.initialize_room_member_preferences() from public, anon, authenticated;
revoke all on function private.remove_departed_room_metadata_envelopes() from public, anon, authenticated;
revoke all on function private.remove_revoked_device_metadata_recipient() from public, anon, authenticated;
revoke all on function private.can_read_device_bundle(uuid) from public, anon, authenticated;
revoke all on function private.set_room_metadata(uuid, uuid, integer, jsonb) from public, anon, authenticated;
revoke all on function private.edit_message(uuid, uuid, integer, jsonb) from public, anon, authenticated;
revoke all on function private.remove_reaction(uuid, uuid) from public, anon, authenticated;
revoke all on function private.set_room_preferences(uuid, uuid, text, text, text, timestamptz)
from public, anon, authenticated;
revoke all on function private.list_room_recipient_devices(uuid) from public, anon, authenticated;
revoke all on function private.list_current_account_recipient_devices() from public, anon, authenticated;
revoke all on function private.create_room_with_metadata(text, integer, uuid, jsonb)
from public, anon, authenticated;
revoke all on function private.send_message(uuid, uuid, uuid, jsonb) from public, anon, authenticated;
revoke all on function private.send_reaction(uuid, uuid, jsonb) from public, anon, authenticated;
revoke all on function private.purge_expired_relational_data(integer, uuid)
from public, anon, authenticated;

revoke all on function public.set_room_metadata(uuid, uuid, integer, jsonb)
from public, anon, authenticated;
revoke all on function public.edit_message(uuid, uuid, integer, jsonb)
from public, anon, authenticated;
revoke all on function public.remove_reaction(uuid, uuid) from public, anon, authenticated;
revoke all on function public.set_room_preferences(uuid, uuid, text, text, text, timestamptz)
from public, anon, authenticated;
revoke all on function public.list_room_recipient_devices(uuid)
from public, anon, authenticated;
revoke all on function public.list_current_account_recipient_devices()
from public, anon, authenticated;
revoke all on function public.create_room_with_metadata(text, uuid, jsonb, integer)
from public, anon, authenticated;

revoke execute on function private.create_room(text, integer) from authenticated;
revoke execute on function public.create_room(text, integer) from authenticated;

grant execute on function private.can_read_device_bundle(uuid) to authenticated;
grant execute on function private.set_room_metadata(uuid, uuid, integer, jsonb) to authenticated;
grant execute on function private.edit_message(uuid, uuid, integer, jsonb) to authenticated;
grant execute on function private.remove_reaction(uuid, uuid) to authenticated;
grant execute on function private.set_room_preferences(uuid, uuid, text, text, text, timestamptz)
to authenticated;
grant execute on function private.list_room_recipient_devices(uuid) to authenticated;
grant execute on function private.list_current_account_recipient_devices() to authenticated;
grant execute on function private.create_room_with_metadata(text, integer, uuid, jsonb)
to authenticated;
grant execute on function private.send_message(uuid, uuid, uuid, jsonb) to authenticated;
grant execute on function private.send_reaction(uuid, uuid, jsonb) to authenticated;

grant execute on function public.set_room_metadata(uuid, uuid, integer, jsonb) to authenticated;
grant execute on function public.edit_message(uuid, uuid, integer, jsonb) to authenticated;
grant execute on function public.remove_reaction(uuid, uuid) to authenticated;
grant execute on function public.set_room_preferences(uuid, uuid, text, text, text, timestamptz)
to authenticated;
grant execute on function public.list_room_recipient_devices(uuid) to authenticated;
grant execute on function public.list_current_account_recipient_devices() to authenticated;
grant execute on function public.create_room_with_metadata(text, uuid, jsonb, integer) to authenticated;

-- This migration does not publish any table to Postgres Changes and does not
-- modify the locked realtime schema. Ciphertext remains polling-only.
