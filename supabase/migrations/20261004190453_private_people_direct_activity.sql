-- Directory visibility is intentional for bound app accounts. It does not grant
-- global profile/device SELECT or change opt-in conversation presence.
create table private.directory_activity (
  device_id uuid primary key references public.devices(id) on delete cascade,
  session_id uuid not null references auth.sessions(id) on delete cascade,
  expires_at timestamptz not null
);
alter table private.directory_activity enable row level security;
revoke all on private.directory_activity from public, anon, authenticated;
create index directory_activity_expiry_idx on private.directory_activity(expires_at);
create index directory_activity_session_idx on private.directory_activity(session_id);

create table private.direct_account_pairs (
  first_user_id uuid not null references public.profiles(user_id) on delete cascade,
  second_user_id uuid not null references public.profiles(user_id) on delete cascade,
  room_id uuid not null unique references public.rooms(id) on delete cascade,
  primary key(first_user_id, second_user_id),
  check(first_user_id < second_user_id)
);
alter table private.direct_account_pairs enable row level security;
revoke all on private.direct_account_pairs from public, anon, authenticated;
create index direct_account_pairs_second_user_idx on private.direct_account_pairs(second_user_id);

create function private.directory_account_available(p_user_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.profiles p join auth.users u on u.id = p.user_id
    where p.user_id = p_user_id and u.is_anonymous is false
      and u.deleted_at is null and (u.banned_until is null or u.banned_until <= statement_timestamp())
  );
$$;

create function private.publish_directory_activity()
returns table(device_id uuid, expires_at timestamptz)
language plpgsql security definer set search_path = '' as $$
declare
  actor_device uuid := private.current_device_id();
  expiry timestamptz := statement_timestamp() + interval '60 seconds';
begin
  if actor_device is null or not private.directory_account_available(auth.uid()) then
    raise exception using errcode = '42501', message = 'Account access is not authorized.';
  end if;
  insert into private.directory_activity as activity(device_id, session_id, expires_at)
  values(actor_device, (auth.jwt()->>'session_id')::uuid, expiry)
  on conflict on constraint directory_activity_pkey do update
    set session_id = excluded.session_id, expires_at = excluded.expires_at;
  return query select actor_device, expiry;
end;
$$;

create function private.list_directory_people(p_after_user_id uuid default null)
returns table(user_id uuid, display_name text, active_for_seconds integer)
language plpgsql stable security definer set search_path = '' as $$
begin
  if private.current_device_id() is null or not private.directory_account_available(auth.uid()) then
    raise exception using errcode = '42501', message = 'Account access is not authorized.';
  end if;
  return query
  select p.user_id, p.display_name, coalesce((
    select greatest(0, least(60, coalesce(ceil(extract(epoch from max(a.expires_at) - statement_timestamp()))::integer, 0)))
    from private.directory_activity a
    join public.devices d on d.id = a.device_id and d.user_id = p.user_id and d.revoked_at is null
    join auth.sessions s on s.id = a.session_id and s.user_id = p.user_id
    join private.device_sessions ds on ds.session_id = s.id and ds.device_id = d.id
    where a.expires_at > statement_timestamp()
  ), 0)
  from public.profiles p
  where p.user_id <> auth.uid() and private.directory_account_available(p.user_id)
    and (p_after_user_id is null or p.user_id > p_after_user_id)
  order by p.user_id limit 100;
end;
$$;

-- Only membership/routing is created here. There is no plaintext room title or
-- content: clients derive the label from the peer's already-public display name.
-- Message sends still require the existing complete Signal envelope set.
create function private.open_direct_conversation(p_target_user_id uuid)
returns table(room_id uuid, actor_user_id uuid, target_user_id uuid)
language plpgsql security definer set search_path = '' as $$
declare
  actor uuid := auth.uid();
  first_id uuid := least(actor, p_target_user_id);
  second_id uuid := greatest(actor, p_target_user_id);
  direct_room uuid;
begin
  if private.current_device_id() is null or p_target_user_id is null or actor = p_target_user_id
    or not private.directory_account_available(actor)
    or not private.directory_account_available(p_target_user_id)
    or not exists(select 1 from public.devices d where d.user_id = p_target_user_id and d.revoked_at is null)
  then
    raise exception using errcode = '42501', message = 'Conversation is unavailable.';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(first_id::text || '/direct/' || second_id::text, 0));
  select pair.room_id into direct_room from private.direct_account_pairs pair
    where pair.first_user_id = first_id and pair.second_user_id = second_id;
  if found then
    if (select count(*) from public.room_members m where m.room_id = direct_room) <> 2
      or not exists(select 1 from public.room_members m where m.room_id = direct_room and m.user_id = actor)
      or not exists(select 1 from public.room_members m where m.room_id = direct_room and m.user_id = p_target_user_id)
    then
      raise exception using errcode = '42501', message = 'Conversation is unavailable.';
    end if;
  else
    insert into public.rooms(owner_user_id, room_kind, creation_client_mutation_id, metadata_revision, metadata_updated_at)
      values(actor, 'DIRECT', null, 1, statement_timestamp()) returning id into direct_room;
    insert into public.room_members(room_id, user_id, member_role)
      values(direct_room, actor, 'OWNER'), (direct_room, p_target_user_id, 'MEMBER');
    insert into private.direct_account_pairs values(first_id, second_id, direct_room);
  end if;
  return query select direct_room, actor, p_target_user_id;
end;
$$;

create function public.publish_directory_activity()
returns table(device_id uuid, expires_at timestamptz)
language sql security invoker set search_path = '' as $$ select * from private.publish_directory_activity(); $$;
create function public.list_directory_people(p_after_user_id uuid default null)
returns table(user_id uuid, display_name text, active_for_seconds integer)
language sql stable security invoker set search_path = '' as $$ select * from private.list_directory_people(p_after_user_id); $$;
create function public.open_direct_conversation(p_target_user_id uuid)
returns table(room_id uuid, actor_user_id uuid, target_user_id uuid)
language sql security invoker set search_path = '' as $$ select * from private.open_direct_conversation(p_target_user_id); $$;

revoke all on function private.directory_account_available(uuid) from public, anon, authenticated;
revoke all on function private.publish_directory_activity(), public.publish_directory_activity(),
  private.list_directory_people(uuid), public.list_directory_people(uuid),
  private.open_direct_conversation(uuid), public.open_direct_conversation(uuid) from public, anon, authenticated;
grant execute on function private.publish_directory_activity(), public.publish_directory_activity(),
  private.list_directory_people(uuid), public.list_directory_people(uuid),
  private.open_direct_conversation(uuid), public.open_direct_conversation(uuid) to authenticated;

-- UPSERT/RETURNING needs SELECT on the caller's own row, including its expired
-- predecessor. Peer visibility retains both expiry and the sharing opt-in.
create policy presence_state_select_current_device_for_upsert
on public.presence_state for select to authenticated
using (device_id = (select private.current_device_id()));

select cron.schedule(
  'synapse-private-directory-activity-purge', '* * * * *',
  $job$delete from private.directory_activity where expires_at <= statement_timestamp();$job$
);
