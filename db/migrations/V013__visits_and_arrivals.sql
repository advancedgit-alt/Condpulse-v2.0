-- Visitas (CP-025) e chegada sem convite (CP-026). Convite é autorização temporal da unidade, não prova de identidade
-- nem de entrada: a portaria segue conferindo a pessoa. Morador e responsável da unidade convidam, acompanham e
-- decidem; dependente só com permissão explícita (permissions.visits.create / permissions.visits.decide). A portaria
-- (capacidade concierge.operate, AAL2) registra a chegada sem convite, que espera a primeira decisão válida até o
-- prazo; sem resposta não existe autorização automática. Convite compartilhável, recorrência e janelas (CP-037/CP-039)
-- e registro de entrada/saída (CP-028) ficam para tickets próprios.

-- Identidade mínima do visitante no condomínio: nome e, quando necessário, só o final do documento.
CREATE TABLE cp.visitors (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 full_name text NOT NULL CHECK(length(btrim(full_name)) BETWEEN 2 AND 160),
 document_last4 text CHECK(document_last4 IS NULL OR document_last4 ~ '^[0-9A-Za-z]{2,4}$'),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id)
);

-- Convite (visita prevista) de uma unidade, com vigência curta e obrigatória. EXPIRED é calculado na leitura.
CREATE TABLE cp.visit_permits (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 visitor_id uuid NOT NULL,
 unit_id uuid NOT NULL,
 purpose text CHECK(purpose IS NULL OR length(btrim(purpose)) BETWEEN 1 AND 200),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','REVOKED')),
 valid_from timestamptz NOT NULL,
 valid_until timestamptz NOT NULL,
 revoked_by uuid REFERENCES platform.accounts(id),
 revoked_at timestamptz,
 revoke_reason text CHECK(revoke_reason IS NULL OR length(btrim(revoke_reason)) BETWEEN 1 AND 500),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,visitor_id) REFERENCES cp.visitors(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK(isfinite(valid_from) AND isfinite(valid_until) AND valid_from<valid_until AND valid_until<=valid_from+interval '7 days'),
 CHECK((state='REVOKED')=(revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);
CREATE INDEX visit_permits_by_unit ON cp.visit_permits(tenant_id,unit_id,valid_until DESC,id);
CREATE INDEX visit_permits_current ON cp.visit_permits(tenant_id,valid_from,valid_until) WHERE state='ACTIVE';
-- Cada pessoa registrada pertence a um único convite (sem cadastro reaproveitável); o índice também atende a política
-- de leitura de visitantes, que procura o convite pela pessoa.
CREATE UNIQUE INDEX visit_permits_one_per_visitor ON cp.visit_permits(tenant_id,visitor_id);

-- Chegada sem convite: a tabela da fundação ganha a pessoa, o convite opcional, quem registrou e a origem da
-- decisão. O prazo vem do relógio do banco (5 minutos); CANCELLED encerra uma chegada registrada por engano ou
-- de quem foi embora.
ALTER TABLE cp.arrival_requests ADD COLUMN visitor_id uuid,
 ADD COLUMN permit_id uuid,
 ADD COLUMN created_by uuid REFERENCES platform.accounts(id),
 ADD COLUMN decision_source text CHECK(decision_source IS NULL OR decision_source IN ('APP','INTERCOM','IN_PERSON','CONCIERGE')),
 ADD COLUMN decision_note text CHECK(decision_note IS NULL OR length(btrim(decision_note)) BETWEEN 1 AND 300),
 ADD CONSTRAINT arrival_requests_visitor_fk FOREIGN KEY(tenant_id,visitor_id) REFERENCES cp.visitors(tenant_id,id),
 ADD CONSTRAINT arrival_requests_permit_fk FOREIGN KEY(tenant_id,permit_id) REFERENCES cp.visit_permits(tenant_id,id);
ALTER TABLE cp.arrival_requests ALTER COLUMN visitor_id SET NOT NULL, ALTER COLUMN created_by SET NOT NULL,
 ALTER COLUMN expires_at SET DEFAULT clock_timestamp()+interval '5 minutes';
ALTER TABLE cp.arrival_requests DROP CONSTRAINT arrival_requests_state_check, DROP CONSTRAINT arrival_requests_check1;
ALTER TABLE cp.arrival_requests ADD CONSTRAINT arrival_requests_state_check
  CHECK(state IN ('WAITING','APPROVED','DENIED','EXPIRED','CANCELLED')),
 ADD CONSTRAINT arrival_requests_decision_fields CHECK(
  (state IN ('APPROVED','DENIED','CANCELLED'))=(decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_source IS NOT NULL)),
 ADD CONSTRAINT arrival_requests_decision_source CHECK(
  state NOT IN ('APPROVED','DENIED') OR decision_source IN ('APP','INTERCOM','IN_PERSON')),
 ADD CONSTRAINT arrival_requests_cancel_source CHECK(state<>'CANCELLED' OR decision_source='CONCIERGE'),
 ADD CONSTRAINT arrival_requests_assisted_note CHECK(decision_source IS NULL OR decision_source IN ('APP','CONCIERGE') OR decision_note IS NOT NULL);
CREATE INDEX arrival_requests_queue ON cp.arrival_requests(tenant_id,created_at DESC,id);
CREATE INDEX arrival_requests_waiting ON cp.arrival_requests(tenant_id,unit_id,expires_at) WHERE state='WAITING';
CREATE INDEX arrival_requests_by_unit ON cp.arrival_requests(tenant_id,unit_id,created_at DESC,id);
-- Cada pessoa registrada pertence a uma única chegada (também atende a política de leitura de visitantes).
CREATE UNIQUE INDEX arrival_requests_one_per_visitor ON cp.arrival_requests(tenant_id,visitor_id);

-- Grants mínimos: a aplicação não escolhe estado inicial, prazo, datas nem responsável de outra conta.
REVOKE ALL ON cp.visitors,cp.visit_permits FROM PUBLIC;
GRANT SELECT ON cp.visitors,cp.visit_permits TO cp_api;
GRANT INSERT(id,tenant_id,full_name,document_last4,created_by) ON cp.visitors TO cp_api;
GRANT INSERT(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until) ON cp.visit_permits TO cp_api;
GRANT UPDATE(state,version,revoked_by,revoke_reason) ON cp.visit_permits TO cp_api;
REVOKE INSERT,UPDATE ON cp.arrival_requests FROM cp_api;
GRANT INSERT(id,tenant_id,unit_id,visitor_id,permit_id,created_by) ON cp.arrival_requests TO cp_api;
GRANT UPDATE(state,version,decided_by,decision_source,decision_note) ON cp.arrival_requests TO cp_api;
ALTER TABLE cp.visitors ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.visitors FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.visit_permits ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.visit_permits FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.visitors TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.visit_permits TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Vínculo vigente da própria conta ativa com a unidade ativa: adulto da unidade (morador ou responsável) ou
-- dependente com a permissão de visitas indicada. Igual ao contexto montado pelo Java.
CREATE FUNCTION cp.visit_member(target_tenant uuid,target_unit uuid,dependent_permission text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   AND (m.role IN ('RESIDENT','RESPONSIBLE') OR (dependent_permission IS NOT NULL AND m.role='DEPENDENT'
    AND coalesce(m.permissions#>ARRAY['visits',dependent_permission]='true'::jsonb,false)))) $$;
REVOKE ALL ON FUNCTION cp.visit_member(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.visit_member(uuid,uuid,text) TO cp_api;

-- Quem convidou ainda pode convidar pela unidade ativa (conta ativa, vínculo vigente, adulto ou dependente com
-- visits.create). Convite de vínculo encerrado, conta suspensa ou unidade inativa não vale para a portaria: some das
-- visitas previstas e não vincula chegada. Executa como quem consulta: a portaria enxerga os vínculos residenciais
-- vigentes do condomínio (V012); para quem não enxerga o vínculo, o resultado é falso (falha fechada).
CREATE FUNCTION cp.permit_issuer_active(target_tenant uuid,target_unit uuid,issuer uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=issuer AND m.status='ACTIVE'
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   AND (m.role IN ('RESIDENT','RESPONSIBLE') OR (m.role='DEPENDENT'
    AND coalesce(m.permissions#>ARRAY['visits','create']='true'::jsonb,false)))) $$;
REVOKE ALL ON FUNCTION cp.permit_issuer_active(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.permit_issuer_active(uuid,uuid,uuid) TO cp_api;

-- Visitante: visível para a portaria e para quem acompanha um convite ou chegada da unidade que o cita; criado em
-- nome da própria conta pela portaria (chegada) ou por quem pode convidar.
CREATE POLICY visitors_read_scope ON cp.visitors AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR created_by=platform.current_actor_id()
  OR EXISTS(SELECT 1 FROM cp.visit_permits p WHERE p.tenant_id=cp.visitors.tenant_id AND p.visitor_id=cp.visitors.id)
  OR EXISTS(SELECT 1 FROM cp.arrival_requests r WHERE r.tenant_id=cp.visitors.tenant_id AND r.visitor_id=cp.visitors.id));
CREATE POLICY visitors_insert_scope ON cp.visitors AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id());

-- Convites: a unidade vê os seus (dependente só os que criou); a portaria vê todos para conferir quem chega.
CREATE POLICY permits_read_scope ON cp.visit_permits AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.visit_member(tenant_id,unit_id,NULL)
  OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create')));
-- A pessoa do convite foi registrada pela própria conta (a FK não passa pela política de leitura).
CREATE POLICY permits_create ON cp.visit_permits AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id() AND state='ACTIVE' AND valid_until>clock_timestamp()
  AND cp.visit_member(tenant_id,unit_id,'create')
  AND EXISTS(SELECT 1 FROM cp.visitors v WHERE v.tenant_id=cp.visit_permits.tenant_id AND v.id=cp.visit_permits.visitor_id
   AND v.created_by=platform.current_actor_id()));
CREATE POLICY permits_revoke ON cp.visit_permits AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.visit_member(tenant_id,unit_id,NULL) OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create')))
 WITH CHECK(state='REVOKED' AND revoked_by=platform.current_actor_id());

-- Chegadas: a portaria registra e acompanha; a unidade (e o dependente com decide) vê e responde as suas.
CREATE POLICY arrivals_read_scope ON cp.arrival_requests AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.visit_member(tenant_id,unit_id,'decide'));
CREATE POLICY arrivals_register ON cp.arrival_requests AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('concierge.operate') AND created_by=platform.current_actor_id() AND state='WAITING'
  AND EXISTS(SELECT 1 FROM cp.units u WHERE u.tenant_id=cp.arrival_requests.tenant_id AND u.id=cp.arrival_requests.unit_id AND u.status='ACTIVE')
  AND EXISTS(SELECT 1 FROM cp.visitors v WHERE v.tenant_id=cp.arrival_requests.tenant_id AND v.id=cp.arrival_requests.visitor_id
   AND v.created_by=platform.current_actor_id()));
-- Decisão pelo aplicativo é da unidade; a portaria registra só a confirmação assistida (interfone/presencial) ou o
-- cancelamento, sempre em nome da própria conta.
CREATE POLICY arrivals_decide ON cp.arrival_requests AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.visit_member(tenant_id,unit_id,'decide'))
 WITH CHECK(decided_by=platform.current_actor_id() AND (
  (decision_source='APP' AND cp.visit_member(tenant_id,unit_id,'decide'))
  OR (decision_source IN ('INTERCOM','IN_PERSON','CONCIERGE') AND platform.has_capability('concierge.operate'))));

-- Inclusões conferidas antes da gravação (executa como quem grava; registro de referência invisível falha fechado):
-- convite com início recente e no máximo 90 dias à frente; chegada com prazo do banco e, quando vinculada a convite,
-- convite da mesma unidade, não revogado, ainda em vigor ou agendado, de quem ainda pode convidar, e a mesma pessoa
-- (nome e final do documento) do convite.
CREATE FUNCTION cp.visit_insert_guard() RETURNS trigger
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
  IF NEW.valid_from<clock_timestamp()-interval '10 minutes' OR NEW.valid_from>clock_timestamp()+interval '90 days' THEN
   RAISE EXCEPTION 'Início do convite fora do intervalo permitido.' USING ERRCODE='23514';
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
REVOKE ALL ON FUNCTION cp.visit_insert_guard() FROM PUBLIC;
CREATE TRIGGER visit_permits_insert_guard BEFORE INSERT ON cp.visit_permits
 FOR EACH ROW EXECUTE FUNCTION cp.visit_insert_guard();
CREATE TRIGGER arrival_requests_insert_guard BEFORE INSERT ON cp.arrival_requests
 FOR EACH ROW EXECUTE FUNCTION cp.visit_insert_guard();

-- Transições: convite ACTIVE -> REVOKED; chegada WAITING -> APPROVED/DENIED ou CANCELLED. Resposta pelo aplicativo só
-- vale dentro do prazo (resposta atrasada não decide); a confirmação assistida pela portaria (interfone/presencial,
-- com a origem anotada) também vale depois dele. Versão avança uma unidade, horários vêm do banco e estados finais não
-- mudam mais.
CREATE FUNCTION cp.visit_transition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_TABLE_NAME='visit_permits' THEN
  IF OLD.state<>'ACTIVE' OR NEW.state<>'REVOKED' OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'Transição de convite não permitida.' USING ERRCODE='23514';
  END IF;
  NEW.revoked_at := clock_timestamp();
 ELSE
  IF OLD.state<>'WAITING' OR NEW.state NOT IN ('APPROVED','DENIED','CANCELLED') OR NEW.version<>OLD.version+1
     OR (NEW.state IN ('APPROVED','DENIED') AND NEW.decision_source='APP' AND OLD.expires_at<=clock_timestamp()) THEN
   RAISE EXCEPTION 'Transição de chegada não permitida.' USING ERRCODE='23514';
  END IF;
  NEW.decided_at := clock_timestamp();
 END IF;
 NEW.updated_at := clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.visit_transition_guard() FROM PUBLIC;
CREATE TRIGGER visit_permits_transition_guard BEFORE UPDATE ON cp.visit_permits
 FOR EACH ROW EXECUTE FUNCTION cp.visit_transition_guard();
CREATE TRIGGER arrival_requests_transition_guard BEFORE UPDATE ON cp.arrival_requests
 FOR EACH ROW EXECUTE FUNCTION cp.visit_transition_guard();

-- Eventos de visitas só para convite ou chegada visível a quem grava (a política de leitura limita à unidade ou à
-- portaria); trilha sempre em nome da própria conta.
CREATE POLICY outbox_visit_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK((event_type NOT LIKE 'VISIT\_%' OR EXISTS(SELECT 1 FROM cp.visit_permits p
   WHERE p.tenant_id=cp.outbox.tenant_id AND p.id=cp.outbox.aggregate_id))
  AND (event_type NOT LIKE 'ARRIVAL\_%' OR EXISTS(SELECT 1 FROM cp.arrival_requests r
   WHERE r.tenant_id=cp.outbox.tenant_id AND r.id=cp.outbox.aggregate_id)));
CREATE POLICY audit_visit_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK((action NOT LIKE 'VISIT\_%' AND action NOT LIKE 'ARRIVAL\_%') OR actor_id=platform.current_actor_id());
