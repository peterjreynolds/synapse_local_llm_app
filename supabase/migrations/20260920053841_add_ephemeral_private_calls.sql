-- Direct calling is explicitly opt-in: encrypted signals, not SDP or ICE, enter this schema.
-- Phones own capture consent and immediate teardown; these leases bound remote stale state.
create type public.private_call_receipt as (
  call_id uuid, room_id uuid, membership_epoch integer,
  caller_user_id uuid, caller_device_id uuid, recipient_user_id uuid,
  accepted_device_id uuid, media_kind text, state text, terminal_reason text,
  created_at timestamptz, ring_expires_at timestamptz, lease_expires_at timestamptz,
  client_mutation_id uuid
);

create table private.call_sessions (
  id uuid primary key,
  room_id uuid not null references public.rooms(id) on delete cascade,
  membership_epoch integer not null check (membership_epoch > 0),
  caller_user_id uuid not null references public.profiles(user_id) on delete cascade,
  caller_device_id uuid not null references public.devices(id) on delete cascade,
  caller_auth_session_id uuid references auth.sessions(id) on delete set null,
  recipient_user_id uuid not null references public.profiles(user_id) on delete cascade,
  accepted_device_id uuid references public.devices(id) on delete cascade,
  accepted_auth_session_id uuid references auth.sessions(id) on delete set null,
  media_kind text not null check (media_kind in ('VOICE', 'VIDEO')),
  state text not null default 'RINGING' check (state in ('RINGING', 'ACTIVE', 'ENDED')),
  terminal_reason text check (terminal_reason in ('CANCELLED','DECLINED','ENDED','TIMEOUT','ACCESS_REVOKED')),
  created_at timestamptz not null default statement_timestamp(),
  ring_expires_at timestamptz not null,
  caller_lease_expires_at timestamptz not null,
  recipient_lease_expires_at timestamptz,
  ended_at timestamptz,
  check (caller_user_id <> recipient_user_id),
  check (ring_expires_at > created_at and ring_expires_at <= created_at + interval '60 seconds'),
  check ((state = 'ENDED') = (ended_at is not null and terminal_reason is not null)),
  check (state <> 'ACTIVE' or (accepted_device_id is not null and recipient_lease_expires_at is not null))
);
create index call_sessions_room_idx on private.call_sessions(room_id);
create index call_sessions_caller_idx on private.call_sessions(caller_user_id, created_at);
create index call_sessions_recipient_idx on private.call_sessions(recipient_user_id);
create index call_sessions_accepted_device_idx on private.call_sessions(accepted_device_id);
create index call_sessions_caller_device_idx on private.call_sessions(caller_device_id);
create index call_sessions_caller_auth_idx on private.call_sessions(caller_auth_session_id);
create index call_sessions_accepted_auth_idx on private.call_sessions(accepted_auth_session_id);
create index call_sessions_expiry_idx on private.call_sessions(state, ring_expires_at, ended_at);

create table private.call_devices (
  call_id uuid not null references private.call_sessions(id) on delete cascade,
  device_id uuid not null references public.devices(id) on delete cascade,
  last_sequence integer not null default -1 check (last_sequence between -1 and 4096),
  primary key (call_id, device_id)
);
create index call_devices_device_idx on private.call_devices(device_id, call_id);
create table private.call_device_leases (
  device_id uuid primary key references public.devices(id) on delete cascade,
  call_id uuid not null references private.call_sessions(id) on delete cascade
);
create index call_device_leases_call_idx on private.call_device_leases(call_id);

