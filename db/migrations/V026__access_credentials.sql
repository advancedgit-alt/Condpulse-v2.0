-- CP-046: credenciais de acesso com estado desejado versionado e revogação prioritária. O CondPulse guarda o que deve
-- valer (ACTIVE ou REVOKED, com versão crescente); o equipamento informa o que aplicou. Uma confirmação só vale para a
-- versão e a ação desejadas no momento da gravação: confirmação atrasada de versão antiga nunca reativa credencial
-- revogada. Revogada é final (nova credencial para voltar a liberar). Nenhum parceiro físico está contratado: conector e
-- equipamentos só são cadastrados pela operação da plataforma (sem grant de escrita para a aplicação), e o único
-- adaptador existente é o simulado (CP-043). Sem foto, template facial ou número de cartão: kind FACE fica para CP-045.

-- Conector de acesso por condomínio (plataforma do parceiro ou simulador de testes).
CREATE TABLE cp.access_connectors (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 provider text NOT NULL CHECK(provider IN ('SIMULATED','PARTNER')),
 label text NOT NULL CHECK(length(btrim(label)) BETWEEN 1 AND 120),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id)
);
-- Equipamento de destino das credenciais (identificador do parceiro, opaco).
CREATE TABLE cp.access_devices (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 connector_id uuid NOT NULL,
 external_id text NOT NULL CHECK(external_id ~ '^[A-Za-z0-9._:-]{1,120}$'),
 label text NOT NULL CHECK(length(btrim(label)) BETWEEN 1 AND 120),
 direction text NOT NULL DEFAULT 'UNKNOWN' CHECK(direction IN ('ENTRY','EXIT','BOTH','UNKNOWN')),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,connector_id,external_id),
 FOREIGN KEY(tenant_id,connector_id) REFERENCES cp.access_connectors(tenant_id,id)
);
CREATE INDEX access_devices_by_connector ON cp.access_devices(tenant_id,connector_id,status);

-- Estado desejado. external_ref é a referência opaca enviada ao parceiro (sem nome, unidade ou documento).
CREATE TABLE cp.access_credentials (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 connector_id uuid NOT NULL,
 membership_id uuid NOT NULL,
 unit_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('CARD','QR')),
 label text NOT NULL CHECK(length(btrim(label)) BETWEEN 1 AND 80),
 external_ref text NOT NULL DEFAULT 'cpc_'||replace(gen_random_uuid()::text,'-','') CHECK(external_ref ~ '^cpc_[0-9a-f]{32}$'),
 desired_state text NOT NULL DEFAULT 'ACTIVE' CHECK(desired_state IN ('ACTIVE','REVOKED')),
 desired_version bigint NOT NULL DEFAULT 1 CHECK(desired_version>=1),
 revoke_reason text CHECK(revoke_reason IS NULL OR length(btrim(revoke_reason)) BETWEEN 1 AND 300),
 revoked_by uuid REFERENCES platform.accounts(id),
 revoked_at timestamptz,
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(connector_id,external_ref),
 FOREIGN KEY(tenant_id,connector_id) REFERENCES cp.access_connectors(tenant_id,id),
 FOREIGN KEY(tenant_id,membership_id) REFERENCES cp.memberships(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK((desired_state='REVOKED')=(revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoke_reason IS NOT NULL)),
 CHECK(desired_state='REVOKED' OR desired_version=1)
);
CREATE INDEX access_credentials_by_unit ON cp.access_credentials(tenant_id,unit_id,created_at DESC,id);
CREATE INDEX access_credentials_by_membership ON cp.access_credentials(tenant_id,membership_id) WHERE desired_state='ACTIVE';

-- Resultado por credencial e equipamento. attempted_*: último comando enviado; observed_*: só o que o equipamento
-- confirmou (APPLIED). Ambos precisam corresponder ao estado desejado ATUAL no momento da gravação.
CREATE TABLE cp.credential_syncs (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 credential_id uuid NOT NULL,
 device_id uuid NOT NULL,
 attempted_version bigint NOT NULL CHECK(attempted_version>=1),
 attempted_action text NOT NULL CHECK(attempted_action IN ('APPLY','REVOKE')),
 outcome text NOT NULL CHECK(outcome IN ('ACCEPTED','APPLIED','REJECTED','RATE_LIMITED','UNKNOWN','UNAVAILABLE')),
 observed_version bigint CHECK(observed_version IS NULL OR observed_version>=1),
 observed_action text CHECK(observed_action IS NULL OR observed_action IN ('APPLY','REVOKE')),
 confirmed_at timestamptz,
 attempts integer NOT NULL DEFAULT 1 CHECK(attempts BETWEEN 1 AND 1000000),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 last_reason text CHECK(last_reason IS NULL OR length(last_reason) BETWEEN 1 AND 200),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,credential_id,device_id),
 FOREIGN KEY(tenant_id,credential_id) REFERENCES cp.access_credentials(tenant_id,id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES cp.access_devices(tenant_id,id),
 CHECK((observed_version IS NULL)=(observed_action IS NULL) AND (observed_version IS NULL)=(confirmed_at IS NULL))
);

