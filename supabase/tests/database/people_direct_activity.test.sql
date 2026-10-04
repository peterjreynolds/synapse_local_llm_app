begin;
create extension if not exists pgtap with schema extensions;
select no_plan();

insert into auth.users(id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
select ('81000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid, 'authenticated', 'authenticated',
 '81000000-0000-4000-8000-' || lpad(n::text,12,'0') || '@identity.synapse-private.invalid', '', now(),
 '{"synapse_private_registration_authority":true}', '{}', now(), now() from generate_series(1,3) n;
insert into public.profiles(user_id, display_name)
select id, 'Person ' || right(id::text,1) from auth.users where id::text like '81000000%';
insert into auth.sessions(id, user_id, created_at, updated_at)
select ('82000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
 ('81000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid, now(), now() from generate_series(1,2) n;
insert into public.devices(id,user_id,protocol_adapter_version,registration_id,signal_device_id,
 identity_key,signed_pre_key_id,signed_pre_key_public,signed_pre_key_signature,kyber_pre_key_id,kyber_pre_key_public,kyber_pre_key_signature)
select ('83000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
 ('81000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,1,1,1,
 decode('05'||repeat('11',32),'hex'),1,decode('05'||repeat('22',32),'hex'),decode(repeat('33',64),'hex'),
 1,decode('08'||repeat('44',1568),'hex'),decode(repeat('55',64),'hex') from generate_series(1,2) n;
insert into private.device_sessions(session_id,device_id)
select ('82000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
 ('83000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid from generate_series(1,2) n;

select set_config('request.jwt.claims','{"sub":"81000000-0000-4000-8000-000000000001","session_id":"82000000-0000-4000-8000-000000000001"}',true);
set local role authenticated;
select is((select count(*)::int from public.list_directory_people()),2,'directory lists other registered accounts without shared rooms');
select is((select count(*)::int from public.list_directory_people() where user_id=auth.uid()),0,'directory excludes self');
select is((select active_for_seconds from public.list_directory_people() where display_name='Person 2'),0,'missing heartbeat is inactive');
select lives_ok('select * from public.publish_directory_activity()','default opt-out account can publish directory activity');
select throws_ok($$insert into public.presence_state(device_id,expires_at) values('83000000-0000-4000-8000-000000000001',now()+interval '60 seconds')$$,
 '42501','presence sharing is not enabled','directory activity does not enable optional presence');
select throws_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000001')$$,
 '42501','Conversation is unavailable.','self selection rejected');
select throws_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000099')$$,
 '42501','Conversation is unavailable.','unknown target uses generic error');
select throws_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000003')$$,
 '42501','Conversation is unavailable.','target without eligible devices uses same error');
reset role;
select is((select count(*)::int from public.rooms),0,'failed direct creations leave no rooms');
select is((select count(*)::int from private.directory_activity),1,'one bounded heartbeat row per device');
select ok((select expires_at > statement_timestamp() and expires_at <= statement_timestamp()+interval '60 seconds' from private.directory_activity),
 'server bounds heartbeat to sixty seconds');

-- Reproduce the existing 42501 path with a valid bound session and opt-in.
update public.profiles set presence_sharing_enabled=true where user_id='81000000-0000-4000-8000-000000000001';
drop policy presence_state_select_current_device_for_upsert on public.presence_state;
set local role authenticated;
select throws_ok($$insert into public.presence_state(device_id,expires_at) values('83000000-0000-4000-8000-000000000001',now()+interval '60 seconds')
 on conflict(device_id) do update set expires_at=excluded.expires_at returning *$$,
 '42501',null,'old SELECT policy denies own UPSERT RETURNING before shared rooms exist');
reset role;
create policy presence_state_select_current_device_for_upsert on public.presence_state for select to authenticated
 using(device_id=private.current_device_id());
set local role authenticated;
select lives_ok($$insert into public.presence_state(device_id,expires_at) values('83000000-0000-4000-8000-000000000001',now()+interval '60 seconds')
 on conflict(device_id) do update set expires_at=excluded.expires_at returning *$$,'own SELECT fixes UPSERT without broadening peers');
reset role;
insert into public.rooms(id,owner_user_id,room_kind)
 values('85000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','DIRECT');
insert into public.room_members(room_id,user_id,member_role)
 values('85000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','OWNER');
alter table public.presence_state disable trigger presence_state_set_expiry;
update public.presence_state set created_at=now()-interval '120 seconds',expires_at=now()-interval '60 seconds';
alter table public.presence_state enable trigger presence_state_set_expiry;
drop policy presence_state_select_current_device_for_upsert on public.presence_state;
set local role authenticated;
select throws_ok($$insert into public.presence_state(device_id,expires_at) values('83000000-0000-4000-8000-000000000001',now()+interval '60 seconds')
 on conflict(device_id) do update set expires_at=excluded.expires_at returning *$$,
 '42501','new row violates row-level security policy (USING expression) for table "presence_state"',
 'expired own row reproduces the exact production USING-expression denial');
reset role;
create policy presence_state_select_current_device_for_upsert on public.presence_state for select to authenticated
 using(device_id=private.current_device_id());
set local role authenticated;
select lives_ok($$insert into public.presence_state(device_id,expires_at) values('83000000-0000-4000-8000-000000000001',now()+interval '60 seconds')
 on conflict(device_id) do update set expires_at=excluded.expires_at returning *$$,'expired own heartbeat can be renewed');
reset role;
delete from public.rooms where id='85000000-0000-4000-8000-000000000001';
set local role authenticated;

select throws_ok($$select * from public.claim_device_prekey('83000000-0000-4000-8000-000000000002')$$,
 '42501',null,'directory visibility alone does not authorize prekey claims');

select lives_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000002')$$,'People Chat creates direct membership');
select lives_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000002')$$,'repeat Chat succeeds');
reset role;
select is((select count(*)::int from public.rooms),1,'repeat creates exactly one room');
select is((select count(*)::int from public.room_members),2,'direct creation has exactly two members');
select is((select count(*)::int from public.room_members where member_role='OWNER'),1,'direct room has one owner');
select is((select room_kind from public.rooms),'DIRECT','created room is DIRECT');
select is((select count(*)::int from public.room_metadata_envelopes),0,'routing-only creation stores no plaintext or fabricated ciphertext metadata');
select set_config('request.jwt.claims','{"sub":"81000000-0000-4000-8000-000000000002","session_id":"82000000-0000-4000-8000-000000000002"}',true);
set local role authenticated;
select ok((select active_for_seconds between 1 and 60 from public.list_directory_people() where display_name='Person 1'),'peer sees short-lived activity');
select lives_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000001')$$,'reverse Chat resolves existing pair');
select is((select count(*)::int from public.list_room_recipient_devices((select id from public.rooms))),2,'existing Signal recipient RPC resolves both devices');
select lives_ok($$select * from public.claim_device_prekey('83000000-0000-4000-8000-000000000001')$$,'membership authorizes existing Signal prekey claim');
select throws_ok($$select private.assert_complete_signal_envelopes((select id from public.rooms),private.current_device_id(),'[]'::jsonb,262144)$$,
 '42501',null,'client cannot bypass encrypted-send gate through internal validator');
reset role;
select is((select count(*)::int from public.rooms),1,'reverse click creates no duplicate');
select throws_ok($$select private.assert_complete_signal_envelopes((select id from public.rooms),'83000000-0000-4000-8000-000000000002','[]'::jsonb,262144)$$,
 '22023',null,'empty Signal envelope set rejected on new direct room');
update public.profiles set presence_sharing_enabled=false where user_id='81000000-0000-4000-8000-000000000001';
set local role authenticated;
select is((select count(*)::int from public.presence_state),0,'opt-out presence stays hidden from shared-room peer');
reset role;
update private.directory_activity set expires_at=now()-interval '1 second';
set local role authenticated;
select is((select active_for_seconds from public.list_directory_people() where display_name='Person 1'),0,'expiry automatically becomes Inactive');
reset role;
update private.directory_activity set expires_at=now()+interval '60 seconds';
update public.devices set revoked_at=clock_timestamp() where id='83000000-0000-4000-8000-000000000001';
set local role authenticated;
select is((select active_for_seconds from public.list_directory_people() where display_name='Person 1'),0,'revoked peer heartbeat becomes Inactive immediately');
select throws_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000001')$$,
 '42501','Conversation is unavailable.','revoked target uses same unavailable error');
reset role;
select set_config('request.jwt.claims','{"sub":"81000000-0000-4000-8000-000000000001","session_id":"82000000-0000-4000-8000-000000000001"}',true);
set local role authenticated;
select throws_ok('select * from public.publish_directory_activity()','42501','Account access is not authorized.','revoked device cannot publish');
select throws_ok('select * from public.list_directory_people()','42501','Account access is not authorized.','revoked device cannot enumerate');
reset role;
update public.devices set revoked_at=null where id='83000000-0000-4000-8000-000000000001';
delete from auth.sessions where id='82000000-0000-4000-8000-000000000001';
set local role authenticated;
select throws_ok('select * from public.publish_directory_activity()','42501','Account access is not authorized.','revoked session cannot publish');
reset role;
select ok(not has_function_privilege('anon','public.list_directory_people(uuid)','EXECUTE') and
 not has_function_privilege('anon','public.publish_directory_activity()','EXECUTE') and
 not has_function_privilege('anon','public.open_direct_conversation(uuid)','EXECUTE'),'anonymous callers have no new RPC grants');
select ok(not has_table_privilege('authenticated','private.directory_activity','SELECT') and
 not has_table_privilege('authenticated','private.directory_activity','INSERT') and
 not has_table_privilege('authenticated','private.direct_account_pairs','SELECT'),'internal tables are not exposed');
select is(pg_get_function_result('public.list_directory_people(uuid)'::regprocedure),
 'TABLE(user_id uuid, display_name text, active_for_seconds integer)','directory contract exposes only intended fields');
select set_config('request.jwt.claims','{"sub":"81000000-0000-4000-8000-000000000002","session_id":"82000000-0000-4000-8000-000000000002"}',true);
update auth.users set banned_until=statement_timestamp()+interval '1 day' where id='81000000-0000-4000-8000-000000000001';
set local role authenticated;
select is((select count(*)::int from public.list_directory_people() where user_id='81000000-0000-4000-8000-000000000001'),0,'banned accounts are unavailable in directory');
select throws_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000001')$$,
 '42501','Conversation is unavailable.','banned target fails closed');
reset role;
update auth.users set banned_until=statement_timestamp()+interval '1 day' where id='81000000-0000-4000-8000-000000000002';
set local role authenticated;
select throws_ok('select * from public.publish_directory_activity()','42501','Account access is not authorized.','banned actor cannot publish even with bound device');
reset role;
select ok((select bool_and(relrowsecurity) from pg_class where oid in ('private.directory_activity'::regclass,'private.direct_account_pairs'::regclass)),
 'new private tables also enable RLS');
select ok((select bool_and(prosecdef and proconfig @> array['search_path=""']) from pg_proc
 where oid in ('private.publish_directory_activity()'::regprocedure,'private.list_directory_people(uuid)'::regprocedure,'private.open_direct_conversation(uuid)'::regprocedure)),
 'privileged implementations have fixed empty search paths');
set local role anon;
select throws_ok('select * from public.list_directory_people()','42501',null,'anonymous directory call denied');
select throws_ok('select * from public.publish_directory_activity()','42501',null,'anonymous publication denied');
reset role;
select * from finish();
rollback;
