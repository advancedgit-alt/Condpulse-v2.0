-- E0 do PRD complementar (docs/prd/CondPulse_PRD_Complementar_Seguranca_v1.0.md, §3, §4.4 e §7.1) e ADR-0011:
-- nível Parceiro entre a plataforma e o condomínio (CPX-001, CPX-002, CPX-003).
--  - platform.partners: parceiro (empresa de segurança, portaria ou integradora); só ACTIVE/SUSPENDED pela versão.
--  - platform.partner_members: equipe do parceiro com papel (PARTNER_ADMIN, SECURITY_LEAD, SECURITY_SUPERVISOR,
--    REMOTE_OPERATOR, TECHNICIAN); revogar é final (novo vínculo para voltar).
--  - platform.partner_tenant_contracts: contrato parceiro × condomínio com vigência; no máximo UM operador de segurança
--    por condomínio em cada instante (R3.4, restrição de exclusão sobre a vigência efetiva). Encerrar é final.
--  - cp.operator_schedules: escala operador/supervisor × condomínio × janela (no máximo 31 dias por linha).
-- Regras R3.1–R3.3 no banco: platform.partner_reach() devolve, para o ator da transação, os condomínios alcançados por
-- papel de parceiro: contrato ativo e vigente + parceiro ativo + papel ativo + conta ativa + (operador e supervisor)
-- escala vigente. Encerrar o contrato, suspender o parceiro, revogar o papel ou cancelar a escala corta o acesso na
-- instrução seguinte, sem job. A escala só é exigida de operador e supervisor; gestor do parceiro, líder de segurança e
-- técnico alcançam todo condomínio com contrato ativo (R3.2; o técnico ainda depende de concessão técnica V037 para
-- devices.commission).
-- Capacidades novas (§4.4) entram em platform.has_capability: além de publicadas pela API (app.capabilities, depois de
-- AAL2), o banco confere de novo o papel de parceiro no condomínio do contexto (ou no parceiro do contexto,
-- app.partner_id) e, para as duas capacidades da gestão local, o vínculo MANAGER vigente. As capacidades antigas seguem
-- exatamente como antes (só a publicação).
-- Toda mudança exige o evento PARTNER_* da trilha da plataforma do mesmo ator, conferido no COMMIT. Nada é apagado.
-- Sem dado real: o parceiro piloto segue pendente (docs/prd/DECISOES_COMPLEMENTAR.md).

