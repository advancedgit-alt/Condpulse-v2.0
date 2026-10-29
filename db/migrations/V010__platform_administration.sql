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
 CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
ALTER TABLE platform.platform_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.platform_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_role_self ON platform.platform_roles FOR SELECT TO cp_api
 USING(account_id = platform.current_actor_id());
REVOKE ALL ON platform.platform_roles FROM PUBLIC;
GRANT SELECT ON platform.platform_roles TO cp_api;

CREATE FUNCTION platform.has_platform_capability(required text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(required = ANY(string_to_array(nullif(current_setting('app.platform_capabilities',true),''),',')),false) $$;
REVOKE ALL ON FUNCTION platform.has_platform_capability(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.has_platform_capability(text) TO cp_api;

-- Catálogo global: leitura de todos os condomínios e mudança de nome/estado somente com capacidade publicada.
-- UPDATE usa USING(true) + WITH CHECK para que a falta de capacidade seja recusa (42501), não "não encontrado".
ALTER TABLE platform.tenants ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE platform.tenants ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE POLICY tenant_platform_read ON platform.tenants FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read'));
CREATE POLICY tenant_platform_update ON platform.tenants FOR UPDATE TO cp_api
 USING(true) WITH CHECK(platform.has_platform_capability('tenants.manage'));
GRANT UPDATE(name,active,version,updated_at) ON platform.tenants TO cp_api;

-- Resultado agregado do onboarding (contagens), sem principal do banco nem hash do manifesto.
CREATE POLICY onboarding_run_platform_read ON platform.onboarding_runs FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read'));
GRANT SELECT(operation_id,tenant_id,counts,created_at) ON platform.onboarding_runs TO cp_api;

-- Auditoria da plataforma, append-only para a role runtime: ator publicado e alguma capacidade de plataforma.
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
CREATE POLICY platform_audit_insert ON platform.platform_audit_log FOR INSERT TO cp_api
 WITH CHECK(actor_id = platform.current_actor_id()
   AND coalesce(current_setting('app.platform_capabilities',true),'') <> '');
CREATE POLICY platform_audit_read ON platform.platform_audit_log FOR SELECT TO cp_api
 USING(platform.has_platform_capability('platform.audit.read'));
REVOKE ALL ON platform.platform_audit_log FROM PUBLIC;
GRANT SELECT ON platform.platform_audit_log TO cp_api;
GRANT INSERT(actor_id,action,tenant_id,reason,details) ON platform.platform_audit_log TO cp_api;