create table private.call_signals (
  call_id uuid not null references private.call_sessions(id) on delete cascade,
  sender_device_id uuid not null references public.devices(id) on delete cascade,
  client_mutation_id uuid not null,
  sequence integer not null check (sequence between 0 and 4096),
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  primary key (call_id, sender_device_id, sequence),
  unique (sender_device_id, client_mutation_id),
  check (expires_at > created_at and expires_at <= created_at + interval '60 seconds')
);
create index call_signals_expiry_idx on private.call_signals(expires_at);
create table private.call_signal_envelopes (
  call_id uuid not null,
  sender_device_id uuid not null,
  sequence integer not null,
  recipient_device_id uuid not null references public.devices(id) on delete cascade,
  protocol_adapter_version smallint not null check (protocol_adapter_version = 1),
  signal_message_type text not null check (signal_message_type in ('PREKEY','WHISPER')),
  ciphertext bytea not null check (octet_length(ciphertext) between 1 and 65536),
  primary key (call_id, sender_device_id, sequence, recipient_device_id),
  foreign key (call_id, sender_device_id, sequence)
    references private.call_signals(call_id, sender_device_id, sequence) on delete cascade,
  check (sender_device_id <> recipient_device_id)
);
create index call_signal_envelopes_recipient_idx on private.call_signal_envelopes(recipient_device_id, call_id);
create table private.call_mutation_receipts (
  actor_device_id uuid not null references public.devices(id) on delete cascade,
  client_mutation_id uuid not null,
  call_id uuid not null references private.call_sessions(id) on delete cascade,
  request_digest bytea not null check (octet_length(request_digest) = 32),
  receipt jsonb not null,
  primary key (actor_device_id, client_mutation_id)
);
create index call_mutation_receipts_call_idx on private.call_mutation_receipts(call_id);

alter table private.call_sessions enable row level security;
alter table private.call_devices enable row level security;
alter table private.call_device_leases enable row level security;
alter table private.call_signals enable row level security;
alter table private.call_signal_envelopes enable row level security;
alter table private.call_mutation_receipts enable row level security;
revoke all on private.call_sessions, private.call_devices, private.call_device_leases,
  private.call_signals, private.call_signal_envelopes, private.call_mutation_receipts
  from public, anon, authenticated, service_role;

create function private.call_receipt(p_call private.call_sessions, p_mutation_id uuid)
returns public.private_call_receipt language sql stable security invoker set search_path = '' as $$
  select p_call.id, p_call.room_id, p_call.membership_epoch,
    p_call.caller_user_id, p_call.caller_device_id, p_call.recipient_user_id,
    p_call.accepted_device_id, p_call.media_kind, p_call.state, p_call.terminal_reason,
    p_call.created_at, p_call.ring_expires_at,
    case when p_call.state = 'RINGING' then p_call.ring_expires_at
      when p_call.state = 'ENDED' then p_call.ended_at
      else least(p_call.caller_lease_expires_at, p_call.recipient_lease_expires_at,
        p_call.created_at + interval '60 minutes') end,
    p_mutation_id;
$$;

create function private.call_context_is_current(p_call private.call_sessions)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.rooms room
    where room.id = p_call.room_id and room.room_kind = 'DIRECT'
      and room.membership_epoch = p_call.membership_epoch
      and (select count(*) from public.room_members member where member.room_id = room.id) = 2
      and exists (select 1 from public.room_members member
        where member.room_id = room.id and member.user_id = p_call.caller_user_id)
      and exists (select 1 from public.room_members member
        where member.room_id = room.id and member.user_id = p_call.recipient_user_id)
  ) and exists (select 1 from public.devices device where device.id = p_call.caller_device_id
    and device.user_id = p_call.caller_user_id and device.revoked_at is null)
  and private.session_belongs_to_user(p_call.caller_auth_session_id, p_call.caller_user_id)
  and exists (select 1 from private.device_sessions binding
    where binding.session_id=p_call.caller_auth_session_id and binding.device_id=p_call.caller_device_id)
  and (p_call.accepted_device_id is null or (
    exists (select 1 from public.devices device where device.id = p_call.accepted_device_id
      and device.user_id = p_call.recipient_user_id and device.revoked_at is null)
    and private.session_belongs_to_user(p_call.accepted_auth_session_id, p_call.recipient_user_id)
    and exists (select 1 from private.device_sessions binding
      where binding.session_id=p_call.accepted_auth_session_id and binding.device_id=p_call.accepted_device_id)
  ));
$$;

