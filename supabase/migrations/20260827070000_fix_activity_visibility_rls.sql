create function private.can_view_typing_state(
  p_room_id uuid,
  p_device_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.is_active_room_member(p_room_id)
    and exists (
      select 1
      from public.devices as typing_device
      join public.profiles as typing_profile
        on typing_profile.user_id = typing_device.user_id
      where typing_device.id = p_device_id
        and typing_device.revoked_at is null
        and typing_profile.typing_indicators_enabled
    );
$$;

create function private.can_view_presence_state(p_device_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.current_device_id() is not null
    and exists (
      select 1
      from public.devices as present_device
      join public.profiles as present_profile
        on present_profile.user_id = present_device.user_id
      join public.room_members as present_membership
        on present_membership.user_id = present_device.user_id
      join public.room_members as viewer_membership
        on viewer_membership.room_id = present_membership.room_id
       and viewer_membership.user_id = (select auth.uid())
      where present_device.id = p_device_id
        and present_device.revoked_at is null
        and present_profile.presence_sharing_enabled
    );
$$;

revoke all on function private.can_view_typing_state(uuid, uuid)
from public, anon, authenticated;
revoke all on function private.can_view_presence_state(uuid)
from public, anon, authenticated;
grant execute on function private.can_view_typing_state(uuid, uuid) to authenticated;
grant execute on function private.can_view_presence_state(uuid) to authenticated;

drop policy typing_state_select_member_before_expiry on public.typing_state;
create policy typing_state_select_member_before_expiry
on public.typing_state
for select
to authenticated
using (
  expires_at > statement_timestamp()
  and private.can_view_typing_state(room_id, device_id)
);

drop policy presence_state_select_room_peer_before_expiry on public.presence_state;
create policy presence_state_select_room_peer_before_expiry
on public.presence_state
for select
to authenticated
using (
  expires_at > statement_timestamp()
  and private.can_view_presence_state(device_id)
);
