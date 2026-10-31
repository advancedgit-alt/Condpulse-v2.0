-- AC-004 (pacote Acesso Facial v1.1), parte 2: equipamento provisionado, canais nativos, vínculo físico versionado do
-- ponto (binding_revision) e aceite de instalação.
--  - cp.access_devices (CP-046) ganha instância provisionada estável, revisão de hardware, combinação homologada, papel e
--    modo de conexão por equipamento (LOCAL_GATEWAY ou CLOUD_SERVICE; null = padrão do condomínio, decisão D5). O MASTER de
--    implantação (installation.manage) passa a cadastrar e manter equipamentos; condomínio, conector e id externo nunca
--    mudam (troca de equipamento é nova instância). Conector continua só pela operação da plataforma.
--  - cp.device_channels: canal nativo (relé, contato, botoeira, sensor). Saída de alarme nunca vira atuador de acesso.
--  - cp.point_bindings: revisão imutável do vínculo ponto × atuador × leitores × sensores, com combinação homologada;
--    configuration_hash e binding_revision calculados pelo banco; no máximo um ACTIVE por ponto e por atuador.
--  - cp.installation_acceptances: aceite por revisão com assinatura técnica (installation.commission) e das regras pela
--    gestão (area.manage). Só aprova sem testes críticos pendentes/reprovados, com as duas assinaturas e combinações
--    APROVADO_PILOTO ou HOMOLOGADO vigentes. A ativação é atômica: bindings ACTIVE, anteriores SUPERSEDED e pontos
--    COMMISSIONED (o ponto só fica comissionado com binding ACTIVE de aceite aprovado — substitui a regra da V041).
-- Nada é apagado; RLS FORCE; conteúdo aprovado e assinaturas imutáveis.

-- 1. Equipamento provisionado (aditivo; linhas antigas recebem instância nova e versão 0).
ALTER TABLE cp.access_devices
 ADD COLUMN provisioned_instance_id uuid NOT NULL DEFAULT gen_random_uuid(),
 ADD COLUMN hardware_revision text CHECK(hardware_revision IS NULL OR length(btrim(hardware_revision)) BETWEEN 1 AND 64),
 ADD COLUMN homologation_id uuid REFERENCES platform.access_homologations(id),
 ADD COLUMN device_role text CHECK(device_role IS NULL OR device_role IN ('FACE_TERMINAL','CONTROLLER','READER','EXPANSION_MODULE')),
 ADD COLUMN connection_mode text CHECK(connection_mode IS NULL OR connection_mode IN ('LOCAL_GATEWAY','CLOUD_SERVICE')),
 ADD COLUMN registered_by uuid REFERENCES platform.accounts(id),
 ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK(version>=0);
ALTER TABLE cp.access_devices ADD CONSTRAINT access_devices_instance_unique UNIQUE(tenant_id,provisioned_instance_id);

CREATE FUNCTION cp.access_devices_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.version<>0 THEN RAISE EXCEPTION 'equipamento nasce ativo na versão 0' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM cp.access_connectors k WHERE k.tenant_id=NEW.tenant_id AND k.id=NEW.connector_id AND k.status='ACTIVE') THEN
   RAISE EXCEPTION 'conector inativo ou de outro condomínio' USING ERRCODE='23514';
  END IF;
  NEW.provisioned_instance_id:=gen_random_uuid(); NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.connector_id,NEW.external_id,NEW.provisioned_instance_id,NEW.created_at,NEW.registered_by)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.connector_id,OLD.external_id,OLD.provisioned_instance_id,OLD.created_at,OLD.registered_by)
   OR NEW.version<>OLD.version+1 OR OLD.status='RETIRED' THEN
  RAISE EXCEPTION 'equipamento: instância, conector e condomínio fixos; retirada final' USING ERRCODE='23514';
 END IF;
 -- Equipamento em vínculo ativo ou pendente (controlador ou leitor): só o nome muda; retirada, sentido, hardware, combinação,
 -- papel ou conexão exigem novo vínculo e novo comissionamento.
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 IF (NEW.status,NEW.direction,NEW.hardware_revision,NEW.homologation_id,NEW.device_role,NEW.connection_mode)
   IS DISTINCT FROM (OLD.status,OLD.direction,OLD.hardware_revision,OLD.homologation_id,OLD.device_role,OLD.connection_mode) AND EXISTS(SELECT 1 FROM cp.point_bindings b WHERE b.tenant_id=NEW.tenant_id AND b.status IN ('ACTIVE','PENDING_COMMISSION')
   AND NEW.id IN (b.controller_device_id,b.entry_reader_device_id,b.exit_reader_device_id)) THEN
  RAISE EXCEPTION 'equipamento em vínculo ativo ou pendente: crie novo vínculo' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_devices_guard() FROM PUBLIC;
CREATE TRIGGER access_devices_guard BEFORE INSERT OR UPDATE ON cp.access_devices FOR EACH ROW EXECUTE FUNCTION cp.access_devices_guard();

-- Leitura do equipamento e do conector também pelo MASTER de implantação.
DROP POLICY access_devices_read ON cp.access_devices;
CREATE POLICY access_devices_read ON cp.access_devices AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage')
  OR platform.has_technical_scope('installation.bind_channel') OR platform.has_technical_scope('installation.commission')
  OR EXISTS(SELECT 1 FROM cp.access_credentials c WHERE c.tenant_id=access_devices.tenant_id AND c.connector_id=access_devices.connector_id));
