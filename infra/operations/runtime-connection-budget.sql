-- CondPulse: ajuste operacional da identidade runtime existente, fora do schema Flyway.
-- Executar somente no projeto rpnrqfrfwywglvyykgrg com a revisão/CI registrados.
-- Uma instrução DO atômica; não altera senha, marker, grants, schema ou sessões.
DO $condpulse_runtime_budget$
DECLARE
  runtime_role record;
  memberships integer;
  membership_safe boolean;
  operator_can_alter boolean;
  database_limit integer;
  other_backends integer;
BEGIN
  PERFORM set_config('lock_timeout', '2s', true);
  PERFORM set_config('statement_timeout', '10s', true);
  IF current_user <> 'postgres' OR session_user <> 'postgres'
      OR current_database() <> 'postgres' THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_ACTOR_DENIED';
  END IF;
  -- Mesmo bigint de BootstrapBaseline.LOCK_KEY (0x4350444c42415345).
  IF NOT pg_try_advisory_xact_lock(4850451892997804869::bigint) THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_OPERATION_BUSY';
  END IF;

  SELECT r.oid, r.rolname, r.rolcanlogin, r.rolinherit, r.rolvaliduntil,
      r.rolsuper, r.rolbypassrls, r.rolcreatedb, r.rolcreaterole,
      r.rolreplication, r.rolconnlimit,
      shobj_description(r.oid, 'pg_authid') AS marker
    INTO runtime_role
    FROM pg_roles r
    WHERE r.rolname = 'cp_runtime_2ca929e11f7e4a3eb9ab1b9ab147ff82';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_ROLE_MISSING';
  END IF;
  IF runtime_role.marker IS DISTINCT FROM
      'condpulse-runtime:rpnrqfrfwywglvyykgrg:2ca929e1-1f7e-4a3e-b9ab-1b9ab147ff82:2f1abf3547736c247fc082b2cc6ed76c18f2d38b:READY'
      OR NOT runtime_role.rolcanlogin
      OR NOT runtime_role.rolinherit
      OR runtime_role.rolvaliduntil IS DISTINCT FROM 'infinity'::timestamptz
      OR runtime_role.rolsuper OR runtime_role.rolbypassrls
      OR runtime_role.rolcreatedb OR runtime_role.rolcreaterole
      OR runtime_role.rolreplication THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_ROLE_UNSAFE';
  END IF;
  SELECT r.rolcreaterole AND EXISTS (
      SELECT 1 FROM pg_auth_members a
      WHERE a.roleid = runtime_role.oid AND a.member = r.oid AND a.admin_option)
    INTO operator_can_alter FROM pg_roles r WHERE r.rolname = current_user;
  IF NOT coalesce(operator_can_alter, false) THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_OPERATOR_PRIVILEGE_REQUIRED';
  END IF;
  SELECT count(*), coalesce(bool_and(
      g.rolname = 'cp_api' AND NOT m.admin_option AND m.inherit_option
      AND NOT m.set_option AND NOT g.rolcanlogin AND NOT g.rolsuper
      AND NOT g.rolbypassrls AND NOT g.rolcreatedb
      AND NOT g.rolcreaterole AND NOT g.rolreplication), false)
    INTO memberships, membership_safe
    FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
    WHERE m.member = runtime_role.oid;
  IF memberships <> 1 OR NOT membership_safe OR EXISTS (
      SELECT 1 FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.member
      WHERE g.rolname = 'cp_api') THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_MEMBERSHIP_UNSAFE';
  END IF;
  IF EXISTS (
      SELECT 1 FROM pg_shdepend d
      WHERE d.refclassid = 'pg_authid'::regclass AND d.refobjid = runtime_role.oid
        AND d.deptype = 'o') OR EXISTS (
      SELECT 1 FROM pg_namespace n WHERE n.nspname !~ '^pg_'
        AND n.nspname <> 'information_schema'
        AND has_schema_privilege(runtime_role.rolname, n.oid, 'CREATE')) THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_DDL_OR_OWNERSHIP_UNSAFE';
  END IF;

  IF runtime_role.rolconnlimit = 16 THEN
    RAISE NOTICE 'RUNTIME_BUDGET_ALREADY_APPLIED';
    RETURN;
  END IF;
  IF runtime_role.rolconnlimit <> 4 THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_LIMIT_DRIFT';
  END IF;
  database_limit := current_setting('max_connections')::integer;
  SELECT count(*) INTO other_backends FROM pg_stat_activity a
    WHERE a.usename IS DISTINCT FROM runtime_role.rolname;
  IF database_limit <> 60 OR other_backends + 16 + 20 > database_limit THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_HEADROOM_INSUFFICIENT';
  END IF;

  ALTER ROLE cp_runtime_2ca929e11f7e4a3eb9ab1b9ab147ff82 CONNECTION LIMIT 16;
  IF (SELECT rolconnlimit FROM pg_roles WHERE oid = runtime_role.oid) IS DISTINCT FROM 16 THEN
    RAISE EXCEPTION 'RUNTIME_BUDGET_READBACK_FAILED';
  END IF;
  RAISE NOTICE 'RUNTIME_BUDGET_APPLIED';
END
$condpulse_runtime_budget$;
