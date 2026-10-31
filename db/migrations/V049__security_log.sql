-- CPX-006 a CPX-011 (PRD complementar Segurança v1.0, seção 5; ADR-0011 item 3): Log de Segurança do condomínio.
--  - cp.security_log: trilha única de prova, só acrescenta. Sequência por condomínio sem buracos e cadeia SHA-256
--    (hash = sha256(prev_hash ‖ campos canônicos "cpsl1")) calculadas no banco, sob a trava da linha de
--    cp.security_log_heads do condomínio. recorded_at = clock_timestamp() depois da trava (cresce com seq);
--    occurred_at é o relógio do banco, exceto para equipamento (horário informado; R5.5, o log nunca reordena).
--  - Autoria (conta e papel) sempre do contexto da transação, nunca do cliente. Correção é evento novo (corrects_id
--    e motivo); nada é alterado nem apagado, nem pelo dono da tabela (gatilho), salvo intervenção com gatilhos
--    desligados (session_replication_role=replica), que a verificação de integridade detecta.
--  - Gravação na mesma transação (R5.4): gatilhos de restrição DEFERRABLE INITIALLY DEFERRED nas fontes (V014, V026,
--    V027, V028, V033, V037, V044, V047, V048 e vínculos). Rodam no COMMIT, depois de todas as travas de negócio, então
--    a trava do log é sempre a última (sem ciclo de impasse com as travas das fontes); se o log falha, o COMMIT falha
--    e a operação inteira é desfeita.
--  - platform.security_log_anchors: âncora diária (último hash do dia, fuso America/Sao_Paulo) gravada pelo worker;
--    cp.security_log_verify recalcula a cadeia e confere âncoras e cabeça.
--  - Leitura: security.log.read.tenant (MANAGER, próprio condomínio, AAL2). security.log.read e security.log.export
--    (parceiro, CPX-003) já valem nas políticas e só passam a ser publicadas pela hierarquia de parceiros, sem migration.
--  - partner_id fica sem chave estrangeira nesta migration: platform.partners nasce no PR da hierarquia (CPX-001), que
--    acrescenta a FK. Retenção ainda indefinida (DECISOES_COMPLEMENTAR.md): nenhuma rotina de expurgo.

-- 1. Catálogo, cabeça da cadeia e log.
CREATE FUNCTION cp.security_log_event_types() RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$ SELECT ARRAY[
 -- Catálogo inicial do PRD (5.2).
 'ENTRY_RECORDED','EXIT_RECORDED','ENTRY_DENIED','EVENT_CORRECTED','FACIAL_MATCH','FACIAL_NO_MATCH','QR_USED','QR_REJECTED',
 'REMOTE_OPEN','LOCAL_OPEN','APP_OPEN','DOUBLE_CONFIRM_GIVEN','INTERLOCK_BLOCKED','DOOR_HELD_OPEN','FORCED_OPEN',
 'STAY_OPENED','STAY_DUE','STAY_OVERDUE_L1','STAY_OVERDUE_L2','STAY_CLOSED','STAY_JUSTIFIED',
 'PANIC_TRIGGERED','DURESS_CODE_USED','INCIDENT_OPENED','INCIDENT_ASSIGNED','INCIDENT_CLOSED',
 'BLOCKLIST_ADDED','BLOCKLIST_APPROVED','BLOCKLIST_REMOVED','BLOCKLIST_HIT',
 'SHIFT_STARTED','SHIFT_HANDOVER_SUBMITTED','SHIFT_HANDOVER_ACCEPTED','CHECKLIST_DONE','PATROL_CHECKPOINT','PATROL_MISSED',
 'QUEUE_ASSIGNED','SLA_BREACHED',
 'DEVICE_OFFLINE','DEVICE_ONLINE','DEVICE_COMMAND_SENT','DEVICE_COMMAND_FAILED','MAINTENANCE_MODE_ON','MAINTENANCE_MODE_OFF',
 'CREDENTIAL_PUBLISHED','CREDENTIAL_REVOKED',
 'LOGIN_SUCCEEDED','LOGIN_FAILED','MFA_CHALLENGED','CONTEXT_SWITCHED','ROLE_GRANTED','ROLE_REVOKED','SCHEDULE_ASSIGNED',
 'POLICY_PUBLISHED','RULE_CHANGED','TECH_GRANT_ISSUED','PARTNER_CONTRACT_CHANGED',
 'SENSITIVE_VIEWED','LOG_EXPORTED','EVIDENCE_EXPORTED','REMOTE_OPEN_REVIEWED',
 -- Acréscimos para as fontes existentes (docs/LOG_SEGURANCA.md): o que o equipamento informou, sem inferir passagem.
 'ACCESS_AUTHORIZED','PASSAGE_DETECTED','DOOR_OPENED','RELAY_TRIGGERED','CREDENTIAL_APPLIED','CREDENTIAL_REJECTED',
 'DEVICE_EVENT_QUARANTINED','OCCURRENCE_RECORDED','OCCURRENCE_RESOLVED','RIGHTS_REVOKED','RIGHTS_PUBLICATION_QUEUED',
 'EDGE_REPORT_RECEIVED','TECH_GRANT_REVOKED'] $$;