DROP POLICY access_connectors_read ON cp.access_connectors;
CREATE POLICY access_connectors_read ON cp.access_connectors AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage'));
CREATE POLICY access_devices_register ON cp.access_devices AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_technical_scope('installation.manage') AND registered_by=platform.current_actor_id());
CREATE POLICY access_devices_maintain ON cp.access_devices AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_technical_scope('installation.manage')) WITH CHECK(platform.has_technical_scope('installation.manage'));
GRANT INSERT(id,tenant_id,connector_id,external_id,label,direction,hardware_revision,homologation_id,device_role,connection_mode,registered_by)
 ON cp.access_devices TO cp_api;
GRANT UPDATE(label,direction,status,hardware_revision,homologation_id,device_role,connection_mode,version) ON cp.access_devices TO cp_api;
GRANT SELECT(provisioned_instance_id,homologation_id,device_role,connection_mode) ON cp.access_devices TO cp_worker;

-- 2. Canais nativos.
CREATE TABLE cp.device_channels (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 device_id uuid NOT NULL,
 native_namespace text NOT NULL CHECK(native_namespace ~ '^[a-z][a-z0-9_.-]{0,63}$'),
 external_channel text NOT NULL CHECK(external_channel ~ '^[A-Za-z0-9._:-]{1,64}$'),
 channel_kind text NOT NULL CHECK(channel_kind IN ('ACCESS_ACTUATOR','DOOR_CONTACT','EXIT_REQUEST','PASSAGE_SENSOR','ALARM_OUTPUT','OTHER')),
 limits jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(limits)='object' AND length(limits::text)<=2048),
 status text NOT NULL DEFAULT 'DISCOVERED' CHECK(status IN ('DISCOVERED','VERIFIED','DISABLED','RETIRED')),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,id,device_id),
 UNIQUE(tenant_id,device_id,native_namespace,external_channel),
 FOREIGN KEY(tenant_id,device_id) REFERENCES cp.access_devices(tenant_id,id)
);
CREATE INDEX device_channels_by_device ON cp.device_channels(tenant_id,device_id,status);

CREATE FUNCTION cp.device_channels_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.version<>0 OR NEW.status NOT IN ('DISCOVERED','VERIFIED') THEN RAISE EXCEPTION 'canal nasce descoberto ou verificado' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM cp.access_devices d WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.device_id AND d.status='ACTIVE') THEN
   RAISE EXCEPTION 'equipamento retirado' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.device_id,NEW.native_namespace,NEW.external_channel,NEW.created_by,NEW.created_at)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.device_id,OLD.native_namespace,OLD.external_channel,OLD.created_by,OLD.created_at)
   OR NEW.version<>OLD.version+1 OR OLD.status='RETIRED' THEN
  RAISE EXCEPTION 'canal: identidade nativa fixa; retirada final' USING ERRCODE='23514';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 -- Canal usado por vínculo ativo ou pendente não muda de função nem deixa de estar verificado sem novo vínculo.
 IF (NEW.channel_kind<>OLD.channel_kind OR NEW.status<>'VERIFIED') AND EXISTS(SELECT 1 FROM cp.point_bindings b
    WHERE b.tenant_id=NEW.tenant_id AND b.status IN ('ACTIVE','PENDING_COMMISSION') AND NEW.id IN (b.actuator_channel_id,b.door_contact_channel_id,
     b.exit_request_channel_id,b.passage_sensor_channel_id)) THEN
  RAISE EXCEPTION 'canal em vínculo ativo ou pendente' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.device_channels_guard() FROM PUBLIC;

