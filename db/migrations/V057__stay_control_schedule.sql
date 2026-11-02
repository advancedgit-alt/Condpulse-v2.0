-- ADR-0014, primeira parte: controle editável, horário local e vínculo da permanência com a festa/reserva.
-- V055 permanece imutável. Sem bloqueio físico presumido; a restrição/liberação de reentrada é etapa própria.
ALTER TABLE cp.stay_rules ADD COLUMN enabled boolean NOT NULL DEFAULT true,
 ADD COLUMN starts_local time, ADD COLUMN ends_local time,
 ADD CONSTRAINT stay_rules_daily_window CHECK((starts_local IS NULL)=(ends_local IS NULL)
  AND (starts_local IS NULL OR starts_local<ends_local));
GRANT INSERT(enabled,starts_local,ends_local),UPDATE(enabled,starts_local,ends_local) ON cp.stay_rules TO cp_api;
GRANT SELECT(tenant_id,kind,enabled) ON cp.stay_rules TO cp_worker;
CREATE POLICY stay_rules_worker_read ON cp.stay_rules FOR SELECT TO cp_worker USING(tenant_id=platform.current_tenant_id());

CREATE OR REPLACE FUNCTION cp.stay_kinds() RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT ARRAY['VISITOR','SERVICE_PROVIDER','DELIVERY','CONSTRUCTION','EVENT_GUEST'] $$;

