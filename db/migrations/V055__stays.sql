-- CPX-015 a CPX-019 (PRD complementar Segurança v1.0, E1 §7.2, §5.2 e fluxo §8.1; ADR-0011): permanência e saída.
--  - cp.stays: toda entrada registrada abre uma permanência e a saída a fecha. Fonte de entrada nesta versão: o
--    registro manual da portaria (V014, MANUAL_IN) — por chegada autorizada (V013), por convite (V013) ou por janela de
--    prestador recorrente (V022, convite com rule_id). Eventos de equipamento (V027) não abrem permanência: a credencial
--    é sempre de um vínculo de morador (V026, membership_id obrigatório) e o evento não identifica visitante; inferir
--    presença de passagem é proibido (R5.10). Facial/QR de visitante entra quando existir credencial de visitante (E1.8).
--  - A permanência nasce por gatilho na mesma instrução da entrada; tudo é derivado pelo banco (unidade, pessoa, tipo,
--    base, quem autorizou, horários). Saída manual (MANUAL_OUT) fecha; correção da entrada anula; correção da saída
--    reabre. O morador adulto da unidade (RESIDENT/RESPONSIBLE) confirma a saída pelo app (CPX-018).
--  - Saída prevista (E1.2): convite → fim do convite; janela de prestador → fim da janela; chegada sem convite → entrada
--    + tempo máximo do tipo (regra do condomínio, cp.stay_rules, ou padrão provisório da plataforma). Tolerâncias N1/N2
--    do tipo, fixadas na abertura. Herança do padrão do parceiro fica pendente (docs/PERMANENCIAS.md).
--  - Alertas (CPX-016): o worker (cp_worker) marca STAY_DUE, STAY_OVERDUE_L1 e STAY_OVERDUE_L2 uma vez cada, no horário
--    ou depois (nunca antes; o banco confere), só para permanência aberta e sem justificativa. Repetir não grava nada.
--  - Log de Segurança (V050, §5.2): STAY_OPENED, STAY_CLOSED, STAY_DUE, STAY_OVERDUE_L1/L2 e STAY_JUSTIFIED na mesma
--    transação (gatilho de restrição adiado, como as demais fontes); RULE_CHANGED na regra de tempo do condomínio.
--  - Leitura: portaria (concierge.operate), parceiro com alcance (security.command, ops.shift.supervise,
--    ops.remote.operate; V049) e a unidade (mesmo escopo do histórico de acesso). Nada é apagado.

-- 1. Tipos e padrões.
CREATE FUNCTION cp.stay_kinds() RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$ SELECT ARRAY['VISITOR','SERVICE_PROVIDER','DELIVERY','CONSTRUCTION'] $$;
REVOKE ALL ON FUNCTION cp.stay_kinds() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.stay_kinds() TO cp_api,cp_worker;

-- Padrão provisório da plataforma (minutos), usado quando o condomínio não configurou o tipo. Valores técnicos a
-- confirmar no piloto (PRD §13: "Definir no piloto"); docs/PERMANENCIAS.md.
CREATE FUNCTION cp.stay_default_rule(kind text,OUT max_minutes integer,OUT l1_after_minutes integer,OUT l2_after_minutes integer)
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT CASE kind WHEN 'DELIVERY' THEN 30 WHEN 'SERVICE_PROVIDER' THEN 480 WHEN 'CONSTRUCTION' THEN 600 ELSE 240 END,15,60 $$;
REVOKE ALL ON FUNCTION cp.stay_default_rule(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.stay_default_rule(text) TO cp_api;

-- Regra de tempo por tipo no condomínio (gestão, policy.edit, AAL2). Muda pela versão; nunca apagada.
CREATE TABLE cp.stay_rules (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 kind text NOT NULL CHECK(kind=ANY(cp.stay_kinds())),
 max_minutes integer NOT NULL CHECK(max_minutes BETWEEN 5 AND 1440),
 l1_after_minutes integer NOT NULL CHECK(l1_after_minutes BETWEEN 0 AND 720),
 l2_after_minutes integer NOT NULL CHECK(l2_after_minutes BETWEEN 1 AND 1440),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 updated_by uuid NOT NULL REFERENCES platform.accounts(id),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,kind),
 CHECK(l2_after_minutes>l1_after_minutes)
);