-- 1. Parceiro.
CREATE TABLE platform.partners (
 id uuid PRIMARY KEY,
 name text NOT NULL CHECK(length(btrim(name)) BETWEEN 2 AND 160),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 updated_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX partners_name ON platform.partners(lower(btrim(name)));

CREATE FUNCTION platform.partners_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.version<>0 OR NEW.updated_by<>NEW.created_by THEN
   RAISE EXCEPTION 'parceiro nasce ativo na versão 0' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF NEW.version<>OLD.version+1 OR NEW.status=OLD.status
   OR (NEW.id,NEW.name,NEW.created_by,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.name,OLD.created_by,OLD.created_at) THEN
  RAISE EXCEPTION 'parceiro só muda de estado pela versão seguinte' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.partners_guard() FROM PUBLIC;
CREATE TRIGGER partners_guard BEFORE INSERT OR UPDATE ON platform.partners
 FOR EACH ROW EXECUTE FUNCTION platform.partners_guard();

-- 2. Equipe do parceiro.
CREATE TABLE platform.partner_members (
 id uuid PRIMARY KEY,
 partner_id uuid NOT NULL REFERENCES platform.partners(id),
 account_id uuid NOT NULL REFERENCES platform.accounts(id),
 role text NOT NULL CHECK(role IN ('PARTNER_ADMIN','SECURITY_LEAD','SECURITY_SUPERVISOR','REMOTE_OPERATOR','TECHNICIAN')),
 display_name text NOT NULL CHECK(length(btrim(display_name)) BETWEEN 1 AND 160),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 revoked_at timestamptz,
 revoked_by uuid REFERENCES platform.accounts(id),
 revoke_reason text CHECK(revoke_reason IS NULL OR length(btrim(revoke_reason)) BETWEEN 3 AND 500),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(partner_id,id),
 UNIQUE(id,account_id),
 CHECK((status='REVOKED')=(revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoke_reason IS NOT NULL))
);
-- Um papel ativo por conta e parceiro; a mesma conta pode ter papéis diferentes e em vários parceiros (R3.5).
CREATE UNIQUE INDEX partner_members_active ON platform.partner_members(partner_id,account_id,role) WHERE status='ACTIVE';
CREATE INDEX partner_members_by_account ON platform.partner_members(account_id,status);

CREATE FUNCTION platform.partner_members_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.version<>0 OR NEW.revoked_at IS NOT NULL OR NEW.revoked_by IS NOT NULL
    OR NEW.revoke_reason IS NOT NULL THEN
   RAISE EXCEPTION 'papel do parceiro nasce ativo na versão 0' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM platform.partners p WHERE p.id=NEW.partner_id AND p.status='ACTIVE') THEN
   RAISE EXCEPTION 'parceiro não está ativo' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF OLD.status<>'ACTIVE' OR NEW.status<>'REVOKED' OR NEW.version<>OLD.version+1
   OR (NEW.id,NEW.partner_id,NEW.account_id,NEW.role,NEW.display_name,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.partner_id,OLD.account_id,OLD.role,OLD.display_name,OLD.created_by,OLD.created_at) THEN
  RAISE EXCEPTION 'papel do parceiro só muda por revogação final' USING ERRCODE='23514';
 END IF;
 NEW.revoked_at:=clock_timestamp(); NEW.updated_at:=NEW.revoked_at;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.partner_members_guard() FROM PUBLIC;
CREATE TRIGGER partner_members_guard BEFORE INSERT OR UPDATE ON platform.partner_members
 FOR EACH ROW EXECUTE FUNCTION platform.partner_members_guard();

-- 3. Contrato parceiro × condomínio. Só o serviço de operador de segurança existe nesta fatia; outros tipos aguardam
-- decisão (não se inventa regra). A vigência efetiva é [starts_at, ended_at ou ends_at).
CREATE TABLE platform.partner_tenant_contracts (
 id uuid PRIMARY KEY,
 partner_id uuid NOT NULL REFERENCES platform.partners(id),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 service text NOT NULL CHECK(service IN ('SECURITY_OPERATOR')),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ENDED')),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz,
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ended_at timestamptz,
 ended_by uuid REFERENCES platform.accounts(id),
 end_reason text CHECK(end_reason IS NULL OR length(btrim(end_reason)) BETWEEN 3 AND 500),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(partner_id,tenant_id,id),
 CHECK(ends_at IS NULL OR ends_at>starts_at),
 CHECK((status='ENDED')=(ended_at IS NOT NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL)),
 -- R3.4: nunca dois operadores de segurança no mesmo condomínio no mesmo instante (troca = encerrar e iniciar depois).
 CONSTRAINT partner_contracts_one_operator EXCLUDE USING gist (
  tenant_id WITH =, tstzrange(starts_at,coalesce(ended_at,ends_at),'[)') WITH &&
 ) WHERE(service='SECURITY_OPERATOR')
);
CREATE INDEX partner_contracts_by_partner ON platform.partner_tenant_contracts(partner_id,status);

