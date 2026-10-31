-- AC-003 (pacote Acesso Facial v1.1, docs/integracoes/facial): autorização administrativa em três níveis.
--  1. MASTER da plataforma: PLATFORM_ADMIN (V010) com a nova capacidade technical_grants.manage publicada pela API só
--     depois de papel vigente e AAL2. Emite e revoga concessões técnicas; não ganha uso físico de áreas nem leitura de
--     dados residenciais por isso.
--  2. MASTER de implantação por condomínio: cp.technical_grants (papel MASTER_IMPLANTACAO), sempre com condomínio,
--     escopos fechados, validade obrigatória e autorizador distinto da conta beneficiada. Escopos entram no contexto da
--     transação (app.technical_scopes) só pela API, depois de AAL2, e o banco confere de novo a concessão vigente.
--  3. Gestor: papel MANAGER existente, sem herança de escopo técnico (segredo, firmware, relé, canal ou vínculo físico).
-- A concessão operacional (granted_via OPERATIONS) é o caminho do SQL revisado ops/sql/technical-grant-master-implantacao.sql,
-- executado fora da role runtime; a role runtime só emite pelo painel da plataforma (granted_via PLATFORM_API).
-- Nada é apagado: revogar é final; nova concessão para voltar a valer.

-- Escopos técnicos permitidos por papel (lista fechada; sem curinga vindo do cliente).
CREATE FUNCTION cp.technical_scopes_valid(role_code text,scopes text[]) RETURNS boolean
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT scopes IS NOT NULL AND cardinality(scopes) BETWEEN 1 AND 16 AND array_position(scopes,NULL) IS NULL
  AND cardinality(scopes)=(SELECT count(DISTINCT s) FROM unnest(scopes) s)
  AND scopes <@ CASE role_code
   WHEN 'MASTER_IMPLANTACAO' THEN ARRAY['installation.read','installation.manage','installation.bind_channel',
    'installation.commission','connector.rotate_secret','gateway.manage','homologation.request','person.review',
    'access.request_open','audit.export']
   WHEN 'TECHNICAL_SUPPORT' THEN ARRAY['installation.read','homologation.request']
   ELSE ARRAY[]::text[] END $$;
REVOKE ALL ON FUNCTION cp.technical_scopes_valid(text,text[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.technical_scopes_valid(text,text[]) TO cp_api;

CREATE TABLE cp.technical_grants (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 account_id uuid NOT NULL REFERENCES platform.accounts(id),
 role_code text NOT NULL CHECK(role_code IN ('MASTER_IMPLANTACAO','TECHNICAL_SUPPORT')),
 scopes text[] NOT NULL,
 valid_from timestamptz NOT NULL,
 valid_until timestamptz NOT NULL,
 granted_via text NOT NULL CHECK(granted_via IN ('PLATFORM_API','OPERATIONS')),
 granted_by_account_id uuid REFERENCES platform.accounts(id),
 -- Identidade do banco que gravou (login runtime ou identidade da operação), definida pelo gatilho.
 granted_by_db_user text NOT NULL DEFAULT session_user,
 approved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 -- Motivo e referência da aprovação, sem segredo.
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 10 AND 500),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
 revoked_at timestamptz,
 revoked_by_account_id uuid REFERENCES platform.accounts(id),
 revoke_reason text CHECK(revoke_reason IS NULL OR length(btrim(revoke_reason)) BETWEEN 3 AND 500),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,id,account_id),
 CHECK(cp.technical_scopes_valid(role_code,scopes)),
 -- Validade obrigatória e curta: teto rígido de 90 dias (o painel aplica o parâmetro menor configurado).
 CHECK(valid_until>valid_from AND valid_until<=valid_from+interval '90 days'),
 -- Pelo painel há sempre autorizador, e nunca a própria conta beneficiada.
 CHECK((granted_via='PLATFORM_API')=(granted_by_account_id IS NOT NULL)),
 CHECK(granted_by_account_id IS NULL OR granted_by_account_id<>account_id),
 CHECK((status='REVOKED')=(revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)),
 CHECK(revoked_by_account_id IS NULL OR status='REVOKED')
);
CREATE INDEX technical_grants_current ON cp.technical_grants(tenant_id,account_id,status,valid_until);
CREATE INDEX technical_grants_by_account ON cp.technical_grants(account_id,status,valid_until);

