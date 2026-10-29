-- Outbox durável com worker (CP-034) e caixa interna da unidade (parte do CP-035). O worker (cp_worker, perfil sem
-- porta pública) arrenda lotes por condomínio com SKIP LOCKED e prazo de arrendamento; aplica o efeito interno e
-- registra o resultado na mesma transação, cercado pela tentativa arrendada (worker atrasado não confirma tarefa que
-- outro arrendou). Falha volta com novo horário (backoff) e, depois do limite, fica FAILED com erro saneado; evento sem
-- consumidor neste recorte fica SKIPPED, nunca "entregue". O único canal é a caixa interna (IN_APP): "disponível na
-- caixa" não é leitura, que só existe com a abertura autenticada pelo destinatário. Push, e-mail e SMS não existem aqui.

-- 1. Estados e campos do processamento.
ALTER TABLE cp.outbox DROP CONSTRAINT outbox_state_check;
ALTER TABLE cp.outbox ADD CONSTRAINT outbox_state_check CHECK(state IN ('PENDING','LEASED','DELIVERED','SKIPPED','FAILED')),
 ADD COLUMN last_error text CHECK(last_error IS NULL OR length(last_error) BETWEEN 1 AND 300),
 ADD COLUMN processed_at timestamptz,
 ADD CONSTRAINT outbox_lease_fields CHECK((state='LEASED')=(lease_until IS NOT NULL)),
 ADD CONSTRAINT outbox_processed_fields CHECK((state IN ('DELIVERED','SKIPPED','FAILED'))=(processed_at IS NOT NULL)),
 ADD CONSTRAINT outbox_failure_reason CHECK(state<>'FAILED' OR last_error IS NOT NULL);
CREATE INDEX outbox_expired_leases ON cp.outbox(tenant_id,lease_until) WHERE state='LEASED';

-- A aplicação só cria tarefa nova; quem processa é o worker, coluna por coluna.
REVOKE INSERT ON cp.outbox FROM cp_api;
GRANT INSERT(tenant_id,event_type,aggregate_id,aggregate_version,payload) ON cp.outbox TO cp_api;
REVOKE UPDATE ON cp.outbox FROM cp_worker;
GRANT UPDATE(state,attempts,available_at,lease_until,last_error,processed_at) ON cp.outbox TO cp_worker;

-- 2. Transições do processamento: PENDING -> LEASED (tentativa +1, prazo futuro de até 10 minutos); arrendamento
-- vencido pode ser arrendado de novo; LEASED -> DELIVERED/SKIPPED (concluída), PENDING (nova tentativa com horário
-- futuro e erro) ou FAILED (erro). Concluídas e falhas são finais neste recorte: o reprocessamento seletivo pelo
-- operador ainda não existe, e FAILED fica visível para tratamento. Horários de conclusão vêm do banco.
CREATE FUNCTION cp.outbox_transition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.event_type<>OLD.event_type OR NEW.aggregate_id<>OLD.aggregate_id
    OR NEW.aggregate_version<>OLD.aggregate_version OR NEW.payload<>OLD.payload OR NEW.created_at<>OLD.created_at THEN
  RAISE EXCEPTION 'Tarefa do outbox não muda de conteúdo.' USING ERRCODE='23514';
 END IF;
 IF NEW.state='LEASED' AND (OLD.state='PENDING' OR (OLD.state='LEASED' AND OLD.lease_until<=clock_timestamp()))
    AND NEW.attempts=OLD.attempts+1 AND NEW.lease_until>clock_timestamp() AND NEW.lease_until<=clock_timestamp()+interval '10 minutes' THEN
  -- Tentativa nova começa sem erro: falha e nova tentativa precisam declarar o próprio erro.
  NEW.last_error := NULL;
 ELSIF OLD.state='LEASED' AND NEW.state IN ('DELIVERED','SKIPPED') AND NEW.attempts=OLD.attempts THEN
  NEW.lease_until := NULL; NEW.processed_at := clock_timestamp(); NEW.last_error := NULL;
 ELSIF OLD.state='LEASED' AND NEW.state='PENDING' AND NEW.attempts=OLD.attempts AND NEW.last_error IS NOT NULL
    AND NEW.available_at>clock_timestamp() THEN
  NEW.lease_until := NULL;
 ELSIF OLD.state='LEASED' AND NEW.state='FAILED' AND NEW.attempts=OLD.attempts AND NEW.last_error IS NOT NULL THEN
  NEW.lease_until := NULL; NEW.processed_at := clock_timestamp();
 ELSE
  RAISE EXCEPTION 'Transição de tarefa do outbox não permitida.' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.outbox_transition_guard() FROM PUBLIC;