-- Called only by authenticated operations or the database-owned cron job.
create function private.purge_expired_private_calls()
returns integer language plpgsql security definer set search_path = '' as $$
declare expired_call private.call_sessions; purged_count integer;
begin
  for expired_call in
    select call.* from private.call_sessions call
    where call.state <> 'ENDED' and (
      not private.call_context_is_current(call)
      or (call.state = 'RINGING' and call.ring_expires_at <= statement_timestamp())
      or (call.state = 'ACTIVE' and least(call.caller_lease_expires_at,
        call.recipient_lease_expires_at, call.created_at + interval '60 minutes') <= statement_timestamp())
    ) order by call.id for update skip locked
  loop
    update private.call_sessions set state = 'ENDED', ended_at = statement_timestamp(),
      terminal_reason = case when private.call_context_is_current(expired_call)
        then 'TIMEOUT' else 'ACCESS_REVOKED' end where id = expired_call.id;
  end loop;
  delete from private.call_signals signal where signal.expires_at <= statement_timestamp()
    or exists (select 1 from private.call_sessions call where call.id = signal.call_id and call.state = 'ENDED');
  delete from private.call_device_leases lease where exists (
    select 1 from private.call_sessions call where call.id = lease.call_id and call.state = 'ENDED');
  delete from private.call_sessions call where call.ended_at <= statement_timestamp() - interval '120 seconds';
  get diagnostics purged_count = row_count;
  return purged_count;
end;
$$;

create function private.require_private_call(p_call_id uuid)
returns private.call_sessions language plpgsql security definer set search_path = '' as $$
declare actor_device uuid := private.current_device_id(); selected_call private.call_sessions;
begin
  if auth.uid() is null or actor_device is null then
    raise exception using errcode='42501', message='an active device session is required';
  end if;
  select call.* into selected_call from private.call_sessions call where call.id = p_call_id for update;
  if not found or not exists (select 1 from private.call_devices participant
    where participant.call_id = p_call_id and participant.device_id = actor_device)
    or auth.uid() not in (selected_call.caller_user_id, selected_call.recipient_user_id)
    or not private.call_context_is_current(selected_call) then
    raise exception using errcode='42501', message='call access is not authorized';
  end if;
  return selected_call;
end;
$$;

create function private.find_call_mutation_receipt(p_device_id uuid, p_mutation_id uuid, p_digest bytea)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare persisted private.call_mutation_receipts;
begin
  if p_mutation_id is null then
    raise exception using errcode='22023', message='call mutation id is required';
  end if;
  select receipt.* into persisted from private.call_mutation_receipts receipt
    where receipt.actor_device_id = p_device_id and receipt.client_mutation_id = p_mutation_id;
  if not found then return null; end if;
  if persisted.request_digest <> p_digest then
    raise exception using errcode='23505', message='call mutation id was already used';
  end if;
  return persisted.receipt;
end;
$$;

create function private.store_private_call_signal(
  p_call private.call_sessions, p_sender_device_id uuid, p_mutation_id uuid,
  p_sequence integer, p_expires_at timestamptz, p_envelopes jsonb
)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare expected_recipients uuid[]; supplied_recipients uuid[]; envelope jsonb;
  ciphertext_bytes integer; recipient uuid; expected_sequence integer;
