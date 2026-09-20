-- Read-only post-deployment verification; no users, sessions, or call fixtures are created.
-- Run with a database-owner connection: psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f this-file.sql
-- all_checks_pass must be true. Cron registration is checked, not successful job execution.
begin read only;

with expected_calls(signature) as (
  values
    ('create_private_call(uuid,uuid,integer,text,uuid,timestamptz,jsonb)'),
    ('accept_private_call(uuid,uuid)'),
    ('send_private_call_signal(uuid,uuid,integer,timestamptz,jsonb)'),
    ('heartbeat_private_call(uuid)'),
    ('end_private_call(uuid,uuid,text)'),
    ('poll_private_calls()')
), expected_schemas(schema_name, expected_definer) as (
  values ('public', false), ('private', true)
), resolved_calls as (
  select schema_name, signature, expected_definer,
    to_regprocedure(schema_name || '.' || signature) as function_oid
  from expected_calls cross join expected_schemas
), call_checks as (
  select schema_name, signature,
    function_oid is not null as exists,
    coalesce(procedure.prosecdef = expected_definer, false) as expected_execution_mode,
    coalesce(procedure.proconfig @> array['search_path=""']::text[], false) as fixed_search_path,
    coalesce(has_function_privilege('authenticated', function_oid, 'EXECUTE'), false) as authenticated_allowed,
    coalesce(not has_function_privilege('anon', function_oid, 'EXECUTE'), false) as anonymous_denied,
    coalesce(not has_function_privilege('service_role', function_oid, 'EXECUTE'), false) as service_role_denied
  from resolved_calls
  left join pg_proc as procedure on procedure.oid = function_oid
), expected_tables(table_name) as (
  values ('call_sessions'), ('call_devices'), ('call_device_leases'),
    ('call_signals'), ('call_signal_envelopes'), ('call_mutation_receipts')
), table_checks as (
  select table_name, relation.oid is not null as exists,
    coalesce(relation.relrowsecurity, false) as row_security_enabled,
    coalesce(not has_table_privilege('authenticated', relation.oid,
      'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'), false) as authenticated_denied,
    coalesce(not has_table_privilege('anon', relation.oid,
      'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'), false) as anonymous_denied,
    coalesce(not has_table_privilege('service_role', relation.oid,
      'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'), false) as service_role_denied
  from expected_tables
  left join pg_class as relation on relation.oid = to_regclass('private.' || table_name)
), purge_check as (
  select count(*) = 1 as exactly_one_job,
    coalesce(bool_and(active), false) as active,
    coalesce(bool_and(schedule = '* * * * *'), false) as every_minute,
    coalesce(bool_and(command = 'select private.purge_expired_private_calls()'), false) as expected_command
  from cron.job
  where jobname = 'synapse-private-call-purge'
), purge_function_check as (
  select
    to_regprocedure('private.purge_expired_private_calls()') is not null as exists,
    coalesce(not has_function_privilege('anon', to_regprocedure('private.purge_expired_private_calls()'), 'EXECUTE'), false) as anonymous_denied,
    coalesce(not has_function_privilege('authenticated', to_regprocedure('private.purge_expired_private_calls()'), 'EXECUTE'), false) as authenticated_denied,
    coalesce(not has_function_privilege('service_role', to_regprocedure('private.purge_expired_private_calls()'), 'EXECUTE'), false) as service_role_denied
)
select jsonb_build_object(
  'verified_at', statement_timestamp(),
  'all_checks_pass',
    (select bool_and(exists and expected_execution_mode and fixed_search_path
      and authenticated_allowed and anonymous_denied and service_role_denied) from call_checks)
    and (select bool_and(exists and row_security_enabled and authenticated_denied
      and anonymous_denied and service_role_denied) from table_checks)
    and (select exactly_one_job and active and every_minute and expected_command from purge_check)
    and (select exists and anonymous_denied and authenticated_denied and service_role_denied from purge_function_check),
  'call_rpc_permissions', (select jsonb_agg(to_jsonb(call_checks) order by schema_name, signature) from call_checks),
  'private_table_permissions', (select jsonb_agg(to_jsonb(table_checks) order by table_name) from table_checks),
  'purge_schedule', (select to_jsonb(purge_check) from purge_check),
  'purge_function_permissions', (select to_jsonb(purge_function_check) from purge_function_check)
) as deployment_receipt;

rollback;
