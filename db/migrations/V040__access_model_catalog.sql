-- AC-019 (dados) do pacote Acesso Facial v1.1: catálogo técnico global e homologações por combinação.
--  - platform.access_model_profiles: perfil documental (fabricante, modelo, hardware, região, revisão, fontes oficiais,
--    capacidades e limites DECLARADOS). Semeado com os 27 modelos do pacote (catalogo/modelos_candidatos.json, consultados
--    em 30/10/2026), todos CANDIDATE/DOCUMENTED_CANDIDATE. Catálogo não é homologação. SKU exato, revisão de hardware e
--    região ficam UNKNOWN até o inventário do condomínio (G0).
--  - platform.access_homologations: combinação concreta (perfil + firmware exato + adaptador + modo de decisão +
--    topologia LOCAL_GATEWAY/CLOUD_SERVICE). Conteúdo técnico imutável; nova observação é nova revisão. Estado só muda por
--    decisão registrada em platform.access_homologation_decisions (append-only) e trilha da plataforma no COMMIT.
-- Sem dados pessoais, fotos, templates ou segredos. Leitura: plataforma, gestão, portaria e MASTER de implantação.

CREATE TABLE platform.access_model_profiles (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 catalog_code text NOT NULL UNIQUE CHECK(catalog_code ~ '^[A-Z]{2,4}-M[0-9]{2,4}$'),
 manufacturer text NOT NULL CHECK(manufacturer IN ('CONTROL_ID','HIKVISION','DAHUA','INTELBRAS')),
 model_sku text NOT NULL CHECK(length(btrim(model_sku)) BETWEEN 1 AND 128),
 hardware_revision text NOT NULL DEFAULT 'UNKNOWN' CHECK(length(btrim(hardware_revision)) BETWEEN 1 AND 64),
 region_code text NOT NULL DEFAULT 'UNKNOWN' CHECK(length(btrim(region_code)) BETWEEN 1 AND 32),
 profile_revision integer NOT NULL DEFAULT 1 CHECK(profile_revision>=1),
 status text NOT NULL DEFAULT 'CANDIDATE' CHECK(status IN ('CANDIDATE','ACTIVE','RETIRED')),
 category text NOT NULL CHECK(category IN ('FACE_TERMINAL','ACCESS_CONTROLLER','READER','EXPANSION_MODULE')),
 source_category text NOT NULL CHECK(length(btrim(source_category)) BETWEEN 1 AND 64),
 market_lifecycle text NOT NULL DEFAULT 'DOCUMENTED_CANDIDATE'
  CHECK(market_lifecycle IN ('DOCUMENTED_CANDIDATE','CURRENT_DECLARED','DISCONTINUED_DECLARED','SUPPORT_UNKNOWN')),
 proposed_priority text CHECK(proposed_priority IS NULL OR proposed_priority ~ '^P[0-9][A-Za-z0-9_]{0,40}$'),
 sources jsonb NOT NULL CHECK(jsonb_typeof(sources)='array' AND jsonb_array_length(sources) BETWEEN 1 AND 30 AND length(sources::text)<=16384),
 declared_capabilities jsonb NOT NULL CHECK(jsonb_typeof(declared_capabilities)='object' AND length(declared_capabilities::text)<=16384),
 declared_limits jsonb NOT NULL CHECK(jsonb_typeof(declared_limits)='object' AND length(declared_limits::text)<=16384),
 notes text NOT NULL DEFAULT '' CHECK(length(notes)<=2000),
 consulted_on date NOT NULL,
 document_hash text NOT NULL DEFAULT '' ,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(manufacturer,model_sku,hardware_revision,region_code,profile_revision)
);
CREATE INDEX access_model_profiles_lookup ON platform.access_model_profiles(manufacturer,model_sku,status);

-- Hash canônico do perfil (o banco calcula; o cliente não escolhe). Perfil nunca muda: revisão nova é linha nova.
CREATE FUNCTION platform.access_model_profiles_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
  IF (NEW.id,NEW.catalog_code,NEW.manufacturer,NEW.model_sku,NEW.hardware_revision,NEW.region_code,NEW.profile_revision,NEW.category,
      NEW.source_category,NEW.market_lifecycle,NEW.proposed_priority,NEW.sources,NEW.declared_capabilities,NEW.declared_limits,NEW.notes,
      NEW.consulted_on,NEW.document_hash,NEW.created_at)
   IS DISTINCT FROM (OLD.id,OLD.catalog_code,OLD.manufacturer,OLD.model_sku,OLD.hardware_revision,OLD.region_code,OLD.profile_revision,OLD.category,
      OLD.source_category,OLD.market_lifecycle,OLD.proposed_priority,OLD.sources,OLD.declared_capabilities,OLD.declared_limits,OLD.notes,
      OLD.consulted_on,OLD.document_hash,OLD.created_at) THEN
   RAISE EXCEPTION 'perfil do catálogo não muda; crie nova revisão' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 NEW.document_hash:=encode(sha256(convert_to(jsonb_build_object('manufacturer',NEW.manufacturer,'sku',NEW.model_sku,
  'hardwareRevision',NEW.hardware_revision,'region',NEW.region_code,'revision',NEW.profile_revision,'category',NEW.category,
  'lifecycle',NEW.market_lifecycle,'sources',NEW.sources,'capabilities',NEW.declared_capabilities,'limits',NEW.declared_limits,
  'notes',NEW.notes,'consultedOn',NEW.consulted_on)::text,'UTF8')),'hex');
 NEW.created_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.access_model_profiles_guard() FROM PUBLIC;
CREATE TRIGGER access_model_profiles_guard BEFORE INSERT OR UPDATE ON platform.access_model_profiles
 FOR EACH ROW EXECUTE FUNCTION platform.access_model_profiles_guard();