begin
  if p_expires_at is null or p_expires_at <= statement_timestamp()
    or p_expires_at > statement_timestamp() + interval '60 seconds'
    or p_sequence is null or p_sequence not between 0 and 4096
    or jsonb_typeof(p_envelopes) is distinct from 'array'
    or jsonb_array_length(p_envelopes) not between 1 and 8 then
    raise exception using errcode='22023', message='call signal bounds are invalid';
  end if;
  if p_call.state = 'RINGING' then
    if p_sender_device_id <> p_call.caller_device_id or p_call.ring_expires_at <= statement_timestamp() then
      raise exception using errcode='42501', message='only the caller can signal before acceptance';
    end if;
    select array_agg(participant.device_id order by participant.device_id) into expected_recipients
    from private.call_devices participant join public.devices device on device.id = participant.device_id
    where participant.call_id = p_call.id and device.user_id = p_call.recipient_user_id and device.revoked_at is null;
  elsif p_call.state = 'ACTIVE' and p_sender_device_id in (p_call.caller_device_id,p_call.accepted_device_id)
    and least(p_call.caller_lease_expires_at,p_call.recipient_lease_expires_at,
      p_call.created_at+interval '60 minutes') > statement_timestamp() then
    expected_recipients := array[case when p_sender_device_id = p_call.caller_device_id
      then p_call.accepted_device_id else p_call.caller_device_id end];
  else
    raise exception using errcode='42501', message='call signaling is not active for this device';
  end if;
  for envelope in select value from jsonb_array_elements(p_envelopes) loop
    if jsonb_typeof(envelope) <> 'object' or not (envelope ?& array[
      'recipient_device_id','protocol_adapter_version','signal_message_type','ciphertext_hex'])
      or exists (select 1 from jsonb_object_keys(envelope) key where key not in (
        'recipient_device_id','protocol_adapter_version','signal_message_type','ciphertext_hex'))
      or envelope->>'protocol_adapter_version' is distinct from '1'
      or coalesce(envelope->>'signal_message_type','') not in ('PREKEY','WHISPER') then
      raise exception using errcode='22023', message='call signal envelope is invalid';
    end if;
    recipient := (envelope->>'recipient_device_id')::uuid;
    supplied_recipients := array_append(supplied_recipients, recipient);
    ciphertext_bytes := octet_length(private.decode_bounded_hex(envelope->>'ciphertext_hex',1,65536,'ciphertext_hex'));
    if (select count(*) from private.call_signal_envelopes stored
      where stored.call_id=p_call.id and stored.recipient_device_id=recipient) >= 128
      or (select coalesce(sum(octet_length(stored.ciphertext)),0) from private.call_signal_envelopes stored
        where stored.call_id=p_call.id and stored.recipient_device_id=recipient) + ciphertext_bytes > 131072 then
      raise exception using errcode='54000', message='call signal capacity exceeded';
    end if;
  end loop;
  select array_agg(recipient_id order by recipient_id) into supplied_recipients from unnest(supplied_recipients) recipient_id;
  if expected_recipients is null or supplied_recipients is distinct from expected_recipients then
    raise exception using errcode='42501', message='call signal recipient set is not authorized';
  end if;
  select participant.last_sequence+1 into expected_sequence from private.call_devices participant
    where participant.call_id=p_call.id and participant.device_id=p_sender_device_id;
  if expected_sequence is null or p_sequence <> expected_sequence then
    raise exception using errcode='23505', message='call signal sequence is not the next sequence';
  end if;
  insert into private.call_signals(call_id,sender_device_id,client_mutation_id,sequence,expires_at)
    values(p_call.id,p_sender_device_id,p_mutation_id,p_sequence,p_expires_at);
  insert into private.call_signal_envelopes(call_id,sender_device_id,sequence,recipient_device_id,
    protocol_adapter_version,signal_message_type,ciphertext)
    select p_call.id,p_sender_device_id,p_sequence,(entry->>'recipient_device_id')::uuid,
      1,(entry->>'signal_message_type'),private.decode_bounded_hex(entry->>'ciphertext_hex',1,65536,'ciphertext_hex')
    from jsonb_array_elements(p_envelopes) entry;
  update private.call_devices set last_sequence=p_sequence where call_id=p_call.id and device_id=p_sender_device_id;
  return jsonb_build_object('call_id',p_call.id,'client_mutation_id',p_mutation_id,'sequence',p_sequence,
    'created_at',statement_timestamp(),'expires_at',p_expires_at);
end;
$$;

create function private.create_private_call(
  p_call_id uuid,p_room_id uuid,p_membership_epoch integer,p_media_kind text,
  p_client_mutation_id uuid,p_expires_at timestamptz,p_envelopes jsonb
)
returns setof public.private_call_receipt language plpgsql security definer set search_path = '' as $$
declare actor_device uuid := private.current_device_id(); recipient_user uuid; room public.rooms;
  recipient_devices uuid[]; participant_device uuid; created_call private.call_sessions;
  request_digest bytea; saved_receipt jsonb; receipt public.private_call_receipt;
