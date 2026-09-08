begin;
create extension if not exists pgtap with schema extensions;
select plan(96);

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
     'message_receipts', 'typing_state', 'presence_state', 'attachments',
     'room_metadata_envelopes', 'message_revisions', 'message_revision_envelopes',
     'room_member_preferences'
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
        'typing_state', 'presence_state', 'attachments', 'room_metadata_envelopes',
        'message_revisions', 'message_revision_envelopes', 'room_member_preferences'
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
  not exists (
    select 1
    from auth.users as auth_user
    where not private.auth_identity_has_bound_internal_address(
      auth_user.id,
      auth_user.email,
      auth_user.phone,
      auth_user.is_anonymous
    )
  ),
  'every existing Auth identity satisfies the UUID-bound internal-address invariant'
);
select ok(
  lower(pg_get_functiondef(
    'private.configure_bootstrap_capability(bytea,integer)'::regprocedure
  )) like '%delete from private.bootstrap_capabilities%where singleton%'
  and lower(pg_get_functiondef(
    'private.redeem_account_registration(bytea,uuid,bytea,text,uuid,text)'::regprocedure
  )) like '%delete from private.bootstrap_capabilities%where singleton%',
  'bootstrap mutation functions use safeupdate-compatible bounded deletes'
);
select ok(
  (
    select prosecdef and proconfig @> array['search_path=""']::text[]
    from pg_proc
    where oid = 'private.enforce_synapse_private_auth_user_identity()'::regprocedure
  ),
  'the Auth insert guard is security-definer with an empty search path'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'private.auth_identity_has_bound_internal_address(uuid,text,text,boolean)',
    'EXECUTE'
  )
  and pg_get_functiondef(
    'private.auth_identity_has_bound_internal_address(uuid,text,text,boolean)'::regprocedure
  ) like '%p_user_id::text || ''@identity.synapse-private.invalid''%'
  and pg_get_functiondef('private.enforce_synapse_private_auth_user_identity()'::regprocedure)
    like '%private.auth_identity_has_bound_internal_address(%',
  'the transient Auth insert requires an inaccessible user-id-bound internal identity'
);
select ok(
  lower(pg_get_functiondef('private.authorize_synapse_private_user_creation(jsonb)'::regprocedure))
    like '%private.auth_identity_is_synapse_private_authorized%'
  and lower(
    pg_get_functiondef(
      'private.auth_identity_is_synapse_private_authorized(text,text,boolean,jsonb)'::regprocedure
    )
  ) like '%p_app_metadata ->> ''synapse_private_registration_authority''%',
  'registration authority comes from server-controlled app metadata'
);
select ok(
  pg_get_functiondef('private.assert_complete_signal_envelopes(uuid,uuid,jsonb,integer)'::regprocedure)
    like '%jsonb_array_length(p_envelopes) not between 1 and 129%'
  and pg_get_functiondef('private.assert_complete_signal_envelopes(uuid,uuid,jsonb,integer)'::regprocedure)
    like '%sender device requires a LOCAL_AEAD envelope%'
  and pg_get_functiondef('private.assert_complete_signal_envelopes(uuid,uuid,jsonb,integer)'::regprocedure)
    like '%more than 128 peer envelopes%',
  'fan-out contains one durable self envelope and at most 128 peer envelopes'
);
select ok(
  pg_get_functiondef('private.assert_reply_link_invariant()'::regprocedure)
    like '%source_room_id <> target_room_id%',
  'reply links fail closed across rooms'
);
select ok(
  pg_get_functiondef('private.register_device_session(uuid,uuid,uuid,smallint,integer,smallint,bytea,integer,bytea,bytea,integer,bytea,bytea,integer,bytea)'::regprocedure)
    like '%active device registration reservation is required%',
  'device registration consumes a server allocation before binding RLS'
);
select ok(
  pg_get_functiondef('private.register_device_session(uuid,uuid,uuid,smallint,integer,smallint,bytea,integer,bytea,bytea,integer,bytea,bytea,integer,bytea)'::regprocedure)
    like '%for update of room%',
  'device registration locks affected rooms deterministically'
);
select ok(
  pg_get_functiondef('private.redeem_room_membership_invite(uuid,uuid,bytea,uuid)'::regprocedure)
    like '%for update%',
  'room invite redemption serializes membership changes'
);
select ok(
  pg_get_functiondef('private.claim_device_prekey(uuid)'::regprocedure)
    like '%target_device.user_id = actor_user_id%',
  'same-account secondary devices may claim prekeys'
);
select ok(
  pg_get_functiondef('private.current_device_id()'::regprocedure)
    like '%device.revoked_at is null%',
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
      and octet_length(invite_derivation_key) = 32
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
select ok(
  pg_get_functiondef('private.set_presence_expiry()'::regprocedure)
    like '%interval ''60 seconds''%',
  'presence is server-expired after 60 seconds'
);
select ok(
  pg_get_functiondef('private.set_typing_expiry()'::regprocedure)
    like '%profile.typing_indicators_enabled%',
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
select lives_ok(
  $$insert into auth.users (
      id, aud, role, email, encrypted_password, email_confirmed_at,
      raw_app_meta_data, raw_user_meta_data, created_at, updated_at
    ) values (
      '90000000-0000-4000-8000-000000000004',
      'authenticated',
      'authenticated',
      '90000000-0000-4000-8000-000000000004@identity.synapse-private.invalid',
      '',
      now(),
      '{"provider":"email","providers":["email"]}',
      '{}',
      now(),
      now()
    )$$,
  'the Auth admin phase-one insert may precede server app metadata persistence'
);
select lives_ok(
  $$update auth.users
    set updated_at = now()
    where id = '90000000-0000-4000-8000-000000000004'$$,
  'the bound identity permits Auth intermediate updates before app metadata persistence'
);
delete from auth.users
where id = '90000000-0000-4000-8000-000000000004';
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
select ok(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'presence_state'
     and policyname = 'presence_state_select_room_peer_before_expiry')
    like '%viewer_membership.room_id = present_membership.room_id%',
  'presence visibility is restricted to users sharing a current room'
);
select ok(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'presence_state'
     and policyname = 'presence_state_select_room_peer_before_expiry')
    like '%presence_sharing_enabled%',
  'presence becomes unreadable immediately when its owner opts out'
);
select ok(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'typing_state'
     and policyname = 'typing_state_select_member_before_expiry')
    like '%typing_indicators_enabled%',
  'typing state becomes unreadable immediately when its owner opts out'
);
select ok(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'message_receipts'
     and policyname = 'message_receipts_select_member')
    like '%read_receipts_enabled%',
  'read receipts become unreadable immediately when their owner opts out'
);
select ok(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'messages'
     and policyname = 'messages_select_member_before_expiry')
    like '%can_access_message(id)%',
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

