-- CP-039: prestador recorrente. A regra da unidade (dias da semana, horário local, período de até 180 dias) não autoriza
-- nada sozinha: ela gera janelas explícitas (convites com rule_id e window_date) num horizonte curto, calculadas no fuso
-- do condomínio pelo banco (nunca pelo aparelho). Fora da janela a portaria não tem convite em vigor. Revogar a regra
-- revoga as janelas ainda válidas na mesma transação.

CREATE TABLE cp.visit_rules (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,
 visitor_id uuid NOT NULL,
 purpose text CHECK(purpose IS NULL OR length(btrim(purpose)) BETWEEN 1 AND 200),
 week_days smallint[] NOT NULL CHECK(cardinality(week_days) BETWEEN 1 AND 7 AND week_days<@ARRAY[1,2,3,4,5,6,7]::smallint[]),
 starts_local time NOT NULL,
 ends_local time NOT NULL,
 valid_from date NOT NULL,
 valid_until date NOT NULL,
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','REVOKED')),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 revoked_by uuid REFERENCES platform.accounts(id),
 revoked_at timestamptz,
 revoke_reason text CHECK(revoke_reason IS NULL OR length(btrim(revoke_reason)) BETWEEN 1 AND 500),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,visitor_id) REFERENCES cp.visitors(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK(starts_local<ends_local),
 CHECK(valid_from<=valid_until AND valid_until<=valid_from+180),
 CHECK((state='REVOKED')=(revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);
-- A pessoa da regra é só dela (sem reaproveitar cadastro de outro convite).
CREATE UNIQUE INDEX visit_rules_one_per_visitor ON cp.visit_rules(tenant_id,visitor_id);
CREATE INDEX visit_rules_by_unit ON cp.visit_rules(tenant_id,unit_id,valid_until DESC,id);

-- Janelas da regra: vários convites para a mesma pessoa, um por dia; convite comum continua com pessoa própria.
ALTER TABLE cp.visit_permits ADD COLUMN rule_id uuid, ADD COLUMN window_date date,
 ADD CONSTRAINT visit_permits_rule_fk FOREIGN KEY(tenant_id,rule_id) REFERENCES cp.visit_rules(tenant_id,id),
 ADD CONSTRAINT visit_permits_rule_window CHECK((rule_id IS NULL)=(window_date IS NULL));
DROP INDEX cp.visit_permits_one_per_visitor;
CREATE UNIQUE INDEX visit_permits_one_per_visitor ON cp.visit_permits(tenant_id,visitor_id) WHERE rule_id IS NULL;
CREATE UNIQUE INDEX visit_permits_rule_day ON cp.visit_permits(tenant_id,rule_id,window_date) WHERE rule_id IS NOT NULL;
GRANT INSERT(rule_id,window_date) ON cp.visit_permits TO cp_api;

REVOKE ALL ON cp.visit_rules FROM PUBLIC;
GRANT SELECT ON cp.visit_rules TO cp_api;
GRANT INSERT(id,tenant_id,unit_id,visitor_id,purpose,week_days,starts_local,ends_local,valid_from,valid_until,created_by) ON cp.visit_rules TO cp_api;
GRANT UPDATE(state,version,revoked_by,revoke_reason) ON cp.visit_rules TO cp_api;
ALTER TABLE cp.visit_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.visit_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.visit_rules TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
-- Mesma visibilidade dos convites: a unidade (dependente só as que criou) e a portaria, que materializa as janelas.
CREATE POLICY visit_rules_read_scope ON cp.visit_rules AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.visit_member(tenant_id,unit_id,NULL)
  OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create')));
CREATE POLICY visit_rules_create ON cp.visit_rules AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id() AND state='ACTIVE' AND cp.visit_member(tenant_id,unit_id,'create')
  AND EXISTS(SELECT 1 FROM cp.visitors v WHERE v.tenant_id=cp.visit_rules.tenant_id AND v.id=cp.visit_rules.visitor_id
   AND v.created_by=platform.current_actor_id()));
CREATE POLICY visit_rules_revoke ON cp.visit_rules AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.visit_member(tenant_id,unit_id,NULL) OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create')))
 WITH CHECK(state='REVOKED' AND revoked_by=platform.current_actor_id());

-- Data de hoje no fuso do condomínio (do banco, nunca do aparelho).
CREATE FUNCTION cp.tenant_today(target_tenant uuid) RETURNS date
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT (clock_timestamp() AT TIME ZONE t.time_zone)::date FROM platform.tenants t WHERE t.id=target_tenant $$;
REVOKE ALL ON FUNCTION cp.tenant_today(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.tenant_today(uuid) TO cp_api;

-- Regra: horário do banco; começa hoje ou depois (no fuso do condomínio) e até 90 dias à frente; dias sem repetição.
-- Revogação: ACTIVE -> REVOKED uma vez, versão +1, horário do banco, e as janelas ainda válidas caem na mesma transação.
CREATE FUNCTION cp.visit_rule_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE today date;
BEGIN
 IF TG_OP='INSERT' THEN
  today:=cp.tenant_today(NEW.tenant_id);
  IF today IS NULL THEN RAISE EXCEPTION 'condomínio indisponível' USING ERRCODE='42501'; END IF;
  IF NEW.valid_from<today OR NEW.valid_from>today+90 THEN RAISE EXCEPTION 'início da regra fora do intervalo' USING ERRCODE='23514'; END IF;
  IF (SELECT count(DISTINCT d) FROM unnest(NEW.week_days) d)<>cardinality(NEW.week_days) THEN
   RAISE EXCEPTION 'dias repetidos' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp();
  RETURN NEW;
 END IF;
 -- Exclusiva contra a expansão (que segura a trava compartilhada enquanto grava janelas desta regra): janela gerada
 -- em paralelo termina antes e é revogada pelo gatilho de encerramento, ou a expansão espera e vê a regra revogada.
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.visit_rules:'||OLD.tenant_id::text||':'||OLD.id::text,0));
 IF OLD.state<>'ACTIVE' OR NEW.state<>'REVOKED' OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'transição de regra não permitida' USING ERRCODE='23514';
 END IF;
 NEW.revoked_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.visit_rule_guard() FROM PUBLIC;
CREATE TRIGGER visit_rules_guard BEFORE INSERT OR UPDATE ON cp.visit_rules FOR EACH ROW EXECUTE FUNCTION cp.visit_rule_guard();

CREATE FUNCTION cp.visit_rule_closed() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE window_permit record;
BEGIN
 -- Trava de cada janela antes das linhas, em ordem estável (mesma ordem da revogação individual e da entrada pela
 -- portaria: cp.lock_permit_access e depois a linha).
 FOR window_permit IN SELECT p.id FROM cp.visit_permits p WHERE p.tenant_id=NEW.tenant_id AND p.rule_id=NEW.id
   AND p.state='ACTIVE' AND p.valid_until>clock_timestamp() ORDER BY p.id LOOP
  PERFORM cp.lock_permit_access(NEW.tenant_id,window_permit.id);
 END LOOP;
 WITH revoked AS (
  UPDATE cp.visit_permits SET state='REVOKED',revoked_by=NEW.revoked_by,revoke_reason='Regra de prestador revogada',version=version+1
   WHERE tenant_id=NEW.tenant_id AND rule_id=NEW.id AND state='ACTIVE' AND valid_until>clock_timestamp()
   RETURNING id,unit_id,version)
 INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
  SELECT NEW.tenant_id,'VISIT_PERMIT_REVOKED',r.id,r.version,
   jsonb_build_object('id',r.id,'unitId',r.unit_id,'state','REVOKED','version',r.version,'ruleId',NEW.id)
  FROM revoked r;
 IF EXISTS(SELECT 1 FROM cp.visit_permits p WHERE p.tenant_id=NEW.tenant_id AND p.rule_id=NEW.id AND p.state='ACTIVE'
   AND p.valid_until>clock_timestamp()) THEN
  RAISE EXCEPTION 'janelas da regra não foram revogadas' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.visit_rule_closed() FROM PUBLIC;
CREATE TRIGGER visit_rules_close_windows AFTER UPDATE OF state ON cp.visit_rules FOR EACH ROW
 WHEN(OLD.state='ACTIVE' AND NEW.state='REVOKED') EXECUTE FUNCTION cp.visit_rule_closed();

-- Janela válida para a regra: regra ativa e visível, mesma pessoa, unidade e autoria, dia permitido dentro do período e
-- horários exatamente os da regra no fuso do condomínio.
CREATE FUNCTION cp.rule_window_valid(target_tenant uuid,target_rule uuid,target_visitor uuid,target_unit uuid,issuer uuid,
  day date,window_from timestamptz,window_until timestamptz) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.visit_rules r JOIN platform.tenants t ON t.id=r.tenant_id
  WHERE r.tenant_id=target_tenant AND r.id=target_rule AND r.state='ACTIVE' AND r.visitor_id=target_visitor
   AND r.unit_id=target_unit AND r.created_by=issuer AND day BETWEEN r.valid_from AND r.valid_until
   AND extract(isodow FROM day)::smallint=ANY(r.week_days)
   AND window_from=((day+r.starts_local) AT TIME ZONE t.time_zone) AND window_until=((day+r.ends_local) AT TIME ZONE t.time_zone)) $$;
REVOKE ALL ON FUNCTION cp.rule_window_valid(uuid,uuid,uuid,uuid,uuid,date,timestamptz,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rule_window_valid(uuid,uuid,uuid,uuid,uuid,date,timestamptz,timestamptz) TO cp_api;

-- Janela de regra pode ser materializada por quem enxerga a regra (unidade ou portaria): é determinística a partir dela
-- e fica em nome de quem criou a regra. Convite comum segue a política original.
DROP POLICY permits_create ON cp.visit_permits;
CREATE POLICY permits_create ON cp.visit_permits AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(state='ACTIVE' AND valid_until>clock_timestamp() AND (
  (rule_id IS NULL AND created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create')
   AND EXISTS(SELECT 1 FROM cp.visitors v WHERE v.tenant_id=cp.visit_permits.tenant_id AND v.id=cp.visit_permits.visitor_id
    AND v.created_by=platform.current_actor_id()))
  OR (rule_id IS NOT NULL AND cp.rule_window_valid(tenant_id,rule_id,visitor_id,unit_id,created_by,window_date,valid_from,valid_until))));

-- O início no passado é aceito só para janela de regra (o horário vem da regra, conferido acima); convite comum mantém a
-- tolerância de 10 minutos. Pessoa de regra não vira convite comum.
CREATE OR REPLACE FUNCTION cp.visit_insert_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 permit_unit uuid;
 permit_state text;
 permit_until timestamptz;
 permit_issuer uuid;
 permit_name text;
 permit_document text;
 arrival_name text;
 arrival_document text;
BEGIN
 IF TG_TABLE_NAME='visit_permits' THEN
  IF NEW.rule_id IS NULL THEN
   IF NEW.valid_from<clock_timestamp()-interval '10 minutes' OR NEW.valid_from>clock_timestamp()+interval '90 days' THEN
    RAISE EXCEPTION 'Início do convite fora do intervalo permitido.' USING ERRCODE='23514';
   END IF;
   IF EXISTS(SELECT 1 FROM cp.visit_rules r WHERE r.tenant_id=NEW.tenant_id AND r.visitor_id=NEW.visitor_id) THEN
    RAISE EXCEPTION 'Pessoa de regra recorrente.' USING ERRCODE='23514';
   END IF;
  END IF;
 ELSE
  NEW.expires_at := clock_timestamp()+interval '5 minutes';
  IF NEW.permit_id IS NOT NULL THEN
   SELECT p.unit_id,p.state,p.valid_until,p.created_by,v.full_name,v.document_last4
    INTO permit_unit,permit_state,permit_until,permit_issuer,permit_name,permit_document
    FROM cp.visit_permits p JOIN cp.visitors v ON v.tenant_id=p.tenant_id AND v.id=p.visitor_id
    WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.permit_id;
   IF permit_unit IS NULL THEN RAISE EXCEPTION 'Convite não visível.' USING ERRCODE='42501'; END IF;
   IF permit_unit<>NEW.unit_id THEN RAISE EXCEPTION 'Convite de outra unidade.' USING ERRCODE='23514'; END IF;
   IF permit_state<>'ACTIVE' OR permit_until<=clock_timestamp() OR NOT cp.permit_issuer_active(NEW.tenant_id,permit_unit,permit_issuer) THEN
    RAISE EXCEPTION 'Convite revogado, vencido ou de vínculo encerrado.' USING ERRCODE='23514';
   END IF;
   SELECT v.full_name,v.document_last4 INTO arrival_name,arrival_document
    FROM cp.visitors v WHERE v.tenant_id=NEW.tenant_id AND v.id=NEW.visitor_id;
   IF arrival_name IS DISTINCT FROM permit_name OR arrival_document IS DISTINCT FROM permit_document THEN
    RAISE EXCEPTION 'Pessoa diferente da do convite.' USING ERRCODE='23514';
   END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;

-- Materializa as janelas das regras ativas visíveis a quem chama, de hoje até o horizonte (no máximo 14 dias), no fuso do
-- condomínio. Idempotente (uma janela por regra e dia); janela já encerrada ou de quem não pode mais convidar não nasce.
CREATE FUNCTION cp.expand_visit_rules(target_tenant uuid,target_unit uuid,horizon_days integer) RETURNS integer
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE created integer:=0; added integer; today date; zone text;
BEGIN
 IF horizon_days IS NULL OR horizon_days<0 OR horizon_days>14 THEN RAISE EXCEPTION 'horizonte inválido' USING ERRCODE='22023'; END IF;
 SELECT t.time_zone INTO zone FROM platform.tenants t WHERE t.id=target_tenant;
 IF zone IS NULL THEN RETURN 0; END IF;
 today:=(clock_timestamp() AT TIME ZONE zone)::date;
 -- Trava compartilhada de cada regra ativa (ordem estável) antes de gerar janelas; a revogação toma a mesma trava em
 -- modo exclusivo. O INSERT abaixo é um comando novo: enxerga a revogação confirmada enquanto esperava a trava.
 PERFORM pg_advisory_xact_lock_shared(hashtextextended('cp.visit_rules:'||r.tenant_id::text||':'||r.id::text,0))
  FROM (SELECT r.tenant_id,r.id FROM cp.visit_rules r WHERE r.tenant_id=target_tenant
   AND (target_unit IS NULL OR r.unit_id=target_unit) AND r.state='ACTIVE' AND r.valid_until>=today ORDER BY r.id) r;
 INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until,rule_id,window_date)
  SELECT gen_random_uuid(),r.tenant_id,r.visitor_id,r.unit_id,r.purpose,r.created_by,
   (d::date+r.starts_local) AT TIME ZONE zone,(d::date+r.ends_local) AT TIME ZONE zone,r.id,d::date
  FROM cp.visit_rules r
  CROSS JOIN LATERAL generate_series(greatest(r.valid_from,today),least(r.valid_until,today+horizon_days),interval '1 day') d
  WHERE r.tenant_id=target_tenant AND (target_unit IS NULL OR r.unit_id=target_unit) AND r.state='ACTIVE'
   AND extract(isodow FROM d)::smallint=ANY(r.week_days)
   AND ((d::date+r.ends_local) AT TIME ZONE zone)>clock_timestamp()
   AND cp.permit_issuer_active(r.tenant_id,r.unit_id,r.created_by)
  ON CONFLICT (tenant_id,rule_id,window_date) WHERE rule_id IS NOT NULL DO NOTHING;
 GET DIAGNOSTICS added=ROW_COUNT;
 created:=created+added;
 RETURN created;
END $$;
REVOKE ALL ON FUNCTION cp.expand_visit_rules(uuid,uuid,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.expand_visit_rules(uuid,uuid,integer) TO cp_api;

-- Eventos de regra (VISIT_RULE_*) referem-se à própria regra; os demais VISIT_* continuam exigindo o convite.
DROP POLICY outbox_visit_events ON cp.outbox;
CREATE POLICY outbox_visit_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK((event_type NOT LIKE 'VISIT\_%' OR (event_type LIKE 'VISIT\_RULE\_%' AND EXISTS(SELECT 1 FROM cp.visit_rules v
   WHERE v.tenant_id=cp.outbox.tenant_id AND v.id=cp.outbox.aggregate_id))
   OR (event_type NOT LIKE 'VISIT\_RULE\_%' AND EXISTS(SELECT 1 FROM cp.visit_permits p
   WHERE p.tenant_id=cp.outbox.tenant_id AND p.id=cp.outbox.aggregate_id)))
  AND (event_type NOT LIKE 'ARRIVAL\_%' OR EXISTS(SELECT 1 FROM cp.arrival_requests r
   WHERE r.tenant_id=cp.outbox.tenant_id AND r.id=cp.outbox.aggregate_id)));

-- A pessoa da regra é visível a quem vê a regra (outros adultos da unidade), mesmo antes de existir janela.
DROP POLICY visitors_read_scope ON cp.visitors;
CREATE POLICY visitors_read_scope ON cp.visitors AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR created_by=platform.current_actor_id()
  OR EXISTS(SELECT 1 FROM cp.visit_permits p WHERE p.tenant_id=cp.visitors.tenant_id AND p.visitor_id=cp.visitors.id)
  OR EXISTS(SELECT 1 FROM cp.arrival_requests r WHERE r.tenant_id=cp.visitors.tenant_id AND r.visitor_id=cp.visitors.id)
  OR EXISTS(SELECT 1 FROM cp.visit_rules v WHERE v.tenant_id=cp.visitors.tenant_id AND v.visitor_id=cp.visitors.id));
