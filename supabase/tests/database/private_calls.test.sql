begin;
create extension if not exists pgtap with schema extensions;
select no_plan();

insert into auth.users(id,aud,role,email,encrypted_password,email_confirmed_at,
  raw_app_meta_data,raw_user_meta_data,created_at,updated_at,is_anonymous)
select ('81000000-0000-4000-8000-'||lpad(account_number::text,12,'0'))::uuid,
  'authenticated','authenticated',
  '81000000-0000-4000-8000-'||lpad(account_number::text,12,'0')||'@identity.synapse-private.invalid',
  '',now(),'{"synapse_private_registration_authority":true}','{}',now(),now(),false
from generate_series(1,3) account_number;
insert into public.profiles(user_id,display_name)
select id,'Calling fixture' from auth.users where id in(
  '81000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000003');
insert into auth.sessions(id,user_id,created_at,updated_at)
select ('86000000-0000-4000-8000-'||lpad(device_number::text,12,'0'))::uuid,
  ('81000000-0000-4000-8000-'||lpad((case when device_number=4 then 2 else device_number end)::text,12,'0'))::uuid,
  now(),now() from generate_series(1,4) device_number;
insert into public.devices(id,user_id,protocol_adapter_version,registration_id,signal_device_id,
  identity_key,signed_pre_key_id,signed_pre_key_public,signed_pre_key_signature,
  kyber_pre_key_id,kyber_pre_key_public,kyber_pre_key_signature)
select ('82000000-0000-4000-8000-'||lpad(device_number::text,12,'0'))::uuid,
  ('81000000-0000-4000-8000-'||lpad((case when device_number=4 then 2 else device_number end)::text,12,'0'))::uuid,
  1,device_number,case when device_number=4 then 2 else 1 end,
  decode('05'||repeat('11',32),'hex'),1,decode('05'||repeat('22',32),'hex'),decode(repeat('33',64),'hex'),
  1,decode('08'||repeat('44',1568),'hex'),decode(repeat('55',64),'hex') from generate_series(1,4) device_number;
insert into private.device_sessions(session_id,device_id)
select ('86000000-0000-4000-8000-'||lpad(device_number::text,12,'0'))::uuid,
  ('82000000-0000-4000-8000-'||lpad(device_number::text,12,'0'))::uuid from generate_series(1,4) device_number;
insert into public.rooms(id,owner_user_id,room_kind,retention_seconds)
values
  ('83000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','DIRECT',300),
  ('83000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000003','DIRECT',300),
  ('83000000-0000-4000-8000-000000000003','81000000-0000-4000-8000-000000000001','GROUP',300);
insert into public.room_members(room_id,user_id,member_role) values
  ('83000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','OWNER'),
  ('83000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000002','MEMBER'),
  ('83000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000003','OWNER'),
  ('83000000-0000-4000-8000-000000000003','81000000-0000-4000-8000-000000000001','OWNER'),
  ('83000000-0000-4000-8000-000000000003','81000000-0000-4000-8000-000000000002','MEMBER');

create function pg_temp.call_actor(p_device_number integer) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims',jsonb_build_object(
    'sub','81000000-0000-4000-8000-'||lpad((case when p_device_number=4 then 2 else p_device_number end)::text,12,'0'),
    'session_id','86000000-0000-4000-8000-'||lpad(p_device_number::text,12,'0'),
    'role','authenticated')::text,true);
end;
$$;
create function pg_temp.call_envelopes(p_device_numbers integer[],p_byte_count integer default 64)
returns jsonb language sql as $$
  select jsonb_agg(jsonb_build_object(
    'recipient_device_id','82000000-0000-4000-8000-'||lpad(device_number::text,12,'0'),
    'protocol_adapter_version',1,'signal_message_type','PREKEY','ciphertext_hex',repeat('ab',p_byte_count))
    order by device_number) from unnest(p_device_numbers) device_number;
$$;
create function pg_temp.call_expiry() returns timestamptz language sql as $$
  select current_setting('synapse_call_test.expires_at')::timestamptz;
$$;
select set_config('synapse_call_test.expires_at',(statement_timestamp()+interval '50 seconds')::text,true);

select ok((select bool_and(relrowsecurity) from pg_class join pg_namespace on pg_namespace.oid=relnamespace
  where nspname='private' and relname in('call_sessions','call_devices','call_device_leases',
    'call_signals','call_signal_envelopes','call_mutation_receipts')),'all call tables enable RLS');
