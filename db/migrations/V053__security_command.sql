-- CPX-012 a CPX-014 (PRD complementar Segurança v1.0, §4.3.4, §5.2, §7.6/E11.4–E11.5, §8.2; ADR-0011): Comando de
-- Segurança do gestor líder (SECURITY_LEAD, capacidade security.command).
--  - Visão da carteira: cp.security_command_summary() conta, no condomínio do contexto, equipamentos fora do ar ou sem
--    informação recente (V026/V028, mesma classificação de /access-health), ocorrências de segurança abertas (V033),
--    alertas críticos do Log de Segurança (V050) das últimas 24 h sem incidente, incidentes abertos e aberturas remotas
--    sem revisão. O critério do semáforo (docs/PARCEIROS.md) fica na API; aqui só contagens, sem dado pessoal.
--  - Incidentes: cp.incidents e cp.incident_links (eventos do log anexados). Abrir, designar e encerrar gravam
--    INCIDENT_OPENED/INCIDENT_ASSIGNED/INCIDENT_CLOSED no log com correlation_id = incidente, na mesma transação (R5.4).
--    A linha do tempo é montada a partir do log (eventos do incidente + eventos anexados).
--  - Revisão de aberturas remotas: REMOTE_OPEN_REVIEWED (já no catálogo da V050) só pela API, por quem tem
--    security.remote_open.review com alcance no condomínio, sobre um REMOTE_OPEN existente, nunca a própria abertura;
--    uma revisão por abertura e correção encadeada (corrects_id + motivo).
--  - Alcance sempre conferido no banco por platform.partner_reach() (contrato ativo, papel ativo), não só pela
--    capacidade publicada: as operações rodam com app.partner_id e o condomínio trocado no contexto da transação.
--  - Nenhum tipo novo no catálogo do log, nenhuma mudança na cadeia, nos grants ou nas políticas de gravação da V050.

-- 1. Alcance de um papel de parceiro com a capacidade no condomínio (sem depender de app.partner_id).
CREATE FUNCTION cp.partner_reach_grants(target_tenant uuid,required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT target_tenant IS NOT NULL AND platform.capability_published(required) AND EXISTS(SELECT 1 FROM platform.partner_reach() r
  WHERE r.tenant_id=target_tenant AND required=ANY(platform.partner_role_capabilities(r.role))) $$;
REVOKE ALL ON FUNCTION cp.partner_reach_grants(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.partner_reach_grants(uuid,text) TO cp_api;

-- 2. Leitura da carteira pelo líder (somente SELECT). Equipamentos e saúde (V026/V028/V048/V051) e ocorrências (V033).
ALTER POLICY access_devices_read ON cp.access_devices
 USING (((SELECT platform.has_capability('memberships.manage'::text)) OR (SELECT platform.has_capability('concierge.operate'::text)) OR (SELECT platform.has_technical_scope('installation.read'::text)) OR (SELECT platform.has_technical_scope('installation.manage'::text)) OR (SELECT platform.has_technical_scope('installation.bind_channel'::text)) OR (SELECT platform.has_technical_scope('installation.commission'::text)) OR (EXISTS ( SELECT 1
   FROM cp.access_credentials c
  WHERE ((c.tenant_id = access_devices.tenant_id) AND (c.connector_id = access_devices.connector_id)))) OR (((SELECT cp.edge_credential()) IS NOT NULL) AND (connector_id = (SELECT cp.edge_connector())))
  OR cp.partner_reach_grants(tenant_id,'security.command')));
ALTER POLICY access_connectors_read ON cp.access_connectors
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage')
  OR platform.has_technical_scope('gateway.manage') OR cp.partner_reach_grants(tenant_id,'security.command'));
ALTER POLICY access_connector_health_read ON cp.access_connector_health
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR cp.partner_reach_grants(tenant_id,'security.command'));
ALTER POLICY access_device_health_read ON cp.access_device_health
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR cp.partner_reach_grants(tenant_id,'security.command'));
-- Ocorrências: a política restritiva da V033 valia para todos os comandos; separada por comando, só a leitura ganha o
-- líder. Gravação e encerramento seguem exatamente como antes.
DROP POLICY concierge_occurrences_staff ON cp.concierge_occurrences;
CREATE POLICY concierge_occurrences_staff_read ON cp.concierge_occurrences AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR platform.has_capability('requests.manage')
  OR cp.partner_reach_grants(tenant_id,'security.command'));
