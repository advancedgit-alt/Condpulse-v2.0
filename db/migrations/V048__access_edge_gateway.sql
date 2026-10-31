-- AC-018 (dados) e contrato do gateway com a plataforma (AC-012/AC-013, parte do executor), pacote Acesso Facial v1.1.
--  - cp.edge_agents: identidade técnica do gateway (local ou de nuvem, D5) por condomínio e conector. Autenticação de
--    máquina por credencial própria de cada gateway (256 bits gerados NO gateway); a plataforma guarda só o SHA-256
--    (credential_hash), nunca o valor, e nunca devolve segredo algum. O mTLS do pacote (edgeMutualTLS) fica pendente
--    (DECISOES_PENDENTES, P19): a borda atual (Fly/Vercel) termina o TLS e não repassa certificado de cliente.
--  - cp.edge_leases: entrega (arrendamento com prazo) de cada trabalho da V047 ao gateway do conector do equipamento.
--    Entra trabalho PENDING ou só com ACK (ainda sem resultado). Entrega vencida sem resultado, com ou sem ACK, volta na
--    tentativa seguinte (pelo menos uma vez; a fila do gateway deduplica pela chave); revogar o gateway libera na hora as
--    entregas abertas dele, e o gateway válido do conector retoma o mesmo trabalho (mesma versão, hash e prioridade).
--  - cp.edge_reports: relatório do gateway, só inserção, idempotente por chave (mesma chave e mesmo corpo devolvem o
--    mesmo resultado). ACK é só transporte; CONFIRMED exige a leitura de volta com o MESMO hash do documento (CHECK da
--    V047); quem relata é sempre quem detém o arrendamento mais recente do trabalho.
--  - Contexto de máquina (app.edge_credential_hash): só vale sem ator, sem capacidade, sem escopo técnico e sem papel
--    de plataforma (transação dedicada da API). O banco confere de novo identidade, estado e validade a cada instrução.
-- Nada aqui fala com equipamento; trabalho entregue não é sucesso físico. Mesmo lock por condomínio das V041-V047.

-- 1. Identidade do gateway.
CREATE TABLE cp.edge_agents (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 connector_id uuid NOT NULL,
 -- Instância nunca reciclada: reinstalar é cadastrar outra identidade.
 instance_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
 label text NOT NULL CHECK(length(btrim(label)) BETWEEN 1 AND 80),
 -- SHA-256 (hex) da credencial gerada no gateway; único na plataforma inteira (nunca reaproveitado, nem após revogação).
 credential_hash text NOT NULL UNIQUE CHECK(credential_hash ~ '^[0-9a-f]{64}$'),
 credential_expires_at timestamptz NOT NULL,
 credential_rotated_at timestamptz,
 status text NOT NULL DEFAULT 'PROVISIONED' CHECK(status IN ('PROVISIONED','ONLINE','REVOKED')),
 agent_version text CHECK(agent_version IS NULL OR agent_version ~ '^[A-Za-z0-9._+-]{1,64}$'),
 protocol_version text CHECK(protocol_version IS NULL OR protocol_version ~ '^[0-9]{1,3}\.[0-9]{1,3}$'),
 -- Último contato autenticado; não prova saúde física dos equipamentos.
 last_seen_at timestamptz,
 revoked_at timestamptz,
 revoke_reason text CHECK(revoke_reason IS NULL OR length(btrim(revoke_reason)) BETWEEN 3 AND 500),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,connector_id) REFERENCES cp.access_connectors(tenant_id,id),
 CHECK((status='REVOKED')=(revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)),
 CHECK(credential_expires_at>created_at)
);
-- Um só gateway não revogado por conector: dois escritores no mesmo destino exigiriam eleição/fencing (AC-022, pendente).
CREATE UNIQUE INDEX edge_agents_one_per_connector ON cp.edge_agents(tenant_id,connector_id) WHERE status<>'REVOKED';

