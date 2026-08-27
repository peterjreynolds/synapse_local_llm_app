create function private.auth_identity_has_bound_internal_address(
  p_user_id uuid,
  p_email text,
  p_phone text,
  p_is_anonymous boolean
)
returns boolean
language sql
immutable
security invoker
set search_path = ''
as $$
  select p_user_id is not null
    and lower(coalesce(p_email, '')) =
      p_user_id::text || '@identity.synapse-private.invalid'
    and coalesce(p_phone, '') = ''
    and coalesce(p_is_anonymous, false) is false;
$$;

revoke all on function private.auth_identity_has_bound_internal_address(
  uuid,
  text,
  text,
  boolean
) from public, anon, authenticated;

do $$
begin
  if exists (
    select 1
    from auth.users as auth_user
    where not private.auth_identity_has_bound_internal_address(
      auth_user.id,
      auth_user.email,
      auth_user.phone,
      auth_user.is_anonymous
    )
  ) then
    raise exception using
      errcode = '55000',
      message = 'Auth identity migration requires every existing account to use its UUID-bound internal address.';
  end if;
end;
$$;

create or replace function private.enforce_synapse_private_auth_user_identity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- Admin Auth creation writes the role, confirmation state, and caller app
  -- metadata in separate updates. The before-user-created hook validates the
  -- authority marker before this transaction; this trigger binds every phase
  -- to the caller-selected UUID so a public signup cannot imitate it.
  if not private.auth_identity_has_bound_internal_address(
    new.id,
    new.email,
    new.phone,
    new.is_anonymous
  ) then
    raise exception using errcode = '42501', message = 'Registration is not authorized.';
  end if;
  return new;
end;
$$;

revoke all on function private.enforce_synapse_private_auth_user_identity()
from public, anon, authenticated;