CREATE FUNCTION platform.partner_contracts_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.version<>0 OR NEW.ended_at IS NOT NULL OR NEW.ended_by IS NOT NULL OR NEW.end_reason IS NOT NULL THEN
   RAISE EXCEPTION 'contrato nasce ativo na versão 0' USING ERRCODE='23514';
  END IF;
  IF NEW.starts_at<clock_timestamp()-interval '5 minutes' OR NEW.starts_at>clock_timestamp()+interval '365 days' THEN
   RAISE EXCEPTION 'início do contrato fora da janela permitida' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM platform.partners p WHERE p.id=NEW.partner_id AND p.status='ACTIVE') THEN
   RAISE EXCEPTION 'parceiro não está ativo' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=NEW.tenant_id AND t.active) THEN
   RAISE EXCEPTION 'condomínio não está ativo' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF OLD.status<>'ACTIVE' OR NEW.status<>'ENDED' OR NEW.version<>OLD.version+1
   OR (NEW.id,NEW.partner_id,NEW.tenant_id,NEW.service,NEW.starts_at,NEW.ends_at,NEW.reason,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.partner_id,OLD.tenant_id,OLD.service,OLD.starts_at,OLD.ends_at,OLD.reason,OLD.created_by,OLD.created_at) THEN
  RAISE EXCEPTION 'contrato só muda por encerramento final' USING ERRCODE='23514';
 END IF;
 -- Encerramento vale agora (R3.3); contrato futuro encerrado fica com vigência vazia; vencido guarda o fim previsto.
 NEW.ended_at:=greatest(OLD.starts_at,least(clock_timestamp(),coalesce(OLD.ends_at,'infinity'::timestamptz)));
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.partner_contracts_guard() FROM PUBLIC;
CREATE TRIGGER partner_contracts_guard BEFORE INSERT OR UPDATE ON platform.partner_tenant_contracts
 FOR EACH ROW EXECUTE FUNCTION platform.partner_contracts_guard();

-- 4. Escala operador/supervisor × condomínio × janela. Conta repetida do papel para a leitura própria sem consulta extra.
CREATE TABLE cp.operator_schedules (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 partner_id uuid NOT NULL,
 member_id uuid NOT NULL,
 account_id uuid NOT NULL,
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','CANCELLED')),
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 cancelled_at timestamptz,
 cancelled_by uuid REFERENCES platform.accounts(id),
 cancel_reason text CHECK(cancel_reason IS NULL OR length(btrim(cancel_reason)) BETWEEN 3 AND 500),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(partner_id,member_id) REFERENCES platform.partner_members(partner_id,id),
 FOREIGN KEY(member_id,account_id) REFERENCES platform.partner_members(id,account_id),
 CHECK(ends_at>starts_at AND ends_at<=starts_at+interval '31 days'),
 CHECK((status='CANCELLED')=(cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND cancel_reason IS NOT NULL)),
 CONSTRAINT operator_schedules_no_overlap EXCLUDE USING gist (
  member_id WITH =, tenant_id WITH =, tstzrange(starts_at,ends_at,'[)') WITH &&
 ) WHERE(status='ACTIVE')
);
CREATE INDEX operator_schedules_by_account ON cp.operator_schedules(account_id,tenant_id,status,ends_at);
CREATE INDEX operator_schedules_by_partner ON cp.operator_schedules(partner_id,tenant_id,starts_at);