-- Nascimento: ativa na versão 0, horários do banco, início sem retroação e no máximo 30 dias à frente.
-- Mudança: somente ACTIVE→REVOKED com versão+1; nada mais muda.
CREATE FUNCTION cp.technical_grants_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.version<>0 OR NEW.revoked_at IS NOT NULL OR NEW.revoked_by_account_id IS NOT NULL
    OR NEW.revoke_reason IS NOT NULL THEN
   RAISE EXCEPTION 'concessão técnica nasce ativa na versão 0' USING ERRCODE='23514';
  END IF;
  IF NEW.valid_from<clock_timestamp()-interval '5 minutes' OR NEW.valid_from>clock_timestamp()+interval '30 days' THEN
   RAISE EXCEPTION 'início da concessão fora da janela permitida' USING ERRCODE='23514';
  END IF;
  NEW.granted_by_db_user:=session_user;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at; NEW.approved_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF OLD.status<>'ACTIVE' OR NEW.status<>'REVOKED' OR NEW.version<>OLD.version+1
   OR (NEW.id,NEW.tenant_id,NEW.account_id,NEW.role_code,NEW.scopes,NEW.valid_from,NEW.valid_until,NEW.granted_via,
       NEW.granted_by_account_id,NEW.granted_by_db_user,NEW.approved_at,NEW.reason,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.account_id,OLD.role_code,OLD.scopes,OLD.valid_from,OLD.valid_until,OLD.granted_via,
       OLD.granted_by_account_id,OLD.granted_by_db_user,OLD.approved_at,OLD.reason,OLD.created_at) THEN
  RAISE EXCEPTION 'concessão técnica só muda por revogação final' USING ERRCODE='23514';
 END IF;
 NEW.revoked_at:=clock_timestamp(); NEW.updated_at:=NEW.revoked_at;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.technical_grants_guard() FROM PUBLIC;
CREATE TRIGGER technical_grants_guard BEFORE INSERT OR UPDATE ON cp.technical_grants
 FOR EACH ROW EXECUTE FUNCTION cp.technical_grants_guard();

-- Emissão e revogação pelo painel só terminam com o evento da trilha da plataforma do mesmo ator (conferido no COMMIT).
CREATE FUNCTION cp.technical_grants_audited() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE expected_action text; expected_actor uuid;
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.granted_via<>'PLATFORM_API' THEN RETURN NULL; END IF;
  expected_action:='TECHNICAL_GRANT_ISSUED'; expected_actor:=NEW.granted_by_account_id;
 ELSE
  IF NEW.revoked_by_account_id IS NULL THEN RETURN NULL; END IF;
  expected_action:='TECHNICAL_GRANT_REVOKED'; expected_actor:=NEW.revoked_by_account_id;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.platform_audit_log a WHERE a.tenant_id=NEW.tenant_id AND a.action=expected_action
   AND a.actor_id=expected_actor AND a.details->>'grantId'=NEW.id::text) THEN
  RAISE EXCEPTION 'Concessão técnica sem evento de auditoria da plataforma.' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.technical_grants_audited() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER technical_grants_audited AFTER INSERT OR UPDATE ON cp.technical_grants
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cp.technical_grants_audited();

