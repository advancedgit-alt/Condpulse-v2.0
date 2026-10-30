-- CP-034: operação da plataforma acompanha a fila de tarefas de todos os condomínios (estado, tentativas, erro saneado
-- e horários; nunca o conteúdo) e devolve para a fila, com motivo auditado pelo serviço, só tarefa FAILED ou SKIPPED
-- (por exemplo, evento que ganhou consumidor depois). A devolução renova as tentativas, limpa o erro e fica disponível já; o worker
-- processa de novo com os mesmos efeitos idempotentes. Sessão de condomínio e worker não reprocessam.

-- attempts é o número da tentativa e cerca o arrendamento (o worker conclui só a própria tentativa): nunca volta atrás.
-- A devolução renova o orçamento de tentativas movendo a base; o worker conta attempts-attempt_base contra o limite.
ALTER TABLE cp.outbox ADD COLUMN attempt_base integer NOT NULL DEFAULT 0 CHECK(attempt_base>=0),
 ADD CONSTRAINT outbox_attempt_base CHECK(attempt_base<=attempts);
GRANT SELECT(attempt_base) ON cp.outbox TO cp_worker;

GRANT SELECT(id,tenant_id,event_type,aggregate_id,state,attempts,attempt_base,available_at,lease_until,last_error,processed_at,created_at) ON cp.outbox TO cp_api;
GRANT UPDATE(state,attempts,available_at,last_error,processed_at) ON cp.outbox TO cp_api;
CREATE POLICY outbox_platform_read ON cp.outbox FOR SELECT TO cp_api USING(platform.has_platform_capability('outbox.operate'));
CREATE POLICY outbox_platform_replay ON cp.outbox FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('outbox.operate')) WITH CHECK(platform.has_platform_capability('outbox.operate'));
-- A API só lê ou altera tarefas com a capacidade de operação (a sessão do condomínio continua só criando tarefas).
CREATE POLICY outbox_api_read_only_platform ON cp.outbox AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_platform_capability('outbox.operate'));
CREATE POLICY outbox_api_update_only_platform ON cp.outbox AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('outbox.operate')) WITH CHECK(platform.has_platform_capability('outbox.operate') AND state='PENDING');

CREATE OR REPLACE FUNCTION cp.outbox_transition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.event_type<>OLD.event_type OR NEW.aggregate_id<>OLD.aggregate_id
    OR NEW.aggregate_version<>OLD.aggregate_version OR NEW.payload<>OLD.payload OR NEW.created_at<>OLD.created_at
    OR NEW.attempt_base<>OLD.attempt_base THEN
  RAISE EXCEPTION 'Tarefa do outbox não muda de conteúdo.' USING ERRCODE='23514';
 END IF;
 IF NEW.state='LEASED' AND ((OLD.state='PENDING' AND OLD.available_at<=clock_timestamp())
    OR (OLD.state='LEASED' AND OLD.lease_until<=clock_timestamp()))
    AND NEW.attempts=OLD.attempts+1 AND NEW.lease_until>clock_timestamp() AND NEW.lease_until<=clock_timestamp()+interval '10 minutes' THEN
  NEW.last_error := NULL;
 ELSIF OLD.state='LEASED' AND NEW.state IN ('DELIVERED','SKIPPED') AND NEW.attempts=OLD.attempts THEN
  NEW.lease_until := NULL; NEW.processed_at := clock_timestamp(); NEW.last_error := NULL;
 ELSIF OLD.state='LEASED' AND NEW.state='PENDING' AND NEW.attempts=OLD.attempts AND NEW.last_error IS NOT NULL
    AND NEW.available_at>clock_timestamp() THEN
  NEW.lease_until := NULL;
 ELSIF OLD.state='LEASED' AND NEW.state='FAILED' AND NEW.attempts=OLD.attempts AND NEW.last_error IS NOT NULL THEN
  NEW.lease_until := NULL; NEW.processed_at := clock_timestamp();
 ELSIF OLD.state IN ('FAILED','SKIPPED') AND NEW.state='PENDING'
    AND pg_has_role(current_user,'cp_api','MEMBER') AND NOT pg_has_role(current_user,'cp_worker','MEMBER')
    AND 'outbox.operate'=ANY(string_to_array(nullif(current_setting('app.platform_capabilities',true),''),',')) THEN
  -- Devolução pela operação da plataforma: tentativas renovadas sem reutilizar o número (cerca), disponível já, sem erro.
  NEW.attempts := OLD.attempts; NEW.attempt_base := OLD.attempts; NEW.available_at := clock_timestamp(); NEW.processed_at := NULL; NEW.last_error := NULL; NEW.lease_until := NULL;
 ELSE
  RAISE EXCEPTION 'Transição de tarefa do outbox não permitida.' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;

-- Trilha da plataforma por capacidade e prefixo da ação: a operação da fila só registra OUTBOX_*.
DROP POLICY platform_audit_insert ON platform.platform_audit_log;
CREATE POLICY platform_audit_insert ON platform.platform_audit_log FOR INSERT TO cp_api
 WITH CHECK(actor_id = platform.current_actor_id() AND (
  (platform.has_platform_capability('tenants.manage') AND action LIKE 'TENANT\_%')
  OR (platform.has_platform_capability('billing.manage') AND action LIKE 'BILLING\_%')
  OR (platform.has_platform_capability('outbox.operate') AND action LIKE 'OUTBOX\_%')));