CREATE FUNCTION cp.stay_rule_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.updated_by IS DISTINCT FROM platform.current_actor_id() THEN
  RAISE EXCEPTION 'regra de permanência só em nome da própria conta' USING ERRCODE='42501';
 END IF;
 IF TG_OP='INSERT' THEN NEW.version:=0;
 ELSE
  IF NEW.tenant_id<>OLD.tenant_id OR NEW.kind<>OLD.kind OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'regra de permanência muda só pela versão' USING ERRCODE='23514';
  END IF;
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.stay_rule_guard() FROM PUBLIC;
CREATE TRIGGER stay_rules_guard BEFORE INSERT OR UPDATE ON cp.stay_rules FOR EACH ROW EXECUTE FUNCTION cp.stay_rule_guard();

-- Regra efetiva do tipo no condomínio (executa como quem consulta; regra invisível cai no padrão).
CREATE FUNCTION cp.stay_rule(target_tenant uuid,target_kind text,OUT max_minutes integer,OUT l1_after_minutes integer,
 OUT l2_after_minutes integer,OUT rule_source text)
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT coalesce(r.max_minutes,d.max_minutes),coalesce(r.l1_after_minutes,d.l1_after_minutes),coalesce(r.l2_after_minutes,d.l2_after_minutes),
  CASE WHEN r.kind IS NULL THEN 'PLATFORM_DEFAULT' ELSE 'TENANT_RULE' END
 FROM cp.stay_default_rule(target_kind) d
 LEFT JOIN cp.stay_rules r ON r.tenant_id=target_tenant AND r.kind=target_kind $$;
REVOKE ALL ON FUNCTION cp.stay_rule(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.stay_rule(uuid,text) TO cp_api;

-- Tipo informado pela portaria na entrada (opcional; sem ele, VISITOR). Janela de prestador é sempre SERVICE_PROVIDER.
ALTER TABLE cp.access_events ADD COLUMN stay_kind text,
 ADD CONSTRAINT access_events_stay_kind CHECK(stay_kind IS NULL OR (event_type='MANUAL_IN' AND stay_kind=ANY(cp.stay_kinds())));
GRANT INSERT(stay_kind) ON cp.access_events TO cp_api;

-- 2. Permanências.
CREATE TABLE cp.stays (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,
 visitor_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind=ANY(cp.stay_kinds())),
 -- Como a entrada foi registrada (PRD E1.1: manual, facial, QR, link). Nesta versão só MANUAL (V014, portaria).
 origin text NOT NULL CHECK(origin IN ('MANUAL','FACIAL','QR','LINK')),
 basis text NOT NULL CHECK(basis IN ('ARRIVAL','PERMIT','RULE')),
 entry_event_id uuid NOT NULL,
 arrival_id uuid,
 permit_id uuid,
 rule_id uuid,
 -- Quem autorizou: autor do convite/regra ou quem decidiu a chegada.
 authorized_by uuid REFERENCES platform.accounts(id),
 entered_at timestamptz NOT NULL,
 expected_exit_at timestamptz NOT NULL,
 expected_source text NOT NULL CHECK(expected_source IN ('PERMIT','RULE','TENANT_RULE','PLATFORM_DEFAULT')),
 rule_source text NOT NULL CHECK(rule_source IN ('TENANT_RULE','PLATFORM_DEFAULT')),
 l1_at timestamptz NOT NULL,
 l2_at timestamptz NOT NULL,
 state text NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','CLOSED','VOIDED')),
 closure text CHECK(closure IN ('EXIT_RECORDED','RESIDENT_CONFIRMED','ENTRY_CORRECTED')),
 exit_event_id uuid,
 closed_at timestamptz,
 closed_by uuid REFERENCES platform.accounts(id),
 due_alerted_at timestamptz,
 l1_alerted_at timestamptz,
 l2_alerted_at timestamptz,
 justification text CHECK(justification IS NULL OR length(btrim(justification)) BETWEEN 3 AND 500),
 justified_at timestamptz,
 justified_by uuid REFERENCES platform.accounts(id),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,entry_event_id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,visitor_id) REFERENCES cp.visitors(tenant_id,id),
 FOREIGN KEY(tenant_id,entry_event_id) REFERENCES cp.access_events(tenant_id,id),
 FOREIGN KEY(tenant_id,exit_event_id) REFERENCES cp.access_events(tenant_id,id),
 FOREIGN KEY(tenant_id,arrival_id) REFERENCES cp.arrival_requests(tenant_id,id),
 FOREIGN KEY(tenant_id,permit_id) REFERENCES cp.visit_permits(tenant_id,id),
 FOREIGN KEY(tenant_id,rule_id) REFERENCES cp.visit_rules(tenant_id,id),
 CHECK(isfinite(entered_at) AND expected_exit_at>entered_at AND l1_at>=expected_exit_at AND l2_at>l1_at),
 CHECK((basis='ARRIVAL')=(arrival_id IS NOT NULL) AND (basis IN ('PERMIT','RULE'))=(permit_id IS NOT NULL) AND (basis='RULE')=(rule_id IS NOT NULL)),
 CHECK((state='OPEN')=(closure IS NULL) AND (state='OPEN')=(closed_at IS NULL) AND (state='OPEN')=(closed_by IS NULL)),
 CHECK(state<>'CLOSED' OR closure IN ('EXIT_RECORDED','RESIDENT_CONFIRMED')),
 CHECK(state<>'VOIDED' OR closure='ENTRY_CORRECTED'),
 CHECK((closure='EXIT_RECORDED')<=(exit_event_id IS NOT NULL)),
 CHECK((justification IS NULL)=(justified_at IS NULL) AND (justification IS NULL)=(justified_by IS NULL)),
 CHECK(l1_alerted_at IS NULL OR due_alerted_at IS NOT NULL),
 CHECK(l2_alerted_at IS NULL OR l1_alerted_at IS NOT NULL)
);
CREATE INDEX stays_open ON cp.stays(tenant_id,expected_exit_at,id) WHERE state='OPEN';
CREATE INDEX stays_entered ON cp.stays(tenant_id,entered_at,id);
CREATE INDEX stays_by_unit ON cp.stays(tenant_id,unit_id,entered_at DESC,id);
CREATE INDEX stays_by_exit ON cp.stays(tenant_id,exit_event_id) WHERE exit_event_id IS NOT NULL;

