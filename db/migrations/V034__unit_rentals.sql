-- Proprietário × inquilino (regra do responsável, 30/10/2026): "proprietário que aluga perde a visão da unidade e só pode
-- incluir/remover o inquilino". OWNER (V001) é o proprietário; RESIDENT/RESPONSIBLE/DEPENDENT são os ocupantes. A gestão
-- (memberships.manage, AAL2) marca a unidade como alugada criando uma linha por vínculo OWNER não revogado e encerra a
-- locação pelo mesmo caminho. Enquanto a linha do proprietário estiver em vigor:
--  * todas as tabelas com unit_id escondem as linhas da unidade dele (política RESTRICTIVE, fora de contexto de equipe);
--  * os avisos deixam de contar o vínculo dele naquela unidade como público;
--  * ele só vê e revoga os vínculos de ocupante da unidade, só vê, cria e revoga convites RESIDENT/RESPONSIBLE dela e,
--    ao remover um ocupante, revoga as credenciais de acesso desse vínculo já revogado.
-- A posse fica nesta tabela (e não numa consulta a cp.memberships) para que as políticas de cp.memberships não
-- dependam de cp.memberships: as políticas desta tabela não consultam vínculos, e nenhuma cadeia de políticas recursa.

CREATE TABLE cp.unit_rentals (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,
 owner_membership_id uuid NOT NULL,
 -- Copiado do vínculo pelo gatilho: as políticas comparam a conta do ator sem ler cp.memberships.
 owner_account_id uuid NOT NULL REFERENCES platform.accounts(id),
 started_by uuid NOT NULL REFERENCES platform.accounts(id),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ended_by uuid REFERENCES platform.accounts(id),
 ended_at timestamptz,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,owner_membership_id) REFERENCES cp.memberships(tenant_id,id),
 CHECK((ended_by IS NULL)=(ended_at IS NULL)),
 CHECK(ended_at IS NULL OR ended_at>=started_at)
);
-- Uma locação em vigor por vínculo de proprietário; a unidade está alugada se houver ao menos uma.
CREATE UNIQUE INDEX unit_rentals_one_active ON cp.unit_rentals(tenant_id,owner_membership_id) WHERE ended_at IS NULL;
CREATE INDEX unit_rentals_active_unit ON cp.unit_rentals(tenant_id,unit_id) WHERE ended_at IS NULL;
CREATE INDEX unit_rentals_active_owner ON cp.unit_rentals(tenant_id,owner_account_id) WHERE ended_at IS NULL;

-- Início: o vínculo precisa ser OWNER não revogado da mesma unidade, e a conta do proprietário não pode ter vínculo de
-- ocupante não revogado nela (quem mora não "aluga" a própria casa para si). Conta, horário e encerramento vêm do banco.
-- Mudança: só encerrar, uma vez, com horário do banco.
CREATE FUNCTION cp.unit_rental_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog
AS $$
DECLARE owner record;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT m.account_id,m.unit_id,m.role,m.status INTO owner FROM cp.memberships m
   WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.owner_membership_id;
  IF NOT FOUND OR owner.role<>'OWNER' OR owner.status='REVOKED' OR owner.unit_id IS DISTINCT FROM NEW.unit_id THEN
   RAISE EXCEPTION 'vínculo de proprietário inválido para a unidade' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.unit_id=NEW.unit_id
    AND m.account_id=owner.account_id AND m.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT') AND m.status<>'REVOKED') THEN
   RAISE EXCEPTION 'proprietário ainda tem vínculo de ocupante na unidade' USING ERRCODE='23514';
  END IF;
  NEW.owner_account_id:=owner.account_id;
  NEW.started_at:=clock_timestamp();
  NEW.ended_by:=NULL; NEW.ended_at:=NULL;
  RETURN NEW;
 END IF;
 IF OLD.ended_at IS NOT NULL OR NEW.ended_by IS NULL
   OR (NEW.tenant_id,NEW.unit_id,NEW.owner_membership_id,NEW.owner_account_id,NEW.started_by,NEW.started_at)
    IS DISTINCT FROM (OLD.tenant_id,OLD.unit_id,OLD.owner_membership_id,OLD.owner_account_id,OLD.started_by,OLD.started_at) THEN
  RAISE EXCEPTION 'locação só pode ser encerrada uma vez' USING ERRCODE='23514';
 END IF;
 NEW.ended_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.unit_rental_guard() FROM PUBLIC;
CREATE TRIGGER unit_rentals_guard BEFORE INSERT OR UPDATE ON cp.unit_rentals
 FOR EACH ROW EXECUTE FUNCTION cp.unit_rental_guard();