select ok(
  (
    select bool_and(has_table_privilege('authenticated', table_schema || '.' || table_name, 'SELECT'))
    from information_schema.tables
    where table_schema = 'public'
      and table_name in (
        'room_metadata_envelopes', 'message_revisions',
        'message_revision_envelopes', 'room_member_preferences'
      )
  ),
  'authenticated devices receive read-only Data API grants for new encrypted state'
);
select ok(
  not exists (
    select 1
    from information_schema.role_table_grants
    where table_schema = 'public'
      and grantee = 'authenticated'
      and table_name in (
        'room_metadata_envelopes', 'message_revisions',
        'message_revision_envelopes', 'room_member_preferences'
      )
      and privilege_type in ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'TRIGGER', 'REFERENCES')
  ),
  'new encrypted state mutates only through checked RPCs'
);
select ok(
  not has_function_privilege('authenticated', 'public.create_room(text,integer)', 'EXECUTE'),
  'legacy non-atomic room creation is unavailable to authenticated clients'
);
select ok(
  has_function_privilege('authenticated', 'public.create_room_with_metadata(uuid,text,uuid,jsonb,integer)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.set_room_metadata(uuid,uuid,integer,jsonb)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.edit_message(uuid,uuid,integer,jsonb)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.remove_reaction(uuid,uuid)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.set_room_preferences(uuid,uuid,text,text,text,timestamptz)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.list_room_recipient_devices(uuid)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.list_current_account_recipient_devices()', 'EXECUTE'),
  'authenticated clients can execute each narrow checked mutation contract'
);
select ok(
  not exists (
    select 1
    from information_schema.columns
    where table_schema = 'public'
      and table_name in (
        'rooms', 'room_metadata_envelopes', 'messages',
        'message_revisions', 'message_revision_envelopes'
      )
      and column_name ~ '(title|body|plaintext|content)'
  ),
  'room titles and edited message content have no plaintext column'
);
select is(
  (
    select count(distinct constraint_table.oid)::integer
    from pg_constraint as constraint_definition
    join pg_class as constraint_table on constraint_table.oid = constraint_definition.conrelid
    join pg_namespace as table_schema on table_schema.oid = constraint_table.relnamespace
    where table_schema.nspname = 'public'
      and constraint_table.relname in (
        'message_envelopes', 'reaction_envelopes',
        'room_metadata_envelopes', 'message_revision_envelopes'
      )
      and pg_get_constraintdef(constraint_definition.oid) like '%LOCAL_AEAD%'
  ),
  4,
  'every encrypted fan-out table constrains the durable self-envelope type'
);
select ok(
  pg_get_functiondef('private.edit_message(uuid,uuid,integer,jsonb)'::regprocedure)
    like '%delete from public.message_envelopes%'
  and pg_get_functiondef('private.edit_message(uuid,uuid,integer,jsonb)'::regprocedure)
    like '%delete from public.message_revisions%',
  'confirmed edits physically remove initial and prior revision ciphertext'
);
select ok(
  lower(pg_get_functiondef('private.can_read_device_bundle(uuid)'::regprocedure))
    like '%target_device.revoked_at is null%'
  and lower(pg_get_functiondef('private.can_read_device_bundle(uuid)'::regprocedure))
    like '%revision.expires_at > statement_timestamp()%'
  and lower(pg_get_functiondef('private.can_read_device_bundle(uuid)'::regprocedure))
    like '%private.can_access_message%',
  'revoked sender crypto context remains visible only while accessible ciphertext requires it'
);
select ok(
  lower(pg_get_functiondef('private.list_room_recipient_devices(uuid)'::regprocedure))
    like '%device.revoked_at is null%'
  and lower(pg_get_functiondef('private.list_room_recipient_devices(uuid)'::regprocedure))
    like '%recipient_count not between 1 and 129%',
  'recipient enumeration excludes revoked devices and enforces the 129-device total cap'
);
select ok(
  (
    select lower(qual) like '%select auth.uid()%'
      and qual like '%private.current_device_id()%'
    from pg_policies
    where schemaname = 'public'
      and tablename = 'room_member_preferences'
      and policyname = 'room_member_preferences_select_self'
  ),
  'room preferences are visible only to the bound owning member with initplan auth lookup'
);
select ok(
  pg_get_functiondef(
    'private.purge_expired_relational_data_without_core_mutation_receipts(integer,uuid)'::regprocedure
  )
    like '%expired_room_creation_receipts%'
  and pg_get_functiondef(
    'private.purge_expired_relational_data_without_core_mutation_receipts(integer,uuid)'::regprocedure
  )
    like '%expired_room_metadata_receipts%'
  and pg_get_functiondef(
    'private.purge_expired_relational_data_without_core_mutation_receipts(integer,uuid)'::regprocedure
  )
    like '%expired_message_revision_receipts%'
  and pg_get_functiondef(
    'private.purge_expired_relational_data_without_core_mutation_receipts(integer,uuid)'::regprocedure
  )
    like '%expired_reaction_removal_receipts%'
  and pg_get_functiondef(
    'private.purge_expired_relational_data_without_core_mutation_receipts(integer,uuid)'::regprocedure
  )
    like '%expired_room_preference_receipts%'
  and pg_get_functiondef('private.purge_expired_relational_data(integer,uuid)'::regprocedure)
    like '%expired_invite_issuance_receipts%'
  and pg_get_functiondef('private.purge_expired_relational_data(integer,uuid)'::regprocedure)
    like '%expired_room_member_removal_receipts%'
  and pg_get_functiondef('private.purge_expired_relational_data(integer,uuid)'::regprocedure)
    like '%expired_message_deletion_receipts%'
  and pg_get_function_result('private.purge_expired_relational_data(integer,uuid)'::regprocedure)
    like '%mutation_receipts_deleted integer%',
  'retention purges bounded non-content mutation receipts and reports the count'
);
select is(
  (
    select count(*)::integer
    from pg_indexes
    where schemaname in ('public', 'private')
      and indexname in (
        'rooms_owner_user_id_idx', 'messages_sender_device_id_idx',
        'reactions_sender_device_id_idx', 'message_reply_links_replied_to_message_id_idx',
        'message_receipts_recipient_device_id_idx', 'typing_state_device_id_idx',
        'attachments_uploader_device_id_idx',
        'account_registration_invites_issued_by_user_id_idx',
        'account_registration_receipts_user_id_idx',
        'room_membership_invites_room_id_idx',
        'room_membership_invites_issued_by_user_id_idx',
        'room_membership_invite_receipts_room_id_idx',
        'room_membership_invite_receipts_user_id_idx',
        'message_deletion_requests_requested_by_user_id_idx'
      )
  ),
  14,
  'advisor-identified foreign keys have covering indexes'
);
select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'public'
      and policyname in (
        'profiles_update_self', 'message_receipts_insert_recipient',
        'presence_state_select_room_peer_before_expiry', 'attachments_insert_sender'
      )
      and lower(coalesce(qual, '') || coalesce(with_check, '')) like '%select auth.uid()%'
  ),
  4,
  'advisor-identified RLS policies use initplan auth lookups'
);
select ok(
  (
    select count(*) = 11
      and bool_and(prosecdef)
      and bool_and(proconfig @> array['search_path=""']::text[])
    from pg_proc
    join pg_namespace on pg_namespace.oid = pronamespace
    where nspname = 'private'
      and proname in (
        'can_read_device_bundle', 'set_room_metadata', 'edit_message',
        'remove_reaction', 'set_room_preferences', 'list_room_recipient_devices',
        'list_current_account_recipient_devices',
        'create_room_with_metadata', 'send_message', 'send_reaction',
        'purge_expired_relational_data'
      )
  ),
  'new privileged functions are security-definer with empty search paths'
);
select ok(
  (
    select count(*) = 7
      and bool_and(not prosecdef)
      and bool_and(proconfig @> array['search_path=""']::text[])
    from pg_proc
    join pg_namespace on pg_namespace.oid = pronamespace
    where nspname = 'public'
      and proname in (
        'create_room_with_metadata', 'set_room_metadata', 'edit_message',
        'remove_reaction', 'set_room_preferences', 'list_room_recipient_devices',
        'list_current_account_recipient_devices'
      )
  ),
  'public mutation wrappers are security-invoker with empty search paths'
);
select ok(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'room_metadata_envelopes'
     and policyname = 'room_metadata_envelopes_select_recipient')
    like '%room.metadata_revision = room_metadata_envelopes.metadata_revision%',
  'room metadata RLS exposes only the current encrypted revision'
);
select ok(
  (select qual from pg_policies
   where schemaname = 'public'
     and tablename = 'message_revisions'
     and policyname = 'message_revisions_select_current_member')
    like '%message.current_revision = message_revisions.revision_number%',
  'message revision RLS exposes only the current unexpired encrypted revision'
);
select ok(
  lower(pg_get_functiondef('private.can_access_message(uuid)'::regprocedure))
    like '%message.created_at >= room_member.joined_at%'
  and lower(pg_get_functiondef('private.can_access_message(uuid)'::regprocedure))
    like '%from public.message_envelopes%'
  and lower(pg_get_functiondef('private.can_access_message(uuid)'::regprocedure))
    like '%from public.message_revisions%'
  and lower(pg_get_functiondef('private.can_access_message(uuid)'::regprocedure))
    like '%join public.message_revision_envelopes%'
  and lower(pg_get_functiondef('private.can_access_message(uuid)'::regprocedure))
    like '%recipient_device_id = private.current_device_id()%'
  and lower(pg_get_functiondef('private.can_access_message(uuid)'::regprocedure))
    like '%private.message_deletion_requests%',
  'message access requires current-device ciphertext after the current membership began'
);
select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'public'
      and policyname in (
        'message_reply_links_select_member', 'reactions_select_member',
        'message_receipts_select_member', 'attachments_select_member_before_message_expiry',
        'message_revisions_select_current_member'
      )
      and lower(coalesce(qual, '')) like '%can_access_message%'
  ),
  5,
  'reply, reaction, receipt, attachment, and revision graphs inherit device-envelope access'
);
select ok(
  lower(pg_get_functiondef('private.send_message(uuid,uuid,uuid,jsonb)'::regprocedure))
    like '%private.can_access_message(replied_to.id)%'
  and lower(pg_get_functiondef('private.send_reaction(uuid,uuid,jsonb)'::regprocedure))
    like '%not private.can_access_message(p_message_id)%',
  'reply and reaction mutations reject parent messages unavailable to the current device'
);