-- Credencial apresentada (hash) no contexto de máquina; nulo em qualquer sessão de pessoa, plataforma ou técnica.
CREATE FUNCTION cp.edge_credential() RETURNS text
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT CASE WHEN platform.current_actor_id() IS NULL AND coalesce(current_setting('app.capabilities',true),'')=''
   AND coalesce(current_setting('app.technical_scopes',true),'')='' AND coalesce(current_setting('app.platform_capabilities',true),'')=''
   AND coalesce(current_setting('app.edge_credential_hash',true),'') ~ '^[0-9a-f]{64}$'
  THEN current_setting('app.edge_credential_hash',true) END $$;
REVOKE ALL ON FUNCTION cp.edge_credential() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.edge_credential() TO cp_api;

-- Gateway autenticado no condomínio da transação: credencial igual, não revogado e dentro da validade.
CREATE FUNCTION cp.edge_agent_id() RETURNS uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT a.id FROM cp.edge_agents a WHERE a.tenant_id=platform.current_tenant_id() AND a.credential_hash=cp.edge_credential()
   AND a.status IN ('PROVISIONED','ONLINE') AND a.credential_expires_at>clock_timestamp() $$;
REVOKE ALL ON FUNCTION cp.edge_agent_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.edge_agent_id() TO cp_api;

CREATE FUNCTION cp.edge_connector() RETURNS uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT a.connector_id FROM cp.edge_agents a WHERE a.tenant_id=platform.current_tenant_id() AND a.id=cp.edge_agent_id() $$;
REVOKE ALL ON FUNCTION cp.edge_connector() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.edge_connector() TO cp_api;

-- Equipamento atendido pelo gateway autenticado (mesmo conector).
CREATE FUNCTION cp.edge_device(target_tenant uuid,target_device uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT cp.edge_credential() IS NOT NULL AND EXISTS(SELECT 1 FROM cp.access_devices d WHERE d.tenant_id=target_tenant AND d.id=target_device
   AND d.connector_id=cp.edge_connector()) $$;
REVOKE ALL ON FUNCTION cp.edge_device(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.edge_device(uuid,uuid) TO cp_api;

CREATE FUNCTION cp.edge_agents_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE edge text:=cp.edge_credential(); now_at timestamptz:=clock_timestamp();
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PROVISIONED' OR NEW.version<>0 OR NEW.revoked_at IS NOT NULL OR NEW.revoke_reason IS NOT NULL OR NEW.last_seen_at IS NOT NULL
    OR NEW.agent_version IS NOT NULL OR NEW.protocol_version IS NOT NULL OR NEW.credential_rotated_at IS NOT NULL THEN
   RAISE EXCEPTION 'gateway nasce provisionado na versão 0' USING ERRCODE='23514';
  END IF;
  IF NOT platform.has_technical_scope('gateway.manage') OR NEW.created_by IS DISTINCT FROM platform.current_actor_id() THEN
   RAISE EXCEPTION 'cadastro de gateway exige gateway.manage em nome da própria conta' USING ERRCODE='42501';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM cp.access_connectors k WHERE k.tenant_id=NEW.tenant_id AND k.id=NEW.connector_id AND k.status='ACTIVE') THEN
   RAISE EXCEPTION 'conector inexistente ou inativo' USING ERRCODE='23503';
  END IF;
  IF NEW.credential_expires_at<=now_at+interval '1 day' OR NEW.credential_expires_at>now_at+interval '180 days' THEN
   RAISE EXCEPTION 'validade da credencial entre 1 e 180 dias' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=now_at; NEW.updated_at:=now_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.connector_id,NEW.instance_id,NEW.label,NEW.created_by,NEW.created_at)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.connector_id,OLD.instance_id,OLD.label,OLD.created_by,OLD.created_at)
   OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'identidade do gateway é imutável' USING ERRCODE='23514';
 END IF;
 IF OLD.status='REVOKED' THEN RAISE EXCEPTION 'gateway revogado não muda' USING ERRCODE='23514'; END IF;
 IF edge IS NOT NULL THEN
  -- O próprio gateway: só contato, versões e PROVISIONED→ONLINE; nunca credencial, validade ou revogação.
  IF OLD.credential_hash<>edge OR (NEW.credential_hash,NEW.credential_expires_at,NEW.credential_rotated_at,NEW.revoked_at,NEW.revoke_reason)
    IS DISTINCT FROM (OLD.credential_hash,OLD.credential_expires_at,OLD.credential_rotated_at,OLD.revoked_at,OLD.revoke_reason)
    OR NEW.status NOT IN ('PROVISIONED','ONLINE') OR (OLD.status='ONLINE' AND NEW.status<>'ONLINE') THEN
   RAISE EXCEPTION 'gateway só registra o próprio contato' USING ERRCODE='42501';
  END IF;
  NEW.last_seen_at:=now_at;
 ELSIF platform.has_technical_scope('gateway.manage') AND platform.current_actor_id() IS NOT NULL THEN
  -- MASTER: rotação (hash novo, validade nova) ou revogação definitiva; nunca contato nem versões do gateway.
  IF (NEW.last_seen_at,NEW.agent_version,NEW.protocol_version) IS DISTINCT FROM (OLD.last_seen_at,OLD.agent_version,OLD.protocol_version) THEN
   RAISE EXCEPTION 'contato e versão só pelo próprio gateway' USING ERRCODE='42501';
  END IF;
  IF NEW.status='REVOKED' THEN
   IF (NEW.credential_hash,NEW.credential_expires_at) IS DISTINCT FROM (OLD.credential_hash,OLD.credential_expires_at) THEN
    RAISE EXCEPTION 'revogação não troca a credencial' USING ERRCODE='23514';
   END IF;
   NEW.revoked_at:=now_at;
  ELSIF NEW.status<>OLD.status OR NEW.revoked_at IS NOT NULL OR NEW.revoke_reason IS NOT NULL THEN
   RAISE EXCEPTION 'transição de gateway inválida' USING ERRCODE='23514';
  ELSE
   IF NEW.credential_hash=OLD.credential_hash THEN RAISE EXCEPTION 'rotação exige credencial nova' USING ERRCODE='23514'; END IF;
   IF NEW.credential_expires_at<=now_at+interval '1 day' OR NEW.credential_expires_at>now_at+interval '180 days' THEN
    RAISE EXCEPTION 'validade da credencial entre 1 e 180 dias' USING ERRCODE='23514';
   END IF;
   NEW.credential_rotated_at:=now_at;
  END IF;
 ELSE
  RAISE EXCEPTION 'gateway só muda pelo MASTER (gateway.manage) ou pelo próprio gateway' USING ERRCODE='42501';
 END IF;
 NEW.updated_at:=now_at;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.edge_agents_guard() FROM PUBLIC;