REVOKE ALL ON cp.unit_rentals FROM PUBLIC;
GRANT SELECT ON cp.unit_rentals TO cp_api;
GRANT INSERT(id,tenant_id,unit_id,owner_membership_id,owner_account_id,started_by) ON cp.unit_rentals TO cp_api;
GRANT UPDATE(ended_by,ended_at) ON cp.unit_rentals TO cp_api;
-- Implantação operacional cria vínculos de ocupante: o gatilho de vínculos consulta esta tabela (sem escrita).
GRANT SELECT ON cp.unit_rentals TO cp_onboarding;
ALTER TABLE cp.unit_rentals ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.unit_rentals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.unit_rentals TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY onboarding_read ON cp.unit_rentals FOR SELECT TO cp_onboarding USING(tenant_id=platform.current_tenant_id());
-- Gestão vê todas; o proprietário, só as próprias. Sem DELETE: o histórico fica.
CREATE POLICY unit_rentals_read ON cp.unit_rentals AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR owner_account_id=platform.current_actor_id());
CREATE POLICY unit_rentals_start ON cp.unit_rentals AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('memberships.manage') AND started_by=platform.current_actor_id() AND ended_at IS NULL);
CREATE POLICY unit_rentals_end ON cp.unit_rentals AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('memberships.manage'))
 WITH CHECK(platform.has_capability('memberships.manage') AND ended_by=platform.current_actor_id());

-- Locação em vigor do ator (proprietário) nesta unidade. Lê só cp.unit_rentals.
CREATE FUNCTION cp.rental_owner_unit(target_tenant uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.unit_rentals r WHERE r.tenant_id=target_tenant AND r.unit_id=target_unit
  AND r.owner_account_id=platform.current_actor_id() AND r.ended_at IS NULL) $$;