-- Ação que corresponde ao estado desejado.
CREATE FUNCTION cp.credential_action(state text) RETURNS text
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$ SELECT CASE state WHEN 'REVOKED' THEN 'REVOKE' ELSE 'APPLY' END $$;
REVOKE ALL ON FUNCTION cp.credential_action(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.credential_action(text) TO cp_api,cp_worker;

-- Vínculo residencial vigente em unidade ativa que pode receber credencial (a gestão não lê contas de terceiros; vínculo
-- de conta desativada é encerrado pela gestão e revoga as credenciais).
CREATE FUNCTION cp.credential_holder(target_tenant uuid,target_membership uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.id=target_membership AND m.unit_id=target_unit AND m.status='ACTIVE'
   AND m.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT') AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) $$;
REVOKE ALL ON FUNCTION cp.credential_holder(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.credential_holder(uuid,uuid,uuid) TO cp_api;

-- Credencial: criação sempre ACTIVE na versão 1; a única mudança é ACTIVE→REVOKED com versão+1 e horário do banco.
CREATE FUNCTION cp.access_credentials_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.desired_state<>'ACTIVE' OR NEW.desired_version<>1 OR NEW.version<>0 THEN
   RAISE EXCEPTION 'credencial nasce ativa na versão 1' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF OLD.desired_state<>'ACTIVE' OR NEW.desired_state<>'REVOKED' OR NEW.desired_version<>OLD.desired_version+1 OR NEW.version<>OLD.version+1
   OR (NEW.tenant_id,NEW.connector_id,NEW.membership_id,NEW.unit_id,NEW.kind,NEW.label,NEW.external_ref,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.tenant_id,OLD.connector_id,OLD.membership_id,OLD.unit_id,OLD.kind,OLD.label,OLD.external_ref,OLD.created_by,OLD.created_at) THEN
  RAISE EXCEPTION 'credencial revogada não volta a valer' USING ERRCODE='23514';
 END IF;
 NEW.revoked_at:=clock_timestamp(); NEW.updated_at:=NEW.revoked_at;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_credentials_guard() FROM PUBLIC;
CREATE TRIGGER access_credentials_guard BEFORE INSERT OR UPDATE ON cp.access_credentials
 FOR EACH ROW EXECUTE FUNCTION cp.access_credentials_guard();

-- Resultado: comando e confirmação só para o estado desejado atual; APPLIED grava a confirmação daquele comando com o
-- horário do banco; outro resultado mantém a última confirmação. Revogação concorrente com a gravação não se perde: o
-- estado desejado é a referência e a diferença volta como pendência na próxima passada.
CREATE FUNCTION cp.credential_syncs_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE c record;
BEGIN
 SELECT desired_state,desired_version,connector_id INTO c FROM cp.access_credentials
  WHERE tenant_id=NEW.tenant_id AND id=NEW.credential_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'credencial inexistente' USING ERRCODE='23503'; END IF;
 IF NOT EXISTS(SELECT 1 FROM cp.access_devices d WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.device_id AND d.connector_id=c.connector_id) THEN
  RAISE EXCEPTION 'equipamento de outro conector' USING ERRCODE='23514';
 END IF;
 IF NEW.attempted_version<>c.desired_version OR NEW.attempted_action<>cp.credential_action(c.desired_state) THEN
  RAISE EXCEPTION 'resultado de versão obsoleta' USING ERRCODE='23514';
 END IF;
 IF NEW.outcome='APPLIED' THEN
  NEW.observed_version:=NEW.attempted_version; NEW.observed_action:=NEW.attempted_action; NEW.confirmed_at:=clock_timestamp();
 ELSE
  NEW.observed_version:=CASE WHEN TG_OP='UPDATE' THEN OLD.observed_version END;
  NEW.observed_action:=CASE WHEN TG_OP='UPDATE' THEN OLD.observed_action END;
  NEW.confirmed_at:=CASE WHEN TG_OP='UPDATE' THEN OLD.confirmed_at END;
 END IF;
 IF TG_OP='UPDATE' AND (NEW.tenant_id,NEW.credential_id,NEW.device_id) IS DISTINCT FROM (OLD.tenant_id,OLD.credential_id,OLD.device_id) THEN
  RAISE EXCEPTION 'destino não muda' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.credential_syncs_guard() FROM PUBLIC;
CREATE TRIGGER credential_syncs_guard BEFORE INSERT OR UPDATE ON cp.credential_syncs
 FOR EACH ROW EXECUTE FUNCTION cp.credential_syncs_guard();

-- Aplicação (cp_api): gestão (memberships.manage) cadastra e revoga; portaria vê para orientar a contingência; a pessoa
-- vê as próprias credenciais. Conector, equipamento e resultado são só leitura para a aplicação.
REVOKE ALL ON cp.access_connectors,cp.access_devices,cp.access_credentials,cp.credential_syncs FROM PUBLIC;
GRANT SELECT ON cp.access_connectors,cp.access_devices,cp.access_credentials,cp.credential_syncs TO cp_api;
GRANT INSERT(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) ON cp.access_credentials TO cp_api;
GRANT UPDATE(desired_state,desired_version,revoke_reason,revoked_by,version) ON cp.access_credentials TO cp_api;
ALTER TABLE cp.access_connectors ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_connectors FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.access_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_devices FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.access_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_credentials FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.credential_syncs ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.credential_syncs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.access_connectors TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.access_devices TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.access_credentials TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.credential_syncs TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

CREATE FUNCTION cp.credential_own(target_tenant uuid,target_membership uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m WHERE m.tenant_id=target_tenant AND m.id=target_membership
  AND m.account_id=platform.current_actor_id()) $$;
REVOKE ALL ON FUNCTION cp.credential_own(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.credential_own(uuid,uuid) TO cp_api;

CREATE POLICY access_connectors_read ON cp.access_connectors AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate'));
-- A pessoa vê os equipamentos de destino das próprias credenciais (a leitura da credencial já é limitada pela política).
CREATE POLICY access_devices_read ON cp.access_devices AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR EXISTS(SELECT 1 FROM cp.access_credentials c WHERE c.tenant_id=access_devices.tenant_id AND c.connector_id=access_devices.connector_id));
CREATE POLICY access_credentials_read ON cp.access_credentials AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate') OR cp.credential_own(tenant_id,membership_id));
CREATE POLICY credential_syncs_read ON cp.credential_syncs AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR EXISTS(SELECT 1 FROM cp.access_credentials c WHERE c.tenant_id=credential_syncs.tenant_id AND c.id=credential_syncs.credential_id));
CREATE POLICY access_credentials_create ON cp.access_credentials AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('memberships.manage') AND created_by=platform.current_actor_id()
  AND cp.credential_holder(tenant_id,membership_id,unit_id)
  AND EXISTS(SELECT 1 FROM cp.access_connectors k WHERE k.tenant_id=access_credentials.tenant_id AND k.id=access_credentials.connector_id AND k.status='ACTIVE'));
CREATE POLICY access_credentials_revoke ON cp.access_credentials AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('memberships.manage'))
 WITH CHECK(platform.has_capability('memberships.manage') AND desired_state='REVOKED' AND revoked_by=platform.current_actor_id());

