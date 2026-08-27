create or replace function private.configure_bootstrap_capability(
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

create or replace function private.redeem_account_registration(
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

revoke all on function private.configure_bootstrap_capability(bytea, integer)
from public, anon, authenticated;
revoke all on function private.redeem_account_registration(bytea, uuid, bytea, text, uuid, text)
from public, anon, authenticated;
