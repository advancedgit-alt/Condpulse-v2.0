-- Manual operational onboarding only. Never granted to API/worker or exposed by HTTP.
DO $$
BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='cp_onboarding') THEN
  CREATE ROLE cp_onboarding NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 END IF;
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='cp_onboarding'
   AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication OR rolcanlogin))
   OR pg_has_role('cp_api','cp_onboarding','MEMBER')
   OR pg_has_role('cp_worker','cp_onboarding','MEMBER')
   OR pg_has_role('cp_onboarding','cp_api','MEMBER')
   OR pg_has_role('cp_onboarding','cp_worker','MEMBER') THEN
  RAISE EXCEPTION 'Operational onboarding role is incompatible with runtime isolation';
 END IF;
END $$;

GRANT USAGE ON SCHEMA platform,cp TO cp_onboarding;
GRANT EXECUTE ON FUNCTION platform.current_tenant_id(),platform.current_actor_id() TO cp_onboarding;
-- Auth identity is verified through the read-only Admin API before this DML job.
-- The managed Auth schema, grants and policies remain entirely provider-owned.
GRANT SELECT ON platform.accounts,platform.tenants,cp.units,cp.memberships,cp.amenities TO cp_onboarding;
GRANT INSERT(id,status) ON platform.accounts TO cp_onboarding;
GRANT INSERT(id,name,time_zone,active) ON platform.tenants TO cp_onboarding;
GRANT INSERT(id,tenant_id,label,status) ON cp.units TO cp_onboarding;
GRANT INSERT(id,tenant_id,account_id,unit_id,display_name,role,status,valid_from,valid_until) ON cp.memberships TO cp_onboarding;
GRANT INSERT(id,tenant_id,name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy,active) ON cp.amenities TO cp_onboarding;

CREATE POLICY onboarding_account_read ON platform.accounts FOR SELECT TO cp_onboarding
 USING(id=platform.current_actor_id());
CREATE POLICY onboarding_account_insert ON platform.accounts FOR INSERT TO cp_onboarding
 WITH CHECK(id=platform.current_actor_id() AND status='ACTIVE');
CREATE POLICY onboarding_tenant_read ON platform.tenants FOR SELECT TO cp_onboarding
 USING(id=platform.current_tenant_id());
CREATE POLICY onboarding_tenant_insert ON platform.tenants FOR INSERT TO cp_onboarding
 WITH CHECK(id=platform.current_tenant_id() AND active);

DO $$
DECLARE table_name text;
BEGIN
 FOREACH table_name IN ARRAY ARRAY['units','memberships','amenities'] LOOP
  EXECUTE format('CREATE POLICY onboarding_read ON cp.%I FOR SELECT TO cp_onboarding USING(tenant_id=platform.current_tenant_id())',table_name);
  EXECUTE format('CREATE POLICY onboarding_insert ON cp.%I FOR INSERT TO cp_onboarding WITH CHECK(tenant_id=platform.current_tenant_id())',table_name);
 END LOOP;
END $$;

-- Records the actual database principal, not a fabricated Auth actor. Append-only.
CREATE TABLE platform.onboarding_runs (
 operation_id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL UNIQUE REFERENCES platform.tenants(id),
 manifest_hash text NOT NULL CHECK(manifest_hash ~ '^[0-9a-f]{64}$'),
 database_actor name NOT NULL,
 counts jsonb NOT NULL CHECK(jsonb_typeof(counts)='object'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON platform.onboarding_runs FROM PUBLIC;
ALTER TABLE platform.onboarding_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.onboarding_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY onboarding_run_read ON platform.onboarding_runs FOR SELECT TO cp_onboarding
 USING(tenant_id=platform.current_tenant_id() AND database_actor=current_user);
CREATE POLICY onboarding_run_insert ON platform.onboarding_runs FOR INSERT TO cp_onboarding
 WITH CHECK(tenant_id=platform.current_tenant_id() AND database_actor=current_user);
GRANT SELECT ON platform.onboarding_runs TO cp_onboarding;
GRANT INSERT(operation_id,tenant_id,manifest_hash,database_actor,counts) ON platform.onboarding_runs TO cp_onboarding;
-- No login, role membership, password, DDL or real business fixture is provisioned here.