-- Leitura pela equipe do parceiro com alcance (V049): comando, supervisor e operador remoto.
CREATE FUNCTION cp.stay_partner_reader() RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT platform.has_capability('security.command') OR platform.has_capability('ops.shift.supervise')
  OR platform.has_capability('ops.remote.operate') $$;
REVOKE ALL ON FUNCTION cp.stay_partner_reader() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.stay_partner_reader() TO cp_api;

-- Abertura: tudo vem da entrada gravada nesta mesma transação (nunca de entrada antiga) e da autorização dela.
-- Atualização: só as transições abaixo, cada uma conferida contra a fonte (registro de acesso, vínculo, capacidade ou
-- horário). Campos de abertura nunca mudam.
CREATE FUNCTION cp.stay_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 entry record; base record; eff record; now_ts timestamptz:=clock_timestamp(); fact record;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT x.id,x.unit_id,x.visitor_id,x.arrival_id,x.permit_id,x.event_type,x.stay_kind,x.occurred_at,x.recorded_by,
    (x.xmin::text=(pg_current_xact_id()::text::bigint%4294967296)::text) AS this_transaction
   INTO entry FROM cp.access_events x WHERE x.tenant_id=NEW.tenant_id AND x.id=NEW.entry_event_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'Entrada não visível.' USING ERRCODE='42501'; END IF;
  IF entry.event_type<>'MANUAL_IN' OR NOT entry.this_transaction THEN
   RAISE EXCEPTION 'Permanência só nasce da entrada registrada na mesma transação.' USING ERRCODE='23514';
  END IF;
  NEW.id:=coalesce(NEW.id,gen_random_uuid());
  NEW.unit_id:=entry.unit_id; NEW.visitor_id:=entry.visitor_id; NEW.origin:='MANUAL';
  NEW.entered_at:=entry.occurred_at;
  NEW.arrival_id:=entry.arrival_id; NEW.permit_id:=entry.permit_id; NEW.rule_id:=NULL;
  IF entry.permit_id IS NOT NULL THEN
   SELECT p.valid_until,p.rule_id,p.created_by INTO base FROM cp.visit_permits p WHERE p.tenant_id=NEW.tenant_id AND p.id=entry.permit_id;
   IF NOT FOUND THEN RAISE EXCEPTION 'Convite não visível.' USING ERRCODE='42501'; END IF;
   NEW.rule_id:=base.rule_id; NEW.authorized_by:=base.created_by;
   NEW.basis:=CASE WHEN base.rule_id IS NULL THEN 'PERMIT' ELSE 'RULE' END;
   IF base.rule_id IS NOT NULL AND coalesce(entry.stay_kind,'SERVICE_PROVIDER')<>'SERVICE_PROVIDER' THEN
    RAISE EXCEPTION 'Janela de prestador recorrente é sempre de prestador.' USING ERRCODE='23514';
   END IF;
   NEW.kind:=CASE WHEN base.rule_id IS NULL THEN coalesce(entry.stay_kind,'VISITOR') ELSE 'SERVICE_PROVIDER' END;
   NEW.expected_exit_at:=base.valid_until; NEW.expected_source:=NEW.basis;
  ELSE
   SELECT r.decided_by INTO base FROM cp.arrival_requests r WHERE r.tenant_id=NEW.tenant_id AND r.id=entry.arrival_id;
   IF NOT FOUND THEN RAISE EXCEPTION 'Chegada não visível.' USING ERRCODE='42501'; END IF;
   NEW.authorized_by:=base.decided_by; NEW.basis:='ARRIVAL'; NEW.kind:=coalesce(entry.stay_kind,'VISITOR');
  END IF;
  SELECT * INTO eff FROM cp.stay_rule(NEW.tenant_id,NEW.kind);
  IF NEW.basis='ARRIVAL' THEN
   NEW.expected_exit_at:=NEW.entered_at+make_interval(mins=>eff.max_minutes); NEW.expected_source:=eff.rule_source;
  END IF;
  NEW.rule_source:=eff.rule_source;
  NEW.l1_at:=NEW.expected_exit_at+make_interval(mins=>eff.l1_after_minutes);
  NEW.l2_at:=NEW.expected_exit_at+make_interval(mins=>eff.l2_after_minutes);
  NEW.state:='OPEN'; NEW.closure:=NULL; NEW.exit_event_id:=NULL; NEW.closed_at:=NULL; NEW.closed_by:=NULL;
  NEW.due_alerted_at:=NULL; NEW.l1_alerted_at:=NULL; NEW.l2_alerted_at:=NULL;
  NEW.justification:=NULL; NEW.justified_at:=NULL; NEW.justified_by:=NULL; NEW.version:=0;
  RETURN NEW;
 END IF;

 IF (NEW.id,NEW.tenant_id,NEW.unit_id,NEW.visitor_id,NEW.kind,NEW.origin,NEW.basis,NEW.entry_event_id,NEW.arrival_id,NEW.permit_id,
     NEW.rule_id,NEW.authorized_by,NEW.entered_at,NEW.expected_exit_at,NEW.expected_source,NEW.rule_source,NEW.l1_at,NEW.l2_at)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.unit_id,OLD.visitor_id,OLD.kind,OLD.origin,OLD.basis,OLD.entry_event_id,OLD.arrival_id,
     OLD.permit_id,OLD.rule_id,OLD.authorized_by,OLD.entered_at,OLD.expected_exit_at,OLD.expected_source,OLD.rule_source,OLD.l1_at,OLD.l2_at) THEN
  RAISE EXCEPTION 'Dados de abertura da permanência não mudam.' USING ERRCODE='42501';
 END IF;

 -- Alertas: cada nível uma vez, nunca antes do horário, só com permanência aberta e sem justificativa.
 IF (NEW.due_alerted_at,NEW.l1_alerted_at,NEW.l2_alerted_at) IS DISTINCT FROM (OLD.due_alerted_at,OLD.l1_alerted_at,OLD.l2_alerted_at) THEN
  IF OLD.state<>'OPEN' OR NEW.state<>'OPEN' OR OLD.justified_at IS NOT NULL
    OR (OLD.due_alerted_at IS NOT NULL AND NEW.due_alerted_at IS DISTINCT FROM OLD.due_alerted_at)
    OR (OLD.l1_alerted_at IS NOT NULL AND NEW.l1_alerted_at IS DISTINCT FROM OLD.l1_alerted_at)
    OR (OLD.l2_alerted_at IS NOT NULL AND NEW.l2_alerted_at IS DISTINCT FROM OLD.l2_alerted_at)
    OR (OLD.due_alerted_at IS NULL AND NEW.due_alerted_at IS NOT NULL AND now_ts<OLD.expected_exit_at)
    OR (OLD.l1_alerted_at IS NULL AND NEW.l1_alerted_at IS NOT NULL AND now_ts<OLD.l1_at)
    OR (OLD.l2_alerted_at IS NULL AND NEW.l2_alerted_at IS NOT NULL AND now_ts<OLD.l2_at) THEN
   RAISE EXCEPTION 'Alerta de permanência fora do horário ou repetido.' USING ERRCODE='23514';
  END IF;
  IF OLD.due_alerted_at IS NULL AND NEW.due_alerted_at IS NOT NULL THEN NEW.due_alerted_at:=now_ts; END IF;
  IF OLD.l1_alerted_at IS NULL AND NEW.l1_alerted_at IS NOT NULL THEN NEW.l1_alerted_at:=now_ts; END IF;
  IF OLD.l2_alerted_at IS NULL AND NEW.l2_alerted_at IS NOT NULL THEN NEW.l2_alerted_at:=now_ts; END IF;
 END IF;

 -- Justificativa (fluxo §8.1, passo 5): uma vez, com motivo, pela portaria ou pela equipe do parceiro com alcance.
 IF (NEW.justification,NEW.justified_by,NEW.justified_at) IS DISTINCT FROM (OLD.justification,OLD.justified_by,OLD.justified_at) THEN
  IF OLD.justification IS NOT NULL OR OLD.state<>'OPEN' OR NEW.state<>'OPEN' OR NEW.justification IS NULL
    OR NEW.justified_by IS DISTINCT FROM platform.current_actor_id()
    OR NOT (platform.has_capability('concierge.operate') OR cp.stay_partner_reader()) THEN
   RAISE EXCEPTION 'Justificativa de permanência não permitida.' USING ERRCODE='42501';
  END IF;
  NEW.justification:=btrim(NEW.justification); NEW.justified_at:=now_ts;
 END IF;

 IF NEW.state IS DISTINCT FROM OLD.state OR NEW.closure IS DISTINCT FROM OLD.closure OR NEW.exit_event_id IS DISTINCT FROM OLD.exit_event_id
   OR NEW.closed_by IS DISTINCT FROM OLD.closed_by OR NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
  IF OLD.state='OPEN' AND NEW.state='CLOSED' AND NEW.closure='EXIT_RECORDED' THEN
   -- Saída manual válida (sem correção) da própria entrada.
   SELECT x.occurred_at,x.recorded_by INTO fact FROM cp.access_events x WHERE x.tenant_id=OLD.tenant_id AND x.id=NEW.exit_event_id
    AND x.event_type='MANUAL_OUT' AND x.entry_event_id=OLD.entry_event_id AND NOT EXISTS(SELECT 1 FROM cp.access_events c
     WHERE c.tenant_id=x.tenant_id AND c.corrects_event_id=x.id);
   IF NOT FOUND THEN RAISE EXCEPTION 'Saída sem registro válido.' USING ERRCODE='23514'; END IF;
   NEW.closed_at:=fact.occurred_at; NEW.closed_by:=fact.recorded_by;
  ELSIF OLD.state='OPEN' AND NEW.state='CLOSED' AND NEW.closure='RESIDENT_CONFIRMED' THEN
   -- CPX-018: só morador adulto (RESIDENT/RESPONSIBLE) com vínculo vigente na unidade da permanência.
   IF NEW.exit_event_id IS NOT NULL OR NEW.closed_by IS DISTINCT FROM platform.current_actor_id()
     OR NOT cp.visit_member(OLD.tenant_id,OLD.unit_id,NULL) THEN
    RAISE EXCEPTION 'Confirmação de saída só pelo morador da unidade.' USING ERRCODE='42501';
   END IF;
   NEW.closed_at:=now_ts;
  ELSIF OLD.state IN ('OPEN','CLOSED') AND NEW.state='VOIDED' AND NEW.closure='ENTRY_CORRECTED' THEN
   SELECT c.occurred_at,c.recorded_by INTO fact FROM cp.access_events c WHERE c.tenant_id=OLD.tenant_id
    AND c.corrects_event_id=OLD.entry_event_id AND c.event_type='CORRECTION';
   IF NOT FOUND THEN RAISE EXCEPTION 'Anulação sem correção da entrada.' USING ERRCODE='23514'; END IF;
   NEW.closed_at:=fact.occurred_at; NEW.closed_by:=fact.recorded_by; NEW.exit_event_id:=OLD.exit_event_id;
  ELSIF OLD.state='CLOSED' AND OLD.closure='EXIT_RECORDED' AND NEW.state='OPEN' THEN
   -- Saída corrigida: a permanência volta a ficar aberta (níveis de alerta já gravados não se repetem).
   IF NOT EXISTS(SELECT 1 FROM cp.access_events c WHERE c.tenant_id=OLD.tenant_id AND c.corrects_event_id=OLD.exit_event_id
     AND c.event_type='CORRECTION') THEN
    RAISE EXCEPTION 'Reabertura sem correção da saída.' USING ERRCODE='23514';
   END IF;
   NEW.closure:=NULL; NEW.exit_event_id:=NULL; NEW.closed_at:=NULL; NEW.closed_by:=NULL;
  ELSE
   RAISE EXCEPTION 'Transição de permanência não permitida.' USING ERRCODE='23514';
  END IF;
 END IF;
 NEW.version:=OLD.version+1;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.stay_guard() FROM PUBLIC;
