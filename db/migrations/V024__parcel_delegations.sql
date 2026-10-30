-- CP-041: retirada delegada com código. Quem pode retirar pela unidade autoriza um terceiro para uma encomenda ainda a
-- retirar: nome, final do documento (opcional) e validade curta. O código (8 dígitos) aparece uma única vez para quem
-- autorizou; o banco guarda só o HMAC-SHA256 calculado pela aplicação com segredo do servidor, fora do banco
-- (CP_PARCEL_CODE_KEY), sobre a delegação e o código: sem o segredo, a tabela não permite testar os códigos. A portaria
-- confere o código daquela encomenda (a
-- delegação de outra encomenda nunca vale), com até 5 tentativas: na quinta errada a delegação fica bloqueada. Código
-- usado não volta a valer; retirada, anulação ou nova delegação encerram a anterior.

CREATE TABLE cp.parcel_delegations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 parcel_id uuid NOT NULL,
 unit_id uuid NOT NULL,
 delegate_name text NOT NULL CHECK(length(btrim(delegate_name)) BETWEEN 2 AND 160),
 document_last4 text CHECK(document_last4 IS NULL OR document_last4 ~ '^[0-9A-Z]{2,4}$'),
 code_hmac text NOT NULL CHECK(code_hmac ~ '^[0-9a-f]{64}$'),
 valid_until timestamptz NOT NULL,
 -- ACTIVE: vale até valid_until; USED: retirada feita com o código; REVOKED: cancelada pela unidade; LOCKED: tentativas
 -- esgotadas; CLOSED: encomenda retirada por outro caminho, anulada ou substituída por nova delegação.
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','USED','REVOKED','LOCKED','CLOSED')),
 attempts smallint NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 used_by uuid REFERENCES platform.accounts(id),
 used_at timestamptz,
 revoked_by uuid REFERENCES platform.accounts(id),
 closed_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,parcel_id) REFERENCES cp.parcels(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK((state='USED')=(used_by IS NOT NULL AND used_at IS NOT NULL)),
 CHECK(state<>'REVOKED' OR revoked_by IS NOT NULL),
 CHECK((state='ACTIVE')=(closed_at IS NULL))
);
-- Uma delegação em vigor por encomenda.
CREATE UNIQUE INDEX parcel_delegations_active ON cp.parcel_delegations(tenant_id,parcel_id) WHERE state='ACTIVE';
CREATE INDEX parcel_delegations_by_parcel ON cp.parcel_delegations(tenant_id,parcel_id,created_at DESC);

REVOKE ALL ON cp.parcel_delegations FROM PUBLIC;
GRANT SELECT ON cp.parcel_delegations TO cp_api;
GRANT INSERT(id,tenant_id,parcel_id,unit_id,delegate_name,document_last4,code_hmac,valid_until,created_by)
 ON cp.parcel_delegations TO cp_api;
GRANT UPDATE(state,attempts,used_by,revoked_by,version) ON cp.parcel_delegations TO cp_api;
ALTER TABLE cp.parcel_delegations ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.parcel_delegations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.parcel_delegations TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Quem pode retirar pela unidade (vínculo vigente, conta e unidade ativas; dependente com parcels.pickup).
CREATE FUNCTION cp.parcel_pickup_member(target_tenant uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=platform.current_actor_id()
   AND m.status='ACTIVE' AND cp.parcel_pickup_role(m.role,m.permissions)
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) $$;
REVOKE ALL ON FUNCTION cp.parcel_pickup_member(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.parcel_pickup_member(uuid,uuid) TO cp_api;

-- A unidade (quem vê as encomendas) acompanha as delegações; a portaria confere. A criação e a revogação são de quem
-- pode retirar; a portaria só registra tentativa, uso, bloqueio e encerramento.
CREATE POLICY parcel_delegations_read ON cp.parcel_delegations AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.resident_of_unit(tenant_id,unit_id));
CREATE POLICY parcel_delegations_create ON cp.parcel_delegations AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id() AND state='ACTIVE' AND attempts=0 AND cp.parcel_pickup_member(tenant_id,unit_id)
  AND EXISTS(SELECT 1 FROM cp.parcels p WHERE p.tenant_id=cp.parcel_delegations.tenant_id AND p.id=cp.parcel_delegations.parcel_id
   AND p.unit_id=cp.parcel_delegations.unit_id AND p.status='RECEIVED'));
