-- Registro manual de entrada e saída de visitantes (CP-028) e ações contextuais da fila da portaria (CP-027). A
-- portaria (capacidade concierge.operate, AAL2) registra a entrada somente de quem tem autorização vigente — chegada
-- autorizada nas últimas 24 horas (pela unidade no aplicativo ou por confirmação assistida) ou convite em vigor pelo
-- relógio do banco — e a saída de uma entrada registrada. Evento manual nunca é rotulado como passagem detectada por
-- equipamento: o tipo é MANUAL_IN/MANUAL_OUT e a origem é a portaria. Correção humana é um evento adicional que
-- referencia o anterior, com motivo; nada é alterado nem apagado. Equipamentos, eventos de sensor e saída automática
-- ficam para os tickets do parceiro (CP-043 a CP-048).

CREATE TABLE cp.access_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,
 visitor_id uuid NOT NULL,
 arrival_id uuid,
 permit_id uuid,
 event_type text NOT NULL CHECK(event_type IN ('MANUAL_IN','MANUAL_OUT','CORRECTION')),
 source text NOT NULL DEFAULT 'CONCIERGE' CHECK(source='CONCIERGE'),
 entry_event_id uuid,
 corrects_event_id uuid,
 evidence text NOT NULL CHECK(evidence IN ('DOCUMENT_CHECKED','RECOGNIZED_BY_UNIT','EXIT_OBSERVED','OPERATOR_CORRECTION')),
 note text CHECK(note IS NULL OR length(btrim(note)) BETWEEN 1 AND 300),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 recorded_by uuid NOT NULL REFERENCES platform.accounts(id),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,visitor_id) REFERENCES cp.visitors(tenant_id,id),
 FOREIGN KEY(tenant_id,arrival_id) REFERENCES cp.arrival_requests(tenant_id,id),
 FOREIGN KEY(tenant_id,permit_id) REFERENCES cp.visit_permits(tenant_id,id),
 FOREIGN KEY(tenant_id,entry_event_id) REFERENCES cp.access_events(tenant_id,id),
 FOREIGN KEY(tenant_id,corrects_event_id) REFERENCES cp.access_events(tenant_id,id),
 -- Entrada: exatamente uma autorização de base; saída: a entrada; correção: o evento corrigido e o motivo.
 CONSTRAINT access_events_entry_basis CHECK(event_type<>'MANUAL_IN'
  OR ((arrival_id IS NOT NULL)<>(permit_id IS NOT NULL) AND entry_event_id IS NULL AND corrects_event_id IS NULL
   AND evidence IN ('DOCUMENT_CHECKED','RECOGNIZED_BY_UNIT'))),
 CONSTRAINT access_events_exit_entry CHECK(event_type<>'MANUAL_OUT'
  OR (entry_event_id IS NOT NULL AND corrects_event_id IS NULL AND evidence='EXIT_OBSERVED')),
 CONSTRAINT access_events_correction_reason CHECK(event_type<>'CORRECTION'
  OR (corrects_event_id IS NOT NULL AND entry_event_id IS NULL AND note IS NOT NULL AND evidence='OPERATOR_CORRECTION')),
 CONSTRAINT access_events_server_times CHECK(received_at=occurred_at)
);
-- Uma correção por evento. "Uma entrada válida por chegada" e "uma saída válida por entrada" (sem contar registros
-- corrigidos) são conferidas no gatilho, em série pela trava consultiva da referência. Convite em vigor admite mais de
-- uma entrada no período (sair e voltar).
CREATE UNIQUE INDEX access_events_one_correction ON cp.access_events(tenant_id,corrects_event_id) WHERE event_type='CORRECTION';
CREATE INDEX access_events_by_arrival ON cp.access_events(tenant_id,arrival_id) WHERE arrival_id IS NOT NULL;
CREATE INDEX access_events_by_entry ON cp.access_events(tenant_id,entry_event_id) WHERE entry_event_id IS NOT NULL;
CREATE INDEX access_events_recent ON cp.access_events(tenant_id,received_at DESC,id);
CREATE INDEX access_events_by_unit ON cp.access_events(tenant_id,unit_id,received_at DESC,id);
CREATE INDEX access_events_by_permit ON cp.access_events(tenant_id,permit_id) WHERE permit_id IS NOT NULL;