CREATE TRIGGER stays_guard BEFORE INSERT OR UPDATE ON cp.stays FOR EACH ROW EXECUTE FUNCTION cp.stay_guard();
CREATE FUNCTION cp.stay_no_delete() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 RAISE EXCEPTION 'permanência não é apagada: % recusado',TG_OP USING ERRCODE='42501';
END $$;
REVOKE ALL ON FUNCTION cp.stay_no_delete() FROM PUBLIC;
CREATE TRIGGER stays_no_delete BEFORE DELETE ON cp.stays FOR EACH ROW EXECUTE FUNCTION cp.stay_no_delete();
CREATE TRIGGER stay_rules_no_delete BEFORE DELETE ON cp.stay_rules FOR EACH ROW EXECUTE FUNCTION cp.stay_no_delete();

-- 3. Ligação com o registro manual (V014): entrada abre; saída fecha; correção da entrada anula; correção da saída
-- reabre. Mesma instrução e mesma transação, como quem registra (a portaria).
CREATE FUNCTION cp.stay_from_access_event() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE target_type text;
BEGIN
 IF NEW.event_type='MANUAL_IN' THEN
  INSERT INTO cp.stays(tenant_id,entry_event_id) VALUES(NEW.tenant_id,NEW.id);
 ELSIF NEW.event_type='MANUAL_OUT' THEN
  UPDATE cp.stays SET state='CLOSED',closure='EXIT_RECORDED',exit_event_id=NEW.id,closed_by=NEW.recorded_by
   WHERE tenant_id=NEW.tenant_id AND entry_event_id=NEW.entry_event_id AND state='OPEN';
 ELSE
  SELECT x.event_type INTO target_type FROM cp.access_events x WHERE x.tenant_id=NEW.tenant_id AND x.id=NEW.corrects_event_id;
  IF target_type='MANUAL_IN' THEN
   UPDATE cp.stays SET state='VOIDED',closure='ENTRY_CORRECTED',closed_by=NEW.recorded_by
    WHERE tenant_id=NEW.tenant_id AND entry_event_id=NEW.corrects_event_id AND state<>'VOIDED';
  ELSIF target_type='MANUAL_OUT' THEN
   UPDATE cp.stays SET state='OPEN',closure=NULL,exit_event_id=NULL,closed_by=NULL
    WHERE tenant_id=NEW.tenant_id AND exit_event_id=NEW.corrects_event_id AND state='CLOSED' AND closure='EXIT_RECORDED';
  END IF;
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.stay_from_access_event() FROM PUBLIC;
CREATE TRIGGER access_events_stays AFTER INSERT ON cp.access_events FOR EACH ROW EXECUTE FUNCTION cp.stay_from_access_event();

