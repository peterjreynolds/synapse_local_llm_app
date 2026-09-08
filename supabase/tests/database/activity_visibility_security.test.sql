begin;
create extension if not exists pgtap with schema extensions;
select plan(5);

select ok(
  (
    select count(*) = 2
      and bool_and(prosecdef)
      and bool_and(proconfig @> array['search_path=""']::text[])
    from pg_proc
    join pg_namespace on pg_namespace.oid = pronamespace
    where nspname = 'private'
      and proname in ('can_view_typing_state', 'can_view_presence_state')
  ),
  'activity visibility predicates are empty-path security definers'
);
select ok(
  has_function_privilege(
    'authenticated',
    'private.can_view_typing_state(uuid,uuid)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'private.can_view_presence_state(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'private.can_view_typing_state(uuid,uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'private.can_view_presence_state(uuid)',
    'EXECUTE'
  ),
  'only authenticated callers can evaluate activity visibility'
);
select ok(
  not has_column_privilege(
    'authenticated',
    'public.devices',
    'revoked_at',
    'SELECT'
  ),
  'device revocation state remains hidden from Data API clients'
);
select ok(
  (
    select lower(qual) like '%can_view_typing_state(room_id, device_id)%'
      and lower(qual) like '%expires_at > statement_timestamp()%'
    from pg_policies
    where schemaname = 'public'
      and tablename = 'typing_state'
      and policyname = 'typing_state_select_member_before_expiry'
  )
  and (
    select lower(qual) like '%can_view_presence_state(device_id)%'
      and lower(qual) like '%expires_at > statement_timestamp()%'
    from pg_policies
    where schemaname = 'public'
      and tablename = 'presence_state'
      and policyname = 'presence_state_select_room_peer_before_expiry'
  ),
  'activity RLS delegates protected device checks to the private predicates'
);
select ok(
  lower(pg_get_functiondef('private.can_view_typing_state(uuid,uuid)'::regprocedure))
    like '%typing_device.revoked_at is null%'
  and lower(pg_get_functiondef('private.can_view_typing_state(uuid,uuid)'::regprocedure))
    like '%typing_profile.typing_indicators_enabled%'
  and lower(pg_get_functiondef('private.can_view_presence_state(uuid)'::regprocedure))
    like '%present_device.revoked_at is null%'
  and lower(pg_get_functiondef('private.can_view_presence_state(uuid)'::regprocedure))
    like '%present_profile.presence_sharing_enabled%'
  and lower(pg_get_functiondef('private.can_view_presence_state(uuid)'::regprocedure))
    like '%viewer_membership.room_id = present_membership.room_id%',
  'activity visibility preserves revocation, opt-in, and shared-room checks'
);

select * from finish();
rollback;
