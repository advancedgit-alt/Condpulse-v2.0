-- AC-007/AC-008 (pacote Acesso Facial v1.1): política de ingresso por área, revisões imutáveis do documento de regra,
-- validação contra os destinos comissionados e aprovação pela gestão. A publicação por destino (ponteiro publicado,
-- implantações e confirmação) fica na V045; a simulação não grava decisão nem cria comando (as decisões entram na V045).
--  - cp.access_policies: uma política por área (identidade estável); estado DRAFT ou RETIRED nesta fatia.
--  - cp.access_policy_versions: documento fechado (schema 1.0) imutável, hash calculado pelo banco, revisão sequencial;
--    DRAFT -> VALIDATED (validação aprovada e vigente) -> APPROVED (policy.publish) ou REJECTED. Nada é apagado.
--  - cp.access_policy_validations: resultado append-only da validação (problemas, pontos afetados, capacidades exigidas),
--    preso ao hash da revisão e à revisão de instalação vigente, com validade de 24 h definida pelo banco.
--  - Gestão: policy.edit (cria, valida, simula) e policy.publish (aprova). Leitura mínima dos destinos para a validação
--    pelo GUC app.policy_destination_scan, ligado só durante a consulta (o gestor nunca recebe canal nem relé).

-- 1. Revisão de instalação vigente (maior aceite aprovado do condomínio; 0 sem aceite).
CREATE FUNCTION cp.installation_current_revision(target_tenant uuid) RETURNS integer
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(max(installation_revision),0) FROM cp.installation_acceptances WHERE tenant_id=target_tenant AND status='APPROVED' $$;
REVOKE ALL ON FUNCTION cp.installation_current_revision(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.installation_current_revision(uuid) TO cp_api;

CREATE FUNCTION cp.policy_destination_scan() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(current_setting('app.policy_destination_scan',true),'')='on'
  AND (platform.has_capability('policy.edit') OR platform.has_capability('policy.publish')) $$;
REVOKE ALL ON FUNCTION cp.policy_destination_scan() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.policy_destination_scan() TO cp_api;

-- 2. Política por área.
CREATE TABLE cp.access_policies (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 area_id uuid NOT NULL,
 name text NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 160),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','SUSPENDED','RETIRED')),
 current_published_version_id uuid,
 envelope_acceptance_id uuid,
 envelope_hash text CHECK(envelope_hash IS NULL OR envelope_hash ~ '^[0-9a-f]{64}$'),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 updated_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,area_id),
 UNIQUE(tenant_id,id,area_id),
 FOREIGN KEY(tenant_id,area_id) REFERENCES cp.access_areas(tenant_id,id),
 FOREIGN KEY(tenant_id,envelope_acceptance_id) REFERENCES cp.installation_acceptances(tenant_id,id)
);
CREATE INDEX access_policies_status ON cp.access_policies(tenant_id,status);

CREATE FUNCTION cp.access_policies_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' OR NEW.version<>0 OR NEW.current_published_version_id IS NOT NULL OR NEW.envelope_acceptance_id IS NOT NULL
    OR NEW.envelope_hash IS NOT NULL THEN
   RAISE EXCEPTION 'política nasce rascunho e sem publicação' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM cp.access_areas a WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.area_id AND a.status='RETIRED') THEN
   RAISE EXCEPTION 'área retirada não recebe política' USING ERRCODE='23514';
  END IF;
  NEW.updated_by:=NEW.created_by; NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.area_id,NEW.created_by,NEW.created_at,NEW.current_published_version_id,NEW.envelope_acceptance_id,NEW.envelope_hash)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.area_id,OLD.created_by,OLD.created_at,OLD.current_published_version_id,OLD.envelope_acceptance_id,OLD.envelope_hash)
   OR NEW.version<>OLD.version+1 OR OLD.status='RETIRED' THEN
  RAISE EXCEPTION 'política: área fixa, publicação só pela publicação, retirada final' USING ERRCODE='23514';
 END IF;
 -- Nesta fatia só DRAFT e RETIRED; ACTIVE e SUSPENDED nascem da publicação (V045).
 IF NEW.status<>OLD.status AND NOT (OLD.status='DRAFT' AND NEW.status='RETIRED') THEN
  RAISE EXCEPTION 'transição de política não permitida' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_policies_guard() FROM PUBLIC;