-- Grants mínimos: a aplicação não escolhe unidade, pessoa, horários nem origem; nada é alterado ou apagado.
REVOKE ALL ON cp.access_events FROM PUBLIC;
GRANT SELECT ON cp.access_events TO cp_api;
GRANT INSERT(id,tenant_id,arrival_id,permit_id,event_type,entry_event_id,corrects_event_id,evidence,note,recorded_by)
 ON cp.access_events TO cp_api;
ALTER TABLE cp.access_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.access_events TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
-- A portaria vê o condomínio; a unidade vê os registros das próprias visitas (adulto, ou dependente com visits.decide,
-- o mesmo escopo das chegadas).
CREATE POLICY access_events_read_scope ON cp.access_events AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.visit_member(tenant_id,unit_id,'decide'));
CREATE POLICY access_events_record ON cp.access_events AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate') AND recorded_by=platform.current_actor_id() AND source='CONCIERGE');

-- Conferência antes da gravação (executa como quem grava; registro de referência invisível falha fechado). Unidade,
-- pessoa, horários e origem vêm do banco. Entrada: chegada autorizada nas últimas 24 horas e ainda sem entrada válida,
-- ou convite em vigor agora. Saída: de entrada sem correção e ainda sem saída válida. Correção: de entrada ou saída
-- (correção não se corrige). Travas consultivas pela chegada, pelo convite e pelo evento de referência deixam em série
-- os registros que dependem do mesmo estado (consultas do plpgsql enxergam o que foi confirmado antes da trava).
CREATE FUNCTION cp.access_event_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 base_unit uuid;
 base_visitor uuid;
 base_state text;
 base_from timestamptz;
 base_until timestamptz;
 target_type text;
 target uuid;
