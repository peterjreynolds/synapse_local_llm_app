-- Bind encrypted room creation and mutation receipts to their authenticated
-- request context, then keep every device's polling surface within the Android
-- client's fail-closed response limits.

alter table public.rooms
add column creation_client_mutation_id uuid;

update public.rooms as room
set creation_client_mutation_id = receipt.client_mutation_id
from private.room_creation_mutation_receipts as receipt
where receipt.room_id = room.id;

alter table public.rooms
alter column creation_client_mutation_id set default gen_random_uuid();

create unique index rooms_owner_creation_mutation_unique
on public.rooms (owner_user_id, creation_client_mutation_id)
where creation_client_mutation_id is not null;

drop function public.create_room_with_metadata(text, uuid, jsonb, integer);
drop function private.create_room_with_metadata(text, integer, uuid, jsonb);

create function private.create_room_with_metadata(
  p_room_id uuid,
  p_room_kind text,
  p_retention_seconds integer,
  p_client_mutation_id uuid,
  p_envelopes jsonb
)
returns table (
  room_id uuid,
  client_mutation_id uuid,
  room_kind text,
  retention_seconds integer,
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
  if mutation_actor_user_id is null or actor_device_id is null then
    raise exception using errcode = '42501', message = 'an active device mutation is required';
  end if;

  if p_room_id is null
    or p_room_id = '00000000-0000-0000-0000-000000000000'::uuid
    or p_client_mutation_id is null
    or p_client_mutation_id = '00000000-0000-0000-0000-000000000000'::uuid
    or p_room_kind is null
    or p_room_kind not in ('DIRECT', 'GROUP')
    or p_retention_seconds is null
    or p_retention_seconds not in (300, 3600, 86400, 604800)
    or p_envelopes is null
    or jsonb_typeof(p_envelopes) <> 'array'
  then
    raise exception using errcode = '22023', message = 'room creation request is invalid';
  end if;

  request_digest := extensions.digest(
    convert_to(
      p_room_id::text || chr(31)
      || p_room_kind || chr(31)
      || p_retention_seconds::text || chr(31)
      || p_envelopes::text,
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
    if persisted_receipt.room_id <> p_room_id
      or persisted_receipt.request_digest <> request_digest
    then
      raise exception using errcode = '23505', message = 'room creation mutation id was already used';
    end if;
    return query
    select
      persisted_receipt.room_id,
      persisted_receipt.client_mutation_id,
      p_room_kind,
      p_retention_seconds,
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
    id,
    owner_user_id,
    creation_client_mutation_id,
    room_kind,
    retention_seconds,
    metadata_revision,
    metadata_updated_at
  ) values (
    p_room_id,
    mutation_actor_user_id,
    p_client_mutation_id,
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
    p_client_mutation_id,
    created_room.room_kind,
    created_room.retention_seconds,
    created_room.membership_epoch,
    1,
    created_room.created_at,
    mutation_time;
end;
$$;

create function public.create_room_with_metadata(
  p_room_id uuid,
  p_room_kind text,
  p_client_mutation_id uuid,
  p_envelopes jsonb,
  p_retention_seconds integer default 86400
)
returns table (
  room_id uuid,
  client_mutation_id uuid,
  room_kind text,
  retention_seconds integer,
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
    p_room_id,
    p_room_kind,
    p_retention_seconds,
    p_client_mutation_id,
    p_envelopes
  );
$$;

drop function public.send_message(uuid, uuid, jsonb, uuid);

create function public.send_message(
  p_room_id uuid,
  p_client_message_id uuid,
  p_envelopes jsonb,
  p_reply_to_message_id uuid default null
)
returns table (
  message_id uuid,
  room_id uuid,
  client_mutation_id uuid,
  expires_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select
    sent.message_id,
    p_room_id,
    p_client_message_id,
    sent.expires_at
  from private.send_message(
    p_room_id,
    p_client_message_id,
    p_reply_to_message_id,
    p_envelopes
  ) as sent;
$$;

drop function public.send_reaction(uuid, uuid, jsonb);

create function public.send_reaction(
  p_message_id uuid,
  p_client_reaction_id uuid,
  p_envelopes jsonb
)
returns table (
  reaction_id uuid,
  message_id uuid,
  client_mutation_id uuid,
  expires_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select
    sent.reaction_id,
    p_message_id,
    p_client_reaction_id,
    sent.expires_at
  from private.send_reaction(
    p_message_id,
    p_client_reaction_id,
    p_envelopes
  ) as sent;
$$;

create table private.device_envelope_capacity (
  recipient_device_id uuid primary key,
  stored_envelope_count integer not null,
  stored_ciphertext_bytes bigint not null,
  updated_at timestamptz not null default statement_timestamp(),
  constraint device_envelope_capacity_count_valid check (
    stored_envelope_count between 1 and 750
  ),
  constraint device_envelope_capacity_bytes_valid check (
    stored_ciphertext_bytes between 1 and 786432
  )
);

create table private.device_room_envelope_capacity (
  recipient_device_id uuid not null,
  room_id uuid not null,
  stored_envelope_count integer not null,
  stored_ciphertext_bytes bigint not null,
  updated_at timestamptz not null default statement_timestamp(),
  primary key (recipient_device_id, room_id),
  constraint device_room_envelope_capacity_count_valid check (
    stored_envelope_count between 1 and 250
  ),
  constraint device_room_envelope_capacity_bytes_valid check (
    stored_ciphertext_bytes between 1 and 262144
  )
);

create table private.device_sender_envelope_capacity (
  recipient_device_id uuid not null,
  sender_user_id uuid not null,
  stored_envelope_count integer not null,
  stored_ciphertext_bytes bigint not null,
  updated_at timestamptz not null default statement_timestamp(),
  primary key (recipient_device_id, sender_user_id),
  constraint device_sender_envelope_capacity_count_valid check (
    stored_envelope_count between 1 and 250
  ),
  constraint device_sender_envelope_capacity_bytes_valid check (
    stored_ciphertext_bytes between 1 and 262144
  )
);

create table private.envelope_capacity_contributions (
  envelope_table text not null,
  parent_record_id uuid not null,
  recipient_device_id uuid not null,
  sender_user_id uuid not null,
  room_id uuid not null,
  ciphertext_bytes integer not null,
  primary key (envelope_table, parent_record_id, recipient_device_id),
  constraint envelope_capacity_contributions_table_valid check (
    envelope_table in (
      'message_envelopes',
      'message_revision_envelopes',
      'reaction_envelopes',
      'room_metadata_envelopes'
    )
  ),
  constraint envelope_capacity_contributions_bytes_valid check (
    ciphertext_bytes between 1 and 262144
  )
);

alter table private.device_envelope_capacity enable row level security;
alter table private.device_room_envelope_capacity enable row level security;
alter table private.device_sender_envelope_capacity enable row level security;
alter table private.envelope_capacity_contributions enable row level security;

lock table
  public.message_envelopes,
  public.message_revision_envelopes,
  public.reaction_envelopes,
  public.room_metadata_envelopes
in share row exclusive mode;

do $$
declare
  over_capacity_device_id uuid;
  over_capacity_room_id uuid;
  over_capacity_sender_user_id uuid;
begin
  select envelope.recipient_device_id
    into over_capacity_device_id
  from (
    select recipient_device_id, ciphertext from public.message_envelopes
    union all
    select recipient_device_id, ciphertext from public.message_revision_envelopes
    union all
    select recipient_device_id, ciphertext from public.reaction_envelopes
    union all
    select recipient_device_id, ciphertext from public.room_metadata_envelopes
  ) as envelope
  group by envelope.recipient_device_id
  having count(*) > 750
    or sum(octet_length(envelope.ciphertext)) > 786432
  order by envelope.recipient_device_id
  limit 1;

  if over_capacity_device_id is not null then
    raise exception using
      errcode = '54000',
      message = 'existing encrypted content exceeds device polling capacity';
  end if;

  select envelope.room_id
    into over_capacity_room_id
  from (
    select message.room_id, envelope.recipient_device_id, envelope.ciphertext
    from public.message_envelopes as envelope
    join public.messages as message on message.id = envelope.message_id
    union all
    select message.room_id, envelope.recipient_device_id, envelope.ciphertext
    from public.message_revision_envelopes as envelope
    join public.message_revisions as revision on revision.id = envelope.revision_id
    join public.messages as message on message.id = revision.message_id
    union all
    select message.room_id, envelope.recipient_device_id, envelope.ciphertext
    from public.reaction_envelopes as envelope
    join public.reactions as reaction on reaction.id = envelope.reaction_id
    join public.messages as message on message.id = reaction.message_id
    union all
    select envelope.room_id, envelope.recipient_device_id, envelope.ciphertext
    from public.room_metadata_envelopes as envelope
  ) as envelope
  group by envelope.recipient_device_id, envelope.room_id
  having count(*) > 250
    or sum(octet_length(envelope.ciphertext)) > 262144
  order by envelope.recipient_device_id, envelope.room_id
  limit 1;

  if over_capacity_room_id is not null then
    raise exception using
      errcode = '54000',
      message = 'existing encrypted content exceeds device room capacity';
  end if;

  select envelope.sender_user_id
    into over_capacity_sender_user_id
  from (
    select message.sender_user_id, envelope.recipient_device_id, envelope.ciphertext
    from public.message_envelopes as envelope
    join public.messages as message on message.id = envelope.message_id
    union all
    select coalesce(revision.editor_user_id, message.sender_user_id),
           envelope.recipient_device_id,
           envelope.ciphertext
    from public.message_revision_envelopes as envelope
    join public.message_revisions as revision on revision.id = envelope.revision_id
    join public.messages as message on message.id = revision.message_id
    union all
    select reaction.sender_user_id, envelope.recipient_device_id, envelope.ciphertext
    from public.reaction_envelopes as envelope
    join public.reactions as reaction on reaction.id = envelope.reaction_id
    union all
    select coalesce(envelope.sender_user_id, room.owner_user_id),
           envelope.recipient_device_id,
           envelope.ciphertext
    from public.room_metadata_envelopes as envelope
    join public.rooms as room on room.id = envelope.room_id
  ) as envelope
  group by envelope.recipient_device_id, envelope.sender_user_id
  having count(*) > 250
    or sum(octet_length(envelope.ciphertext)) > 262144
  order by envelope.recipient_device_id, envelope.sender_user_id
  limit 1;

  if over_capacity_sender_user_id is not null then
    raise exception using
      errcode = '54000',
      message = 'existing encrypted content exceeds device sender capacity';
  end if;
end;
$$;

insert into private.envelope_capacity_contributions (
  envelope_table,
  parent_record_id,
  recipient_device_id,
  sender_user_id,
  room_id,
  ciphertext_bytes
)
select
  envelope.envelope_table,
  envelope.parent_record_id,
  envelope.recipient_device_id,
  envelope.sender_user_id,
  envelope.room_id,
  octet_length(envelope.ciphertext)
from (
  select
    'message_envelopes'::text as envelope_table,
    envelope.message_id as parent_record_id,
    envelope.recipient_device_id,
    message.sender_user_id,
    message.room_id,
    envelope.ciphertext
  from public.message_envelopes as envelope
  join public.messages as message on message.id = envelope.message_id
  union all
  select
    'message_revision_envelopes',
    envelope.revision_id,
    envelope.recipient_device_id,
    coalesce(revision.editor_user_id, message.sender_user_id),
    message.room_id,
    envelope.ciphertext
  from public.message_revision_envelopes as envelope
  join public.message_revisions as revision on revision.id = envelope.revision_id
  join public.messages as message on message.id = revision.message_id
  union all
  select
    'reaction_envelopes',
    envelope.reaction_id,
    envelope.recipient_device_id,
    reaction.sender_user_id,
    message.room_id,
    envelope.ciphertext
  from public.reaction_envelopes as envelope
  join public.reactions as reaction on reaction.id = envelope.reaction_id
  join public.messages as message on message.id = reaction.message_id
  union all
  select
    'room_metadata_envelopes',
    envelope.room_id,
    envelope.recipient_device_id,
    coalesce(envelope.sender_user_id, room.owner_user_id),
    envelope.room_id,
    envelope.ciphertext
  from public.room_metadata_envelopes as envelope
  join public.rooms as room on room.id = envelope.room_id
) as envelope;

insert into private.device_envelope_capacity (
  recipient_device_id,
  stored_envelope_count,
  stored_ciphertext_bytes,
  updated_at
)
select
  envelope.recipient_device_id,
  count(*)::integer,
  sum(octet_length(envelope.ciphertext))::bigint,
  statement_timestamp()
from (
  select recipient_device_id, ciphertext from public.message_envelopes
  union all
  select recipient_device_id, ciphertext from public.message_revision_envelopes
  union all
  select recipient_device_id, ciphertext from public.reaction_envelopes
  union all
  select recipient_device_id, ciphertext from public.room_metadata_envelopes
) as envelope
group by envelope.recipient_device_id;

insert into private.device_room_envelope_capacity (
  recipient_device_id,
  room_id,
  stored_envelope_count,
  stored_ciphertext_bytes,
  updated_at
)
select
  contribution.recipient_device_id,
  contribution.room_id,
  count(*)::integer,
  sum(contribution.ciphertext_bytes)::bigint,
  statement_timestamp()
from private.envelope_capacity_contributions as contribution
group by contribution.recipient_device_id, contribution.room_id;

insert into private.device_sender_envelope_capacity (
  recipient_device_id,
  sender_user_id,
  stored_envelope_count,
  stored_ciphertext_bytes,
  updated_at
)
select
  contribution.recipient_device_id,
  contribution.sender_user_id,
  count(*)::integer,
  sum(contribution.ciphertext_bytes)::bigint,
  statement_timestamp()
from private.envelope_capacity_contributions as contribution
group by contribution.recipient_device_id, contribution.sender_user_id;

create function private.reserve_device_envelope_capacity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  inserted_envelope record;
  parent_record_id uuid;
  resolved_room_id uuid;
  resolved_sender_user_id uuid;
  reserved_device_id uuid;
begin
  perform pg_advisory_xact_lock(
    hashtextextended('synapse-private/encrypted-envelope-capacity', 0)
  );

  for inserted_envelope in
    select
      to_jsonb(envelope) as row_data,
      envelope.recipient_device_id,
      octet_length(envelope.ciphertext) as ciphertext_bytes
    from inserted_envelopes as envelope
    order by envelope.recipient_device_id
  loop
    if tg_table_name = 'message_envelopes' then
      parent_record_id := (inserted_envelope.row_data ->> 'message_id')::uuid;
      select message.room_id, message.sender_user_id
        into strict resolved_room_id, resolved_sender_user_id
      from public.messages as message
      where message.id = parent_record_id;
    elsif tg_table_name = 'message_revision_envelopes' then
      parent_record_id := (inserted_envelope.row_data ->> 'revision_id')::uuid;
      select message.room_id, coalesce(revision.editor_user_id, message.sender_user_id)
        into strict resolved_room_id, resolved_sender_user_id
      from public.message_revisions as revision
      join public.messages as message on message.id = revision.message_id
      where revision.id = parent_record_id;
    elsif tg_table_name = 'reaction_envelopes' then
      parent_record_id := (inserted_envelope.row_data ->> 'reaction_id')::uuid;
      select message.room_id, reaction.sender_user_id
        into strict resolved_room_id, resolved_sender_user_id
      from public.reactions as reaction
      join public.messages as message on message.id = reaction.message_id
      where reaction.id = parent_record_id;
    elsif tg_table_name = 'room_metadata_envelopes' then
      parent_record_id := (inserted_envelope.row_data ->> 'room_id')::uuid;
      resolved_room_id := parent_record_id;
      select coalesce(
               (inserted_envelope.row_data ->> 'sender_user_id')::uuid,
               room.owner_user_id
             )
        into strict resolved_sender_user_id
      from public.rooms as room
      where room.id = parent_record_id;
    else
      raise exception using errcode = '55000', message = 'encrypted envelope capacity table is unsupported';
    end if;

    reserved_device_id := null;
    insert into private.device_envelope_capacity as capacity (
      recipient_device_id,
      stored_envelope_count,
      stored_ciphertext_bytes,
      updated_at
    ) values (
      inserted_envelope.recipient_device_id,
      1,
      inserted_envelope.ciphertext_bytes,
      statement_timestamp()
    )
    on conflict (recipient_device_id) do update
    set stored_envelope_count = capacity.stored_envelope_count + 1,
        stored_ciphertext_bytes = capacity.stored_ciphertext_bytes + excluded.stored_ciphertext_bytes,
        updated_at = statement_timestamp()
    where capacity.stored_envelope_count < 750
      and capacity.stored_ciphertext_bytes + excluded.stored_ciphertext_bytes <= 786432
    returning recipient_device_id into reserved_device_id;

    if reserved_device_id is null then
      raise exception using errcode = '54000', message = 'encrypted device polling capacity reached';
    end if;

    reserved_device_id := null;
    insert into private.device_room_envelope_capacity as capacity (
      recipient_device_id,
      room_id,
      stored_envelope_count,
      stored_ciphertext_bytes,
      updated_at
    ) values (
      inserted_envelope.recipient_device_id,
      resolved_room_id,
      1,
      inserted_envelope.ciphertext_bytes,
      statement_timestamp()
    )
    on conflict (recipient_device_id, room_id) do update
    set stored_envelope_count = capacity.stored_envelope_count + 1,
        stored_ciphertext_bytes = capacity.stored_ciphertext_bytes + excluded.stored_ciphertext_bytes,
        updated_at = statement_timestamp()
    where capacity.stored_envelope_count < 250
      and capacity.stored_ciphertext_bytes + excluded.stored_ciphertext_bytes <= 262144
    returning recipient_device_id into reserved_device_id;

    if reserved_device_id is null then
      raise exception using errcode = '54000', message = 'encrypted room polling capacity reached';
    end if;

    reserved_device_id := null;
    insert into private.device_sender_envelope_capacity as capacity (
      recipient_device_id,
      sender_user_id,
      stored_envelope_count,
      stored_ciphertext_bytes,
      updated_at
    ) values (
      inserted_envelope.recipient_device_id,
      resolved_sender_user_id,
      1,
      inserted_envelope.ciphertext_bytes,
      statement_timestamp()
    )
    on conflict (recipient_device_id, sender_user_id) do update
    set stored_envelope_count = capacity.stored_envelope_count + 1,
        stored_ciphertext_bytes = capacity.stored_ciphertext_bytes + excluded.stored_ciphertext_bytes,
        updated_at = statement_timestamp()
    where capacity.stored_envelope_count < 250
      and capacity.stored_ciphertext_bytes + excluded.stored_ciphertext_bytes <= 262144
    returning recipient_device_id into reserved_device_id;

    if reserved_device_id is null then
      raise exception using errcode = '54000', message = 'encrypted sender polling capacity reached';
    end if;

    insert into private.envelope_capacity_contributions (
      envelope_table,
      parent_record_id,
      recipient_device_id,
      sender_user_id,
      room_id,
      ciphertext_bytes
    ) values (
      tg_table_name,
      parent_record_id,
      inserted_envelope.recipient_device_id,
      resolved_sender_user_id,
      resolved_room_id,
      inserted_envelope.ciphertext_bytes
    );
  end loop;

  return null;
end;
$$;

create function private.release_device_envelope_capacity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  deleted_envelope record;
  parent_record_id uuid;
  persisted_contribution private.envelope_capacity_contributions;
  released_device_id uuid;
begin
  perform pg_advisory_xact_lock(
    hashtextextended('synapse-private/encrypted-envelope-capacity', 0)
  );

  for deleted_envelope in
    select
      to_jsonb(envelope) as row_data,
      envelope.recipient_device_id,
      octet_length(envelope.ciphertext) as ciphertext_bytes
    from deleted_envelopes as envelope
    order by envelope.recipient_device_id
  loop
    if tg_table_name = 'message_envelopes' then
      parent_record_id := (deleted_envelope.row_data ->> 'message_id')::uuid;
    elsif tg_table_name = 'message_revision_envelopes' then
      parent_record_id := (deleted_envelope.row_data ->> 'revision_id')::uuid;
    elsif tg_table_name = 'reaction_envelopes' then
      parent_record_id := (deleted_envelope.row_data ->> 'reaction_id')::uuid;
    elsif tg_table_name = 'room_metadata_envelopes' then
      parent_record_id := (deleted_envelope.row_data ->> 'room_id')::uuid;
    else
      raise exception using errcode = '55000', message = 'encrypted envelope capacity table is unsupported';
    end if;

    select contribution.*
      into persisted_contribution
    from private.envelope_capacity_contributions as contribution
    where contribution.envelope_table = tg_table_name
      and contribution.parent_record_id = parent_record_id
      and contribution.recipient_device_id = deleted_envelope.recipient_device_id
    for update;

    if not found
      or persisted_contribution.ciphertext_bytes <> deleted_envelope.ciphertext_bytes
    then
      raise exception using errcode = '55000', message = 'encrypted envelope contribution is missing';
    end if;

    released_device_id := null;
    delete from private.device_envelope_capacity as capacity
    where capacity.recipient_device_id = persisted_contribution.recipient_device_id
      and capacity.stored_envelope_count = 1
      and capacity.stored_ciphertext_bytes = persisted_contribution.ciphertext_bytes
    returning capacity.recipient_device_id into released_device_id;

    if released_device_id is null then
      update private.device_envelope_capacity as capacity
      set stored_envelope_count = capacity.stored_envelope_count - 1,
          stored_ciphertext_bytes = capacity.stored_ciphertext_bytes - persisted_contribution.ciphertext_bytes,
          updated_at = statement_timestamp()
      where capacity.recipient_device_id = persisted_contribution.recipient_device_id
        and capacity.stored_envelope_count > 1
        and capacity.stored_ciphertext_bytes > persisted_contribution.ciphertext_bytes
      returning capacity.recipient_device_id into released_device_id;
    end if;

    if released_device_id is null then
      raise exception using errcode = '55000', message = 'encrypted device capacity ledger is inconsistent';
    end if;

    released_device_id := null;
    delete from private.device_room_envelope_capacity as capacity
    where capacity.recipient_device_id = persisted_contribution.recipient_device_id
      and capacity.room_id = persisted_contribution.room_id
      and capacity.stored_envelope_count = 1
      and capacity.stored_ciphertext_bytes = persisted_contribution.ciphertext_bytes
    returning capacity.recipient_device_id into released_device_id;

    if released_device_id is null then
      update private.device_room_envelope_capacity as capacity
      set stored_envelope_count = capacity.stored_envelope_count - 1,
          stored_ciphertext_bytes = capacity.stored_ciphertext_bytes - persisted_contribution.ciphertext_bytes,
          updated_at = statement_timestamp()
      where capacity.recipient_device_id = persisted_contribution.recipient_device_id
        and capacity.room_id = persisted_contribution.room_id
        and capacity.stored_envelope_count > 1
        and capacity.stored_ciphertext_bytes > persisted_contribution.ciphertext_bytes
      returning capacity.recipient_device_id into released_device_id;
    end if;

    if released_device_id is null then
      raise exception using errcode = '55000', message = 'encrypted room capacity ledger is inconsistent';
    end if;

    released_device_id := null;
    delete from private.device_sender_envelope_capacity as capacity
    where capacity.recipient_device_id = persisted_contribution.recipient_device_id
      and capacity.sender_user_id = persisted_contribution.sender_user_id
      and capacity.stored_envelope_count = 1
      and capacity.stored_ciphertext_bytes = persisted_contribution.ciphertext_bytes
    returning capacity.recipient_device_id into released_device_id;

    if released_device_id is null then
      update private.device_sender_envelope_capacity as capacity
      set stored_envelope_count = capacity.stored_envelope_count - 1,
          stored_ciphertext_bytes = capacity.stored_ciphertext_bytes - persisted_contribution.ciphertext_bytes,
          updated_at = statement_timestamp()
      where capacity.recipient_device_id = persisted_contribution.recipient_device_id
        and capacity.sender_user_id = persisted_contribution.sender_user_id
        and capacity.stored_envelope_count > 1
        and capacity.stored_ciphertext_bytes > persisted_contribution.ciphertext_bytes
      returning capacity.recipient_device_id into released_device_id;
    end if;

    if released_device_id is null then
      raise exception using errcode = '55000', message = 'encrypted sender capacity ledger is inconsistent';
    end if;

    delete from private.envelope_capacity_contributions as contribution
    where contribution.envelope_table = persisted_contribution.envelope_table
      and contribution.parent_record_id = persisted_contribution.parent_record_id
      and contribution.recipient_device_id = persisted_contribution.recipient_device_id;
  end loop;

  return null;
end;
$$;

create function private.prevent_envelope_capacity_key_update()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  old_parent_record_id uuid := coalesce(
    (to_jsonb(old) ->> 'message_id')::uuid,
    (to_jsonb(old) ->> 'revision_id')::uuid,
    (to_jsonb(old) ->> 'reaction_id')::uuid,
    (to_jsonb(old) ->> 'room_id')::uuid
  );
  new_parent_record_id uuid := coalesce(
    (to_jsonb(new) ->> 'message_id')::uuid,
    (to_jsonb(new) ->> 'revision_id')::uuid,
    (to_jsonb(new) ->> 'reaction_id')::uuid,
    (to_jsonb(new) ->> 'room_id')::uuid
  );
begin
  if new.recipient_device_id is distinct from old.recipient_device_id
    or new.ciphertext is distinct from old.ciphertext
    or new_parent_record_id is distinct from old_parent_record_id
  then
    raise exception using errcode = '55000', message = 'encrypted envelope capacity fields are immutable';
  end if;
  return new;
end;
$$;

create trigger reserve_message_envelope_capacity
after insert on public.message_envelopes
referencing new table as inserted_envelopes
for each statement execute function private.reserve_device_envelope_capacity();
create trigger release_message_envelope_capacity
after delete on public.message_envelopes
referencing old table as deleted_envelopes
for each statement execute function private.release_device_envelope_capacity();
create trigger protect_message_envelope_capacity_fields
before update on public.message_envelopes
for each row execute function private.prevent_envelope_capacity_key_update();

create trigger reserve_message_revision_envelope_capacity
after insert on public.message_revision_envelopes
referencing new table as inserted_envelopes
for each statement execute function private.reserve_device_envelope_capacity();
create trigger release_message_revision_envelope_capacity
after delete on public.message_revision_envelopes
referencing old table as deleted_envelopes
for each statement execute function private.release_device_envelope_capacity();
create trigger protect_message_revision_envelope_capacity_fields
before update on public.message_revision_envelopes
for each row execute function private.prevent_envelope_capacity_key_update();

create trigger reserve_reaction_envelope_capacity
after insert on public.reaction_envelopes
referencing new table as inserted_envelopes
for each statement execute function private.reserve_device_envelope_capacity();
create trigger release_reaction_envelope_capacity
after delete on public.reaction_envelopes
referencing old table as deleted_envelopes
for each statement execute function private.release_device_envelope_capacity();
create trigger protect_reaction_envelope_capacity_fields
before update on public.reaction_envelopes
for each row execute function private.prevent_envelope_capacity_key_update();

create trigger reserve_room_metadata_envelope_capacity
after insert on public.room_metadata_envelopes
referencing new table as inserted_envelopes
for each statement execute function private.reserve_device_envelope_capacity();
create trigger release_room_metadata_envelope_capacity
after delete on public.room_metadata_envelopes
referencing old table as deleted_envelopes
for each statement execute function private.release_device_envelope_capacity();
create trigger protect_room_metadata_envelope_capacity_fields
before update on public.room_metadata_envelopes
for each row execute function private.prevent_envelope_capacity_key_update();

create index messages_sender_room_created_idx
on public.messages (sender_user_id, room_id, created_at desc);

create function private.enforce_message_send_rate()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.sender_user_id is null or new.room_id is null then
    raise exception using errcode = '22023', message = 'message sender context is invalid';
  end if;

  perform pg_advisory_xact_lock(
    hashtextextended(
      new.sender_user_id::text || '/room-message-rate/' || new.room_id::text,
      0
    )
  );

  if (
    select count(*)
    from public.messages as recent_message
    where recent_message.sender_user_id = new.sender_user_id
      and recent_message.room_id = new.room_id
      and recent_message.created_at >= statement_timestamp() - interval '1 minute'
  ) >= 30 then
    raise exception using errcode = '54000', message = 'message send rate limit exceeded';
  end if;

  return new;
end;
$$;

create trigger enforce_message_send_rate
before insert on public.messages
for each row execute function private.enforce_message_send_rate();

revoke all on table
  private.device_envelope_capacity,
  private.device_room_envelope_capacity,
  private.device_sender_envelope_capacity,
  private.envelope_capacity_contributions
from public, anon, authenticated;

revoke all on function private.reserve_device_envelope_capacity() from public, anon, authenticated;
revoke all on function private.release_device_envelope_capacity() from public, anon, authenticated;
revoke all on function private.prevent_envelope_capacity_key_update() from public, anon, authenticated;
revoke all on function private.enforce_message_send_rate() from public, anon, authenticated;

revoke all on function private.create_room_with_metadata(uuid, text, integer, uuid, jsonb)
from public, anon, authenticated;
revoke all on function public.create_room_with_metadata(uuid, text, uuid, jsonb, integer)
from public, anon, authenticated;
revoke all on function public.send_message(uuid, uuid, jsonb, uuid) from public, anon, authenticated;
revoke all on function public.send_reaction(uuid, uuid, jsonb) from public, anon, authenticated;
grant execute on function private.create_room_with_metadata(uuid, text, integer, uuid, jsonb) to authenticated;
grant execute on function public.create_room_with_metadata(uuid, text, uuid, jsonb, integer) to authenticated;
grant execute on function public.send_message(uuid, uuid, jsonb, uuid) to authenticated;
grant execute on function public.send_reaction(uuid, uuid, jsonb) to authenticated;