-- Capacidades do contrato (documentada/observada/aprovada, null = desconhecido). Lista fechada de 12 nomes.
CREATE FUNCTION platform.access_capabilities_valid(caps jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT jsonb_typeof(caps)='object'
  AND (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(caps) k)=ARRAY['doorContactEvents','eventBackfill','faceDeletion','faceEnrollment',
   'localValidity','offlineRestriction','onlineDecisionBeforeRelay','passageEvents','perDoorRights','safeRecovery','scopedRemoteOpen','secureTransport']
  AND NOT EXISTS(SELECT 1 FROM jsonb_each(caps) e WHERE jsonb_typeof(e.value)<>'object'
   OR coalesce(jsonb_typeof(e.value->'documented'),'null') NOT IN ('boolean','null')
   OR coalesce(jsonb_typeof(e.value->'observed'),'null') NOT IN ('boolean','null')
   OR coalesce(jsonb_typeof(e.value->'approved'),'null') NOT IN ('boolean','null')
   OR (e.value->>'approved')='true') $$;
REVOKE ALL ON FUNCTION platform.access_capabilities_valid(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.access_capabilities_valid(jsonb) TO cp_api;

CREATE TABLE platform.access_homologations (
 id uuid PRIMARY KEY,
 model_profile_id uuid NOT NULL REFERENCES platform.access_model_profiles(id),
 homologation_revision integer NOT NULL DEFAULT 1 CHECK(homologation_revision>=1),
 firmware_exact text NOT NULL CHECK(firmware_exact ~ '^[A-Za-z0-9._+()-]{1,128}$'),
 adapter_name text NOT NULL CHECK(adapter_name ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
 adapter_version text NOT NULL CHECK(adapter_version ~ '^[A-Za-z0-9._+-]{1,64}$'),
 decision_mode text NOT NULL CHECK(decision_mode IN ('DEVICE_LOCAL_ACL','EDGE_ONLINE','PROVIDER_LOCAL','ASSISTED')),
 -- Decisão do responsável (31/10/2026): gateway local e serviço de nuvem, ambos parametrizáveis.
 topology text NOT NULL CHECK(topology IN ('LOCAL_GATEWAY','CLOUD_SERVICE')),
 contract_schema_version text NOT NULL CHECK(contract_schema_version ~ '^[A-Za-z0-9._-]{1,32}$'),
 -- Capacidades observadas no dossiê (aprovada nunca vem do cliente: approved_capabilities registra a decisão).
 capabilities jsonb NOT NULL CHECK(platform.access_capabilities_valid(capabilities)),
 limits jsonb NOT NULL CHECK(jsonb_typeof(limits)='object' AND length(limits::text)<=4096),
 limitations jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(limitations)='array' AND jsonb_array_length(limitations)<=100 AND length(limitations::text)<=16384),
 evidence_refs uuid[] NOT NULL DEFAULT '{}' CHECK(cardinality(evidence_refs)<=300),
 evidence_hash text NOT NULL DEFAULT '',
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 8 AND 1000),
 requested_by uuid NOT NULL REFERENCES platform.accounts(id),
 status text NOT NULL DEFAULT 'CANDIDATO_DOCUMENTADO' CHECK(status IN ('CANDIDATO_DOCUMENTADO','EM_ENSAIO','APROVADO_BANCADA','APROVADO_PILOTO',
  'HOMOLOGADO','REPROVADO','SUSPENSO','EXPIRADO')),
 approved_capabilities text[] NOT NULL DEFAULT '{}',
 restrictions jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(restrictions)='array' AND length(restrictions::text)<=16384),
 approved_at timestamptz,
 approved_by uuid REFERENCES platform.accounts(id),
 review_due_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(model_profile_id,firmware_exact,adapter_name,adapter_version,decision_mode,topology,homologation_revision),
 CHECK((status IN ('APROVADO_BANCADA','APROVADO_PILOTO','HOMOLOGADO'))=(approved_at IS NOT NULL AND approved_by IS NOT NULL) OR status IN ('SUSPENSO','EXPIRADO','REPROVADO'))
);
CREATE INDEX access_homologations_profile ON platform.access_homologations(model_profile_id,status);

CREATE TABLE platform.access_homologation_decisions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 homologation_id uuid NOT NULL REFERENCES platform.access_homologations(id),
 decision text NOT NULL CHECK(decision IN ('START_TRIAL','APPROVE_LAB','APPROVE_PILOT','APPROVE_PRODUCTION','REJECT','SUSPEND')),
 from_status text NOT NULL,
 to_status text NOT NULL,
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 8 AND 1000),
 evidence_refs uuid[] NOT NULL DEFAULT '{}' CHECK(cardinality(evidence_refs)<=300),
 approved_capabilities text[] NOT NULL DEFAULT '{}',
 restrictions jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(restrictions)='array' AND length(restrictions::text)<=16384),
 review_due_at timestamptz,
 decided_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX access_homologation_decisions_by ON platform.access_homologation_decisions(homologation_id,created_at);

-- Transições: CANDIDATO→EM_ENSAIO→APROVADO_BANCADA→APROVADO_PILOTO→HOMOLOGADO; REJECT de estado não final; SUSPEND de aprovado.
-- Aprovar exige evidência e só aprova capacidade observada no dossiê.
CREATE FUNCTION platform.homologation_next(current_status text,decision text) RETURNS text
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT CASE
  WHEN decision='START_TRIAL' AND current_status='CANDIDATO_DOCUMENTADO' THEN 'EM_ENSAIO'
  WHEN decision='APPROVE_LAB' AND current_status='EM_ENSAIO' THEN 'APROVADO_BANCADA'
  WHEN decision='APPROVE_PILOT' AND current_status='APROVADO_BANCADA' THEN 'APROVADO_PILOTO'
  WHEN decision='APPROVE_PRODUCTION' AND current_status='APROVADO_PILOTO' THEN 'HOMOLOGADO'
  WHEN decision='REJECT' AND current_status IN ('CANDIDATO_DOCUMENTADO','EM_ENSAIO','APROVADO_BANCADA','APROVADO_PILOTO','SUSPENSO') THEN 'REPROVADO'
  WHEN decision='SUSPEND' AND current_status IN ('APROVADO_BANCADA','APROVADO_PILOTO','HOMOLOGADO') THEN 'SUSPENSO'
  ELSE NULL END $$;
REVOKE ALL ON FUNCTION platform.homologation_next(text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.homologation_next(text,text) TO cp_api;

CREATE FUNCTION platform.access_homologation_decisions_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE h record;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'decisão de homologação não muda' USING ERRCODE='23514'; END IF;
 SELECT status,capabilities INTO h FROM platform.access_homologations WHERE id=NEW.homologation_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'homologação inexistente' USING ERRCODE='23503'; END IF;
 NEW.from_status:=h.status;
 NEW.to_status:=platform.homologation_next(h.status,NEW.decision);
 IF NEW.to_status IS NULL THEN RAISE EXCEPTION 'transição de homologação não permitida' USING ERRCODE='23514'; END IF;
 IF NEW.decision LIKE 'APPROVE\_%' THEN
  IF cardinality(NEW.evidence_refs)=0 THEN RAISE EXCEPTION 'aprovação exige evidência' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM unnest(NEW.approved_capabilities) c WHERE coalesce(h.capabilities->c->>'observed','')<>'true') THEN
   RAISE EXCEPTION 'só capacidade observada no dossiê pode ser aprovada' USING ERRCODE='23514';
  END IF;
 ELSIF cardinality(NEW.approved_capabilities)>0 THEN
  RAISE EXCEPTION 'só aprovação lista capacidades' USING ERRCODE='23514';
 END IF;
 NEW.created_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.access_homologation_decisions_guard() FROM PUBLIC;