-- 3. Aceite de instalação por revisão.
CREATE TABLE cp.installation_acceptances (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 installation_revision integer NOT NULL DEFAULT 1 CHECK(installation_revision>=1),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','TESTING','APPROVED','REJECTED','SUSPENDED','SUPERSEDED')),
 plant_ref text NOT NULL CHECK(plant_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 plant_hash text NOT NULL CHECK(plant_hash ~ '^[0-9a-f]{64}$'),
 topology_manifest_ref text NOT NULL CHECK(topology_manifest_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 topology_hash text NOT NULL CHECK(topology_hash ~ '^[0-9a-f]{64}$'),
 capability_envelope_hash text NOT NULL CHECK(capability_envelope_hash ~ '^[0-9a-f]{64}$'),
 test_suite_version text NOT NULL CHECK(test_suite_version ~ '^[A-Za-z0-9._-]{1,64}$'),
 test_report_ref text NOT NULL CHECK(test_report_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 critical_tests_pending integer NOT NULL CHECK(critical_tests_pending>=0),
 critical_tests_failed integer NOT NULL CHECK(critical_tests_failed>=0),
 emergency_test_ref text NOT NULL CHECK(emergency_test_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 rollback_plan_ref text NOT NULL CHECK(rollback_plan_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 revocation_checkpoint_ref text CHECK(revocation_checkpoint_ref IS NULL OR revocation_checkpoint_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 technical_grant_id uuid,
 technical_approver_account_id uuid REFERENCES platform.accounts(id),
 technical_signed_at timestamptz,
 rules_approver_membership_id uuid,
 rules_approver_account_id uuid REFERENCES platform.accounts(id),
 rules_signed_at timestamptz,
 effective_at timestamptz,
 review_due_at timestamptz,
 status_reason text CHECK(status_reason IS NULL OR length(btrim(status_reason)) BETWEEN 3 AND 300),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,installation_revision),
 FOREIGN KEY(tenant_id,technical_grant_id,technical_approver_account_id) REFERENCES cp.technical_grants(tenant_id,id,account_id),
 FOREIGN KEY(tenant_id,rules_approver_membership_id) REFERENCES cp.memberships(tenant_id,id),
 CHECK((technical_signed_at IS NULL)=(technical_approver_account_id IS NULL) AND (technical_signed_at IS NULL)=(technical_grant_id IS NULL)),
 CHECK((rules_signed_at IS NULL)=(rules_approver_account_id IS NULL) AND (rules_signed_at IS NULL)=(rules_approver_membership_id IS NULL)),
 CHECK(status<>'APPROVED' OR (technical_signed_at IS NOT NULL AND rules_signed_at IS NOT NULL AND critical_tests_pending=0
  AND critical_tests_failed=0 AND effective_at IS NOT NULL))
);
CREATE INDEX installation_acceptances_status ON cp.installation_acceptances(tenant_id,status,installation_revision);

-- 4. Vínculo físico versionado.
CREATE TABLE cp.point_bindings (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 point_id uuid NOT NULL,
 actuator_channel_id uuid NOT NULL,
 controller_device_id uuid NOT NULL,
 entry_reader_device_id uuid,
 exit_reader_device_id uuid,
 door_contact_channel_id uuid,
 exit_request_channel_id uuid,
 passage_sensor_channel_id uuid,
 homologation_id uuid NOT NULL REFERENCES platform.access_homologations(id),
 binding_revision integer NOT NULL DEFAULT 1 CHECK(binding_revision>=1),
 installation_acceptance_id uuid,
 -- Mapa fechado: sentido de cada leitor em relação ao ponto (FROM_TO = da origem para o destino).
 direction_map jsonb NOT NULL CHECK(jsonb_typeof(direction_map)='object'
  AND direction_map-'entryReader'-'exitReader'='{}'::jsonb
  AND coalesce(direction_map->>'entryReader','FROM_TO') IN ('FROM_TO','TO_FROM')
  AND coalesce(direction_map->>'exitReader','TO_FROM') IN ('FROM_TO','TO_FROM')),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PENDING_COMMISSION','ACTIVE','SUPERSEDED','SUSPENDED')),
 valid_from timestamptz,
 valid_until timestamptz,
 wiring_evidence_ref text NOT NULL CHECK(wiring_evidence_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 configuration_hash text NOT NULL DEFAULT '',
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,point_id,binding_revision),
 UNIQUE(tenant_id,id,point_id),
 UNIQUE(tenant_id,id,binding_revision),
 FOREIGN KEY(tenant_id,point_id) REFERENCES cp.access_points(tenant_id,id),
 FOREIGN KEY(tenant_id,actuator_channel_id,controller_device_id) REFERENCES cp.device_channels(tenant_id,id,device_id),
 FOREIGN KEY(tenant_id,entry_reader_device_id) REFERENCES cp.access_devices(tenant_id,id),
 FOREIGN KEY(tenant_id,exit_reader_device_id) REFERENCES cp.access_devices(tenant_id,id),
 FOREIGN KEY(tenant_id,door_contact_channel_id) REFERENCES cp.device_channels(tenant_id,id),
 FOREIGN KEY(tenant_id,exit_request_channel_id) REFERENCES cp.device_channels(tenant_id,id),
 FOREIGN KEY(tenant_id,passage_sensor_channel_id) REFERENCES cp.device_channels(tenant_id,id),
 FOREIGN KEY(tenant_id,installation_acceptance_id) REFERENCES cp.installation_acceptances(tenant_id,id),
 CHECK((status='ACTIVE')=(valid_from IS NOT NULL AND valid_until IS NULL) OR status IN ('SUPERSEDED','SUSPENDED')),
 CHECK(status NOT IN ('SUPERSEDED','SUSPENDED') OR valid_from IS NULL OR valid_until IS NOT NULL),
 CHECK(status IN ('DRAFT','SUPERSEDED') OR installation_acceptance_id IS NOT NULL)
);
CREATE UNIQUE INDEX point_bindings_one_active_point ON cp.point_bindings(tenant_id,point_id) WHERE status='ACTIVE';
CREATE UNIQUE INDEX point_bindings_one_active_actuator ON cp.point_bindings(tenant_id,actuator_channel_id) WHERE status='ACTIVE';
CREATE UNIQUE INDEX point_bindings_one_per_acceptance ON cp.point_bindings(tenant_id,installation_acceptance_id,point_id)
 WHERE installation_acceptance_id IS NOT NULL AND status IN ('PENDING_COMMISSION','ACTIVE');
CREATE INDEX point_bindings_point_time ON cp.point_bindings(tenant_id,point_id,valid_from);
CREATE INDEX point_bindings_actuator_time ON cp.point_bindings(tenant_id,actuator_channel_id,valid_from);

CREATE TRIGGER device_channels_guard BEFORE INSERT OR UPDATE ON cp.device_channels FOR EACH ROW EXECUTE FUNCTION cp.device_channels_guard();

-- Conferência física do vínculo, repetida na criação, na submissão ao aceite e na ativação: canal e equipamento podem ter
-- mudado enquanto o vínculo era rascunho. O lock por condomínio serializa com as mudanças de canal e equipamento.
CREATE FUNCTION cp.point_binding_check(b cp.point_bindings) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||b.tenant_id::text,0));
 IF NOT EXISTS(SELECT 1 FROM cp.device_channels c WHERE c.tenant_id=b.tenant_id AND c.id=b.actuator_channel_id AND c.channel_kind='ACCESS_ACTUATOR' AND c.status='VERIFIED')
   OR (b.door_contact_channel_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cp.device_channels c WHERE c.tenant_id=b.tenant_id AND c.id=b.door_contact_channel_id AND c.channel_kind='DOOR_CONTACT' AND c.status='VERIFIED'))
   OR (b.exit_request_channel_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cp.device_channels c WHERE c.tenant_id=b.tenant_id AND c.id=b.exit_request_channel_id AND c.channel_kind='EXIT_REQUEST' AND c.status='VERIFIED'))
   OR (b.passage_sensor_channel_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cp.device_channels c WHERE c.tenant_id=b.tenant_id AND c.id=b.passage_sensor_channel_id AND c.channel_kind='PASSAGE_SENSOR' AND c.status='VERIFIED')) THEN
  RAISE EXCEPTION 'função do canal não confere ou canal não verificado' USING ERRCODE='23514';
 END IF;
 IF (b.direction_map ? 'entryReader')<>(b.entry_reader_device_id IS NOT NULL) OR (b.direction_map ? 'exitReader')<>(b.exit_reader_device_id IS NOT NULL) THEN
  RAISE EXCEPTION 'sentido de cada leitor precisa estar no mapa' USING ERRCODE='23514';
 END IF;
 -- Falha fechada: ponto e equipamentos precisam estar visíveis e ativos.
 IF NOT EXISTS(SELECT 1 FROM cp.access_points p WHERE p.tenant_id=b.tenant_id AND p.id=b.point_id AND p.status<>'RETIRED')
   OR (SELECT count(*) FROM cp.access_devices d WHERE d.tenant_id=b.tenant_id AND d.status='ACTIVE'
    AND d.id IN (b.controller_device_id,b.entry_reader_device_id,b.exit_reader_device_id))
    <>(SELECT count(DISTINCT x) FROM unnest(ARRAY[b.controller_device_id,b.entry_reader_device_id,b.exit_reader_device_id]) x WHERE x IS NOT NULL) THEN
  RAISE EXCEPTION 'ponto retirado ou equipamento inativo' USING ERRCODE='23514';
 END IF;
