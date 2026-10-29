-- Pedidos da unidade e conversa vinculada (CP-033, sem anexos). Morador ou responsável com vínculo vigente abre um
-- pedido para a unidade (categoria, título e descrição) e conversa nele; a gestão (capacidade requests.manage, AAL2)
-- acompanha, define responsável da equipe e prazo comunicado, muda o andamento e registra a resolução. Comentário
-- interno nunca aparece para a unidade. Mensagens não mudam nem são apagadas; fechamento preserva o histórico e encerra
-- a conversa. A conversa pertence sempre ao pedido em que foi gravada.

CREATE TABLE cp.service_requests (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,
 requester_id uuid NOT NULL REFERENCES platform.accounts(id),
 -- Nome do vínculo de quem abriu, gravado pelo banco na abertura (a gestão de pedidos não lê vínculos residenciais).
 requester_name text NOT NULL CHECK(length(btrim(requester_name)) BETWEEN 1 AND 160),
 category text NOT NULL CHECK(category IN ('MAINTENANCE','CLEANING','SECURITY','NOISE','ADMINISTRATIVE','OTHER')),
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 3 AND 120),
 description text NOT NULL CHECK(length(btrim(description)) BETWEEN 1 AND 2000),
 state text NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','TRIAGED','IN_PROGRESS','WAITING_RESIDENT','RESOLVED','CLOSED')),
 assignee_membership_id uuid,
 due_on date,
 resolution text CHECK(resolution IS NULL OR length(btrim(resolution)) BETWEEN 1 AND 1000),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 resolved_at timestamptz,
 closed_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,assignee_membership_id) REFERENCES cp.memberships(tenant_id,id),
 -- Resolvido sempre com resolução e horário; fechado sempre com horário; reaberto perde a resolução (fica na conversa).
 CONSTRAINT service_requests_resolved CHECK((state='RESOLVED')<=(resolution IS NOT NULL AND resolved_at IS NOT NULL)),
 CONSTRAINT service_requests_open_fields CHECK(state IN ('RESOLVED','CLOSED') OR (resolution IS NULL AND resolved_at IS NULL)),
 CONSTRAINT service_requests_closed CHECK((state='CLOSED')=(closed_at IS NOT NULL))
);
CREATE INDEX service_requests_by_unit ON cp.service_requests(tenant_id,unit_id,created_at DESC,id);
CREATE INDEX service_requests_by_state ON cp.service_requests(tenant_id,state,updated_at DESC,id);

CREATE TABLE cp.request_messages (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 request_id uuid NOT NULL,
 author_id uuid NOT NULL REFERENCES platform.accounts(id),
 author_role text NOT NULL CHECK(author_role IN ('UNIT','STAFF')),
 -- Nome do vínculo do autor no momento da mensagem, gravado pelo banco.
 author_name text NOT NULL CHECK(length(btrim(author_name)) BETWEEN 1 AND 160),
 body text NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 2000),
 visibility text NOT NULL CHECK(visibility IN ('RESIDENT','INTERNAL')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,request_id) REFERENCES cp.service_requests(tenant_id,id),
 -- A unidade só escreve mensagem visível; comentário interno é sempre da equipe.
 CONSTRAINT request_messages_internal_staff CHECK(visibility<>'INTERNAL' OR author_role='STAFF')
);
CREATE INDEX request_messages_by_request ON cp.request_messages(tenant_id,request_id,created_at,id);

-- Morador ou responsável com vínculo vigente na unidade ativa e conta ativa (dependente e proprietário ficam fora
-- neste recorte: nenhum fluxo concede permissão ao dependente).
CREATE FUNCTION cp.request_unit_member(target_tenant uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
   AND m.role IN ('RESIDENT','RESPONSIBLE') AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) $$;
REVOKE ALL ON FUNCTION cp.request_unit_member(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.request_unit_member(uuid,uuid) TO cp_api;

-- Grants mínimos: a unidade abre o pedido (conteúdo e unidade); a gestão altera só andamento, responsável, prazo e
-- resolução; horários, autoria e conteúdo do pedido não mudam pela aplicação.
REVOKE ALL ON cp.service_requests,cp.request_messages FROM PUBLIC;
GRANT SELECT ON cp.service_requests,cp.request_messages TO cp_api;
GRANT INSERT(id,tenant_id,unit_id,requester_id,category,title,description) ON cp.service_requests TO cp_api;
GRANT UPDATE(state,assignee_membership_id,due_on,resolution,version) ON cp.service_requests TO cp_api;
GRANT INSERT(id,tenant_id,request_id,author_id,author_role,body,visibility) ON cp.request_messages TO cp_api;
ALTER TABLE cp.service_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.service_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.request_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.request_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.service_requests TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.request_messages TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Quem gerencia pedidos enxerga a equipe vigente do condomínio (gestão e portaria) para escolher o responsável; nenhum
-- vínculo residencial a mais.
CREATE POLICY membership_request_team_read ON cp.memberships FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND platform.has_capability('requests.manage')
  AND role IN ('MANAGER','CONCIERGE') AND status='ACTIVE');