CREATE TRIGGER access_homologation_decisions_guard BEFORE INSERT OR UPDATE ON platform.access_homologation_decisions
 FOR EACH ROW EXECUTE FUNCTION platform.access_homologation_decisions_guard();

-- Homologação: nasce CANDIDATO_DOCUMENTADO na versão 0, revisão seguinte da mesma combinação e hash das evidências pelo
-- banco. Conteúdo técnico imutável; estado só segue a decisão gravada nesta transação.
CREATE FUNCTION platform.access_homologations_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE d record;
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'CANDIDATO_DOCUMENTADO' OR NEW.version<>0 OR NEW.approved_at IS NOT NULL OR NEW.approved_by IS NOT NULL
    OR cardinality(NEW.approved_capabilities)>0 OR NEW.restrictions<>'[]'::jsonb THEN
   RAISE EXCEPTION 'homologação nasce como candidata' USING ERRCODE='23514';
  END IF;
  SELECT coalesce(max(homologation_revision),0)+1 INTO NEW.homologation_revision FROM platform.access_homologations
   WHERE model_profile_id=NEW.model_profile_id AND firmware_exact=NEW.firmware_exact AND adapter_name=NEW.adapter_name
    AND adapter_version=NEW.adapter_version AND decision_mode=NEW.decision_mode AND topology=NEW.topology;
  NEW.evidence_hash:=encode(sha256(convert_to(coalesce((SELECT string_agg(e::text,',' ORDER BY e) FROM unnest(NEW.evidence_refs) e),''),'UTF8')),'hex');
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.model_profile_id,NEW.homologation_revision,NEW.firmware_exact,NEW.adapter_name,NEW.adapter_version,NEW.decision_mode,
     NEW.topology,NEW.contract_schema_version,NEW.capabilities,NEW.limits,NEW.limitations,NEW.evidence_refs,NEW.evidence_hash,NEW.reason,
     NEW.requested_by,NEW.created_at)
  IS DISTINCT FROM (OLD.id,OLD.model_profile_id,OLD.homologation_revision,OLD.firmware_exact,OLD.adapter_name,OLD.adapter_version,OLD.decision_mode,
     OLD.topology,OLD.contract_schema_version,OLD.capabilities,OLD.limits,OLD.limitations,OLD.evidence_refs,OLD.evidence_hash,OLD.reason,
     OLD.requested_by,OLD.created_at) OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'conteúdo técnico da homologação não muda; crie nova revisão' USING ERRCODE='23514';
 END IF;
 SELECT * INTO d FROM platform.access_homologation_decisions WHERE homologation_id=NEW.id AND created_at>=now()
  ORDER BY created_at DESC,id DESC LIMIT 1;
 IF NOT FOUND OR d.from_status<>OLD.status OR d.to_status<>NEW.status THEN
  RAISE EXCEPTION 'estado da homologação só muda por decisão registrada' USING ERRCODE='23514';
 END IF;
 IF d.decision LIKE 'APPROVE\_%' THEN
  NEW.approved_at:=clock_timestamp(); NEW.approved_by:=d.decided_by; NEW.approved_capabilities:=d.approved_capabilities;
  NEW.restrictions:=d.restrictions; NEW.review_due_at:=d.review_due_at;
 ELSE
  NEW.approved_at:=OLD.approved_at; NEW.approved_by:=OLD.approved_by; NEW.approved_capabilities:=OLD.approved_capabilities;
  NEW.restrictions:=OLD.restrictions; NEW.review_due_at:=OLD.review_due_at;
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.access_homologations_guard() FROM PUBLIC;
CREATE TRIGGER access_homologations_guard BEFORE INSERT OR UPDATE ON platform.access_homologations
 FOR EACH ROW EXECUTE FUNCTION platform.access_homologations_guard();

-- Criação e decisões só terminam com o evento da trilha da plataforma do mesmo ator (conferido no COMMIT).
CREATE FUNCTION platform.access_homologation_audited() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE target uuid; actor uuid; expected text;
BEGIN
 IF TG_TABLE_NAME='access_homologations' THEN target:=NEW.id; actor:=NEW.requested_by; expected:='HOMOLOGATION_CREATED';
 ELSE target:=NEW.homologation_id; actor:=NEW.decided_by; expected:='HOMOLOGATION_'||NEW.decision; END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.platform_audit_log a WHERE a.action=expected AND a.actor_id=actor AND a.details->>'homologationId'=target::text) THEN
  RAISE EXCEPTION 'Homologação sem evento de auditoria da plataforma.' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION platform.access_homologation_audited() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER access_homologations_audited AFTER INSERT ON platform.access_homologations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.access_homologation_audited();
CREATE CONSTRAINT TRIGGER access_homologation_decisions_audited AFTER INSERT ON platform.access_homologation_decisions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.access_homologation_audited();

-- Leitura: plataforma (catálogo ou aprovação), gestão, portaria e MASTER de implantação consultam o estado.
CREATE FUNCTION platform.access_catalog_reader() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_platform_capability('access_catalog.manage') OR platform.has_platform_capability('homologation.approve')
  OR platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('homologation.request') $$;
REVOKE ALL ON FUNCTION platform.access_catalog_reader() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.access_catalog_reader() TO cp_api;

