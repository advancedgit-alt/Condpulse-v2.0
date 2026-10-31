-- Painel master (fase 4, OPS-01): criação de condomínio pela interface, convite do primeiro gestor e leitura agregada
-- do catálogo. Mesmo rigor do onboarding operacional (ADR-0007): papel de plataforma com AAL2 publicado pela API,
-- trilha da plataforma exigida no banco para cada criação, chave de idempotência e nenhuma credencial Auth Admin na
-- API. A conta do primeiro gestor não é resolvida pelo e-mail no servidor: o gestor prova o e-mail confirmado no
-- Auth ao aceitar um convite de uso único (token só em hash, como CP-020/CP-037), e só então o vínculo nasce ativo.

-- 1. Leitura agregada do catálogo (tenants.read): contagem de unidades e vínculos e lista de gestores. A API devolve
-- só contagens, gestores e datas; o banco não restringe colunas por política (decisão registrada em PAINEL_MASTER.md).
CREATE POLICY units_platform_read ON cp.units FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read'));
CREATE POLICY memberships_platform_read ON cp.memberships FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read'));

-- 2. Registro da criação pela plataforma: idempotência por conta e chave, hash do pedido e contagens.
-- creation_xid prende a criação à transação que a gravou: unidades e área só entram no condomínio criado na mesma
-- transação, pela mesma conta. A FK para o condomínio é diferida porque o registro precede o próprio condomínio.
CREATE TABLE platform.tenant_creations (
 tenant_id uuid PRIMARY KEY,
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 idempotency_key text NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
 request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
 counts jsonb NOT NULL CHECK (jsonb_typeof(counts) = 'object' AND length(counts::text) <= 512),
 creation_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (created_by, idempotency_key),
 CONSTRAINT tenant_creations_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenants(id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE platform.tenant_creations ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_creations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.tenant_creations FROM PUBLIC;
CREATE POLICY tenant_creation_read ON platform.tenant_creations FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read'));
CREATE POLICY tenant_creation_insert ON platform.tenant_creations FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('tenants.manage') AND created_by = platform.current_actor_id());
GRANT SELECT ON platform.tenant_creations TO cp_api;
GRANT INSERT(tenant_id,created_by,idempotency_key,request_hash,counts) ON platform.tenant_creations TO cp_api;

-- Verdadeiro somente na transação que registrou a criação, para a mesma conta e com tenants.manage publicada.
CREATE FUNCTION platform.tenant_created_here(target uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_platform_capability('tenants.manage') AND EXISTS(
  SELECT 1 FROM platform.tenant_creations c
  WHERE c.tenant_id = target AND c.created_by = platform.current_actor_id() AND c.creation_xid = pg_current_xact_id()) $$;
REVOKE ALL ON FUNCTION platform.tenant_created_here(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.tenant_created_here(uuid) TO cp_api;

-- A criação só termina com o evento TENANT_CREATED do mesmo ator na trilha da plataforma (conferido no COMMIT).
CREATE FUNCTION platform.tenant_creation_audited() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.platform_audit_log a
   WHERE a.tenant_id = NEW.tenant_id AND a.action = 'TENANT_CREATED' AND a.actor_id = NEW.created_by) THEN
  RAISE EXCEPTION 'Criação de condomínio sem evento de auditoria da plataforma.' USING ERRCODE = '23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION platform.tenant_creation_audited() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER tenant_creation_audited AFTER INSERT ON platform.tenant_creations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.tenant_creation_audited();

-- 3. Condomínio, unidades e área inicial pela plataforma, somente dentro da transação de criação registrada.
-- O condomínio nasce ativo e na versão 0; o fuso é conferido pela API contra pg_timezone_names na mesma transação.
CREATE POLICY tenant_platform_insert ON platform.tenants FOR INSERT TO cp_api
 WITH CHECK(active AND version = 0 AND platform.tenant_created_here(id));
GRANT INSERT(id,name,time_zone,active) ON platform.tenants TO cp_api;
-- As políticas restritivas da gestão local (V006) passam a aceitar também a criação registrada nesta transação; o
-- tenant_boundary continua exigindo o condomínio publicado no contexto local.
DROP POLICY units_manage_insert ON cp.units;
CREATE POLICY units_manage_insert ON cp.units AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('units.manage') OR platform.tenant_created_here(tenant_id));
DROP POLICY amenities_manage_insert ON cp.amenities;
CREATE POLICY amenities_manage_insert ON cp.amenities AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('amenities.manage') OR platform.tenant_created_here(tenant_id));

-- 4. Convite do primeiro gestor, emitido pela plataforma. O e-mail não é guardado: SHA-256 de "<id>:<e-mail
-- normalizado>", conferido contra o e-mail do token Auth de quem aceita. Um convite pendente por condomínio.
CREATE FUNCTION platform.current_manager_invitation_hash() RETURNS text
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT nullif(current_setting('app.manager_invitation_hash',true),'') $$;
REVOKE ALL ON FUNCTION platform.current_manager_invitation_hash() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.current_manager_invitation_hash() TO cp_api;