CREATE POLICY concierge_occurrences_staff_insert ON cp.concierge_occurrences AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate') OR platform.has_capability('requests.manage'));
CREATE POLICY concierge_occurrences_staff_update ON cp.concierge_occurrences AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('concierge.operate') OR platform.has_capability('requests.manage'))
 WITH CHECK(platform.has_capability('concierge.operate') OR platform.has_capability('requests.manage'));
-- Escala e equipe do próprio parceiro (central sem operador escalado; responsável do incidente). Mesmo padrão da
-- leitura do gestor (V049): publicação no parceiro do contexto, sem consultar outra tabela (grafo de políticas sem ciclo).
CREATE POLICY operator_schedules_command_read ON cp.operator_schedules FOR SELECT TO cp_api
 USING(partner_id=platform.current_partner_id() AND platform.capability_published('security.command'));
CREATE POLICY partner_members_command_read ON platform.partner_members FOR SELECT TO cp_api
 USING(partner_id=platform.current_partner_id() AND platform.capability_published('security.command'));

-- 3. Incidentes (E11.5). Do condomínio e do parceiro que o abriu; só o líder com alcance lê e muda.
CREATE TABLE cp.incidents (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 partner_id uuid NOT NULL REFERENCES platform.partners(id),
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 3 AND 120),
 severity text NOT NULL CHECK(severity IN ('ATTENTION','CRITICAL')),
 state text NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','CLOSED')),
 opened_by uuid NOT NULL REFERENCES platform.accounts(id),
 opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 assignee_member_id uuid REFERENCES platform.partner_members(id),
 assigned_by uuid REFERENCES platform.accounts(id),
 assigned_at timestamptz,
 outcome text CHECK(outcome IS NULL OR outcome IN ('RESOLVED','FALSE_ALARM','REFERRED')),
 report text CHECK(report IS NULL OR length(btrim(report)) BETWEEN 10 AND 4000),
 closed_by uuid REFERENCES platform.accounts(id),
 closed_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 CHECK((assignee_member_id IS NULL)=(assigned_by IS NULL) AND (assigned_by IS NULL)=(assigned_at IS NULL)),
 CHECK((state='CLOSED')=(outcome IS NOT NULL AND report IS NOT NULL AND closed_by IS NOT NULL AND closed_at IS NOT NULL)),
 CHECK(state='CLOSED' OR (outcome IS NULL AND report IS NULL AND closed_by IS NULL AND closed_at IS NULL))
);
CREATE INDEX incidents_by_state ON cp.incidents(tenant_id,state,opened_at DESC,id);

CREATE TABLE cp.incident_links (
 tenant_id uuid NOT NULL,
 incident_id uuid NOT NULL,
 log_event_id uuid NOT NULL,
 linked_by uuid NOT NULL REFERENCES platform.accounts(id),
 linked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(incident_id,log_event_id),
 FOREIGN KEY(tenant_id,incident_id) REFERENCES cp.incidents(tenant_id,id),
 FOREIGN KEY(tenant_id,log_event_id) REFERENCES cp.security_log(tenant_id,id)
);
CREATE INDEX incident_links_event ON cp.incident_links(tenant_id,log_event_id);