select ok(not has_table_privilege('authenticated','private.call_signal_envelopes','SELECT')
  and not has_table_privilege('anon','private.call_sessions','SELECT'),'call tables are not direct client APIs');
select ok(not has_function_privilege('anon','public.create_private_call(uuid,uuid,integer,text,uuid,timestamptz,jsonb)','EXECUTE')
  and not has_function_privilege('authenticated','private.purge_expired_private_calls()','EXECUTE'),
  'anonymous mutations and direct purge invocation are denied');

set local role authenticated;
select throws_ok($$select public.poll_private_calls()$$,'42501','an active device session is required','poll requires a bound authoritative session');
select pg_temp.call_actor(3);
select throws_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000001',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  '42501','a current two-member direct room is required','a nonmember cannot call the room');
select pg_temp.call_actor(1);
select throws_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000003',1,'VOICE',
  '85000000-0000-4000-8000-000000000001',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  '42501','a current two-member direct room is required','group calling is not silently treated as direct calling');
select throws_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001',2,'VOICE',
  '85000000-0000-4000-8000-000000000001',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  '42501','a current two-member direct room is required','a stale membership epoch cannot create a call');
select throws_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000001',pg_temp.call_expiry(),pg_temp.call_envelopes(array[3]))$$,
  '42501','call signal recipient set is not authorized','an envelope cannot route the offer to an outsider');
select throws_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000001',statement_timestamp()+interval '61 seconds',pg_temp.call_envelopes(array[2,4]))$$,
  '22023','call invitation bounds are invalid','ringing cannot exceed sixty seconds');
select is((select state from public.create_private_call(
  '84000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000001',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))),
  'RINGING','create persists an authorized ringing receipt');
select is((select client_mutation_id from public.create_private_call(
  '84000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000001',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))),
  '85000000-0000-4000-8000-000000000001'::uuid,'an exact create retry returns the original mutation receipt');
select throws_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001',1,'VIDEO',
  '85000000-0000-4000-8000-000000000001',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  '23505','call mutation id was already used','create retries cannot substitute media or ciphertext');
select throws_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000002','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000002',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  '23505','a participating device already has an active call','one device cannot start a second active call');
select is(jsonb_array_length(public.poll_private_calls()->'signals'),0,'caller does not receive a self envelope');
select throws_ok($$select * from public.accept_private_call('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000003')$$,'42501','the call cannot be accepted by this device','caller cannot accept its own invitation');

select is((select sequence from public.send_private_call_signal('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000004',1,pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))),1,
  'the caller sends subsequent encrypted candidates in sequence');
select is((select sequence from public.send_private_call_signal('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000004',1,pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))),1,
  'an exact signal retry does not advance the sequence');
select throws_ok($$select * from public.send_private_call_signal('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000005',1,pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  '23505','call signal sequence is not the next sequence','replaying a sequence under another mutation is denied');
select throws_ok($$select * from public.send_private_call_signal('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000006',2,pg_temp.call_expiry(),
  jsonb_set(pg_temp.call_envelopes(array[2,4]),'{0,sdp}','"plaintext is forbidden"'::jsonb))$$,
  '22023','call signal envelope is invalid','plaintext signal fields are rejected');
select throws_ok($$do $capacity$ begin for candidate in 2..130 loop
  perform public.send_private_call_signal('84000000-0000-4000-8000-000000000001',gen_random_uuid(),
    candidate,pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]));
end loop; end $capacity$;$$,'54000','call signal capacity exceeded','pending signal count is bounded atomically');

select pg_temp.call_actor(3);
select is(jsonb_array_length(public.poll_private_calls()->'calls'),0,'an outsider cannot poll call metadata');
select pg_temp.call_actor(2);
select is(jsonb_array_length(public.poll_private_calls()->'signals'),2,'a recipient sees only its encrypted offer and candidate');
select ok((public.poll_private_calls()->'signals'->0->>'ciphertext_hex')=repeat('ab',64),
  'poll ciphertext uses exact lowercase hex with no bytea prefix');
select ok(not(public.poll_private_calls()->'calls'->0 ? 'client_mutation_id'),'poll call records omit mutation receipts');
select is((select accepted_device_id from public.accept_private_call('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000010')),'82000000-0000-4000-8000-000000000002'::uuid,
  'the first recipient device atomically accepts');
