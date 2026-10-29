-- Encomendas (CP-029/CP-030/CP-031). A portaria (capacidade concierge.operate, publicada só com vínculo vigente e
-- AAL2) recebe, entrega e anula registro indevido; morador e responsável veem as encomendas das unidades em que têm
-- vínculo residencial vigente, e o dependente só com permissão explícita (permissions.parcels.view). Retirada
-- identificada: a pessoa que retira é escolhida entre os vínculos residenciais vigentes da unidade (dependente só com
-- permissions.parcels.pickup) e gravada no registro de retirada. Uma única entrega por encomenda (UNIQUE no registro
-- de retirada) e "entregue" somente junto com esse registro, conferido no commit. Foto, código de retirada e delegação
-- ficam para tickets próprios (CP-017/CP-041).

-- Registro indevido (unidade errada, duplicidade): a encomenda fica ANULADA com motivo e responsável, nada é apagado.
ALTER TABLE cp.parcels DROP CONSTRAINT parcels_status_check;
ALTER TABLE cp.parcels ADD CONSTRAINT parcels_status_check CHECK(status IN ('RECEIVED','DELIVERED','VOIDED'));
ALTER TABLE cp.parcels ADD COLUMN voided_by uuid REFERENCES platform.accounts(id),
 ADD COLUMN voided_at timestamptz,
 ADD COLUMN void_reason text CHECK(void_reason IS NULL OR length(btrim(void_reason)) BETWEEN 1 AND 500),
 ADD CONSTRAINT parcels_void_fields CHECK((status='VOIDED')=(voided_by IS NOT NULL AND voided_at IS NOT NULL AND void_reason IS NOT NULL));

-- Retirada assistida grava o vínculo escolhido (mesmo condomínio pela chave composta).
ALTER TABLE cp.parcel_handoffs ADD COLUMN recipient_membership_id uuid,
 ADD CONSTRAINT parcel_handoffs_recipient_fk FOREIGN KEY(tenant_id,recipient_membership_id) REFERENCES cp.memberships(tenant_id,id),
 ADD CONSTRAINT parcel_handoffs_assisted_recipient CHECK(evidence_kind<>'ASSISTED' OR recipient_membership_id IS NOT NULL);

-- Grants mínimos: a aplicação não escolhe estado inicial, datas ou responsável de outra conta.
REVOKE INSERT,UPDATE ON cp.parcels FROM cp_api;
GRANT INSERT(id,tenant_id,unit_id,quantity,description,storage_location,received_by) ON cp.parcels TO cp_api;
GRANT UPDATE(status,version,voided_by,void_reason) ON cp.parcels TO cp_api;
REVOKE INSERT ON cp.parcel_handoffs FROM cp_api;
GRANT INSERT(id,tenant_id,parcel_id,operator_id,recipient_name,recipient_membership_id,evidence_kind) ON cp.parcel_handoffs TO cp_api;