CREATE FUNCTION cp.operator_schedules_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.version<>0 OR NEW.cancelled_at IS NOT NULL OR NEW.cancelled_by IS NOT NULL
    OR NEW.cancel_reason IS NOT NULL THEN
   RAISE EXCEPTION 'escala nasce ativa na versão 0' USING ERRCODE='23514';
  END IF;
  IF NEW.starts_at<clock_timestamp()-interval '5 minutes' OR NEW.starts_at>clock_timestamp()+interval '90 days' THEN
   RAISE EXCEPTION 'início da escala fora da janela permitida' USING ERRCODE='23514';
  END IF;
  -- Só operador e supervisor têm escala (R3.1 c); papel ativo de parceiro ativo.
  IF NOT EXISTS(SELECT 1 FROM platform.partner_members m JOIN platform.partners p ON p.id=m.partner_id AND p.status='ACTIVE'
    WHERE m.id=NEW.member_id AND m.partner_id=NEW.partner_id AND m.status='ACTIVE'
     AND m.role IN ('REMOTE_OPERATOR','SECURITY_SUPERVISOR')) THEN
   RAISE EXCEPTION 'escala só para operador ou supervisor ativo do parceiro' USING ERRCODE='23514';
  END IF;
  -- A janela precisa caber na vigência de um contrato ativo do mesmo parceiro com o condomínio.
  IF NOT EXISTS(SELECT 1 FROM platform.partner_tenant_contracts c WHERE c.partner_id=NEW.partner_id AND c.tenant_id=NEW.tenant_id
    AND c.status='ACTIVE' AND c.starts_at<=NEW.starts_at AND (c.ends_at IS NULL OR c.ends_at>=NEW.ends_at)) THEN
   RAISE EXCEPTION 'escala fora de contrato ativo do parceiro com o condomínio' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF OLD.status<>'ACTIVE' OR NEW.status<>'CANCELLED' OR NEW.version<>OLD.version+1
   OR (NEW.id,NEW.tenant_id,NEW.partner_id,NEW.member_id,NEW.account_id,NEW.starts_at,NEW.ends_at,NEW.reason,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.partner_id,OLD.member_id,OLD.account_id,OLD.starts_at,OLD.ends_at,OLD.reason,OLD.created_by,OLD.created_at) THEN
  RAISE EXCEPTION 'escala só muda por cancelamento final' USING ERRCODE='23514';
 END IF;
 NEW.cancelled_at:=clock_timestamp(); NEW.updated_at:=NEW.cancelled_at;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.operator_schedules_guard() FROM PUBLIC;
CREATE TRIGGER operator_schedules_guard BEFORE INSERT OR UPDATE ON cp.operator_schedules
 FOR EACH ROW EXECUTE FUNCTION cp.operator_schedules_guard();

-- 5. Contexto, matriz e alcance.
CREATE FUNCTION platform.current_partner_id() RETURNS uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT nullif(current_setting('app.partner_id',true),'')::uuid $$;
REVOKE ALL ON FUNCTION platform.current_partner_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.current_partner_id() TO cp_api;