REVOKE ALL ON FUNCTION cp.rental_owner_unit(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rental_owner_unit(uuid,uuid) TO cp_api;

-- Linha escondida do ator: unidade alugada em que ele é proprietário. Em contexto de equipe (capacidade publicada após
-- AAL2) nada é escondido: a gestão e a portaria seguem as próprias políticas. Função (e não subconsulta na política):
-- subconsulta tornaria a expansão de políticas recursiva entre convites, visitantes e chegadas.
CREATE FUNCTION cp.rental_owner_hidden(target_tenant uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT target_unit IS NOT NULL AND coalesce(current_setting('app.capabilities',true),'')=''
  AND cp.rental_owner_unit(target_tenant,target_unit) $$;
REVOKE ALL ON FUNCTION cp.rental_owner_hidden(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rental_owner_hidden(uuid,uuid) TO cp_api;

-- Ocupante já revogado da unidade alugada do ator: única linha de credencial que o proprietário alcança, para revogar.
CREATE FUNCTION cp.rental_removed_occupant(target_tenant uuid,target_unit uuid,target_membership uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT cp.rental_owner_unit(target_tenant,target_unit) AND EXISTS(SELECT 1 FROM cp.memberships m
  WHERE m.tenant_id=target_tenant AND m.id=target_membership AND m.unit_id=target_unit AND m.status='REVOKED'
   AND m.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT')) $$;
REVOKE ALL ON FUNCTION cp.rental_removed_occupant(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rental_removed_occupant(uuid,uuid,uuid) TO cp_api;

-- Barreira geral: em toda tabela com unit_id (exceto vínculos e convites, que o proprietário administra), a unidade
-- alugada some para o proprietário — leitura e escrita. Tabelas sem unit_id (encomenda entregue, mensagem de pedido,
-- anexo, sincronização e evento de credencial) dependem da linha-mãe, que já fica escondida pela política dela.
DO $$
DECLARE table_name text;
BEGIN
 FOREACH table_name IN ARRAY ARRAY[
  'access_events','arrival_requests','concierge_occurrences','device_events','import_rows','move_requests','notifications',
  'parcel_delegations','parcels','permit_links','reservation_waitlist','service_requests','visit_permits','visit_rules'
 ] LOOP
  EXECUTE format('CREATE POLICY rental_owner_hidden ON cp.%I AS RESTRICTIVE FOR ALL TO cp_api '
   'USING(NOT cp.rental_owner_hidden(tenant_id,unit_id)) WITH CHECK(NOT cp.rental_owner_hidden(tenant_id,unit_id))',table_name);
 END LOOP;
END $$;
-- Reservas: idem, exceto a leitura anônima de ocupação (consulta de disponibilidade da área, que devolve só intervalos sem
-- unidade): o proprietário que também mora em outra unidade continua vendo o horário como ocupado. A API liga
-- app.occupancy_scan somente durante essa consulta; escrita segue recusada.
CREATE FUNCTION cp.occupancy_scan() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(current_setting('app.occupancy_scan',true),'')='on' $$;
REVOKE ALL ON FUNCTION cp.occupancy_scan() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.occupancy_scan() TO cp_api;
CREATE POLICY rental_owner_hidden ON cp.reservations AS RESTRICTIVE FOR ALL TO cp_api
 USING(NOT cp.rental_owner_hidden(tenant_id,unit_id)
  OR (cp.occupancy_scan() AND status IN ('HELD','PENDING_APPROVAL','CONFIRMED')))
 WITH CHECK(NOT cp.rental_owner_hidden(tenant_id,unit_id));
-- Credenciais: idem, exceto as de ocupante já revogado, que o proprietário revoga ao remover o inquilino.
CREATE POLICY rental_owner_hidden ON cp.access_credentials AS RESTRICTIVE FOR ALL TO cp_api
 USING(NOT cp.rental_owner_hidden(tenant_id,unit_id) OR cp.rental_removed_occupant(tenant_id,unit_id,membership_id))
 WITH CHECK(NOT cp.rental_owner_hidden(tenant_id,unit_id) OR cp.rental_removed_occupant(tenant_id,unit_id,membership_id));
DROP POLICY access_credentials_read ON cp.access_credentials;
CREATE POLICY access_credentials_read ON cp.access_credentials AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate') OR cp.credential_own(tenant_id,membership_id)
  OR cp.rental_removed_occupant(tenant_id,unit_id,membership_id));
DROP POLICY access_credentials_revoke ON cp.access_credentials;
CREATE POLICY access_credentials_revoke ON cp.access_credentials AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('memberships.manage') OR cp.rental_removed_occupant(tenant_id,unit_id,membership_id))
 WITH CHECK((platform.has_capability('memberships.manage') OR cp.rental_removed_occupant(tenant_id,unit_id,membership_id))
  AND desired_state='REVOKED' AND revoked_by=platform.current_actor_id());
DROP POLICY audit_credential_actions ON cp.audit_log;
CREATE POLICY audit_credential_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'CREDENTIAL\_%' OR (actor_id=platform.current_actor_id() AND resource_type='ACCESS_CREDENTIAL'
  AND EXISTS(SELECT 1 FROM cp.access_credentials c WHERE c.tenant_id=cp.audit_log.tenant_id AND c.id=cp.audit_log.resource_id
   AND (platform.has_capability('memberships.manage')
    OR (cp.audit_log.action='CREDENTIAL_REVOKED' AND cp.rental_removed_occupant(c.tenant_id,c.unit_id,c.membership_id))))));
-- Trilha de locação só pela gestão, sobre unidade do condomínio, em nome da própria conta.
CREATE POLICY audit_rental_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'UNIT\_RENTAL\_%' OR (platform.has_capability('memberships.manage') AND actor_id=platform.current_actor_id()
  AND resource_type='UNIT' AND EXISTS(SELECT 1 FROM cp.units u WHERE u.tenant_id=cp.audit_log.tenant_id AND u.id=cp.audit_log.resource_id)));

-- Vínculos: o proprietário da unidade alugada vê os ocupantes dela e só pode revogá-los.
CREATE POLICY membership_rental_owner_read ON cp.memberships FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND unit_id IS NOT NULL AND role IN ('RESIDENT','RESPONSIBLE','DEPENDENT')
  AND cp.rental_owner_unit(tenant_id,unit_id));
CREATE POLICY membership_rental_owner_remove ON cp.memberships FOR UPDATE TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND unit_id IS NOT NULL AND role IN ('RESIDENT','RESPONSIBLE','DEPENDENT')
  AND status<>'REVOKED' AND cp.rental_owner_unit(tenant_id,unit_id))
 WITH CHECK(tenant_id=platform.current_tenant_id() AND unit_id IS NOT NULL AND role IN ('RESIDENT','RESPONSIBLE','DEPENDENT')
  AND status='REVOKED' AND cp.rental_owner_unit(tenant_id,unit_id));

-- Convites: o proprietário da unidade alugada convida inquilino (RESIDENT/RESPONSIBLE) para ela, vê esses convites e os
-- revoga enquanto pendentes. O aceite segue o fluxo existente (vínculo PENDING; a gestão ativa).
CREATE POLICY invitation_rental_owner_read ON cp.membership_invitations FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND unit_id IS NOT NULL AND role IN ('RESIDENT','RESPONSIBLE')
  AND cp.rental_owner_unit(tenant_id,unit_id));