CREATE TRIGGER outbox_transition_guard BEFORE UPDATE ON cp.outbox FOR EACH ROW EXECUTE FUNCTION cp.outbox_transition_guard();

-- A aplicação cria a tarefa pendente; nada de estado, tentativa ou prazo escolhido por ela.
CREATE POLICY outbox_api_insert_pending ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(state='PENDING' AND attempts=0 AND lease_until IS NULL AND processed_at IS NULL AND last_error IS NULL);

-- 3. Destinatários: o worker enxerga somente vínculos residenciais vigentes do condomínio em contexto, a situação das
-- contas desses vínculos e as unidades.
GRANT SELECT(id,tenant_id,account_id,unit_id,role,status,valid_from,valid_until,permissions) ON cp.memberships TO cp_worker;
CREATE POLICY membership_worker_read ON cp.memberships FOR SELECT TO cp_worker
 USING(tenant_id=platform.current_tenant_id() AND unit_id IS NOT NULL AND role IN ('RESIDENT','RESPONSIBLE','DEPENDENT')
  AND status='ACTIVE');
GRANT SELECT(id,status) ON platform.accounts TO cp_worker;
CREATE POLICY account_worker_read ON platform.accounts FOR SELECT TO cp_worker
 USING(EXISTS(SELECT 1 FROM cp.memberships m WHERE m.account_id=platform.accounts.id AND m.tenant_id=platform.current_tenant_id()
  AND m.unit_id IS NOT NULL AND m.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT') AND m.status='ACTIVE'));
GRANT SELECT(id,tenant_id,status) ON cp.units TO cp_worker;
-- Situação mínima do registro que gerou o evento: o worker não avisa chegada já respondida/vencida nem encomenda já
-- retirada/anulada. Sem nome de visitante, descrição ou local de guarda.
GRANT SELECT(id,tenant_id,unit_id,state,expires_at) ON cp.arrival_requests TO cp_worker;
GRANT SELECT(id,tenant_id,unit_id,status) ON cp.parcels TO cp_worker;

-- Quem recebe cada tipo de aviso: chegada aguardando, quem pode responder (adulto ou dependente com visits.decide);
-- encomenda recebida, quem vê as encomendas (adulto ou dependente com parcels.view). Conta ativa, vínculo vigente,
-- unidade ativa. Executa como quem consulta: worker (vínculos do condomínio) ou o próprio destinatário (vínculo dele).
CREATE FUNCTION cp.notification_recipient(target_tenant uuid,target_unit uuid,target_account uuid,notification_kind text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=target_account AND m.status='ACTIVE'
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   AND (m.role IN ('RESIDENT','RESPONSIBLE') OR (m.role='DEPENDENT' AND coalesce(m.permissions#>(CASE notification_kind
     WHEN 'ARRIVAL_WAITING' THEN ARRAY['visits','decide'] WHEN 'PARCEL_RECEIVED' THEN ARRAY['parcels','view'] END)='true'::jsonb,false)))) $$;
REVOKE ALL ON FUNCTION cp.notification_recipient(uuid,uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.notification_recipient(uuid,uuid,uuid,text) TO cp_api,cp_worker;

-- 4. Caixa interna: um aviso por tarefa, destinatário e canal; sem nome do visitante nem dado do registro no aviso (a
-- tela busca o detalhe com as permissões de quem abre).
CREATE TABLE cp.notifications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 account_id uuid NOT NULL REFERENCES platform.accounts(id),
 unit_id uuid NOT NULL,
 outbox_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('ARRIVAL_WAITING','PARCEL_RECEIVED')),
 resource_id uuid NOT NULL,
 channel text NOT NULL DEFAULT 'IN_APP' CHECK(channel='IN_APP'),
 state text NOT NULL DEFAULT 'AVAILABLE' CHECK(state IN ('AVAILABLE','READ_IN_APP')),
 read_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,outbox_id,account_id,channel),
 FOREIGN KEY(tenant_id,outbox_id) REFERENCES cp.outbox(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK((state='READ_IN_APP')=(read_at IS NOT NULL))
);
CREATE INDEX notifications_inbox ON cp.notifications(tenant_id,account_id,unit_id,created_at DESC,id);
REVOKE ALL ON cp.notifications FROM PUBLIC;
GRANT SELECT ON cp.notifications TO cp_api,cp_worker;
GRANT UPDATE(state) ON cp.notifications TO cp_api;
GRANT INSERT(tenant_id,account_id,unit_id,outbox_id,kind,resource_id) ON cp.notifications TO cp_worker;
ALTER TABLE cp.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.notifications TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
-- O destinatário vê somente os próprios avisos, e só enquanto ainda tem o vínculo que justificou o aviso.
CREATE POLICY notifications_owner_read ON cp.notifications AS RESTRICTIVE FOR SELECT TO cp_api
 USING(account_id=platform.current_actor_id() AND cp.notification_recipient(tenant_id,unit_id,account_id,kind));
