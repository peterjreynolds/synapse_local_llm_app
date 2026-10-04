begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

insert into auth.users (
  id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at
) values
  ('71000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated',
   '71000000-0000-4000-8000-000000000001@identity.synapse-private.invalid', '', now(),
   '{"synapse_private_registration_authority":true}', '{}', now(), now()),
  ('71000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated',
   '71000000-0000-4000-8000-000000000002@identity.synapse-private.invalid', '', now(),
   '{"synapse_private_registration_authority":true}', '{}', now(), now());
insert into public.profiles(user_id, display_name)
values ('71000000-0000-4000-8000-000000000001', 'Account A');
insert into private.account_registration_invites(issued_by_user_id, code_digest, expires_at)
values ('71000000-0000-4000-8000-000000000001', decode(repeat('71',32),'hex'), now() + interval '1 hour');
insert into auth.sessions(id, user_id, created_at, updated_at)
values ('72000000-0000-4000-8000-000000000002', '71000000-0000-4000-8000-000000000002', now(), now());
insert into public.devices (
  id, user_id, protocol_adapter_version, registration_id, signal_device_id,
  identity_key, signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
  kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature
) values (
  '73000000-0000-4000-8000-000000000001', '71000000-0000-4000-8000-000000000001', 1, 1, 1,
  decode('05' || repeat('11',32),'hex'), 1, decode('05' || repeat('22',32),'hex'), decode(repeat('33',64),'hex'),
  1, decode('08' || repeat('44',1568),'hex'), decode(repeat('55',64),'hex')
);

select lives_ok($$select * from private.redeem_account_registration(
  decode(repeat('71',32),'hex'), '74000000-0000-4000-8000-000000000002', decode(repeat('72',32),'hex'),
  '71000000-0000-4000-8000-000000000002@identity.synapse-private.invalid',
  '71000000-0000-4000-8000-000000000002', 'Account B'
)$$, 'account registration commits before device binding');
select throws_ok($$select * from private.reserve_device_registration(
  '71000000-0000-4000-8000-000000000002', '72000000-0000-4000-8000-000000000002',
  '73000000-0000-4000-8000-000000000001'
)$$, '42501', 'device registration is not authorized', 'old account device remains denied after partial registration');
select is((select registration_state from private.inspect_account_registration(
  decode(repeat('71',32),'hex'), '74000000-0000-4000-8000-000000000002'
)), 'REDEEMED', 'partial registration retains its recovery receipt');
select lives_ok($$select * from private.redeem_account_registration(
  decode(repeat('71',32),'hex'), '74000000-0000-4000-8000-000000000002', decode(repeat('72',32),'hex'),
  '71000000-0000-4000-8000-000000000002@identity.synapse-private.invalid',
  '71000000-0000-4000-8000-000000000002', 'Account B'
)$$, 'original redemption identity replays the committed account');
select is((select count(*)::int from private.account_registration_receipts), 1, 'retry creates no second registration');
select lives_ok($$select * from private.reserve_device_registration(
  '71000000-0000-4000-8000-000000000002', '72000000-0000-4000-8000-000000000002',
  '73000000-0000-4000-8000-000000000002'
)$$, 'recovery reserves a fresh transport UUID for account B');
select is((select user_id from public.devices where id = '73000000-0000-4000-8000-000000000001'),
  '71000000-0000-4000-8000-000000000001'::uuid, 'recovery never transfers account A device');
select * from finish();
rollback;