CREATE TABLE platform.manager_invitations (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 display_name text NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 160),
 email_hash text NOT NULL CHECK (email_hash ~ '^[0-9a-f]{64}$'),
 token_hash text NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','REVOKED')),
 expires_at timestamptz NOT NULL,
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 accepted_by uuid REFERENCES platform.accounts(id),
 accepted_at timestamptz,
 membership_id uuid,
 revoked_by uuid REFERENCES platform.accounts(id),
 revoked_at timestamptz,
 revoke_reason text CHECK (revoke_reason IS NULL OR length(btrim(revoke_reason)) BETWEEN 1 AND 500),
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 FOREIGN KEY (tenant_id, membership_id) REFERENCES cp.memberships(tenant_id, id),
 CHECK (expires_at > created_at AND expires_at <= created_at + interval '14 days'),
 CHECK ((status = 'ACCEPTED') = (accepted_by IS NOT NULL AND accepted_at IS NOT NULL AND membership_id IS NOT NULL)),
 CHECK ((status = 'REVOKED') = (revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);
CREATE UNIQUE INDEX manager_invitations_one_pending ON platform.manager_invitations(tenant_id) WHERE status = 'PENDING';
CREATE INDEX manager_invitations_tenant ON platform.manager_invitations(tenant_id, created_at DESC);
ALTER TABLE platform.manager_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.manager_invitations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.manager_invitations FROM PUBLIC;
GRANT SELECT ON platform.manager_invitations TO cp_api;
GRANT INSERT(id,tenant_id,display_name,email_hash,token_hash,expires_at,created_by) ON platform.manager_invitations TO cp_api;
GRANT UPDATE(status,accepted_by,accepted_at,membership_id,revoked_by,revoked_at,revoke_reason,version)
 ON platform.manager_invitations TO cp_api;

CREATE POLICY manager_invitation_platform_read ON platform.manager_invitations FOR SELECT TO cp_api
 USING(platform.has_platform_capability('tenants.read'));
CREATE POLICY manager_invitation_platform_insert ON platform.manager_invitations FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('tenants.manage') AND created_by = platform.current_actor_id()
  AND status = 'PENDING' AND version = 0
  AND EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id = platform.manager_invitations.tenant_id AND t.active));
-- A plataforma só revoga convite pendente, com motivo e o próprio ator.
CREATE POLICY manager_invitation_platform_revoke ON platform.manager_invitations FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('tenants.read') AND status = 'PENDING')
 WITH CHECK(platform.has_platform_capability('tenants.manage') AND status = 'REVOKED'
  AND revoked_by = platform.current_actor_id() AND accepted_by IS NULL);
-- O convidado vê só o convite do hash apresentado, definido localmente na transação; nenhuma listagem pública.
CREATE POLICY manager_invitation_by_token ON platform.manager_invitations FOR SELECT TO cp_api
 USING(token_hash = platform.current_manager_invitation_hash());
CREATE POLICY manager_invitation_accept ON platform.manager_invitations FOR UPDATE TO cp_api
 USING(token_hash = platform.current_manager_invitation_hash() AND status = 'PENDING' AND expires_at > now())
 WITH CHECK(token_hash = platform.current_manager_invitation_hash() AND status = 'ACCEPTED'
  AND accepted_by = platform.current_actor_id() AND revoked_by IS NULL AND EXISTS(
   SELECT 1 FROM cp.memberships m
   WHERE m.tenant_id = platform.manager_invitations.tenant_id AND m.id = platform.manager_invitations.membership_id
    AND m.account_id = platform.current_actor_id() AND m.role = 'MANAGER' AND m.status = 'ACTIVE'));

-- O convidado enxerga o condomínio ativo do convite de gestor pendente apresentado (para conferir o estado).
CREATE POLICY tenant_manager_invitation_read ON platform.tenants FOR SELECT TO cp_api
 USING(active AND EXISTS(SELECT 1 FROM platform.manager_invitations i
  WHERE i.tenant_id = platform.tenants.id AND i.status = 'PENDING'
   AND i.token_hash = platform.current_manager_invitation_hash()));

-- O convidado cria somente o próprio vínculo MANAGER ativo, sem unidade, com o nome do convite válido e em
-- condomínio ativo. É o único caminho da API para um vínculo nascer ACTIVE.
CREATE POLICY membership_manager_invitation_insert ON cp.memberships FOR INSERT TO cp_api
 WITH CHECK(account_id = platform.current_actor_id() AND role = 'MANAGER' AND status = 'ACTIVE' AND unit_id IS NULL
  AND invitation_id IS NULL AND tenant_id = platform.current_tenant_id() AND EXISTS(
   SELECT 1 FROM platform.manager_invitations i
   WHERE i.token_hash = platform.current_manager_invitation_hash()
    AND i.tenant_id = cp.memberships.tenant_id AND i.status = 'PENDING' AND i.expires_at > now()
    AND i.display_name = cp.memberships.display_name)
  AND EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id = cp.memberships.tenant_id AND t.active));
