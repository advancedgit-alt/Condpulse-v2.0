DO $$
BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='cp_api') THEN
  CREATE ROLE cp_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 END IF;
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='cp_worker') THEN
  CREATE ROLE cp_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 END IF;
 IF EXISTS(SELECT FROM pg_roles WHERE rolname IN ('cp_api','cp_worker')
   AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication OR rolcanlogin)) THEN
  RAISE EXCEPTION 'Role CondPulse incompatível; revisar provisionamento';
 END IF;
END $$;
REVOKE ALL ON SCHEMA cp,platform FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA cp,platform FROM PUBLIC;
GRANT USAGE ON SCHEMA cp,platform TO cp_api,cp_worker;
CREATE FUNCTION platform.current_tenant_id() RETURNS uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT nullif(current_setting('app.tenant_id',true),'')::uuid $$;
CREATE FUNCTION platform.current_actor_id() RETURNS uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT nullif(current_setting('app.actor_id',true),'')::uuid $$;
REVOKE ALL ON FUNCTION platform.current_tenant_id(),platform.current_actor_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.current_tenant_id(),platform.current_actor_id() TO cp_api,cp_worker;
ALTER TABLE platform.accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.accounts FORCE ROW LEVEL SECURITY;
CREATE POLICY account_self ON platform.accounts TO cp_api USING(id=platform.current_actor_id());
ALTER TABLE cp.memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.memberships FORCE ROW LEVEL SECURITY;
-- Somente vínculos próprios antes de escolher tenant; não é permissão de equipe inteira.
CREATE POLICY membership_self ON cp.memberships FOR SELECT TO cp_api USING(account_id=platform.current_actor_id());
ALTER TABLE platform.tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_membership ON platform.tenants FOR SELECT TO cp_api
USING(active AND EXISTS(
 SELECT 1 FROM cp.memberships m WHERE m.tenant_id=platform.tenants.id AND m.account_id=platform.current_actor_id()
 AND m.status='ACTIVE' AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
));
CREATE POLICY worker_tenant_catalog ON platform.tenants FOR SELECT TO cp_worker USING(active);
GRANT SELECT ON platform.accounts,platform.tenants,cp.memberships TO cp_api;
GRANT SELECT(id,active) ON platform.tenants TO cp_worker;
DO $$
DECLARE table_name text;
BEGIN
 FOREACH table_name IN ARRAY ARRAY[
  'blocks','units','amenities','reservations','parcels','parcel_handoffs','arrival_requests','audit_log','outbox'
 ] LOOP
  EXECUTE format('ALTER TABLE cp.%I ENABLE ROW LEVEL SECURITY',table_name);
  EXECUTE format('ALTER TABLE cp.%I FORCE ROW LEVEL SECURITY',table_name);
  EXECUTE format('CREATE POLICY tenant_boundary ON cp.%I TO cp_api,cp_worker USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id())',table_name);
 END LOOP;
END $$;
ALTER TABLE cp.idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_actor ON cp.idempotency_keys TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND actor_id=platform.current_actor_id())
 WITH CHECK(tenant_id=platform.current_tenant_id() AND actor_id=platform.current_actor_id());
GRANT SELECT ON cp.blocks,cp.units,cp.amenities,cp.reservations,cp.parcels,cp.parcel_handoffs,cp.arrival_requests TO cp_api;
GRANT INSERT,UPDATE ON cp.reservations,cp.parcels,cp.arrival_requests TO cp_api;
GRANT INSERT ON cp.parcel_handoffs,cp.audit_log,cp.outbox TO cp_api;
GRANT SELECT,INSERT,UPDATE ON cp.idempotency_keys TO cp_api;
GRANT SELECT,UPDATE ON cp.outbox,cp.reservations TO cp_worker;
GRANT INSERT ON cp.audit_log TO cp_worker;
-- Sem grant para anon/authenticated e sem alteração de evidências/auditoria.
