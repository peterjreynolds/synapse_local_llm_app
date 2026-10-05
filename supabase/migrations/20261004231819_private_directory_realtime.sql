-- Broadcast only an invalidation marker. Directory rows still come from the
-- device-authorized RPC; no identities, profiles, or message content enter Realtime.
create function private.can_receive_directory_updates()
returns boolean language sql stable security definer set search_path = '' as $$
  select private.current_device_id() is not null
    and private.directory_account_available(auth.uid());
$$;
revoke all on function private.can_receive_directory_updates() from public, anon, authenticated;
grant execute on function private.can_receive_directory_updates() to authenticated;

create policy private_directory_updates_receive on realtime.messages
for select to authenticated using (
  topic = 'synapse-private-directory' and private
  and (select realtime.topic()) = 'synapse-private-directory'
  and (select private.can_receive_directory_updates())
);

create function private.broadcast_directory_change()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  perform realtime.send('{}'::jsonb, 'directory_changed', 'synapse-private-directory', true);
  return null;
end;
$$;
revoke all on function private.broadcast_directory_change() from public, anon, authenticated;
create trigger directory_activity_changed
  after insert or update or delete on private.directory_activity
  for each row execute function private.broadcast_directory_change();