select ok(
  has_function_privilege(
    'service_role',
    'public._edge_issue_account_registration_invite(uuid,uuid,uuid,bytea,integer)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public._edge_issue_room_membership_invite(uuid,uuid,uuid,uuid,bytea,integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public._edge_issue_account_registration_invite(uuid,uuid,uuid,bytea,integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public._edge_issue_room_membership_invite(uuid,uuid,uuid,uuid,bytea,integer)',
    'EXECUTE'
  ),
  'deterministic invite issuance remains service-role only'
);
select ok(
  to_regprocedure(
    'public._edge_issue_account_registration_invite(uuid,uuid,bytea,integer)'
  ) is null
  and to_regprocedure(
    'public._edge_issue_room_membership_invite(uuid,uuid,uuid,bytea,integer)'
  ) is null,
  'receiptless Edge invite signatures are removed'
);
select ok(
  has_function_privilege(
    'authenticated', 'public.update_room_retention(uuid,uuid,integer)', 'EXECUTE'
  )
  and has_function_privilege(
    'authenticated', 'public.update_room_member_role(uuid,uuid,uuid,text)', 'EXECUTE'
  )
  and has_function_privilege(
    'authenticated', 'public.remove_room_member(uuid,uuid,uuid)', 'EXECUTE'
  )
  and has_function_privilege(
    'authenticated', 'public.delete_message_for_everyone(uuid,uuid,integer)', 'EXECUTE'
  )
  and not has_function_privilege(
    'anon', 'public.update_room_retention(uuid,uuid,integer)', 'EXECUTE'
  )
  and not has_function_privilege(
    'anon', 'public.update_room_member_role(uuid,uuid,uuid,text)', 'EXECUTE'
  )
  and not has_function_privilege(
    'anon', 'public.remove_room_member(uuid,uuid,uuid)', 'EXECUTE'
  )
  and not has_function_privilege(
    'anon', 'public.delete_message_for_everyone(uuid,uuid,integer)', 'EXECUTE'
  ),
  'bound authenticated devices alone receive the idempotent core mutation RPCs'
);
select ok(
  to_regprocedure('public.update_room_retention(uuid,integer)') is null
  and to_regprocedure('public.update_room_member_role(uuid,uuid,text)') is null
  and to_regprocedure('public.remove_room_member(uuid,uuid)') is null
  and to_regprocedure('public.delete_message_for_everyone(uuid)') is null
  and to_regprocedure('public.delete_message_for_everyone(uuid,uuid)') is null,
  'receiptless core mutation signatures are removed'
);
select ok(
  not has_function_privilege(
    'service_role', 'private.derive_invite_code_digest(uuid,text,uuid,uuid)', 'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated', 'private.derive_invite_code_digest(uuid,text,uuid,uuid)', 'EXECUTE'
  ),
  'the database-owned invite derivation key has no callable digest oracle'
);
select ok(
  not exists (
    select 1
    from information_schema.columns
    where table_schema = 'private'
      and table_name in (
        'invite_issuance_mutation_receipts', 'room_retention_mutation_receipts',
        'room_member_role_mutation_receipts', 'room_member_removal_mutation_receipts',
        'message_deletion_mutation_receipts'
      )
      and column_name ~ '(plaintext|ciphertext|invite_code|raw_code|message_body|room_title)'
  ),
  'core mutation receipts retain no plaintext, ciphertext, or raw invite capability'
);
select ok(
  pg_get_functiondef(
    'private.issue_invite(uuid,uuid,uuid,text,uuid,bytea,integer)'::regprocedure
  ) like '%derive_invite_code_digest%'
  and pg_get_functiondef(
    'private.issue_invite(uuid,uuid,uuid,text,uuid,bytea,integer)'::regprocedure
  ) like '%request_digest%'
  and pg_get_functiondef(
    'private.issue_invite(uuid,uuid,uuid,text,uuid,bytea,integer)'::regprocedure
  ) like '%invite_issuance_mutation_receipts%',
  'invite issuance validates deterministic derivation and exact retry input'
);
select ok(
  pg_get_functiondef('private.update_room_retention(uuid,uuid,integer)'::regprocedure)
    like '%room_retention_mutation_receipts%'
  and pg_get_functiondef('private.update_room_member_role(uuid,uuid,uuid,text)'::regprocedure)
    like '%room_member_role_mutation_receipts%'
  and pg_get_functiondef('private.remove_room_member(uuid,uuid,uuid)'::regprocedure)
    like '%room_member_removal_mutation_receipts%'
  and pg_get_functiondef('private.delete_message_for_everyone(uuid,uuid,integer)'::regprocedure)
    like '%message_deletion_mutation_receipts%'
  and pg_get_functiondef('private.update_room_retention(uuid,uuid,integer)'::regprocedure)
    like '%request_digest%'
  and pg_get_functiondef('private.update_room_member_role(uuid,uuid,uuid,text)'::regprocedure)
    like '%request_digest%'
  and pg_get_functiondef('private.remove_room_member(uuid,uuid,uuid)'::regprocedure)
    like '%request_digest%'
  and pg_get_functiondef('private.delete_message_for_everyone(uuid,uuid,integer)'::regprocedure)
    like '%request_digest%'
  and pg_get_functiondef('private.delete_message_for_everyone(uuid,uuid,integer)'::regprocedure)
    like '%current_revision <> p_expected_revision%',
  'each core mutation binds its retry receipt to the complete request identity and delete CAS'
);
select ok(
  (
    select count(*) = 9
      and bool_and(prosecdef)
      and bool_and(proconfig @> array['search_path=""']::text[])
    from pg_proc
    join pg_namespace on pg_namespace.oid = pronamespace
    where nspname = 'private'
      and proname in (
        'initialize_runtime_configuration', 'derive_invite_code_digest',
        'assert_invite_code_available', 'issue_invite', 'update_room_retention',
        'update_room_member_role', 'remove_room_member',
        'delete_message_for_everyone', 'purge_expired_relational_data'
      )
  ),
  'core idempotency security-definer functions pin an empty search path'
);
select ok(
  (
    select count(*) = 7
      and bool_and(not prosecdef)
      and bool_and(proconfig @> array['search_path=""']::text[])
    from pg_proc
    join pg_namespace on pg_namespace.oid = pronamespace
    where nspname = 'public'
      and proname in (
        '_edge_initialize_runtime_configuration',
        '_edge_issue_account_registration_invite',
        '_edge_issue_room_membership_invite', 'update_room_retention',
        'update_room_member_role', 'remove_room_member',
        'delete_message_for_everyone'
      )
  ),
  'core idempotency public wrappers are invokers with empty search paths'
);
select is(
  (
    select count(*)::integer
    from pg_indexes
    where schemaname = 'private'
      and indexname in (
        'invite_issuance_mutation_receipts_room_id_idx',
        'room_retention_mutation_receipts_room_id_idx',
        'room_member_role_mutation_receipts_room_id_idx',
        'room_member_role_mutation_receipts_member_user_idx',
        'room_member_removal_mutation_receipts_room_id_idx'
      )
  ),
  5,
  'new receipt foreign keys have covering indexes'
);