-- Líder do mesmo parceiro do incidente com alcance no condomínio (contrato ativo). Lê só o próprio parceiro: outro
-- parceiro que assuma o condomínio depois não vê os incidentes do anterior.
CREATE FUNCTION cp.incident_command(target_tenant uuid,target_partner uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT target_tenant=platform.current_tenant_id() AND platform.capability_published('security.command')
  AND EXISTS(SELECT 1 FROM platform.partner_reach() r WHERE r.tenant_id=target_tenant AND r.partner_id=target_partner
   AND 'security.command'=ANY(platform.partner_role_capabilities(r.role))) $$;
REVOKE ALL ON FUNCTION cp.incident_command(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.incident_command(uuid,uuid) TO cp_api;

CREATE FUNCTION cp.incidents_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE actor uuid:=platform.current_actor_id();
BEGIN
 IF actor IS NULL THEN RAISE EXCEPTION 'incidente exige ator' USING ERRCODE='42501'; END IF;
 IF TG_OP='INSERT' THEN
  -- Parceiro do contexto, com alcance de líder no condomínio; autoria e horários do banco.
  NEW.partner_id:=platform.current_partner_id();
  IF NEW.partner_id IS NULL OR NOT cp.incident_command(NEW.tenant_id,NEW.partner_id) THEN
   RAISE EXCEPTION 'incidente exige líder de segurança com alcance no condomínio' USING ERRCODE='42501';
  END IF;
  IF NEW.state<>'OPEN' OR NEW.version<>0 OR NEW.assignee_member_id IS NOT NULL OR NEW.outcome IS NOT NULL OR NEW.report IS NOT NULL THEN
   RAISE EXCEPTION 'incidente nasce aberto, sem responsável, na versão 0' USING ERRCODE='23514';
  END IF;
  NEW.title:=btrim(NEW.title); NEW.opened_by:=actor; NEW.opened_at:=clock_timestamp();
  NEW.assigned_by:=NULL; NEW.assigned_at:=NULL; NEW.closed_by:=NULL; NEW.closed_at:=NULL;
  RETURN NEW;
 END IF;
 IF OLD.state<>'OPEN' THEN RAISE EXCEPTION 'incidente encerrado não muda' USING ERRCODE='23514'; END IF;
 IF NEW.version<>OLD.version+1
   OR (NEW.id,NEW.tenant_id,NEW.partner_id,NEW.title,NEW.severity,NEW.opened_by,NEW.opened_at)
    IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.partner_id,OLD.title,OLD.severity,OLD.opened_by,OLD.opened_at) THEN
  RAISE EXCEPTION 'incidente só muda por designação ou encerramento' USING ERRCODE='23514';
 END IF;
 IF NEW.state='OPEN' THEN
  -- Designação: membro ativo do mesmo parceiro, em papel de segurança da operação.
  IF NEW.assignee_member_id IS NOT DISTINCT FROM OLD.assignee_member_id OR NEW.assignee_member_id IS NULL
    OR NEW.outcome IS NOT NULL OR NEW.report IS NOT NULL THEN
   RAISE EXCEPTION 'designação inválida' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM platform.partner_members m WHERE m.id=NEW.assignee_member_id AND m.partner_id=NEW.partner_id
    AND m.status='ACTIVE' AND m.role IN ('SECURITY_LEAD','SECURITY_SUPERVISOR','REMOTE_OPERATOR')) THEN
   RAISE EXCEPTION 'responsável precisa ser líder, supervisor ou operador ativo do parceiro' USING ERRCODE='23514';
  END IF;
  NEW.assigned_by:=actor; NEW.assigned_at:=clock_timestamp();
  RETURN NEW;
 END IF;
 -- Encerramento: final, com desfecho e relatório; responsável não muda no mesmo passo.
 IF NEW.assignee_member_id IS DISTINCT FROM OLD.assignee_member_id OR NEW.outcome IS NULL OR NEW.report IS NULL THEN
  RAISE EXCEPTION 'encerramento exige desfecho e relatório' USING ERRCODE='23514';
 END IF;
 NEW.report:=btrim(NEW.report); NEW.assigned_by:=OLD.assigned_by; NEW.assigned_at:=OLD.assigned_at;
 NEW.closed_by:=actor; NEW.closed_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.incidents_guard() FROM PUBLIC;
CREATE TRIGGER incidents_guard BEFORE INSERT OR UPDATE ON cp.incidents FOR EACH ROW EXECUTE FUNCTION cp.incidents_guard();
CREATE TRIGGER incidents_no_delete BEFORE DELETE ON cp.incidents FOR EACH ROW EXECUTE FUNCTION cp.security_log_immutable();
CREATE TRIGGER incidents_no_truncate BEFORE TRUNCATE ON cp.incidents FOR EACH STATEMENT EXECUTE FUNCTION cp.security_log_immutable();

