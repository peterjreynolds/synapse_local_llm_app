begin;
create extension if not exists pgtap with schema extensions;
select plan(52);

insert into auth.users (
  id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at
) values (
  '90000000-0000-4000-8000-000000000001',
  'authenticated',
  'authenticated',
  '90000000-0000-4000-8000-000000000001@identity.synapse-private.invalid',
  '',
  now(),
  '{"provider":"email","providers":["email"],"synapse_private_registration_authority":true}',
  '{}',
  now(),
  now()
);
insert into public.profiles (user_id, display_name)
values ('90000000-0000-4000-8000-000000000001', 'Security Test');
insert into private.account_credentials (user_id, username_digest, internal_email)
values (
  '90000000-0000-4000-8000-000000000001',
  decode(repeat('91', 32), 'hex'),
  '90000000-0000-4000-8000-000000000001@identity.synapse-private.invalid'
);

select ok(
  (select bool_and(relrowsecurity) from pg_class join pg_namespace on pg_namespace.oid = relnamespace
   where nspname = 'public' and relname in (
     'profiles', 'devices', 'device_one_time_prekeys', 'rooms', 'room_members', 'messages',
     'message_envelopes', 'message_reply_links', 'reactions', 'reaction_envelopes',
     'message_receipts', 'typing_state', 'presence_state', 'attachments'
   )),
  'every exposed Synapse Private table enables RLS'
);
select is(
  (select count(*)::integer from information_schema.role_table_grants
   where table_schema = 'private' and grantee in ('anon', 'authenticated')),
  0,
  'Data API roles have no private-table grants'
);
select is(
  (select count(*)::integer from information_schema.role_table_grants
   where table_schema = 'public' and grantee = 'anon'),
  0,
  'anonymous callers have no Synapse Private table grants'
);
select ok(
  not has_table_privilege('authenticated', 'public.messages', 'INSERT'),
  'messages can only be inserted through the checked RPC'
);
select ok(
  not has_table_privilege('authenticated', 'public.message_envelopes', 'INSERT'),
  'message envelopes can only be inserted atomically through the checked RPC'
);
select ok(
  has_column_privilege('authenticated', 'public.profiles', 'display_name', 'UPDATE'),
  'profiles expose only the intended editable field'
);
select ok(
  not has_column_privilege('authenticated', 'public.profiles', 'user_id', 'UPDATE'),
  'profile ownership cannot be reassigned'
);
select is(
  (select confdeltype::text from pg_constraint
   where conname = 'rooms_owner_user_id_fkey'),
  'r',
  'owner deletion is restricted instead of cascading group rooms'
);
select is(
  (select confdeltype::text from pg_constraint
   where conname = 'room_members_user_id_fkey'),
  'r',
  'member deletion cannot silently rewrite membership history'
);
select ok(
  not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename in (
        'profiles', 'devices', 'rooms', 'room_members', 'messages', 'message_envelopes',
        'message_reply_links', 'reactions', 'reaction_envelopes', 'message_receipts',
        'typing_state', 'presence_state', 'attachments'
      )
  ),
  'sensitive tables are absent from Postgres Changes'
);
select ok(
  not has_function_privilege('anon', 'public.create_room(text,integer)', 'EXECUTE'),
  'anonymous callers cannot create rooms'
);
select ok(
  not has_function_privilege('authenticated', 'public._edge_resolve_account_login(bytea)', 'EXECUTE'),
  'username lookup remains service-role only'
);
select ok(
  not has_function_privilege('authenticated', 'public._edge_redeem_account_registration(bytea,uuid,bytea,text,uuid,text)', 'EXECUTE'),
  'account redemption remains service-role only'
);
select ok(
  not has_function_privilege('authenticated', 'public._edge_reserve_device_registration(uuid,uuid,uuid)', 'EXECUTE'),
  'clients cannot allocate their own Signal device identifiers'
);
select ok(
  has_function_privilege('service_role', 'public._edge_reserve_device_registration(uuid,uuid,uuid)', 'EXECUTE'),
  'the Edge service can reserve a Signal device identifier after auth'
);
select ok(
  has_function_privilege('supabase_auth_admin', 'private.authorize_synapse_private_user_creation(jsonb)', 'EXECUTE'),
  'Auth can call the direct-signup denial hook'
);
select ok(
  not has_function_privilege('authenticated', 'private.authorize_synapse_private_user_creation(jsonb)', 'EXECUTE'),
  'clients cannot call the Auth authority hook'
);
select ok(
  has_function_privilege('supabase_auth_admin', 'private.authorize_synapse_private_access_token(jsonb)', 'EXECUTE'),
  'Auth can call the credential-backed access-token hook'
);
select ok(
  not has_function_privilege('authenticated', 'private.authorize_synapse_private_access_token(jsonb)', 'EXECUTE'),
  'clients cannot call the access-token authority hook'
);
select is(
  (
    select tgenabled::text
    from pg_trigger
    where tgname = 'enforce_synapse_private_auth_user_identity'
      and tgrelid = 'auth.users'::regclass
      and not tgisinternal
  ),
  'O',
  'the database-side Auth creation guard is enabled for normal inserts'
);
select ok(
  not has_function_privilege('authenticated', 'private.enforce_synapse_private_auth_user_identity()', 'EXECUTE'),
  'clients cannot invoke the Auth trigger function directly'
);
select ok(
  (
    select prosecdef and proconfig @> array['search_path=""']::text[]
    from pg_proc
    where oid = 'private.enforce_synapse_private_auth_user_identity()'::regprocedure
  ),
  'the Auth insert guard is security-definer with an empty search path'
);
select like(
  pg_get_functiondef('private.authorize_synapse_private_user_creation(jsonb)'::regprocedure),
  '%app_metadata%synapse_private_registration_authority%',
  'registration authority comes from server-controlled app metadata'
);
select like(
  pg_get_functiondef('private.assert_complete_signal_envelopes(uuid,uuid,jsonb,integer)'::regprocedure),
  '%jsonb_array_length(p_envelopes) not between 1 and 128%',
  'fan-out is bounded to exactly 128 recipients'
);
select like(
  pg_get_functiondef('private.assert_reply_link_invariant()'::regprocedure),
  '%source_room_id <> target_room_id%',
  'reply links fail closed across rooms'
);
select like(
  pg_get_functiondef('private.register_device_session(uuid,uuid,uuid,smallint,integer,smallint,bytea,integer,bytea,bytea,integer,bytea,bytea,integer,bytea)'::regprocedure),
  '%active device registration reservation is required%',
  'device registration consumes a server allocation before binding RLS'
);
select like(
  pg_get_functiondef('private.register_device_session(uuid,uuid,uuid,smallint,integer,smallint,bytea,integer,bytea,bytea,integer,bytea,bytea,integer,bytea)'::regprocedure),
  '%for update of room%',
  'device registration locks affected rooms deterministically'
);
select like(
  pg_get_functiondef('private.redeem_room_membership_invite(uuid,uuid,bytea,uuid)'::regprocedure),
  '%for update%',
  'room invite redemption serializes membership changes'
);
select like(
  pg_get_functiondef('private.claim_device_prekey(uuid)'::regprocedure),
  '%target_device.user_id = actor_user_id%',
  'same-account secondary devices may claim prekeys'
);
select like(
  pg_get_functiondef('private.current_device_id()'::regprocedure),
  '%device.revoked_at is null%',
  'revoked devices cannot authenticate through RLS helpers'
);
select ok(
  not has_function_privilege('service_role', 'private.configure_bootstrap_capability(bytea,integer)', 'EXECUTE'),
  'bootstrap provisioning remains database-owner only'
);
select ok(
  not exists (
    select 1 from pg_proc join pg_namespace on pg_namespace.oid = pronamespace
    where nspname = 'private' and proname = 'configure_retention_vault'
  ),
  'raw retention secrets cannot be passed through an RPC'
);
select ok(
  not has_function_privilege('authenticated', 'public._edge_finalize_expired_attachment_message_purge(uuid)', 'EXECUTE'),
  'attachment finalization remains service-role only'
);
select ok(
  not has_function_privilege('authenticated', 'public._edge_initialize_runtime_configuration(text)', 'EXECUTE'),
  'clients cannot retrieve runtime peppers or purge capability'
);
select ok(
  has_function_privilege('service_role', 'public._edge_initialize_runtime_configuration(text)', 'EXECUTE'),
  'only the Edge service can initialize and retrieve runtime configuration'
);
select ok(
  (
    select octet_length(username_hmac_pepper) = 32
      and octet_length(rate_limit_hmac_pepper) = 32
      and octet_length(purge_capability) = 32
    from private.runtime_configuration
    where singleton
  ),
  'runtime secrets are migration-generated 256-bit values'
);
select ok(
  not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'devices' and column_name = 'last_seen_at'
  ),
  'device records do not retain durable last-seen tracking'
);
select like(
  pg_get_functiondef('private.set_presence_expiry()'::regprocedure),
  '%interval ''60 seconds''%',
  'presence is server-expired after 60 seconds'
);
select like(
  pg_get_functiondef('private.set_typing_expiry()'::regprocedure),
  '%profile.typing_indicators_enabled%',
  'typing indicators require explicit profile opt-in'
);
select ok(
  private.authorize_synapse_private_user_creation(
    '{"user":{"email":"person@example.com","is_anonymous":false,"app_metadata":{}}}'::jsonb
  ) ? 'error',
  'direct Auth signup is denied without server authority'
);
select throws_ok(
  $$insert into auth.users (
      id, aud, role, email, encrypted_password, email_confirmed_at,
      raw_app_meta_data, raw_user_meta_data, created_at, updated_at
    ) values (
      '90000000-0000-4000-8000-000000000002',
      'authenticated',
      'authenticated',
      'person@example.com',
      '',
      now(),
      '{"provider":"email","providers":["email"]}',
      '{}',
      now(),
      now()
    )$$,
  '42501',
  'Registration is not authorized.',
  'the database trigger rejects direct signup even without hosted hook configuration'
);
select throws_ok(
  $$update auth.users
    set email = 'person@example.com', updated_at = now()
    where id = '90000000-0000-4000-8000-000000000001'$$,
  '42501',
  'Registration is not authorized.',
  'the database trigger prevents an existing account from leaving pseudonymous auth'
);
select ok(
  private.authorize_synapse_private_user_creation(
    '{"user":{"email":"90000000-0000-4000-8000-000000000003@identity.synapse-private.invalid","phone":"+15555550100","is_anonymous":false,"app_metadata":{"synapse_private_registration_authority":true}}}'::jsonb
  ) ? 'error',
  'phone-bearing Auth identities are rejected even with the server marker'
);
select is(
  private.authorize_synapse_private_user_creation(
    '{"user":{"email":"10000000-0000-4000-8000-000000000001@identity.synapse-private.invalid","is_anonymous":false,"app_metadata":{"synapse_private_registration_authority":true}}}'::jsonb
  ),
  '{}'::jsonb,
  'server-authorized internal Auth creation passes the hook'
);
select ok(
  private.authorize_synapse_private_access_token(
    '{"user_id":"90000000-0000-4000-8000-000000000099","claims":{"sub":"90000000-0000-4000-8000-000000000099","email":"90000000-0000-4000-8000-000000000099@identity.synapse-private.invalid","phone":"","is_anonymous":false}}'::jsonb
  ) ? 'error',
  'an uncredentialed Auth identity cannot receive an access token'
);
select ok(
  (
    private.authorize_synapse_private_access_token(
      '{"user_id":"90000000-0000-4000-8000-000000000001","claims":{"sub":"90000000-0000-4000-8000-000000000001","email":"90000000-0000-4000-8000-000000000001@identity.synapse-private.invalid","phone":"","is_anonymous":false,"app_metadata":{"private":"remove"},"user_metadata":{"private":"remove"}}}'::jsonb
    ) -> 'claims'
  ) ?& array['sub', 'email']
  and not (
    (
      private.authorize_synapse_private_access_token(
        '{"user_id":"90000000-0000-4000-8000-000000000001","claims":{"sub":"90000000-0000-4000-8000-000000000001","email":"90000000-0000-4000-8000-000000000001@identity.synapse-private.invalid","phone":"","is_anonymous":false,"app_metadata":{"private":"remove"},"user_metadata":{"private":"remove"}}}'::jsonb
      ) -> 'claims'
    ) ?| array['app_metadata', 'user_metadata']
  ),
  'credential-backed tokens retain identity claims but strip mutable metadata'
);
select like(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'presence_state'
     and policyname = 'presence_state_select_room_peer_before_expiry'),
  '%viewer_membership.room_id = present_membership.room_id%',
  'presence visibility is restricted to users sharing a current room'
);
select like(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'presence_state'
     and policyname = 'presence_state_select_room_peer_before_expiry'),
  '%presence_sharing_enabled%',
  'presence becomes unreadable immediately when its owner opts out'
);
select like(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'typing_state'
     and policyname = 'typing_state_select_member_before_expiry'),
  '%typing_indicators_enabled%',
  'typing state becomes unreadable immediately when its owner opts out'
);
select like(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'message_receipts'
     and policyname = 'message_receipts_select_member'),
  '%read_receipts_enabled%',
  'read receipts become unreadable immediately when their owner opts out'
);
select like(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'messages'
     and policyname = 'messages_select_member_before_expiry'),
  '%can_access_message(id)%',
  'message RLS applies expiry and delete-for-everyone in one private predicate'
);
select ok(
  not exists (
    select 1
    from information_schema.role_table_grants
    where table_schema = 'public'
      and grantee = 'authenticated'
      and privilege_type = 'DELETE'
      and table_name in (
        'messages', 'message_envelopes', 'message_reply_links', 'reactions',
        'reaction_envelopes', 'message_receipts', 'attachments'
      )
  ),
  'content deletion is exposed only through checked RPCs'
);

select * from finish();
rollback;