CREATE TRIGGER edge_agents_guard BEFORE INSERT OR UPDATE ON cp.edge_agents FOR EACH ROW EXECUTE FUNCTION cp.edge_agents_guard();

-- 2. Entrega dos trabalhos da V047 ao gateway (arrendamento com prazo, tentativa monotônica por trabalho).
CREATE TABLE cp.edge_leases (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 publication_id uuid NOT NULL,
 agent_id uuid NOT NULL,
 device_id uuid NOT NULL,
 attempt integer NOT NULL DEFAULT 0 CHECK(attempt BETWEEN 0 AND 1000000),
 lease_seconds integer NOT NULL DEFAULT 120 CHECK(lease_seconds BETWEEN 30 AND 600),
 leased_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 lease_until timestamptz NOT NULL DEFAULT clock_timestamp(),
 -- ACK do gateway: comando gravado na fila local durável (não é aplicação no equipamento).
 acknowledged_at timestamptz,
 -- Resultado relatado (qualquer estado além de ACK) ou trabalho já encerrado na plataforma.
 finished_at timestamptz,
 -- Liberada pela revogação do gateway que a detinha: deixa de valer na hora, sem esperar o prazo.
 released_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,publication_id,attempt),
 FOREIGN KEY(tenant_id,publication_id) REFERENCES cp.rights_publications(tenant_id,id),
 FOREIGN KEY(tenant_id,agent_id) REFERENCES cp.edge_agents(tenant_id,id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES cp.access_devices(tenant_id,id),
 CHECK(lease_until>leased_at)
);
CREATE INDEX edge_leases_publication ON cp.edge_leases(tenant_id,publication_id,attempt DESC);
CREATE INDEX edge_leases_agent ON cp.edge_leases(tenant_id,agent_id,leased_at DESC);

