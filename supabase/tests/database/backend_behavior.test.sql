begin;
create extension if not exists pgtap with schema extensions;
select plan(26);

insert into auth.users (
  id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at
) values
  ('10000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated',
   '10000000-0000-4000-8000-000000000001@identity.synapse-private.invalid', '', now(),
   '{"provider":"email","providers":["email"],"synapse_private_registration_authority":true}', '{}', now(), now()),
  ('10000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated',
   '10000000-0000-4000-8000-000000000002@identity.synapse-private.invalid', '', now(),
   '{"provider":"email","providers":["email"],"synapse_private_registration_authority":true}', '{}', now(), now());

insert into public.profiles (user_id, display_name)
values
  ('10000000-0000-4000-8000-000000000001', 'Owner'),
  ('10000000-0000-4000-8000-000000000002', 'Member');

insert into auth.sessions (id, user_id, created_at, updated_at)
values
  ('60000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', now(), now()),
  ('60000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', now(), now()),
  ('60000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', now(), now());

insert into public.devices (
  id, user_id, protocol_adapter_version, registration_id, signal_device_id,
  identity_key, signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
  kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature
) values (
  '20000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  1, 1, 1,
  decode('05' || repeat('11', 32), 'hex'),
  1, decode('05' || repeat('22', 32), 'hex'), decode(repeat('33', 64), 'hex'),
  1, decode('08' || repeat('44', 1568), 'hex'), decode(repeat('55', 64), 'hex')
);

insert into public.rooms (id, owner_user_id, room_kind, retention_seconds)
values
  ('30000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'GROUP', 300),
  ('30000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', 'GROUP', 3600);
insert into public.room_members (room_id, user_id, member_role)
values
  ('30000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'OWNER'),
  ('30000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', 'OWNER');

insert into public.messages (
  id, room_id, sender_user_id, client_message_id, membership_epoch, expires_at
) values
  ('40000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001',
   '10000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000001', 99, now() + interval '7 days'),
  ('40000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000002',
   '10000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000002', 99, now() + interval '7 days'),
  ('40000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000001',
   '10000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000003', 99, now() + interval '7 days');

select is(
  (select membership_epoch from public.messages where id = '40000000-0000-4000-8000-000000000001'),
  1,
  'message membership epoch is server-owned'
);
select ok(
  (select expires_at <= created_at + interval '301 seconds'
   from public.messages where id = '40000000-0000-4000-8000-000000000001'),
  'message expiry is derived from the room retention period'
);
select lives_ok(
  $$insert into public.message_reply_links (message_id, replied_to_message_id)
    values ('40000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000003')$$,
  'same-room replies are accepted'
);
select throws_ok(
  $$insert into public.message_reply_links (message_id, replied_to_message_id)
    values ('40000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000002')$$,
  '23514',
  'reply links require unexpired messages in the same room',
  'cross-room replies fail closed'
);
select throws_ok(
  $$delete from public.profiles where user_id = '10000000-0000-4000-8000-000000000001'$$,
  '23503',
  null,
  'deleting a group owner cannot cascade the room'
);
select throws_ok(
  $$insert into public.typing_state (room_id, device_id, membership_epoch, expires_at)
    values ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 99, now() + interval '1 hour')$$,
  '42501',
  'typing indicators are not enabled',
  'typing is denied before explicit opt-in'
);

update public.profiles
set typing_indicators_enabled = true, presence_sharing_enabled = true
where user_id = '10000000-0000-4000-8000-000000000001';
insert into public.typing_state (room_id, device_id, membership_epoch, expires_at)
values ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 99, now() + interval '1 hour');
insert into public.presence_state (device_id, expires_at)
values ('20000000-0000-4000-8000-000000000001', now() + interval '1 hour');

select is(
  (select membership_epoch from public.typing_state where device_id = '20000000-0000-4000-8000-000000000001'),
  1,
  'typing membership epoch is server-owned'
);
select ok(
  (select expires_at <= created_at + interval '16 seconds'
   from public.typing_state where device_id = '20000000-0000-4000-8000-000000000001'),
  'typing expires after 15 seconds'
);
select ok(
  (select expires_at <= created_at + interval '61 seconds'
   from public.presence_state where device_id = '20000000-0000-4000-8000-000000000001'),
  'presence expires after 60 seconds'
);

select is(
  (
    select signal_device_id
    from private.reserve_device_registration(
      '10000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '20000000-0000-4000-8000-000000000002'
    )
  ),
  2::smallint,
  'phase one reserves the lowest collision-free Signal device id'
);
select is(
  (
    select signal_device_id
    from private.reserve_device_registration(
      '10000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '20000000-0000-4000-8000-000000000002'
    )
  ),
  2::smallint,
  'reservation retry is idempotent for the same auth session and transport device'
);
select is(
  (select count(*)::integer from private.device_registration_reservations
   where auth_session_id = '60000000-0000-4000-8000-000000000001'),
  1,
  'reservation retry persists one receipt'
);
select throws_ok(
  $$select * from private.reserve_device_registration(
      '10000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '20000000-0000-4000-8000-000000000099'
    )$$,
  '23505',
  'the auth session already reserved another device',
  'an auth session cannot switch transport devices after reservation'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"10000000-0000-4000-8000-000000000001","session_id":"60000000-0000-4000-8000-000000000001","role":"authenticated"}',
  true
);
select is(
  private.current_device_id(),
  null::uuid,
  'phase-one authentication cannot read app data before bundle binding'
);