-- Publicação pela API (corpo original de has_capability, V006). Usada nas políticas de leitura das tabelas de parceiro,
-- que não podem chamar a conferência (ela lê essas tabelas).
CREATE FUNCTION platform.capability_published(required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(required = ANY(string_to_array(nullif(current_setting('app.capabilities',true),''),',')),false) $$;
REVOKE ALL ON FUNCTION platform.capability_published(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.capability_published(text) TO cp_api;

-- Matriz papel de parceiro × capacidade (§4.4). O Java (PartnerCapability) é conferido contra esta função em teste.
CREATE FUNCTION platform.partner_role_capabilities(role_code text) RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT CASE role_code
  WHEN 'PARTNER_ADMIN' THEN ARRAY['partner.manage','partner.team.manage','security.log.read']
  WHEN 'SECURITY_LEAD' THEN ARRAY['security.command','security.log.read','security.log.export','security.remote_open.review',
   'security.policy.publish','security.blocklist.approve']
  WHEN 'SECURITY_SUPERVISOR' THEN ARRAY['security.log.read.shift','ops.shift.supervise','ops.remote.operate','ops.remote.open']
  WHEN 'REMOTE_OPERATOR' THEN ARRAY['ops.remote.operate','ops.remote.open']
  WHEN 'TECHNICIAN' THEN ARRAY['devices.commission']
  ELSE ARRAY[]::text[] END $$;
REVOKE ALL ON FUNCTION platform.partner_role_capabilities(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.partner_role_capabilities(text) TO cp_api;

-- Capacidades do PRD complementar (todas conferidas no banco por has_capability).
CREATE FUNCTION platform.security_capabilities() RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT ARRAY['partner.manage','partner.team.manage','security.command','security.log.read','security.log.read.shift',
  'security.log.export','security.remote_open.review','security.policy.publish','security.blocklist.approve',
  'ops.shift.supervise','ops.remote.operate','ops.remote.open','devices.commission','security.log.read.tenant'] $$;
REVOKE ALL ON FUNCTION platform.security_capabilities() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.security_capabilities() TO cp_api;

-- Alcance do ator por papel de parceiro (R3.1–R3.3). Não lê platform.tenants (a política do catálogo usa esta função);
-- quem abre o condomínio confere o condomínio ativo à parte.
CREATE FUNCTION platform.partner_reach() RETURNS TABLE(member_id uuid,partner_id uuid,role text,tenant_id uuid)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT m.id,m.partner_id,m.role,c.tenant_id
 FROM platform.partner_members m
 JOIN platform.partners p ON p.id=m.partner_id AND p.status='ACTIVE'
 JOIN platform.partner_tenant_contracts c ON c.partner_id=m.partner_id AND c.status='ACTIVE'
  AND c.starts_at<=now() AND (c.ends_at IS NULL OR c.ends_at>now())
 WHERE m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
  AND EXISTS(SELECT 1 FROM platform.accounts a WHERE a.id=m.account_id AND a.status='ACTIVE')
  AND (m.role IN ('PARTNER_ADMIN','SECURITY_LEAD','TECHNICIAN') OR EXISTS(SELECT 1 FROM cp.operator_schedules s
   WHERE s.member_id=m.id AND s.account_id=m.account_id AND s.tenant_id=c.tenant_id AND s.status='ACTIVE'
    AND s.starts_at<=now() AND s.ends_at>now())) $$;
REVOKE ALL ON FUNCTION platform.partner_reach() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.partner_reach() TO cp_api;

-- Papel ativo do ator no parceiro informado (nível parceiro, sem contrato), com a capacidade na matriz.
CREATE FUNCTION platform.partner_role_grants(partner uuid,required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT partner IS NOT NULL AND EXISTS(SELECT 1 FROM platform.partner_members m
  JOIN platform.partners p ON p.id=m.partner_id AND p.status='ACTIVE'
  WHERE m.partner_id=partner AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
   AND required=ANY(platform.partner_role_capabilities(m.role))
   AND EXISTS(SELECT 1 FROM platform.accounts a WHERE a.id=m.account_id AND a.status='ACTIVE')) $$;
REVOKE ALL ON FUNCTION platform.partner_role_grants(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.partner_role_grants(uuid,text) TO cp_api;

-- Conferência no banco de uma capacidade do PRD complementar.
--  - condomínio do contexto: papel alcançado por partner_reach() com a capacidade (devices.commission ainda exige
--    concessão técnica vigente da V037) ou, para as capacidades da gestão local, vínculo MANAGER vigente;
--  - parceiro do contexto (app.partner_id): papel ativo com a capacidade, exceto as de operação no condomínio.
CREATE FUNCTION platform.capability_verified(required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT (required IN ('security.blocklist.approve','security.log.read.tenant') AND EXISTS(SELECT 1 FROM cp.memberships m
   WHERE m.tenant_id=platform.current_tenant_id() AND m.account_id=platform.current_actor_id() AND m.role='MANAGER'
    AND m.status='ACTIVE' AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())))
  OR (platform.current_tenant_id() IS NOT NULL AND EXISTS(SELECT 1 FROM platform.partner_reach() r
   WHERE r.tenant_id=platform.current_tenant_id() AND required=ANY(platform.partner_role_capabilities(r.role))
    AND (required<>'devices.commission' OR EXISTS(SELECT 1 FROM cp.technical_grants g WHERE g.tenant_id=r.tenant_id
     AND g.account_id=platform.current_actor_id() AND g.status='ACTIVE' AND g.valid_from<=now() AND g.valid_until>now()))))
  OR (required NOT IN ('ops.remote.operate','ops.remote.open','devices.commission')
   AND platform.partner_role_grants(platform.current_partner_id(),required)) $$;
REVOKE ALL ON FUNCTION platform.capability_verified(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.capability_verified(text) TO cp_api;

-- has_capability: capacidades antigas seguem só pela publicação (mesmo resultado da V006); as novas exigem também a
-- conferência no banco. CASE garante a ordem: a conferência só roda para capacidade nova e publicada.
CREATE OR REPLACE FUNCTION platform.has_capability(required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT CASE WHEN NOT platform.capability_published(required) THEN false
  WHEN NOT (required = ANY(platform.security_capabilities())) THEN true
  ELSE coalesce(platform.capability_verified(required),false) END $$;

-- 6. RLS e grants. Grafo de políticas de leitura sem ciclo: equipe e escala não consultam outra tabela; parceiro lê a
-- equipe; contrato lê equipe e escala; catálogo de condomínios usa partner_reach().
ALTER TABLE platform.partners ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.partners FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.partner_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.partner_members FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.partner_tenant_contracts ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.partner_tenant_contracts FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.operator_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.operator_schedules FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.partners,platform.partner_members,platform.partner_tenant_contracts,cp.operator_schedules FROM PUBLIC;
GRANT SELECT ON platform.partners,platform.partner_members,platform.partner_tenant_contracts,cp.operator_schedules TO cp_api;
GRANT INSERT(id,name,created_by,updated_by) ON platform.partners TO cp_api;
GRANT UPDATE(status,version,updated_by) ON platform.partners TO cp_api;
GRANT INSERT(id,partner_id,account_id,role,display_name,created_by) ON platform.partner_members TO cp_api;
GRANT UPDATE(status,revoked_by,revoke_reason,version) ON platform.partner_members TO cp_api;
GRANT INSERT(id,partner_id,tenant_id,service,starts_at,ends_at,reason,created_by) ON platform.partner_tenant_contracts TO cp_api;
GRANT UPDATE(status,ended_by,end_reason,version) ON platform.partner_tenant_contracts TO cp_api;
GRANT INSERT(id,tenant_id,partner_id,member_id,account_id,starts_at,ends_at,reason,created_by) ON cp.operator_schedules TO cp_api;
GRANT UPDATE(status,cancelled_by,cancel_reason,version) ON cp.operator_schedules TO cp_api;
-- Sem DELETE para ninguém da aplicação; cp_worker sem acesso nesta fatia.

-- Parceiro: plataforma (partners.read/partners.manage) e quem tem papel ativo nele.
CREATE POLICY partners_platform_read ON platform.partners FOR SELECT TO cp_api
 USING(platform.has_platform_capability('partners.read'));
CREATE POLICY partners_member_read ON platform.partners FOR SELECT TO cp_api
 USING(id IN (SELECT m.partner_id FROM platform.partner_members m
  WHERE m.account_id=platform.current_actor_id() AND m.status='ACTIVE'));
CREATE POLICY partners_platform_insert ON platform.partners FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('partners.manage') AND created_by=platform.current_actor_id());
CREATE POLICY partners_platform_update ON platform.partners FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('partners.manage'))
 WITH CHECK(platform.has_platform_capability('partners.manage') AND updated_by=platform.current_actor_id());

-- Equipe: o próprio papel; plataforma; gestor do parceiro do contexto (leitura pela publicação; gravação conferida).
CREATE POLICY partner_members_self_read ON platform.partner_members FOR SELECT TO cp_api
 USING(account_id=platform.current_actor_id());
CREATE POLICY partner_members_platform_read ON platform.partner_members FOR SELECT TO cp_api
 USING(platform.has_platform_capability('partners.read'));
CREATE POLICY partner_members_team_read ON platform.partner_members FOR SELECT TO cp_api
 USING(partner_id=platform.current_partner_id() AND platform.capability_published('partner.team.manage'));
-- O gestor do parceiro não concede nem revoga papel para a própria conta (a plataforma pode).
CREATE POLICY partner_members_insert ON platform.partner_members FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id() AND (platform.has_platform_capability('partners.manage')
  OR (partner_id=platform.current_partner_id() AND account_id<>platform.current_actor_id()
   AND platform.has_capability('partner.team.manage'))));
CREATE POLICY partner_members_revoke ON platform.partner_members FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('partners.manage')
  OR (partner_id=platform.current_partner_id() AND platform.capability_published('partner.team.manage')))
 WITH CHECK(revoked_by=platform.current_actor_id() AND (platform.has_platform_capability('partners.manage')
  OR (partner_id=platform.current_partner_id() AND account_id<>platform.current_actor_id()
   AND platform.has_capability('partner.team.manage'))));

-- Contrato: plataforma grava; gestor, líder e técnico do parceiro leem a carteira; operador e supervisor só os
-- condomínios em que têm escala.
CREATE POLICY partner_contracts_platform_read ON platform.partner_tenant_contracts FOR SELECT TO cp_api
 USING(platform.has_platform_capability('partners.read'));
CREATE POLICY partner_contracts_staff_read ON platform.partner_tenant_contracts FOR SELECT TO cp_api
 USING(partner_id IN (SELECT m.partner_id FROM platform.partner_members m WHERE m.account_id=platform.current_actor_id()
   AND m.status='ACTIVE' AND m.role IN ('PARTNER_ADMIN','SECURITY_LEAD','TECHNICIAN'))
  OR EXISTS(SELECT 1 FROM cp.operator_schedules s WHERE s.account_id=platform.current_actor_id()
   AND s.partner_id=platform.partner_tenant_contracts.partner_id AND s.tenant_id=platform.partner_tenant_contracts.tenant_id));
CREATE POLICY partner_contracts_platform_insert ON platform.partner_tenant_contracts FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('partners.manage') AND created_by=platform.current_actor_id());
CREATE POLICY partner_contracts_platform_end ON platform.partner_tenant_contracts FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('partners.manage'))
 WITH CHECK(platform.has_platform_capability('partners.manage') AND ended_by=platform.current_actor_id());

-- Escala: a própria; plataforma; gestor do parceiro do contexto.
CREATE POLICY operator_schedules_self_read ON cp.operator_schedules FOR SELECT TO cp_api
 USING(account_id=platform.current_actor_id());
CREATE POLICY operator_schedules_platform_read ON cp.operator_schedules FOR SELECT TO cp_api
 USING(platform.has_platform_capability('partners.read'));
CREATE POLICY operator_schedules_team_read ON cp.operator_schedules FOR SELECT TO cp_api
 USING(partner_id=platform.current_partner_id() AND platform.capability_published('partner.team.manage'));
CREATE POLICY operator_schedules_insert ON cp.operator_schedules FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id() AND (platform.has_platform_capability('partners.manage')
  OR (partner_id=platform.current_partner_id() AND platform.has_capability('partner.team.manage'))));