-- Detentor do trabalho: o gateway autenticado tem o arrendamento mais recente (aberto ou vencido, não liberado) do
-- trabalho. Entrega nova para outro gateway (ou liberação pela revogação) tira do anterior qualquer relatório.
CREATE FUNCTION cp.edge_lease_holder(target_tenant uuid,target_publication uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT cp.edge_credential() IS NOT NULL AND coalesce((SELECT l.agent_id=cp.edge_agent_id() AND l.released_at IS NULL FROM cp.edge_leases l
   WHERE l.tenant_id=target_tenant AND l.publication_id=target_publication ORDER BY l.attempt DESC LIMIT 1),false) $$;
REVOKE ALL ON FUNCTION cp.edge_lease_holder(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.edge_lease_holder(uuid,uuid) TO cp_api;

CREATE FUNCTION cp.edge_leases_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE p record; now_at timestamptz:=clock_timestamp(); agent uuid:=cp.edge_agent_id();
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 -- Liberação pela revogação do gateway (cascata do MASTER com gateway.manage): só released_at muda.
 IF TG_OP='UPDATE' AND agent IS NULL AND cp.edge_credential() IS NULL THEN
  IF NOT platform.has_technical_scope('gateway.manage') OR platform.current_actor_id() IS NULL
    OR NOT EXISTS(SELECT 1 FROM cp.edge_agents a WHERE a.tenant_id=OLD.tenant_id AND a.id=OLD.agent_id AND a.status='REVOKED')
    OR OLD.released_at IS NOT NULL OR OLD.finished_at IS NOT NULL OR NEW.released_at IS NULL
    OR (NEW.id,NEW.tenant_id,NEW.publication_id,NEW.agent_id,NEW.device_id,NEW.attempt,NEW.lease_seconds,NEW.leased_at,NEW.lease_until,
      NEW.acknowledged_at,NEW.finished_at) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.publication_id,OLD.agent_id,OLD.device_id,OLD.attempt,
      OLD.lease_seconds,OLD.leased_at,OLD.lease_until,OLD.acknowledged_at,OLD.finished_at)
    OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'liberação só da entrega aberta de gateway revogado' USING ERRCODE='42501';
  END IF;
  NEW.released_at:=now_at;
  RETURN NEW;
 END IF;
 IF agent IS NULL OR NEW.agent_id IS DISTINCT FROM agent THEN
  RAISE EXCEPTION 'arrendamento só pelo próprio gateway autenticado' USING ERRCODE='42501';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>0 OR NEW.acknowledged_at IS NOT NULL OR NEW.finished_at IS NOT NULL OR NEW.released_at IS NOT NULL THEN
   RAISE EXCEPTION 'arrendamento nasce aberto na versão 0' USING ERRCODE='23514';
  END IF;
  SELECT r.device_id,r.state,r.next_attempt_at INTO p FROM cp.rights_publications r WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.publication_id;
  IF NOT FOUND OR p.device_id<>NEW.device_id OR NOT cp.edge_device(NEW.tenant_id,NEW.device_id) THEN
   RAISE EXCEPTION 'trabalho fora dos equipamentos deste gateway' USING ERRCODE='42501';
  END IF;
  -- Sem resultado ainda: PENDING ou só ACK (o ACK de um gateway que sumiu não pode prender uma revogação).
  IF p.state NOT IN ('PENDING','ACK') OR p.next_attempt_at>now_at THEN
   RAISE EXCEPTION 'só trabalho sem resultado é entregue' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM cp.edge_leases l WHERE l.tenant_id=NEW.tenant_id AND l.publication_id=NEW.publication_id
    AND l.finished_at IS NULL AND l.released_at IS NULL AND l.lease_until>now_at) THEN
   RAISE EXCEPTION 'trabalho já entregue e em andamento' USING ERRCODE='23514';
  END IF;
  NEW.attempt:=coalesce((SELECT max(l.attempt) FROM cp.edge_leases l WHERE l.tenant_id=NEW.tenant_id AND l.publication_id=NEW.publication_id),0)+1;
  NEW.leased_at:=now_at;
  NEW.lease_until:=now_at+make_interval(secs=>NEW.lease_seconds);
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.publication_id,NEW.agent_id,NEW.device_id,NEW.attempt,NEW.lease_seconds,NEW.leased_at,NEW.lease_until)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.publication_id,OLD.agent_id,OLD.device_id,OLD.attempt,OLD.lease_seconds,OLD.leased_at,OLD.lease_until)
   OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'arrendamento é imutável; só ACK e término avançam' USING ERRCODE='23514';
 END IF;
 IF OLD.released_at IS NOT NULL OR NEW.released_at IS NOT NULL THEN
  RAISE EXCEPTION 'entrega liberada não avança' USING ERRCODE='23514';
 END IF;
 IF (OLD.acknowledged_at IS NOT NULL AND NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at)
   OR (OLD.finished_at IS NOT NULL AND NEW.finished_at IS DISTINCT FROM OLD.finished_at) THEN
  RAISE EXCEPTION 'ACK e término não voltam nem mudam' USING ERRCODE='23514';
 END IF;
 IF NEW.acknowledged_at IS NOT NULL AND OLD.acknowledged_at IS NULL THEN NEW.acknowledged_at:=now_at; END IF;
 IF NEW.finished_at IS NOT NULL AND OLD.finished_at IS NULL THEN NEW.finished_at:=now_at; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.edge_leases_guard() FROM PUBLIC;