BEGIN
 NEW.occurred_at := clock_timestamp();
 NEW.received_at := NEW.occurred_at;
 NEW.source := 'CONCIERGE';
 IF NEW.event_type='MANUAL_IN' THEN
  IF NEW.arrival_id IS NOT NULL THEN
   SELECT r.unit_id,r.visitor_id,r.state,r.created_at INTO base_unit,base_visitor,base_state,base_from
    FROM cp.arrival_requests r WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.arrival_id;
   IF base_unit IS NULL THEN RAISE EXCEPTION 'Chegada não visível.' USING ERRCODE='42501'; END IF;
   IF base_state<>'APPROVED' OR base_from<=clock_timestamp()-interval '24 hours' THEN
    RAISE EXCEPTION 'Chegada sem autorização vigente.' USING ERRCODE='23514';
   END IF;
   PERFORM pg_advisory_xact_lock(hashtextextended('cp.arrival_requests:'||NEW.tenant_id::text||':'||NEW.arrival_id::text,0));
   IF EXISTS(SELECT 1 FROM cp.access_events x WHERE x.tenant_id=NEW.tenant_id AND x.arrival_id=NEW.arrival_id
     AND x.event_type='MANUAL_IN' AND NOT EXISTS(SELECT 1 FROM cp.access_events c
      WHERE c.tenant_id=x.tenant_id AND c.corrects_event_id=x.id AND c.event_type='CORRECTION')) THEN
    RAISE EXCEPTION 'Entrada da chegada já registrada.' USING ERRCODE='23505';
   END IF;
  ELSIF NEW.permit_id IS NOT NULL THEN
   -- Mesma trava da revogação: entrada e revogação do mesmo convite ficam em série.
   PERFORM pg_advisory_xact_lock(hashtextextended('cp.visit_permits:'||NEW.tenant_id::text||':'||NEW.permit_id::text,0));
   SELECT p.unit_id,p.visitor_id,p.state,p.valid_from,p.valid_until INTO base_unit,base_visitor,base_state,base_from,base_until
    FROM cp.visit_permits p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.permit_id;
   IF base_unit IS NULL THEN RAISE EXCEPTION 'Convite não visível.' USING ERRCODE='42501'; END IF;
   IF base_state<>'ACTIVE' OR base_from>clock_timestamp() OR base_until<=clock_timestamp() THEN
    RAISE EXCEPTION 'Convite fora do período ou revogado.' USING ERRCODE='23514';
   END IF;
  END IF;
 ELSE
  target := CASE WHEN NEW.event_type='MANUAL_OUT' THEN NEW.entry_event_id ELSE NEW.corrects_event_id END;
  IF target IS NOT NULL THEN
   PERFORM pg_advisory_xact_lock(hashtextextended('cp.access_events:'||NEW.tenant_id::text||':'||target::text,0));
   SELECT x.unit_id,x.visitor_id,x.event_type INTO base_unit,base_visitor,target_type
    FROM cp.access_events x WHERE x.tenant_id=NEW.tenant_id AND x.id=target;
   IF base_unit IS NULL THEN RAISE EXCEPTION 'Registro de acesso não visível.' USING ERRCODE='42501'; END IF;
   IF NEW.event_type='MANUAL_OUT' AND (target_type<>'MANUAL_IN' OR EXISTS(SELECT 1 FROM cp.access_events c
     WHERE c.tenant_id=NEW.tenant_id AND c.corrects_event_id=target AND c.event_type='CORRECTION')) THEN
    RAISE EXCEPTION 'Saída sem entrada válida.' USING ERRCODE='23514';
   END IF;
   IF NEW.event_type='MANUAL_OUT' AND EXISTS(SELECT 1 FROM cp.access_events x WHERE x.tenant_id=NEW.tenant_id
     AND x.entry_event_id=target AND x.event_type='MANUAL_OUT' AND NOT EXISTS(SELECT 1 FROM cp.access_events c
      WHERE c.tenant_id=x.tenant_id AND c.corrects_event_id=x.id AND c.event_type='CORRECTION')) THEN
    RAISE EXCEPTION 'Saída da entrada já registrada.' USING ERRCODE='23505';
   END IF;
   IF NEW.event_type='CORRECTION' AND target_type='CORRECTION' THEN
    RAISE EXCEPTION 'Correção não se corrige.' USING ERRCODE='23514';
   END IF;
  END IF;
 END IF;
 -- Sem referência os CHECKs recusam a linha; com ela, unidade e pessoa sempre vêm do registro de referência.
 NEW.unit_id := coalesce(base_unit,NEW.unit_id);
 NEW.visitor_id := coalesce(base_visitor,NEW.visitor_id);
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_event_guard() FROM PUBLIC;
CREATE TRIGGER access_events_guard BEFORE INSERT ON cp.access_events
 FOR EACH ROW EXECUTE FUNCTION cp.access_event_guard();

-- Revogação toma a mesma trava consultiva da entrada pelo convite: quem chegar depois vê o estado final do outro (a
-- entrada lê o convite sem trava de linha, que a portaria não tem).
CREATE FUNCTION cp.permit_access_lock() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.visit_permits:'||NEW.tenant_id::text||':'||NEW.id::text,0));
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.permit_access_lock() FROM PUBLIC;
CREATE TRIGGER visit_permits_access_lock BEFORE UPDATE ON cp.visit_permits
 FOR EACH ROW EXECUTE FUNCTION cp.permit_access_lock();

-- Eventos e trilha de acesso só para registro visível e em nome da própria conta.
CREATE POLICY outbox_access_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type NOT LIKE 'ACCESS\_%' OR EXISTS(SELECT 1 FROM cp.access_events x
  WHERE x.tenant_id=cp.outbox.tenant_id AND x.id=cp.outbox.aggregate_id));
CREATE POLICY audit_access_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'ACCESS\_%' OR actor_id=platform.current_actor_id());