CREATE FUNCTION cp.incident_links_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF platform.current_actor_id() IS NULL THEN RAISE EXCEPTION 'anexo exige ator' USING ERRCODE='42501'; END IF;
 -- Só em incidente aberto e visível (RLS do incidente); trava a linha para contar os anexos sem corrida.
 PERFORM 1 FROM cp.incidents i WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.incident_id AND i.state='OPEN' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'anexo só em incidente aberto' USING ERRCODE='23514'; END IF;
 IF (SELECT count(*) FROM cp.incident_links l WHERE l.incident_id=NEW.incident_id)>=50 THEN
  RAISE EXCEPTION 'no máximo 50 eventos anexados por incidente' USING ERRCODE='23514';
 END IF;
 NEW.linked_by:=platform.current_actor_id(); NEW.linked_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.incident_links_guard() FROM PUBLIC;
CREATE TRIGGER incident_links_guard BEFORE INSERT ON cp.incident_links FOR EACH ROW EXECUTE FUNCTION cp.incident_links_guard();
CREATE TRIGGER incident_links_immutable BEFORE UPDATE OR DELETE ON cp.incident_links FOR EACH ROW EXECUTE FUNCTION cp.security_log_immutable();
CREATE TRIGGER incident_links_no_truncate BEFORE TRUNCATE ON cp.incident_links FOR EACH STATEMENT EXECUTE FUNCTION cp.security_log_immutable();

REVOKE ALL ON cp.incidents,cp.incident_links FROM PUBLIC;
GRANT SELECT,INSERT(id,tenant_id,title,severity),UPDATE(assignee_member_id,state,outcome,report,version) ON cp.incidents TO cp_api;
GRANT SELECT,INSERT(tenant_id,incident_id,log_event_id) ON cp.incident_links TO cp_api;
ALTER TABLE cp.incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.incidents FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.incident_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.incident_links FORCE ROW LEVEL SECURITY;
CREATE POLICY incidents_command ON cp.incidents TO cp_api
 USING(cp.incident_command(tenant_id,partner_id)) WITH CHECK(cp.incident_command(tenant_id,partner_id));
CREATE POLICY incident_links_command ON cp.incident_links TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND EXISTS(SELECT 1 FROM cp.incidents i WHERE i.tenant_id=incident_links.tenant_id AND i.id=incident_links.incident_id))
 WITH CHECK(tenant_id=platform.current_tenant_id() AND EXISTS(SELECT 1 FROM cp.incidents i WHERE i.tenant_id=incident_links.tenant_id AND i.id=incident_links.incident_id));

-- Incidente no log (R5.4): no COMMIT, com correlation_id = incidente. details sem título nem relatório.
CREATE FUNCTION cp.security_log_from_incident() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE linked jsonb;
BEGIN
 IF current_setting('session_replication_role')='replica' THEN RETURN NULL; END IF;
 SELECT coalesce(jsonb_agg(l.log_event_id ORDER BY l.linked_at,l.log_event_id),'[]'::jsonb) INTO linked
  FROM cp.incident_links l WHERE l.tenant_id=NEW.tenant_id AND l.incident_id=NEW.id;
 IF TG_OP='INSERT' THEN
  INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,correlation_id,details)
  VALUES(NEW.tenant_id,'INCIDENT_OPENED',NEW.severity,'REMOTE_CENTRAL','INCIDENT',NEW.id,NEW.id,
   jsonb_build_object('severity',NEW.severity,'linkedEventIds',linked));
 ELSIF OLD.assignee_member_id IS DISTINCT FROM NEW.assignee_member_id THEN
  INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,correlation_id,details)
  VALUES(NEW.tenant_id,'INCIDENT_ASSIGNED','INFO','REMOTE_CENTRAL','INCIDENT',NEW.id,NEW.id,
   jsonb_strip_nulls(jsonb_build_object('assigneeMemberId',NEW.assignee_member_id,'previousAssigneeMemberId',OLD.assignee_member_id)));
 ELSIF OLD.state='OPEN' AND NEW.state='CLOSED' THEN
  INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,correlation_id,details)
  VALUES(NEW.tenant_id,'INCIDENT_CLOSED','INFO','REMOTE_CENTRAL','INCIDENT',NEW.id,NEW.id,
   jsonb_build_object('outcome',NEW.outcome,'linkedEventIds',linked));
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_incident() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER incidents_security_log AFTER INSERT OR UPDATE ON cp.incidents DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_incident();