-- 4. Grants e RLS. Aplicação: abre só pela entrada (gatilho); muda estado, justificativa e confirmação; nunca apaga.
REVOKE ALL ON cp.stays,cp.stay_rules FROM PUBLIC;
GRANT SELECT ON cp.stays,cp.stay_rules TO cp_api;
GRANT INSERT(tenant_id,entry_event_id) ON cp.stays TO cp_api;
GRANT UPDATE(state,closure,exit_event_id,closed_by,justification,justified_by) ON cp.stays TO cp_api;
GRANT INSERT(tenant_id,kind,max_minutes,l1_after_minutes,l2_after_minutes,updated_by) ON cp.stay_rules TO cp_api;
GRANT UPDATE(max_minutes,l1_after_minutes,l2_after_minutes,version,updated_by) ON cp.stay_rules TO cp_api;
-- Worker: só o necessário para os alertas (sem pessoa, unidade nem justificativa).
GRANT SELECT(id,tenant_id,state,expected_exit_at,l1_at,l2_at,due_alerted_at,l1_alerted_at,l2_alerted_at,justified_at) ON cp.stays TO cp_worker;
GRANT UPDATE(due_alerted_at,l1_alerted_at,l2_alerted_at) ON cp.stays TO cp_worker;
ALTER TABLE cp.stays ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.stays FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.stay_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.stay_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.stays TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY stays_read_scope ON cp.stays AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.stay_partner_reader() OR cp.visit_member(tenant_id,unit_id,'decide'));
CREATE POLICY stays_insert ON cp.stays AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate'));
CREATE POLICY stays_change ON cp.stays AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.stay_partner_reader() OR cp.visit_member(tenant_id,unit_id,NULL))
 WITH CHECK(platform.has_capability('concierge.operate') OR cp.stay_partner_reader() OR cp.visit_member(tenant_id,unit_id,NULL));