END $$;
REVOKE ALL ON FUNCTION cp.point_binding_check(cp.point_bindings) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.point_binding_check(cp.point_bindings) TO cp_api;

-- Vínculo: função de cada canal conferida no banco; revisão e hash calculados; conteúdo físico imutável; transições fixas.
CREATE FUNCTION cp.point_bindings_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE acceptance_status text;
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' OR NEW.version<>0 OR NEW.installation_acceptance_id IS NOT NULL OR NEW.valid_from IS NOT NULL OR NEW.valid_until IS NOT NULL THEN
   RAISE EXCEPTION 'vínculo nasce rascunho' USING ERRCODE='23514';
  END IF;
  PERFORM cp.point_binding_check(NEW);
  SELECT coalesce(max(binding_revision),0)+1 INTO NEW.binding_revision FROM cp.point_bindings WHERE tenant_id=NEW.tenant_id AND point_id=NEW.point_id;
  NEW.configuration_hash:=encode(sha256(convert_to(jsonb_build_object('point',NEW.point_id,'revision',NEW.binding_revision,
   'actuator',NEW.actuator_channel_id,'controller',NEW.controller_device_id,'entryReader',NEW.entry_reader_device_id,'exitReader',NEW.exit_reader_device_id,
   'doorContact',NEW.door_contact_channel_id,'exitRequest',NEW.exit_request_channel_id,'passageSensor',NEW.passage_sensor_channel_id,
   'homologation',NEW.homologation_id,'directions',NEW.direction_map,'wiring',NEW.wiring_evidence_ref)::text,'UTF8')),'hex');
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.point_id,NEW.actuator_channel_id,NEW.controller_device_id,NEW.entry_reader_device_id,NEW.exit_reader_device_id,
     NEW.door_contact_channel_id,NEW.exit_request_channel_id,NEW.passage_sensor_channel_id,NEW.homologation_id,NEW.binding_revision,NEW.direction_map,
     NEW.wiring_evidence_ref,NEW.configuration_hash,NEW.created_by,NEW.created_at)
  IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.point_id,OLD.actuator_channel_id,OLD.controller_device_id,OLD.entry_reader_device_id,OLD.exit_reader_device_id,
     OLD.door_contact_channel_id,OLD.exit_request_channel_id,OLD.passage_sensor_channel_id,OLD.homologation_id,OLD.binding_revision,OLD.direction_map,
     OLD.wiring_evidence_ref,OLD.configuration_hash,OLD.created_by,OLD.created_at) OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'vínculo físico não muda; crie nova revisão' USING ERRCODE='23514';
 END IF;
 IF NOT ((OLD.status='DRAFT' AND NEW.status IN ('PENDING_COMMISSION','SUPERSEDED'))
   OR (OLD.status='PENDING_COMMISSION' AND NEW.status IN ('ACTIVE','SUPERSEDED'))
   OR (OLD.status='ACTIVE' AND NEW.status IN ('SUPERSEDED','SUSPENDED'))) THEN
  RAISE EXCEPTION 'transição de vínculo não permitida' USING ERRCODE='23514';
 END IF;
 IF OLD.installation_acceptance_id IS NOT NULL AND NEW.installation_acceptance_id IS DISTINCT FROM OLD.installation_acceptance_id THEN
  RAISE EXCEPTION 'aceite do vínculo não muda' USING ERRCODE='23514';
 END IF;
 -- O conjunto de vínculos de um aceite fecha na assinatura técnica: depois dela nada entra nem sai (salvo rejeição).
 IF NEW.status IN ('PENDING_COMMISSION','ACTIVE') THEN
  PERFORM cp.point_binding_check(NEW);
 END IF;
 IF NEW.status='PENDING_COMMISSION' THEN
  SELECT status INTO acceptance_status FROM cp.installation_acceptances WHERE tenant_id=NEW.tenant_id AND id=NEW.installation_acceptance_id
   AND technical_signed_at IS NULL;
  IF acceptance_status IS NULL OR acceptance_status NOT IN ('DRAFT','TESTING') THEN
   RAISE EXCEPTION 'vínculo só entra em aceite em preparação, antes da assinatura técnica' USING ERRCODE='23514';
  END IF;
 ELSIF OLD.status='PENDING_COMMISSION' AND NEW.status='SUPERSEDED' AND NOT EXISTS(SELECT 1 FROM cp.installation_acceptances a
   WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.installation_acceptance_id AND (a.technical_signed_at IS NULL OR a.status='REJECTED')) THEN
  RAISE EXCEPTION 'vínculo de aceite assinado só sai pela rejeição do aceite' USING ERRCODE='23514';
 ELSIF NEW.status='ACTIVE' THEN
  SELECT status INTO acceptance_status FROM cp.installation_acceptances WHERE tenant_id=NEW.tenant_id AND id=NEW.installation_acceptance_id;
  IF acceptance_status IS DISTINCT FROM 'APPROVED' THEN RAISE EXCEPTION 'vínculo só ativa com aceite aprovado' USING ERRCODE='23514'; END IF;
  NEW.valid_from:=now();
 ELSIF NEW.status IN ('SUPERSEDED','SUSPENDED') AND OLD.status='ACTIVE' THEN
  NEW.valid_until:=now();
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.point_bindings_guard() FROM PUBLIC;
CREATE TRIGGER point_bindings_guard BEFORE INSERT OR UPDATE ON cp.point_bindings FOR EACH ROW EXECUTE FUNCTION cp.point_bindings_guard();