-- 4. Revisão de aberturas remotas (CPX-014, §8.2 passo 5).
CREATE UNIQUE INDEX security_log_remote_open_review ON cp.security_log(tenant_id,subject_id)
 WHERE event_type='REMOTE_OPEN_REVIEWED' AND corrects_id IS NULL;
CREATE UNIQUE INDEX security_log_remote_open_review_correction ON cp.security_log(tenant_id,corrects_id)
 WHERE event_type='REMOTE_OPEN_REVIEWED' AND corrects_id IS NOT NULL;

CREATE FUNCTION cp.security_log_remote_open_review_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE verdict text:=NEW.details->>'verdict'; opener uuid; found boolean;
BEGIN
 IF platform.current_actor_id() IS NULL OR NOT has_function_privilege('platform.partner_reach()','EXECUTE') THEN
  RAISE EXCEPTION 'revisão de abertura remota só pela API com ator' USING ERRCODE='42501';
 END IF;
 IF NOT cp.partner_reach_grants(NEW.tenant_id,'security.remote_open.review') THEN
  RAISE EXCEPTION 'revisão exige security.remote_open.review com alcance no condomínio' USING ERRCODE='42501';
 END IF;
 IF EXISTS(SELECT 1 FROM jsonb_object_keys(NEW.details) k WHERE k<>'verdict') OR verdict IS NULL OR verdict NOT IN ('CONFERRED','IRREGULAR')
   OR NEW.severity<>(CASE verdict WHEN 'IRREGULAR' THEN 'ATTENTION' ELSE 'INFO' END) OR NEW.source<>'REMOTE_CENTRAL'
   OR NEW.subject_type IS DISTINCT FROM 'SECURITY_LOG_EVENT' OR NEW.subject_id IS NULL
   OR NEW.unit_id IS NOT NULL OR NEW.point_id IS NOT NULL OR NEW.device_id IS NOT NULL OR NEW.evidence_ref IS NOT NULL
   OR ((verdict='IRREGULAR' OR NEW.corrects_id IS NOT NULL) AND NEW.reason IS NULL) THEN
  RAISE EXCEPTION 'revisão de abertura remota com forma inválida' USING ERRCODE='23514';
 END IF;
 -- A abertura existe neste condomínio e é legível por quem revisa (RLS do log).
 SELECT s.actor_account_id,true INTO opener,found FROM cp.security_log s
  WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.subject_id AND s.event_type='REMOTE_OPEN';
 IF found IS NULL THEN RAISE EXCEPTION 'abertura remota inexistente neste condomínio' USING ERRCODE='23514'; END IF;
 IF opener=platform.current_actor_id() THEN
  RAISE EXCEPTION 'ninguém revisa a própria abertura remota' USING ERRCODE='42501';
 END IF;
 -- Correção: aponta para uma revisão da mesma abertura (o índice único garante que é a última da corrente).
 IF NEW.corrects_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cp.security_log s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.corrects_id
   AND s.event_type='REMOTE_OPEN_REVIEWED' AND s.subject_id=NEW.subject_id) THEN
  RAISE EXCEPTION 'correção precisa apontar para uma revisão da mesma abertura' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_remote_open_review_guard() FROM PUBLIC;
-- Antes da cadeia (ordem alfabética dos gatilhos BEFORE): recusa sem tocar a cabeça.
CREATE TRIGGER security_log_a_remote_open_review BEFORE INSERT ON cp.security_log FOR EACH ROW
 WHEN (NEW.event_type='REMOTE_OPEN_REVIEWED') EXECUTE FUNCTION cp.security_log_remote_open_review_guard();