ALTER TABLE platform.access_model_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.access_model_profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.access_homologations ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.access_homologations FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.access_homologation_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.access_homologation_decisions FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.access_model_profiles,platform.access_homologations,platform.access_homologation_decisions FROM PUBLIC;
CREATE POLICY access_model_profiles_read ON platform.access_model_profiles FOR SELECT TO cp_api USING(platform.access_catalog_reader());
CREATE POLICY access_model_profiles_worker ON platform.access_model_profiles FOR SELECT TO cp_worker USING(true);
CREATE POLICY access_homologations_read ON platform.access_homologations FOR SELECT TO cp_api USING(platform.access_catalog_reader());
CREATE POLICY access_homologations_worker ON platform.access_homologations FOR SELECT TO cp_worker USING(true);
CREATE POLICY access_homologations_create ON platform.access_homologations FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('access_catalog.manage') AND requested_by=platform.current_actor_id());
CREATE POLICY access_homologations_decide ON platform.access_homologations FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('homologation.approve')) WITH CHECK(platform.has_platform_capability('homologation.approve'));
CREATE POLICY access_homologation_decisions_read ON platform.access_homologation_decisions FOR SELECT TO cp_api
 USING(platform.has_platform_capability('homologation.approve') OR platform.has_platform_capability('access_catalog.manage'));
CREATE POLICY access_homologation_decisions_insert ON platform.access_homologation_decisions FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('homologation.approve') AND decided_by=platform.current_actor_id());
GRANT SELECT ON platform.access_model_profiles,platform.access_homologations,platform.access_homologation_decisions TO cp_api;
GRANT INSERT(id,model_profile_id,firmware_exact,adapter_name,adapter_version,decision_mode,topology,contract_schema_version,capabilities,
 limits,limitations,evidence_refs,reason,requested_by) ON platform.access_homologations TO cp_api;
GRANT UPDATE(status,version) ON platform.access_homologations TO cp_api;
GRANT INSERT(id,homologation_id,decision,reason,evidence_refs,approved_capabilities,restrictions,review_due_at,decided_by)
 ON platform.access_homologation_decisions TO cp_api;
-- O gateway (worker) confere o perfil da combinação ao reconectar: leitura técnica, sem dados pessoais.
GRANT SELECT ON platform.access_model_profiles,platform.access_homologations TO cp_worker;

-- Trilha da plataforma: HOMOLOGATION_* com access_catalog.manage (criação) ou homologation.approve (decisões); a
-- conferência no COMMIT lê só esses eventos.
DROP POLICY platform_audit_insert ON platform.platform_audit_log;
CREATE POLICY platform_audit_insert ON platform.platform_audit_log FOR INSERT TO cp_api
 WITH CHECK(actor_id = platform.current_actor_id() AND (
  (platform.has_platform_capability('tenants.manage') AND action LIKE 'TENANT\_%')
  OR (platform.has_platform_capability('billing.manage') AND action LIKE 'BILLING\_%')
  OR (platform.has_platform_capability('outbox.operate') AND action LIKE 'OUTBOX\_%')
  OR (platform.has_platform_capability('technical_grants.manage') AND action LIKE 'TECHNICAL\_GRANT\_%')
  OR (platform.has_platform_capability('access_catalog.manage') AND action='HOMOLOGATION_CREATED')
  OR (platform.has_platform_capability('homologation.approve') AND action LIKE 'HOMOLOGATION\_%' AND action<>'HOMOLOGATION_CREATED')));
CREATE POLICY platform_audit_homologation_read ON platform.platform_audit_log FOR SELECT TO cp_api
 USING((platform.has_platform_capability('access_catalog.manage') OR platform.has_platform_capability('homologation.approve'))
  AND action LIKE 'HOMOLOGATION\_%');