-- Aceite: revisão sequencial; assinatura técnica com concessão vigente (installation.commission) e das regras pelo vínculo
-- de gestão da própria conta; conteúdo imutável depois da assinatura técnica; aprovação só com as duas assinaturas,
-- testes críticos zerados, ao menos um vínculo e todas as combinações aprovadas para piloto ou produção e vigentes.
CREATE FUNCTION cp.installation_acceptances_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' OR NEW.version<>0 OR NEW.technical_signed_at IS NOT NULL OR NEW.rules_signed_at IS NOT NULL OR NEW.effective_at IS NOT NULL THEN
   RAISE EXCEPTION 'aceite nasce em rascunho sem assinaturas' USING ERRCODE='23514';
  END IF;
  SELECT coalesce(max(installation_revision),0)+1 INTO NEW.installation_revision FROM cp.installation_acceptances WHERE tenant_id=NEW.tenant_id;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.installation_revision,NEW.created_by,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.installation_revision,OLD.created_by,OLD.created_at)
   OR NEW.version<>OLD.version+1 OR OLD.status IN ('REJECTED','SUPERSEDED') THEN
  RAISE EXCEPTION 'aceite: revisão fixa e estado final preservado' USING ERRCODE='23514';
 END IF;
 IF OLD.technical_signed_at IS NOT NULL AND (NEW.plant_ref,NEW.plant_hash,NEW.topology_manifest_ref,NEW.topology_hash,NEW.capability_envelope_hash,
     NEW.test_suite_version,NEW.test_report_ref,NEW.critical_tests_pending,NEW.critical_tests_failed,NEW.emergency_test_ref,NEW.rollback_plan_ref,
     NEW.revocation_checkpoint_ref,NEW.technical_grant_id,NEW.technical_approver_account_id,NEW.technical_signed_at,NEW.review_due_at)
   IS DISTINCT FROM (OLD.plant_ref,OLD.plant_hash,OLD.topology_manifest_ref,OLD.topology_hash,OLD.capability_envelope_hash,
     OLD.test_suite_version,OLD.test_report_ref,OLD.critical_tests_pending,OLD.critical_tests_failed,OLD.emergency_test_ref,OLD.rollback_plan_ref,
     OLD.revocation_checkpoint_ref,OLD.technical_grant_id,OLD.technical_approver_account_id,OLD.technical_signed_at,OLD.review_due_at) THEN
  RAISE EXCEPTION 'conteúdo assinado não muda; crie nova revisão' USING ERRCODE='23514';
 END IF;
 IF OLD.rules_signed_at IS NOT NULL AND (NEW.rules_approver_membership_id,NEW.rules_approver_account_id,NEW.rules_signed_at)
   IS DISTINCT FROM (OLD.rules_approver_membership_id,OLD.rules_approver_account_id,OLD.rules_signed_at) THEN
  RAISE EXCEPTION 'assinatura das regras não muda' USING ERRCODE='23514';
 END IF;
 IF OLD.technical_signed_at IS NULL AND NEW.technical_signed_at IS NOT NULL THEN
  IF NEW.technical_approver_account_id<>platform.current_actor_id() OR NOT platform.has_technical_scope('installation.commission')
    OR NOT EXISTS(SELECT 1 FROM cp.technical_grants g WHERE g.tenant_id=NEW.tenant_id AND g.id=NEW.technical_grant_id
     AND g.account_id=NEW.technical_approver_account_id AND g.status='ACTIVE' AND g.valid_from<=now() AND g.valid_until>now()
     AND 'installation.commission'=ANY(g.scopes)) THEN
   RAISE EXCEPTION 'assinatura técnica exige concessão vigente de comissionamento da própria conta' USING ERRCODE='42501';
  END IF;
  NEW.technical_signed_at:=clock_timestamp();
 END IF;
 IF OLD.rules_signed_at IS NULL AND NEW.rules_signed_at IS NOT NULL THEN
  -- A gestão assina o conteúdo técnico já fechado.
  IF OLD.technical_signed_at IS NULL THEN RAISE EXCEPTION 'regras só se assinam depois da assinatura técnica' USING ERRCODE='23514'; END IF;
  IF NEW.rules_approver_account_id<>platform.current_actor_id() OR NOT platform.has_capability('area.manage')
    OR NOT EXISTS(SELECT 1 FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.rules_approver_membership_id
     AND m.account_id=NEW.rules_approver_account_id AND m.role='MANAGER' AND m.status='ACTIVE' AND m.valid_from<=now()
     AND (m.valid_until IS NULL OR m.valid_until>now())) THEN
   RAISE EXCEPTION 'assinatura das regras exige vínculo de gestão vigente da própria conta' USING ERRCODE='42501';
  END IF;
  NEW.rules_signed_at:=clock_timestamp();
 END IF;
 -- A gestão só assina as regras; conteúdo e estado são do comissionamento técnico.
 IF NOT platform.has_technical_scope('installation.commission') AND (to_jsonb(NEW)-ARRAY['rules_approver_membership_id','rules_approver_account_id',
   'rules_signed_at','version','updated_at'])<>(to_jsonb(OLD)-ARRAY['rules_approver_membership_id','rules_approver_account_id','rules_signed_at','version','updated_at']) THEN
  RAISE EXCEPTION 'gestão só assina as regras do aceite' USING ERRCODE='42501';
 END IF;
 IF NEW.status<>OLD.status THEN
  IF NOT ((OLD.status='DRAFT' AND NEW.status IN ('TESTING','REJECTED')) OR (OLD.status='TESTING' AND NEW.status IN ('APPROVED','REJECTED'))
    OR (OLD.status='APPROVED' AND NEW.status IN ('SUSPENDED','SUPERSEDED')) OR (OLD.status='SUSPENDED' AND NEW.status='SUPERSEDED')) THEN
   RAISE EXCEPTION 'transição de aceite não permitida' USING ERRCODE='23514';
  END IF;
  IF NEW.status IN ('REJECTED','SUSPENDED','SUPERSEDED') AND NEW.status_reason IS NULL THEN
   RAISE EXCEPTION 'encerramento do aceite exige motivo' USING ERRCODE='23514';
  END IF;
  IF NEW.status='APPROVED' THEN
   IF NOT platform.has_technical_scope('installation.commission') THEN
    RAISE EXCEPTION 'aprovação exige comissionamento técnico' USING ERRCODE='42501';
   END IF;
   -- Falha fechada: cada vínculo precisa enxergar a própria combinação aprovada e vigente.
   IF NOT EXISTS(SELECT 1 FROM cp.point_bindings b WHERE b.tenant_id=NEW.tenant_id AND b.installation_acceptance_id=NEW.id AND b.status='PENDING_COMMISSION')
     OR EXISTS(SELECT 1 FROM cp.point_bindings b WHERE b.tenant_id=NEW.tenant_id AND b.installation_acceptance_id=NEW.id AND b.status='PENDING_COMMISSION'
      AND NOT EXISTS(SELECT 1 FROM platform.access_homologations h WHERE h.id=b.homologation_id AND h.status IN ('APROVADO_PILOTO','HOMOLOGADO')
       AND (h.review_due_at IS NULL OR h.review_due_at>now()))) THEN
    RAISE EXCEPTION 'aprovação exige vínculo com combinação aprovada para piloto ou produção' USING ERRCODE='23514';
   END IF;
   NEW.effective_at:=clock_timestamp();
  END IF;
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.installation_acceptances_guard() FROM PUBLIC;
CREATE TRIGGER installation_acceptances_guard BEFORE INSERT OR UPDATE ON cp.installation_acceptances
 FOR EACH ROW EXECUTE FUNCTION cp.installation_acceptances_guard();

