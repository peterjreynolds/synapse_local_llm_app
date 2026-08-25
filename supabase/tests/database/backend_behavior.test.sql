begin;
create extension if not exists pgtap with schema extensions;
select plan(88);

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
  ('60000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', now(), now()),
  ('60000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000002', now(), now());

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
), (
  '20000000-0000-4000-8000-000000000004',
  '10000000-0000-4000-8000-000000000002',
  1, 4, 1,
  decode('05' || repeat('21', 32), 'hex'),
  4, decode('05' || repeat('22', 32), 'hex'), decode(repeat('23', 64), 'hex'),
  4, decode('08' || repeat('24', 1568), 'hex'), decode(repeat('25', 64), 'hex')
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

update private.runtime_configuration
set invite_derivation_key = decode(repeat('ab', 32), 'hex')
where singleton;
select is(
  encode(
    private.derive_invite_code_digest(
      '018f1d9e-7b2a-7000-8000-000000000001',
      'ACCOUNT_REGISTRATION',
      null,
      '018f1d9e-7b2a-7000-8000-000000000001'
    ),
    'hex'
  ),
  '2a38609366204c29365497b3a099cf8b396a3046babc53a734afe3d9fbd03221',
  'database and Edge invitation derivation share an exact protocol vector'
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
      1::smallint, 2, 2::smallint,
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
select ok(
  not private.can_access_message('40000000-0000-4000-8000-000000000001'),
  'a newly registered device cannot observe history without its own envelope'
);
select is(
  (select display_name from private.register_device_session(
    '10000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000002',
    1::smallint, 2, 2::smallint,
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
    1::smallint, 2, 2::smallint,
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
    1::smallint, 2, 2::smallint,
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
    1::smallint, 3, 3::smallint,
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
  (select count(*)::integer from private.list_current_account_recipient_devices()),
  2,
  'pre-room recipient enumeration returns every active device on the current account'
);

select is(
  (
    select metadata_revision
    from private.create_room_with_metadata(
      'GROUP',
      300,
      '80000000-0000-4000-8000-000000000001',
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'PREKEY',
          'ciphertext_hex', repeat('81', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('82', 29)
        )
      )
    )
  ),
  1,
  'room creation atomically persists encrypted metadata revision one'
);
select is(
  (
    select room_id
    from private.create_room_with_metadata(
      'GROUP',
      300,
      '80000000-0000-4000-8000-000000000001',
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'PREKEY',
          'ciphertext_hex', repeat('81', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('82', 29)
        )
      )
    )
  ),
  (
    select room_id
    from private.room_creation_mutation_receipts
    where client_mutation_id = '80000000-0000-4000-8000-000000000001'
  ),
  'room creation retry returns the durable original receipt'
);
select ok(
  (
    select expires_at = metadata_updated_at + interval '24 hours'
    from private.room_creation_mutation_receipts
    where client_mutation_id = '80000000-0000-4000-8000-000000000001'
  ),
  'room creation keeps only a bounded 24-hour non-content retry receipt'
);
select throws_ok(
  $$select * from private.create_room_with_metadata(
      'GROUP',
      300,
      '80000000-0000-4000-8000-000000000001',
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'PREKEY',
          'ciphertext_hex', repeat('83', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('82', 29)
        )
      )
    )$$,
  '23505',
  'room creation mutation id was already used',
  'room creation rejects mutation-id ciphertext substitution'
);

select is(
  (
    select metadata_revision
    from private.set_room_metadata(
      '30000000-0000-4000-8000-000000000001',
      '80000000-0000-4000-8000-000000000002',
      0,
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'WHISPER',
          'ciphertext_hex', repeat('84', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('85', 29)
        )
      )
    )
  ),
  1,
  'room metadata advances through an encrypted complete-device mutation'
);
select ok(
  (
    select count(*) = 2
      and count(*) filter (where signal_message_type = 'LOCAL_AEAD') = 1
    from public.room_metadata_envelopes
    where room_id = '30000000-0000-4000-8000-000000000001'
  ),
  'room metadata retains one current envelope per active device including self'
);
select is(
  (
    select count(*)::integer
    from private.list_room_recipient_devices('30000000-0000-4000-8000-000000000001')
  ),
  2,
  'recipient enumeration contains active peer and self devices only'
);