CREATE TRIGGER access_policies_guard BEFORE INSERT OR UPDATE ON cp.access_policies FOR EACH ROW EXECUTE FUNCTION cp.access_policies_guard();

-- 3. Revisões do documento.
CREATE TABLE cp.access_policy_versions (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 policy_id uuid NOT NULL,
 revision_number integer NOT NULL DEFAULT 1 CHECK(revision_number>=1),
 state text NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','VALIDATED','APPROVED','PUBLISHED','SUPERSEDED','REJECTED')),
 document_schema_version text NOT NULL CHECK(document_schema_version='1.0'),
 document jsonb NOT NULL CHECK(jsonb_typeof(document)='object' AND length(document::text)<=32768),
 canonical_hash text NOT NULL DEFAULT '',
 change_reason text NOT NULL CHECK(length(btrim(change_reason)) BETWEEN 8 AND 1000),
 validation_id uuid,
 envelope_hash text CHECK(envelope_hash IS NULL OR envelope_hash ~ '^[0-9a-f]{64}$'),
 authored_by uuid NOT NULL REFERENCES platform.accounts(id),
 approved_by uuid REFERENCES platform.accounts(id),
 approved_at timestamptz,
 rejected_reason text CHECK(rejected_reason IS NULL OR length(btrim(rejected_reason)) BETWEEN 3 AND 300),
 published_at timestamptz,
 valid_from timestamptz,
 valid_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,policy_id,revision_number),
 UNIQUE(tenant_id,id,policy_id),
 FOREIGN KEY(tenant_id,policy_id) REFERENCES cp.access_policies(tenant_id,id),
 CHECK((approved_at IS NULL)=(approved_by IS NULL)),
 CHECK(state NOT IN ('APPROVED','PUBLISHED','SUPERSEDED') OR approved_at IS NOT NULL),
 CHECK(state NOT IN ('VALIDATED','APPROVED','PUBLISHED','SUPERSEDED') OR validation_id IS NOT NULL),
 CHECK((state='REJECTED')=(rejected_reason IS NOT NULL)),
 CHECK(valid_until IS NULL OR valid_from IS NULL OR valid_until>valid_from)
);
CREATE INDEX access_policy_versions_history ON cp.access_policy_versions(tenant_id,policy_id,state,revision_number);
ALTER TABLE cp.access_policies ADD CONSTRAINT access_policies_published_version
 FOREIGN KEY(tenant_id,current_published_version_id,id) REFERENCES cp.access_policy_versions(tenant_id,id,policy_id);

