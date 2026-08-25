-- Production bootstrap capabilities are deliberately not seeded. Generate a
-- random 32-byte invite locally, persist only its SHA-256 digest through
-- private.configure_bootstrap_capability, and deliver the raw code out of band.
-- This file exists so `supabase db reset` has a deterministic, secret-free seed.

do $$
begin
  if exists (select 1 from public.profiles) then
    raise exception 'a reset seed must not create Synapse Private accounts';
  end if;
end;
$$;