REVOKE ALL ON FUNCTION cp.security_log_event_types() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_event_types() TO cp_api,cp_worker,cp_onboarding;

-- Nenhuma chave (em qualquer nível) que sugira dado biométrico (R5.9).
CREATE FUNCTION cp.security_log_details_valid(details jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT details IS NOT NULL AND jsonb_typeof(details)='object' AND octet_length(details::text)<=4096
  AND NOT jsonb_path_exists(details,'lax $.** ? (@.type() == "object").keyvalue() ? (@.key like_regex "biometr|template|embedding|faceprint|face_?(image|photo|vector)|fingerprint|minuti|iris|selfie|photo|image" flag "i")') $$;
REVOKE ALL ON FUNCTION cp.security_log_details_valid(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_details_valid(jsonb) TO cp_api,cp_worker,cp_onboarding;

CREATE TABLE cp.security_log_heads (
 tenant_id uuid PRIMARY KEY REFERENCES platform.tenants(id),
 last_seq bigint NOT NULL DEFAULT 0 CHECK(last_seq>=0),
 last_hash text NOT NULL DEFAULT repeat('0',64) CHECK(last_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE cp.security_log (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 -- Parceiro operador no momento. FK para platform.partners entra com a hierarquia (CPX-001), em migration própria.
 partner_id uuid,
 seq bigint NOT NULL CHECK(seq>=1),
 occurred_at timestamptz NOT NULL CHECK(isfinite(occurred_at)),
 recorded_at timestamptz NOT NULL,
 clock_skew_ms bigint CHECK(clock_skew_ms IS NULL OR clock_skew_ms BETWEEN -86400000 AND 86400000),
 event_type text NOT NULL CHECK(event_type=ANY(cp.security_log_event_types())),
 severity text NOT NULL CHECK(severity IN ('INFO','ATTENTION','CRITICAL')),
 source text NOT NULL CHECK(source IN ('CONCIERGE','REMOTE_CENTRAL','RESIDENT_APP','FACIAL','QR','DEVICE','SYSTEM','PLATFORM','MANAGEMENT')),
 actor_account_id uuid,
 actor_role text CHECK(actor_role IS NULL OR actor_role ~ '^[A-Z][A-Z_]{1,39}$'),
 session_id text CHECK(session_id IS NULL OR session_id ~ '^[A-Za-z0-9._:-]{8,128}$'),
 subject_type text CHECK(subject_type IS NULL OR subject_type ~ '^[A-Z][A-Z_]{1,39}$'),
 subject_id uuid,
 unit_id uuid,
 point_id uuid,
 device_id uuid,
 reason text CHECK(reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
 evidence_ref text CHECK(evidence_ref IS NULL OR evidence_ref ~ '^[A-Za-z0-9._:/-]{4,300}$'),
 correlation_id uuid,
 trace_id text CHECK(trace_id IS NULL OR trace_id ~ '^[A-Za-z0-9._:-]{1,64}$'),
 -- Evento corrigido (R5.2). Sem FK: correção de registro anterior a esta migration aponta para o id da fonte.
 corrects_id uuid CHECK(corrects_id IS NULL OR corrects_id<>id),
 details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(cp.security_log_details_valid(details)),
 prev_hash text NOT NULL CHECK(prev_hash ~ '^[0-9a-f]{64}$'),
 hash text NOT NULL CHECK(hash ~ '^[0-9a-f]{64}$'),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,seq),
 CHECK(event_type<>'EVENT_CORRECTED' OR (corrects_id IS NOT NULL AND reason IS NOT NULL)),
 CHECK(event_type NOT IN ('REMOTE_OPEN','STAY_JUSTIFIED','BLOCKLIST_REMOVED','LOG_EXPORTED','EVIDENCE_EXPORTED') OR reason IS NOT NULL)
);
CREATE INDEX security_log_type ON cp.security_log(tenant_id,event_type,seq DESC);
CREATE INDEX security_log_actor ON cp.security_log(tenant_id,actor_account_id,seq DESC) WHERE actor_account_id IS NOT NULL;
CREATE INDEX security_log_recorded ON cp.security_log(tenant_id,recorded_at);
CREATE INDEX security_log_subject ON cp.security_log(tenant_id,subject_id) WHERE subject_id IS NOT NULL;

-- 2. Forma canônica e cadeia. Mesma função para gravar e para verificar.
CREATE FUNCTION cp.security_log_digest(prev text,e cp.security_log) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT encode(sha256(convert_to(concat_ws('|','cpsl1',prev,jsonb_build_array(e.id,e.tenant_id,e.partner_id,e.seq,
  to_char(e.occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  to_char(e.recorded_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  e.clock_skew_ms,e.event_type,e.severity,e.source,e.actor_account_id,e.actor_role,e.session_id,e.subject_type,e.subject_id,
  e.unit_id,e.point_id,e.device_id,e.reason,e.evidence_ref,e.correlation_id,e.trace_id,e.corrects_id,e.details)::text),'UTF8')),'hex') $$;
REVOKE ALL ON FUNCTION cp.security_log_digest(text,cp.security_log) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_digest(text,cp.security_log) TO cp_api,cp_worker,cp_onboarding;

-- Papel do ator no condomínio (equipe antes de unidade); plataforma e escopo técnico quando não há vínculo.
CREATE FUNCTION cp.security_log_actor_role(target_tenant uuid) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT CASE WHEN platform.current_actor_id() IS NULL THEN NULL ELSE coalesce(
  (SELECT m.role FROM cp.memberships m WHERE m.tenant_id=target_tenant AND m.account_id=platform.current_actor_id()
    AND m.status='ACTIVE' AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   ORDER BY (m.unit_id IS NOT NULL),CASE m.role WHEN 'MANAGER' THEN 0 WHEN 'CONCIERGE' THEN 1 WHEN 'MAINTENANCE' THEN 2
    WHEN 'SUPPORT' THEN 3 WHEN 'RESPONSIBLE' THEN 4 WHEN 'OWNER' THEN 5 WHEN 'RESIDENT' THEN 6 ELSE 7 END LIMIT 1),
  CASE WHEN nullif(current_setting('app.platform_capabilities',true),'') IS NOT NULL THEN 'PLATFORM_ADMIN'
   WHEN nullif(current_setting('app.technical_scopes',true),'') IS NOT NULL THEN 'TECHNICAL_GRANT' END) END $$;
REVOKE ALL ON FUNCTION cp.security_log_actor_role(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_actor_role(uuid) TO cp_api,cp_worker,cp_onboarding;

CREATE FUNCTION cp.security_log_chain() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE head record;
BEGIN
 SELECT h.last_seq,h.last_hash INTO head FROM cp.security_log_heads h WHERE h.tenant_id=NEW.tenant_id FOR UPDATE;
 IF NOT FOUND THEN
  INSERT INTO cp.security_log_heads(tenant_id) VALUES(NEW.tenant_id) ON CONFLICT DO NOTHING;
  SELECT h.last_seq,h.last_hash INTO head FROM cp.security_log_heads h WHERE h.tenant_id=NEW.tenant_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'cabeça do log de segurança indisponível' USING ERRCODE='42501'; END IF;
 END IF;
 NEW.id:=coalesce(NEW.id,gen_random_uuid());
 NEW.recorded_at:=clock_timestamp();
 NEW.occurred_at:=CASE WHEN NEW.source='DEVICE' AND NEW.occurred_at IS NOT NULL THEN NEW.occurred_at ELSE NEW.recorded_at END;
 NEW.clock_skew_ms:=CASE WHEN NEW.source='DEVICE' THEN NEW.clock_skew_ms END;
 NEW.actor_account_id:=platform.current_actor_id();
 NEW.actor_role:=cp.security_log_actor_role(NEW.tenant_id);
 NEW.details:=coalesce(NEW.details,'{}'::jsonb);
 NEW.seq:=head.last_seq+1;
 NEW.prev_hash:=head.last_hash;
 NEW.hash:=cp.security_log_digest(NEW.prev_hash,NEW);
 UPDATE cp.security_log_heads SET last_seq=NEW.seq,last_hash=NEW.hash WHERE tenant_id=NEW.tenant_id;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_chain() FROM PUBLIC;
CREATE TRIGGER security_log_chain BEFORE INSERT ON cp.security_log FOR EACH ROW EXECUTE FUNCTION cp.security_log_chain();

-- Só acrescenta, para qualquer role (inclusive o dono). Intervenção só com gatilhos desligados, e a verificação detecta.
CREATE FUNCTION cp.security_log_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 RAISE EXCEPTION 'log de segurança só acrescenta: % recusado em %',TG_OP,TG_TABLE_NAME USING ERRCODE='42501';
END $$;
REVOKE ALL ON FUNCTION cp.security_log_immutable() FROM PUBLIC;
CREATE TRIGGER security_log_immutable BEFORE UPDATE OR DELETE ON cp.security_log FOR EACH ROW EXECUTE FUNCTION cp.security_log_immutable();
CREATE TRIGGER security_log_no_truncate BEFORE TRUNCATE ON cp.security_log FOR EACH STATEMENT EXECUTE FUNCTION cp.security_log_immutable();

-- A cabeça só avança um passo por vez e nunca muda de condomínio; nunca é apagada.
CREATE FUNCTION cp.security_log_heads_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN NEW.last_seq:=0; NEW.last_hash:=repeat('0',64); RETURN NEW; END IF;
 IF TG_OP<>'UPDATE' OR NEW.tenant_id<>OLD.tenant_id OR NEW.last_seq<>OLD.last_seq+1 THEN
  RAISE EXCEPTION 'cabeça do log de segurança só avança um evento por vez' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_heads_guard() FROM PUBLIC;
CREATE TRIGGER security_log_heads_guard BEFORE INSERT OR UPDATE OR DELETE ON cp.security_log_heads FOR EACH ROW EXECUTE FUNCTION cp.security_log_heads_guard();
CREATE TRIGGER security_log_heads_no_truncate BEFORE TRUNCATE ON cp.security_log_heads FOR EACH STATEMENT EXECUTE FUNCTION cp.security_log_immutable();

-- 3. Grants e RLS. Runtime: só INSERT, por coluna, sem colunas preenchidas pelo banco (R5.1).
REVOKE ALL ON cp.security_log,cp.security_log_heads FROM PUBLIC;
GRANT INSERT(id,tenant_id,partner_id,occurred_at,clock_skew_ms,event_type,severity,source,session_id,subject_type,subject_id,unit_id,
 point_id,device_id,reason,evidence_ref,correlation_id,trace_id,corrects_id,details) ON cp.security_log TO cp_api,cp_worker,cp_onboarding;
GRANT SELECT ON cp.security_log TO cp_api;
-- Worker: só o necessário para a âncora diária.
GRANT SELECT(tenant_id,seq,hash,recorded_at) ON cp.security_log TO cp_worker;
GRANT SELECT,INSERT(tenant_id),UPDATE(last_seq,last_hash) ON cp.security_log_heads TO cp_api,cp_worker,cp_onboarding;
ALTER TABLE cp.security_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.security_log FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.security_log_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.security_log_heads FORCE ROW LEVEL SECURITY;

-- Gravação: no condomínio do contexto. Exceção única: concessão técnica emitida/revogada pela plataforma (V037), que
-- roda sem condomínio no contexto e com technical_grants.manage.
CREATE POLICY security_log_insert ON cp.security_log FOR INSERT TO cp_worker,cp_onboarding
 WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY security_log_api_insert ON cp.security_log FOR INSERT TO cp_api
 WITH CHECK(tenant_id=platform.current_tenant_id() OR (platform.current_tenant_id() IS NULL
  AND event_type IN ('TECH_GRANT_ISSUED','TECH_GRANT_REVOKED') AND platform.has_platform_capability('technical_grants.manage')));
-- Leitura pela equipe com capacidade publicada (AAL2): gestão local hoje; parceiro (security.log.read/export) depois.
CREATE POLICY security_log_read ON cp.security_log FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND (platform.has_capability('security.log.read.tenant')
  OR platform.has_capability('security.log.read') OR platform.has_capability('security.log.export')));
CREATE POLICY security_log_worker_read ON cp.security_log FOR SELECT TO cp_worker USING(tenant_id=platform.current_tenant_id());

CREATE POLICY security_log_heads_tenant ON cp.security_log_heads TO cp_worker,cp_onboarding
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY security_log_heads_api ON cp.security_log_heads TO cp_api
 USING(tenant_id=platform.current_tenant_id() OR (platform.current_tenant_id() IS NULL AND platform.has_platform_capability('technical_grants.manage')))
 WITH CHECK(tenant_id=platform.current_tenant_id() OR (platform.current_tenant_id() IS NULL AND platform.has_platform_capability('technical_grants.manage')));

-- 4. Gravação comum das fontes (invocador; sujeita às mesmas políticas). Devolve o id sem precisar ler a linha.
CREATE FUNCTION cp.security_log_append(target_tenant uuid,event text,level text,origin text,subject_kind text,subject uuid,
 unit uuid,point uuid,device uuid,motive text,corrects uuid,extra jsonb,occurred timestamptz DEFAULT NULL,skew bigint DEFAULT NULL,
 event_id uuid DEFAULT NULL) RETURNS uuid
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE new_id uuid:=coalesce(event_id,gen_random_uuid());
BEGIN
 -- Gatilho adiado que chega ao COMMIT com gatilhos desligados (intervenção de superusuário): sem cadeia, nada a gravar.
 IF current_setting('session_replication_role')='replica' THEN RETURN NULL; END IF;
 INSERT INTO cp.security_log(id,tenant_id,occurred_at,clock_skew_ms,event_type,severity,source,subject_type,subject_id,unit_id,point_id,
  device_id,reason,corrects_id,details)
 VALUES(new_id,target_tenant,occurred,skew,event,level,origin,subject_kind,subject,unit,point,device,
  CASE WHEN motive IS NULL OR btrim(motive)='' THEN NULL ELSE left(btrim(motive),500) END,corrects,jsonb_strip_nulls(coalesce(extra,'{}'::jsonb)));
 RETURN new_id;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_append(uuid,text,text,text,text,uuid,uuid,uuid,uuid,text,uuid,jsonb,timestamptz,bigint,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_append(uuid,text,text,text,text,uuid,uuid,uuid,uuid,text,uuid,jsonb,timestamptz,bigint,uuid)
 TO cp_api,cp_worker,cp_onboarding;

-- 5. Fontes. Gatilhos de restrição adiados: gravam no COMMIT da mesma transação (R5.4).
-- V014: entrada, saída e correção da portaria. O evento do log usa o id do registro de acesso (correção aponta para ele).
CREATE FUNCTION cp.security_log_from_access_event() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM cp.security_log_append(NEW.tenant_id,
  CASE NEW.event_type WHEN 'MANUAL_IN' THEN 'ENTRY_RECORDED' WHEN 'MANUAL_OUT' THEN 'EXIT_RECORDED' ELSE 'EVENT_CORRECTED' END,
  CASE WHEN NEW.event_type='CORRECTION' THEN 'ATTENTION' ELSE 'INFO' END,'CONCIERGE','ACCESS_EVENT',NEW.id,NEW.unit_id,NULL,NULL,
  CASE WHEN NEW.event_type='CORRECTION' THEN NEW.note END,NEW.corrects_event_id,
  jsonb_build_object('accessEventId',NEW.id,'visitorId',NEW.visitor_id,'arrivalId',NEW.arrival_id,'permitId',NEW.permit_id,
   'entryEventId',NEW.entry_event_id,'evidence',NEW.evidence,'manual',true),NULL,NULL,NEW.id);
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_access_event() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER access_events_security_log AFTER INSERT ON cp.access_events DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_access_event();

-- V033: ocorrência registrada e encerrada (categoria SECURITY pede atenção). Sem título nem descrição no log.
CREATE FUNCTION cp.security_log_from_occurrence() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  PERFORM cp.security_log_append(NEW.tenant_id,'OCCURRENCE_RECORDED',CASE WHEN NEW.category='SECURITY' THEN 'ATTENTION' ELSE 'INFO' END,
   'CONCIERGE','OCCURRENCE',NEW.id,NEW.unit_id,NULL,NULL,NULL,NULL,jsonb_build_object('category',NEW.category));
 ELSIF OLD.state='OPEN' AND NEW.state='RESOLVED' THEN
  PERFORM cp.security_log_append(NEW.tenant_id,'OCCURRENCE_RESOLVED','INFO','CONCIERGE','OCCURRENCE',NEW.id,NEW.unit_id,NULL,NULL,NULL,NULL,
   jsonb_build_object('category',NEW.category));
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_occurrence() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER concierge_occurrences_security_log AFTER INSERT OR UPDATE ON cp.concierge_occurrences DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_occurrence();

-- V026: credencial cadastrada (estado desejado publicado) e revogada, com motivo.
CREATE FUNCTION cp.security_log_from_credential() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  PERFORM cp.security_log_append(NEW.tenant_id,'CREDENTIAL_PUBLISHED','INFO','MANAGEMENT','ACCESS_CREDENTIAL',NEW.id,NEW.unit_id,NULL,NULL,NULL,NULL,
   jsonb_build_object('kind',NEW.kind,'membershipId',NEW.membership_id,'desiredVersion',NEW.desired_version));
 ELSIF OLD.desired_state='ACTIVE' AND NEW.desired_state='REVOKED' THEN
  PERFORM cp.security_log_append(NEW.tenant_id,'CREDENTIAL_REVOKED','ATTENTION','MANAGEMENT','ACCESS_CREDENTIAL',NEW.id,NEW.unit_id,NULL,NULL,
   NEW.revoke_reason,NULL,jsonb_build_object('kind',NEW.kind,'membershipId',NEW.membership_id,'desiredVersion',NEW.desired_version));
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_credential() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER access_credentials_security_log AFTER INSERT OR UPDATE ON cp.access_credentials DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_credential();

-- V027: evento do equipamento, com o horário do equipamento (occurred_at) e o de gravação do servidor.
CREATE FUNCTION cp.security_log_from_device_event() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE event text; level text:='INFO';
BEGIN
 IF NEW.state='QUARANTINED' THEN event:='DEVICE_EVENT_QUARANTINED'; level:='ATTENTION';
 ELSE
  event:=CASE NEW.kind WHEN 'AUTHORIZED' THEN 'ACCESS_AUTHORIZED' WHEN 'DENIED' THEN 'ENTRY_DENIED' WHEN 'RELAY_TRIGGERED' THEN 'RELAY_TRIGGERED'
   WHEN 'DOOR_OPENED' THEN 'DOOR_OPENED' WHEN 'PASSAGE_DETECTED' THEN 'PASSAGE_DETECTED' WHEN 'CREDENTIAL_APPLIED' THEN 'CREDENTIAL_APPLIED'
   WHEN 'CREDENTIAL_REJECTED' THEN 'CREDENTIAL_REJECTED' WHEN 'CREDENTIAL_REVOKED' THEN 'CREDENTIAL_REVOKED'
   WHEN 'DEVICE_ONLINE' THEN 'DEVICE_ONLINE' ELSE 'DEVICE_OFFLINE' END;
  IF NEW.kind IN ('DENIED','CREDENTIAL_REJECTED','DEVICE_OFFLINE') THEN level:='ATTENTION'; END IF;
 END IF;
 PERFORM cp.security_log_append(NEW.tenant_id,event,level,'DEVICE','DEVICE_EVENT',NEW.id,NEW.unit_id,NULL,NEW.device_id,NULL,NULL,
  jsonb_build_object('kind',NEW.kind,'direction',NEW.direction,'evidence',NEW.evidence,'credentialId',NEW.credential_id,
   'connectorId',NEW.connector_id,'externalEventId',left(NEW.external_event_id,200),'quarantineReason',NEW.quarantine_reason,
   'receivedAt',to_char(NEW.received_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),NEW.occurred_at);
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_device_event() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER device_events_security_log AFTER INSERT ON cp.device_events DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_device_event();

-- V028: só mudança de estado do equipamento (primeira observação e cada troca online/fora do ar).
CREATE FUNCTION cp.security_log_from_device_health() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' OR OLD.online IS DISTINCT FROM NEW.online THEN
  PERFORM cp.security_log_append(NEW.tenant_id,CASE WHEN NEW.online THEN 'DEVICE_ONLINE' ELSE 'DEVICE_OFFLINE' END,
   CASE WHEN NEW.online THEN 'INFO' ELSE 'ATTENTION' END,'SYSTEM','ACCESS_DEVICE',NEW.device_id,NULL,NULL,NEW.device_id,NULL,NULL,
   jsonb_build_object('observation','HEALTH','clockSkewMs',NEW.clock_skew_ms));
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_device_health() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER access_device_health_security_log AFTER INSERT OR UPDATE ON cp.access_device_health DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_device_health();

-- V037: concessão técnica emitida e revogada (plataforma, sem condomínio no contexto, ou operação revisada).
CREATE FUNCTION cp.security_log_from_technical_grant() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  PERFORM cp.security_log_append(NEW.tenant_id,'TECH_GRANT_ISSUED','INFO','PLATFORM','TECHNICAL_GRANT',NEW.id,NULL,NULL,NULL,NEW.reason,NULL,
   jsonb_build_object('accountId',NEW.account_id,'roleCode',NEW.role_code,'scopes',to_jsonb(NEW.scopes),'grantedVia',NEW.granted_via,
    'validUntil',to_char(NEW.valid_until AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')));
 ELSIF OLD.status='ACTIVE' AND NEW.status='REVOKED' THEN
  PERFORM cp.security_log_append(NEW.tenant_id,'TECH_GRANT_REVOKED','ATTENTION','PLATFORM','TECHNICAL_GRANT',NEW.id,NULL,NULL,NULL,NEW.revoke_reason,NULL,
   jsonb_build_object('accountId',NEW.account_id,'roleCode',NEW.role_code));
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_technical_grant() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER technical_grants_security_log AFTER INSERT OR UPDATE ON cp.technical_grants DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_technical_grant();

-- Vínculos: papel concedido (nasce ou passa a ACTIVE) e retirado (SUSPENDED/REVOKED). Pedido PENDING não é concessão.
CREATE FUNCTION cp.security_log_from_membership() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE event text;
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status='ACTIVE' THEN event:='ROLE_GRANTED'; END IF;
 ELSIF OLD.status IS DISTINCT FROM NEW.status THEN
  IF NEW.status='ACTIVE' THEN event:='ROLE_GRANTED';
  ELSIF NEW.status IN ('SUSPENDED','REVOKED') AND OLD.status='ACTIVE' THEN event:='ROLE_REVOKED';
  END IF;
 END IF;
 IF event IS NOT NULL THEN
  PERFORM cp.security_log_append(NEW.tenant_id,event,'INFO',CASE WHEN platform.current_actor_id() IS NULL THEN 'SYSTEM' ELSE 'MANAGEMENT' END,
   'MEMBERSHIP',NEW.id,NEW.unit_id,NULL,NULL,NULL,NULL,
   jsonb_build_object('accountId',NEW.account_id,'role',NEW.role,'status',NEW.status,'previousStatus',CASE WHEN TG_OP='UPDATE' THEN OLD.status END));
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_membership() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER memberships_security_log AFTER INSERT OR UPDATE ON cp.memberships DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_membership();

-- V044: marca de revogação de direitos (sujeito, contexto ou direito).
CREATE FUNCTION cp.security_log_from_revocation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM cp.security_log_append(NEW.tenant_id,'RIGHTS_REVOKED','ATTENTION',CASE WHEN platform.current_actor_id() IS NULL THEN 'SYSTEM' ELSE 'MANAGEMENT' END,
  'ACCESS_SUBJECT',NEW.subject_id,NULL,NULL,NULL,NULL,NULL,
  jsonb_build_object('revocationMarkerId',NEW.id,'targetKind',NEW.target_kind,'contextId',NEW.context_id,'grantId',NEW.grant_id,
   'reasonCode',NEW.reason_code,'journalSequence',NEW.journal_sequence));
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_revocation() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER revocation_markers_security_log AFTER INSERT ON cp.revocation_markers DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_revocation();

-- V047: trabalho de publicação criado e resultado relatado (ACK, confirmado com leitura de volta, falha/parcial/incerto).
CREATE FUNCTION cp.security_log_from_publication() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE event text; level text:='INFO';
BEGIN
 IF TG_OP='INSERT' THEN event:='RIGHTS_PUBLICATION_QUEUED';
 ELSIF OLD.state IS DISTINCT FROM NEW.state THEN
  event:=CASE NEW.state WHEN 'ACK' THEN 'DEVICE_COMMAND_SENT' WHEN 'CONFIRMED' THEN 'CREDENTIAL_PUBLISHED'
   WHEN 'FAILED' THEN 'DEVICE_COMMAND_FAILED' WHEN 'PARTIAL' THEN 'DEVICE_COMMAND_FAILED' WHEN 'UNKNOWN' THEN 'DEVICE_COMMAND_FAILED' END;
  IF NEW.state IN ('FAILED','PARTIAL','UNKNOWN') THEN level:='ATTENTION'; END IF;
 END IF;
 IF event IS NOT NULL THEN
  PERFORM cp.security_log_append(NEW.tenant_id,event,level,CASE WHEN TG_OP='INSERT' AND platform.current_actor_id() IS NOT NULL THEN 'MANAGEMENT' ELSE 'SYSTEM' END,
   'RIGHTS_PUBLICATION',NEW.id,NULL,NULL,NEW.device_id,NULL,NULL,
   jsonb_build_object('subjectId',NEW.subject_id,'origin',NEW.origin,'action',NEW.action,'priority',NEW.priority,'desiredVersion',NEW.desired_version,
    'state',NEW.state,'confirmationLevel',NEW.confirmation_level,'resultCode',NEW.result_code,'documentHash',nullif(NEW.document_hash,'')));
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_publication() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER rights_publications_security_log AFTER INSERT OR UPDATE ON cp.rights_publications DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_publication();

-- V048: relatório do gateway (recusado pede atenção).
CREATE FUNCTION cp.security_log_from_edge_report() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM cp.security_log_append(NEW.tenant_id,'EDGE_REPORT_RECEIVED',CASE WHEN NEW.outcome='REJECTED' THEN 'ATTENTION' ELSE 'INFO' END,'DEVICE',
  'EDGE_REPORT',NEW.id,NULL,NULL,NULL,NULL,NULL,
  jsonb_build_object('agentId',NEW.agent_id,'publicationId',NEW.publication_id,'state',NEW.state,'outcome',NEW.outcome,
   'outcomeCode',NEW.outcome_code,'resultCode',NEW.result_code,'documentHash',NEW.document_hash,'observedHash',NEW.observed_hash));
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_edge_report() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER edge_reports_security_log AFTER INSERT ON cp.edge_reports DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_edge_report();

-- 6. Âncora diária (R5.3): último hash de cada dia (America/Sao_Paulo) por condomínio, gravado pelo worker, só acrescenta.
CREATE TABLE platform.security_log_anchors (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 anchor_date date NOT NULL,
 last_seq bigint NOT NULL CHECK(last_seq>=1),
 last_hash text NOT NULL CHECK(last_hash ~ '^[0-9a-f]{64}$'),
 anchored_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,anchor_date)
);
CREATE FUNCTION platform.security_log_anchor_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 -- Âncora só de evento existente, com o hash gravado; nunca de dia ainda aberto.
 IF NEW.anchor_date>=(clock_timestamp() AT TIME ZONE 'America/Sao_Paulo')::date
   OR NOT EXISTS(SELECT 1 FROM cp.security_log s WHERE s.tenant_id=NEW.tenant_id AND s.seq=NEW.last_seq AND s.hash=NEW.last_hash
    AND s.recorded_at<((NEW.anchor_date+1)::timestamp AT TIME ZONE 'America/Sao_Paulo')) THEN
  RAISE EXCEPTION 'âncora não corresponde ao log' USING ERRCODE='23514';
 END IF;
 NEW.anchored_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.security_log_anchor_guard() FROM PUBLIC;
CREATE TRIGGER security_log_anchor_guard BEFORE INSERT ON platform.security_log_anchors FOR EACH ROW EXECUTE FUNCTION platform.security_log_anchor_guard();
CREATE TRIGGER security_log_anchor_immutable BEFORE UPDATE OR DELETE ON platform.security_log_anchors FOR EACH ROW EXECUTE FUNCTION cp.security_log_immutable();
CREATE TRIGGER security_log_anchor_no_truncate BEFORE TRUNCATE ON platform.security_log_anchors FOR EACH STATEMENT EXECUTE FUNCTION cp.security_log_immutable();
REVOKE ALL ON platform.security_log_anchors FROM PUBLIC;
GRANT SELECT,INSERT(tenant_id,anchor_date,last_seq,last_hash) ON platform.security_log_anchors TO cp_worker;
GRANT SELECT ON platform.security_log_anchors TO cp_api;
ALTER TABLE platform.security_log_anchors ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.security_log_anchors FORCE ROW LEVEL SECURITY;
CREATE POLICY security_log_anchors_worker ON platform.security_log_anchors TO cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY security_log_anchors_read ON platform.security_log_anchors FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND (platform.has_capability('security.log.read.tenant')
  OR platform.has_capability('security.log.read') OR platform.has_capability('security.log.export')));

-- Âncoras dos dias fechados (últimos 7) do condomínio do contexto ainda sem âncora; idempotente. Devolve quantas gravou.
CREATE FUNCTION platform.security_log_anchor_days() RETURNS integer
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE tenant uuid:=platform.current_tenant_id(); today date:=(clock_timestamp() AT TIME ZONE 'America/Sao_Paulo')::date; day date;
 last record; written integer:=0; added integer;
BEGIN
 IF tenant IS NULL THEN RAISE EXCEPTION 'âncora exige condomínio no contexto' USING ERRCODE='42501'; END IF;
 FOR day IN SELECT generate_series(today-7,today-1,interval '1 day')::date LOOP
  SELECT s.seq,s.hash INTO last FROM cp.security_log s WHERE s.tenant_id=tenant
   AND s.recorded_at<((day+1)::timestamp AT TIME ZONE 'America/Sao_Paulo') ORDER BY s.seq DESC LIMIT 1;
  IF FOUND THEN
   INSERT INTO platform.security_log_anchors(tenant_id,anchor_date,last_seq,last_hash) VALUES(tenant,day,last.seq,last.hash) ON CONFLICT DO NOTHING;
   GET DIAGNOSTICS added=ROW_COUNT; written:=written+added;
  END IF;
 END LOOP;
 RETURN written;
END $$;
REVOKE ALL ON FUNCTION platform.security_log_anchor_days() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.security_log_anchor_days() TO cp_worker;

-- 7. Verificação de integridade (R5.3): recalcula a cadeia do condomínio inteira e confere âncoras e cabeça. Roda como
-- quem consulta: exige leitura de todo o log do condomínio (a API confere a capacidade antes).
CREATE FUNCTION cp.security_log_verify(target_tenant uuid)
RETURNS TABLE(intact boolean,checked_events bigint,last_seq bigint,last_hash text,broken_seq bigint,problem text,anchors_checked integer)
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE r cp.security_log; expected bigint:=0; prev text:=repeat('0',64); anchor record; head record;
BEGIN
 anchors_checked:=0;
 FOR r IN SELECT * FROM cp.security_log s WHERE s.tenant_id=target_tenant ORDER BY s.seq LOOP
  expected:=expected+1;
  IF r.seq<>expected THEN problem:='SEQ_GAP'; broken_seq:=expected;
  ELSIF r.prev_hash<>prev THEN problem:='PREV_HASH_MISMATCH'; broken_seq:=r.seq;
  ELSIF cp.security_log_digest(r.prev_hash,r)<>r.hash THEN problem:='HASH_MISMATCH'; broken_seq:=r.seq;
  END IF;
  IF problem IS NOT NULL THEN
   intact:=false; checked_events:=expected-1; last_seq:=expected-1; last_hash:=prev; RETURN NEXT; RETURN;
  END IF;
  prev:=r.hash;
 END LOOP;
 checked_events:=expected; last_seq:=expected; last_hash:=prev;
 FOR anchor IN SELECT a.anchor_date,a.last_seq,a.last_hash,s.hash AS logged FROM platform.security_log_anchors a
   LEFT JOIN cp.security_log s ON s.tenant_id=a.tenant_id AND s.seq=a.last_seq WHERE a.tenant_id=target_tenant ORDER BY a.anchor_date LOOP
  anchors_checked:=anchors_checked+1;
  IF anchor.logged IS DISTINCT FROM anchor.last_hash THEN
   intact:=false; broken_seq:=anchor.last_seq; problem:='ANCHOR_MISMATCH'; RETURN NEXT; RETURN;
  END IF;
 END LOOP;
 SELECT h.last_seq,h.last_hash INTO head FROM cp.security_log_heads h WHERE h.tenant_id=target_tenant;
 IF (FOUND AND (head.last_seq<>expected OR head.last_hash<>prev)) OR (NOT FOUND AND expected>0) THEN
  intact:=false; broken_seq:=expected+1; problem:='HEAD_MISMATCH'; RETURN NEXT; RETURN;
 END IF;
 intact:=true; RETURN NEXT;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_verify(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_verify(uuid) TO cp_api;
