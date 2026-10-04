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


select ok(not has_function_privilege('anon','private.can_receive_directory_updates()','EXECUTE'),'anonymous channel authorization denied');
select ok(not has_function_privilege('authenticated','private.broadcast_directory_change()','EXECUTE'),'clients cannot call the broadcast trigger');
select set_config('request.jwt.claims','{"sub":"81000000-0000-4000-8000-000000000001","session_id":"82000000-0000-4000-8000-000000000001"}',true);
select set_config('realtime.topic','synapse-private-directory',true);
set local role authenticated;
select ok(private.can_receive_directory_updates(),'bound device may subscribe');
select lives_ok('select * from public.publish_directory_activity()','activity publication and broadcast commit together');
reset role;
select is((select count(*)::int from realtime.messages where topic='synapse-private-directory' and event='directory_changed'),1,'one directory invalidation broadcast');
select ok((select bool_and(payload - 'id' = '{}'::jsonb and private) from realtime.messages where topic='synapse-private-directory'),'broadcast contains no user or device data and is private');
set local role authenticated;
select is((select count(*)::int from realtime.messages where topic='synapse-private-directory'),1,'bound device receives directory invalidation');
select set_config('realtime.topic','other-channel',true);
select is((select count(*)::int from realtime.messages),0,'unrelated channel is denied');
reset role;
select set_config('realtime.topic','synapse-private-directory',true);
update public.devices set revoked_at=statement_timestamp() where id='83000000-0000-4000-8000-000000000001';
set local role authenticated;
select ok(not private.can_receive_directory_updates(),'revoked device cannot subscribe');
select is((select count(*)::int from realtime.messages),0,'revoked device cannot read invalidations');
reset role;
update public.devices set revoked_at=null where id='83000000-0000-4000-8000-000000000001';
delete from auth.sessions where id='82000000-0000-4000-8000-000000000001';
set local role authenticated;
select ok(not private.can_receive_directory_updates(),'ended session cannot subscribe');
reset role;
select * from finish();
rollback;