CREATE FUNCTION cp.stay_control_enabled(target_tenant uuid,target_kind text) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT coalesce((SELECT r.enabled FROM cp.stay_rules r WHERE r.tenant_id=target_tenant AND r.kind=target_kind),true) $$;
REVOKE ALL ON FUNCTION cp.stay_control_enabled(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.stay_control_enabled(uuid,text) TO cp_api,cp_worker;
GRANT SELECT(kind,unit_id) ON cp.stays TO cp_worker;

ALTER TABLE cp.stays ADD COLUMN reservation_id uuid,
 ADD CONSTRAINT stays_reservation_fk FOREIGN KEY(tenant_id,reservation_id) REFERENCES cp.reservations(tenant_id,id);
CREATE INDEX stays_by_reservation ON cp.stays(tenant_id,reservation_id,entered_at) WHERE reservation_id IS NOT NULL;

-- O relógio é sempre o do banco. A função pura também permite testar fronteiras e fuso sem mudar o relógio do servidor.
CREATE FUNCTION cp.stay_daily_exit(entered timestamptz,zone text,opens time,closes time) RETURNS timestamptz
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE local_now timestamp:=entered AT TIME ZONE zone;
BEGIN
 IF opens IS NULL AND closes IS NULL THEN RETURN NULL; END IF;
 IF opens IS NULL OR closes IS NULL OR opens>=closes THEN
  RAISE EXCEPTION 'Horário diário inválido.' USING ERRCODE='23514';
 END IF;
 IF local_now::time<opens OR local_now::time>=closes THEN
  RAISE EXCEPTION 'Entrada fora do horário permitido para esta categoria.' USING ERRCODE='P5701';
 END IF;
 RETURN (local_now::date+closes) AT TIME ZONE zone;
END $$;
REVOKE ALL ON FUNCTION cp.stay_daily_exit(timestamptz,text,time,time) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.stay_daily_exit(timestamptz,text,time,time) TO cp_api;

-- Nome zz: roda depois de stays_guard da V055, que já derivou pessoa/unidade/entrada da autorização real.
CREATE FUNCTION cp.stay_control_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE cfg record; eff record; venue uuid; zone text; deadline timestamptz;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF NEW.reservation_id IS DISTINCT FROM OLD.reservation_id THEN
   RAISE EXCEPTION 'Vínculo de festa da permanência não muda.' USING ERRCODE='42501';
  END IF;
  IF (NEW.due_alerted_at,NEW.l1_alerted_at,NEW.l2_alerted_at) IS DISTINCT FROM
    (OLD.due_alerted_at,OLD.l1_alerted_at,OLD.l2_alerted_at) AND NOT cp.stay_control_enabled(OLD.tenant_id,OLD.kind) THEN
   RAISE EXCEPTION 'Controle de alertas desligado no condomínio.' USING ERRCODE='42501';
  END IF;
  RETURN NEW;
 END IF;
 NEW.reservation_id:=NULL;
 IF NEW.permit_id IS NOT NULL THEN
  SELECT p.reservation_id INTO venue FROM cp.visit_permits p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.permit_id;
  NEW.reservation_id:=venue;
  IF venue IS NOT NULL THEN NEW.kind:='EVENT_GUEST'; END IF;
 END IF;
 SELECT * INTO eff FROM cp.stay_rule(NEW.tenant_id,NEW.kind);
 SELECT r.enabled,r.starts_local,r.ends_local INTO cfg FROM cp.stay_rules r WHERE r.tenant_id=NEW.tenant_id AND r.kind=NEW.kind;
 IF FOUND AND cfg.enabled AND cfg.starts_local IS NOT NULL THEN
  SELECT t.time_zone INTO zone FROM platform.tenants t WHERE t.id=NEW.tenant_id;
  deadline:=cp.stay_daily_exit(NEW.entered_at,zone,cfg.starts_local,cfg.ends_local);
  IF deadline<NEW.expected_exit_at THEN NEW.expected_exit_at:=deadline; NEW.expected_source:='TENANT_RULE'; END IF;
 END IF;
 NEW.rule_source:=eff.rule_source;
 NEW.l1_at:=NEW.expected_exit_at+make_interval(mins=>eff.l1_after_minutes);
 NEW.l2_at:=NEW.expected_exit_at+make_interval(mins=>eff.l2_after_minutes);
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.stay_control_guard() FROM PUBLIC;
CREATE TRIGGER zz_stays_control_guard BEFORE INSERT OR UPDATE ON cp.stays FOR EACH ROW EXECUTE FUNCTION cp.stay_control_guard();

-- Desligar alertas não apaga entrada/saída; as permanências continuam na lista e no histórico.
CREATE OR REPLACE FUNCTION cp.stay_alert_pass() RETURNS integer
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE tenant uuid:=platform.current_tenant_id(); now_ts timestamptz:=clock_timestamp(); changed integer;
BEGIN
 IF tenant IS NULL THEN RAISE EXCEPTION 'alertas exigem condomínio no contexto' USING ERRCODE='42501'; END IF;
 UPDATE cp.stays s SET
  due_alerted_at=CASE WHEN s.due_alerted_at IS NULL AND s.expected_exit_at<=now_ts THEN now_ts ELSE s.due_alerted_at END,
  l1_alerted_at=CASE WHEN s.l1_alerted_at IS NULL AND s.l1_at<=now_ts THEN now_ts ELSE s.l1_alerted_at END,
  l2_alerted_at=CASE WHEN s.l2_alerted_at IS NULL AND s.l2_at<=now_ts THEN now_ts ELSE s.l2_alerted_at END
 WHERE s.tenant_id=tenant AND s.state='OPEN' AND s.justified_at IS NULL AND cp.stay_control_enabled(s.tenant_id,s.kind)
  AND ((s.due_alerted_at IS NULL AND s.expected_exit_at<=now_ts) OR (s.l1_alerted_at IS NULL AND s.l1_at<=now_ts)
   OR (s.l2_alerted_at IS NULL AND s.l2_at<=now_ts));
 GET DIAGNOSTICS changed=ROW_COUNT; RETURN changed;
END $$;

-- Catálogo de mudanças versionado e auditado, incluindo ativação e horário efetivo.
CREATE OR REPLACE FUNCTION cp.security_log_from_stay_rule() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM cp.security_log_append(NEW.tenant_id,'RULE_CHANGED','INFO','MANAGEMENT','STAY_RULE',NULL,NULL,NULL,NULL,NULL,NULL,
  jsonb_build_object('rule','STAY_TIME','kind',NEW.kind,'maxMinutes',NEW.max_minutes,'l1AfterMinutes',NEW.l1_after_minutes,
   'l2AfterMinutes',NEW.l2_after_minutes,'enabled',NEW.enabled,'startsLocal',NEW.starts_local,'endsLocal',NEW.ends_local,'version',NEW.version));
 RETURN NULL;
END $$;

-- A festa/reserva acompanha os eventos derivados da permanência.
CREATE OR REPLACE FUNCTION cp.security_log_stay_event(s cp.stays,event text,level text,origin text,motive text,extra jsonb) RETURNS void
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF current_setting('session_replication_role')='replica' THEN RETURN; END IF;
 INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,unit_id,reason,correlation_id,details)
 VALUES(s.tenant_id,event,level,origin,'STAY',s.id,s.unit_id,
  CASE WHEN motive IS NULL OR btrim(motive)='' THEN NULL ELSE left(btrim(motive),500) END,s.id,
  jsonb_strip_nulls(jsonb_build_object('stayId',s.id,'entryEventId',s.entry_event_id,'visitorId',s.visitor_id,'kind',s.kind,
   'origin',s.origin,'basis',s.basis,'reservationId',s.reservation_id,'version',s.version)||coalesce(extra,'{}'::jsonb)));
END $$;