CREATE TRIGGER edge_leases_guard BEFORE INSERT OR UPDATE ON cp.edge_leases FOR EACH ROW EXECUTE FUNCTION cp.edge_leases_guard();

-- Revogar o gateway libera na mesma transação as entregas sem resultado dele: o gateway válido do conector retoma o
-- mesmo trabalho (revogação não fica presa num ACK de identidade revogada).
CREATE FUNCTION cp.edge_agents_release() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 UPDATE cp.edge_leases l SET released_at=clock_timestamp(),version=l.version+1
  WHERE l.tenant_id=NEW.tenant_id AND l.agent_id=NEW.id AND l.finished_at IS NULL AND l.released_at IS NULL;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.edge_agents_release() FROM PUBLIC;
CREATE TRIGGER edge_agents_release AFTER UPDATE OF status ON cp.edge_agents FOR EACH ROW
 WHEN (NEW.status='REVOKED' AND OLD.status<>'REVOKED') EXECUTE FUNCTION cp.edge_agents_release();

-- 3. Relatórios do gateway: evidência só de inserção; idempotência por (gateway, chave).
CREATE TABLE cp.edge_reports (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 agent_id uuid NOT NULL,
 lease_id uuid NOT NULL,
 publication_id uuid NOT NULL,
 idempotency_key text NOT NULL CHECK(idempotency_key ~ '^[A-Za-z0-9_.:-]{8,128}$'),
 -- SHA-256 do relatório normalizado: mesma chave com corpo diferente é conflito, nunca segundo efeito.
 request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 state text NOT NULL CHECK(state IN ('ACK','APPLIED_UNVERIFIED','CONFIRMED','PARTIAL','FAILED','UNKNOWN')),
 result_code text CHECK(result_code IS NULL OR result_code ~ '^[A-Z0-9_]{1,64}$'),
 document_hash text NOT NULL CHECK(document_hash ~ '^[0-9a-f]{64}$'),
 observed_hash text CHECK(observed_hash IS NULL OR observed_hash ~ '^[0-9a-f]{64}$'),
 outcome text NOT NULL CHECK(outcome IN ('APPLIED','CLOSED','REJECTED')),
 outcome_code text CHECK(outcome_code IS NULL OR outcome_code ~ '^[A-Z0-9_]{1,64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,agent_id,idempotency_key),
 FOREIGN KEY(tenant_id,agent_id) REFERENCES cp.edge_agents(tenant_id,id),
 FOREIGN KEY(tenant_id,lease_id) REFERENCES cp.edge_leases(tenant_id,id),
 FOREIGN KEY(tenant_id,publication_id) REFERENCES cp.rights_publications(tenant_id,id),
 CHECK(state<>'CONFIRMED' OR observed_hash IS NOT NULL),
 CHECK((outcome='APPLIED')=(outcome_code IS NULL))
);
CREATE INDEX edge_reports_publication ON cp.edge_reports(tenant_id,publication_id,created_at);

CREATE FUNCTION cp.edge_reports_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE l record; p record; agent uuid:=cp.edge_agent_id();
BEGIN
 IF agent IS NULL OR NEW.agent_id IS DISTINCT FROM agent THEN
  RAISE EXCEPTION 'relatório só do próprio gateway autenticado' USING ERRCODE='42501';
 END IF;
 SELECT * INTO l FROM cp.edge_leases x WHERE x.tenant_id=NEW.tenant_id AND x.publication_id=NEW.publication_id ORDER BY x.attempt DESC LIMIT 1;
 IF NOT FOUND OR l.id<>NEW.lease_id OR l.agent_id<>agent OR l.released_at IS NOT NULL THEN
  RAISE EXCEPTION 'relatório exige o arrendamento mais recente do trabalho' USING ERRCODE='42501';
 END IF;
 SELECT r.state,r.document_hash,r.observed_hash INTO p FROM cp.rights_publications r WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.publication_id;
 -- Efeito aplicado só se o trabalho mostra exatamente o que foi relatado, para o mesmo documento.
 IF NEW.outcome='APPLIED' AND (p.state<>NEW.state OR p.document_hash<>NEW.document_hash
   OR (NEW.state='CONFIRMED' AND (NEW.observed_hash<>p.document_hash OR p.observed_hash IS DISTINCT FROM NEW.observed_hash))) THEN
  RAISE EXCEPTION 'relatório aplicado diverge do trabalho' USING ERRCODE='23514';
 END IF;
 IF NEW.outcome='CLOSED' AND p.state NOT IN ('CONFIRMED','SUPERSEDED') THEN
  RAISE EXCEPTION 'trabalho ainda aberto não é encerrado pelo relatório' USING ERRCODE='23514';
 END IF;
 NEW.created_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.edge_reports_guard() FROM PUBLIC;
CREATE TRIGGER edge_reports_guard BEFORE INSERT ON cp.edge_reports FOR EACH ROW EXECUTE FUNCTION cp.edge_reports_guard();

-- 4. RLS. Leem o MASTER (gateway.manage ou installation.read), a gestão de direitos (o andamento da publicação) e o
-- próprio gateway; só o MASTER cadastra, gira e revoga; só o gateway arrenda e relata.
ALTER TABLE cp.edge_agents ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.edge_agents FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.edge_leases ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.edge_leases FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.edge_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.edge_reports FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.edge_agents,cp.edge_leases,cp.edge_reports FROM PUBLIC;

CREATE POLICY tenant_boundary ON cp.edge_agents TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
-- Antes de saber o condomínio, o gateway só enxerga a própria linha (pelo hash da credencial apresentada).
CREATE POLICY edge_agents_lookup ON cp.edge_agents FOR SELECT TO cp_api
 USING(platform.current_tenant_id() IS NULL AND credential_hash=cp.edge_credential());
CREATE POLICY edge_agents_read ON cp.edge_agents AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_technical_scope('gateway.manage') OR platform.has_technical_scope('installation.read') OR credential_hash=cp.edge_credential());
CREATE POLICY edge_agents_register ON cp.edge_agents AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_technical_scope('gateway.manage') AND created_by=platform.current_actor_id());
CREATE POLICY edge_agents_update ON cp.edge_agents AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_technical_scope('gateway.manage') OR credential_hash=cp.edge_credential())
 WITH CHECK(platform.has_technical_scope('gateway.manage') OR credential_hash=cp.edge_credential());