-- 4. Validações (append-only).
CREATE TABLE cp.access_policy_validations (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 policy_id uuid NOT NULL,
 policy_version_id uuid NOT NULL,
 canonical_hash text NOT NULL CHECK(canonical_hash ~ '^[0-9a-f]{64}$'),
 installation_revision integer NOT NULL CHECK(installation_revision>=0),
 status text NOT NULL CHECK(status IN ('VALIDATED','BLOCKED')),
 issues jsonb NOT NULL CHECK(jsonb_typeof(issues)='array' AND jsonb_array_length(issues)<=300 AND length(issues::text)<=262144),
 affected_point_ids uuid[] NOT NULL CHECK(cardinality(affected_point_ids)<=1000),
 -- Impressão dos fatos consultados (área, passagens de ingresso, vínculos ativos e combinações), calculada pelo banco.
 facts_hash text NOT NULL DEFAULT '',
 required_capabilities text[] NOT NULL CHECK(required_capabilities <@ ARRAY['faceEnrollment','faceDeletion','perDoorRights','localValidity',
  'onlineDecisionBeforeRelay','offlineRestriction','doorContactEvents','passageEvents','scopedRemoteOpen','eventBackfill','secureTransport','safeRecovery']),
 validated_by uuid NOT NULL REFERENCES platform.accounts(id),
 validated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,id,policy_version_id),
 FOREIGN KEY(tenant_id,policy_version_id,policy_id) REFERENCES cp.access_policy_versions(tenant_id,id,policy_id),
 CHECK(expires_at>validated_at),
 -- Validada só sem impeditivo e com ao menos um destino.
 CHECK(status<>'VALIDATED' OR (cardinality(affected_point_ids)>=1 AND NOT jsonb_path_exists(issues,'$[*] ? (@.severity == "BLOCKER")')))
);
CREATE INDEX access_policy_validations_version ON cp.access_policy_validations(tenant_id,policy_version_id,validated_at);
ALTER TABLE cp.access_policy_versions ADD CONSTRAINT access_policy_versions_validation
 FOREIGN KEY(tenant_id,validation_id,id) REFERENCES cp.access_policy_validations(tenant_id,id,policy_version_id);

-- Impressão dos fatos que a validação consulta. Só com a varredura ligada (falha fechada): sem ela os vínculos não são
-- visíveis e a impressão não representaria o que foi validado.
CREATE FUNCTION cp.policy_facts_hash(target_tenant uuid,target_area uuid) RETURNS text
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE facts jsonb;
BEGIN
 IF NOT cp.policy_destination_scan() THEN RAISE EXCEPTION 'impressão dos fatos exige a varredura de destinos' USING ERRCODE='42501'; END IF;
 SELECT jsonb_build_object(
   'area',(SELECT jsonb_build_object('status',a.status,'kind',a.area_kind,'amenity',a.amenity_id,'timezone',a.timezone,'essential',a.essential_route)
     FROM cp.access_areas a WHERE a.tenant_id=target_tenant AND a.id=target_area),
   'points',coalesce((SELECT jsonb_agg(jsonb_build_object('id',p.id,'status',p.status,'flow',p.flow_direction,'from',p.from_area_id,'to',p.to_area_id,
      'binding',b.id,'revision',b.binding_revision,'fingerprint',b.commissioned_fingerprint,'homologation',h.id,'homologationStatus',h.status,
      'homologationVersion',h.version,'reviewDue',h.review_due_at,'capabilities',h.approved_capabilities) ORDER BY p.id)
     FROM cp.access_points p
     LEFT JOIN cp.point_bindings b ON b.tenant_id=p.tenant_id AND b.point_id=p.id AND b.status='ACTIVE'
     LEFT JOIN platform.access_homologations h ON h.id=b.homologation_id
     WHERE p.tenant_id=target_tenant AND p.status<>'RETIRED'
      AND ((p.to_area_id=target_area AND p.flow_direction IN ('FROM_TO','BOTH','UNKNOWN'))
       OR (p.from_area_id=target_area AND p.flow_direction IN ('TO_FROM','BOTH','UNKNOWN')))),'[]'::jsonb))
  INTO facts;
 RETURN encode(sha256(convert_to(facts::text,'UTF8')),'hex');