select ok(
  (
    select count(*) = 4 and bool_and(relrowsecurity)
    from pg_class
    join pg_namespace on pg_namespace.oid = relnamespace
    where nspname = 'private'
      and relname in (
        'device_envelope_capacity', 'device_room_envelope_capacity',
        'device_sender_envelope_capacity',
        'envelope_capacity_contributions'
      )
  )
  and not has_table_privilege('authenticated', 'private.device_envelope_capacity', 'SELECT')
  and not has_table_privilege('authenticated', 'private.device_room_envelope_capacity', 'SELECT')
  and not has_table_privilege('authenticated', 'private.device_sender_envelope_capacity', 'SELECT')
  and not has_table_privilege('authenticated', 'private.envelope_capacity_contributions', 'SELECT')
  and not has_table_privilege('anon', 'private.device_envelope_capacity', 'SELECT')
  and not has_table_privilege('anon', 'private.device_room_envelope_capacity', 'SELECT')
  and not has_table_privilege('anon', 'private.device_sender_envelope_capacity', 'SELECT')
  and not has_table_privilege('anon', 'private.envelope_capacity_contributions', 'SELECT'),
  'encrypted capacity ledgers are private, RLS-enabled, and inaccessible to clients'
);
select ok(
  exists (
    select 1
    from pg_attribute
    where attrelid = 'public.rooms'::regclass
      and attname = 'creation_client_mutation_id'
      and not attisdropped
  )
  and not (
    select attnotnull
    from pg_attribute
    where attrelid = 'public.rooms'::regclass
      and attname = 'creation_client_mutation_id'
      and not attisdropped
  )
  and exists (
    select 1
    from pg_indexes
    where schemaname = 'public'
      and tablename = 'rooms'
      and indexname = 'rooms_owner_creation_mutation_unique'
      and indexdef like '%(owner_user_id, creation_client_mutation_id)%'
      and indexdef like '%WHERE (creation_client_mutation_id IS NOT NULL)%'
  ),
  'new rooms have a unique owner-scoped creation binding without breaking legacy rooms'
);
select ok(
  pg_get_functiondef(
    'private.create_room_with_metadata(uuid,text,integer,uuid,jsonb)'::regprocedure
  ) like '%creation_client_mutation_id%'
  and pg_get_functiondef(
    'private.create_room_with_metadata(uuid,text,integer,uuid,jsonb)'::regprocedure
  ) like '%p_client_mutation_id%'
  and pg_get_functiondef(
    'private.create_room_with_metadata(uuid,text,integer,uuid,jsonb)'::regprocedure
  ) like '%p_room_id%',
  'room creation persists encrypted metadata room and mutation identities in its authoritative row'
);
select ok(
  (
    select count(*) = 2
      and bool_and(not prosecdef)
      and bool_and(proconfig @> array['search_path=""']::text[])
    from pg_proc
    join pg_namespace on pg_namespace.oid = pronamespace
    where nspname = 'public'
      and proname in ('send_message', 'send_reaction')
  )
  and has_function_privilege(
    'authenticated', 'public.send_message(uuid,uuid,jsonb,uuid)', 'EXECUTE'
  )
  and has_function_privilege(
    'authenticated', 'public.send_reaction(uuid,uuid,jsonb)', 'EXECUTE'
  )
  and not has_function_privilege(
    'anon', 'public.send_message(uuid,uuid,jsonb,uuid)', 'EXECUTE'
  )
  and not has_function_privilege(
    'anon', 'public.send_reaction(uuid,uuid,jsonb)', 'EXECUTE'
  ),
  'content RPC wrappers are empty-path invokers granted only to authenticated clients'
);
select ok(
  pg_get_function_result('public.send_message(uuid,uuid,jsonb,uuid)'::regprocedure)
    like '%room_id uuid%client_mutation_id uuid%'
  and pg_get_function_result('public.send_reaction(uuid,uuid,jsonb)'::regprocedure)
    like '%message_id uuid%client_mutation_id uuid%',
  'content RPC receipts expose the parent and client mutation identities for correlation'
);
select ok(
  (
    select count(*) = 4
      and bool_and(prosecdef)
      and bool_and(proconfig @> array['search_path=""']::text[])
    from pg_proc
    join pg_namespace on pg_namespace.oid = pronamespace
    where nspname = 'private'
      and proname in (
        'reserve_device_envelope_capacity', 'release_device_envelope_capacity',
        'prevent_envelope_capacity_key_update', 'enforce_message_send_rate'
      )
  )
  and not has_function_privilege(
    'authenticated', 'private.reserve_device_envelope_capacity()', 'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated', 'private.release_device_envelope_capacity()', 'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated', 'private.prevent_envelope_capacity_key_update()', 'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated', 'private.enforce_message_send_rate()', 'EXECUTE'
  )
  and lower(
    pg_get_functiondef('private.prevent_envelope_capacity_key_update()'::regprocedure)
  ) like '%new_parent_record_id is distinct from old_parent_record_id%',
  'capacity and rate trigger functions are inaccessible empty-path security definers'
);
select ok(
  (
    select count(*) = 12
    from pg_trigger
    where not tgisinternal
      and tgfoid in (
        'private.reserve_device_envelope_capacity()'::regprocedure,
        'private.release_device_envelope_capacity()'::regprocedure,
        'private.prevent_envelope_capacity_key_update()'::regprocedure
      )
      and tgrelid in (
        'public.message_envelopes'::regclass,
        'public.message_revision_envelopes'::regclass,
        'public.reaction_envelopes'::regclass,
        'public.room_metadata_envelopes'::regclass
      )
  )
  and (
    select count(*) = 4
      and bool_and((tgtype::integer & 1) = 0)
      and bool_and(lower(pg_get_triggerdef(oid)) like '%referencing new table as inserted_envelopes%')
    from pg_trigger
    where not tgisinternal
      and tgname like 'reserve_%_capacity'
  )
  and (
    select count(*) = 4
      and bool_and((tgtype::integer & 1) = 0)
      and bool_and(lower(pg_get_triggerdef(oid)) like '%referencing old table as deleted_envelopes%')
    from pg_trigger
    where not tgisinternal
      and tgname like 'release_%_capacity'
  ),
  'every encrypted-envelope table uses statement transition triggers and immutable-row protection'
);
select ok(
  to_regclass('public.messages_sender_room_created_idx') is not null
  and exists (
    select 1
    from pg_trigger
    where not tgisinternal
      and tgname = 'enforce_message_send_rate'
      and tgrelid = 'public.messages'::regclass
  ),
  'message rate enforcement has its actor-room time index and insert trigger'
);

select * from finish();
rollback;