select is(
  (
    select signal_device_id
    from private.register_device_session(
      '10000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '20000000-0000-4000-8000-000000000002',
      1, 2, 2,
      decode('05' || repeat('61', 32), 'hex'),
      2, decode('05' || repeat('62', 32), 'hex'), decode(repeat('63', 64), 'hex'),
      2, decode('08' || repeat('64', 1568), 'hex'), decode(repeat('65', 64), 'hex'),
      7, decode('05' || repeat('66', 32), 'hex')
    )
  ),
  2::smallint,
  'register-device consumes the matching allocation and returns its Signal id'
);
select is(
  private.current_device_id(),
  '20000000-0000-4000-8000-000000000002'::uuid,
  'bundle binding opens app-data RLS for the exact auth session'
);
select is(
  (select display_name from private.register_device_session(
    '10000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000002',
    1, 2, 2,
    decode('05' || repeat('61', 32), 'hex'),
    2, decode('05' || repeat('62', 32), 'hex'), decode(repeat('63', 64), 'hex'),
    2, decode('08' || repeat('64', 1568), 'hex'), decode(repeat('65', 64), 'hex'),
    7, decode('05' || repeat('66', 32), 'hex')
  )),
  'Owner',
  'idempotent registration returns the pseudonymous display name'
);

delete from public.device_one_time_prekeys
where device_id = '20000000-0000-4000-8000-000000000002' and pre_key_id = 7;
select lives_ok(
  $$select * from private.register_device_session(
    '10000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000002',
    1, 2, 2,
    decode('05' || repeat('61', 32), 'hex'),
    2, decode('05' || repeat('62', 32), 'hex'), decode(repeat('63', 64), 'hex'),
    2, decode('08' || repeat('64', 1568), 'hex'), decode(repeat('65', 64), 'hex'),
    7, decode('05' || repeat('66', 32), 'hex')
  )$$,
  'registration retry republishes a one-time prekey after the previous key was claimed'
);
select throws_ok(
  $$select * from private.register_device_session(
    '10000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000002',
    1, 2, 2,
    decode('05' || repeat('61', 32), 'hex'),
    2, decode('05' || repeat('62', 32), 'hex'), decode(repeat('63', 64), 'hex'),
    2, decode('08' || repeat('64', 1568), 'hex'), decode(repeat('65', 64), 'hex'),
    7, decode('05' || repeat('67', 32), 'hex')
  )$$,
  '23505',
  'one-time prekey identity does not match the registered device',
  'registration retry rejects one-time prekey id substitution'
);

select lives_ok(
  $$select * from private.reserve_device_registration(
    '10000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000002',
    '20000000-0000-4000-8000-000000000002'
  )$$,
  'a new auth session can reserve the already-registered transport device'
);
select lives_ok(
  $$select * from private.reserve_device_registration(
    '10000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000003',
    '20000000-0000-4000-8000-000000000003'
  )$$,
  'a different transport device receives a distinct pending reservation'
);
update private.device_registration_reservations
set reserved_at = statement_timestamp() - interval '16 minutes',
    expires_at = statement_timestamp() - interval '1 minute'
where auth_session_id = '60000000-0000-4000-8000-000000000003';
select throws_ok(
  $$select * from private.register_device_session(
    '10000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000003',
    '20000000-0000-4000-8000-000000000003',
    1, 3, 3,
    decode('05' || repeat('71', 32), 'hex'),
    3, decode('05' || repeat('72', 32), 'hex'), decode(repeat('73', 64), 'hex'),
    3, decode('08' || repeat('74', 1568), 'hex'), decode(repeat('75', 64), 'hex'),
    8, decode('05' || repeat('76', 32), 'hex')
  )$$,
  '42501',
  'an active device registration reservation is required',
  'register-device fails closed after reservation expiry'
);
select is(
  (
    select device_reservations_deleted
    from private.purge_expired_relational_data(
      500,
      '70000000-0000-4000-8000-000000000001'
    )
  ),
  1,
  'retention purges expired device reservations with a durable count'
);

select is(
  (select revoked_device_id from private.revoke_device('20000000-0000-4000-8000-000000000002')),
  '20000000-0000-4000-8000-000000000002'::uuid,
  'explicit device revocation succeeds from an active bound device session'
);
select is(
  (select count(*)::integer from private.device_registration_reservations
   where device_id = '20000000-0000-4000-8000-000000000002'),
  0,
  'explicit revocation removes outstanding registrations for that device'
);
select throws_ok(
  $$select * from private.reserve_device_registration(
    '10000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000002',
    '20000000-0000-4000-8000-000000000002'
  )$$,
  '42501',
  'device registration is not authorized',
  'a revoked transport device cannot reserve or rebind'
);

select * from finish();
rollback;
