-- Central administradora (fatia 3 do desenho docs/ESTRUTURA_CENTRAL_E_CONDOMINIO.md): papel de equipe MAINTENANCE
-- (zelador). Vínculo de equipe sem unidade, convidado e aprovado pela gestão como a portaria; capacidade própria
-- maintenance.operate (publicada pela API só depois de vínculo vigente e AAL2). Lê e atualiza somente os pedidos da
-- unidade atribuídos ao próprio vínculo: andamento (em execução, aguardando a unidade, concluído com resolução) e
-- conversa. Não muda responsável nem prazo, não encerra, não reabre e não enxerga financeiro, cadastro, vínculos,
-- encomendas, visitas nem acesso (nenhuma política existente aceita maintenance.operate).
-- Ocorrências da portaria (V033) não têm atribuição; ficam fora até existir esse modelo.

ALTER TABLE cp.memberships DROP CONSTRAINT memberships_role_check;
ALTER TABLE cp.memberships ADD CONSTRAINT memberships_role_check
 CHECK(role IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER','CONCIERGE','MANAGER','SUPPORT','MAINTENANCE'));
ALTER TABLE cp.membership_invitations DROP CONSTRAINT membership_invitations_role_check;
ALTER TABLE cp.membership_invitations ADD CONSTRAINT membership_invitations_role_check
 CHECK(role IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER','CONCIERGE','MAINTENANCE'));
-- Zelador é equipe: nunca vinculado a unidade (o convite e o vínculo repetem a regra da portaria na API).
ALTER TABLE cp.memberships ADD CONSTRAINT memberships_maintenance_without_unit CHECK(role<>'MAINTENANCE' OR unit_id IS NULL);
ALTER TABLE cp.membership_invitations ADD CONSTRAINT membership_invitations_maintenance_without_unit
 CHECK(role<>'MAINTENANCE' OR unit_id IS NULL);

-- Pedido atribuído ao vínculo de zeladoria vigente da própria conta, com a capacidade publicada nesta transação.
CREATE FUNCTION cp.request_maintenance_assigned(target_tenant uuid,assignee uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT assignee IS NOT NULL AND platform.has_capability('maintenance.operate') AND EXISTS(SELECT 1 FROM cp.memberships m
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.id=assignee AND m.account_id=platform.current_actor_id() AND m.role='MAINTENANCE'
   AND m.status='ACTIVE' AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) $$;
REVOKE ALL ON FUNCTION cp.request_maintenance_assigned(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.request_maintenance_assigned(uuid,uuid) TO cp_api;

-- Gestão de pedidos passa a ver o zelador vigente para atribuir.
DROP POLICY membership_request_team_read ON cp.memberships;
CREATE POLICY membership_request_team_read ON cp.memberships FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND platform.has_capability('requests.manage')
  AND role IN ('MANAGER','CONCIERGE','MAINTENANCE') AND status='ACTIVE');

DROP POLICY service_requests_read_scope ON cp.service_requests;
CREATE POLICY service_requests_read_scope ON cp.service_requests AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('requests.manage') OR cp.request_unit_member(tenant_id,unit_id)
  OR cp.request_maintenance_assigned(tenant_id,assignee_membership_id));
DROP POLICY service_requests_manage ON cp.service_requests;
CREATE POLICY service_requests_manage ON cp.service_requests AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('requests.manage') OR cp.request_maintenance_assigned(tenant_id,assignee_membership_id))
 WITH CHECK(platform.has_capability('requests.manage') OR cp.request_maintenance_assigned(tenant_id,assignee_membership_id));

DROP POLICY request_messages_read_scope ON cp.request_messages;
CREATE POLICY request_messages_read_scope ON cp.request_messages AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('requests.manage') OR EXISTS(SELECT 1 FROM cp.service_requests r
  WHERE r.tenant_id=cp.request_messages.tenant_id AND r.id=cp.request_messages.request_id
   AND cp.request_maintenance_assigned(r.tenant_id,r.assignee_membership_id))
  OR (visibility='RESIDENT' AND EXISTS(SELECT 1 FROM cp.service_requests r
  WHERE r.tenant_id=cp.request_messages.tenant_id AND r.id=cp.request_messages.request_id AND cp.request_unit_member(r.tenant_id,r.unit_id))));
DROP POLICY request_messages_write ON cp.request_messages;
CREATE POLICY request_messages_write ON cp.request_messages AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(author_id=platform.current_actor_id() AND (
  (author_role='STAFF' AND platform.has_capability('requests.manage'))
  OR (author_role='STAFF' AND EXISTS(SELECT 1 FROM cp.service_requests r
   WHERE r.tenant_id=cp.request_messages.tenant_id AND r.id=cp.request_messages.request_id
    AND cp.request_maintenance_assigned(r.tenant_id,r.assignee_membership_id)))
  OR (author_role='UNIT' AND visibility='RESIDENT' AND EXISTS(SELECT 1 FROM cp.service_requests r
   WHERE r.tenant_id=cp.request_messages.tenant_id AND r.id=cp.request_messages.request_id AND cp.request_unit_member(r.tenant_id,r.unit_id)))));

-- Andamento: mesma máquina da V017; responsável pode ser zelador vigente; quem altera só pela zeladoria (sem
-- requests.manage) não muda responsável nem prazo e só leva o pedido em triagem/execução/aguardando para execução,
-- aguardando a unidade ou concluído.
CREATE OR REPLACE FUNCTION cp.service_request_guard() RETURNS trigger
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
 IF NOT platform.has_capability('requests.manage') THEN
  IF NEW.assignee_membership_id IS DISTINCT FROM OLD.assignee_membership_id OR NEW.due_on IS DISTINCT FROM OLD.due_on
     OR NEW.state=OLD.state OR OLD.state NOT IN ('TRIAGED','IN_PROGRESS','WAITING_RESIDENT')
     OR NEW.state NOT IN ('IN_PROGRESS','WAITING_RESIDENT','RESOLVED') THEN
   RAISE EXCEPTION 'Zeladoria só registra o andamento do pedido atribuído.' USING ERRCODE='42501';
  END IF;
 END IF;
 IF NEW.assignee_membership_id IS DISTINCT FROM OLD.assignee_membership_id AND NEW.assignee_membership_id IS NOT NULL
    AND NOT EXISTS(SELECT 1 FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.assignee_membership_id
     AND m.role IN ('MANAGER','CONCIERGE','MAINTENANCE') AND m.status='ACTIVE' AND m.valid_from<=now()
     AND (m.valid_until IS NULL OR m.valid_until>now())) THEN
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

-- Nome do autor da equipe: gestão, portaria ou zeladoria (o próprio vínculo é o único legível para ele).
CREATE OR REPLACE FUNCTION cp.request_message_guard() RETURNS trigger
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
 IF NEW.author_role='UNIT' THEN
  SELECT m.display_name INTO name FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.unit_id=current_unit
   AND m.account_id=NEW.author_id AND m.status='ACTIVE' AND m.role IN ('RESIDENT','RESPONSIBLE') ORDER BY m.role DESC LIMIT 1;
 ELSE
  SELECT m.display_name INTO name FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.account_id=NEW.author_id
   AND m.status='ACTIVE' AND m.role IN ('MANAGER','CONCIERGE','MAINTENANCE')
   ORDER BY (m.role='MANAGER') DESC,(m.role='CONCIERGE') DESC LIMIT 1;
 END IF;
 NEW.author_name := coalesce(name,CASE WHEN NEW.author_role='STAFF' THEN 'Equipe do condomínio' ELSE 'Unidade' END);
 RETURN NEW;
END $$;