-- 5. Contagens do semáforo no condomínio do contexto (só para quem tem security.command com alcance).
CREATE FUNCTION cp.security_command_summary(health_stale_seconds integer,heartbeat_stale_seconds integer)
RETURNS TABLE(devices_total integer,devices_offline integer,devices_unknown integer,security_occurrences_open integer,
 critical_alerts_unlinked integer,incidents_open integer,incidents_critical_open integer,remote_opens_pending_review integer)
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE tenant uuid:=platform.current_tenant_id();
BEGIN
 IF NOT cp.partner_reach_grants(tenant,'security.command') THEN
  RAISE EXCEPTION 'visão da carteira exige security.command com alcance no condomínio' USING ERRCODE='42501';
 END IF;
 IF health_stale_seconds IS NULL OR health_stale_seconds<1 OR heartbeat_stale_seconds IS NULL OR heartbeat_stale_seconds<1 THEN
  RAISE EXCEPTION 'limites de atualização inválidos' USING ERRCODE='22023';
 END IF;
 RETURN QUERY
 WITH devices AS (
  -- Mesma classificação de /access-health (CredentialStore.health): sem resposta recente do conector ou do equipamento
  -- é "sem informação" (nunca "no ar" presumido); fora do ar informado ou sinal antigo é "fora do ar".
  SELECT CASE
    WHEN k.status<>'ACTIVE' OR ch.reachable IS NULL OR NOT ch.reachable
     OR ch.checked_at<=clock_timestamp()-make_interval(secs=>health_stale_seconds) THEN 'UNKNOWN'
    WHEN h.online IS NULL OR h.observed_at<=clock_timestamp()-make_interval(secs=>health_stale_seconds) THEN 'UNKNOWN'
    WHEN NOT h.online THEN 'OFFLINE'
    WHEN h.last_heartbeat IS NULL THEN 'UNKNOWN'
    WHEN h.last_heartbeat<clock_timestamp()-make_interval(secs=>heartbeat_stale_seconds) THEN 'OFFLINE'
    ELSE 'ONLINE' END AS state
  FROM cp.access_devices d
  JOIN cp.access_connectors k ON k.tenant_id=d.tenant_id AND k.id=d.connector_id
  LEFT JOIN cp.access_device_health h ON h.tenant_id=d.tenant_id AND h.device_id=d.id
  LEFT JOIN cp.access_connector_health ch ON ch.tenant_id=d.tenant_id AND ch.connector_id=d.connector_id
  WHERE d.tenant_id=tenant AND d.status='ACTIVE')
 SELECT (SELECT count(*) FROM devices)::integer,
  (SELECT count(*) FROM devices WHERE state='OFFLINE')::integer,
  (SELECT count(*) FROM devices WHERE state='UNKNOWN')::integer,
  (SELECT count(*) FROM cp.concierge_occurrences o WHERE o.tenant_id=tenant AND o.category='SECURITY' AND o.state='OPEN')::integer,
  -- Alerta crítico aberto: evento CRITICAL das últimas 24 h que nenhum incidente do parceiro anexou (os eventos do
  -- próprio incidente não contam).
  (SELECT count(*) FROM cp.security_log s WHERE s.tenant_id=tenant AND s.severity='CRITICAL'
    AND s.recorded_at>clock_timestamp()-interval '24 hours' AND s.event_type NOT LIKE 'INCIDENT\_%'
    AND NOT EXISTS(SELECT 1 FROM cp.incident_links l WHERE l.tenant_id=s.tenant_id AND l.log_event_id=s.id))::integer,
  (SELECT count(*) FROM cp.incidents i WHERE i.tenant_id=tenant AND i.state='OPEN')::integer,
  (SELECT count(*) FROM cp.incidents i WHERE i.tenant_id=tenant AND i.state='OPEN' AND i.severity='CRITICAL')::integer,
  -- Revisão é por amostragem (§8.2): indicador dos últimos 7 dias, sem cor.
  (SELECT count(*) FROM cp.security_log s WHERE s.tenant_id=tenant AND s.event_type='REMOTE_OPEN'
    AND s.recorded_at>clock_timestamp()-interval '7 days'
    AND NOT EXISTS(SELECT 1 FROM cp.security_log r WHERE r.tenant_id=s.tenant_id AND r.event_type='REMOTE_OPEN_REVIEWED'
     AND r.subject_id=s.id))::integer;
END $$;
REVOKE ALL ON FUNCTION cp.security_command_summary(integer,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_command_summary(integer,integer) TO cp_api;
