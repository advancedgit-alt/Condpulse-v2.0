-- CP-047: eventos dos equipamentos recebidos do parceiro, deduplicados e mapeados sem inventar dado. O worker busca os
-- eventos de cada conector ativo pelo adaptador (getEvents, janela limitada) dentro do contexto do condomínio dono do
-- conector: o condomínio vem do conector autenticado, nunca do conteúdo do evento. Equipamento e credencial são
-- procurados só entre os do mesmo conector; evento de equipamento desconhecido fica em quarentena, sem unidade.
-- Direção, evidência e versão ausentes continuam ausentes (null); nada de passagem ou presença inferida. Repetição do
-- mesmo eventId não duplica (chave única por conector). Nenhum parceiro físico está contratado (CP-044).

CREATE TABLE cp.device_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 connector_id uuid NOT NULL,
 external_event_id text NOT NULL CHECK(length(external_event_id) BETWEEN 1 AND 200),
 device_id uuid,
 credential_id uuid,
 unit_id uuid,
 kind text NOT NULL CHECK(kind IN ('AUTHORIZED','DENIED','RELAY_TRIGGERED','DOOR_OPENED','PASSAGE_DETECTED','CREDENTIAL_APPLIED',
  'CREDENTIAL_REJECTED','CREDENTIAL_REVOKED','DEVICE_ONLINE','DEVICE_OFFLINE')),
 direction text CHECK(direction IS NULL OR direction IN ('ENTRY','EXIT','UNKNOWN')),
 evidence text CHECK(evidence IS NULL OR evidence ~ '^[A-Z0-9_]{1,60}$'),
 applied_version bigint CHECK(applied_version IS NULL OR applied_version>=1),
 occurred_at timestamptz NOT NULL,
 received_at timestamptz NOT NULL,
 ingested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 state text NOT NULL CHECK(state IN ('ACCEPTED','QUARANTINED')),
 quarantine_reason text CHECK(quarantine_reason IS NULL OR quarantine_reason ~ '^[A-Z0-9_]{1,60}$'),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,connector_id,external_event_id),
 FOREIGN KEY(tenant_id,connector_id) REFERENCES cp.access_connectors(tenant_id,id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES cp.access_devices(tenant_id,id),
 FOREIGN KEY(tenant_id,credential_id) REFERENCES cp.access_credentials(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK((state='QUARANTINED')=(quarantine_reason IS NOT NULL)),
 CHECK(state='QUARANTINED' OR device_id IS NOT NULL),
 CHECK(credential_id IS NOT NULL OR unit_id IS NULL),
 CHECK(isfinite(occurred_at) AND isfinite(received_at))
);
CREATE INDEX device_events_recent ON cp.device_events(tenant_id,occurred_at DESC,id);
CREATE INDEX device_events_by_unit ON cp.device_events(tenant_id,unit_id,occurred_at DESC,id) WHERE unit_id IS NOT NULL;
CREATE INDEX device_events_by_credential ON cp.device_events(tenant_id,credential_id,occurred_at DESC) WHERE credential_id IS NOT NULL;

-- Posição da busca por conector (horário de recebimento do último evento gravado).
CREATE TABLE cp.device_event_cursors (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 connector_id uuid NOT NULL,
 last_received_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,connector_id),
 FOREIGN KEY(tenant_id,connector_id) REFERENCES cp.access_connectors(tenant_id,id)
);

-- Equipamento e credencial do mesmo conector do evento; unidade sempre a da credencial (nunca do conteúdo do evento).
CREATE FUNCTION cp.device_events_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE credential_unit uuid; credential_connector uuid;
BEGIN
 IF NEW.device_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cp.access_devices d
   WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.device_id AND d.connector_id=NEW.connector_id) THEN
  RAISE EXCEPTION 'equipamento de outro conector' USING ERRCODE='23514';
 END IF;
 IF NEW.credential_id IS NOT NULL THEN
  SELECT c.unit_id,c.connector_id INTO credential_unit,credential_connector FROM cp.access_credentials c
   WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.credential_id;
  IF credential_connector IS DISTINCT FROM NEW.connector_id THEN
   RAISE EXCEPTION 'credencial de outro conector' USING ERRCODE='23514';
  END IF;
  NEW.unit_id:=credential_unit;
 ELSE
  NEW.unit_id:=NULL;
 END IF;
 NEW.ingested_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.device_events_guard() FROM PUBLIC;
CREATE TRIGGER device_events_guard BEFORE INSERT ON cp.device_events FOR EACH ROW EXECUTE FUNCTION cp.device_events_guard();

REVOKE ALL ON cp.device_events,cp.device_event_cursors FROM PUBLIC;
ALTER TABLE cp.device_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.device_events FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.device_event_cursors ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.device_event_cursors FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.device_events TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.device_event_cursors TO cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Aplicação: só leitura. Portaria e gestão veem o condomínio; a pessoa vê os eventos das próprias credenciais (a leitura
-- da credencial já é limitada ao próprio vínculo). Nada é alterado nem apagado.
GRANT SELECT ON cp.device_events TO cp_api;
CREATE POLICY device_events_read ON cp.device_events AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR platform.has_capability('memberships.manage')
  OR (credential_id IS NOT NULL AND EXISTS(SELECT 1 FROM cp.access_credentials c WHERE c.tenant_id=device_events.tenant_id AND c.id=device_events.credential_id)));

-- Worker: grava eventos (sem escolher unidade nem horário de ingestão) e a própria posição de busca.
GRANT SELECT(id,tenant_id,connector_id,external_event_id) ON cp.device_events TO cp_worker;
GRANT INSERT(tenant_id,connector_id,external_event_id,device_id,credential_id,kind,direction,evidence,applied_version,occurred_at,received_at,
 state,quarantine_reason) ON cp.device_events TO cp_worker;
GRANT SELECT,INSERT(tenant_id,connector_id,last_received_at),UPDATE(last_received_at,updated_at) ON cp.device_event_cursors TO cp_worker;
GRANT SELECT(id,tenant_id,connector_id,unit_id) ON cp.access_credentials TO cp_worker;