CREATE POLICY notifications_owner_open ON cp.notifications AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(account_id=platform.current_actor_id()) WITH CHECK(account_id=platform.current_actor_id() AND state='READ_IN_APP');
-- O worker grava somente o aviso da tarefa que arrendou, do tipo correspondente ao evento, para a unidade real do
-- registro (a do payload precisa conferir com a da chegada ou da encomenda) e para destinatário apto.
CREATE POLICY notifications_worker_insert ON cp.notifications AS RESTRICTIVE FOR INSERT TO cp_worker
 WITH CHECK(EXISTS(SELECT 1 FROM cp.outbox o WHERE o.tenant_id=cp.notifications.tenant_id AND o.id=cp.notifications.outbox_id
   AND o.state='LEASED' AND o.aggregate_id=cp.notifications.resource_id AND o.payload->>'unitId'=cp.notifications.unit_id::text
   AND ((cp.notifications.kind='ARRIVAL_WAITING' AND o.event_type='ARRIVAL_REQUESTED' AND EXISTS(SELECT 1 FROM cp.arrival_requests a
      WHERE a.tenant_id=o.tenant_id AND a.id=o.aggregate_id AND a.unit_id=cp.notifications.unit_id))
    OR (cp.notifications.kind='PARCEL_RECEIVED' AND o.event_type='PARCEL_RECEIVED' AND EXISTS(SELECT 1 FROM cp.parcels p
      WHERE p.tenant_id=o.tenant_id AND p.id=o.aggregate_id AND p.unit_id=cp.notifications.unit_id))))
  AND cp.notification_recipient(tenant_id,unit_id,account_id,kind));

-- Abertura: AVAILABLE -> READ_IN_APP uma vez, com horário do banco; nada mais muda.
CREATE FUNCTION cp.notification_open_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.state<>'AVAILABLE' OR NEW.state<>'READ_IN_APP' THEN
  RAISE EXCEPTION 'Aviso já aberto ou transição não permitida.' USING ERRCODE='23514';
 END IF;
 NEW.read_at := clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.notification_open_guard() FROM PUBLIC;
CREATE TRIGGER notifications_open_guard BEFORE UPDATE ON cp.notifications FOR EACH ROW EXECUTE FUNCTION cp.notification_open_guard();
