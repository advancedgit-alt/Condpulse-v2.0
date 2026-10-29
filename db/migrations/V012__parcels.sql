-- Encomendas (CP-029/CP-030/CP-031). A portaria (capacidade concierge.operate, publicada só com vínculo vigente e
-- AAL2) recebe e entrega; morador, responsável e dependente veem somente as encomendas das unidades em que têm vínculo
-- vigente. Uma única entrega por encomenda (UNIQUE no registro de retirada) e "entregue" somente junto com esse
-- registro, conferido no commit. Foto, código de retirada e delegação ficam para tickets próprios (CP-017/CP-041).

-- Grants mínimos: a aplicação não escolhe estado inicial, datas ou responsável de outra conta.
REVOKE INSERT,UPDATE ON cp.parcels FROM cp_api;
GRANT INSERT(id,tenant_id,unit_id,quantity,description,storage_location,received_by) ON cp.parcels TO cp_api;
GRANT UPDATE(status,version) ON cp.parcels TO cp_api;
REVOKE INSERT ON cp.parcel_handoffs FROM cp_api;
GRANT INSERT(id,tenant_id,parcel_id,operator_id,recipient_name,evidence_kind) ON cp.parcel_handoffs TO cp_api;

-- Vínculo residencial vigente da própria conta com a unidade (proprietário não residente não vê as encomendas).
CREATE FUNCTION cp.resident_of_unit(target_tenant uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=platform.current_actor_id()
   AND m.status='ACTIVE' AND m.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT')
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) $$;
REVOKE ALL ON FUNCTION cp.resident_of_unit(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.resident_of_unit(uuid,uuid) TO cp_api;

CREATE POLICY parcels_read_scope ON cp.parcels AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.resident_of_unit(tenant_id,unit_id));
CREATE POLICY parcels_receive ON cp.parcels AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate') AND received_by=platform.current_actor_id() AND status='RECEIVED');
CREATE POLICY parcels_deliver ON cp.parcels AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('concierge.operate')) WITH CHECK(platform.has_capability('concierge.operate'));
CREATE POLICY handoffs_read_scope ON cp.parcel_handoffs AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR EXISTS(SELECT 1 FROM cp.parcels p
  WHERE p.tenant_id=cp.parcel_handoffs.tenant_id AND p.id=cp.parcel_handoffs.parcel_id AND cp.resident_of_unit(p.tenant_id,p.unit_id)));
CREATE POLICY handoffs_register ON cp.parcel_handoffs AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate') AND operator_id=platform.current_actor_id() AND evidence_kind='ASSISTED');

-- Única transição: RECEIVED -> DELIVERED, com a versão avançando uma unidade.
CREATE FUNCTION cp.parcel_transition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.status<>'RECEIVED' OR NEW.status<>'DELIVERED' OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'Transição de encomenda não permitida.' USING ERRCODE='23514';
 END IF;
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