-- Eventos e trilha de credencial só pela gestão, sobre credencial existente, em nome da própria conta.
CREATE POLICY outbox_credential_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type NOT LIKE 'CREDENTIAL\_%' OR (platform.has_capability('memberships.manage') AND EXISTS(SELECT 1 FROM cp.access_credentials c
  WHERE c.tenant_id=cp.outbox.tenant_id AND c.id=cp.outbox.aggregate_id)));
CREATE POLICY audit_credential_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'CREDENTIAL\_%' OR (platform.has_capability('memberships.manage') AND actor_id=platform.current_actor_id()
  AND resource_type='ACCESS_CREDENTIAL' AND EXISTS(SELECT 1 FROM cp.access_credentials c WHERE c.tenant_id=cp.audit_log.tenant_id
   AND c.id=cp.audit_log.resource_id)));

-- Worker (cp_worker, sem conta): lê o estado desejado e os destinos e grava só o resultado por destino; não cria nem
-- revoga credencial, não muda conector nem equipamento.
GRANT SELECT(id,tenant_id,provider,status) ON cp.access_connectors TO cp_worker;
GRANT SELECT(id,tenant_id,connector_id,external_id,status) ON cp.access_devices TO cp_worker;
GRANT SELECT(id,tenant_id,connector_id,external_ref,desired_state,desired_version,revoked_at,created_at) ON cp.access_credentials TO cp_worker;
GRANT SELECT,INSERT(tenant_id,credential_id,device_id,attempted_version,attempted_action,outcome,attempts,next_attempt_at,last_reason),
 UPDATE(attempted_version,attempted_action,outcome,attempts,next_attempt_at,last_reason) ON cp.credential_syncs TO cp_worker;

CREATE INDEX credential_syncs_due ON cp.credential_syncs(tenant_id,next_attempt_at);
