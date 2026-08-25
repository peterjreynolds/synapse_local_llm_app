create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;

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
    device_reservations_deleted
  ) values (
    p_correlation_id,
    purge_started_at,
    purge_completed_at,
    purged_message_count,
    0,
    purged_typing_count,
    purged_presence_count,
    purged_invite_count,
    purged_device_reservation_count
  );

  return query
  select
    p_correlation_id,
    purged_message_count,
    purged_typing_count,
    purged_presence_count,
    purged_invite_count,
    purged_device_reservation_count,
    purge_completed_at;
end;
$$;

create function private.lease_expired_attachment_batch(p_batch_limit integer default 100)
returns table (
  lease_id uuid,
  message_id uuid,
  object_paths text[],
  lease_expires_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  created_lease_id uuid := gen_random_uuid();
  lease_time timestamptz := statement_timestamp();
  created_lease_expiry timestamptz := lease_time + interval '5 minutes';
begin
  if p_batch_limit not between 1 and 100 then
    raise exception using errcode = '22023', message = 'attachment purge batch limit must be between one and 100';
  end if;

  -- The advisory lock serializes lease ownership. The persisted unexpired
  -- lease keeps later Edge invocations from starting a second Storage batch
  -- after this RPC transaction releases the advisory lock.
  perform pg_advisory_xact_lock(
    hashtextextended('synapse-private/attachment-purge-lease/v1', 0)
  );

  delete from private.attachment_purge_leases as expired_lease
  where expired_lease.lease_expires_at <= lease_time;

  if exists (select 1 from private.attachment_purge_leases) then
    return;
  end if;

  return query
  with expired_messages as materialized (
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
      and exists (
        select 1
        from public.attachments as attachment
        where attachment.message_id = message.id
      )
      and not exists (
        select 1
        from private.attachment_purge_leases as existing_lease
        where existing_lease.message_id = message.id
      )
    order by message.expires_at, message.id
    for update skip locked
    limit p_batch_limit
  ), created_leases as (
    insert into private.attachment_purge_leases (
      message_id,
      lease_id,
      leased_at,
      lease_expires_at
    )
    select expired_message.id, created_lease_id, lease_time, created_lease_expiry
    from expired_messages as expired_message
    returning attachment_purge_leases.message_id
  )
  select
    created_lease_id,
    created_lease.message_id,
    array_agg(attachment_object.object_path order by attachment_object.object_path),
    created_lease_expiry
  from created_leases as created_lease
  cross join lateral (
    select attachment.object_path
    from public.attachments as attachment
    where attachment.message_id = created_lease.message_id
    union all
    select attachment.thumbnail_object_path
    from public.attachments as attachment
    where attachment.message_id = created_lease.message_id
      and attachment.thumbnail_object_path is not null
  ) as attachment_object(object_path)
  group by created_lease.message_id;
end;
$$;

create function private.finalize_expired_attachment_message_purge(p_lease_id uuid)
returns table (
  correlation_id uuid,
  messages_deleted integer,
  attachment_objects_deleted integer,
  completed_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  purge_completed_at timestamptz := clock_timestamp();
  purged_message_count integer;
  expected_object_count integer;
  leased_message_ids uuid[];
  purge_started_at timestamptz;
begin
  perform 1
  from private.attachment_purge_leases as lease
  where lease.lease_id = p_lease_id
    and lease.lease_expires_at > statement_timestamp()
  for update;

  if not found then
    raise exception using errcode = '40001', message = 'attachment purge lease is unavailable';
  end if;

  select
    array_agg(lease.message_id order by lease.message_id),
    min(lease.leased_at)
    into leased_message_ids, purge_started_at
  from private.attachment_purge_leases as lease
  where lease.lease_id = p_lease_id;

  if cardinality(leased_message_ids) not between 1 and 100 then
    raise exception using errcode = '22023', message = 'attachment purge receipt is invalid';
  end if;

  select count(*)
    into expected_object_count
  from public.attachments as attachment
  cross join lateral unnest(
    array_remove(array[attachment.object_path, attachment.thumbnail_object_path], null)
  ) as expected_object_path
  where attachment.message_id = any(leased_message_ids);

  if expected_object_count not between 1 and 1600 then
    raise exception using errcode = '22023', message = 'attachment purge receipt is invalid';
  end if;

  with deleted_messages as (
    delete from public.messages as message
    where message.id = any(leased_message_ids)
      and (
        message.expires_at <= statement_timestamp()
        or exists (
          select 1
          from private.message_deletion_requests as deletion_request
          where deletion_request.message_id = message.id
        )
      )
    returning message.id
  )
  select count(*) into purged_message_count from deleted_messages;

  if purged_message_count <> cardinality(leased_message_ids) then
    raise exception using errcode = '40001', message = 'attachment purge batch changed before finalization';
  end if;

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
    p_lease_id,
    purge_started_at,
    purge_completed_at,
    purged_message_count,
    expected_object_count,
    0,
    0,
    0,
    0
  );

  return query
  select p_lease_id, purged_message_count, expected_object_count, purge_completed_at;
end;
$$;

create function public._edge_lease_expired_attachment_batch(p_batch_limit integer default 100)
returns table (
  lease_id uuid,
  message_id uuid,
  object_paths text[],
  lease_expires_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.lease_expired_attachment_batch(p_batch_limit);
$$;

create function public._edge_finalize_expired_attachment_message_purge(p_lease_id uuid)
returns table (
  correlation_id uuid,
  messages_deleted integer,
  attachment_objects_deleted integer,
  completed_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.finalize_expired_attachment_message_purge(p_lease_id);
$$;

create function private.retention_configuration_health(p_runtime_purge_secret_sha256 bytea)
returns table (
  configuration_valid boolean,
  relational_job_active boolean,
  storage_job_active boolean,
  project_url_configured boolean,
  purge_secret_matches boolean
)
language sql
stable
security definer
set search_path = ''
as $$
  with configuration as (
    select
      exists (
        select 1
        from cron.job
        where jobname = 'synapse-private-relational-purge' and active
      ) as relational_job_active,
      exists (
        select 1
        from cron.job
        where jobname = 'synapse-private-storage-purge' and active
      ) as storage_job_active,
      exists (
        select 1
        from private.runtime_configuration as runtime_configuration
        where runtime_configuration.singleton
          and runtime_configuration.project_url is not null
          and runtime_configuration.configured_at is not null
      ) as project_url_configured,
      exists (
        select 1
        from private.runtime_configuration as runtime_configuration
        where runtime_configuration.singleton
          and extensions.digest(
            convert_to(
              rtrim(
                translate(encode(runtime_configuration.purge_capability, 'base64'), '+/', '-_'),
                '='
              ),
              'UTF8'
            ),
            'sha256'
          ) = p_runtime_purge_secret_sha256
      ) as purge_secret_matches
  )
  select
    relational_job_active and storage_job_active and project_url_configured and purge_secret_matches,
    relational_job_active,
    storage_job_active,
    project_url_configured,
    purge_secret_matches
  from configuration;
$$;

create function private.record_storage_purge_heartbeat(p_runtime_purge_secret_sha256 bytea)
returns table (
  correlation_id uuid,
  completed_at timestamptz,
  configuration_valid boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  heartbeat_id uuid := gen_random_uuid();
  heartbeat_time timestamptz := clock_timestamp();
  retention_configuration_valid boolean;
begin
  select health.configuration_valid
    into strict retention_configuration_valid
  from private.retention_configuration_health(p_runtime_purge_secret_sha256) as health;

  if not retention_configuration_valid then
    raise exception using errcode = '55000', message = 'retention configuration verification failed';
  end if;

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
    heartbeat_id,
    heartbeat_time,
    heartbeat_time,
    0,
    0,
    0,
    0,
    0,
    0
  );

  return query select heartbeat_id, heartbeat_time, retention_configuration_valid;
end;
$$;

create function public._edge_retention_configuration_health(p_runtime_purge_secret_sha256 bytea)
returns table (
  configuration_valid boolean,
  relational_job_active boolean,
  storage_job_active boolean,
  project_url_configured boolean,
  purge_secret_matches boolean
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.retention_configuration_health(p_runtime_purge_secret_sha256);
$$;

create function public._edge_record_storage_purge_heartbeat(p_runtime_purge_secret_sha256 bytea)
returns table (
  correlation_id uuid,
  completed_at timestamptz,
  configuration_valid boolean
)
language sql
security invoker
set search_path = ''
as $$
  select * from private.record_storage_purge_heartbeat(p_runtime_purge_secret_sha256);
$$;

revoke all on function private.purge_expired_relational_data(integer, uuid) from public, anon, authenticated;
revoke all on function private.lease_expired_attachment_batch(integer) from public, anon, authenticated;
revoke all on function private.finalize_expired_attachment_message_purge(uuid) from public, anon, authenticated;
revoke all on function public._edge_lease_expired_attachment_batch(integer) from public, anon, authenticated;
revoke all on function public._edge_finalize_expired_attachment_message_purge(uuid) from public, anon, authenticated;
revoke all on function private.retention_configuration_health(bytea) from public, anon, authenticated;
revoke all on function private.record_storage_purge_heartbeat(bytea) from public, anon, authenticated;
revoke all on function public._edge_retention_configuration_health(bytea) from public, anon, authenticated;
revoke all on function public._edge_record_storage_purge_heartbeat(bytea) from public, anon, authenticated;

grant execute on function private.lease_expired_attachment_batch(integer) to service_role;
grant execute on function private.finalize_expired_attachment_message_purge(uuid) to service_role;
grant execute on function public._edge_lease_expired_attachment_batch(integer) to service_role;
grant execute on function public._edge_finalize_expired_attachment_message_purge(uuid) to service_role;
grant execute on function private.retention_configuration_health(bytea) to service_role;
grant execute on function private.record_storage_purge_heartbeat(bytea) to service_role;
grant execute on function public._edge_retention_configuration_health(bytea) to service_role;
grant execute on function public._edge_record_storage_purge_heartbeat(bytea) to service_role;

do $$
declare
  existing_job record;
begin
  for existing_job in
    select jobid
    from cron.job
    where jobname in (
      'synapse-private-relational-purge',
      'synapse-private-storage-purge',
      'synapse-private-cron-history-prune'
    )
  loop
    perform cron.unschedule(existing_job.jobid);
  end loop;
end;
$$;

select cron.schedule(
  'synapse-private-relational-purge',
  '* * * * *',
  $job$select private.purge_expired_relational_data(500);$job$
);

select cron.schedule(
  'synapse-private-storage-purge',
  '* * * * *',
  $job$
    select net.http_post(
      url := runtime_configuration.project_url || '/functions/v1/purge-expired-data',
      headers := jsonb_build_object(
        'content-type', 'application/json',
        'x-synapse-purge-secret',
        rtrim(
          translate(encode(runtime_configuration.purge_capability, 'base64'), '+/', '-_'),
          '='
        )
      ),
      body := '{}'::jsonb
    )
    from private.runtime_configuration as runtime_configuration
    where runtime_configuration.singleton
      and runtime_configuration.project_url is not null;
  $job$
);

select cron.schedule(
  'synapse-private-cron-history-prune',
  '17 3 * * *',
  $job$
    delete from cron.job_run_details
    where end_time < statement_timestamp() - interval '7 days';
    delete from private.purge_receipts
    where completed_at < statement_timestamp() - interval '30 days';
  $job$
);