begin
  if auth.uid() is null or actor_device is null or p_call_id is null then
    raise exception using errcode='42501', message='an active device session is required';
  end if;
  perform private.purge_expired_private_calls();
  select selected.* into room from public.rooms selected where selected.id=p_room_id for share;
  if not found or room.room_kind <> 'DIRECT' or room.membership_epoch <> p_membership_epoch
    or not private.is_active_room_member(p_room_id)
    or (select count(*) from public.room_members member where member.room_id=p_room_id) <> 2 then
    raise exception using errcode='42501', message='a current two-member direct room is required';
  end if;
  request_digest := extensions.digest(convert_to(jsonb_build_array('CREATE',p_call_id,p_room_id,
    p_membership_epoch,p_media_kind,p_expires_at,p_envelopes)::text,'UTF8'),'sha256');
  perform pg_advisory_xact_lock(hashtextextended(actor_device::text || '/call/' || p_client_mutation_id::text,0));
  saved_receipt := private.find_call_mutation_receipt(actor_device,p_client_mutation_id,request_digest);
  if saved_receipt is not null then
    return next jsonb_populate_record(null::public.private_call_receipt,saved_receipt); return;
  end if;
  if p_media_kind is null or p_media_kind not in ('VOICE','VIDEO')
    or p_expires_at is null or p_expires_at <= statement_timestamp()
    or p_expires_at > statement_timestamp()+interval '60 seconds' then
    raise exception using errcode='22023', message='call invitation bounds are invalid';
  end if;
  if (select count(*) from private.call_sessions call where call.caller_user_id=auth.uid()
    and call.created_at > statement_timestamp()-interval '1 minute') >= 6 then
    raise exception using errcode='54000', message='call invitation rate exceeded';
  end if;
  select member.user_id into strict recipient_user from public.room_members member
    where member.room_id=p_room_id and member.user_id<>auth.uid();
  select array_agg(device.id order by device.id) into recipient_devices from public.devices device
    where device.user_id=recipient_user and device.revoked_at is null;
  if coalesce(cardinality(recipient_devices),0) not between 1 and 8 then
    raise exception using errcode='42501', message='the recipient has no available call device';
  end if;
  for participant_device in select device_id from unnest(recipient_devices || actor_device) device_id order by device_id loop
    perform pg_advisory_xact_lock(hashtextextended(participant_device::text || '/active-call',0));
  end loop;
  if exists(select 1 from private.call_device_leases lease where lease.device_id=any(recipient_devices || actor_device)) then
    raise exception using errcode='23505', message='a participating device already has an active call';
  end if;
  insert into private.call_sessions(id,room_id,membership_epoch,caller_user_id,caller_device_id,
    caller_auth_session_id,recipient_user_id,media_kind,ring_expires_at,caller_lease_expires_at)
    values(p_call_id,p_room_id,p_membership_epoch,auth.uid(),actor_device,
      (auth.jwt()->>'session_id')::uuid,recipient_user,p_media_kind,p_expires_at,p_expires_at)
    returning * into created_call;
  insert into private.call_devices(call_id,device_id) select p_call_id,device_id from unnest(recipient_devices || actor_device) device_id;
  insert into private.call_device_leases(call_id,device_id) select p_call_id,device_id from unnest(recipient_devices || actor_device) device_id;
  perform private.store_private_call_signal(created_call,actor_device,p_client_mutation_id,0,p_expires_at,p_envelopes);
  receipt := private.call_receipt(created_call,p_client_mutation_id);
  insert into private.call_mutation_receipts values(actor_device,p_client_mutation_id,p_call_id,request_digest,to_jsonb(receipt));
  return next receipt;
end;
$$;

create function private.accept_private_call(p_call_id uuid,p_client_mutation_id uuid)
returns setof public.private_call_receipt language plpgsql security definer set search_path = '' as $$
declare actor_device uuid := private.current_device_id(); selected_call private.call_sessions;
  request_digest bytea := extensions.digest(convert_to(jsonb_build_array('ACCEPT',p_call_id)::text,'UTF8'),'sha256');
  saved_receipt jsonb; receipt public.private_call_receipt;