GRANT SELECT ON cp.edge_agents TO cp_api;
GRANT INSERT(id,tenant_id,connector_id,label,credential_hash,credential_expires_at,created_by) ON cp.edge_agents TO cp_api;
GRANT UPDATE(credential_hash,credential_expires_at,status,revoke_reason,last_seen_at,agent_version,protocol_version,version) ON cp.edge_agents TO cp_api;

CREATE POLICY tenant_boundary ON cp.edge_leases TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY edge_leases_read ON cp.edge_leases AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.edge_device(tenant_id,device_id) OR platform.has_technical_scope('gateway.manage') OR platform.has_technical_scope('installation.read')
  OR cp.grant_reader());
CREATE POLICY edge_leases_create ON cp.edge_leases AS RESTRICTIVE FOR INSERT TO cp_api WITH CHECK(agent_id=cp.edge_agent_id());
CREATE POLICY edge_leases_progress ON cp.edge_leases AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(agent_id=cp.edge_agent_id() OR platform.has_technical_scope('gateway.manage'))
 WITH CHECK(agent_id=cp.edge_agent_id() OR platform.has_technical_scope('gateway.manage'));
GRANT SELECT ON cp.edge_leases TO cp_api;
GRANT INSERT(id,tenant_id,publication_id,agent_id,device_id,lease_seconds) ON cp.edge_leases TO cp_api;
GRANT UPDATE(acknowledged_at,finished_at,released_at,version) ON cp.edge_leases TO cp_api;