select is((select accepted_device_id from public.accept_private_call('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000010')),'82000000-0000-4000-8000-000000000002'::uuid,
  'acceptance retry preserves the winning device');
select pg_temp.call_actor(4);
select throws_ok($$select * from public.accept_private_call('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000011')$$,'42501','the call cannot be accepted by this device',
  'a competing acceptance cannot replace the winner');
select is(jsonb_array_length(public.poll_private_calls()->'calls'),0,'acceptance elsewhere stops ringing on the other device');
select is(jsonb_array_length(public.poll_private_calls()->'signals'),0,'nonwinning recipient receives no active media signaling');
select throws_ok($$select * from public.end_private_call('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000012','ENDED')$$,'42501','call termination is not authorized',
  'a nonwinning device cannot hang up an active call');
select pg_temp.call_actor(2);
select is((select sequence from public.send_private_call_signal('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000013',0,pg_temp.call_expiry(),pg_temp.call_envelopes(array[1]))),0,
  'the accepted device has its own answer sequence starting at zero');
select throws_ok($$select * from public.send_private_call_signal('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000014',1,pg_temp.call_expiry(),pg_temp.call_envelopes(array[4]))$$,
  '42501','call signal recipient set is not authorized','an active answer cannot be forwarded to another device');
select pg_temp.call_actor(1);
select ok((select lease_expires_at<=statement_timestamp()+interval '45 seconds'
  and client_mutation_id is null from public.heartbeat_private_call('84000000-0000-4000-8000-000000000001')),
  'heartbeat refresh remains bounded and does not fabricate a mutation receipt');
select is((select state from public.end_private_call('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000015','ENDED')),'ENDED','hangup confirms terminal state');
select is((select state from public.end_private_call('84000000-0000-4000-8000-000000000001',
  '85000000-0000-4000-8000-000000000015','ENDED')),'ENDED','hangup retry is idempotent');
reset role;
select is((select count(*) from private.call_signal_envelopes where call_id='84000000-0000-4000-8000-000000000001'),
  0::bigint,'hangup physically removes all ciphertext');
select is((select count(*) from private.call_device_leases where call_id='84000000-0000-4000-8000-000000000001'),
  0::bigint,'hangup releases all device reservations');

set local role authenticated;
select pg_temp.call_actor(1);
select lives_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000002','83000000-0000-4000-8000-000000000001',1,'VIDEO',
  '85000000-0000-4000-8000-000000000020',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  'a completed call does not block the next call');
reset role;
insert into private.call_sessions(id,room_id,membership_epoch,caller_user_id,caller_device_id,
  caller_auth_session_id,recipient_user_id,media_kind,state,terminal_reason,ring_expires_at,caller_lease_expires_at,ended_at)
select ('87000000-0000-4000-8000-'||lpad(terminal_number::text,12,'0'))::uuid,
  '83000000-0000-4000-8000-000000000001',1,'81000000-0000-4000-8000-000000000001',
  '82000000-0000-4000-8000-000000000001','86000000-0000-4000-8000-000000000001',
  '81000000-0000-4000-8000-000000000002','VOICE','ENDED','ENDED',
  statement_timestamp()+interval '30 seconds',statement_timestamp()+interval '30 seconds',statement_timestamp()
from generate_series(1,21) terminal_number;
insert into private.call_devices(call_id,device_id)
select ('87000000-0000-4000-8000-'||lpad(terminal_number::text,12,'0'))::uuid,
  '82000000-0000-4000-8000-000000000002' from generate_series(1,21) terminal_number;
set local role authenticated;
select pg_temp.call_actor(2);
select is(jsonb_array_length(public.poll_private_calls()->'calls'),20,'recent terminal metadata cannot overflow the bounded poll contract');
select ok(exists(select 1 from jsonb_array_elements(public.poll_private_calls()->'calls') call
  where call->>'call_id'='84000000-0000-4000-8000-000000000002' and call->>'state'='RINGING'),
  'a ringing call is never displaced by terminal metadata');
reset role;
delete from private.call_sessions where id::text like '87000000-0000-4000-8000-%';
update private.call_sessions set created_at=statement_timestamp()-interval '70 seconds',
  ring_expires_at=statement_timestamp()-interval '10 seconds',caller_lease_expires_at=statement_timestamp()-interval '10 seconds'
  where id='84000000-0000-4000-8000-000000000002';