CREATE POLICY invitation_rental_owner_insert ON cp.membership_invitations FOR INSERT TO cp_api
 WITH CHECK(tenant_id=platform.current_tenant_id() AND unit_id IS NOT NULL AND role IN ('RESIDENT','RESPONSIBLE')
  AND created_by=platform.current_actor_id() AND status='PENDING' AND cp.rental_owner_unit(tenant_id,unit_id));
CREATE POLICY invitation_rental_owner_revoke ON cp.membership_invitations FOR UPDATE TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND unit_id IS NOT NULL AND role IN ('RESIDENT','RESPONSIBLE') AND status='PENDING'
  AND cp.rental_owner_unit(tenant_id,unit_id))
 WITH CHECK(tenant_id=platform.current_tenant_id() AND unit_id IS NOT NULL AND role IN ('RESIDENT','RESPONSIBLE') AND status='REVOKED'
  AND cp.rental_owner_unit(tenant_id,unit_id));

-- Proprietário com locação em vigor não recebe nem reativa vínculo de ocupante na mesma unidade (aceite de convite,
-- aprovação ou implantação). Quem grava lê as próprias locações (convidado), todas (gestão) ou o condomínio (implantação).
CREATE FUNCTION cp.membership_rental_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog
AS $$
BEGIN
 IF NEW.unit_id IS NOT NULL AND NEW.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT') AND NEW.status<>'REVOKED'
   AND EXISTS(SELECT 1 FROM cp.unit_rentals r WHERE r.tenant_id=NEW.tenant_id AND r.unit_id=NEW.unit_id
    AND r.owner_account_id=NEW.account_id AND r.ended_at IS NULL) THEN
  RAISE EXCEPTION 'proprietário de unidade alugada não recebe vínculo de ocupante nela' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.membership_rental_guard() FROM PUBLIC;
CREATE TRIGGER memberships_rental_guard BEFORE INSERT OR UPDATE OF status ON cp.memberships
 FOR EACH ROW EXECUTE FUNCTION cp.membership_rental_guard();

-- Proprietário aprovado depois do início da locação também perde a visão; proprietário revogado encerra a própria linha.
-- Roda no contexto de quem decide o vínculo (gestão com memberships.manage), sujeito às políticas acima.
CREATE FUNCTION cp.owner_rental_sync() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog
AS $$
BEGIN
 IF NEW.status='REVOKED' THEN
  UPDATE cp.unit_rentals SET ended_by=platform.current_actor_id()
   WHERE tenant_id=NEW.tenant_id AND owner_membership_id=NEW.id AND ended_at IS NULL;
 ELSIF NEW.status='ACTIVE' AND EXISTS(SELECT 1 FROM cp.unit_rentals r WHERE r.tenant_id=NEW.tenant_id AND r.unit_id=NEW.unit_id
    AND r.ended_at IS NULL)
   AND NOT EXISTS(SELECT 1 FROM cp.unit_rentals r WHERE r.tenant_id=NEW.tenant_id AND r.owner_membership_id=NEW.id AND r.ended_at IS NULL) THEN
  INSERT INTO cp.unit_rentals(tenant_id,unit_id,owner_membership_id,owner_account_id,started_by)
   VALUES(NEW.tenant_id,NEW.unit_id,NEW.id,NEW.account_id,platform.current_actor_id());
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.owner_rental_sync() FROM PUBLIC;
CREATE TRIGGER memberships_owner_rental_sync AFTER UPDATE OF status ON cp.memberships
 FOR EACH ROW WHEN (NEW.role='OWNER' AND NEW.unit_id IS NOT NULL AND OLD.status IS DISTINCT FROM NEW.status)
 EXECUTE FUNCTION cp.owner_rental_sync();

-- Avisos: vínculo em unidade alugada pelo próprio ator (OWNER, ou ocupante gravado fora das regras) deixa de contar como
-- público, inclusive para avisos do condomínio inteiro; vínculos em outras unidades continuam valendo.
CREATE OR REPLACE FUNCTION cp.announcement_audience_member(target_tenant uuid,target_audience text,target_ids uuid[]) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
   AND m.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER')
   AND NOT cp.rental_owner_unit(m.tenant_id,m.unit_id)
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   AND (target_audience='TENANT' OR (target_audience='UNITS' AND u.id=ANY(target_ids))
    OR (target_audience='BLOCKS' AND u.block_id=ANY(target_ids)))) $$;