-- Efeito atômico do aceite: aprovação ativa os vínculos (o ACTIVE anterior do ponto vira SUPERSEDED) e comissiona os pontos;
-- suspensão devolve os pontos do aceite a MAPPED (o gatilho do ponto suspende o vínculo); rejeição descarta os pendentes;
-- substituição só sem vínculo ativo.
CREATE FUNCTION cp.installation_acceptances_effects() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE pending record; changed bigint;
BEGIN
 IF NEW.status=OLD.status THEN RETURN NULL; END IF;
 IF NEW.status='APPROVED' THEN
  FOR pending IN SELECT id,point_id FROM cp.point_bindings WHERE tenant_id=NEW.tenant_id AND installation_acceptance_id=NEW.id AND status='PENDING_COMMISSION'
    ORDER BY point_id FOR UPDATE LOOP
   UPDATE cp.point_bindings SET status='SUPERSEDED',version=version+1 WHERE tenant_id=NEW.tenant_id AND point_id=pending.point_id AND status='ACTIVE';
   UPDATE cp.point_bindings SET status='ACTIVE',version=version+1 WHERE tenant_id=NEW.tenant_id AND id=pending.id;
   GET DIAGNOSTICS changed=ROW_COUNT;
   UPDATE cp.access_points SET status='COMMISSIONED',updated_by=platform.current_actor_id(),version=version+1
    WHERE tenant_id=NEW.tenant_id AND id=pending.point_id AND status<>'COMMISSIONED';
   -- Falha fechada: o vínculo precisa ter ativado e o ponto precisa estar comissionado e visível.
   IF changed<>1 OR NOT EXISTS(SELECT 1 FROM cp.access_points p WHERE p.tenant_id=NEW.tenant_id AND p.id=pending.point_id AND p.status='COMMISSIONED') THEN
    RAISE EXCEPTION 'ativação incompleta do vínculo ou do ponto' USING ERRCODE='42501';
   END IF;
  END LOOP;
 ELSIF NEW.status='SUSPENDED' THEN
  UPDATE cp.access_points p SET status='MAPPED',updated_by=platform.current_actor_id(),version=version+1
   WHERE p.tenant_id=NEW.tenant_id AND p.status='COMMISSIONED' AND EXISTS(SELECT 1 FROM cp.point_bindings b WHERE b.tenant_id=p.tenant_id
    AND b.point_id=p.id AND b.installation_acceptance_id=NEW.id AND b.status='ACTIVE');
  IF EXISTS(SELECT 1 FROM cp.point_bindings b WHERE b.tenant_id=NEW.tenant_id AND b.installation_acceptance_id=NEW.id AND b.status='ACTIVE') THEN
   RAISE EXCEPTION 'suspensão incompleta dos pontos do aceite' USING ERRCODE='42501';
  END IF;
 ELSIF NEW.status='REJECTED' THEN
  UPDATE cp.point_bindings SET status='SUPERSEDED',version=version+1
   WHERE tenant_id=NEW.tenant_id AND installation_acceptance_id=NEW.id AND status='PENDING_COMMISSION';
 ELSIF NEW.status='SUPERSEDED' AND EXISTS(SELECT 1 FROM cp.point_bindings b WHERE b.tenant_id=NEW.tenant_id AND b.installation_acceptance_id=NEW.id
   AND b.status='ACTIVE') THEN
  RAISE EXCEPTION 'aceite com vínculo ativo não é substituído' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.installation_acceptances_effects() FROM PUBLIC;
