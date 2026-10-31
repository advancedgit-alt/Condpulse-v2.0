-- Central administradora (fatia 2 do desenho docs/ESTRUTURA_CENTRAL_E_CONDOMINIO.md): módulos liberados por condomínio.
-- A plataforma (PLATFORM_ADMIN com a nova capacidade tenant_modules.manage, publicada pela API só depois de papel vigente
-- e AAL2) liga ou desliga um módulo de um condomínio pela versão lida, com motivo e evento na trilha da plataforma
-- exigido no COMMIT. A role runtime lê o estado no contexto do próprio condomínio (executores de tenant e técnico), nos
-- condomínios dos próprios vínculos (/v1/me) e pelo link público de visita. A flag global continua mandando: o módulo só
-- vale com a flag ligada E o condomínio liberado.
-- Padrão: sem linha = liberado (estado atual de todo condomínio existente). Nada muda até a central alterar.
-- Nada é apagado: desligar é gravar enabled=false com nova versão.
CREATE TABLE platform.tenant_modules (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 module text NOT NULL CHECK (module IN ('reservations','parcels','visits','announcements','requests','finance',
  'access_credentials','facial_access')),
 enabled boolean NOT NULL,
 -- Versão 0 é o padrão sem linha; a primeira gravação nasce na versão 1 e cada mudança soma 1.
 version bigint NOT NULL CHECK (version >= 1),
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 3 AND 500),
 updated_by uuid NOT NULL REFERENCES platform.accounts(id),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (tenant_id, module)
);

-- Nascimento na versão 1; mudança só de enabled/motivo/autor com versão+1; horário sempre do banco.
CREATE FUNCTION platform.tenant_modules_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.version<>1 THEN
   RAISE EXCEPTION 'módulo do condomínio nasce na versão 1' USING ERRCODE='23514';
  END IF;
 ELSIF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.module IS DISTINCT FROM OLD.module
   OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'módulo do condomínio só muda pela versão seguinte' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.tenant_modules_guard() FROM PUBLIC;
CREATE TRIGGER tenant_modules_guard BEFORE INSERT OR UPDATE ON platform.tenant_modules
 FOR EACH ROW EXECUTE FUNCTION platform.tenant_modules_guard();

-- Cada gravação só termina com o evento da trilha da plataforma do mesmo ator, módulo, estado e versão (no COMMIT).
CREATE FUNCTION platform.tenant_modules_audited() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.platform_audit_log a WHERE a.tenant_id=NEW.tenant_id AND a.actor_id=NEW.updated_by
   AND a.action=CASE WHEN NEW.enabled THEN 'TENANT_MODULE_ENABLED' ELSE 'TENANT_MODULE_DISABLED' END
   AND a.details->>'module'=NEW.module AND a.details->>'version'=NEW.version::text) THEN
  RAISE EXCEPTION 'Módulo do condomínio sem evento de auditoria da plataforma.' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION platform.tenant_modules_audited() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER tenant_modules_audited AFTER INSERT OR UPDATE ON platform.tenant_modules
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.tenant_modules_audited();

ALTER TABLE platform.tenant_modules ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_modules FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.tenant_modules FROM PUBLIC;
GRANT SELECT ON platform.tenant_modules TO cp_api;
GRANT INSERT(tenant_id,module,enabled,version,reason,updated_by) ON platform.tenant_modules TO cp_api;
GRANT UPDATE(enabled,version,reason,updated_by) ON platform.tenant_modules TO cp_api;
-- Sem DELETE para ninguém da aplicação; cp_worker sem acesso nesta fatia.

-- Plataforma: leitura com o catálogo ou com a capacidade de alterar; gravação só com tenant_modules.manage e o próprio ator.
CREATE POLICY tenant_modules_platform_read ON platform.tenant_modules FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read') OR platform.has_platform_capability('tenant_modules.manage'));
CREATE POLICY tenant_modules_platform_insert ON platform.tenant_modules FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('tenant_modules.manage') AND updated_by=platform.current_actor_id());
CREATE POLICY tenant_modules_platform_update ON platform.tenant_modules FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('tenant_modules.manage'))
 WITH CHECK(platform.has_platform_capability('tenant_modules.manage') AND updated_by=platform.current_actor_id());
-- Runtime do condomínio: o executor publica app.tenant_id só depois de conta ativa e do condomínio ativo; a linha do
-- próprio condomínio fica visível para a conferência do módulo na mesma transação.
CREATE POLICY tenant_modules_context_read ON platform.tenant_modules FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id());
-- /v1/me: módulos dos condomínios em que a própria conta tem vínculo ativo (menu).
CREATE POLICY tenant_modules_member_read ON platform.tenant_modules FOR SELECT TO cp_api
 USING(EXISTS(SELECT 1 FROM cp.memberships m WHERE m.tenant_id=platform.tenant_modules.tenant_id
  AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'));
-- Link público de visita: só o condomínio do link apresentado (hash local da transação).
CREATE POLICY tenant_modules_permit_link_read ON platform.tenant_modules FOR SELECT TO cp_api
 USING(EXISTS(SELECT 1 FROM cp.permit_links l WHERE l.tenant_id=platform.tenant_modules.tenant_id
  AND l.token_hash=nullif(current_setting('app.permit_link_hash',true),'')));

-- Estado do módulo no condomínio visto pela transação: sem linha visível vale o padrão (liberado). Quem chama precisa
-- estar num contexto em que a linha do condomínio é visível (políticas acima); a API só consulta nesses contextos.
CREATE FUNCTION platform.tenant_module_enabled(tenant uuid,module_key text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce((SELECT m.enabled FROM platform.tenant_modules m WHERE m.tenant_id=tenant AND m.module=module_key),true) $$;
REVOKE ALL ON FUNCTION platform.tenant_module_enabled(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.tenant_module_enabled(uuid,text) TO cp_api;

-- Trilha da plataforma: TENANT_MODULE_* passa a exigir tenant_modules.manage (tenants.manage não grava esses eventos).
DROP POLICY platform_audit_insert ON platform.platform_audit_log;
CREATE POLICY platform_audit_insert ON platform.platform_audit_log FOR INSERT TO cp_api
 WITH CHECK(actor_id = platform.current_actor_id() AND (
  (platform.has_platform_capability('tenants.manage') AND action LIKE 'TENANT\_%' AND action NOT LIKE 'TENANT\_MODULE\_%')
  OR (platform.has_platform_capability('tenant_modules.manage') AND action IN ('TENANT_MODULE_ENABLED','TENANT_MODULE_DISABLED'))
  OR (platform.has_platform_capability('billing.manage') AND action LIKE 'BILLING\_%')
  OR (platform.has_platform_capability('outbox.operate') AND action LIKE 'OUTBOX\_%')
  OR (platform.has_platform_capability('technical_grants.manage') AND action LIKE 'TECHNICAL\_GRANT\_%')
  OR (platform.has_platform_capability('access_catalog.manage') AND action='HOMOLOGATION_CREATED')
  OR (platform.has_platform_capability('homologation.approve') AND action LIKE 'HOMOLOGATION\_%' AND action<>'HOMOLOGATION_CREATED')));
-- A conferência do COMMIT (gatilho do invocador) lê só os eventos de módulo, com a mesma capacidade.
CREATE POLICY platform_audit_tenant_module_read ON platform.platform_audit_log FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenant_modules.manage') AND action IN ('TENANT_MODULE_ENABLED','TENANT_MODULE_DISABLED'));