END $$;
REVOKE ALL ON FUNCTION cp.policy_facts_hash(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.policy_facts_hash(uuid,uuid) TO cp_api;

CREATE FUNCTION cp.access_policy_validations_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 -- Presa ao hash atual da revisão e à revisão de instalação vigente; horário e validade definidos pelo banco.
 IF NOT EXISTS(SELECT 1 FROM cp.access_policy_versions v WHERE v.tenant_id=NEW.tenant_id AND v.id=NEW.policy_version_id
   AND v.canonical_hash=NEW.canonical_hash AND v.state IN ('DRAFT','VALIDATED')) THEN
  RAISE EXCEPTION 'validação só de revisão em preparação e pelo hash atual' USING ERRCODE='23514';
 END IF;
 -- Mesmo lock da instalação (vínculos, aceites, canais, equipamentos e pontos): os fatos não mudam até o commit.
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 IF NEW.installation_revision<>cp.installation_current_revision(NEW.tenant_id) THEN
  RAISE EXCEPTION 'revisão de instalação mudou durante a validação' USING ERRCODE='23514';
 END IF;
 NEW.facts_hash:=cp.policy_facts_hash(NEW.tenant_id,(SELECT p.area_id FROM cp.access_policies p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.policy_id));
 NEW.validated_at:=clock_timestamp(); NEW.expires_at:=NEW.validated_at+interval '24 hours';
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_policy_validations_guard() FROM PUBLIC;
CREATE TRIGGER access_policy_validations_guard BEFORE INSERT ON cp.access_policy_validations
 FOR EACH ROW EXECUTE FUNCTION cp.access_policy_validations_guard();

-- Revisão: número sequencial e hash pelo banco; documento imutável; transições fechadas. A aprovação exige validação
-- aprovada, vigente, do hash atual e da revisão de instalação atual; o banco grava aprovador e horário.
CREATE FUNCTION cp.access_policy_versions_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE v record;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.access_policy:'||NEW.policy_id::text,0));
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'DRAFT' OR NEW.version<>0 OR NEW.validation_id IS NOT NULL OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL
    OR NEW.published_at IS NOT NULL OR NEW.envelope_hash IS NOT NULL OR NEW.valid_from IS NOT NULL OR NEW.valid_until IS NOT NULL THEN
   RAISE EXCEPTION 'revisão nasce rascunho' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM cp.access_policies p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.policy_id AND p.status<>'RETIRED'
    AND p.area_id::text=NEW.document->>'areaId') OR NEW.document->>'schemaVersion' IS DISTINCT FROM NEW.document_schema_version THEN
   RAISE EXCEPTION 'documento da área da política, em política não retirada e na versão de esquema declarada' USING ERRCODE='23514';
  END IF;
  SELECT coalesce(max(revision_number),0)+1 INTO NEW.revision_number FROM cp.access_policy_versions WHERE tenant_id=NEW.tenant_id AND policy_id=NEW.policy_id;
  NEW.canonical_hash:=encode(sha256(convert_to(NEW.document_schema_version||':'||NEW.document::text,'UTF8')),'hex');
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.policy_id,NEW.revision_number,NEW.document_schema_version,NEW.document,NEW.canonical_hash,NEW.change_reason,
     NEW.authored_by,NEW.created_at,NEW.published_at,NEW.envelope_hash,NEW.valid_from,NEW.valid_until)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.policy_id,OLD.revision_number,OLD.document_schema_version,OLD.document,OLD.canonical_hash,OLD.change_reason,
     OLD.authored_by,OLD.created_at,OLD.published_at,OLD.envelope_hash,OLD.valid_from,OLD.valid_until)
   OR NEW.version<>OLD.version+1 OR OLD.state IN ('REJECTED','SUPERSEDED','PUBLISHED') THEN
  RAISE EXCEPTION 'revisão: documento imutável; edição é nova revisão' USING ERRCODE='23514';
 END IF;
 IF OLD.approved_at IS NOT NULL AND (NEW.approved_by,NEW.approved_at) IS DISTINCT FROM (OLD.approved_by,OLD.approved_at) THEN
  RAISE EXCEPTION 'aprovação não muda' USING ERRCODE='23514';
 END IF;
 -- Nova validação (DRAFT/VALIDATED): o estado segue o resultado.
 IF NEW.validation_id IS DISTINCT FROM OLD.validation_id THEN
  IF OLD.state NOT IN ('DRAFT','VALIDATED') OR NOT platform.has_capability('policy.edit') THEN
   RAISE EXCEPTION 'validação só de revisão em preparação, pela edição de políticas' USING ERRCODE='42501';
  END IF;
  SELECT status,expires_at,canonical_hash INTO v FROM cp.access_policy_validations WHERE tenant_id=NEW.tenant_id AND id=NEW.validation_id;
  IF v.status IS NULL OR v.canonical_hash<>NEW.canonical_hash THEN RAISE EXCEPTION 'validação de outra revisão' USING ERRCODE='23514'; END IF;
  IF NEW.state<>(CASE WHEN v.status='VALIDATED' THEN 'VALIDATED' ELSE 'DRAFT' END) THEN
   RAISE EXCEPTION 'estado da revisão segue a validação' USING ERRCODE='23514';
  END IF;
  NEW.updated_at:=clock_timestamp();
  RETURN NEW;
 END IF;
 IF NEW.state<>OLD.state THEN
  IF NEW.state='APPROVED' THEN
   IF OLD.state<>'VALIDATED' OR NOT platform.has_capability('policy.publish') THEN
    RAISE EXCEPTION 'aprovação só de revisão validada, pela publicação de políticas' USING ERRCODE='42501';
   END IF;
   PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
   SELECT status,expires_at,installation_revision,canonical_hash,facts_hash INTO v FROM cp.access_policy_validations
    WHERE tenant_id=NEW.tenant_id AND id=NEW.validation_id;
   -- Os fatos atuais (passagens, vínculos, combinações e área) precisam ser os mesmos da validação.
   IF v.status IS DISTINCT FROM 'VALIDATED' OR v.expires_at<=now() OR v.canonical_hash<>NEW.canonical_hash
     OR v.installation_revision<>cp.installation_current_revision(NEW.tenant_id)
     OR v.facts_hash<>cp.policy_facts_hash(NEW.tenant_id,(SELECT p.area_id FROM cp.access_policies p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.policy_id)) THEN
    RAISE EXCEPTION 'validação vencida ou de outra instalação: valide de novo' USING ERRCODE='23514';
   END IF;
   NEW.approved_by:=platform.current_actor_id(); NEW.approved_at:=clock_timestamp();
  ELSIF NEW.state='REJECTED' THEN
   IF OLD.state NOT IN ('DRAFT','VALIDATED','APPROVED') OR NOT (platform.has_capability('policy.edit') OR platform.has_capability('policy.publish')) THEN
    RAISE EXCEPTION 'rejeição não permitida' USING ERRCODE='42501';
   END IF;
  ELSE
   RAISE EXCEPTION 'transição de revisão não permitida' USING ERRCODE='23514';
  END IF;
 ELSIF (NEW.approved_by,NEW.approved_at,NEW.rejected_reason) IS DISTINCT FROM (OLD.approved_by,OLD.approved_at,OLD.rejected_reason) THEN
  RAISE EXCEPTION 'aprovação e rejeição só pela transição' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_policy_versions_guard() FROM PUBLIC;