-- Pedido: a gestão vê o condomínio; a unidade, os pedidos dela (enquanto mantém o vínculo).
CREATE POLICY service_requests_read_scope ON cp.service_requests AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('requests.manage') OR cp.request_unit_member(tenant_id,unit_id));
CREATE POLICY service_requests_open ON cp.service_requests AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(cp.request_unit_member(tenant_id,unit_id) AND requester_id=platform.current_actor_id() AND state='OPEN');
CREATE POLICY service_requests_manage ON cp.service_requests AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('requests.manage')) WITH CHECK(platform.has_capability('requests.manage'));

-- Mensagem: a unidade vê só as visíveis dos pedidos que vê; a gestão vê todas. Escrita em nome da própria conta: a
-- unidade como UNIT (visível) em pedido da própria unidade; a equipe como STAFF (visível ou interna).
CREATE POLICY request_messages_read_scope ON cp.request_messages AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('requests.manage') OR (visibility='RESIDENT' AND EXISTS(SELECT 1 FROM cp.service_requests r
  WHERE r.tenant_id=cp.request_messages.tenant_id AND r.id=cp.request_messages.request_id AND cp.request_unit_member(r.tenant_id,r.unit_id))));
CREATE POLICY request_messages_write ON cp.request_messages AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(author_id=platform.current_actor_id() AND (
  (author_role='STAFF' AND platform.has_capability('requests.manage'))
  OR (author_role='UNIT' AND visibility='RESIDENT' AND EXISTS(SELECT 1 FROM cp.service_requests r
   WHERE r.tenant_id=cp.request_messages.tenant_id AND r.id=cp.request_messages.request_id AND cp.request_unit_member(r.tenant_id,r.unit_id)))));

-- Andamento pela versão lida (+1). Conteúdo, unidade e autoria não mudam. Transições: aberto -> em triagem, em
-- andamento ou fechado; em triagem -> em andamento, aguardando a unidade, resolvido ou fechado; em andamento ->
-- aguardando a unidade, resolvido ou fechado; aguardando a unidade -> em andamento, resolvido ou fechado; resolvido ->
-- em andamento (reaberto) ou fechado. Fechado é final. Responsável é vínculo vigente da equipe (gestão ou portaria).
CREATE FUNCTION cp.service_request_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  NEW.state := 'OPEN'; NEW.version := 0; NEW.assignee_membership_id := NULL; NEW.due_on := NULL; NEW.resolution := NULL;
  NEW.created_at := clock_timestamp(); NEW.updated_at := NEW.created_at; NEW.resolved_at := NULL; NEW.closed_at := NULL;
  -- Executa como quem abre: só o próprio vínculo é legível, então pedido em nome de outra conta não encontra nome.
  NEW.requester_name := NULL;
  SELECT m.display_name INTO NEW.requester_name FROM cp.memberships m
   WHERE m.tenant_id=NEW.tenant_id AND m.unit_id=NEW.unit_id AND m.account_id=NEW.requester_id AND m.status='ACTIVE'
    AND m.role IN ('RESIDENT','RESPONSIBLE') ORDER BY m.role DESC LIMIT 1;
  IF NEW.requester_name IS NULL THEN RAISE EXCEPTION 'Pedido somente pelo próprio vínculo na unidade.' USING ERRCODE='42501'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.requester_name<>OLD.requester_name THEN RAISE EXCEPTION 'Conteúdo do pedido não muda.' USING ERRCODE='23514'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.unit_id<>OLD.unit_id OR NEW.requester_id<>OLD.requester_id OR NEW.category<>OLD.category
    OR NEW.title<>OLD.title OR NEW.description<>OLD.description OR NEW.created_at<>OLD.created_at THEN
  RAISE EXCEPTION 'Conteúdo do pedido não muda.' USING ERRCODE='23514';
 END IF;
 IF NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Versão do pedido fora de ordem.' USING ERRCODE='23514'; END IF;
 IF OLD.state='CLOSED' THEN RAISE EXCEPTION 'Pedido fechado não muda.' USING ERRCODE='23514'; END IF;
 -- Mesma trava da conversa: mensagem e fechamento do mesmo pedido ficam em série.
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.service_requests:'||NEW.tenant_id::text||':'||NEW.id::text,0));
 IF OLD.state='RESOLVED' AND NEW.state='RESOLVED' AND NEW.resolution IS DISTINCT FROM OLD.resolution THEN
  RAISE EXCEPTION 'Resolução registrada não muda; reabra o pedido para alterar.' USING ERRCODE='23514';
 END IF;
 IF NEW.state<>OLD.state AND NOT (
     (OLD.state='OPEN' AND NEW.state IN ('TRIAGED','IN_PROGRESS','CLOSED'))
  OR (OLD.state='TRIAGED' AND NEW.state IN ('IN_PROGRESS','WAITING_RESIDENT','RESOLVED','CLOSED'))
  OR (OLD.state='IN_PROGRESS' AND NEW.state IN ('WAITING_RESIDENT','RESOLVED','CLOSED'))
  OR (OLD.state='WAITING_RESIDENT' AND NEW.state IN ('IN_PROGRESS','RESOLVED','CLOSED'))
  OR (OLD.state='RESOLVED' AND NEW.state IN ('IN_PROGRESS','CLOSED'))) THEN
  RAISE EXCEPTION 'Transição de pedido não permitida.' USING ERRCODE='23514';
 END IF;
 IF NEW.assignee_membership_id IS DISTINCT FROM OLD.assignee_membership_id AND NEW.assignee_membership_id IS NOT NULL
    AND NOT EXISTS(SELECT 1 FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.assignee_membership_id
     AND m.role IN ('MANAGER','CONCIERGE') AND m.status='ACTIVE' AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) THEN
  RAISE EXCEPTION 'Responsável fora da equipe vigente do condomínio.' USING ERRCODE='23514';
 END IF;
 IF NEW.state='RESOLVED' AND OLD.state<>'RESOLVED' THEN
  NEW.resolved_at := clock_timestamp();
 ELSIF NEW.state='CLOSED' THEN
  NEW.resolved_at := OLD.resolved_at; NEW.resolution := CASE WHEN OLD.state='RESOLVED' THEN OLD.resolution ELSE NULL END;
  NEW.closed_at := clock_timestamp();
 ELSIF NEW.state<>'RESOLVED' THEN
  -- Reaberto ou em andamento: sem resolução vigente (a resolução anterior fica na conversa).
  NEW.resolution := NULL; NEW.resolved_at := NULL;
 ELSE
  NEW.resolved_at := OLD.resolved_at;
 END IF;
 IF NEW.state<>'CLOSED' THEN NEW.closed_at := NULL; END IF;
 NEW.updated_at := clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.service_request_guard() FROM PUBLIC;
