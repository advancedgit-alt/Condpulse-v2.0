-- Vínculos pela gestão e convite individual (CP-014/CP-020). A gestão lê e decide
-- vínculos somente com memberships.manage publicado após AAL2. Quem recebe o convite
-- cria apenas o próprio vínculo PENDING, ligado a um convite válido no contexto local.

-- Espelho local da identidade Auth já validada. Nunca cria usuário no provedor.
CREATE POLICY account_self_insert ON platform.accounts FOR INSERT TO cp_api
 WITH CHECK(id=platform.current_actor_id() AND status='ACTIVE');
GRANT INSERT(id,status) ON platform.accounts TO cp_api;

CREATE TABLE cp.membership_invitations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid,
 role text NOT NULL CHECK(role IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER','CONCIERGE')),
 display_name text NOT NULL CHECK(length(btrim(display_name)) BETWEEN 1 AND 160),
 -- SHA-256 de "<id>:<e-mail normalizado>": confere o destinatário sem guardar o e-mail.
 email_hash text NOT NULL CHECK(email_hash ~ '^[0-9a-f]{64}$'),
 token_hash text NOT NULL UNIQUE CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','REVOKED')),
 expires_at timestamptz NOT NULL,
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 accepted_by uuid REFERENCES platform.accounts(id),
 accepted_at timestamptz,
 membership_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,membership_id) REFERENCES cp.memberships(tenant_id,id),
 CHECK(expires_at>created_at AND expires_at<=created_at+interval '14 days'),
 CHECK(role NOT IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER') OR unit_id IS NOT NULL),
 CHECK((status='ACCEPTED')=(accepted_by IS NOT NULL AND accepted_at IS NOT NULL AND membership_id IS NOT NULL))
);
CREATE INDEX membership_invitations_pending ON cp.membership_invitations(tenant_id,status,expires_at);
ALTER TABLE cp.membership_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.membership_invitations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.membership_invitations FROM PUBLIC;
GRANT SELECT ON cp.membership_invitations TO cp_api;
GRANT INSERT(id,tenant_id,unit_id,role,display_name,email_hash,token_hash,expires_at,created_by)
 ON cp.membership_invitations TO cp_api;
GRANT UPDATE(status,accepted_by,accepted_at,membership_id,updated_at,version) ON cp.membership_invitations TO cp_api;

CREATE POLICY invitation_staff_read ON cp.membership_invitations FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND platform.has_capability('memberships.manage'));
CREATE POLICY invitation_staff_insert ON cp.membership_invitations FOR INSERT TO cp_api
 WITH CHECK(tenant_id=platform.current_tenant_id() AND platform.has_capability('memberships.manage')
  AND created_by=platform.current_actor_id() AND status='PENDING');
CREATE POLICY invitation_staff_revoke ON cp.membership_invitations FOR UPDATE TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND platform.has_capability('memberships.manage') AND status='PENDING')
 WITH CHECK(tenant_id=platform.current_tenant_id() AND platform.has_capability('memberships.manage') AND status='REVOKED');
-- O hash do token apresentado é definido localmente na transação; nenhuma listagem pública.
CREATE POLICY invitation_by_token ON cp.membership_invitations FOR SELECT TO cp_api
 USING(token_hash=nullif(current_setting('app.invitation_hash',true),''));
CREATE POLICY invitation_accept ON cp.membership_invitations FOR UPDATE TO cp_api
 USING(token_hash=nullif(current_setting('app.invitation_hash',true),'') AND status='PENDING' AND expires_at>now())
 WITH CHECK(token_hash=nullif(current_setting('app.invitation_hash',true),'') AND status='ACCEPTED'
  AND accepted_by=platform.current_actor_id());

-- Gestão de vínculos do próprio condomínio.
CREATE POLICY membership_staff_read ON cp.memberships FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND platform.has_capability('memberships.manage'));
CREATE POLICY membership_staff_update ON cp.memberships FOR UPDATE TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND platform.has_capability('memberships.manage'))
 WITH CHECK(tenant_id=platform.current_tenant_id() AND platform.has_capability('memberships.manage'));
GRANT UPDATE(status,valid_until,updated_at,version) ON cp.memberships TO cp_api;
-- Convidado cria somente o próprio vínculo pendente, igual ao convite válido.
CREATE POLICY membership_invitation_insert ON cp.memberships FOR INSERT TO cp_api
 WITH CHECK(account_id=platform.current_actor_id() AND status='PENDING'
  AND tenant_id=platform.current_tenant_id() AND EXISTS(
   SELECT 1 FROM cp.membership_invitations i
   WHERE i.token_hash=nullif(current_setting('app.invitation_hash',true),'')
    AND i.tenant_id=cp.memberships.tenant_id AND i.status='PENDING' AND i.expires_at>now()
    AND i.role=cp.memberships.role AND i.unit_id IS NOT DISTINCT FROM cp.memberships.unit_id
    AND i.display_name=cp.memberships.display_name));
GRANT INSERT(id,tenant_id,account_id,unit_id,display_name,role,status,valid_from) ON cp.memberships TO cp_api;
