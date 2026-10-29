-- Painel master (fase 4, OPS-01). Autoridade de plataforma separada dos vínculos de condomínio: concedida somente
-- por procedimento operacional revisado (a role runtime não insere nem altera papéis), publicada pela API no
-- contexto local apenas após conta ativa, papel vigente e AAL2. Não dá leitura de dados de negócio (cp.*), não
-- cria condomínio (o cadastro segue pelo onboarding operacional) e não permite personificação.
CREATE TABLE platform.platform_roles (
 account_id uuid PRIMARY KEY REFERENCES platform.accounts(id),
 role text NOT NULL CHECK (role IN ('PLATFORM_ADMIN')),
 status text NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
 granted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 revoked_at timestamptz,
 -- Revogar não sobrescreve o motivo da concessão.
 revoke_reason text CHECK (revoke_reason IS NULL OR length(btrim(revoke_reason)) BETWEEN 1 AND 500),
 CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
 CHECK ((status = 'REVOKED') = (revoke_reason IS NOT NULL))
);
ALTER TABLE platform.platform_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.platform_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_role_self ON platform.platform_roles FOR SELECT TO cp_api
 USING(account_id = platform.current_actor_id());
REVOKE ALL ON platform.platform_roles FROM PUBLIC;
GRANT SELECT ON platform.platform_roles TO cp_api;

-- Trilha de concessões, revogações e remoções do papel, gravada por gatilho na mesma transação do SQL operacional,
-- com a identidade do banco que executou. Nenhuma role de aplicação lê ou escreve; sem chave estrangeira para a
-- trilha sobreviver à conta.
CREATE TABLE platform.platform_role_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 operation text NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
 account_id uuid NOT NULL,
 role text NOT NULL,
 status text NOT NULL,
 reason text NOT NULL,
 revoke_reason text,
 executed_by text NOT NULL DEFAULT session_user,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE platform.platform_role_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.platform_role_events FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.platform_role_events FROM PUBLIC;
CREATE FUNCTION platform.platform_role_history() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 changed platform.platform_roles;
BEGIN
 IF TG_OP = 'DELETE' THEN changed := OLD; ELSE changed := NEW; END IF;
 INSERT INTO platform.platform_role_events(operation,account_id,role,status,reason,revoke_reason)
 VALUES(TG_OP,changed.account_id,changed.role,changed.status,changed.reason,changed.revoke_reason);
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION platform.platform_role_history() FROM PUBLIC;
CREATE TRIGGER platform_role_history AFTER INSERT OR UPDATE OR DELETE ON platform.platform_roles
 FOR EACH ROW EXECUTE FUNCTION platform.platform_role_history();

CREATE FUNCTION platform.has_platform_capability(required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(required = ANY(string_to_array(nullif(current_setting('app.platform_capabilities',true),''),',')),false) $$;
REVOKE ALL ON FUNCTION platform.has_platform_capability(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.has_platform_capability(text) TO cp_api;

-- Catálogo global: leitura de todos os condomínios e mudança de nome/estado somente com capacidade publicada.
ALTER TABLE platform.tenants ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE platform.tenants ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE POLICY tenant_platform_read ON platform.tenants FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read'));
-- USING exige a leitura global (UPDATE sem WHERE não alcança linhas fora dela, nem bloqueia a linha por membro);
-- WITH CHECK recusa com 42501 a alteração sem tenants.manage.
CREATE POLICY tenant_platform_update ON platform.tenants FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('tenants.read')) WITH CHECK(platform.has_platform_capability('tenants.manage'));
GRANT UPDATE(name,active,version,updated_at) ON platform.tenants TO cp_api;

-- Convite de condomínio suspenso não cria vínculo. O convidado ainda sem vínculo enxerga o condomínio ativo só pelo
-- convite pendente apresentado (hash local da transação), e o INSERT do vínculo pelo convite exige condomínio ativo.
CREATE POLICY tenant_invitation_read ON platform.tenants FOR SELECT TO cp_api
 USING(active AND EXISTS(SELECT 1 FROM cp.membership_invitations i
  WHERE i.tenant_id=platform.tenants.id AND i.status='PENDING'
   AND i.token_hash=nullif(current_setting('app.invitation_hash',true),'')));
DROP POLICY membership_invitation_insert ON cp.memberships;
CREATE POLICY membership_invitation_insert ON cp.memberships FOR INSERT TO cp_api
 WITH CHECK(account_id=platform.current_actor_id() AND status='PENDING'
  AND tenant_id=platform.current_tenant_id() AND EXISTS(
   SELECT 1 FROM cp.membership_invitations i
   WHERE i.token_hash=nullif(current_setting('app.invitation_hash',true),'')
    AND i.id=cp.memberships.invitation_id
    AND i.tenant_id=cp.memberships.tenant_id AND i.status='PENDING' AND i.expires_at>now()
    AND i.role=cp.memberships.role AND i.unit_id IS NOT DISTINCT FROM cp.memberships.unit_id
    AND i.display_name=cp.memberships.display_name)
  AND EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=cp.memberships.tenant_id AND t.active));

-- Resultado agregado do onboarding (contagens), sem principal do banco nem hash do manifesto.
CREATE POLICY onboarding_run_platform_read ON platform.onboarding_runs FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read'));
GRANT SELECT(operation_id,tenant_id,counts,created_at) ON platform.onboarding_runs TO cp_api;

-- Auditoria da plataforma, append-only para a role runtime: ator publicado e capacidade de alterar.
CREATE TABLE platform.platform_audit_log (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 actor_id uuid NOT NULL REFERENCES platform.accounts(id),
 action text NOT NULL CHECK (action ~ '^[A-Z][A-Z_]{2,63}$'),
 tenant_id uuid REFERENCES platform.tenants(id),
 reason text CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
 details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object' AND length(details::text) <= 4096),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX platform_audit_recent ON platform.platform_audit_log(created_at DESC, id DESC);
ALTER TABLE platform.platform_audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.platform_audit_log FORCE ROW LEVEL SECURITY;
-- Inserção pelo próprio ator e somente com a capacidade de alterar (leitura não grava trilha).
CREATE POLICY platform_audit_insert ON platform.platform_audit_log FOR INSERT TO cp_api
 WITH CHECK(actor_id = platform.current_actor_id() AND platform.has_platform_capability('tenants.manage'));
CREATE POLICY platform_audit_read ON platform.platform_audit_log FOR SELECT TO cp_api
 USING(platform.has_platform_capability('platform.audit.read'));
REVOKE ALL ON platform.platform_audit_log FROM PUBLIC;
GRANT SELECT ON platform.platform_audit_log TO cp_api;
GRANT INSERT(actor_id,action,tenant_id,reason,details) ON platform.platform_audit_log TO cp_api;