CREATE TRIGGER access_policy_versions_guard BEFORE INSERT OR UPDATE ON cp.access_policy_versions
 FOR EACH ROW EXECUTE FUNCTION cp.access_policy_versions_guard();

-- 5. RLS.
ALTER TABLE cp.access_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.access_policy_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_policy_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.access_policy_validations ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_policy_validations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.access_policies,cp.access_policy_versions,cp.access_policy_validations FROM PUBLIC;
CREATE POLICY tenant_boundary ON cp.access_policies TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.access_policy_versions TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.access_policy_validations TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

CREATE FUNCTION cp.policy_reader() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_capability('policy.edit') OR platform.has_capability('policy.publish') $$;
REVOKE ALL ON FUNCTION cp.policy_reader() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.policy_reader() TO cp_api;

CREATE POLICY access_policies_read ON cp.access_policies AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.policy_reader());
CREATE POLICY access_policies_create ON cp.access_policies AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('policy.edit') AND created_by=platform.current_actor_id());
CREATE POLICY access_policies_change ON cp.access_policies AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('policy.edit')) WITH CHECK(platform.has_capability('policy.edit') AND updated_by=platform.current_actor_id());
CREATE POLICY access_policy_versions_read ON cp.access_policy_versions AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.policy_reader());
CREATE POLICY access_policy_versions_create ON cp.access_policy_versions AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('policy.edit') AND authored_by=platform.current_actor_id());
CREATE POLICY access_policy_versions_change ON cp.access_policy_versions AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.policy_reader()) WITH CHECK(cp.policy_reader());
CREATE POLICY access_policy_validations_read ON cp.access_policy_validations AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.policy_reader());
CREATE POLICY access_policy_validations_create ON cp.access_policy_validations AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('policy.edit') AND validated_by=platform.current_actor_id());

