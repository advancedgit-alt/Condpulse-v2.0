-- CP-042: mudança e entrega de móveis. A gestão (requests.manage, AAL2) configura as regras do condomínio (texto exibido
-- à unidade, dias e horário local permitidos, antecedência mínima e duração máxima) e decide cada pedido. A unidade
-- (adulto com vínculo residencial) pede um período com os dados do prestador, aceitando a versão vigente das regras. A
-- portaria vê só os pedidos aprovados. A aprovação libera o período combinado: não reserva elevador nem área (nada disso
-- existe no sistema) e não autoriza obra.

CREATE TABLE cp.move_rules (
 tenant_id uuid PRIMARY KEY REFERENCES platform.tenants(id),
 rules_text text NOT NULL CHECK(length(btrim(rules_text)) BETWEEN 1 AND 2000),
 week_days smallint[] NOT NULL CHECK(cardinality(week_days) BETWEEN 1 AND 7 AND week_days<@ARRAY[1,2,3,4,5,6,7]::smallint[]),
 starts_local time NOT NULL,
 ends_local time NOT NULL,
 min_notice_hours integer NOT NULL CHECK(min_notice_hours BETWEEN 0 AND 720),
 max_duration_minutes integer NOT NULL CHECK(max_duration_minutes BETWEEN 30 AND 720),
 version integer NOT NULL DEFAULT 1 CHECK(version>=1),
 updated_by uuid NOT NULL REFERENCES platform.accounts(id),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(starts_local<ends_local)
);
REVOKE ALL ON cp.move_rules FROM PUBLIC;
GRANT SELECT ON cp.move_rules TO cp_api;
GRANT INSERT(tenant_id,rules_text,week_days,starts_local,ends_local,min_notice_hours,max_duration_minutes,updated_by) ON cp.move_rules TO cp_api;
GRANT UPDATE(rules_text,week_days,starts_local,ends_local,min_notice_hours,max_duration_minutes,version,updated_by) ON cp.move_rules TO cp_api;
ALTER TABLE cp.move_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.move_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.move_rules TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY move_rules_manage_insert ON cp.move_rules AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('requests.manage') AND updated_by=platform.current_actor_id() AND version=1);
CREATE POLICY move_rules_manage_update ON cp.move_rules AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('requests.manage'))
 WITH CHECK(platform.has_capability('requests.manage') AND updated_by=platform.current_actor_id());

CREATE FUNCTION cp.move_rules_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF (SELECT count(DISTINCT d) FROM unnest(NEW.week_days) d)<>cardinality(NEW.week_days) THEN
  RAISE EXCEPTION 'dias repetidos' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'versão das regras' USING ERRCODE='23514'; END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.move_rules_guard() FROM PUBLIC;
CREATE TRIGGER move_rules_guard BEFORE INSERT OR UPDATE ON cp.move_rules FOR EACH ROW EXECUTE FUNCTION cp.move_rules_guard();

CREATE TABLE cp.move_requests (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,
 requester_id uuid NOT NULL REFERENCES platform.accounts(id),
 kind text NOT NULL CHECK(kind IN ('MOVE_IN','MOVE_OUT','FURNITURE_DELIVERY')),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 provider_name text NOT NULL CHECK(length(btrim(provider_name)) BETWEEN 2 AND 160),
 vehicle_plate text CHECK(vehicle_plate IS NULL OR vehicle_plate ~ '^[A-Z0-9]{5,8}$'),
 items text CHECK(items IS NULL OR length(btrim(items)) BETWEEN 1 AND 500),
 rules_version integer NOT NULL CHECK(rules_version>=1),
 -- REQUESTED: aguarda a gestão; APPROVED/REJECTED: decisão com nota; CANCELLED: retirado pela unidade (antes do início)
 -- ou cancelado pela gestão com nota.
 state text NOT NULL DEFAULT 'REQUESTED' CHECK(state IN ('REQUESTED','APPROVED','REJECTED','CANCELLED')),
 decided_by uuid REFERENCES platform.accounts(id),
 decided_at timestamptz,
 decision_note text CHECK(decision_note IS NULL OR length(btrim(decision_note)) BETWEEN 1 AND 500),
 cancelled_by uuid REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK(isfinite(starts_at) AND isfinite(ends_at) AND starts_at<ends_at),
 CHECK(state<>'REJECTED' OR (decided_by IS NOT NULL AND decision_note IS NOT NULL)),
 CHECK(state NOT IN ('APPROVED','REJECTED') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
 CHECK((state='CANCELLED')=(cancelled_by IS NOT NULL))
);
CREATE INDEX move_requests_by_unit ON cp.move_requests(tenant_id,unit_id,starts_at DESC,id);
CREATE INDEX move_requests_by_state ON cp.move_requests(tenant_id,state,starts_at,id);

REVOKE ALL ON cp.move_requests FROM PUBLIC;
GRANT SELECT ON cp.move_requests TO cp_api;
GRANT INSERT(id,tenant_id,unit_id,requester_id,kind,starts_at,ends_at,provider_name,vehicle_plate,items,rules_version) ON cp.move_requests TO cp_api;
GRANT UPDATE(state,decided_by,decision_note,cancelled_by,version) ON cp.move_requests TO cp_api;
ALTER TABLE cp.move_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.move_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.move_requests TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Adulto com vínculo residencial vigente na unidade (conta e unidade ativas).
CREATE FUNCTION cp.move_member(target_tenant uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
   AND m.role IN ('RESIDENT','RESPONSIBLE') AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) $$;
REVOKE ALL ON FUNCTION cp.move_member(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.move_member(uuid,uuid) TO cp_api;

-- A unidade vê os seus; a gestão vê todos; a portaria vê só os aprovados.
CREATE POLICY move_requests_read ON cp.move_requests AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('requests.manage') OR cp.move_member(tenant_id,unit_id)
  OR (platform.has_capability('concierge.operate') AND state='APPROVED'));
CREATE POLICY move_requests_create ON cp.move_requests AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(requester_id=platform.current_actor_id() AND state='REQUESTED' AND cp.move_member(tenant_id,unit_id));
-- Gestão decide e cancela; a unidade só retira (CANCELLED em nome próprio).
CREATE POLICY move_requests_change ON cp.move_requests AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('requests.manage') OR cp.move_member(tenant_id,unit_id))
 WITH CHECK((platform.has_capability('requests.manage') AND ((state='CANCELLED' AND cancelled_by=platform.current_actor_id())
   OR (state IN ('APPROVED','REJECTED') AND decided_by=platform.current_actor_id())))
  OR (cp.move_member(tenant_id,unit_id) AND state='CANCELLED' AND cancelled_by=platform.current_actor_id()));

