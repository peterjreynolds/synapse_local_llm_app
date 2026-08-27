alter table public.profiles enable row level security;
alter table public.devices enable row level security;
alter table public.device_one_time_prekeys enable row level security;
alter table public.rooms enable row level security;
alter table public.room_members enable row level security;
alter table public.messages enable row level security;
alter table public.message_envelopes enable row level security;
alter table public.message_reply_links enable row level security;
alter table public.reactions enable row level security;
alter table public.reaction_envelopes enable row level security;
alter table public.message_receipts enable row level security;
alter table public.typing_state enable row level security;
alter table public.presence_state enable row level security;
alter table public.attachments enable row level security;

insert into storage.buckets (
  id,
  name,
  public,
  file_size_limit,
  allowed_mime_types
) values (
  'encrypted-attachments',
  'encrypted-attachments',
  false,
  20971520,
  array['application/octet-stream']::text[]
)
on conflict (id) do update
set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

revoke all on table
  public.profiles,
  public.devices,
  public.device_one_time_prekeys,
  public.rooms,
  public.room_members,
  public.messages,
  public.message_envelopes,
  public.message_reply_links,
  public.reactions,
  public.reaction_envelopes,
  public.message_receipts,
  public.typing_state,
  public.presence_state,
  public.attachments
from anon, authenticated;

grant select on public.profiles to authenticated;
grant update (
  display_name,
  presence_sharing_enabled,
  typing_indicators_enabled,
  read_receipts_enabled
) on public.profiles to authenticated;
grant select (
  id,
  user_id,
  protocol_adapter_version,
  registration_id,
  signal_device_id,
  identity_key,
  signed_pre_key_id,
  signed_pre_key_public,
  signed_pre_key_signature,
  kyber_pre_key_id,
  kyber_pre_key_public,
  kyber_pre_key_signature,
  created_at
) on public.devices to authenticated;
grant select on public.rooms to authenticated;
grant select on public.room_members to authenticated;
grant select on public.messages to authenticated;
grant select on public.message_envelopes to authenticated;
grant select on public.message_reply_links to authenticated;
grant select on public.reactions to authenticated;
grant select on public.reaction_envelopes to authenticated;
grant select, insert on public.message_receipts to authenticated;
grant select, insert, update, delete on public.typing_state to authenticated;
grant select, insert, update, delete on public.presence_state to authenticated;
grant select on public.attachments to authenticated;
grant insert (
  id,
  message_id,
  uploader_device_id,
  encrypted_header,
  ciphertext_digest,
  ciphertext_byte_count,
  encrypted_thumbnail_header,
  thumbnail_ciphertext_digest,
  thumbnail_ciphertext_byte_count
) on public.attachments to authenticated;

create policy profiles_select_shared_room
on public.profiles
for select
to authenticated
using (private.shares_room_with(user_id));

create policy profiles_update_self
on public.profiles
for update
to authenticated
using (user_id = auth.uid() and private.current_device_id() is not null)
with check (user_id = auth.uid() and private.current_device_id() is not null);

create policy devices_select_shared_room
on public.devices
for select
to authenticated
using (revoked_at is null and private.shares_room_with(user_id));

create policy rooms_select_member
on public.rooms
for select
to authenticated
using (private.is_active_room_member(id));

create policy room_members_select_member
on public.room_members
for select
to authenticated
using (private.is_active_room_member(room_id));

create policy messages_select_member_before_expiry
on public.messages
for select
to authenticated
using (private.can_access_message(id));

create policy message_envelopes_select_recipient
on public.message_envelopes
for select
to authenticated
using (
  recipient_device_id = private.current_device_id()
  and private.can_access_message(message_id)
);

create policy message_reply_links_select_member
on public.message_reply_links
for select
to authenticated
using (
  private.can_access_message(message_id)
  and private.can_access_message(replied_to_message_id)
);

create policy reactions_select_member
on public.reactions
for select
to authenticated
using (expires_at > statement_timestamp() and private.can_access_message(message_id));

create policy reaction_envelopes_select_recipient
on public.reaction_envelopes
for select
to authenticated
using (
  recipient_device_id = private.current_device_id()
  and exists (
    select 1
    from public.reactions as reaction
    where reaction.id = reaction_id
      and reaction.expires_at > statement_timestamp()
      and private.can_access_message(reaction.message_id)
  )
);

create policy message_receipts_select_member
on public.message_receipts
for select
to authenticated
using (
  expires_at > statement_timestamp()
  and private.can_access_message(message_id)
  and (
    receipt_kind = 'DELIVERED'
    or exists (
      select 1
      from public.devices as receipt_device
      join public.profiles as receipt_profile on receipt_profile.user_id = receipt_device.user_id
      where receipt_device.id = message_receipts.recipient_device_id
        and receipt_profile.read_receipts_enabled
    )
  )
);

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
  )
  and (
    receipt_kind = 'DELIVERED'
    or exists (
      select 1
      from public.profiles as profile
      where profile.user_id = auth.uid()
        and profile.read_receipts_enabled
    )
  )
);

create policy typing_state_select_member_before_expiry
on public.typing_state
for select
to authenticated
using (
  expires_at > statement_timestamp()
  and private.is_active_room_member(room_id)
  and exists (
    select 1
    from public.devices as typing_device
    join public.profiles as typing_profile on typing_profile.user_id = typing_device.user_id
    where typing_device.id = typing_state.device_id
      and typing_device.revoked_at is null
      and typing_profile.typing_indicators_enabled
  )
);

create policy typing_state_insert_current_device
on public.typing_state
for insert
to authenticated
with check (device_id = private.current_device_id() and private.is_active_room_member(room_id));

create policy typing_state_update_current_device
on public.typing_state
for update
to authenticated
using (device_id = private.current_device_id() and private.is_active_room_member(room_id))
with check (device_id = private.current_device_id() and private.is_active_room_member(room_id));

create policy typing_state_delete_current_device
on public.typing_state
for delete
to authenticated
using (device_id = private.current_device_id() and private.is_active_room_member(room_id));

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
     and viewer_membership.user_id = auth.uid()
    where present_device.id = presence_state.device_id
      and present_device.revoked_at is null
      and present_profile.presence_sharing_enabled
  )
  and private.current_device_id() is not null
);

create policy presence_state_insert_current_device
on public.presence_state
for insert
to authenticated
with check (device_id = private.current_device_id());

create policy presence_state_update_current_device
on public.presence_state
for update
to authenticated
using (device_id = private.current_device_id())
with check (device_id = private.current_device_id());

create policy presence_state_delete_current_device
on public.presence_state
for delete
to authenticated
using (device_id = private.current_device_id());

create policy attachments_select_member_before_message_expiry
on public.attachments
for select
to authenticated
using (private.can_access_message(message_id));

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
      and message.sender_user_id = auth.uid()
      and message.sender_device_id = private.current_device_id()
      and message.expires_at > statement_timestamp()
  )
);

create function private.can_read_attachment_object(p_object_path text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.current_device_id() is not null
    and exists (
      select 1
      from public.attachments as attachment
      join public.messages as message on message.id = attachment.message_id
      join public.room_members as room_member
        on room_member.room_id = message.room_id
       and room_member.user_id = auth.uid()
      where p_object_path in (attachment.object_path, attachment.thumbnail_object_path)
        and message.expires_at > statement_timestamp()
        and not exists (
          select 1
          from private.message_deletion_requests as deletion_request
          where deletion_request.message_id = message.id
        )
    );
$$;

create function private.can_upload_attachment_object(p_object_path text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.attachments as attachment
    join public.messages as message on message.id = attachment.message_id
    where p_object_path in (attachment.object_path, attachment.thumbnail_object_path)
      and attachment.uploader_device_id = private.current_device_id()
      and message.sender_user_id = auth.uid()
      and message.sender_device_id = private.current_device_id()
      and message.expires_at > statement_timestamp()
      and not exists (
        select 1
        from private.message_deletion_requests as deletion_request
        where deletion_request.message_id = message.id
      )
  );
$$;

revoke all on function private.can_read_attachment_object(text) from public, anon;
revoke all on function private.can_upload_attachment_object(text) from public, anon;
grant execute on function private.can_read_attachment_object(text) to authenticated;
grant execute on function private.can_upload_attachment_object(text) to authenticated;

create policy encrypted_attachments_select_current_member
on storage.objects
for select
to authenticated
using (
  bucket_id = 'encrypted-attachments'
  and private.can_read_attachment_object(name)
);

create policy encrypted_attachments_insert_sender
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'encrypted-attachments'
  and owner_id = auth.uid()::text
  and private.can_upload_attachment_object(name)
);