select lives_ok(
  $$select * from private.send_message(
      '30000000-0000-4000-8000-000000000001',
      '80000000-0000-4000-8000-000000000003',
      null,
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'PREKEY',
          'ciphertext_hex', repeat('86', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('87', 29)
        )
      )
    )$$,
  'message send accepts exact peer and durable self envelopes'
);
select is(
  (
    select signal_message_type
    from public.message_envelopes as envelope
    join public.messages as message on message.id = envelope.message_id
    where message.client_message_id = '80000000-0000-4000-8000-000000000003'
      and envelope.recipient_device_id = '20000000-0000-4000-8000-000000000002'
  ),
  'LOCAL_AEAD',
  'the sender device receives restart-safe encrypted content'
);
select lives_ok(
  $$select * from private.send_message(
      '30000000-0000-4000-8000-000000000001',
      '80000000-0000-4000-8000-000000000003',
      null,
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'PREKEY',
          'ciphertext_hex', repeat('86', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('87', 29)
        )
      )
    )$$,
  'identical message retry returns the persisted request-digest receipt'
);
select throws_ok(
  $$select * from private.send_message(
      '30000000-0000-4000-8000-000000000001',
      '80000000-0000-4000-8000-000000000003',
      null,
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'PREKEY',
          'ciphertext_hex', repeat('88', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('87', 29)
        )
      )
    )$$,
  '23505',
  'message mutation id was already used',
  'message retry rejects changed ciphertext under the same mutation id'
);

select is(
  (
    select revision_number
    from private.edit_message(
      (select id from public.messages where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '80000000-0000-4000-8000-000000000004',
      0,
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'WHISPER',
          'ciphertext_hex', repeat('91', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('92', 29)
        )
      )
    )
  ),
  1,
  'message edit creates current encrypted revision one'
);
select is(
  (
    select count(*)::integer
    from public.message_envelopes as envelope
    join public.messages as message on message.id = envelope.message_id
    where message.client_message_id = '80000000-0000-4000-8000-000000000003'
  ),
  0,
  'first edit physically removes the original ciphertext envelopes'
);
select is(
  (
    select count(*)::integer
    from public.message_revision_envelopes as envelope
    join public.message_revisions as revision on revision.id = envelope.revision_id
    join public.messages as message on message.id = revision.message_id
    where message.client_message_id = '80000000-0000-4000-8000-000000000003'
  ),
  2,
  'current edit contains exactly one envelope for each active device'
);
select is(
  (
    select revision_number
    from private.edit_message(
      (select id from public.messages where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '80000000-0000-4000-8000-000000000005',
      1,
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'WHISPER',
          'ciphertext_hex', repeat('93', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('94', 29)
        )
      )
    )
  ),
  2,
  'second edit advances the authoritative message revision'
);
select ok(
  (
    select count(*) = 2
      and bool_and(ciphertext in (decode(repeat('93', 32), 'hex'), decode(repeat('94', 29), 'hex')))
    from public.message_revision_envelopes as envelope
    join public.message_revisions as revision on revision.id = envelope.revision_id
    join public.messages as message on message.id = revision.message_id
    where message.client_message_id = '80000000-0000-4000-8000-000000000003'
  ),
  'confirmed edit leaves no prior revision ciphertext recoverable'
);
select throws_ok(
  $$select * from private.edit_message(
      (select id from public.messages where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '80000000-0000-4000-8000-000000000005',
      1,
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'WHISPER',
          'ciphertext_hex', repeat('95', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('94', 29)
        )
      )
    )$$,
  '23505',
  'message edit mutation id was already used',
  'edit retry rejects mutation-id ciphertext substitution'
);

select lives_ok(
  $$select * from private.send_reaction(
      (select id from public.messages where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '80000000-0000-4000-8000-000000000006',
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'WHISPER',
          'ciphertext_hex', repeat('96', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('97', 29)
        )
      )
    )$$,
  'reaction send persists exact peer and durable self envelopes'
);
select throws_ok(
  $$select * from private.send_reaction(
      (select id from public.messages where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '80000000-0000-4000-8000-000000000006',
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'WHISPER',
          'ciphertext_hex', repeat('98', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('97', 29)
        )
      )
    )$$,
  '23505',
  'reaction mutation id was already used',
  'reaction retry rejects changed ciphertext under the same mutation id'
);
select lives_ok(
  $$select * from private.remove_reaction(
      (select id from public.reactions where client_reaction_id = '80000000-0000-4000-8000-000000000006'),
      '80000000-0000-4000-8000-000000000007'
    )$$,
  'reaction removal hard-deletes through an idempotent mutation'
);
select is(
  (select count(*)::integer from public.reactions where client_reaction_id = '80000000-0000-4000-8000-000000000006'),
  0,
  'reaction removal leaves no ciphertext or reaction row'
);
select ok(
  (
    select expires_at = removed_at + interval '24 hours'
    from private.reaction_removal_receipts
    where client_mutation_id = '80000000-0000-4000-8000-000000000007'
  ),
  'reaction removal preserves a full 24-hour non-content retry receipt'
);
select lives_ok(
  $$select * from private.remove_reaction(
      (select reaction_id from private.reaction_removal_receipts
       where client_mutation_id = '80000000-0000-4000-8000-000000000007'),
      '80000000-0000-4000-8000-000000000007'
    )$$,
  'reaction removal retry returns its bounded receipt'
);
select lives_ok(
  $$select * from private.send_reaction(
      (select id from public.messages
       where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '80000000-0000-4000-8000-000000000010',
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'WHISPER',
          'ciphertext_hex', repeat('9a', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('9b', 29)
        )
      )
    )$$,
  'pre-join reaction fixture has complete encrypted fan-out for existing devices'
);