CREATE POLICY parcel_delegations_change ON cp.parcel_delegations AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.parcel_pickup_member(tenant_id,unit_id))
 WITH CHECK((platform.has_capability('concierge.operate') AND state IN ('ACTIVE','USED','LOCKED','CLOSED')
   AND (state<>'USED' OR used_by=platform.current_actor_id()))
  OR (cp.parcel_pickup_member(tenant_id,unit_id) AND (state IN ('CLOSED','ACTIVE') OR (state='REVOKED' AND revoked_by=platform.current_actor_id()))));

-- Validade de até 7 dias; horários do banco. Transições: ACTIVE→USED (em vigor, sem bloqueio), ACTIVE→REVOKED,
-- ACTIVE→LOCKED (na quinta tentativa errada), ACTIVE→CLOSED; tentativas só sobem de um em um enquanto ACTIVE.
CREATE FUNCTION cp.parcel_delegation_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  NEW.created_at:=clock_timestamp();
  IF NEW.valid_until<=NEW.created_at OR NEW.valid_until>NEW.created_at+interval '7 days' THEN
   RAISE EXCEPTION 'validade da delegação fora do intervalo' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF NEW.version<>OLD.version+1 OR OLD.state<>'ACTIVE' OR (NEW.tenant_id,NEW.parcel_id,NEW.unit_id,NEW.delegate_name,NEW.document_last4,
   NEW.code_hmac,NEW.valid_until,NEW.created_by) IS DISTINCT FROM (OLD.tenant_id,OLD.parcel_id,OLD.unit_id,
   OLD.delegate_name,OLD.document_last4,OLD.code_hmac,OLD.valid_until,OLD.created_by) THEN
  RAISE EXCEPTION 'alteração de delegação não permitida' USING ERRCODE='23514';
 END IF;
 IF NEW.state='ACTIVE' THEN
  IF NEW.attempts<>OLD.attempts+1 OR NEW.attempts>=5 THEN RAISE EXCEPTION 'tentativa inválida' USING ERRCODE='23514'; END IF;
 ELSIF NEW.state='LOCKED' THEN
  IF NEW.attempts<>5 OR OLD.attempts<>4 THEN RAISE EXCEPTION 'bloqueio só na quinta tentativa' USING ERRCODE='23514'; END IF;
 ELSIF NEW.state='USED' THEN
  IF NEW.attempts<>OLD.attempts OR OLD.valid_until<=clock_timestamp() THEN
   RAISE EXCEPTION 'delegação vencida ou alterada' USING ERRCODE='23514';
  END IF;
  NEW.used_at:=clock_timestamp();
 ELSIF NEW.attempts<>OLD.attempts THEN
  RAISE EXCEPTION 'tentativas não mudam no encerramento' USING ERRCODE='23514';
 END IF;
 IF NEW.state<>'ACTIVE' THEN NEW.closed_at:=clock_timestamp(); END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.parcel_delegation_guard() FROM PUBLIC;
CREATE TRIGGER parcel_delegations_guard BEFORE INSERT OR UPDATE ON cp.parcel_delegations
 FOR EACH ROW EXECUTE FUNCTION cp.parcel_delegation_guard();

-- Retirada ou anulação da encomenda encerram a delegação em vigor na mesma transação (o código deixa de valer).
CREATE FUNCTION cp.parcel_delegations_close() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 UPDATE cp.parcel_delegations SET state='CLOSED',version=version+1
  WHERE tenant_id=NEW.tenant_id AND parcel_id=NEW.id AND state='ACTIVE';
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.parcel_delegations_close() FROM PUBLIC;
CREATE TRIGGER parcels_close_delegations AFTER UPDATE OF status ON cp.parcels FOR EACH ROW
 WHEN(OLD.status='RECEIVED' AND NEW.status<>'RECEIVED') EXECUTE FUNCTION cp.parcel_delegations_close();