create function private.initialize_runtime_configuration(p_project_url text)
returns table (
  username_hmac_pepper text,
  rate_limit_hmac_pepper text,
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
    set
      project_url = normalized_project_url,
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
    rtrim(translate(encode(runtime_configuration.purge_capability, 'base64'), '+/', '-_'), '=');
end;
$$;

create function private.decode_bounded_hex(
  p_encoded text,
  p_minimum_bytes integer,
  p_maximum_bytes integer,
  p_field_name text
)
returns bytea
language plpgsql
immutable
security invoker
set search_path = ''
as $$
declare
  decoded bytea;
begin
  if p_encoded is null
    or p_encoded !~ '^[0-9a-f]+$'
    or length(p_encoded) % 2 <> 0
  then
    raise exception using errcode = '22023', message = p_field_name || ' must be lowercase hexadecimal';
  end if;

  decoded := decode(p_encoded, 'hex');
  if octet_length(decoded) not between p_minimum_bytes and p_maximum_bytes then
    raise exception using errcode = '22023', message = p_field_name || ' has an invalid byte length';
  end if;
  return decoded;
end;
$$;

create function private.assert_complete_signal_envelopes(
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
begin
  if p_envelopes is null
    or jsonb_typeof(p_envelopes) <> 'array'
    or jsonb_array_length(p_envelopes) not between 1 and 128
  then
    raise exception using errcode = '22023', message = 'envelopes must contain between one and 128 entries';
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
    if envelope ->> 'signal_message_type' not in ('PREKEY', 'WHISPER') then
      raise exception using errcode = '22023', message = 'Signal message type is invalid';
    end if;
    perform private.decode_bounded_hex(
      envelope ->> 'ciphertext_hex',
      1,
      p_maximum_ciphertext_bytes,
      'ciphertext_hex'
    );
  end loop;

  select coalesce(array_agg(device.id order by device.id), array[]::uuid[])
    into expected_device_ids
  from public.room_members as room_member
  join public.devices as device on device.user_id = room_member.user_id
  where room_member.room_id = p_room_id
    and device.revoked_at is null
    and device.id <> p_sender_device_id;

  select coalesce(array_agg(supplied_device_id order by supplied_device_id), array[]::uuid[])
    into supplied_device_ids
  from unnest(supplied_device_ids) as supplied_device_id;

  if supplied_device_ids <> expected_device_ids then
    raise exception using errcode = '22023', message = 'one envelope is required for every recipient device';
  end if;
end;
$$;

create function private.create_room(
  p_room_kind text,
  p_retention_seconds integer
)
returns table (room_id uuid, membership_epoch integer, created_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  created_room public.rooms;
begin
  if actor_user_id is null or actor_device_id is null then
    raise exception using errcode = '42501', message = 'an active device session is required';
  end if;

  insert into public.rooms (
    owner_user_id,
    room_kind,
    retention_seconds
  ) values (
    actor_user_id,
    p_room_kind,
    p_retention_seconds
  ) returning * into created_room;

  insert into public.room_members (room_id, user_id, member_role)
  values (created_room.id, actor_user_id, 'OWNER');

  return query select created_room.id, created_room.membership_epoch, created_room.created_at;
end;
$$;

create function public.create_room(
  p_room_kind text,
  p_retention_seconds integer default 86400
)
returns table (room_id uuid, membership_epoch integer, created_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select *
  from private.create_room(
    p_room_kind,
    p_retention_seconds
  );
$$;

create function private.publish_device_one_time_prekeys(
  p_pre_key_ids integer[],
  p_public_key_hex_values text[]
)
returns table (device_id uuid, published_count integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_device_id uuid := private.current_device_id();
  pre_key_count integer;
begin
  if actor_device_id is null then
    raise exception using errcode = '42501', message = 'an active device session is required';
  end if;

  pre_key_count := coalesce(cardinality(p_pre_key_ids), 0);
  if pre_key_count not between 1 and 100
    or pre_key_count <> coalesce(cardinality(p_public_key_hex_values), 0)
  then
    raise exception using errcode = '22023', message = 'one to 100 paired one-time prekeys are required';
  end if;

  insert into public.device_one_time_prekeys (device_id, pre_key_id, public_key)
  select
    actor_device_id,
    pre_key_id,
    private.decode_bounded_hex(public_key_hex, 33, 33, 'one_time_pre_key')
  from unnest(p_pre_key_ids, p_public_key_hex_values) as pre_key(pre_key_id, public_key_hex);

  return query select actor_device_id, pre_key_count;
end;
$$;

create function public.publish_device_one_time_prekeys(
  p_pre_key_ids integer[],
  p_public_key_hex_values text[]
)
returns table (device_id uuid, published_count integer)
language sql
security invoker
set search_path = ''
as $$
  select * from private.publish_device_one_time_prekeys(p_pre_key_ids, p_public_key_hex_values);
$$;

create function private.claim_device_prekey(p_target_device_id uuid)
returns table (
  target_device_id uuid,
  protocol_adapter_version smallint,
  registration_id integer,
  signal_device_id smallint,
  identity_key bytea,
  signed_pre_key_id integer,
  signed_pre_key_public bytea,
  signed_pre_key_signature bytea,
  kyber_pre_key_id integer,
  kyber_pre_key_public bytea,
  kyber_pre_key_signature bytea,
  one_time_pre_key_id integer,
  one_time_pre_key_public bytea
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  claimed_pre_key_id integer;
  claimed_public_key bytea;
begin
  if actor_user_id is null or actor_device_id is null then
    raise exception using errcode = '42501', message = 'an active device session is required';
  end if;

  if not exists (
    select 1
    from public.devices as target_device
    where target_device.id = p_target_device_id
      and target_device.revoked_at is null
      and target_device.id <> actor_device_id
      and (
        target_device.user_id = actor_user_id
        or exists (
          select 1
          from public.room_members as target_membership
          join public.room_members as actor_membership
            on actor_membership.room_id = target_membership.room_id
           and actor_membership.user_id = actor_user_id
          where target_membership.user_id = target_device.user_id
        )
      )
  ) then
    raise exception using errcode = '42501', message = 'target device is not a current room peer';
  end if;

  delete from public.device_one_time_prekeys as one_time_pre_key
  where (one_time_pre_key.device_id, one_time_pre_key.pre_key_id) = (
    select candidate.device_id, candidate.pre_key_id
    from public.device_one_time_prekeys as candidate
    where candidate.device_id = p_target_device_id
    order by candidate.pre_key_id
    for update skip locked
    limit 1
  )
  returning one_time_pre_key.pre_key_id, one_time_pre_key.public_key
    into claimed_pre_key_id, claimed_public_key;

  return query
  select
    target_device.id,
    target_device.protocol_adapter_version,
    target_device.registration_id,
    target_device.signal_device_id,
    target_device.identity_key,
    target_device.signed_pre_key_id,
    target_device.signed_pre_key_public,
    target_device.signed_pre_key_signature,
    target_device.kyber_pre_key_id,
    target_device.kyber_pre_key_public,
    target_device.kyber_pre_key_signature,
    claimed_pre_key_id,
    claimed_public_key
  from public.devices as target_device
  where target_device.id = p_target_device_id
    and target_device.revoked_at is null;
end;
$$;

create function public.claim_device_prekey(p_target_device_id uuid)
returns table (
  target_device_id uuid,
  protocol_adapter_version smallint,
  registration_id integer,
  signal_device_id smallint,
  identity_key bytea,
  signed_pre_key_id integer,
  signed_pre_key_public bytea,
  signed_pre_key_signature bytea,
  kyber_pre_key_id integer,
  kyber_pre_key_public bytea,
  kyber_pre_key_signature bytea,
  one_time_pre_key_id integer,
  one_time_pre_key_public bytea
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.claim_device_prekey(p_target_device_id);
$$;

create function private.send_message(
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
  actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  locked_room public.rooms;
  persisted_message public.messages;
begin
  select room.*
    into strict locked_room
  from public.rooms as room
  where room.id = p_room_id
  for share;

  if actor_user_id is null or actor_device_id is null or not private.is_active_room_member(p_room_id) then
    raise exception using errcode = '42501', message = 'current room membership is required';
  end if;

  if p_reply_to_message_id is not null and not exists (
    select 1
    from public.messages as replied_to
    where replied_to.id = p_reply_to_message_id
      and replied_to.room_id = p_room_id
      and replied_to.expires_at > statement_timestamp()
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
  )
  values (
    locked_room.id,
    actor_user_id,
    actor_device_id,
    p_client_message_id,
    locked_room.membership_epoch,
    statement_timestamp() + make_interval(secs => locked_room.retention_seconds)
  )
  on conflict (sender_user_id, client_message_id) do nothing
  returning * into persisted_message;

  if persisted_message.id is null then
    select message.*
      into strict persisted_message
    from public.messages as message
    where message.sender_user_id = actor_user_id
      and message.client_message_id = p_client_message_id
      and message.room_id = p_room_id;
    return query select persisted_message.id, persisted_message.expires_at;
    return;
  end if;

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

  return query select persisted_message.id, persisted_message.expires_at;
end;
$$;

create function public.send_message(
  p_room_id uuid,
  p_client_message_id uuid,
  p_envelopes jsonb,
  p_reply_to_message_id uuid default null
)
returns table (message_id uuid, expires_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select * from private.send_message(p_room_id, p_client_message_id, p_reply_to_message_id, p_envelopes);
$$;

create function private.send_reaction(
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
  actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  parent_message public.messages;
  locked_room public.rooms;
  persisted_reaction public.reactions;
begin
  select message.*
    into strict parent_message
  from public.messages as message
  where message.id = p_message_id
    and message.expires_at > statement_timestamp()
    and not exists (
      select 1
      from private.message_deletion_requests as deletion_request
      where deletion_request.message_id = message.id
    );

  select room.*
    into strict locked_room
  from public.rooms as room
  where room.id = parent_message.room_id
  for share;

  if actor_user_id is null
    or actor_device_id is null
    or not private.is_active_room_member(parent_message.room_id)
  then
    raise exception using errcode = '42501', message = 'current room membership is required';
  end if;

  perform private.assert_complete_signal_envelopes(parent_message.room_id, actor_device_id, p_envelopes, 16384);

  insert into public.reactions (
    message_id,
    sender_user_id,
    sender_device_id,
    client_reaction_id,
    membership_epoch,
    expires_at
  ) values (
    parent_message.id,
    actor_user_id,
    actor_device_id,
    p_client_reaction_id,
    locked_room.membership_epoch,
    parent_message.expires_at
  )
  on conflict (sender_user_id, client_reaction_id) do nothing
  returning * into persisted_reaction;

  if persisted_reaction.id is null then
    select reaction.*
      into strict persisted_reaction
    from public.reactions as reaction
    where reaction.sender_user_id = actor_user_id
      and reaction.client_reaction_id = p_client_reaction_id
      and reaction.message_id = p_message_id;
    return query select persisted_reaction.id, persisted_reaction.expires_at;
    return;
  end if;

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

  return query select persisted_reaction.id, persisted_reaction.expires_at;
end;
$$;

create function public.send_reaction(
  p_message_id uuid,
  p_client_reaction_id uuid,
  p_envelopes jsonb
)
returns table (reaction_id uuid, expires_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select * from private.send_reaction(p_message_id, p_client_reaction_id, p_envelopes);
$$;

create function private.delete_message_for_everyone(p_message_id uuid)
returns table (
  message_id uuid,
  correlation_id uuid,
  deletion_state text,
  requested_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_user_id uuid := auth.uid();
  actor_device_id uuid := private.current_device_id();
  persisted_message public.messages;
  persisted_request private.message_deletion_requests;
  deletion_time timestamptz := statement_timestamp();
begin
  if actor_user_id is null or actor_device_id is null then
    raise exception using errcode = '42501', message = 'an active device session is required';
  end if;

  select message.*
    into strict persisted_message
  from public.messages as message
  where message.id = p_message_id
    and message.sender_user_id = actor_user_id
    and message.expires_at > deletion_time
  for update;

  select deletion_request.*
    into persisted_request
  from private.message_deletion_requests as deletion_request
  where deletion_request.message_id = persisted_message.id;

  if found then
    return query
    select
      persisted_message.id,
      persisted_request.correlation_id,
      'PURGE_PENDING'::text,
      persisted_request.requested_at;
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
      actor_user_id,
      deletion_time
    )
    returning * into strict persisted_request;

    return query
    select
      persisted_message.id,
      persisted_request.correlation_id,
      'PURGE_PENDING'::text,
      persisted_request.requested_at;
    return;
  end if;

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
    device_reservations_deleted
  ) values (
    persisted_request.correlation_id,
    deletion_time,
    clock_timestamp(),
    1,
    0,
    0,
    0,
    0,
    0
  );

  return query
  select
    persisted_message.id,
    persisted_request.correlation_id,
    'DELETED'::text,
    persisted_request.requested_at;
end;
$$;

create function public.delete_message_for_everyone(p_message_id uuid)
returns table (
  message_id uuid,
  correlation_id uuid,
  deletion_state text,
  requested_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.delete_message_for_everyone(p_message_id);
$$;

create function private.update_room_retention(p_room_id uuid, p_retention_seconds integer)
returns table (room_id uuid, retention_seconds integer, updated_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  update_time timestamptz := statement_timestamp();
begin
  if private.current_device_id() is null or not private.can_manage_room(p_room_id) then
    raise exception using errcode = '42501', message = 'room management is not authorized';
  end if;
  if p_retention_seconds not in (300, 3600, 86400, 604800) then
    raise exception using errcode = '22023', message = 'retention period is unsupported';
  end if;

  update public.rooms as room
  set retention_seconds = p_retention_seconds
  where room.id = p_room_id;
  if not found then
    raise exception using errcode = '22023', message = 'room is unavailable';
  end if;
  return query select p_room_id, p_retention_seconds, update_time;
end;
$$;

create function public.update_room_retention(p_room_id uuid, p_retention_seconds integer)
returns table (room_id uuid, retention_seconds integer, updated_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select * from private.update_room_retention(p_room_id, p_retention_seconds);
$$;

create function private.update_room_member_role(
  p_room_id uuid,
  p_member_user_id uuid,
  p_member_role text
)
returns table (room_id uuid, member_user_id uuid, member_role text, new_membership_epoch integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_user_id uuid := auth.uid();
  advanced_membership_epoch integer;
begin
  perform 1
  from public.rooms as room
  where room.id = p_room_id
    and room.room_kind = 'GROUP'
    and room.owner_user_id = actor_user_id
  for update;

  if private.current_device_id() is null or not found
    or p_member_user_id = actor_user_id
    or p_member_role not in ('ADMIN', 'MEMBER')
  then
    raise exception using errcode = '42501', message = 'member role change is not authorized';
  end if;

  update public.room_members as room_member
  set member_role = p_member_role
  where room_member.room_id = p_room_id
    and room_member.user_id = p_member_user_id
    and room_member.member_role <> 'OWNER';
  if not found then
    raise exception using errcode = '22023', message = 'room member is unavailable';
  end if;

  update public.rooms as room
  set membership_epoch = room.membership_epoch + 1
  where room.id = p_room_id
    and room.membership_epoch < 2147483647
  returning room.membership_epoch into strict advanced_membership_epoch;

  return query select p_room_id, p_member_user_id, p_member_role, advanced_membership_epoch;
end;
$$;

create function public.update_room_member_role(
  p_room_id uuid,
  p_member_user_id uuid,
  p_member_role text
)
returns table (room_id uuid, member_user_id uuid, member_role text, new_membership_epoch integer)
language sql
security invoker
set search_path = ''
as $$
  select * from private.update_room_member_role(p_room_id, p_member_user_id, p_member_role);
$$;

create function private.remove_room_member(p_room_id uuid, p_removed_user_id uuid)
returns table (room_id uuid, removed_user_id uuid, new_membership_epoch integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_user_id uuid := auth.uid();
  actor_role text;
  removed_role text;
  advanced_membership_epoch integer;
begin
  if private.current_device_id() is null then
    raise exception using errcode = '42501', message = 'an active device session is required';
  end if;

  perform 1
  from public.rooms as room
  where room.id = p_room_id
  for update;

  select room_member.member_role into strict actor_role
  from public.room_members as room_member
  where room_member.room_id = p_room_id and room_member.user_id = actor_user_id;

  select room_member.member_role into strict removed_role
  from public.room_members as room_member
  where room_member.room_id = p_room_id and room_member.user_id = p_removed_user_id;

  if p_removed_user_id = actor_user_id
    or removed_role = 'OWNER'
    or actor_role not in ('OWNER', 'ADMIN')
    or (actor_role = 'ADMIN' and removed_role <> 'MEMBER')
  then
    raise exception using errcode = '42501', message = 'member removal is not authorized';
  end if;

  delete from public.room_members
  where room_members.room_id = p_room_id
    and room_members.user_id = p_removed_user_id;

  update public.rooms as room
  set membership_epoch = room.membership_epoch + 1
  where room.id = p_room_id
    and room.membership_epoch < 2147483647
  returning room.membership_epoch into strict advanced_membership_epoch;

  return query select p_room_id, p_removed_user_id, advanced_membership_epoch;
end;
$$;

create function public.remove_room_member(p_room_id uuid, p_removed_user_id uuid)
returns table (room_id uuid, removed_user_id uuid, new_membership_epoch integer)
language sql
security invoker
set search_path = ''
as $$
  select * from private.remove_room_member(p_room_id, p_removed_user_id);
$$;

create function private.transfer_room_ownership(p_room_id uuid, p_new_owner_user_id uuid)
returns table (room_id uuid, previous_owner_user_id uuid, new_owner_user_id uuid, new_membership_epoch integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_user_id uuid := auth.uid();
  advanced_membership_epoch integer;
begin
  if private.current_device_id() is null then
    raise exception using errcode = '42501', message = 'an active device session is required';
  end if;

  perform 1
  from public.rooms as room
  where room.id = p_room_id
    and room.owner_user_id = actor_user_id
  for update;

  if not found or p_new_owner_user_id = actor_user_id then
    raise exception using errcode = '42501', message = 'ownership transfer is not authorized';
  end if;
  if not exists (
    select 1
    from public.room_members as member
    where member.room_id = p_room_id
      and member.user_id = p_new_owner_user_id
  ) then
    raise exception using errcode = '22023', message = 'the new owner must already be a room member';
  end if;

  update public.room_members as member
  set member_role = 'ADMIN'
  where member.room_id = p_room_id
    and member.user_id = actor_user_id;

  update public.room_members as member
  set member_role = 'OWNER'
  where member.room_id = p_room_id
    and member.user_id = p_new_owner_user_id;

  update public.rooms as room
  set
    owner_user_id = p_new_owner_user_id,
    membership_epoch = room.membership_epoch + 1
  where room.id = p_room_id
    and room.membership_epoch < 2147483647
  returning room.membership_epoch into strict advanced_membership_epoch;

  return query
  select p_room_id, actor_user_id, p_new_owner_user_id, advanced_membership_epoch;
end;
$$;

create function public.transfer_room_ownership(p_room_id uuid, p_new_owner_user_id uuid)
returns table (room_id uuid, previous_owner_user_id uuid, new_owner_user_id uuid, new_membership_epoch integer)
language sql
security invoker
set search_path = ''
as $$
  select * from private.transfer_room_ownership(p_room_id, p_new_owner_user_id);
$$;

create function private.revoke_device(p_device_id uuid)
returns table (revoked_device_id uuid, revoked_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_user_id uuid := auth.uid();
  revocation_time timestamptz := statement_timestamp();
begin
  if private.current_device_id() is null then
    raise exception using errcode = '42501', message = 'an active device session is required';
  end if;

  update public.devices as device
  set revoked_at = revocation_time
  where device.id = p_device_id
    and device.user_id = actor_user_id
    and device.revoked_at is null;

  if not found then
    raise exception using errcode = '42501', message = 'device revocation is not authorized';
  end if;

  delete from private.device_sessions as device_session
  where device_session.device_id = p_device_id;

  delete from private.device_registration_reservations as reservation
  where reservation.user_id = actor_user_id
    and reservation.device_id = p_device_id;

  return query select p_device_id, revocation_time;
end;
$$;

create function public.revoke_device(p_device_id uuid)
returns table (revoked_device_id uuid, revoked_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select * from private.revoke_device(p_device_id);
$$;

create function private.edge_actor_session_is_active(
  p_actor_user_id uuid,
  p_auth_session_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.session_belongs_to_user(p_auth_session_id, p_actor_user_id)
    and exists (
      select 1
      from private.device_sessions as device_session
      join public.devices as device on device.id = device_session.device_id
      where device_session.session_id = p_auth_session_id
        and device.user_id = p_actor_user_id
        and device.revoked_at is null
    );
$$;

create function private.record_account_access_attempt(
  p_source_digest bytea,
  p_operation text
)
returns table (accepted boolean, remaining_attempts integer, window_expires_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_window timestamptz := date_bin(
    interval '15 minutes',
    statement_timestamp(),
    timestamptz '2000-01-01 00:00:00+00'
  );
  persisted_attempt_count integer;
begin
  if octet_length(p_source_digest) <> 32
    or p_operation not in ('REGISTER', 'SIGN_IN', 'ROOM_REDEEM')
  then
    raise exception using errcode = '22023', message = 'rate-limit input is invalid';
  end if;

  insert into private.account_access_rate_limits (
    source_digest,
    operation,
    window_started_at,
    attempt_count,
    expires_at
  ) values (
    p_source_digest,
    p_operation,
    current_window,
    1,
    current_window + interval '2 days'
  )
  on conflict (source_digest, operation, window_started_at)
  do update
    set attempt_count = private.account_access_rate_limits.attempt_count + 1
    where private.account_access_rate_limits.attempt_count < 10
  returning account_access_rate_limits.attempt_count into persisted_attempt_count;

  return query
  select
    persisted_attempt_count is not null,
    greatest(10 - coalesce(persisted_attempt_count, 10), 0),
    current_window + interval '15 minutes';
end;
$$;

create function private.assert_invite_code_available(p_code_digest bytea)
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
  then
    raise exception using errcode = '23505', message = 'invite capability already exists';
  end if;
end;
$$;

create function private.configure_bootstrap_capability(
  p_code_digest bytea,
  p_expires_in_seconds integer default 86400
)
returns table (registration_kind text, expires_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  capability_expiry timestamptz;
begin
  if exists (select 1 from public.profiles) then
    raise exception using errcode = '42501', message = 'bootstrap is permanently closed after first registration';
  end if;
  if p_expires_in_seconds not between 60 and 86400 then
    raise exception using errcode = '22023', message = 'bootstrap expiry must be between one minute and 24 hours';
  end if;

  perform private.assert_invite_code_available(p_code_digest);
  capability_expiry := statement_timestamp() + make_interval(secs => p_expires_in_seconds);

  delete from private.bootstrap_capabilities where singleton;
  insert into private.bootstrap_capabilities (code_digest, expires_at)
  values (p_code_digest, capability_expiry);

  return query select 'BOOTSTRAP'::text, capability_expiry;
end;
$$;

create function private.issue_account_registration_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
  p_code_digest bytea,
  p_expires_in_seconds integer
)
returns table (invite_id uuid, invite_kind text, expires_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  created_invite private.account_registration_invites;
begin
  if not private.edge_actor_session_is_active(p_actor_user_id, p_auth_session_id)
    or not exists (
      select 1
      from public.room_members as room_member
      where room_member.user_id = p_actor_user_id
        and room_member.member_role in ('OWNER', 'ADMIN')
    )
  then
    raise exception using errcode = '42501', message = 'account invite issuance is not authorized';
  end if;
  if p_expires_in_seconds not between 60 and 86400 then
    raise exception using errcode = '22023', message = 'invite expiry must be between one minute and 24 hours';
  end if;

  perform private.assert_invite_code_available(p_code_digest);

  insert into private.account_registration_invites (
    issued_by_user_id,
    code_digest,
    expires_at
  ) values (
    p_actor_user_id,
    p_code_digest,
    statement_timestamp() + make_interval(secs => p_expires_in_seconds)
  ) returning * into created_invite;

  return query select created_invite.id, 'ACCOUNT_REGISTRATION'::text, created_invite.expires_at;
end;
$$;

create function private.issue_room_membership_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
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
  created_invite private.room_membership_invites;
begin
  if not private.edge_actor_session_is_active(p_actor_user_id, p_auth_session_id)
    or not exists (
      select 1
      from public.room_members as room_member
      where room_member.room_id = p_room_id
        and room_member.user_id = p_actor_user_id
        and room_member.member_role in ('OWNER', 'ADMIN')
    )
  then
    raise exception using errcode = '42501', message = 'room invite issuance is not authorized';
  end if;
  if p_expires_in_seconds not between 60 and 86400 then
    raise exception using errcode = '22023', message = 'invite expiry must be between one minute and 24 hours';
  end if;

  perform private.assert_invite_code_available(p_code_digest);

  insert into private.room_membership_invites (
    room_id,
    issued_by_user_id,
    code_digest,
    expires_at
  ) values (
    p_room_id,
    p_actor_user_id,
    p_code_digest,
    statement_timestamp() + make_interval(secs => p_expires_in_seconds)
  ) returning * into created_invite;

  return query
  select created_invite.id, 'ROOM_MEMBERSHIP'::text, created_invite.room_id, created_invite.expires_at;
end;
$$;

create function private.inspect_account_registration(
  p_code_digest bytea,
  p_redemption_id uuid
)
returns table (
  registration_state text,
  registration_kind text,
  expires_at timestamptz,
  existing_user_id uuid,
  existing_internal_email text,
  completed_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
begin
  if octet_length(p_code_digest) <> 32 then
    raise exception using errcode = '22023', message = 'registration capability digest is invalid';
  end if;

  return query
  select
    'REDEEMED'::text,
    receipt.registration_kind,
    receipt.expires_at,
    receipt.user_id,
    credential.internal_email,
    receipt.completed_at
  from private.account_registration_receipts as receipt
  join private.account_credentials as credential on credential.user_id = receipt.user_id
  where receipt.code_digest = p_code_digest
    and receipt.redemption_id = p_redemption_id
    and receipt.expires_at > statement_timestamp()

  union all

  select
    'AVAILABLE'::text,
    'ACCOUNT_REGISTRATION'::text,
    invite.expires_at,
    null::uuid,
    null::text,
    null::timestamptz
  from private.account_registration_invites as invite
  where invite.code_digest = p_code_digest
    and invite.expires_at > statement_timestamp()

  union all

  select
    'AVAILABLE'::text,
    'BOOTSTRAP'::text,
    capability.expires_at,
    null::uuid,
    null::text,
    null::timestamptz
  from private.bootstrap_capabilities as capability
  where capability.code_digest = p_code_digest
    and capability.expires_at > statement_timestamp()
    and not exists (select 1 from public.profiles)

  limit 1;
end;
$$;

create function private.resolve_account_login(p_username_digest bytea)
returns table (user_id uuid, internal_email text)
language sql
stable
security definer
set search_path = ''
as $$
  select credential.user_id, credential.internal_email
  from private.account_credentials as credential
  where credential.username_digest = p_username_digest
  limit 1;
$$;

create function private.reserve_device_registration(
  p_user_id uuid,
  p_auth_session_id uuid,
  p_device_id uuid
)
returns table (
  user_id uuid,
  device_id uuid,
  signal_device_id smallint,
  expires_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  reservation_time timestamptz := statement_timestamp();
  reservation_expiry timestamptz := reservation_time + interval '15 minutes';
  persisted_reservation private.device_registration_reservations;
  existing_device public.devices;
  selected_signal_device_id smallint;
  registered_or_pending_device_count integer;
begin
  if not private.session_belongs_to_user(p_auth_session_id, p_user_id) then
    raise exception using errcode = '42501', message = 'a verified non-anonymous auth session is required';
  end if;

  perform 1
  from public.profiles as profile
  where profile.user_id = p_user_id
  for update;

  if not found then
    raise exception using errcode = '42501', message = 'the account profile is unavailable';
  end if;

  delete from private.device_registration_reservations as expired_reservation
  where expired_reservation.user_id = p_user_id
    and expired_reservation.expires_at <= reservation_time;

  select reservation.*
    into persisted_reservation
  from private.device_registration_reservations as reservation
  where reservation.auth_session_id = p_auth_session_id
  for update;

  if found then
    if persisted_reservation.user_id <> p_user_id
      or persisted_reservation.device_id <> p_device_id
    then
      raise exception using errcode = '23505', message = 'the auth session already reserved another device';
    end if;

    select device.*
      into existing_device
    from public.devices as device
    where device.id = p_device_id;

    if found and (
      existing_device.user_id <> p_user_id
      or existing_device.revoked_at is not null
      or existing_device.signal_device_id <> persisted_reservation.signal_device_id
    ) then
      raise exception using errcode = '42501', message = 'device registration reservation is no longer valid';
    end if;

    update private.device_registration_reservations as reservation
    set
      reserved_at = reservation_time,
      expires_at = reservation_expiry
    where reservation.auth_session_id = p_auth_session_id
    returning reservation.* into strict persisted_reservation;

    return query
    select
      persisted_reservation.user_id,
      persisted_reservation.device_id,
      persisted_reservation.signal_device_id,
      persisted_reservation.expires_at;
    return;
  end if;

  select device.*
    into existing_device
  from public.devices as device
  where device.id = p_device_id;

  if found then
    if existing_device.user_id <> p_user_id or existing_device.revoked_at is not null then
      raise exception using errcode = '42501', message = 'device registration is not authorized';
    end if;
    selected_signal_device_id := existing_device.signal_device_id;
  else
    select min(reservation.signal_device_id)
      into selected_signal_device_id
    from private.device_registration_reservations as reservation
    where reservation.user_id = p_user_id
      and reservation.device_id = p_device_id
      and reservation.expires_at > reservation_time;

    if selected_signal_device_id is null then
      select count(*)
        into registered_or_pending_device_count
      from (
        select device.id
        from public.devices as device
        where device.user_id = p_user_id
          and device.revoked_at is null
        union
        select reservation.device_id
        from private.device_registration_reservations as reservation
        where reservation.user_id = p_user_id
          and reservation.expires_at > reservation_time
      ) as registered_or_pending_device;

      if registered_or_pending_device_count >= 8 then
        raise exception using errcode = '23514', message = 'an account cannot reserve more than eight active devices';
      end if;

      select candidate.signal_device_id::smallint
        into selected_signal_device_id
      from generate_series(1, 127) as candidate(signal_device_id)
      where not exists (
        select 1
        from public.devices as device
        where device.user_id = p_user_id
          and device.signal_device_id = candidate.signal_device_id
      )
        and not exists (
          select 1
          from private.device_registration_reservations as reservation
          where reservation.user_id = p_user_id
            and reservation.signal_device_id = candidate.signal_device_id
            and reservation.expires_at > reservation_time
        )
      order by candidate.signal_device_id
      limit 1;

      if selected_signal_device_id is null then
        raise exception using errcode = '23514', message = 'no Signal device identifier is available';
      end if;
    end if;
  end if;

  insert into private.device_registration_reservations (
    auth_session_id,
    user_id,
    device_id,
    signal_device_id,
    reserved_at,
    expires_at
  ) values (
    p_auth_session_id,
    p_user_id,
    p_device_id,
    selected_signal_device_id,
    reservation_time,
    reservation_expiry
  ) returning * into strict persisted_reservation;

  return query
  select
    persisted_reservation.user_id,
    persisted_reservation.device_id,
    persisted_reservation.signal_device_id,
    persisted_reservation.expires_at;
end;
$$;

create function private.register_device_session(
  p_user_id uuid,
  p_auth_session_id uuid,
  p_device_id uuid,
  p_protocol_adapter_version smallint,
  p_registration_id integer,
  p_signal_device_id smallint,
  p_identity_key bytea,
  p_signed_pre_key_id integer,
  p_signed_pre_key_public bytea,
  p_signed_pre_key_signature bytea,
  p_kyber_pre_key_id integer,
  p_kyber_pre_key_public bytea,
  p_kyber_pre_key_signature bytea,
  p_one_time_pre_key_id integer,
  p_one_time_pre_key_public bytea
)
returns table (
  user_id uuid,
  device_id uuid,
  signal_device_id smallint,
  display_name text,
  bound_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  device_reservation private.device_registration_reservations;
  existing_device public.devices;
  bound_device_id uuid;
  persisted_bound_at timestamptz;
  persisted_display_name text;
  room_device_count integer;
  session_was_already_bound boolean := false;
begin
  if not private.session_belongs_to_user(p_auth_session_id, p_user_id) then
    raise exception using errcode = '42501', message = 'a verified non-anonymous auth session is required';
  end if;
  if (p_one_time_pre_key_id is null) <> (p_one_time_pre_key_public is null) then
    raise exception using errcode = '22023', message = 'one-time prekey id and public key must be supplied together';
  end if;

  select profile.display_name
    into persisted_display_name
  from public.profiles as profile
  where profile.user_id = p_user_id
  for update;

  if not found then
    raise exception using errcode = '42501', message = 'the account profile is unavailable';
  end if;

  select device_session.device_id
    into bound_device_id
  from private.device_sessions as device_session
  where device_session.session_id = p_auth_session_id
  for update;

  if found then
    if bound_device_id <> p_device_id then
      raise exception using errcode = '23505', message = 'the auth session is already bound to another device';
    end if;
    session_was_already_bound := true;
  else
    select reservation.*
      into device_reservation
    from private.device_registration_reservations as reservation
    where reservation.auth_session_id = p_auth_session_id
    for update;

    if not found
      or device_reservation.expires_at <= statement_timestamp()
      or device_reservation.user_id <> p_user_id
      or device_reservation.device_id <> p_device_id
      or device_reservation.signal_device_id <> p_signal_device_id
    then
      raise exception using errcode = '42501', message = 'an active device registration reservation is required';
    end if;
  end if;

  select device.*
    into existing_device
  from public.devices as device
  where device.id = p_device_id;

  if found then
    if existing_device.revoked_at is not null
      or existing_device.user_id <> p_user_id
      or existing_device.protocol_adapter_version <> p_protocol_adapter_version
      or existing_device.registration_id <> p_registration_id
      or existing_device.signal_device_id <> p_signal_device_id
      or existing_device.identity_key <> p_identity_key
    then
      raise exception using errcode = '42501', message = 'device identity does not match the registered account';
    end if;

    update public.devices as device
    set
      signed_pre_key_id = p_signed_pre_key_id,
      signed_pre_key_public = p_signed_pre_key_public,
      signed_pre_key_signature = p_signed_pre_key_signature,
      kyber_pre_key_id = p_kyber_pre_key_id,
      kyber_pre_key_public = p_kyber_pre_key_public,
      kyber_pre_key_signature = p_kyber_pre_key_signature
    where device.id = p_device_id;
  else
    if (
      select count(*)
      from public.devices as device
      where device.user_id = p_user_id and device.revoked_at is null
    ) >= 8 then
      raise exception using errcode = '23514', message = 'an account cannot register more than eight devices';
    end if;

    perform 1
    from public.rooms as room
    join public.room_members as own_membership
      on own_membership.room_id = room.id
     and own_membership.user_id = p_user_id
    order by room.id
    for update of room;

    select count(*)
      into room_device_count
    from public.room_members as own_membership
    join public.room_members as room_membership on room_membership.room_id = own_membership.room_id
    join public.devices as room_device
      on room_device.user_id = room_membership.user_id
     and room_device.revoked_at is null
    where own_membership.user_id = p_user_id
    group by own_membership.room_id
    order by count(*) desc
    limit 1;

    if coalesce(room_device_count, 0) >= 129 then
      raise exception using errcode = '23514', message = 'a room cannot require more than 128 recipient envelopes';
    end if;

    insert into public.devices (
      id,
      user_id,
      protocol_adapter_version,
      registration_id,
      signal_device_id,
      identity_key,
      signed_pre_key_id,
      signed_pre_key_public,
      signed_pre_key_signature,
      kyber_pre_key_id,
      kyber_pre_key_public,
      kyber_pre_key_signature
    ) values (
      p_device_id,
      p_user_id,
      p_protocol_adapter_version,
      p_registration_id,
      p_signal_device_id,
      p_identity_key,
      p_signed_pre_key_id,
      p_signed_pre_key_public,
      p_signed_pre_key_signature,
      p_kyber_pre_key_id,
      p_kyber_pre_key_public,
      p_kyber_pre_key_signature
    );

  end if;

  if p_one_time_pre_key_id is not null then
    insert into public.device_one_time_prekeys (device_id, pre_key_id, public_key)
    values (p_device_id, p_one_time_pre_key_id, p_one_time_pre_key_public)
    on conflict on constraint device_one_time_prekeys_pkey do nothing;

    if not exists (
      select 1
      from public.device_one_time_prekeys as one_time_pre_key
      where one_time_pre_key.device_id = p_device_id
        and one_time_pre_key.pre_key_id = p_one_time_pre_key_id
        and one_time_pre_key.public_key = p_one_time_pre_key_public
    ) then
      raise exception using errcode = '23505', message = 'one-time prekey identity does not match the registered device';
    end if;
  end if;

  if not session_was_already_bound then
    insert into private.device_sessions (session_id, device_id)
    values (p_auth_session_id, p_device_id);
  end if;

  select device_session.device_id, device_session.bound_at
    into strict bound_device_id, persisted_bound_at
  from private.device_sessions as device_session
  where device_session.session_id = p_auth_session_id;

  if bound_device_id <> p_device_id then
    raise exception using errcode = '23505', message = 'the auth session is already bound to another device';
  end if;

  delete from private.device_registration_reservations as reservation
  where reservation.auth_session_id = p_auth_session_id;

  return query
  select
    p_user_id,
    p_device_id,
    p_signal_device_id,
    persisted_display_name,
    persisted_bound_at;
end;
$$;

create function private.redeem_account_registration(
  p_code_digest bytea,
  p_redemption_id uuid,
  p_username_digest bytea,
  p_internal_email text,
  p_user_id uuid,
  p_display_name text
)
returns table (
  registration_kind text,
  user_id uuid,
  completed_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  persisted_receipt private.account_registration_receipts;
  registration_invite private.account_registration_invites;
  bootstrap_capability private.bootstrap_capabilities;
  selected_registration_kind text;
  completion_time timestamptz := statement_timestamp();
  auth_email text;
  auth_registration_authorized boolean;
begin
  if octet_length(p_code_digest) <> 32 or octet_length(p_username_digest) <> 32 then
    raise exception using errcode = '22023', message = 'registration is not authorized';
  end if;

  select
    auth_user.email,
    coalesce(
      (auth_user.raw_app_meta_data ->> 'synapse_private_registration_authority')::boolean,
      false
    )
    into strict auth_email, auth_registration_authorized
  from auth.users as auth_user
  where auth_user.id = p_user_id;

  if lower(auth_email) <> lower(p_internal_email)
    or lower(p_internal_email) !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@identity[.]synapse-private[.]invalid$'
    or not auth_registration_authorized
  then
    raise exception using errcode = '42501', message = 'auth identity does not match redemption request';
  end if;

  select receipt.*
    into persisted_receipt
  from private.account_registration_receipts as receipt
  where receipt.redemption_id = p_redemption_id
    and receipt.code_digest = p_code_digest
  for update;

  if found then
    if persisted_receipt.user_id <> p_user_id
      or not exists (
        select 1
        from private.account_credentials as credential
        join public.profiles as profile on profile.user_id = credential.user_id
        where credential.user_id = p_user_id
          and credential.username_digest = p_username_digest
          and credential.internal_email = lower(p_internal_email)
          and profile.display_name = p_display_name
      )
    then
      raise exception using errcode = '23505', message = 'registration request was already completed';
    end if;

    return query
    select
      persisted_receipt.registration_kind,
      persisted_receipt.user_id,
      persisted_receipt.completed_at;
    return;
  end if;

  select invite.*
    into registration_invite
  from private.account_registration_invites as invite
  where invite.code_digest = p_code_digest
  for update;

  if found then
    if registration_invite.expires_at <= completion_time then
      raise exception using errcode = '22023', message = 'registration is not authorized';
    end if;
    selected_registration_kind := 'ACCOUNT_REGISTRATION';
    delete from private.account_registration_invites where id = registration_invite.id;
  else
    select capability.*
      into bootstrap_capability
    from private.bootstrap_capabilities as capability
    where capability.code_digest = p_code_digest
    for update;

    if not found
      or bootstrap_capability.expires_at <= completion_time
      or exists (select 1 from public.profiles)
    then
      raise exception using errcode = '22023', message = 'registration is not authorized';
    end if;
    selected_registration_kind := 'BOOTSTRAP';
    delete from private.bootstrap_capabilities where singleton;
  end if;

  insert into public.profiles (user_id, display_name)
  values (p_user_id, p_display_name);

  insert into private.account_credentials (user_id, username_digest, internal_email)
  values (p_user_id, p_username_digest, lower(p_internal_email));

  insert into private.account_registration_receipts (
    redemption_id,
    code_digest,
    registration_kind,
    user_id,
    completed_at,
    expires_at
  ) values (
    p_redemption_id,
    p_code_digest,
    selected_registration_kind,
    p_user_id,
    completion_time,
    completion_time + interval '24 hours'
  );

  return query select selected_registration_kind, p_user_id, completion_time;
end;
$$;

create function private.redeem_room_membership_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
  p_code_digest bytea,
  p_redemption_id uuid
)
returns table (
  room_id uuid,
  user_id uuid,
  membership_epoch integer,
  completed_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  persisted_receipt private.room_membership_invite_receipts;
  membership_invite private.room_membership_invites;
  advanced_membership_epoch integer;
  completion_time timestamptz := statement_timestamp();
  room_kind text;
  member_count integer;
begin
  if octet_length(p_code_digest) <> 32 then
    raise exception using errcode = '22023', message = 'room invitation is not authorized';
  end if;

  if not private.edge_actor_session_is_active(p_actor_user_id, p_auth_session_id) then
    raise exception using errcode = '42501', message = 'an active account session is required';
  end if;

  perform 1
  from public.profiles as profile
  where profile.user_id = p_actor_user_id
  for update;

  if not found then
    raise exception using errcode = '42501', message = 'the account profile is unavailable';
  end if;

  select receipt.*
    into persisted_receipt
  from private.room_membership_invite_receipts as receipt
  where receipt.redemption_id = p_redemption_id
    and receipt.code_digest = p_code_digest
  for update;

  if found then
    if persisted_receipt.user_id <> p_actor_user_id then
      raise exception using errcode = '23505', message = 'room invitation was already redeemed';
    end if;
    return query
    select
      persisted_receipt.room_id,
      persisted_receipt.user_id,
      persisted_receipt.membership_epoch,
      persisted_receipt.completed_at;
    return;
  end if;

  select invite.*
    into strict membership_invite
  from private.room_membership_invites as invite
  where invite.code_digest = p_code_digest
  for update;

  if membership_invite.expires_at <= completion_time then
    raise exception using errcode = '22023', message = 'room invitation is not authorized';
  end if;

  select room.room_kind
    into strict room_kind
  from public.rooms as room
  where room.id = membership_invite.room_id
  for update;

  select count(*)
    into member_count
  from public.room_members as room_member
  where room_member.room_id = membership_invite.room_id;

  if (
    select count(*)
    from public.room_members as existing_membership
    join public.devices as existing_device
      on existing_device.user_id = existing_membership.user_id
     and existing_device.revoked_at is null
    where existing_membership.room_id = membership_invite.room_id
  ) + (
    select count(*)
    from public.devices as joining_device
    where joining_device.user_id = p_actor_user_id
      and joining_device.revoked_at is null
  ) > 129 then
    raise exception using errcode = '23514', message = 'a room cannot require more than 128 recipient envelopes';
  end if;

  if exists (
    select 1
    from public.room_members
    where room_members.room_id = membership_invite.room_id
      and room_members.user_id = p_actor_user_id
  ) then
    raise exception using errcode = '23505', message = 'account is already a room member';
  end if;
  if room_kind = 'DIRECT' and member_count >= 2 then
    raise exception using errcode = '23514', message = 'direct rooms cannot have more than two members';
  end if;

  insert into public.room_members (room_id, user_id, member_role)
  values (membership_invite.room_id, p_actor_user_id, 'MEMBER');

  update public.rooms as room
  set membership_epoch = room.membership_epoch + 1
  where room.id = membership_invite.room_id
    and room.membership_epoch < 2147483647
  returning room.membership_epoch into strict advanced_membership_epoch;

  delete from private.room_membership_invites where id = membership_invite.id;

  insert into private.room_membership_invite_receipts (
    redemption_id,
    code_digest,
    room_id,
    user_id,
    membership_epoch,
    completed_at,
    expires_at
  ) values (
    p_redemption_id,
    p_code_digest,
    membership_invite.room_id,
    p_actor_user_id,
    advanced_membership_epoch,
    completion_time,
    completion_time + interval '24 hours'
  );

  return query
  select membership_invite.room_id, p_actor_user_id, advanced_membership_epoch, completion_time;
end;
$$;

create function public._edge_initialize_runtime_configuration(p_project_url text)
returns table (
  username_hmac_pepper text,
  rate_limit_hmac_pepper text,
  purge_secret text
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.initialize_runtime_configuration(p_project_url);
$$;

create function public._edge_record_account_access_attempt(
  p_source_digest bytea,
  p_operation text
)
returns table (accepted boolean, remaining_attempts integer, window_expires_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select * from private.record_account_access_attempt(p_source_digest, p_operation);
$$;

create function public._edge_issue_account_registration_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
  p_code_digest bytea,
  p_expires_in_seconds integer
)
returns table (invite_id uuid, invite_kind text, expires_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select * from private.issue_account_registration_invite(
    p_actor_user_id,
    p_auth_session_id,
    p_code_digest,
    p_expires_in_seconds
  );
$$;

create function public._edge_issue_room_membership_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
  p_room_id uuid,
  p_code_digest bytea,
  p_expires_in_seconds integer
)
returns table (invite_id uuid, invite_kind text, room_id uuid, expires_at timestamptz)
language sql
security invoker
set search_path = ''
as $$
  select * from private.issue_room_membership_invite(
    p_actor_user_id,
    p_auth_session_id,
    p_room_id,
    p_code_digest,
    p_expires_in_seconds
  );
$$;

create function public._edge_inspect_account_registration(
  p_code_digest bytea,
  p_redemption_id uuid
)
returns table (
  registration_state text,
  registration_kind text,
  expires_at timestamptz,
  existing_user_id uuid,
  existing_internal_email text,
  completed_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.inspect_account_registration(p_code_digest, p_redemption_id);
$$;

create function public._edge_resolve_account_login(p_username_digest bytea)
returns table (user_id uuid, internal_email text)
language sql
security invoker
set search_path = ''
as $$
  select * from private.resolve_account_login(p_username_digest);
$$;

create function public._edge_reserve_device_registration(
  p_user_id uuid,
  p_auth_session_id uuid,
  p_device_id uuid
)
returns table (
  user_id uuid,
  device_id uuid,
  signal_device_id smallint,
  expires_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.reserve_device_registration(
    p_user_id,
    p_auth_session_id,
    p_device_id
  );
$$;

create function public._edge_register_device_session(
  p_user_id uuid,
  p_auth_session_id uuid,
  p_device_id uuid,
  p_protocol_adapter_version smallint,
  p_registration_id integer,
  p_signal_device_id smallint,
  p_identity_key bytea,
  p_signed_pre_key_id integer,
  p_signed_pre_key_public bytea,
  p_signed_pre_key_signature bytea,
  p_kyber_pre_key_id integer,
  p_kyber_pre_key_public bytea,
  p_kyber_pre_key_signature bytea,
  p_one_time_pre_key_id integer,
  p_one_time_pre_key_public bytea
)
returns table (
  user_id uuid,
  device_id uuid,
  signal_device_id smallint,
  display_name text,
  bound_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.register_device_session(
    p_user_id,
    p_auth_session_id,
    p_device_id,
    p_protocol_adapter_version,
    p_registration_id,
    p_signal_device_id,
    p_identity_key,
    p_signed_pre_key_id,
    p_signed_pre_key_public,
    p_signed_pre_key_signature,
    p_kyber_pre_key_id,
    p_kyber_pre_key_public,
    p_kyber_pre_key_signature,
    p_one_time_pre_key_id,
    p_one_time_pre_key_public
  );
$$;

create function public._edge_redeem_account_registration(
  p_code_digest bytea,
  p_redemption_id uuid,
  p_username_digest bytea,
  p_internal_email text,
  p_user_id uuid,
  p_display_name text
)
returns table (
  registration_kind text,
  user_id uuid,
  completed_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.redeem_account_registration(
    p_code_digest,
    p_redemption_id,
    p_username_digest,
    p_internal_email,
    p_user_id,
    p_display_name
  );
$$;

create function public._edge_redeem_room_membership_invite(
  p_actor_user_id uuid,
  p_auth_session_id uuid,
  p_code_digest bytea,
  p_redemption_id uuid
)
returns table (
  room_id uuid,
  user_id uuid,
  membership_epoch integer,
  completed_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.redeem_room_membership_invite(
    p_actor_user_id,
    p_auth_session_id,
    p_code_digest,
    p_redemption_id
  );
$$;

revoke all on all functions in schema private from public, anon, authenticated;

revoke all on function public.create_room(text, integer) from public, anon, authenticated;
revoke all on function public.publish_device_one_time_prekeys(integer[], text[]) from public, anon, authenticated;
revoke all on function public.claim_device_prekey(uuid) from public, anon, authenticated;
revoke all on function public.send_message(uuid, uuid, jsonb, uuid) from public, anon, authenticated;
revoke all on function public.send_reaction(uuid, uuid, jsonb) from public, anon, authenticated;
revoke all on function public.delete_message_for_everyone(uuid) from public, anon, authenticated;
revoke all on function public.update_room_retention(uuid, integer) from public, anon, authenticated;
revoke all on function public.update_room_member_role(uuid, uuid, text) from public, anon, authenticated;
revoke all on function public.remove_room_member(uuid, uuid) from public, anon, authenticated;
revoke all on function public.transfer_room_ownership(uuid, uuid) from public, anon, authenticated;
revoke all on function public.revoke_device(uuid) from public, anon, authenticated;
revoke all on function public._edge_initialize_runtime_configuration(text) from public, anon, authenticated;
revoke all on function public._edge_record_account_access_attempt(bytea, text) from public, anon, authenticated;
revoke all on function public._edge_issue_account_registration_invite(uuid, uuid, bytea, integer) from public, anon, authenticated;
revoke all on function public._edge_issue_room_membership_invite(uuid, uuid, uuid, bytea, integer) from public, anon, authenticated;
revoke all on function public._edge_inspect_account_registration(bytea, uuid) from public, anon, authenticated;
revoke all on function public._edge_resolve_account_login(bytea) from public, anon, authenticated;
revoke all on function public._edge_reserve_device_registration(uuid, uuid, uuid) from public, anon, authenticated;
revoke all on function public._edge_register_device_session(
  uuid,
  uuid,
  uuid,
  smallint,
  integer,
  smallint,
  bytea,
  integer,
  bytea,
  bytea,
  integer,
  bytea,
  bytea,
  integer,
  bytea
) from public, anon, authenticated;
revoke all on function public._edge_redeem_account_registration(
  bytea,
  uuid,
  bytea,
  text,
  uuid,
  text
) from public, anon, authenticated;
revoke all on function public._edge_redeem_room_membership_invite(uuid, uuid, bytea, uuid)
from public, anon, authenticated;

grant execute on function private.current_device_id() to authenticated;
grant execute on function private.is_active_room_member(uuid) to authenticated;
grant execute on function private.can_manage_room(uuid) to authenticated;
grant execute on function private.can_access_message(uuid) to authenticated;
grant execute on function private.shares_room_with(uuid) to authenticated;
grant execute on function private.can_read_attachment_object(text) to authenticated;
grant execute on function private.can_upload_attachment_object(text) to authenticated;
grant execute on function private.create_room(text, integer) to authenticated;
grant execute on function private.publish_device_one_time_prekeys(integer[], text[]) to authenticated;
grant execute on function private.claim_device_prekey(uuid) to authenticated;
grant execute on function private.send_message(uuid, uuid, uuid, jsonb) to authenticated;
grant execute on function private.send_reaction(uuid, uuid, jsonb) to authenticated;
grant execute on function private.delete_message_for_everyone(uuid) to authenticated;
grant execute on function private.update_room_retention(uuid, integer) to authenticated;
grant execute on function private.update_room_member_role(uuid, uuid, text) to authenticated;
grant execute on function private.remove_room_member(uuid, uuid) to authenticated;
grant execute on function private.transfer_room_ownership(uuid, uuid) to authenticated;
grant execute on function private.revoke_device(uuid) to authenticated;

grant execute on function public.create_room(text, integer) to authenticated;
grant execute on function public.publish_device_one_time_prekeys(integer[], text[]) to authenticated;
grant execute on function public.claim_device_prekey(uuid) to authenticated;
grant execute on function public.send_message(uuid, uuid, jsonb, uuid) to authenticated;
grant execute on function public.send_reaction(uuid, uuid, jsonb) to authenticated;
grant execute on function public.delete_message_for_everyone(uuid) to authenticated;
grant execute on function public.update_room_retention(uuid, integer) to authenticated;
grant execute on function public.update_room_member_role(uuid, uuid, text) to authenticated;
grant execute on function public.remove_room_member(uuid, uuid) to authenticated;
grant execute on function public.transfer_room_ownership(uuid, uuid) to authenticated;
grant execute on function public.revoke_device(uuid) to authenticated;

grant execute on function private.session_belongs_to_user(uuid, uuid) to service_role;
grant execute on function private.initialize_runtime_configuration(text) to service_role;
grant execute on function private.record_account_access_attempt(bytea, text) to service_role;
grant execute on function private.issue_account_registration_invite(uuid, uuid, bytea, integer) to service_role;
grant execute on function private.issue_room_membership_invite(uuid, uuid, uuid, bytea, integer) to service_role;
grant execute on function private.inspect_account_registration(bytea, uuid) to service_role;
grant execute on function private.resolve_account_login(bytea) to service_role;
grant execute on function private.reserve_device_registration(uuid, uuid, uuid) to service_role;
grant execute on function private.register_device_session(
  uuid,
  uuid,
  uuid,
  smallint,
  integer,
  smallint,
  bytea,
  integer,
  bytea,
  bytea,
  integer,
  bytea,
  bytea,
  integer,
  bytea
) to service_role;
grant execute on function private.redeem_account_registration(
  bytea,
  uuid,
  bytea,
  text,
  uuid,
  text
) to service_role;
grant execute on function private.redeem_room_membership_invite(uuid, uuid, bytea, uuid) to service_role;

grant execute on function public._edge_initialize_runtime_configuration(text) to service_role;
grant execute on function public._edge_record_account_access_attempt(bytea, text) to service_role;
grant execute on function public._edge_issue_account_registration_invite(uuid, uuid, bytea, integer) to service_role;
grant execute on function public._edge_issue_room_membership_invite(uuid, uuid, uuid, bytea, integer) to service_role;
grant execute on function public._edge_inspect_account_registration(bytea, uuid) to service_role;
grant execute on function public._edge_resolve_account_login(bytea) to service_role;
grant execute on function public._edge_reserve_device_registration(uuid, uuid, uuid) to service_role;
grant execute on function public._edge_register_device_session(
  uuid,
  uuid,
  uuid,
  smallint,
  integer,
  smallint,
  bytea,
  integer,
  bytea,
  bytea,
  integer,
  bytea,
  bytea,
  integer,
  bytea
) to service_role;
grant execute on function public._edge_redeem_account_registration(
  bytea,
  uuid,
  bytea,
  text,
  uuid,
  text
) to service_role;
grant execute on function public._edge_redeem_room_membership_invite(uuid, uuid, bytea, uuid) to service_role;

-- No content-bearing table is added to supabase_realtime. Postgres Changes sends
-- DELETE events without RLS row filtering, so clients use authenticated polling
-- until a separately reviewed private Broadcast authorization contract exists.