begin
  if actor_device is null then raise exception using errcode='42501',message='an active device session is required'; end if;
  perform private.purge_expired_private_calls();
  selected_call := private.require_private_call(p_call_id);
  saved_receipt := private.find_call_mutation_receipt(actor_device,p_client_mutation_id,request_digest);
  if saved_receipt is not null then return next jsonb_populate_record(null::public.private_call_receipt,saved_receipt); return; end if;
  if selected_call.state <> 'RINGING' or selected_call.ring_expires_at <= statement_timestamp()
    or auth.uid() <> selected_call.recipient_user_id then
    raise exception using errcode='42501',message='the call cannot be accepted by this device';
  end if;
  update private.call_sessions set state='ACTIVE',accepted_device_id=actor_device,
    accepted_auth_session_id=(auth.jwt()->>'session_id')::uuid,
    caller_lease_expires_at=statement_timestamp()+interval '45 seconds',
    recipient_lease_expires_at=statement_timestamp()+interval '45 seconds'
    where id=p_call_id returning * into selected_call;
  delete from private.call_device_leases where call_id=p_call_id and device_id not in (actor_device,selected_call.caller_device_id);
  receipt := private.call_receipt(selected_call,p_client_mutation_id);
  insert into private.call_mutation_receipts values(actor_device,p_client_mutation_id,p_call_id,request_digest,to_jsonb(receipt));
  return next receipt;
end;
$$;

create function private.send_private_call_signal(
  p_call_id uuid,p_client_mutation_id uuid,p_sequence integer,p_expires_at timestamptz,p_envelopes jsonb
)
returns table(call_id uuid,client_mutation_id uuid,sequence integer,created_at timestamptz,expires_at timestamptz)
language plpgsql security definer set search_path = '' as $$
declare actor_device uuid := private.current_device_id(); selected_call private.call_sessions;
  request_digest bytea := extensions.digest(convert_to(jsonb_build_array('SIGNAL',p_call_id,p_sequence,p_expires_at,p_envelopes)::text,'UTF8'),'sha256');
  saved_receipt jsonb;
begin
  if actor_device is null then raise exception using errcode='42501',message='an active device session is required'; end if;
  perform private.purge_expired_private_calls();
  selected_call := private.require_private_call(p_call_id);
  saved_receipt := private.find_call_mutation_receipt(actor_device,p_client_mutation_id,request_digest);
  if saved_receipt is null then
    saved_receipt := private.store_private_call_signal(selected_call,actor_device,p_client_mutation_id,p_sequence,p_expires_at,p_envelopes);
    insert into private.call_mutation_receipts values(actor_device,p_client_mutation_id,p_call_id,request_digest,saved_receipt);
  end if;
  return query select (saved_receipt->>'call_id')::uuid,(saved_receipt->>'client_mutation_id')::uuid,
    (saved_receipt->>'sequence')::integer,(saved_receipt->>'created_at')::timestamptz,(saved_receipt->>'expires_at')::timestamptz;
end;
$$;

create function private.heartbeat_private_call(p_call_id uuid)
returns setof public.private_call_receipt language plpgsql security definer set search_path = '' as $$
declare actor_device uuid := private.current_device_id(); selected_call private.call_sessions;
begin
  if actor_device is null then raise exception using errcode='42501',message='an active device session is required'; end if;
  perform private.purge_expired_private_calls();
  selected_call := private.require_private_call(p_call_id);
  if selected_call.state='ACTIVE' and actor_device in (selected_call.caller_device_id,selected_call.accepted_device_id)
    and least(selected_call.caller_lease_expires_at,selected_call.recipient_lease_expires_at,
      selected_call.created_at+interval '60 minutes') > statement_timestamp() then
    update private.call_sessions set
      caller_lease_expires_at=case when actor_device=caller_device_id then statement_timestamp()+interval '45 seconds' else caller_lease_expires_at end,
      recipient_lease_expires_at=case when actor_device=accepted_device_id then statement_timestamp()+interval '45 seconds' else recipient_lease_expires_at end
      where id=p_call_id returning * into selected_call;
  elsif selected_call.state <> 'RINGING' or actor_device<>selected_call.caller_device_id then
    raise exception using errcode='42501',message='call heartbeat is not authorized';
  end if;
  return next private.call_receipt(selected_call,null);
end;
$$;

create function private.end_private_call(p_call_id uuid,p_client_mutation_id uuid,p_reason text)
returns setof public.private_call_receipt language plpgsql security definer set search_path = '' as $$
declare actor_device uuid := private.current_device_id(); selected_call private.call_sessions;
  request_digest bytea := extensions.digest(convert_to(jsonb_build_array('END',p_call_id,p_reason)::text,'UTF8'),'sha256');
  saved_receipt jsonb; receipt public.private_call_receipt;