CREATE POLICY tenant_boundary ON cp.edge_reports TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY edge_reports_read ON cp.edge_reports AS RESTRICTIVE FOR SELECT TO cp_api
 USING(agent_id=cp.edge_agent_id() OR platform.has_technical_scope('gateway.manage') OR platform.has_technical_scope('installation.read')
  OR cp.grant_reader());
CREATE POLICY edge_reports_create ON cp.edge_reports AS RESTRICTIVE FOR INSERT TO cp_api WITH CHECK(agent_id=cp.edge_agent_id());
GRANT SELECT ON cp.edge_reports TO cp_api;
GRANT INSERT(id,tenant_id,agent_id,lease_id,publication_id,idempotency_key,request_hash,state,result_code,document_hash,observed_hash,outcome,outcome_code)
 ON cp.edge_reports TO cp_api;

-- 5. Trabalhos da V047 para o gateway: lê os do próprio conector; relata só quem detém o arrendamento mais recente. A
-- superação continua da cascata de quem publica (nunca do gateway). O worker (cp_worker) mantém o que já tinha.
DROP POLICY rights_publications_read ON cp.rights_publications;
CREATE POLICY rights_publications_read ON cp.rights_publications AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.grant_reader() OR cp.edge_device(tenant_id,device_id));
DROP POLICY rights_publications_supersede ON cp.rights_publications;
CREATE POLICY rights_publications_update ON cp.rights_publications AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.grant_reader() OR cp.edge_lease_holder(tenant_id,id))
 WITH CHECK((state='SUPERSEDED' AND cp.edge_credential() IS NULL) OR (state<>'SUPERSEDED' AND cp.edge_lease_holder(tenant_id,id)));
GRANT UPDATE(confirmation_level,observed_hash,result_code,attempts) ON cp.rights_publications TO cp_api;

-- Equipamentos: o gateway enxerga os do próprio conector (texto da V042 mais o gateway).
DROP POLICY access_devices_read ON cp.access_devices;
CREATE POLICY access_devices_read ON cp.access_devices AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage')
  OR platform.has_technical_scope('installation.bind_channel') OR platform.has_technical_scope('installation.commission')
  OR EXISTS(SELECT 1 FROM cp.access_credentials c WHERE c.tenant_id=access_devices.tenant_id AND c.connector_id=access_devices.connector_id)
  OR (cp.edge_credential() IS NOT NULL AND connector_id=cp.edge_connector()));
-- Conectores: o MASTER com gateway.manage vê o conector ao qual liga o gateway (texto da V042 mais gateway.manage).
DROP POLICY access_connectors_read ON cp.access_connectors;
CREATE POLICY access_connectors_read ON cp.access_connectors AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage')
  OR platform.has_technical_scope('gateway.manage'));

-- Condomínio ativo visto pelo gateway autenticado (a transação da máquina não tem conta nem vínculo).
CREATE POLICY tenant_edge_read ON platform.tenants FOR SELECT TO cp_api
 USING(active AND id=platform.current_tenant_id() AND cp.edge_agent_id() IS NOT NULL);

-- 6. Trilha EDGE_AGENT_* só do MASTER com gateway.manage, em nome da própria conta.
CREATE POLICY audit_edge_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'EDGE\_%' OR (actor_id=platform.current_actor_id() AND platform.has_technical_scope('gateway.manage')
  AND resource_type='EDGE_AGENT'));
