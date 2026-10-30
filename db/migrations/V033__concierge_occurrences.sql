-- CP-027 (parte): ocorrências da portaria. Portaria (concierge.operate) e gestão (requests.manage), com AAL2 conferido na
-- API, registram o que precisa de verificação (equipamento, segurança, manutenção) e encerram com a resolução, pela versão
-- lida. Autoria, nome do vínculo e horários vêm do banco; o conteúdo não muda depois de registrado e nada é apagado.
-- Moradores, unidades e worker não leem ocorrências.
CREATE TABLE cp.concierge_occurrences (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 category text NOT NULL CHECK(category IN ('EQUIPMENT','SECURITY','MAINTENANCE','CLEANING','NOISE','OTHER')),
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 3 AND 120),
 description text NOT NULL CHECK(length(btrim(description)) BETWEEN 1 AND 2000),
 unit_id uuid,
 state text NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','RESOLVED')),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_by_name text NOT NULL CHECK(length(btrim(created_by_name)) BETWEEN 1 AND 160),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 resolution text CHECK(resolution IS NULL OR length(btrim(resolution)) BETWEEN 1 AND 1000),
 resolved_by uuid REFERENCES platform.accounts(id),
 resolved_by_name text,
 resolved_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK((state='RESOLVED')=(resolution IS NOT NULL AND resolved_by IS NOT NULL AND resolved_by_name IS NOT NULL AND resolved_at IS NOT NULL)),
 CHECK(state='RESOLVED' OR (resolution IS NULL AND resolved_by IS NULL AND resolved_by_name IS NULL AND resolved_at IS NULL))
);
CREATE INDEX concierge_occurrences_open ON cp.concierge_occurrences(tenant_id,state,created_at DESC,id);

CREATE FUNCTION cp.concierge_occurrence_actor_name(target_tenant uuid) RETURNS text
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT m.display_name FROM cp.memberships m JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.account_id=platform.current_actor_id() AND m.unit_id IS NULL AND m.status='ACTIVE'
   AND m.role IN ('CONCIERGE','MANAGER') AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
  ORDER BY m.role, m.created_at LIMIT 1 $$;
REVOKE ALL ON FUNCTION cp.concierge_occurrence_actor_name(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.concierge_occurrence_actor_name(uuid) TO cp_api;

CREATE FUNCTION cp.concierge_occurrences_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE actor_name text;
BEGIN
 actor_name:=cp.concierge_occurrence_actor_name(NEW.tenant_id);
 IF actor_name IS NULL THEN RAISE EXCEPTION 'Ocorrência somente pela equipe do condomínio.' USING ERRCODE='42501'; END IF;
 IF TG_OP='INSERT' THEN
  NEW.created_by:=platform.current_actor_id(); NEW.created_by_name:=actor_name; NEW.created_at:=clock_timestamp();
  NEW.state:='OPEN'; NEW.version:=0; NEW.resolution:=NULL; NEW.resolved_by:=NULL; NEW.resolved_by_name:=NULL; NEW.resolved_at:=NULL;
  RETURN NEW;
 END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.category<>OLD.category OR NEW.title<>OLD.title OR NEW.description<>OLD.description
    OR NEW.unit_id IS DISTINCT FROM OLD.unit_id OR NEW.created_by<>OLD.created_by OR NEW.created_by_name<>OLD.created_by_name
    OR NEW.created_at<>OLD.created_at THEN
  RAISE EXCEPTION 'Ocorrência registrada não muda de conteúdo.' USING ERRCODE='23514';
 END IF;
 IF OLD.state<>'OPEN' OR NEW.state<>'RESOLVED' OR NEW.resolution IS NULL OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'Encerramento de ocorrência não permitido.' USING ERRCODE='23514';
 END IF;
 NEW.resolved_by:=platform.current_actor_id(); NEW.resolved_by_name:=actor_name; NEW.resolved_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.concierge_occurrences_guard() FROM PUBLIC;
CREATE TRIGGER concierge_occurrences_guard BEFORE INSERT OR UPDATE ON cp.concierge_occurrences FOR EACH ROW EXECUTE FUNCTION cp.concierge_occurrences_guard();

REVOKE ALL ON cp.concierge_occurrences FROM PUBLIC;
GRANT SELECT ON cp.concierge_occurrences TO cp_api;
GRANT INSERT(id,tenant_id,category,title,description,unit_id) ON cp.concierge_occurrences TO cp_api;
GRANT UPDATE(state,resolution,version) ON cp.concierge_occurrences TO cp_api;
ALTER TABLE cp.concierge_occurrences ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.concierge_occurrences FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.concierge_occurrences TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY concierge_occurrences_staff ON cp.concierge_occurrences AS RESTRICTIVE TO cp_api
 USING(platform.has_capability('concierge.operate') OR platform.has_capability('requests.manage'))
 WITH CHECK(platform.has_capability('concierge.operate') OR platform.has_capability('requests.manage'));
CREATE POLICY audit_occurrence_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'OCCURRENCE\_%' OR (actor_id=platform.current_actor_id() AND resource_type='CONCIERGE_OCCURRENCE'
  AND (platform.has_capability('concierge.operate') OR platform.has_capability('requests.manage'))
  AND EXISTS(SELECT 1 FROM cp.concierge_occurrences o WHERE o.tenant_id=cp.audit_log.tenant_id AND o.id=cp.audit_log.resource_id)));