CREATE POLICY stays_worker ON cp.stays TO cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY rental_owner_hidden ON cp.stays AS RESTRICTIVE FOR ALL TO cp_api
 USING(NOT cp.rental_owner_hidden(tenant_id,unit_id)) WITH CHECK(NOT cp.rental_owner_hidden(tenant_id,unit_id));
-- Regras: leitura no condomínio do contexto (a portaria precisa delas para abrir a permanência); escrita pela gestão.
CREATE POLICY tenant_boundary ON cp.stay_rules TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY stay_rules_insert ON cp.stay_rules AS RESTRICTIVE FOR INSERT TO cp_api WITH CHECK(platform.has_capability('policy.edit'));
CREATE POLICY stay_rules_update ON cp.stay_rules AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('policy.edit')) WITH CHECK(platform.has_capability('policy.edit'));

-- A pessoa de uma permanência visível é visível a quem vê a permanência (painel do parceiro e relatório do dia).
DROP POLICY visitors_read_scope ON cp.visitors;
CREATE POLICY visitors_read_scope ON cp.visitors AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR created_by=platform.current_actor_id()
  OR EXISTS(SELECT 1 FROM cp.visit_permits p WHERE p.tenant_id=cp.visitors.tenant_id AND p.visitor_id=cp.visitors.id)
  OR EXISTS(SELECT 1 FROM cp.arrival_requests r WHERE r.tenant_id=cp.visitors.tenant_id AND r.visitor_id=cp.visitors.id)
  OR EXISTS(SELECT 1 FROM cp.visit_rules v WHERE v.tenant_id=cp.visitors.tenant_id AND v.visitor_id=cp.visitors.id)
  OR EXISTS(SELECT 1 FROM cp.stays s WHERE s.tenant_id=cp.visitors.tenant_id AND s.visitor_id=cp.visitors.id));