select is(
  (select archive_state from private.set_room_preferences(
    '30000000-0000-4000-8000-000000000001',
    '80000000-0000-4000-8000-000000000008',
    'ARCHIVED',
    'PINNED',
    'MUTED_FOREVER',
    null
  )),
  'ARCHIVED',
  'one preference mutation atomically persists archive, pin, and mute state'
);
select ok(
  (
    select archive_state = 'ARCHIVED'
      and pin_state = 'PINNED'
      and mute_state = 'MUTED_FOREVER'
      and muted_until is null
    from public.room_member_preferences
    where room_id = '30000000-0000-4000-8000-000000000001'
      and user_id = '10000000-0000-4000-8000-000000000001'
  ),
  'room preferences remain private member-owned durable state'
);
select is(
  (select pin_state from private.set_room_preferences(
    '30000000-0000-4000-8000-000000000001',
    '80000000-0000-4000-8000-000000000008',
    'ARCHIVED',
    'PINNED',
    'MUTED_FOREVER',
    null
  )),
  'PINNED',
  'preference retry returns the original bounded receipt'
);
select throws_ok(
  $$select * from private.set_room_preferences(
      '30000000-0000-4000-8000-000000000001',
      '80000000-0000-4000-8000-000000000008',
      'ACTIVE',
      'PINNED',
      'MUTED_FOREVER',
      null
    )$$,
  '23505',
  'room preference mutation id was already used',
  'preference retry rejects changed aggregate state under the same mutation id'
);
select throws_ok(
  $$select * from private.set_room_preferences(
      '30000000-0000-4000-8000-000000000001',
      '80000000-0000-4000-8000-000000000009',
      'ACTIVE',
      'UNPINNED',
      'MUTED_UNTIL',
      null
    )$$,
  '22023',
  'room preference mutation is invalid',
  'timed mute requires an explicit future expiry'
);

insert into private.device_sessions (session_id, device_id)
values (
  '60000000-0000-4000-8000-000000000004',
  '20000000-0000-4000-8000-000000000004'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"10000000-0000-4000-8000-000000000002","session_id":"60000000-0000-4000-8000-000000000004","role":"authenticated"}',
  true
);
select ok(
  not private.can_access_message(
    (select id from public.messages
     where client_message_id = '80000000-0000-4000-8000-000000000003')
  ),
  'a non-member cannot observe pre-join message history'
);