-- Escopo técnico utilizável nesta transação: publicado pela API (após AAL2 e concessão vigente) E ainda vigente no
-- banco para a conta e o condomínio do contexto. Revogação e vencimento valem na próxima instrução, sem job.
CREATE FUNCTION platform.has_technical_scope(required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(required=ANY(string_to_array(nullif(current_setting('app.technical_scopes',true),''),',')),false)
  AND EXISTS(SELECT 1 FROM cp.technical_grants g WHERE g.tenant_id=platform.current_tenant_id()
   AND g.account_id=platform.current_actor_id() AND g.status='ACTIVE' AND g.valid_from<=now() AND g.valid_until>now()
   AND required=ANY(g.scopes)) $$;
REVOKE ALL ON FUNCTION platform.has_technical_scope(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.has_technical_scope(text) TO cp_api;

ALTER TABLE cp.technical_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.technical_grants FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.technical_grants FROM PUBLIC;
GRANT SELECT ON cp.technical_grants TO cp_api;
GRANT INSERT(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,granted_by_account_id,reason)
 ON cp.technical_grants TO cp_api;
GRANT UPDATE(status,revoked_by_account_id,revoke_reason,version) ON cp.technical_grants TO cp_api;
-- cp_worker: nenhum acesso nesta fatia.

-- Plataforma (technical_grants.manage): leitura de todos os condomínios, emissão para outra conta em condomínio ativo e
-- revogação com motivo e o próprio ator. É a única leitura sem condomínio no contexto, por desenho (MASTER plataforma).
CREATE POLICY technical_grants_platform_read ON cp.technical_grants FOR SELECT TO cp_api
 USING(platform.has_platform_capability('technical_grants.manage'));
CREATE POLICY technical_grants_platform_insert ON cp.technical_grants FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('technical_grants.manage') AND granted_via='PLATFORM_API'
  AND granted_by_account_id=platform.current_actor_id() AND account_id<>platform.current_actor_id()
  AND EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=cp.technical_grants.tenant_id AND t.active));
CREATE POLICY technical_grants_platform_revoke ON cp.technical_grants FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('technical_grants.manage') AND status='ACTIVE')
 WITH CHECK(platform.has_platform_capability('technical_grants.manage') AND status='REVOKED'
  AND revoked_by_account_id=platform.current_actor_id());
-- A conta beneficiada vê as próprias concessões (resolução do contexto técnico).
CREATE POLICY technical_grants_self_read ON cp.technical_grants FOR SELECT TO cp_api
 USING(account_id=platform.current_actor_id());
-- O gestor vê quem tem concessão técnica no próprio condomínio (transparência), sem poder emitir nem revogar.
CREATE POLICY technical_grants_manager_read ON cp.technical_grants FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND platform.has_capability('memberships.manage'));

-- O MASTER de implantação sem vínculo residencial enxerga o condomínio ativo da própria concessão vigente.
CREATE POLICY tenant_technical_grant_read ON platform.tenants FOR SELECT TO cp_api
 USING(active AND EXISTS(SELECT 1 FROM cp.technical_grants g WHERE g.tenant_id=platform.tenants.id
  AND g.account_id=platform.current_actor_id() AND g.status='ACTIVE' AND g.valid_from<=now() AND g.valid_until>now()));

-- Trilha da plataforma passa a aceitar TECHNICAL_GRANT_* somente com technical_grants.manage.
DROP POLICY platform_audit_insert ON platform.platform_audit_log;
CREATE POLICY platform_audit_insert ON platform.platform_audit_log FOR INSERT TO cp_api
 WITH CHECK(actor_id = platform.current_actor_id() AND (
  (platform.has_platform_capability('tenants.manage') AND action LIKE 'TENANT\_%')
  OR (platform.has_platform_capability('billing.manage') AND action LIKE 'BILLING\_%')
  OR (platform.has_platform_capability('outbox.operate') AND action LIKE 'OUTBOX\_%')
  OR (platform.has_platform_capability('technical_grants.manage') AND action LIKE 'TECHNICAL\_GRANT\_%')));
-- A conferência do COMMIT (gatilho do invocador) lê só os eventos de concessão técnica, com a mesma capacidade.
CREATE POLICY platform_audit_technical_grant_read ON platform.platform_audit_log FOR SELECT TO cp_api
 USING(platform.has_platform_capability('technical_grants.manage') AND action LIKE 'TECHNICAL\_GRANT\_%');