CREATE POLICY operator_schedules_cancel ON cp.operator_schedules FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('partners.manage')
  OR (partner_id=platform.current_partner_id() AND platform.capability_published('partner.team.manage')))
 WITH CHECK(cancelled_by=platform.current_actor_id() AND (platform.has_platform_capability('partners.manage')
  OR (partner_id=platform.current_partner_id() AND platform.has_capability('partner.team.manage'))));

-- Catálogo de condomínios: a equipe do parceiro enxerga o condomínio ativo que alcança agora (R3.1–R3.3).
CREATE POLICY tenant_partner_read ON platform.tenants FOR SELECT TO cp_api
 USING(active AND id IN (SELECT r.tenant_id FROM platform.partner_reach() r));
-- /v1/me e o executor do condomínio conferem o módulo do condomínio alcançado pelo parceiro.
CREATE POLICY tenant_modules_partner_read ON platform.tenant_modules FOR SELECT TO cp_api
 USING(tenant_id IN (SELECT r.tenant_id FROM platform.partner_reach() r));

-- 7. Trilha da plataforma (append-only): eventos PARTNER_* pela plataforma; equipe e escala também pelo gestor do
-- parceiro do contexto, sempre com o parceiro nos detalhes.
CREATE POLICY platform_audit_partner_insert ON platform.platform_audit_log FOR INSERT TO cp_api
 WITH CHECK(actor_id=platform.current_actor_id() AND (
  (platform.has_platform_capability('partners.manage') AND action LIKE 'PARTNER\_%')
  OR (action IN ('PARTNER_MEMBER_ADDED','PARTNER_MEMBER_REVOKED','PARTNER_SCHEDULE_CREATED','PARTNER_SCHEDULE_CANCELLED')
   AND details->>'partnerId'=platform.current_partner_id()::text AND platform.has_capability('partner.team.manage'))));
