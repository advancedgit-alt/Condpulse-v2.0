-- Gestão local (CP-014/CP-021/CP-018). A API publica app.capabilities somente
-- após conta ativa, vínculo vigente e token AAL2. A autorização Java continua
-- obrigatória; estas políticas restritivas são a segunda barreira no banco.
CREATE FUNCTION platform.has_capability(required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(required = ANY(string_to_array(nullif(current_setting('app.capabilities',true),''),',')),false) $$;
REVOKE ALL ON FUNCTION platform.has_capability(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.has_capability(text) TO cp_api;

-- Nomes legíveis e sem duplicidade por condomínio. Bases novas não possuem linhas.
ALTER TABLE cp.blocks ADD CONSTRAINT blocks_name_length CHECK(length(btrim(name)) BETWEEN 1 AND 60);
ALTER TABLE cp.amenities ADD CONSTRAINT amenities_name_length CHECK(length(btrim(name)) BETWEEN 1 AND 120);
CREATE UNIQUE INDEX amenities_tenant_name ON cp.amenities(tenant_id,lower(btrim(name)));

-- Sem DELETE: unidades e áreas são inativadas para preservar histórico e FKs.
GRANT INSERT(id,tenant_id,name) ON cp.blocks TO cp_api;
GRANT UPDATE(name) ON cp.blocks TO cp_api;
GRANT INSERT(id,tenant_id,block_id,label,status) ON cp.units TO cp_api;
GRANT UPDATE(block_id,label,status,updated_at,version) ON cp.units TO cp_api;
GRANT INSERT(id,tenant_id,name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy,active)
 ON cp.amenities TO cp_api;
GRANT UPDATE(name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy,active,updated_at,version)
 ON cp.amenities TO cp_api;
-- Leitura de auditoria somente com capacidade; inserção continua append-only.
GRANT SELECT ON cp.audit_log TO cp_api;

CREATE POLICY blocks_manage_insert ON cp.blocks AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('units.manage'));
CREATE POLICY blocks_manage_update ON cp.blocks AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('units.manage')) WITH CHECK(platform.has_capability('units.manage'));
CREATE POLICY units_manage_insert ON cp.units AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('units.manage'));
CREATE POLICY units_manage_update ON cp.units AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('units.manage')) WITH CHECK(platform.has_capability('units.manage'));
CREATE POLICY amenities_manage_insert ON cp.amenities AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('amenities.manage'));
CREATE POLICY amenities_manage_update ON cp.amenities AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('amenities.manage')) WITH CHECK(platform.has_capability('amenities.manage'));
CREATE POLICY audit_read_capability ON cp.audit_log AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('audit.read'));
