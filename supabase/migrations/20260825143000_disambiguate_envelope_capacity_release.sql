-- Keep envelope quota release deterministic when PL/pgSQL resolves identifiers.
-- The original trigger function used the same identifier for a local UUID and
-- a contribution-ledger column, which made delete-trigger execution ambiguous.

create or replace function private.release_device_envelope_capacity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  deleted_envelope record;
  resolved_parent_record_id uuid;
  persisted_contribution private.envelope_capacity_contributions;
  released_device_id uuid;
begin
  perform pg_advisory_xact_lock(
    hashtextextended('synapse-private/encrypted-envelope-capacity', 0)
  );

  for deleted_envelope in
    select
      to_jsonb(envelope) as row_data,
      envelope.recipient_device_id,
      octet_length(envelope.ciphertext) as ciphertext_bytes
    from deleted_envelopes as envelope
    order by envelope.recipient_device_id
  loop
    if tg_table_name = 'message_envelopes' then
      resolved_parent_record_id := (deleted_envelope.row_data ->> 'message_id')::uuid;
    elsif tg_table_name = 'message_revision_envelopes' then
      resolved_parent_record_id := (deleted_envelope.row_data ->> 'revision_id')::uuid;
    elsif tg_table_name = 'reaction_envelopes' then
      resolved_parent_record_id := (deleted_envelope.row_data ->> 'reaction_id')::uuid;
    elsif tg_table_name = 'room_metadata_envelopes' then
      resolved_parent_record_id := (deleted_envelope.row_data ->> 'room_id')::uuid;
    else
      raise exception using errcode = '55000', message = 'encrypted envelope capacity table is unsupported';
    end if;

    select contribution.*
      into persisted_contribution
    from private.envelope_capacity_contributions as contribution
    where contribution.envelope_table = tg_table_name
      and contribution.parent_record_id = resolved_parent_record_id
      and contribution.recipient_device_id = deleted_envelope.recipient_device_id
    for update;

    if not found
      or persisted_contribution.ciphertext_bytes <> deleted_envelope.ciphertext_bytes
    then
      raise exception using errcode = '55000', message = 'encrypted envelope contribution is missing';
    end if;

    released_device_id := null;
    delete from private.device_envelope_capacity as capacity
    where capacity.recipient_device_id = persisted_contribution.recipient_device_id
      and capacity.stored_envelope_count = 1
      and capacity.stored_ciphertext_bytes = persisted_contribution.ciphertext_bytes
    returning capacity.recipient_device_id into released_device_id;

    if released_device_id is null then
      update private.device_envelope_capacity as capacity
      set stored_envelope_count = capacity.stored_envelope_count - 1,
          stored_ciphertext_bytes = capacity.stored_ciphertext_bytes - persisted_contribution.ciphertext_bytes,
          updated_at = statement_timestamp()
      where capacity.recipient_device_id = persisted_contribution.recipient_device_id
        and capacity.stored_envelope_count > 1
        and capacity.stored_ciphertext_bytes > persisted_contribution.ciphertext_bytes
      returning capacity.recipient_device_id into released_device_id;
    end if;

    if released_device_id is null then
      raise exception using errcode = '55000', message = 'encrypted device capacity ledger is inconsistent';
    end if;

    released_device_id := null;
    delete from private.device_room_envelope_capacity as capacity
    where capacity.recipient_device_id = persisted_contribution.recipient_device_id
      and capacity.room_id = persisted_contribution.room_id
      and capacity.stored_envelope_count = 1
      and capacity.stored_ciphertext_bytes = persisted_contribution.ciphertext_bytes
    returning capacity.recipient_device_id into released_device_id;

    if released_device_id is null then
      update private.device_room_envelope_capacity as capacity
      set stored_envelope_count = capacity.stored_envelope_count - 1,
          stored_ciphertext_bytes = capacity.stored_ciphertext_bytes - persisted_contribution.ciphertext_bytes,
          updated_at = statement_timestamp()
      where capacity.recipient_device_id = persisted_contribution.recipient_device_id
        and capacity.room_id = persisted_contribution.room_id
        and capacity.stored_envelope_count > 1
        and capacity.stored_ciphertext_bytes > persisted_contribution.ciphertext_bytes
      returning capacity.recipient_device_id into released_device_id;
    end if;

    if released_device_id is null then
      raise exception using errcode = '55000', message = 'encrypted room capacity ledger is inconsistent';
    end if;

    released_device_id := null;
    delete from private.device_sender_envelope_capacity as capacity
    where capacity.recipient_device_id = persisted_contribution.recipient_device_id
      and capacity.sender_user_id = persisted_contribution.sender_user_id
      and capacity.stored_envelope_count = 1
      and capacity.stored_ciphertext_bytes = persisted_contribution.ciphertext_bytes
    returning capacity.recipient_device_id into released_device_id;

    if released_device_id is null then
      update private.device_sender_envelope_capacity as capacity
      set stored_envelope_count = capacity.stored_envelope_count - 1,
          stored_ciphertext_bytes = capacity.stored_ciphertext_bytes - persisted_contribution.ciphertext_bytes,
          updated_at = statement_timestamp()
      where capacity.recipient_device_id = persisted_contribution.recipient_device_id
        and capacity.sender_user_id = persisted_contribution.sender_user_id
        and capacity.stored_envelope_count > 1
        and capacity.stored_ciphertext_bytes > persisted_contribution.ciphertext_bytes
      returning capacity.recipient_device_id into released_device_id;
    end if;

    if released_device_id is null then
      raise exception using errcode = '55000', message = 'encrypted sender capacity ledger is inconsistent';
    end if;

    delete from private.envelope_capacity_contributions as contribution
    where contribution.envelope_table = persisted_contribution.envelope_table
      and contribution.parent_record_id = persisted_contribution.parent_record_id
      and contribution.recipient_device_id = persisted_contribution.recipient_device_id;
  end loop;

  return null;
end;
$$;

revoke all on function private.release_device_envelope_capacity() from public, anon, authenticated;