CREATE TRIGGER installation_acceptances_effects AFTER UPDATE ON cp.installation_acceptances
 FOR EACH ROW EXECUTE FUNCTION cp.installation_acceptances_effects();

-- Ponto comissionado: só com vínculo ACTIVE de aceite aprovado (substitui a regra provisória da V041).
CREATE OR REPLACE FUNCTION cp.access_points_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF (TG_OP='INSERT' OR (NEW.from_area_id,NEW.to_area_id) IS DISTINCT FROM (OLD.from_area_id,OLD.to_area_id))
   AND EXISTS(SELECT 1 FROM cp.access_areas a WHERE a.tenant_id=NEW.tenant_id AND a.id IN (NEW.from_area_id,NEW.to_area_id) AND a.status='RETIRED') THEN
  RAISE EXCEPTION 'ponto não liga área retirada' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>0 OR NEW.status NOT IN ('DRAFT','MAPPED') THEN RAISE EXCEPTION 'ponto nasce rascunho ou mapeado' USING ERRCODE='23514'; END IF;
  NEW.updated_by:=NEW.created_by; NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.code,NEW.created_by,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.code,OLD.created_by,OLD.created_at)
   OR NEW.version<>OLD.version+1 OR OLD.status='RETIRED' THEN
  RAISE EXCEPTION 'ponto: código fixo, versão sequencial e retirada final' USING ERRCODE='23514';
 END IF;
 IF NEW.status='COMMISSIONED' AND OLD.status<>'COMMISSIONED' AND NOT EXISTS(SELECT 1 FROM cp.point_bindings b
    JOIN cp.installation_acceptances a ON a.tenant_id=b.tenant_id AND a.id=b.installation_acceptance_id
    WHERE b.tenant_id=NEW.tenant_id AND b.point_id=NEW.id AND b.status='ACTIVE' AND a.status='APPROVED') THEN
  RAISE EXCEPTION 'ponto só fica comissionado com vínculo ativo de aceite aprovado' USING ERRCODE='23514';
 END IF;
 -- Mudança física de ponto comissionado o devolve a MAPPED, e o vínculo ativo é suspenso (novo comissionamento).
 IF OLD.status='COMMISSIONED' AND NEW.status='COMMISSIONED' AND (NEW.from_area_id,NEW.to_area_id,NEW.point_kind,NEW.flow_direction,NEW.egress_class)
   IS DISTINCT FROM (OLD.from_area_id,OLD.to_area_id,OLD.point_kind,OLD.flow_direction,OLD.egress_class) THEN
  NEW.status:='MAPPED';
 END IF;
 IF OLD.status='COMMISSIONED' AND NEW.status<>'COMMISSIONED' THEN
  UPDATE cp.point_bindings SET status='SUSPENDED',version=version+1 WHERE tenant_id=NEW.tenant_id AND point_id=NEW.id AND status='ACTIVE';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;

ALTER TABLE cp.device_channels ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.device_channels FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.point_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.point_bindings FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.installation_acceptances ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.installation_acceptances FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.device_channels,cp.point_bindings,cp.installation_acceptances FROM PUBLIC;
CREATE POLICY tenant_boundary ON cp.device_channels TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.point_bindings TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.installation_acceptances TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

CREATE FUNCTION cp.installation_technical() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage')
  OR platform.has_technical_scope('installation.bind_channel') OR platform.has_technical_scope('installation.commission') $$;
REVOKE ALL ON FUNCTION cp.installation_technical() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.installation_technical() TO cp_api;

