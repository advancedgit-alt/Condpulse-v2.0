-- CP-048: saúde dos equipamentos e do conector. O worker consulta o adaptador (health) de cada conector ativo e grava a
-- última observação no condomínio dono do conector; o condomínio nunca vem do conteúdo da resposta. O banco escolhe os
-- horários de observação e de mudança de estado: a aplicação mostra "fora do ar" quando o equipamento disse que está, e
-- "sem informação recente" quando a observação envelheceu ou o conector não respondeu (nada de "online" presumido).
-- Relógio do equipamento e fila do parceiro são guardados como informados, sem correção. Nenhum parceiro físico está
-- contratado: só o adaptador simulado (CP-043) responde, e apenas nos testes.

CREATE TABLE cp.access_connector_health (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 connector_id uuid NOT NULL,
 reachable boolean NOT NULL,
 pending_commands integer CHECK(pending_commands IS NULL OR pending_commands BETWEEN 0 AND 1000000),
 observed_delay_ms bigint CHECK(observed_delay_ms IS NULL OR observed_delay_ms BETWEEN 0 AND 86400000),
 failure_reason text CHECK(failure_reason IS NULL OR failure_reason ~ '^[A-Z0-9_]{1,60}$'),
 checked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 last_reachable_at timestamptz,
 PRIMARY KEY(tenant_id,connector_id),
 FOREIGN KEY(tenant_id,connector_id) REFERENCES cp.access_connectors(tenant_id,id),
 CHECK(reachable=(failure_reason IS NULL)),
 CHECK(reachable OR (pending_commands IS NULL AND observed_delay_ms IS NULL)),
 CHECK(NOT reachable OR last_reachable_at=checked_at)
);

CREATE TABLE cp.access_device_health (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 device_id uuid NOT NULL,
 online boolean NOT NULL,
 last_heartbeat timestamptz CHECK(last_heartbeat IS NULL OR isfinite(last_heartbeat)),
 clock_skew_ms bigint CHECK(clock_skew_ms IS NULL OR clock_skew_ms BETWEEN -86400000 AND 86400000),
 observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 -- Desde quando o equipamento está no estado atual (online/fora do ar), pelo relógio do banco.
 state_since timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,device_id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES cp.access_devices(tenant_id,id),
 CHECK(state_since<=observed_at)
);

-- Horários sempre do banco; identidade da linha não muda.
CREATE FUNCTION cp.access_connector_health_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.tenant_id,NEW.connector_id) IS DISTINCT FROM (OLD.tenant_id,OLD.connector_id) THEN
  RAISE EXCEPTION 'conector não muda' USING ERRCODE='23514';
 END IF;
 NEW.checked_at:=clock_timestamp();
 NEW.last_reachable_at:=CASE WHEN NEW.reachable THEN NEW.checked_at WHEN TG_OP='UPDATE' THEN OLD.last_reachable_at END;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_connector_health_guard() FROM PUBLIC;
CREATE TRIGGER access_connector_health_guard BEFORE INSERT OR UPDATE ON cp.access_connector_health
 FOR EACH ROW EXECUTE FUNCTION cp.access_connector_health_guard();

CREATE FUNCTION cp.access_device_health_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.tenant_id,NEW.device_id) IS DISTINCT FROM (OLD.tenant_id,OLD.device_id) THEN
  RAISE EXCEPTION 'equipamento não muda' USING ERRCODE='23514';
 END IF;
 NEW.observed_at:=clock_timestamp();
 NEW.state_since:=CASE WHEN TG_OP='UPDATE' AND OLD.online=NEW.online THEN OLD.state_since ELSE NEW.observed_at END;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_device_health_guard() FROM PUBLIC;
CREATE TRIGGER access_device_health_guard BEFORE INSERT OR UPDATE ON cp.access_device_health
 FOR EACH ROW EXECUTE FUNCTION cp.access_device_health_guard();

REVOKE ALL ON cp.access_connector_health,cp.access_device_health FROM PUBLIC;
ALTER TABLE cp.access_connector_health ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_connector_health FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.access_device_health ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_device_health FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.access_connector_health TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.access_device_health TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Aplicação: só leitura, para a gestão (memberships.manage) e a portaria (concierge.operate), que usa a informação para
-- orientar a contingência. Morador não vê a saúde dos equipamentos.
GRANT SELECT ON cp.access_connector_health,cp.access_device_health TO cp_api;
CREATE POLICY access_connector_health_read ON cp.access_connector_health AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate'));
CREATE POLICY access_device_health_read ON cp.access_device_health AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate'));

-- Worker: grava só o que o adaptador informou; horários vêm do banco; nunca apaga.
GRANT SELECT,INSERT(tenant_id,connector_id,reachable,pending_commands,observed_delay_ms,failure_reason),
 UPDATE(reachable,pending_commands,observed_delay_ms,failure_reason) ON cp.access_connector_health TO cp_worker;
GRANT SELECT,INSERT(tenant_id,device_id,online,last_heartbeat,clock_skew_ms),
 UPDATE(online,last_heartbeat,clock_skew_ms) ON cp.access_device_health TO cp_worker;