begin
  if actor_device is null then raise exception using errcode='42501',message='an active device session is required'; end if;
  perform private.purge_expired_private_calls();
  selected_call := private.require_private_call(p_call_id);
  saved_receipt := private.find_call_mutation_receipt(actor_device,p_client_mutation_id,request_digest);
  if saved_receipt is not null then return next jsonb_populate_record(null::public.private_call_receipt,saved_receipt); return; end if;
  if p_reason is null or p_reason not in ('CANCELLED','DECLINED','ENDED')
    or (p_reason='CANCELLED' and actor_device<>selected_call.caller_device_id)
    or (p_reason='DECLINED' and auth.uid()<>selected_call.recipient_user_id)
    or (selected_call.state='ACTIVE' and actor_device not in(selected_call.caller_device_id,selected_call.accepted_device_id)) then
    raise exception using errcode='42501',message='call termination is not authorized';
  end if;
  if selected_call.state<>'ENDED' then
    update private.call_sessions set state='ENDED',terminal_reason=p_reason,ended_at=statement_timestamp()
      where id=p_call_id returning * into selected_call;
  end if;
  delete from private.call_signals signal where signal.call_id=p_call_id;
  delete from private.call_device_leases lease where lease.call_id=p_call_id;
  receipt := private.call_receipt(selected_call,p_client_mutation_id);
  insert into private.call_mutation_receipts values(actor_device,p_client_mutation_id,p_call_id,request_digest,to_jsonb(receipt));
  return next receipt;
end;
$$;

create function private.poll_private_calls()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor_device uuid := private.current_device_id(); visible_calls uuid[]; calls jsonb; signals jsonb;
begin
  if auth.uid() is null or actor_device is null then
    raise exception using errcode='42501',message='an active device session is required';
  end if;
  perform private.purge_expired_private_calls();
  select coalesce(array_agg(call.id),'{}'::uuid[]),
    coalesce(jsonb_agg(to_jsonb(private.call_receipt(call,null))-'client_mutation_id' order by call.created_at),'[]'::jsonb)
    into visible_calls,calls from (
    select selected.* from private.call_sessions selected
    join private.call_devices participant on participant.call_id=selected.id and participant.device_id=actor_device
    where auth.uid() in(selected.caller_user_id,selected.recipient_user_id)
      and private.is_active_room_member(selected.room_id)
      and exists(select 1 from public.rooms room where room.id=selected.room_id and room.membership_epoch=selected.membership_epoch)
      and (selected.state<>'ACTIVE' or actor_device in(selected.caller_device_id,selected.accepted_device_id))
      and (selected.ended_at is null or selected.ended_at>statement_timestamp()-interval '120 seconds')
    order by (selected.state='ENDED'),selected.created_at desc,selected.id
    limit 20
    ) call;
  select coalesce(jsonb_agg(jsonb_build_object(
    'call_id',signal.call_id,'room_id',call.room_id,'membership_epoch',call.membership_epoch,
    'sender_user_id',sender.user_id,'sender_device_id',sender.id,'sender_signal_device_id',sender.signal_device_id,
    'client_mutation_id',signal.client_mutation_id,'sequence',signal.sequence,
    'recipient_device_id',envelope.recipient_device_id,'protocol_adapter_version',envelope.protocol_adapter_version,
    'signal_message_type',envelope.signal_message_type,'ciphertext_hex',encode(envelope.ciphertext,'hex'),
    'created_at',signal.created_at,'expires_at',signal.expires_at
  ) order by signal.created_at,signal.sequence),'[]'::jsonb) into signals
    from private.call_signal_envelopes envelope
    join private.call_signals signal using(call_id,sender_device_id,sequence)
    join private.call_sessions call on call.id=signal.call_id
    join public.devices sender on sender.id=signal.sender_device_id
    where envelope.recipient_device_id=actor_device and call.id=any(visible_calls)
      and call.state<>'ENDED' and signal.expires_at>statement_timestamp()
      and (case when call.state='RINGING' then call.ring_expires_at else
        least(call.caller_lease_expires_at,call.recipient_lease_expires_at,call.created_at+interval '60 minutes') end)>statement_timestamp()
      and (call.state='RINGING' or actor_device in(call.caller_device_id,call.accepted_device_id))
      and sender.revoked_at is null and private.call_context_is_current(call);
  return jsonb_build_object('calls',calls,'signals',signals);