CREATE POLICY platform_audit_partner_read ON platform.platform_audit_log FOR SELECT TO cp_api
 USING((platform.has_platform_capability('partners.manage') AND action LIKE 'PARTNER\_%')
  OR (action IN ('PARTNER_MEMBER_ADDED','PARTNER_MEMBER_REVOKED','PARTNER_SCHEDULE_CREATED','PARTNER_SCHEDULE_CANCELLED')
   AND details->>'partnerId'=platform.current_partner_id()::text AND platform.has_capability('partner.team.manage')));

-- Cada gravação só termina com o evento do mesmo ator, ação e identificador (conferido no COMMIT).
CREATE FUNCTION platform.partner_change_audited() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE expected_action text; expected_actor uuid; id_key text; tenant uuid;
BEGIN
 IF TG_TABLE_NAME='partners' THEN
  id_key:='partnerId'; tenant:=NULL;
  IF TG_OP='INSERT' THEN expected_action:='PARTNER_CREATED'; expected_actor:=NEW.created_by;
  ELSE expected_action:=CASE WHEN NEW.status='SUSPENDED' THEN 'PARTNER_SUSPENDED' ELSE 'PARTNER_REACTIVATED' END;
   expected_actor:=NEW.updated_by; END IF;
 ELSIF TG_TABLE_NAME='partner_members' THEN
  id_key:='memberId'; tenant:=NULL;
  IF TG_OP='INSERT' THEN expected_action:='PARTNER_MEMBER_ADDED'; expected_actor:=NEW.created_by;
  ELSE expected_action:='PARTNER_MEMBER_REVOKED'; expected_actor:=NEW.revoked_by; END IF;
 ELSIF TG_TABLE_NAME='partner_tenant_contracts' THEN
  id_key:='contractId'; tenant:=NEW.tenant_id;
  IF TG_OP='INSERT' THEN expected_action:='PARTNER_CONTRACT_STARTED'; expected_actor:=NEW.created_by;
  ELSE expected_action:='PARTNER_CONTRACT_ENDED'; expected_actor:=NEW.ended_by; END IF;
 ELSE
  id_key:='scheduleId'; tenant:=NEW.tenant_id;
  IF TG_OP='INSERT' THEN expected_action:='PARTNER_SCHEDULE_CREATED'; expected_actor:=NEW.created_by;
  ELSE expected_action:='PARTNER_SCHEDULE_CANCELLED'; expected_actor:=NEW.cancelled_by; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.platform_audit_log a WHERE a.action=expected_action AND a.actor_id=expected_actor
   AND a.tenant_id IS NOT DISTINCT FROM tenant AND a.details->>id_key=NEW.id::text
   AND a.details->>'version'=NEW.version::text) THEN
  RAISE EXCEPTION 'Mudança de parceiro sem evento de auditoria da plataforma.' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION platform.partner_change_audited() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER partners_audited AFTER INSERT OR UPDATE ON platform.partners
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.partner_change_audited();
CREATE CONSTRAINT TRIGGER partner_members_audited AFTER INSERT OR UPDATE ON platform.partner_members
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.partner_change_audited();
CREATE CONSTRAINT TRIGGER partner_contracts_audited AFTER INSERT OR UPDATE ON platform.partner_tenant_contracts
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.partner_change_audited();
CREATE CONSTRAINT TRIGGER operator_schedules_audited AFTER INSERT OR UPDATE ON cp.operator_schedules
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.partner_change_audited();