insert into public.room_members (room_id, user_id, member_role)
values (
  '30000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000002',
  'MEMBER'
);
select ok(
  not private.can_access_message('40000000-0000-4000-8000-000000000001'),
  'joining a room does not reveal an initial message without a recipient envelope'
);
select ok(
  not private.can_access_message(
    (select id from public.messages
     where client_message_id = '80000000-0000-4000-8000-000000000003')
  ),
  'joining a room does not reveal a current edited revision without a recipient envelope'
);
select ok(
  not exists (
    select 1
    from public.message_reply_links as reply_link
    where private.can_access_message(reply_link.message_id)
      and private.can_access_message(reply_link.replied_to_message_id)
  ),
  'pre-join reply links remain outside the joined device access graph'
);
select ok(
  not exists (
    select 1
    from public.reactions as reaction
    where reaction.client_reaction_id = '80000000-0000-4000-8000-000000000010'
      and private.can_access_message(reaction.message_id)
  ),
  'pre-join reactions remain outside the joined device access graph'
);
select ok(
  not exists (
    select 1
    from public.message_revisions as revision
    where private.can_access_message(revision.message_id)
  ),
  'pre-join message revisions remain outside the joined device access graph'
);
select throws_ok(
  $$select * from private.send_message(
      '30000000-0000-4000-8000-000000000001',
      '84000000-0000-4000-8000-000000000001',
      (select id from public.messages
       where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '[]'::jsonb
    )$$,
  '22023',
  'reply target is unavailable',
  'a newly joined device cannot reply to guessed pre-join history'
);
select throws_ok(
  $$select * from private.send_reaction(
      (select id from public.messages
       where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '84000000-0000-4000-8000-000000000002',
      '[]'::jsonb
    )$$,
  '42501',
  'message reaction is not authorized',
  'a newly joined device cannot react to guessed pre-join history'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"10000000-0000-4000-8000-000000000001","session_id":"60000000-0000-4000-8000-000000000001","role":"authenticated"}',
  true
);
select lives_ok(
  $$select * from private.send_message(
      '30000000-0000-4000-8000-000000000001',
      '83000000-0000-4000-8000-000000000001',
      null,
      jsonb_build_array(
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000001',
          'protocol_adapter_version', 1,
          'signal_message_type', 'WHISPER',
          'ciphertext_hex', repeat('9c', 32)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000002',
          'protocol_adapter_version', 1,
          'signal_message_type', 'LOCAL_AEAD',
          'ciphertext_hex', repeat('9d', 29)
        ),
        jsonb_build_object(
          'recipient_device_id', '20000000-0000-4000-8000-000000000004',
          'protocol_adapter_version', 1,
          'signal_message_type', 'PREKEY',
          'ciphertext_hex', repeat('9e', 32)
        )
      )
    )$$,
  'post-join message mutation includes the newly joined device'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"10000000-0000-4000-8000-000000000002","session_id":"60000000-0000-4000-8000-000000000004","role":"authenticated"}',
  true
);
select ok(
  private.can_access_message(
    (select id from public.messages
     where client_message_id = '83000000-0000-4000-8000-000000000001')
  ),
  'a joined device can read post-join content carrying its exact envelope'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"10000000-0000-4000-8000-000000000001","session_id":"60000000-0000-4000-8000-000000000001","role":"authenticated"}',
  true
);

select is(
  (select retention_seconds from private.update_room_retention(
    '30000000-0000-4000-8000-000000000001',
    '82000000-0000-4000-8000-000000000001',
    3600
  )),
  3600,
  'retention mutation persists an explicit idempotency receipt'
);
select lives_ok(
  $$select * from private.update_room_retention(
      '30000000-0000-4000-8000-000000000001',
      '82000000-0000-4000-8000-000000000001',
      3600
    )$$,
  'identical retention retry returns the original receipt'
);
select throws_ok(
  $$select * from private.update_room_retention(
      '30000000-0000-4000-8000-000000000001',
      '82000000-0000-4000-8000-000000000001',
      300
    )$$,
  '23505',
  'room retention mutation id was already used',
  'retention retry rejects changed input'
);

select is(
  (select member_role from private.update_room_member_role(
    '30000000-0000-4000-8000-000000000001',
    '82000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000002',
    'ADMIN'
  )),
  'ADMIN',
  'member role mutation persists a bounded request receipt'
);
select is(
  (select new_membership_epoch from private.update_room_member_role(
    '30000000-0000-4000-8000-000000000001',
    '82000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000002',
    'ADMIN'
  )),
  2,
  'member role retry does not advance membership twice'
);
select throws_ok(
  $$select * from private.update_room_member_role(
      '30000000-0000-4000-8000-000000000001',
      '82000000-0000-4000-8000-000000000002',
      '10000000-0000-4000-8000-000000000002',
      'MEMBER'
    )$$,
  '23505',
  'member role mutation id was already used',
  'member role retry rejects changed input'
);

select is(
  (select removed_user_id from private.remove_room_member(
    '30000000-0000-4000-8000-000000000001',
    '82000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000002'
  )),
  '10000000-0000-4000-8000-000000000002'::uuid,
  'member removal returns a durable mutation receipt'
);
select is(
  (select new_membership_epoch from private.remove_room_member(
    '30000000-0000-4000-8000-000000000001',
    '82000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000002'
  )),
  3,
  'member removal retry succeeds after the member row is gone'
);