-- Catálogo documental do pacote v1.1 (27 modelos, fontes oficiais consultadas em 30/10/2026). Gerado de
-- docs/integracoes/facial/pacote-v1.1/catalogo/modelos_candidatos.json e fontes_oficiais.json, sem alterar valores.
INSERT INTO platform.access_model_profiles(catalog_code,manufacturer,model_sku,category,source_category,proposed_priority,sources,declared_capabilities,declared_limits,notes,consulted_on) VALUES
('CI-M01','CONTROL_ID','iDFace Lite e Pro','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "CI01", "title": "Control iD iDFace", "url": "https://www.controlid.com.br/controle-de-acesso/idface/"}, {"consultedOn": "2026-10-30", "id": "CI06", "title": "Control iD Introdução à API", "url": "https://www.controlid.com.br/docs/access-api-pt/"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1 via módulo externo", "environment": "Instalação conforme manual", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["REST", "Ethernet", "Wiegand", "RS-485 ao módulo", "SIP conforme versão"]}'::jsonb,'{"details": {"conflito_faces_pro": [10000, 30000], "faces_lite_publicadas": 3000}, "facesHomologated": null, "facesPublished": null}'::jsonb,'Lite 3.000; página especifica Pro 30.000 e introdução ainda 10.000. Não assumir capacidade sem licença, hardware e firmware.','2026-10-30'::date),
('CI-M02','CONTROL_ID','iDFace Max','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "CI02", "title": "Control iD iDFace Max", "url": "https://www.controlid.com.br/controle-de-acesso/idface-max/"}, {"consultedOn": "2026-10-30", "id": "CI06", "title": "Control iD Introdução à API", "url": "https://www.controlid.com.br/docs/access-api-pt/"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "Relé interno e módulo externo; independência de portas a ensaiar", "environment": "Instalação conforme manual", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["REST", "Ethernet", "OSDP como periférico", "Wiegand no módulo", "PoE conforme ficha", "SIP Pro"]}'::jsonb,'{"details": {"opcoes_faces_licenca": [10000, 50000, 100000]}, "facesHomologated": null, "facesPublished": null}'::jsonb,'Licenças publicadas 10.000 / 50.000 / 100.000; número de faces efetivo depende da licença.','2026-10-30'::date),
('CI-M03','CONTROL_ID','iDBlock Next','FACE_TERMINAL','CATRACA_FACIAL','P1','[{"consultedOn": "2026-10-30", "id": "CI03", "title": "Control iD iDBlock Next", "url": "https://www.controlid.com.br/controle-de-acesso/idblock-next/"}, {"consultedOn": "2026-10-30", "id": "CI12", "title": "Control iD Particularidades dos terminais", "url": "https://www.controlid.com.br/docs/access-api-pt/particularidade-dos-produtos/particulariade-terminais-control-id/"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "Catraca com até 2 iDFace", "environment": "Interior", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["REST", "TCP/IP", "Monitor de giro"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": null}'::jsonb,'Página da catraca cita 3.000 Lite e 10.000 Pro; não herdar 30.000 do terminal avulso.','2026-10-30'::date),
('CI-M04','CONTROL_ID','iDBlock Balcão Facial','FACE_TERMINAL','CATRACA_FACIAL','P1','[{"consultedOn": "2026-10-30", "id": "CI04", "title": "Control iD iDBlock Balcão Facial", "url": "https://www.controlid.com.br/controle-de-acesso/idblock-balcao-facial/"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "Catraca com até 2 iDFace Max", "environment": "Conferir instalação e ficha do conjunto", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["REST", "TCP/IP", "Monitor de giro"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": 100000}'::jsonb,'Até 100.000 conforme modelo e licença; número declarado não é medição.','2026-10-30'::date),
('CI-M05','CONTROL_ID','iDBox V2','ACCESS_CONTROLLER','CONTROLADORA','P1','[{"consultedOn": "2026-10-30", "id": "CI05", "title": "Control iD iDBox V2", "url": "https://www.controlid.com.br/controle-de-acesso/idbox-v2/"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "4 portas / 4 relés", "environment": "Gabinete e condições conforme manual", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["REST", "Ethernet", "Wiegand", "RS-485", "OSDP Secure Channel declarado"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": null}'::jsonb,'Não reconhece face por si. Combinação com leitor e proteção ativa precisam ser homologadas.','2026-10-30'::date),
('HK-M01','HIKVISION','DS-K1T341AMF','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "HK01", "title": "DS-K1T341AMF, Datasheet V1.1, arquivo 08/03/2023.", "url": "https://www.hikvision.com/content/dam/hikvision/products/S000000001/S000000080/S000000103/S000000107/OFR000141/M000039878/Data_Sheet/DS-K1T341AMF-Face-Recognition-Terminal_Datasheet_V1.1_20230308.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "Respeitar manual, iluminação e condições reais; IP não dispensa projeto.", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["ISAPI", "ISUP 5.0", "SDK", "HTTPS", "Ethernet", "Wiegand"]}'::jsonb,'{"details": {"capacidade": {"cartoes": 3000, "digitais": 3000, "eventos": 150000, "faces": 3000}, "firmware_documentado_apenas": null, "variantes": ["DS-K1T341AM", "DS-K1T341AMF-S"]}, "facesHomologated": null, "facesPublished": 3000}'::jsonb,'Documento de 2023 não comprova continuidade comercial ou suporte. Não herdar RS-485, SIP ou OSDP de outros modelos.','2026-10-30'::date),
('HK-M02','HIKVISION','DS-K1T342MFWX','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "HK02", "title": "DS-K1T342MFWX, Datasheet, arquivo 06/05/2026.", "url": "https://assets.hikvision.com/prd/normal/all/doc/m000054133/DS-K1T342MFWX_Datasheet_20260506.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "Instalação conforme manual", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["ISAPI", "ISUP 5.0", "Push SDK", "SIP com servidores listados na ficha", "Ethernet", "Wi-Fi", "RS-485", "Wiegand"]}'::jsonb,'{"details": {"capacidade": {"cartoes": 3000, "digitais": 3000, "eventos": 150000, "faces": 1500}, "firmware_documentado_apenas": null, "variantes": ["DS-K1T342MFWX-E1", "DS-K1T342MWX"]}, "facesHomologated": null, "facesPublished": 1500}'::jsonb,'Capacidade deve incluir moradores, funcionários e visitantes válidos em cada destino. Não tratar -E1 como versão idêntica.','2026-10-30'::date),
('HK-M03','HIKVISION','DS-K1T343MWX','FACE_TERMINAL','TERMINAL_FACIAL','P1','[{"consultedOn": "2026-10-30", "id": "HK03", "title": "DS-K1T343MWX, página oficial global, sem data editorial.", "url": "https://securitylab.hikvision.com/en/products/Access-Control-Products/terminals/face-recognition-terminal/ds-k1t343mwx/"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "INTERNO", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["ISAPI", "ISUP 5.0", "Push SDK", "SIP com servidores listados na página", "Ethernet", "Wi-Fi", "RS-485", "Wiegand"]}'::jsonb,'{"details": {"capacidade": {"cartoes": 3000, "digitais": null, "eventos": null, "faces": 1500}, "firmware_documentado_apenas": "V4.48.40_260629", "variantes": ["DS-K1T343MFWX"]}, "facesHomologated": null, "facesPublished": 1500}'::jsonb,'Uso interno explícito; não atribuir IP65 por analogia. Firmware listado publicamente não é firmware homologado CondPulse.','2026-10-30'::date),
('HK-M04','HIKVISION','DS-K1T344MBWX-QRE1','FACE_TERMINAL','TERMINAL_FACIAL','P1','[{"consultedOn": "2026-10-30", "id": "HK04", "title": "DS-K1T344MBWX-QRE1, Datasheet, arquivo 25/07/2025.", "url": "https://assets.hikvision.com/prd/normal/all/doc/m000126763/DS-K1T344MBWX-QRE1_Datasheet_20250725.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "Instalação conforme manual", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["Ethernet", "Wi-Fi", "Bluetooth", "RS-485", "Wiegand somente entrada"]}'::jsonb,'{"details": {"capacidade": {"cartoes": 3000, "eventos": 150000, "faces": 3000, "pessoas": 3000}, "firmware_documentado_apenas": null, "variantes": []}, "facesHomologated": null, "facesPublished": 3000}'::jsonb,'Receber guia de API específico antes de implementar. QR suportado não comprova uso único ou token CondPulse. Wiegand é somente entrada na ficha; não projetar saída por inferência.','2026-10-30'::date),
('HK-M05','HIKVISION','DS-K1T671M-L','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "HK05", "title": "DS-K1T671M-L, ficha PT-BR, sem data editorial identificada.", "url": "https://www.hikvision.com/content/dam/hikvision/pt-br/DS-K1T671M-L-PTBR.PDF"}, {"consultedOn": "2026-10-30", "id": "HK06", "title": "DS-K1T671M-L, ficha global antiga, sem data editorial identificada.", "url": "https://www.hikvision.com/content/dam/hikvision/products/S000000001/S000000080/S000000103/S000000105/OFR000135/M000029752/Data_Sheet/DS-K1T671M-L.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "Instalação conforme manual", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["ISAPI", "ISUP 5.0", "SDK", "SIP", "Ethernet", "RS-485", "Wiegand"]}'::jsonb,'{"details": {"capacidade": {"cartoes": null, "conflito": {"fonte_HK05_PTBR": {"cartoes": 10000, "faces": 10000}, "fonte_HK06_global_antiga": {"cartoes": 6000, "faces": 6000}}, "eventos": 50000, "faces": null, "resolucao": "EXIGE_IDENTIFICACAO_HARDWARE_FIRMWARE_E_CONFIRMACAO_FABRICANTE"}, "firmware_documentado_apenas": null, "variantes": ["DS-K1T671M", "DS-K1T671MF-L"]}, "facesHomologated": null, "facesPublished": null}'::jsonb,'Capacidades conflitantes entre documentos oficiais do mesmo nome comercial. Não publicar capacidade unificada antes de resolver região, hardware e firmware.','2026-10-30'::date),
('HK-M06','HIKVISION','DS-K1T673DWX','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "HK07", "title": "DS-K1T673DWX, Datasheet, arquivo 28/04/2026.", "url": "https://assets.hikvision.com/prd/normal/all/doc/m000049932/DS-K1T673DWX_Datasheet_20260428.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "Instalação conforme manual", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["ISAPI", "ISUP 5.0", "Push SDK", "SIP com servidores listados na ficha", "OSDP V2.1.7, baud 19200", "Ethernet", "Wi-Fi", "RS-485", "Wiegand"]}'::jsonb,'{"details": {"capacidade": {"cartoes": 50000, "digitais_com_modulo": 10000, "eventos": 150000, "faces": 10000}, "firmware_documentado_apenas": null, "variantes": ["DS-K1T673DWX-E1", "DS-K1T673TDWX"]}, "facesHomologated": null, "facesPublished": 10000}'::jsonb,'OSDP documentado em ficha não comprova Secure Channel ou suporte no firmware instalado. Digitais requerem módulo opcional.','2026-10-30'::date),
('HK-M07','HIKVISION','DS-K1T673DWX-E1','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "HK08", "title": "DS-K1T673DWX-E1, Datasheet, arquivo 23/12/2025.", "url": "https://assets.hikvision.com/prd/normal/all/doc/m000173870/DS-K1T673DWX-E1_Datasheet_20251223.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "Instalação conforme manual", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["ISAPI", "ISUP 5.0", "OSDP, baud 9600/19200/38400", "Ethernet", "Wi-Fi", "RS-485", "Wiegand"]}'::jsonb,'{"details": {"capacidade": {"cartoes": 50000, "digitais_com_modulo": 10000, "eventos": 150000, "faces": 10000}, "firmware_documentado_apenas": null, "variantes": ["DS-K1T673DWX", "DS-K1T673TDWX-E1"]}, "facesHomologated": null, "facesPublished": 10000}'::jsonb,'Secure Channel precisa de teste e confirmação. Versão -E1 deve ter perfil próprio; não reutilizar cegamente firmware da versão base.','2026-10-30'::date),
('HK-M08','HIKVISION','DS-K1T680DW','FACE_TERMINAL','TERMINAL_FACIAL','P2','[{"consultedOn": "2026-10-30", "id": "HK09", "title": "DS-K1T680DW, Datasheet, arquivo 27/12/2023.", "url": "https://assets.hikvision.com/prd/public/all/doc/m000039971/DS-K1T680DW_Datasheet_20231227.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "Conferir proteção aplicável; IP não comprovado", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["ISAPI", "ISUP 5.0", "RS-485", "Wiegand", "Ethernet", "Wi-Fi"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": 100000}'::jsonb,'Alta capacidade apenas quando necessidade justificar. IP/IK não comprovados na ficha consultada.','2026-10-30'::date),
('DH-M01','DAHUA','DHI-ASI6214D-W','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "DH01", "title": "Dahua Brasil — DHI-ASI6214D-W", "url": "https://www.dahuasecurity.com/br/products/Access-Control/AI/Standalone/Lite/DHI-ASI6214D-W"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "interior", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["CGI", "ONVIF", "SDK/API", "OSDP"]}'::jsonb,'{"details": {"registros_publicados": 300000}, "facesHomologated": null, "facesPublished": 6000}'::jsonb,'RS-485/Wiegand compartilham porta; confirmar SKU e firmware','2026-10-30'::date),
('DH-M02','DAHUA','ASI6214J-MFW-V1','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "DH02", "title": "Dahua — ASI6214J-MFW-V1", "url": "https://www.dahuasecurity.com/products/access-control-%26-time-attendance/ai/standalone/pro/asi6214j-mfw-v1"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "IP65; instalação conforme manual", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["CGI", "ONVIF", "SDK/API", "OSDP"]}'::jsonb,'{"details": {"registros_publicados": 300000}, "facesHomologated": null, "facesPublished": 20000}'::jsonb,'Não transferir capacidade para modelo sem -V1','2026-10-30'::date),
('DH-M03','DAHUA','ASI8214S-W-V1','FACE_TERMINAL','TERMINAL_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "DH03", "title": "Dahua Brasil — ASI8214S-W-V1", "url": "https://www.dahuasecurity.com/br/products/Access-Control/AI/Standalone/ASI8214S-W-V1"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "interior/exterior", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["SDK/API", "OSDP"]}'::jsonb,'{"details": {"registros_publicados": 300000}, "facesHomologated": null, "facesPublished": 100000}'::jsonb,'Duas saídas alarme não são duas portas; ficha disponível omite -V1 no nome','2026-10-30'::date),
('DH-M04','DAHUA','ASI3214A-W-E1','FACE_TERMINAL','TERMINAL_FACIAL','P1','[{"consultedOn": "2026-10-30", "id": "DH04", "title": "Dahua — ASI3214A-W-E1", "url": "https://www.dahuasecurity.com/products/access-control-%26-time-attendance/ai/standalone/lite/asi3214a-w-e1"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "interior", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["CGI", "ONVIF", "SDK/API", "OSDP"]}'::jsonb,'{"details": {"registros_publicados": 50000}, "facesHomologated": null, "facesPublished": 3000}'::jsonb,'Não prometer QR sem validação, campos da ficha diferem','2026-10-30'::date),
('DH-M05','DAHUA','ASI3213G-MW','FACE_TERMINAL','TERMINAL_FACIAL','P1_parque_instalado','[{"consultedOn": "2026-10-30", "id": "DH05", "title": "Dahua — ASI3213G-MW", "url": "https://www.dahuasecurity.com/products/Access-ControlTime-Attendance/AI/Standalone/ASI3213G-MW"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "interior", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["CGI", "SDK", "OSDP"]}'::jsonb,'{"details": {"registros_publicados": 150000}, "facesHomologated": null, "facesPublished": 1500}'::jsonb,'Sem EOL explícito na fonte consultada; confirmar ciclo de suporte','2026-10-30'::date),
('DH-M06','DAHUA','ASI7213X-T1','FACE_TERMINAL','TERMINAL_FACIAL_LEGADO','P1_legado','[{"consultedOn": "2026-10-30", "id": "DH06", "title": "Dahua — ASI7213X-T1", "url": "https://www.dahuasecurity.com/products/Access-ControlTime-Attendance/AI/Standalone/ASI7213X-T1"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1", "environment": "interior", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["CGI", "ONVIF", "SDK", "OSDP"]}'::jsonb,'{"details": {"registros_publicados": 300000}, "facesHomologated": null, "facesPublished": 100000}'::jsonb,'Catálogo global descontinuado; não priorizar compra nova','2026-10-30'::date),
('DH-M07','DAHUA','ASC3202B','ACCESS_CONTROLLER','CONTROLADOR_NAO_FACIAL','P0_topologia','[{"consultedOn": "2026-10-30", "id": "DH07", "title": "Dahua Brasil — ASC3202B", "url": "https://www.dahuasecurity.com/br/products/Access-Control/Controllers/ASC3202B"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "2", "environment": "interior", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["CGI", "ONVIF", "SDK/API"]}'::jsonb,'{"details": {"registros_publicados": 300000}, "facesHomologated": null, "facesPublished": null}'::jsonb,'1 porta bidirecional ou 2 unidirecionais; 1.000 usuários modo master/10.000 modo sub; combinar leitor exige homologação própria','2026-10-30'::date),
('IB-M01','INTELBRAS','SS 3532 MF W','FACE_TERMINAL','CONTROLADOR_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "IB01", "title": "SS 3532 MF W — página de produto", "url": "https://www.intelbras.com/pt-br/controlador-de-acesso-facial-ss-3532-mf-w"}, {"consultedOn": "2026-10-30", "id": "IB02", "title": "SS 3532 MF W — ficha técnica abril/2026", "url": "https://backend.intelbras.com/sites/default/files/2026-04/Datasheet%20SS%203532%20MF%20W.pdf"}, {"consultedOn": "2026-10-30", "id": "IB04", "title": "Manual usuário SS 3532 MF W / SS 3542 MF W", "url": "https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/manual-usuario-pt-BR/manual_SS35x2_MF_W.html"}, {"consultedOn": "2026-10-30", "id": "IB19", "title": "Interface Web Bio-T — SS3532/3542 MF W", "url": "https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/pt-BR/manual_SS3532_SS3542_MFW_pt-BR.html?lang=pt"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1 relé de porta integrado; expansão XR 2201 condicionada a modo e topologia homologados", "environment": "Interno/externo conforme instalação", "homologatedFirmware": null, "ingressProtection": "IP55", "interfaces": ["API/CGI", "Ethernet", "Wi-Fi", "PoE 802.3af", "RS-485", "Wiegand", "OSDP para leitor auxiliar", "SIP padrão na ficha 2026"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": 10000}'::jsonb,'10.000 é capacidade publicada condicionada ao firmware V3.002.00IB000.0.R.250625; não medida. Não extrapolar a variante FE ou modelo sem W. Timeout on-line pode levar à decisão local: enforcement offline deve ser ensaiado.','2026-10-30'::date),
('IB-M02','INTELBRAS','SS 3542 MF W','FACE_TERMINAL','CONTROLADOR_FACIAL_COM_DIGITAL','P1','[{"consultedOn": "2026-10-30", "id": "IB03", "title": "SS 3542 MF W — página de produto", "url": "https://www.intelbras.com/pt-br/controlador-de-acesso-facial-com-biometria-digital-ss-3542-mf-w"}, {"consultedOn": "2026-10-30", "id": "IB04", "title": "Manual usuário SS 3532 MF W / SS 3542 MF W", "url": "https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/manual-usuario-pt-BR/manual_SS35x2_MF_W.html"}, {"consultedOn": "2026-10-30", "id": "IB05", "title": "Comparativo — Leitores Faciais e Telas Condominial/Corporativo", "url": "https://backend.intelbras.com/sites/default/files/2026-01/Comparativo%20-%20Leitores%20Faciais%20e%20Telas%20Condominial%20%20Corporativo.pdf"}, {"consultedOn": "2026-10-30", "id": "IB19", "title": "Interface Web Bio-T — SS3532/3542 MF W", "url": "https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/pt-BR/manual_SS3532_SS3542_MFW_pt-BR.html?lang=pt"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1 saída de contato seco; segunda saída mediante módulo e topologia a homologar", "environment": "Interno/externo conforme instalação", "homologatedFirmware": null, "ingressProtection": "IP55", "interfaces": ["API de integração Bio-T — contrato atual pendente", "Ethernet", "Wi-Fi", "PoE 802.3af", "RS-485", "Wiegand", "OSDP para leitor auxiliar", "SIP — validar versão"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": 10000}'::jsonb,'Página atual anuncia até 10.000 faces e digitais. Valor publicado, sem medição local e sem firmware homologado. Biometria digital e leitor auxiliar exigem ensaios próprios.','2026-10-30'::date),
('IB-M03','INTELBRAS','SS 5532 MF W','FACE_TERMINAL','CONTROLADOR_FACIAL','P0','[{"consultedOn": "2026-10-30", "id": "IB06", "title": "SS 5532 MF W — ficha técnica fevereiro/2026", "url": "https://backend.intelbras.com/sites/default/files/2026-02/Datasheet%20SS%205532%20MF%20W.pdf"}, {"consultedOn": "2026-10-30", "id": "IB07", "title": "SS 5532 MF W — ficha técnica novembro/2025", "url": "https://backend.intelbras.com/sites/default/files/2025-11/Datasheet%20SS%205532%20MF%20W.pdf"}, {"consultedOn": "2026-10-30", "id": "IB05", "title": "Comparativo — Leitores Faciais e Telas Condominial/Corporativo", "url": "https://backend.intelbras.com/sites/default/files/2026-01/Comparativo%20-%20Leitores%20Faciais%20e%20Telas%20Condominial%20%20Corporativo.pdf"}, {"consultedOn": "2026-10-30", "id": "IB20", "title": "Manual usuário SS5532/SS5532 V2/SS5542/SS5532 T W", "url": "https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/manual-usuario-pt-BR/manual_SS55x2_MF_T-W.html?lang=pt"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1 relé de porta integrado; expansão deve ser validada por revisão", "environment": "Interno/externo condicionado à revisão e instalação", "homologatedFirmware": null, "ingressProtection": "IP55 na documentação selecionada", "interfaces": ["API/CGI", "Ethernet", "Wi-Fi", "RS-485", "Wiegand", "OSDP para leitor auxiliar", "SIP com divergência documental"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": null}'::jsonb,'Publicações consultadas citam 50.000 faces, porém vinculam a capacidade a ramos diferentes: V1.000.00IB000.0.R.240725 (2026) e V3.002.00IB000.0.R.250625 (2025). faces=null até vincular SKU/hardware/firmware. SIP proprietário na ficha versus padrão no comparativo. Não equiparar versão V2.','2026-10-30'::date),
('IB-M04','INTELBRAS','SS 5542 MF W','FACE_TERMINAL','CONTROLADOR_FACIAL_COM_DIGITAL','P1','[{"consultedOn": "2026-10-30", "id": "IB08", "title": "SS 5542 MF W — ficha técnica janeiro/2026", "url": "https://backend.intelbras.com/sites/default/files/2026-01/Datasheet%20SS%205542%20MF%20W.pdf"}, {"consultedOn": "2026-10-30", "id": "IB20", "title": "Manual usuário SS5532/SS5532 V2/SS5542/SS5532 T W", "url": "https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/manual-usuario-pt-BR/manual_SS55x2_MF_T-W.html?lang=pt"}, {"consultedOn": "2026-10-30", "id": "IB05", "title": "Comparativo — Leitores Faciais e Telas Condominial/Corporativo", "url": "https://backend.intelbras.com/sites/default/files/2026-01/Comparativo%20-%20Leitores%20Faciais%20e%20Telas%20Condominial%20%20Corporativo.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1 relé de porta integrado; módulo adicional sujeito ao manual e ao ensaio", "environment": "Interno/externo conforme instalação", "homologatedFirmware": null, "ingressProtection": "IP55", "interfaces": ["API", "Ethernet", "Wi-Fi", "RS-485", "Wiegand", "OSDP para leitor auxiliar", "SIP proprietário na ficha 2026"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": 50000}'::jsonb,'50.000 faces e 10.000 digitais são valores publicados, condicionados ao firmware V1.000.00IB000.0.R.240725 na ficha. SIP deve ser confirmado diante de divergência do comparativo. Templates, pessoas e credenciais têm limites distintos.','2026-10-30'::date),
('IB-M05','INTELBRAS','SS 5531 MF EX','FACE_TERMINAL','CONTROLADOR_FACIAL','P1','[{"consultedOn": "2026-10-30", "id": "IB09", "title": "SS 5531 MF EX — ficha técnica julho/2026", "url": "https://backend.intelbras.com/sites/default/files/2026-07/Datasheet%20SS%205531%20MF%20EX.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1 relé de porta integrado; XR 2201 documentado para segurança/porta adicional, sujeito a validação", "environment": "Interno/externo conforme instalação", "homologatedFirmware": null, "ingressProtection": "IP55", "interfaces": ["API/CGI", "Ethernet", "RS-485", "Wiegand", "OSDP para leitor auxiliar", "SIP padrão na ficha julho/2026"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": 50000}'::jsonb,'50.000 faces é valor publicado. Modelo EX não equivale ao SS 5531 MF W. Sem Wi-Fi declarado na ficha selecionada. Alcance ampliado depende de firmware e não substitui ensaio no local.','2026-10-30'::date),
('IB-M06','INTELBRAS','SS 3531 MF LITE','FACE_TERMINAL','CONTROLADOR_FACIAL','P1','[{"consultedOn": "2026-10-30", "id": "IB10", "title": "SS 3531 MF LITE — ficha técnica abril/2026", "url": "https://backend.intelbras.com/sites/default/files/2026-04/Datasheet%20SS%203531%20MF%20Lite.pdf"}, {"consultedOn": "2026-10-30", "id": "IB11", "title": "SS 3531 MF LITE — ficha técnica setembro/2025", "url": "https://backend.intelbras.com/sites/default/files/2025-09/Datasheet%20SS%203531%20MF%20Lite_0.pdf"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1 relé de porta integrado; XR 2201 como módulo adicional sujeito à topologia validada", "environment": "Interno", "homologatedFirmware": null, "ingressProtection": null, "interfaces": ["API/CGI", "Ethernet", "RS-485/Wiegand compartilhado", "OSDP para leitor auxiliar", "SIP — revisão deve ser confirmada"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": 6000}'::jsonb,'6.000 faces é valor publicado, não medido. Não prometer Wi-Fi ou uso externo. Ficha 2026 diz SIP padrão; ficha 2025 diz proprietário. Nenhum grau IP foi atribuído.','2026-10-30'::date),
('IB-M07','INTELBRAS','SS 7530 FACE','FACE_TERMINAL','CONTROLADOR_FACIAL_PARQUE_EXISTENTE','P2','[{"consultedOn": "2026-10-30", "id": "IB12", "title": "SS 7530 FACE — ficha técnica 2020", "url": "https://backend.intelbras.com/sites/default/files/2020-06/Datasheet-SS-7530-FACE.01.20.pdf"}, {"consultedOn": "2026-10-30", "id": "IB13", "title": "SS 7530 FACE — Ajuda e downloads", "url": "https://www.intelbras.com/pt-br/ajuda-download/download/controlador-de-acesso-facial-ss-7530-face"}]'::jsonb,'{"approvedCapabilities": [], "benchStatus": "NAO_EXECUTADA", "doors": "1 controle de bloqueio; 2 relés de alarme não equivalem a 2 portas adicionais", "environment": "Interno/externo conforme manual e instalação", "homologatedFirmware": null, "ingressProtection": "IP65", "interfaces": ["CGI", "SDK/API histórico documentado", "Ethernet", "RS-485", "Wiegand", "OSDP", "ONVIF sem comprovação de gerenciamento facial por esse protocolo"]}'::jsonb,'{"details": {}, "facesHomologated": null, "facesPublished": 100000}'::jsonb,'Ficha 2020 declara 100.000 imagens faciais/usuários. Downloads distinguem SS75xx e SS75xx V2; não intercambiar firmware. Fim de vida e estoque não confirmados. Não confundir com SS 7530 MF FACE.','2026-10-30'::date);
