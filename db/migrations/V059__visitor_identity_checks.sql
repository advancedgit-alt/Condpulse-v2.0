-- ADR-0014: vinculação humana e explícita entre cadastros. Nome/final de documento nunca são chave de identidade.
CREATE TABLE cp.visitor_identity_checks (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 visitor_id uuid NOT NULL,
 canonical_visitor_id uuid NOT NULL,
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500),
 evidence text NOT NULL DEFAULT 'DOCUMENT_CHECKED' CHECK(evidence='DOCUMENT_CHECKED'),
 checked_by uuid NOT NULL REFERENCES platform.accounts(id),
 checked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','VOIDED')),
 void_reason text CHECK(void_reason IS NULL OR length(btrim(void_reason)) BETWEEN 3 AND 500),
 voided_by uuid REFERENCES platform.accounts(id),
 voided_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,visitor_id) REFERENCES cp.visitors(tenant_id,id),
 FOREIGN KEY(tenant_id,canonical_visitor_id) REFERENCES cp.visitors(tenant_id,id),
 CHECK((state='VOIDED')=(void_reason IS NOT NULL AND voided_by IS NOT NULL AND voided_at IS NOT NULL))
);
CREATE UNIQUE INDEX visitor_identity_one_active ON cp.visitor_identity_checks(tenant_id,visitor_id) WHERE state='ACTIVE';
CREATE INDEX visitor_identity_aliases ON cp.visitor_identity_checks(tenant_id,canonical_visitor_id,visitor_id) WHERE state='ACTIVE';
CREATE INDEX visitors_name_prefix ON cp.visitors(tenant_id,lower(full_name) text_pattern_ops);
REVOKE ALL ON cp.visitor_identity_checks FROM PUBLIC;
GRANT SELECT ON cp.visitor_identity_checks TO cp_api;
GRANT INSERT(id,tenant_id,visitor_id,canonical_visitor_id,reason,checked_by) ON cp.visitor_identity_checks TO cp_api;
GRANT UPDATE(state,void_reason,version) ON cp.visitor_identity_checks TO cp_api;
-- Só identificadores opacos para decisões do worker; nenhum nome/documento/motivo.
GRANT SELECT(tenant_id,visitor_id,canonical_visitor_id,state) ON cp.visitor_identity_checks TO cp_worker;
ALTER TABLE cp.visitor_identity_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.visitor_identity_checks FORCE ROW LEVEL SECURITY;
CREATE POLICY identity_tenant ON cp.visitor_identity_checks TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY identity_staff ON cp.visitor_identity_checks AS RESTRICTIVE TO cp_api
 USING(cp.subject_staff()) WITH CHECK(cp.subject_staff());
CREATE POLICY identity_worker_read ON cp.visitor_identity_checks FOR SELECT TO cp_worker
 USING(tenant_id=platform.current_tenant_id());

CREATE FUNCTION cp.visitor_canonical(target_tenant uuid,target_visitor uuid) RETURNS uuid
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT coalesce((SELECT c.canonical_visitor_id FROM cp.visitor_identity_checks c
  WHERE c.tenant_id=target_tenant AND c.visitor_id=target_visitor AND c.state='ACTIVE'),target_visitor) $$;