select throws_ok(
  $$select * from private.delete_message_for_everyone(
      (select id from public.messages
       where client_message_id = '80000000-0000-4000-8000-000000000003'),
      '82000000-0000-4000-8000-000000000009',
      1
    )$$,
  '40001',
  'message revision changed',
  'delete-for-everyone rejects a stale expected revision atomically'
);
select is(
  (select deletion_state from private.delete_message_for_everyone(
    (select id from public.messages where client_message_id = '80000000-0000-4000-8000-000000000003'),
    '82000000-0000-4000-8000-000000000004',
    2
  )),
  'DELETED',
  'delete-for-everyone hard-deletes content and persists a non-content receipt'
);
select is(
  (select deletion_state from private.delete_message_for_everyone(
    (select message_id from private.message_deletion_mutation_receipts
     where client_mutation_id = '82000000-0000-4000-8000-000000000004'),
    '82000000-0000-4000-8000-000000000004',
    2
  )),
  'DELETED',
  'delete retry succeeds after the message row is physically absent'
);
select throws_ok(
  $$select * from private.delete_message_for_everyone(
      '40000000-0000-4000-8000-000000000001',
      '82000000-0000-4000-8000-000000000004',
      2
    )$$,
  '23505',
  'message deletion mutation id was already used',
  'delete retry rejects message-id substitution'
);

select is(
  (
    select invite_id
    from private.issue_invite(
      '10000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '82000000-0000-4000-8000-000000000005',
      'ACCOUNT_REGISTRATION',
      null,
      private.derive_invite_code_digest(
        '10000000-0000-4000-8000-000000000001',
        'ACCOUNT_REGISTRATION',
        null,
        '82000000-0000-4000-8000-000000000005'
      ),
      300
    )
  ),
  (
    select invite_id
    from private.issue_invite(
      '10000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '82000000-0000-4000-8000-000000000005',
      'ACCOUNT_REGISTRATION',
      null,
      private.derive_invite_code_digest(
        '10000000-0000-4000-8000-000000000001',
        'ACCOUNT_REGISTRATION',
        null,
        '82000000-0000-4000-8000-000000000005'
      ),
      300
    )
  ),
  'account invite retry reproduces the exact issued credential receipt'
);
select is(
  (select count(*)::integer from private.account_registration_invites
   where issued_by_user_id = '10000000-0000-4000-8000-000000000001'),
  1,
  'account invite retry creates no orphan duplicate credential'
);
select throws_ok(
  $$select * from private.issue_invite(
      '10000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '82000000-0000-4000-8000-000000000005',
      'ACCOUNT_REGISTRATION',
      null,
      private.derive_invite_code_digest(
        '10000000-0000-4000-8000-000000000001',
        'ACCOUNT_REGISTRATION',
        null,
        '82000000-0000-4000-8000-000000000005'
      ),
      301
    )$$,
  '23505',
  'invite mutation id was already used',
  'invite retry rejects changed expiry input'
);
select is(
  (
    select invite_kind
    from private.issue_invite(
      '10000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '82000000-0000-4000-8000-000000000006',
      'ROOM_MEMBERSHIP',
      '30000000-0000-4000-8000-000000000001',
      private.derive_invite_code_digest(
        '10000000-0000-4000-8000-000000000001',
        'ROOM_MEMBERSHIP',
        '30000000-0000-4000-8000-000000000001',
        '82000000-0000-4000-8000-000000000006'
      ),
      300
    )
  ),
  'ROOM_MEMBERSHIP',
  'room invite issuance uses the same deterministic receipt contract'
);

update private.room_retention_mutation_receipts
set completed_at = statement_timestamp() - interval '25 hours',
    expires_at = statement_timestamp() - interval '1 hour'
where client_mutation_id = '82000000-0000-4000-8000-000000000001';
update private.room_creation_mutation_receipts
set metadata_updated_at = statement_timestamp() - interval '25 hours',
    expires_at = statement_timestamp() - interval '1 hour'
where client_mutation_id = '80000000-0000-4000-8000-000000000001';
select ok(
  (
    select mutation_receipts_deleted >= 1
    from private.purge_expired_relational_data(
      500,
      '70000000-0000-4000-8000-000000000002'
    )
  ),
  'retention purges expired core mutation receipts with a reported count'
);
select is(
  (select count(*)::integer from private.room_creation_mutation_receipts
   where client_mutation_id = '80000000-0000-4000-8000-000000000001'),
  0,
  'retention physically removes expired room-creation mutation receipts'
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
insert into private.device_sessions (session_id, device_id)
values (
  '60000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000001'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"10000000-0000-4000-8000-000000000001","session_id":"60000000-0000-4000-8000-000000000002","role":"authenticated"}',
  true
);
select ok(
  private.can_read_device_bundle('20000000-0000-4000-8000-000000000002'),
  'a revoked sender bundle stays readable while current unexpired ciphertext requires it'
);
select is(
  (
    select count(*)::integer
    from private.list_room_recipient_devices('30000000-0000-4000-8000-000000000001')
  ),
  1,
  'recipient enumeration excludes revoked historical devices'
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