CREATE TRIGGER service_requests_guard BEFORE INSERT OR UPDATE ON cp.service_requests
 FOR EACH ROW EXECUTE FUNCTION cp.service_request_guard();

-- Conversa só em pedido ainda não fechado (fechado preserva o histórico); horário do banco. A trava consultiva do
-- pedido (a mesma da alteração de andamento) deixa mensagem e fechamento em série; depois dela a leitura enxerga o
-- estado confirmado. Sem trava de linha: a unidade não tem a política de alteração exigida por FOR SHARE.
CREATE FUNCTION cp.request_message_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 current_state text;
 current_unit uuid;
 name text;
BEGIN
 NEW.created_at := clock_timestamp();
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.service_requests:'||NEW.tenant_id::text||':'||NEW.request_id::text,0));
 SELECT r.state,r.unit_id INTO current_state,current_unit FROM cp.service_requests r WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.request_id;
 IF current_state IS NULL THEN RAISE EXCEPTION 'Pedido não visível.' USING ERRCODE='42501'; END IF;
 IF current_state='CLOSED' THEN RAISE EXCEPTION 'Pedido fechado não recebe mensagem.' USING ERRCODE='23514'; END IF;
 -- Nome do próprio vínculo do autor (o único legível para ele): na unidade do pedido ou na equipe.
 IF NEW.author_role='UNIT' THEN
  SELECT m.display_name INTO name FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.unit_id=current_unit
   AND m.account_id=NEW.author_id AND m.status='ACTIVE' AND m.role IN ('RESIDENT','RESPONSIBLE') ORDER BY m.role DESC LIMIT 1;
 ELSE
  SELECT m.display_name INTO name FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.account_id=NEW.author_id
   AND m.status='ACTIVE' AND m.role IN ('MANAGER','CONCIERGE') ORDER BY (m.role='MANAGER') DESC LIMIT 1;
 END IF;
 NEW.author_name := coalesce(name,CASE WHEN NEW.author_role='STAFF' THEN 'Equipe do condomínio' ELSE 'Unidade' END);
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.request_message_guard() FROM PUBLIC;
CREATE TRIGGER request_messages_guard BEFORE INSERT ON cp.request_messages
 FOR EACH ROW EXECUTE FUNCTION cp.request_message_guard();

-- Eventos e trilha dos pedidos em nome da própria conta, de pedido visível a quem grava.
CREATE POLICY outbox_request_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type NOT LIKE 'REQUEST\_%' OR EXISTS(SELECT 1 FROM cp.service_requests r
  WHERE r.tenant_id=cp.outbox.tenant_id AND r.id=cp.outbox.aggregate_id AND cp.outbox.payload->>'unitId'=r.unit_id::text));
CREATE POLICY audit_request_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'REQUEST\_%' OR (actor_id=platform.current_actor_id() AND resource_type='SERVICE_REQUEST'
  AND EXISTS(SELECT 1 FROM cp.service_requests r WHERE r.tenant_id=cp.audit_log.tenant_id AND r.id=cp.audit_log.resource_id)));