-- Papéis que veem e que retiram encomendas da unidade.
CREATE FUNCTION cp.parcel_viewer_role(member_role text,member_permissions jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT member_role IN ('RESIDENT','RESPONSIBLE')
  OR (member_role='DEPENDENT' AND coalesce(member_permissions#>'{parcels,view}'='true'::jsonb,false)) $$;
CREATE FUNCTION cp.parcel_pickup_role(member_role text,member_permissions jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT member_role IN ('RESIDENT','RESPONSIBLE')
  OR (member_role='DEPENDENT' AND coalesce(member_permissions#>'{parcels,pickup}'='true'::jsonb,false)) $$;
REVOKE ALL ON FUNCTION cp.parcel_viewer_role(text,jsonb),cp.parcel_pickup_role(text,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.parcel_viewer_role(text,jsonb),cp.parcel_pickup_role(text,jsonb) TO cp_api;

-- Vínculo residencial vigente da própria conta ativa com a unidade ativa (proprietário não residente não vê as
-- encomendas; dependente só com permissão explícita), igual ao contexto montado pelo Java.
CREATE FUNCTION cp.resident_of_unit(target_tenant uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=platform.current_actor_id()
   AND m.status='ACTIVE' AND cp.parcel_viewer_role(m.role,m.permissions)
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) $$;
REVOKE ALL ON FUNCTION cp.resident_of_unit(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.resident_of_unit(uuid,uuid) TO cp_api;

-- A portaria enxerga os vínculos residenciais vigentes do condomínio (nome e papel, para conferir quem retira) e o
-- estado das contas desses vínculos. Proprietário, gestão e equipe ficam fora.
CREATE POLICY membership_concierge_read ON cp.memberships FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND platform.has_capability('concierge.operate') AND unit_id IS NOT NULL
  AND role IN ('RESIDENT','RESPONSIBLE','DEPENDENT') AND status='ACTIVE');
CREATE POLICY account_concierge_read ON platform.accounts FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') AND EXISTS(SELECT 1 FROM cp.memberships m
  WHERE m.account_id=platform.accounts.id AND m.tenant_id=platform.current_tenant_id() AND m.unit_id IS NOT NULL
   AND m.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT') AND m.status='ACTIVE'));

CREATE POLICY parcels_read_scope ON cp.parcels AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR (status<>'VOIDED' AND cp.resident_of_unit(tenant_id,unit_id)));
CREATE POLICY parcels_receive ON cp.parcels AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate') AND received_by=platform.current_actor_id() AND status='RECEIVED'
  AND EXISTS(SELECT 1 FROM cp.units u WHERE u.tenant_id=cp.parcels.tenant_id AND u.id=cp.parcels.unit_id AND u.status='ACTIVE'));
CREATE POLICY parcels_update ON cp.parcels AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('concierge.operate'))
 WITH CHECK(platform.has_capability('concierge.operate') AND (status<>'VOIDED' OR voided_by=platform.current_actor_id()));
CREATE POLICY handoffs_read_scope ON cp.parcel_handoffs AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR EXISTS(SELECT 1 FROM cp.parcels p
  WHERE p.tenant_id=cp.parcel_handoffs.tenant_id AND p.id=cp.parcel_handoffs.parcel_id AND cp.resident_of_unit(p.tenant_id,p.unit_id)));
-- Retirada assistida somente para vínculo residencial vigente, de conta ativa, da unidade da encomenda, com o nome
-- gravado igual ao do vínculo.
CREATE POLICY handoffs_register ON cp.parcel_handoffs AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate') AND operator_id=platform.current_actor_id() AND evidence_kind='ASSISTED'
  AND EXISTS(SELECT 1 FROM cp.parcels p
   JOIN cp.units u ON u.tenant_id=p.tenant_id AND u.id=p.unit_id AND u.status='ACTIVE'
   JOIN cp.memberships m ON m.tenant_id=p.tenant_id AND m.unit_id=p.unit_id
   JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
   WHERE p.tenant_id=cp.parcel_handoffs.tenant_id AND p.id=cp.parcel_handoffs.parcel_id
    AND m.id=cp.parcel_handoffs.recipient_membership_id AND m.status='ACTIVE' AND cp.parcel_pickup_role(m.role,m.permissions)
    AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
    AND m.display_name=cp.parcel_handoffs.recipient_name));
-- Eventos e trilha das encomendas só pela portaria, em nome da própria conta.
CREATE POLICY outbox_parcel_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type NOT LIKE 'PARCEL\_%' OR platform.has_capability('concierge.operate'));
CREATE POLICY audit_parcel_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'PARCEL\_%' OR (platform.has_capability('concierge.operate') AND actor_id=platform.current_actor_id()));

-- Transições: RECEIVED -> DELIVERED (retirada) ou RECEIVED -> VOIDED (registro indevido), com a versão avançando uma
-- unidade e horários do banco. Retirada e anulação são finais.
CREATE FUNCTION cp.parcel_transition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.status<>'RECEIVED' OR NEW.status NOT IN ('DELIVERED','VOIDED') OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'Transição de encomenda não permitida.' USING ERRCODE='23514';
 END IF;
 IF NEW.status='VOIDED' THEN NEW.voided_at := clock_timestamp(); END IF;
 NEW.updated_at := clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.parcel_transition_guard() FROM PUBLIC;
CREATE TRIGGER parcels_transition_guard BEFORE UPDATE ON cp.parcels
 FOR EACH ROW EXECUTE FUNCTION cp.parcel_transition_guard();

-- No commit: DELIVERED se e somente se existe o registro de retirada. Executa como quem gravou; encomenda invisível
-- para quem gravou falha fechado (42501).
CREATE FUNCTION cp.parcel_delivery_consistency() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 target uuid;
 delivered boolean;
 handed boolean;
BEGIN
 IF TG_TABLE_NAME='parcels' THEN target := NEW.id; ELSE target := NEW.parcel_id; END IF;
 SELECT p.status='DELIVERED' INTO delivered FROM cp.parcels p WHERE p.tenant_id=NEW.tenant_id AND p.id=target;
 IF delivered IS NULL THEN
  RAISE EXCEPTION 'Encomenda não visível para conferir a entrega.' USING ERRCODE='42501';
 END IF;
 SELECT EXISTS(SELECT 1 FROM cp.parcel_handoffs h WHERE h.tenant_id=NEW.tenant_id AND h.parcel_id=target) INTO handed;
 IF delivered<>handed THEN
  RAISE EXCEPTION 'Entrega exige um único registro de retirada.' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.parcel_delivery_consistency() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER parcels_delivery_consistency AFTER INSERT OR UPDATE OF status ON cp.parcels
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cp.parcel_delivery_consistency();
CREATE CONSTRAINT TRIGGER parcel_handoffs_delivery_consistency AFTER INSERT ON cp.parcel_handoffs
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cp.parcel_delivery_consistency();

CREATE INDEX parcels_recent ON cp.parcels(tenant_id,received_at DESC,id DESC);
CREATE INDEX parcels_by_unit ON cp.parcels(tenant_id,unit_id,received_at DESC,id DESC);
-- Fila da portaria: pendentes da mais antiga para a mais nova, para nenhuma sair da tela por excesso de novas.
CREATE INDEX parcels_waiting ON cp.parcels(tenant_id,received_at,id) WHERE status='RECEIVED';