-- 5. Alertas N1/N2 (CPX-016) pelo worker, no condomínio do contexto. Idempotente: cada nível só uma vez, sob a trava da
-- linha (quem chega depois reavalia e não acha nada). Devolve quantas permanências receberam algum nível.
CREATE FUNCTION cp.stay_alert_pass() RETURNS integer
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE tenant uuid:=platform.current_tenant_id(); now_ts timestamptz:=clock_timestamp(); changed integer;
BEGIN
 IF tenant IS NULL THEN RAISE EXCEPTION 'alertas exigem condomínio no contexto' USING ERRCODE='42501'; END IF;
 UPDATE cp.stays s SET
   due_alerted_at=CASE WHEN s.due_alerted_at IS NULL AND s.expected_exit_at<=now_ts THEN now_ts ELSE s.due_alerted_at END,
   l1_alerted_at=CASE WHEN s.l1_alerted_at IS NULL AND s.l1_at<=now_ts THEN now_ts ELSE s.l1_alerted_at END,
   l2_alerted_at=CASE WHEN s.l2_alerted_at IS NULL AND s.l2_at<=now_ts THEN now_ts ELSE s.l2_alerted_at END
  WHERE s.tenant_id=tenant AND s.state='OPEN' AND s.justified_at IS NULL
   AND ((s.due_alerted_at IS NULL AND s.expected_exit_at<=now_ts) OR (s.l1_alerted_at IS NULL AND s.l1_at<=now_ts)
    OR (s.l2_alerted_at IS NULL AND s.l2_at<=now_ts));
 GET DIAGNOSTICS changed=ROW_COUNT;
 RETURN changed;
END $$;
REVOKE ALL ON FUNCTION cp.stay_alert_pass() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.stay_alert_pass() TO cp_worker;

-- 6. Log de Segurança (V050, R5.4): eventos de permanência na mesma transação, no COMMIT.
CREATE FUNCTION cp.security_log_stay_event(s cp.stays,event text,level text,origin text,motive text,extra jsonb) RETURNS void
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF current_setting('session_replication_role')='replica' THEN RETURN; END IF;
 INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,unit_id,reason,correlation_id,details)
 VALUES(s.tenant_id,event,level,origin,'STAY',s.id,s.unit_id,
  CASE WHEN motive IS NULL OR btrim(motive)='' THEN NULL ELSE left(btrim(motive),500) END,s.id,
  jsonb_strip_nulls(jsonb_build_object('stayId',s.id,'entryEventId',s.entry_event_id,'visitorId',s.visitor_id,'kind',s.kind,
   'origin',s.origin,'basis',s.basis,'version',s.version)||coalesce(extra,'{}'::jsonb)));