-- Canais e vínculos: só o MASTER de implantação (o gestor não recebe canal, relé nem vínculo físico).
CREATE POLICY device_channels_read ON cp.device_channels AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.installation_technical());
CREATE POLICY device_channels_create ON cp.device_channels AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_technical_scope('installation.manage') AND created_by=platform.current_actor_id());
CREATE POLICY device_channels_change ON cp.device_channels AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_technical_scope('installation.manage')) WITH CHECK(platform.has_technical_scope('installation.manage'));
-- Leitura dos vínculos também pelo ponto comissionado (o gatilho de pontos suspende o vínculo ativo).
CREATE POLICY point_bindings_read ON cp.point_bindings AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.installation_technical());
CREATE POLICY point_bindings_create ON cp.point_bindings AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_technical_scope('installation.bind_channel') AND created_by=platform.current_actor_id());
CREATE POLICY point_bindings_change ON cp.point_bindings AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_technical_scope('installation.bind_channel') OR platform.has_technical_scope('installation.commission')
  OR platform.has_technical_scope('installation.manage'))
 WITH CHECK(platform.has_technical_scope('installation.bind_channel') OR platform.has_technical_scope('installation.commission')
  OR platform.has_technical_scope('installation.manage'));
-- Aceite: técnico cria e assina; a gestão lê e assina as regras.
CREATE POLICY installation_acceptances_read ON cp.installation_acceptances AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.installation_technical() OR platform.has_capability('area.manage'));
CREATE POLICY installation_acceptances_create ON cp.installation_acceptances AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_technical_scope('installation.commission') AND created_by=platform.current_actor_id());
CREATE POLICY installation_acceptances_change ON cp.installation_acceptances AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_technical_scope('installation.commission') OR platform.has_capability('area.manage'))
 WITH CHECK(platform.has_technical_scope('installation.commission') OR platform.has_capability('area.manage'));

GRANT SELECT ON cp.device_channels,cp.point_bindings,cp.installation_acceptances TO cp_api;
GRANT INSERT(id,tenant_id,device_id,native_namespace,external_channel,channel_kind,limits,status,created_by) ON cp.device_channels TO cp_api;
GRANT UPDATE(channel_kind,limits,status,version) ON cp.device_channels TO cp_api;
GRANT INSERT(id,tenant_id,point_id,actuator_channel_id,controller_device_id,entry_reader_device_id,exit_reader_device_id,door_contact_channel_id,
 exit_request_channel_id,passage_sensor_channel_id,homologation_id,direction_map,wiring_evidence_ref,created_by) ON cp.point_bindings TO cp_api;
GRANT UPDATE(status,installation_acceptance_id,version) ON cp.point_bindings TO cp_api;
GRANT INSERT(id,tenant_id,plant_ref,plant_hash,topology_manifest_ref,topology_hash,capability_envelope_hash,test_suite_version,test_report_ref,
 critical_tests_pending,critical_tests_failed,emergency_test_ref,rollback_plan_ref,revocation_checkpoint_ref,review_due_at,created_by)
 ON cp.installation_acceptances TO cp_api;
GRANT UPDATE(status,plant_ref,plant_hash,topology_manifest_ref,topology_hash,capability_envelope_hash,test_suite_version,test_report_ref,
 critical_tests_pending,critical_tests_failed,emergency_test_ref,rollback_plan_ref,revocation_checkpoint_ref,review_due_at,technical_grant_id,
 technical_approver_account_id,technical_signed_at,rules_approver_membership_id,rules_approver_account_id,rules_signed_at,status_reason,version)
 ON cp.installation_acceptances TO cp_api;
-- Worker/gateway: topologia ativa e revisão, sem referências de projeto.
GRANT SELECT(id,tenant_id,device_id,native_namespace,external_channel,channel_kind,limits,status) ON cp.device_channels TO cp_worker;
GRANT SELECT(id,tenant_id,point_id,actuator_channel_id,controller_device_id,entry_reader_device_id,exit_reader_device_id,door_contact_channel_id,
 exit_request_channel_id,passage_sensor_channel_id,homologation_id,binding_revision,direction_map,status,valid_from,valid_until,configuration_hash)
 ON cp.point_bindings TO cp_worker;

-- O comissionamento técnico atualiza o estado do ponto pelo aceite (o gatilho do ponto exige vínculo ativo aprovado).
DROP POLICY access_points_change ON cp.access_points;
CREATE POLICY access_points_change ON cp.access_points AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_technical_scope('installation.manage') OR platform.has_technical_scope('installation.commission'))
 WITH CHECK((platform.has_technical_scope('installation.manage') OR platform.has_technical_scope('installation.commission'))
  AND updated_by=platform.current_actor_id());
-- Quem vincula ou comissiona lê o mapa (o comissionamento atualiza o ponto).
CREATE OR REPLACE FUNCTION cp.access_map_reader() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_capability('area.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage')
  OR platform.has_technical_scope('installation.bind_channel') OR platform.has_technical_scope('installation.commission') $$;
-- Quem vincula ou comissiona enxerga a combinação homologada (a aprovação do aceite falha fechada se não enxergar).
CREATE OR REPLACE FUNCTION platform.access_catalog_reader() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_platform_capability('access_catalog.manage') OR platform.has_platform_capability('homologation.approve')
  OR platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('homologation.request')
  OR platform.has_technical_scope('installation.manage') OR platform.has_technical_scope('installation.bind_channel')
  OR platform.has_technical_scope('installation.commission') $$;

-- Trilha: DEVICE_/CHANNEL_/BINDING_/INSTALLATION_ do MASTER de implantação; INSTALLATION_RULES_SIGNED da gestão.
CREATE POLICY audit_installation_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK((action NOT LIKE 'DEVICE\_%' AND action NOT LIKE 'CHANNEL\_%' AND action NOT LIKE 'BINDING\_%' AND action NOT LIKE 'INSTALLATION\_%')
  OR (actor_id=platform.current_actor_id() AND (cp.installation_technical()
   OR (action='INSTALLATION_RULES_SIGNED' AND platform.has_capability('area.manage')))));