end;
$$;

create function public.create_private_call(p_call_id uuid,p_room_id uuid,p_membership_epoch integer,
  p_media_kind text,p_client_mutation_id uuid,p_expires_at timestamptz,p_envelopes jsonb)
returns setof public.private_call_receipt language sql security invoker set search_path='' as $$
  select * from private.create_private_call(p_call_id,p_room_id,p_membership_epoch,p_media_kind,p_client_mutation_id,p_expires_at,p_envelopes);
$$;
create function public.accept_private_call(p_call_id uuid,p_client_mutation_id uuid)
returns setof public.private_call_receipt language sql security invoker set search_path='' as $$
  select * from private.accept_private_call(p_call_id,p_client_mutation_id);
$$;
create function public.send_private_call_signal(p_call_id uuid,p_client_mutation_id uuid,p_sequence integer,
  p_expires_at timestamptz,p_envelopes jsonb)
returns table(call_id uuid,client_mutation_id uuid,sequence integer,created_at timestamptz,expires_at timestamptz)
language sql security invoker set search_path='' as $$
  select * from private.send_private_call_signal(p_call_id,p_client_mutation_id,p_sequence,p_expires_at,p_envelopes);
$$;
create function public.heartbeat_private_call(p_call_id uuid)
returns setof public.private_call_receipt language sql security invoker set search_path='' as $$
  select * from private.heartbeat_private_call(p_call_id);
$$;
create function public.end_private_call(p_call_id uuid,p_client_mutation_id uuid,p_reason text)
returns setof public.private_call_receipt language sql security invoker set search_path='' as $$
  select * from private.end_private_call(p_call_id,p_client_mutation_id,p_reason);
$$;
create function public.poll_private_calls() returns jsonb language sql security invoker set search_path='' as $$
  select private.poll_private_calls();
$$;

-- Only the six authenticated boundaries are callable. Private helpers cannot become alternate APIs.
revoke all on function private.call_receipt(private.call_sessions,uuid),private.call_context_is_current(private.call_sessions),
  private.purge_expired_private_calls(),private.require_private_call(uuid),private.find_call_mutation_receipt(uuid,uuid,bytea),
  private.store_private_call_signal(private.call_sessions,uuid,uuid,integer,timestamptz,jsonb)
  from public,anon,authenticated,service_role;
revoke all on function private.create_private_call(uuid,uuid,integer,text,uuid,timestamptz,jsonb),
  private.accept_private_call(uuid,uuid),private.send_private_call_signal(uuid,uuid,integer,timestamptz,jsonb),
  private.heartbeat_private_call(uuid),private.end_private_call(uuid,uuid,text),private.poll_private_calls(),
  public.create_private_call(uuid,uuid,integer,text,uuid,timestamptz,jsonb),public.accept_private_call(uuid,uuid),
  public.send_private_call_signal(uuid,uuid,integer,timestamptz,jsonb),public.heartbeat_private_call(uuid),
  public.end_private_call(uuid,uuid,text),public.poll_private_calls() from public,anon,authenticated,service_role;
grant execute on function private.create_private_call(uuid,uuid,integer,text,uuid,timestamptz,jsonb),
  private.accept_private_call(uuid,uuid),private.send_private_call_signal(uuid,uuid,integer,timestamptz,jsonb),
  private.heartbeat_private_call(uuid),private.end_private_call(uuid,uuid,text),private.poll_private_calls(),
  public.create_private_call(uuid,uuid,integer,text,uuid,timestamptz,jsonb),public.accept_private_call(uuid,uuid),
  public.send_private_call_signal(uuid,uuid,integer,timestamptz,jsonb),public.heartbeat_private_call(uuid),
  public.end_private_call(uuid,uuid,text),public.poll_private_calls() to authenticated;

select cron.schedule('synapse-private-call-purge','* * * * *','select private.purge_expired_private_calls()');