REVOKE ALL ON FUNCTION cp.visitor_canonical(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.visitor_canonical(uuid,uuid) TO cp_api,cp_worker;

CREATE FUNCTION cp.visitor_identity_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text,59001));
 IF TG_OP='INSERT' THEN
  IF NOT platform.has_capability('concierge.operate') OR NEW.checked_by IS DISTINCT FROM platform.current_actor_id() THEN
   RAISE EXCEPTION 'Conferência exige portaria autenticada.' USING ERRCODE='42501'; END IF;
  IF NEW.state<>'ACTIVE' OR NEW.version<>0 OR NEW.void_reason IS NOT NULL OR NEW.voided_by IS NOT NULL OR NEW.voided_at IS NOT NULL THEN
   RAISE EXCEPTION 'Conferência nasce ativa.' USING ERRCODE='23514'; END IF;
  IF NEW.visitor_id<>NEW.canonical_visitor_id AND NOT EXISTS(SELECT 1 FROM cp.visitor_identity_checks c
   WHERE c.tenant_id=NEW.tenant_id AND c.visitor_id=NEW.canonical_visitor_id AND c.canonical_visitor_id=c.visitor_id AND c.state='ACTIVE') THEN
   RAISE EXCEPTION 'Escolha uma identidade raiz já conferida.' USING ERRCODE='23514'; END IF;
  IF NEW.visitor_id<>NEW.canonical_visitor_id AND EXISTS(SELECT 1 FROM cp.visitor_identity_checks c
   WHERE c.tenant_id=NEW.tenant_id AND c.canonical_visitor_id=NEW.visitor_id AND c.visitor_id<>NEW.visitor_id AND c.state='ACTIVE') THEN
   RAISE EXCEPTION 'Raiz com associações não pode virar alias.' USING ERRCODE='23514'; END IF;
  NEW.checked_at:=clock_timestamp();
 ELSE
  IF NOT platform.has_capability('memberships.manage') THEN RAISE EXCEPTION 'Correção exige gestão autenticada.' USING ERRCODE='42501'; END IF;
  IF (NEW.id,NEW.tenant_id,NEW.visitor_id,NEW.canonical_visitor_id,NEW.reason,NEW.evidence,NEW.checked_by,NEW.checked_at)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.visitor_id,OLD.canonical_visitor_id,OLD.reason,OLD.evidence,OLD.checked_by,OLD.checked_at)
   OR OLD.state<>'ACTIVE' OR NEW.state<>'VOIDED' OR NEW.version<>OLD.version+1 OR NEW.void_reason IS NULL THEN
   RAISE EXCEPTION 'Conferência só é anulada com motivo e versão.' USING ERRCODE='23514'; END IF;
  IF OLD.visitor_id=OLD.canonical_visitor_id AND EXISTS(SELECT 1 FROM cp.visitor_identity_checks c
   WHERE c.tenant_id=OLD.tenant_id AND c.canonical_visitor_id=OLD.visitor_id AND c.visitor_id<>OLD.visitor_id AND c.state='ACTIVE') THEN
   RAISE EXCEPTION 'Corrija primeiro as associações desta raiz.' USING ERRCODE='23514'; END IF;
  NEW.voided_by:=platform.current_actor_id(); NEW.voided_at:=clock_timestamp();
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.visitor_identity_guard() FROM PUBLIC;
CREATE TRIGGER visitor_identity_guard BEFORE INSERT OR UPDATE ON cp.visitor_identity_checks FOR EACH ROW EXECUTE FUNCTION cp.visitor_identity_guard();

CREATE OR REPLACE FUNCTION cp.security_log_event_types() RETURNS text[]
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
 'EDGE_REPORT_RECEIVED','TECH_GRANT_REVOKED','VISITOR_IDENTITY_CHECKED','VISITOR_IDENTITY_VOIDED'] $$;

CREATE FUNCTION cp.visitor_identity_log() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM cp.security_log_append(NEW.tenant_id,CASE WHEN TG_OP='INSERT' THEN 'VISITOR_IDENTITY_CHECKED' ELSE 'VISITOR_IDENTITY_VOIDED' END,
  'ATTENTION',CASE WHEN TG_OP='INSERT' THEN 'CONCIERGE' ELSE 'MANAGEMENT' END,'VISITOR_IDENTITY',NEW.id,NULL,NULL,NULL,
  CASE WHEN TG_OP='INSERT' THEN NEW.reason ELSE NEW.void_reason END,NULL,
  jsonb_build_object('visitorId',NEW.visitor_id,'canonicalVisitorId',NEW.canonical_visitor_id,'version',NEW.version,'evidence',NEW.evidence));
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.visitor_identity_log() FROM PUBLIC;
CREATE TRIGGER visitor_identity_log AFTER INSERT OR UPDATE ON cp.visitor_identity_checks FOR EACH ROW EXECUTE FUNCTION cp.visitor_identity_log();
