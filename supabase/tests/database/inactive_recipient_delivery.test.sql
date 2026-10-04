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
select is((select active_for_seconds from public.list_directory_people() where user_id='81000000-0000-4000-8000-000000000002'),0,'recipient starts inactive');
select lives_ok($$select * from public.open_direct_conversation('81000000-0000-4000-8000-000000000002')$$,'inactive recipient can receive a direct room');
select lives_ok($$select * from public.send_message(
 (select id from public.rooms), '85000000-0000-4000-8000-000000000001',
 jsonb_build_array(
   jsonb_build_object('recipient_device_id','83000000-0000-4000-8000-000000000001','protocol_adapter_version',1,'signal_message_type','LOCAL_AEAD','ciphertext_hex',repeat('a1',29)),
   jsonb_build_object('recipient_device_id','83000000-0000-4000-8000-000000000002','protocol_adapter_version',1,'signal_message_type','PREKEY','ciphertext_hex',repeat('b2',32))
 ), null)$$,'message is accepted while recipient has no activity lease');
select is((select count(*)::integer from public.messages where client_message_id='85000000-0000-4000-8000-000000000001'),1,'sender sees durable accepted message');
reset role;
select is((select count(*)::integer from private.directory_activity),0,'send does not fabricate online activity');
select set_config('request.jwt.claims','{"sub":"81000000-0000-4000-8000-000000000002","session_id":"82000000-0000-4000-8000-000000000002"}',true);
set local role authenticated;
select is((select count(*)::integer from public.messages where client_message_id='85000000-0000-4000-8000-000000000001'),1,'recipient sees the pending message when its session reads');
select is((select count(*)::integer from public.message_envelopes where recipient_device_id='83000000-0000-4000-8000-000000000002'),1,'recipient can retrieve its stored encrypted envelope');
select lives_ok('select * from public.publish_directory_activity()','recipient can announce activity after receiving stored message');
reset role;
select * from finish();
rollback;