GRANT SELECT ON cp.access_policies,cp.access_policy_versions,cp.access_policy_validations TO cp_api;
GRANT INSERT(id,tenant_id,area_id,name,created_by) ON cp.access_policies TO cp_api;
GRANT UPDATE(name,status,updated_by,version) ON cp.access_policies TO cp_api;
GRANT INSERT(id,tenant_id,policy_id,document_schema_version,document,change_reason,authored_by) ON cp.access_policy_versions TO cp_api;
GRANT UPDATE(state,validation_id,rejected_reason,version) ON cp.access_policy_versions TO cp_api;
GRANT INSERT(id,tenant_id,policy_id,policy_version_id,canonical_hash,installation_revision,status,issues,affected_point_ids,required_capabilities,
 validated_by) ON cp.access_policy_validations TO cp_api;
-- Worker (compilador de direitos, V044/V045): documento e estado, sem autoria nem motivo.
GRANT SELECT(id,tenant_id,area_id,status,current_published_version_id,envelope_hash,version) ON cp.access_policies TO cp_worker;
GRANT SELECT(id,tenant_id,policy_id,revision_number,state,document_schema_version,document,canonical_hash,valid_from,valid_until)
 ON cp.access_policy_versions TO cp_worker;

-- 6. Leitura mínima dos destinos para validar e simular: gestão com policy.edit/publish lê mapa, combinações e — só com
-- o GUC de varredura ligado na consulta da API — os vínculos ativos e os aceites (sem expor canal ou relé na API).
CREATE OR REPLACE FUNCTION cp.access_map_reader() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_capability('area.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_capability('policy.edit') OR platform.has_capability('policy.publish')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage')
  OR platform.has_technical_scope('installation.bind_channel') OR platform.has_technical_scope('installation.commission') $$;
CREATE OR REPLACE FUNCTION platform.access_catalog_reader() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_platform_capability('access_catalog.manage') OR platform.has_platform_capability('homologation.approve')
  OR platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_capability('policy.edit') OR platform.has_capability('policy.publish')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('homologation.request')
  OR platform.has_technical_scope('installation.manage') OR platform.has_technical_scope('installation.bind_channel')
  OR platform.has_technical_scope('installation.commission') $$;
DROP POLICY point_bindings_read ON cp.point_bindings;
CREATE POLICY point_bindings_read ON cp.point_bindings AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.installation_technical() OR cp.policy_destination_scan());
DROP POLICY installation_acceptances_read ON cp.installation_acceptances;
CREATE POLICY installation_acceptances_read ON cp.installation_acceptances AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.installation_technical() OR platform.has_capability('area.manage') OR cp.policy_reader());

-- 7. Trilha: POLICY_* pela gestão com policy.edit/policy.publish.
CREATE POLICY audit_policy_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'POLICY\_%' OR (actor_id=platform.current_actor_id() AND resource_type IN ('ACCESS_POLICY','ACCESS_POLICY_VERSION')
  AND cp.policy_reader()));