-- Retirada por delegação: a portaria registra em nome próprio, com o nome da pessoa autorizada e a delegação usada por
-- ela nesta encomenda. A retirada assistida continua como antes (V012).
DROP POLICY handoffs_register ON cp.parcel_handoffs;
CREATE POLICY handoffs_register ON cp.parcel_handoffs AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate') AND operator_id=platform.current_actor_id() AND (
  (evidence_kind='ASSISTED' AND EXISTS(SELECT 1 FROM cp.parcels p
   JOIN cp.units u ON u.tenant_id=p.tenant_id AND u.id=p.unit_id AND u.status='ACTIVE'
   JOIN cp.memberships m ON m.tenant_id=p.tenant_id AND m.unit_id=p.unit_id
   JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
   WHERE p.tenant_id=cp.parcel_handoffs.tenant_id AND p.id=cp.parcel_handoffs.parcel_id
    AND m.id=cp.parcel_handoffs.recipient_membership_id AND m.status='ACTIVE' AND cp.parcel_pickup_role(m.role,m.permissions)
    AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
    AND m.display_name=cp.parcel_handoffs.recipient_name))
  OR (evidence_kind='DELEGATION' AND recipient_membership_id IS NULL AND EXISTS(SELECT 1 FROM cp.parcel_delegations d
   WHERE d.tenant_id=cp.parcel_handoffs.tenant_id AND d.parcel_id=cp.parcel_handoffs.parcel_id AND d.state='USED'
    AND d.used_by=platform.current_actor_id() AND d.delegate_name=cp.parcel_handoffs.recipient_name))));

-- Conferência do código pela portaria, sob a trava da linha da delegação. A aplicação calcula o autenticador do código
-- informado com o segredo do servidor sobre a delegação em vigor que leu; o banco só compara. Devolve OK (delegação
-- passa a USED), WRONG (tentativa contada), LOCKED (quinta errada: bloqueada), EXPIRED, NONE (sem delegação em vigor para
-- esta encomenda) ou CHANGED (a delegação em vigor não é a lida: nada é contado). O código nunca chega ao banco.
CREATE FUNCTION cp.parcel_delegation_check(target_tenant uuid,target_parcel uuid,target_delegation uuid,candidate text) RETURNS text
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE d record;
BEGIN
 IF NOT platform.has_capability('concierge.operate') THEN RAISE EXCEPTION 'somente a portaria confere' USING ERRCODE='42501'; END IF;
 SELECT * INTO d FROM cp.parcel_delegations WHERE tenant_id=target_tenant AND parcel_id=target_parcel AND state='ACTIVE' FOR UPDATE;
 IF NOT FOUND THEN RETURN 'NONE'; END IF;
 IF d.valid_until<=clock_timestamp() THEN RETURN 'EXPIRED'; END IF;
 IF d.id IS DISTINCT FROM target_delegation THEN RETURN 'CHANGED'; END IF;
 IF candidate IS NOT NULL AND candidate ~ '^[0-9a-f]{64}$' AND candidate=d.code_hmac THEN
  UPDATE cp.parcel_delegations SET state='USED',used_by=platform.current_actor_id(),version=version+1 WHERE id=d.id;
  RETURN 'OK';
 END IF;
 IF d.attempts>=4 THEN
  UPDATE cp.parcel_delegations SET state='LOCKED',attempts=5,version=version+1 WHERE id=d.id;
  RETURN 'LOCKED';
 END IF;
 UPDATE cp.parcel_delegations SET attempts=attempts+1,version=version+1 WHERE id=d.id;
 RETURN 'WRONG';
END $$;
REVOKE ALL ON FUNCTION cp.parcel_delegation_check(uuid,uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.parcel_delegation_check(uuid,uuid,uuid,text) TO cp_api;

-- Eventos de delegação (DELEGATION_*) só sobre delegação visível a quem grava; os eventos PARCEL_* continuam da portaria.
CREATE POLICY outbox_delegation_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type NOT LIKE 'DELEGATION\_%' OR EXISTS(SELECT 1 FROM cp.parcel_delegations d
  WHERE d.tenant_id=cp.outbox.tenant_id AND d.id=cp.outbox.aggregate_id));