-- Pedido dentro das regras vigentes (mesma versão aceita), no fuso do condomínio: dia permitido, início e fim no mesmo
-- dia local e dentro do horário, antecedência mínima e duração máxima. Transições: REQUESTED→APPROVED/REJECTED/
-- CANCELLED, APPROVED→CANCELLED (antes do fim); unidade só retira antes do início.
CREATE FUNCTION cp.move_requests_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE r record; zone text; local_start timestamp; local_end timestamp;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT * INTO r FROM cp.move_rules WHERE tenant_id=NEW.tenant_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'regras de mudança não configuradas' USING ERRCODE='23514'; END IF;
  IF r.version<>NEW.rules_version THEN RAISE EXCEPTION 'regras de mudança alteradas' USING ERRCODE='23514'; END IF;
  SELECT t.time_zone INTO zone FROM platform.tenants t WHERE t.id=NEW.tenant_id;
  local_start:=NEW.starts_at AT TIME ZONE zone; local_end:=NEW.ends_at AT TIME ZONE zone;
  IF local_start::date<>local_end::date OR NOT (extract(isodow FROM local_start)::smallint=ANY(r.week_days))
    OR local_start::time<r.starts_local OR local_end::time>r.ends_local
    OR NEW.starts_at<clock_timestamp()+make_interval(hours=>r.min_notice_hours)
    OR NEW.ends_at-NEW.starts_at>make_interval(mins=>r.max_duration_minutes)
    OR NEW.starts_at>clock_timestamp()+interval '180 days' THEN
   RAISE EXCEPTION 'período fora das regras de mudança' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF NEW.version<>OLD.version+1 OR (NEW.tenant_id,NEW.unit_id,NEW.requester_id,NEW.kind,NEW.starts_at,NEW.ends_at,NEW.provider_name,
   NEW.vehicle_plate,NEW.items,NEW.rules_version) IS DISTINCT FROM (OLD.tenant_id,OLD.unit_id,OLD.requester_id,OLD.kind,OLD.starts_at,
   OLD.ends_at,OLD.provider_name,OLD.vehicle_plate,OLD.items,OLD.rules_version) THEN
  RAISE EXCEPTION 'alteração de pedido de mudança não permitida' USING ERRCODE='23514';
 END IF;
 IF NOT ((OLD.state='REQUESTED' AND NEW.state IN ('APPROVED','REJECTED','CANCELLED'))
   OR (OLD.state='APPROVED' AND NEW.state='CANCELLED')) THEN
  RAISE EXCEPTION 'transição de mudança não permitida' USING ERRCODE='23514';
 END IF;
 IF NEW.state='CANCELLED' AND OLD.ends_at<=clock_timestamp() THEN RAISE EXCEPTION 'período já encerrado' USING ERRCODE='23514'; END IF;
 IF NEW.state='CANCELLED' AND NOT platform.has_capability('requests.manage') AND OLD.starts_at<=clock_timestamp() THEN
  RAISE EXCEPTION 'retirada só antes do início' USING ERRCODE='23514';
 END IF;
 IF NEW.state IN ('APPROVED','REJECTED') THEN
  IF OLD.starts_at<=clock_timestamp() THEN RAISE EXCEPTION 'decisão depois do início' USING ERRCODE='23514'; END IF;
  NEW.decided_at:=clock_timestamp();
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.move_requests_guard() FROM PUBLIC;
CREATE TRIGGER move_requests_guard BEFORE INSERT OR UPDATE ON cp.move_requests FOR EACH ROW EXECUTE FUNCTION cp.move_requests_guard();

-- Eventos de mudança só sobre pedido visível a quem grava (portaria é avisada pelos aprovados).
CREATE POLICY outbox_move_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type NOT LIKE 'MOVE\_%' OR EXISTS(SELECT 1 FROM cp.move_requests m
  WHERE m.tenant_id=cp.outbox.tenant_id AND m.id=cp.outbox.aggregate_id));