set local role authenticated;
select pg_temp.call_actor(2);
select ok(exists(select 1 from jsonb_array_elements(public.poll_private_calls()->'calls') call
  where call->>'call_id'='84000000-0000-4000-8000-000000000002' and call->>'terminal_reason'='TIMEOUT'),
  'expired ringing becomes a terminal timeout before any signal is delivered');
select throws_ok($$select * from public.accept_private_call('84000000-0000-4000-8000-000000000002',
  '85000000-0000-4000-8000-000000000021')$$,'42501','the call cannot be accepted by this device','expired offers cannot be accepted');
select pg_temp.call_actor(1);
select lives_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000003','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000030',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  'expired ringing releases the devices');
select pg_temp.call_actor(2);
select lives_ok($$select * from public.accept_private_call('84000000-0000-4000-8000-000000000003',
  '85000000-0000-4000-8000-000000000031')$$,'second active call accepted');
reset role;
update private.call_sessions set caller_lease_expires_at=statement_timestamp()-interval '1 second'
  where id='84000000-0000-4000-8000-000000000003';
set local role authenticated;
select throws_ok($$select * from public.heartbeat_private_call('84000000-0000-4000-8000-000000000003')$$,
  '42501','call heartbeat is not authorized','an expired heartbeat cannot resurrect capture authority');
select ok(exists(select 1 from jsonb_array_elements(public.poll_private_calls()->'calls') call
  where call->>'call_id'='84000000-0000-4000-8000-000000000003' and call->>'terminal_reason'='TIMEOUT'),
  'either missing participant heartbeat terminates the call');

select pg_temp.call_actor(1);
select lives_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000004','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000040',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  'a subsequent call starts before authorization revocation');
reset role;
delete from private.device_sessions where session_id='86000000-0000-4000-8000-000000000001';
set local role authenticated;
select pg_temp.call_actor(2);
select ok(exists(select 1 from jsonb_array_elements(public.poll_private_calls()->'calls') call
  where call->>'call_id'='84000000-0000-4000-8000-000000000004' and call->>'terminal_reason'='ACCESS_REVOKED'),
  'removing the device binding terminates a call even while its Auth session exists');
reset role;
insert into private.device_sessions(session_id,device_id)
  values('86000000-0000-4000-8000-000000000001','82000000-0000-4000-8000-000000000001');
set local role authenticated;
select pg_temp.call_actor(1);
select lives_ok($$select * from public.create_private_call(
  '84000000-0000-4000-8000-000000000005','83000000-0000-4000-8000-000000000001',1,'VOICE',
  '85000000-0000-4000-8000-000000000050',pg_temp.call_expiry(),pg_temp.call_envelopes(array[2,4]))$$,
  'a properly rebound device can start a new call');
reset role;
delete from auth.sessions where id='86000000-0000-4000-8000-000000000001';
set local role authenticated;
select throws_ok($$select public.poll_private_calls()$$,'42501','an active device session is required',
  'a deleted authoritative auth session cannot poll with its old JWT');
select pg_temp.call_actor(2);
select ok(exists(select 1 from jsonb_array_elements(public.poll_private_calls()->'calls') call
  where call->>'call_id'='84000000-0000-4000-8000-000000000005' and call->>'terminal_reason'='ACCESS_REVOKED'),
  'peer session revocation immediately terminates signaling');
reset role;
update public.rooms set membership_epoch=2 where id='83000000-0000-4000-8000-000000000001';
set local role authenticated;
select is(jsonb_array_length(public.poll_private_calls()->'calls'),0,'a changed room epoch hides all old call context');
reset role;
update public.devices set revoked_at=statement_timestamp() where id='82000000-0000-4000-8000-000000000002';
set local role authenticated;
select throws_ok($$select public.poll_private_calls()$$,'42501','an active device session is required',
  'device revocation independently closes the call boundary');
reset role;
update private.call_sessions set ended_at=statement_timestamp()-interval '121 seconds' where state='ENDED';
select is(private.purge_expired_private_calls(),5,'terminal call records are physically purged after the bounded window');
select is((select count(*) from private.call_mutation_receipts),0::bigint,'purge cascades all call mutation receipts');
select * from finish();
rollback;