END $$;
REVOKE ALL ON FUNCTION cp.security_log_stay_event(cp.stays,text,text,text,text,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_stay_event(cp.stays,text,text,text,text,jsonb) TO cp_api,cp_worker;

CREATE FUNCTION cp.security_log_from_stay() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE utc text:='YYYY-MM-DD"T"HH24:MI:SS.US"Z"';
BEGIN
 IF TG_OP='INSERT' THEN
  PERFORM cp.security_log_stay_event(NEW,'STAY_OPENED','INFO','CONCIERGE',NULL,jsonb_build_object(
   'expectedExitAt',to_char(NEW.expected_exit_at AT TIME ZONE 'UTC',utc),'expectedSource',NEW.expected_source,'ruleSource',NEW.rule_source,
   'l1At',to_char(NEW.l1_at AT TIME ZONE 'UTC',utc),'l2At',to_char(NEW.l2_at AT TIME ZONE 'UTC',utc)));
  RETURN NULL;
 END IF;
 IF OLD.due_alerted_at IS NULL AND NEW.due_alerted_at IS NOT NULL THEN
  PERFORM cp.security_log_stay_event(NEW,'STAY_DUE','INFO','SYSTEM',NULL,jsonb_build_object(
   'scheduledAt',to_char(NEW.expected_exit_at AT TIME ZONE 'UTC',utc),
   'delayMs',floor(extract(epoch FROM NEW.due_alerted_at-NEW.expected_exit_at)*1000)::bigint));
 END IF;
 IF OLD.l1_alerted_at IS NULL AND NEW.l1_alerted_at IS NOT NULL THEN
  PERFORM cp.security_log_stay_event(NEW,'STAY_OVERDUE_L1','ATTENTION','SYSTEM',NULL,jsonb_build_object(
   'scheduledAt',to_char(NEW.l1_at AT TIME ZONE 'UTC',utc),'delayMs',floor(extract(epoch FROM NEW.l1_alerted_at-NEW.l1_at)*1000)::bigint));
 END IF;
 IF OLD.l2_alerted_at IS NULL AND NEW.l2_alerted_at IS NOT NULL THEN
  PERFORM cp.security_log_stay_event(NEW,'STAY_OVERDUE_L2','ATTENTION','SYSTEM',NULL,jsonb_build_object(
   'scheduledAt',to_char(NEW.l2_at AT TIME ZONE 'UTC',utc),'delayMs',floor(extract(epoch FROM NEW.l2_alerted_at-NEW.l2_at)*1000)::bigint));
 END IF;
 IF OLD.justified_at IS NULL AND NEW.justified_at IS NOT NULL THEN
  PERFORM cp.security_log_stay_event(NEW,'STAY_JUSTIFIED','INFO',
   CASE WHEN platform.has_capability('concierge.operate') THEN 'CONCIERGE' ELSE 'REMOTE_CENTRAL' END,NEW.justification,NULL);
 END IF;
 IF OLD.state<>NEW.state THEN
  IF NEW.state='OPEN' THEN
   PERFORM cp.security_log_stay_event(NEW,'STAY_OPENED','ATTENTION','CONCIERGE',NULL,jsonb_build_object('reopened',true,
    'correctedExitEventId',OLD.exit_event_id));
  ELSE
   PERFORM cp.security_log_stay_event(NEW,'STAY_CLOSED',CASE WHEN NEW.state='VOIDED' THEN 'ATTENTION' ELSE 'INFO' END,
    CASE NEW.closure WHEN 'RESIDENT_CONFIRMED' THEN 'RESIDENT_APP' ELSE 'CONCIERGE' END,NULL,
    jsonb_build_object('closure',NEW.closure,'exitEventId',NEW.exit_event_id,'previousState',OLD.state,
     'closedAt',to_char(NEW.closed_at AT TIME ZONE 'UTC',utc),
     'overdue',NEW.closed_at>NEW.expected_exit_at));
  END IF;
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_stay() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER stays_security_log AFTER INSERT OR UPDATE ON cp.stays DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_stay();

-- Regra de tempo alterada (RULE_CHANGED), com os valores e a versão.
CREATE FUNCTION cp.security_log_from_stay_rule() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM cp.security_log_append(NEW.tenant_id,'RULE_CHANGED','INFO','MANAGEMENT','STAY_RULE',NULL,NULL,NULL,NULL,NULL,NULL,
  jsonb_build_object('rule','STAY_TIME','kind',NEW.kind,'maxMinutes',NEW.max_minutes,'l1AfterMinutes',NEW.l1_after_minutes,
   'l2AfterMinutes',NEW.l2_after_minutes,'version',NEW.version));
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_from_stay_rule() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER stay_rules_security_log AFTER INSERT OR UPDATE ON cp.stay_rules DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION cp.security_log_from_stay_rule();
