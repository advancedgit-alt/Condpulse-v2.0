-- AC-006 (pacote Acesso Facial v1.1): evidência de idade minimizada e parâmetros do acesso facial por condomínio.
--  - cp.facial_access_settings: parâmetros por condomínio (critério de quem nasceu em 29/02, cadastro facial de menores,
--    validade da evidência). Sem linha, valem os padrões provisórios (DECISOES_PENDENTES.md): 01/03, menores desligado e
--    365 dias.
--  - cp.age_evidence: atestação de predicado (ex.: "18 ou mais", verificada) ou data de nascimento CIFRADA pela API
--    (AES-GCM, chave fora do banco, só referência e versão aqui). Nunca data em claro, nunca estimativa pela imagem.
--    Uma evidência válida por sujeito; correção cadastral substitui (SUPERSEDED) na mesma transação; nada é apagado.
-- A projeção para gestão é só "atende / não atende / desconhecido" para uma idade mínima; portaria e worker não leem.

CREATE TABLE cp.facial_access_settings (
 tenant_id uuid PRIMARY KEY REFERENCES platform.tenants(id),
 leap_day_rule text NOT NULL DEFAULT 'MARCH_1' CHECK(leap_day_rule IN ('MARCH_1','FEBRUARY_28')),
 minor_face_enrollment_enabled boolean NOT NULL DEFAULT false,
 age_evidence_validity_days integer NOT NULL DEFAULT 365 CHECK(age_evidence_validity_days BETWEEN 1 AND 3660),
 updated_by uuid NOT NULL REFERENCES platform.accounts(id),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 1 CHECK(version>=1)
);

CREATE TABLE cp.age_evidence (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 subject_id uuid NOT NULL,
 method text NOT NULL CHECK(method IN ('AGE_PREDICATE_ATTESTATION','ENCRYPTED_BIRTH_DATE')),
 minimum_age smallint CHECK(minimum_age IS NULL OR minimum_age BETWEEN 0 AND 130),
 maximum_age smallint CHECK(maximum_age IS NULL OR maximum_age BETWEEN 0 AND 130),
 predicate_result boolean,
 -- Envelope AES-GCM (IV de 12 bytes + texto cifrado + etiqueta), ligado ao condomínio, sujeito e id da evidência.
 birth_date_ciphertext bytea CHECK(birth_date_ciphertext IS NULL OR octet_length(birth_date_ciphertext) BETWEEN 30 AND 128),
 encryption_key_ref text CHECK(encryption_key_ref IS NULL OR encryption_key_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 encryption_key_version text CHECK(encryption_key_version IS NULL OR encryption_key_version ~ '^[A-Za-z0-9._-]{1,64}$'),
 verification_method text NOT NULL CHECK(verification_method IN ('DOCUMENT_REVIEW','TRUSTED_EXISTING_RECORD')),
 source_evidence_ref text NOT NULL CHECK(source_evidence_ref ~ '^[A-Za-z0-9._:-]{8,120}$'),
 verified_by_account_id uuid NOT NULL REFERENCES platform.accounts(id),
 verified_at timestamptz NOT NULL DEFAULT now(),
 valid_until timestamptz NOT NULL,
 status text NOT NULL DEFAULT 'VALID' CHECK(status IN ('VALID','REVOKED','SUPERSEDED')),
 supersedes_evidence_id uuid,
 status_reason text CHECK(status_reason IS NULL OR length(btrim(status_reason)) BETWEEN 3 AND 300),
 status_changed_by uuid REFERENCES platform.accounts(id),
 status_changed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,id,subject_id),
 FOREIGN KEY(tenant_id,subject_id) REFERENCES cp.access_subjects(tenant_id,id),
 FOREIGN KEY(tenant_id,supersedes_evidence_id,subject_id) REFERENCES cp.age_evidence(tenant_id,id,subject_id),
 CHECK(method<>'AGE_PREDICATE_ATTESTATION' OR ((minimum_age IS NOT NULL OR maximum_age IS NOT NULL) AND predicate_result IS NOT NULL
  AND birth_date_ciphertext IS NULL AND encryption_key_ref IS NULL AND encryption_key_version IS NULL)),
 CHECK(method<>'ENCRYPTED_BIRTH_DATE' OR (birth_date_ciphertext IS NOT NULL AND encryption_key_ref IS NOT NULL
  AND encryption_key_version IS NOT NULL AND minimum_age IS NULL AND maximum_age IS NULL AND predicate_result IS NULL)),
 CHECK(maximum_age IS NULL OR minimum_age IS NULL OR maximum_age>=minimum_age),
 CHECK(valid_until>verified_at),
 CHECK((status='VALID')=(status_reason IS NULL AND status_changed_at IS NULL))
);
CREATE UNIQUE INDEX age_evidence_one_valid ON cp.age_evidence(tenant_id,subject_id) WHERE status='VALID';
CREATE INDEX age_evidence_current ON cp.age_evidence(tenant_id,subject_id,status,valid_until);

-- Nascimento VALID na versão 0, verificada agora pela própria conta; a evidência anterior citada precisa ter sido
-- substituída na mesma transação. Conteúdo nunca muda; só VALID → REVOKED/SUPERSEDED, com motivo e versão+1.
CREATE FUNCTION cp.age_evidence_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'VALID' OR NEW.version<>0 THEN RAISE EXCEPTION 'evidência nasce válida na versão 0' USING ERRCODE='23514'; END IF;
  IF NEW.valid_until>now()+interval '3660 days' THEN RAISE EXCEPTION 'validade longa demais' USING ERRCODE='23514'; END IF;
  IF NEW.supersedes_evidence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cp.age_evidence e WHERE e.tenant_id=NEW.tenant_id
     AND e.id=NEW.supersedes_evidence_id AND e.subject_id=NEW.subject_id AND e.status='SUPERSEDED') THEN
   RAISE EXCEPTION 'evidência anterior precisa ser substituída antes' USING ERRCODE='23514';
  END IF;
  NEW.verified_at:=now(); NEW.created_at:=clock_timestamp();
  RETURN NEW;
 END IF;
 IF OLD.status<>'VALID' OR NEW.status='VALID' OR NEW.version<>OLD.version+1 OR NEW.status_reason IS NULL
   OR (NEW.id,NEW.tenant_id,NEW.subject_id,NEW.method,NEW.minimum_age,NEW.maximum_age,NEW.predicate_result,NEW.birth_date_ciphertext,
       NEW.encryption_key_ref,NEW.encryption_key_version,NEW.verification_method,NEW.source_evidence_ref,NEW.verified_by_account_id,
       NEW.verified_at,NEW.valid_until,NEW.supersedes_evidence_id,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.subject_id,OLD.method,OLD.minimum_age,OLD.maximum_age,OLD.predicate_result,OLD.birth_date_ciphertext,
       OLD.encryption_key_ref,OLD.encryption_key_version,OLD.verification_method,OLD.source_evidence_ref,OLD.verified_by_account_id,
       OLD.verified_at,OLD.valid_until,OLD.supersedes_evidence_id,OLD.created_at) THEN
  RAISE EXCEPTION 'evidência de idade não muda; só é revogada ou substituída' USING ERRCODE='23514';
 END IF;
 NEW.status_changed_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.age_evidence_guard() FROM PUBLIC;
CREATE TRIGGER age_evidence_guard BEFORE INSERT OR UPDATE ON cp.age_evidence FOR EACH ROW EXECUTE FUNCTION cp.age_evidence_guard();

-- Parâmetros: versão sequencial e autoria da própria conta, horário do banco.
CREATE FUNCTION cp.facial_access_settings_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' AND NEW.version<>1 OR TG_OP='UPDATE' AND (NEW.version<>OLD.version+1 OR NEW.tenant_id<>OLD.tenant_id) THEN
  RAISE EXCEPTION 'parâmetros mudam pela versão lida' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.facial_access_settings_guard() FROM PUBLIC;
CREATE TRIGGER facial_access_settings_guard BEFORE INSERT OR UPDATE ON cp.facial_access_settings
 FOR EACH ROW EXECUTE FUNCTION cp.facial_access_settings_guard();

ALTER TABLE cp.facial_access_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.facial_access_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.age_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.age_evidence FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.facial_access_settings,cp.age_evidence FROM PUBLIC;
CREATE POLICY tenant_boundary ON cp.facial_access_settings TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.age_evidence TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Parâmetros: gestão lê e altera; portaria e MASTER de implantação leem (para orientar a operação).
CREATE POLICY facial_settings_read ON cp.facial_access_settings AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read'));
CREATE POLICY facial_settings_write ON cp.facial_access_settings AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('memberships.manage') AND updated_by=platform.current_actor_id());
CREATE POLICY facial_settings_update ON cp.facial_access_settings AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('memberships.manage'))
 WITH CHECK(platform.has_capability('memberships.manage') AND updated_by=platform.current_actor_id());
GRANT SELECT ON cp.facial_access_settings TO cp_api;
GRANT INSERT(tenant_id,leap_day_rule,minor_face_enrollment_enabled,age_evidence_validity_days,updated_by,version),
 UPDATE(leap_day_rule,minor_face_enrollment_enabled,age_evidence_validity_days,updated_by,version) ON cp.facial_access_settings TO cp_api;
GRANT SELECT(tenant_id,leap_day_rule,minor_face_enrollment_enabled,age_evidence_validity_days,version) ON cp.facial_access_settings TO cp_worker;

-- Evidência: só gestão (memberships.manage) ou person.review técnico delegado verificam e leem; portaria, morador e
-- worker não leem (a data cifrada não sai do serviço). Autoria é a própria conta.
CREATE FUNCTION cp.age_reviewer() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_capability('memberships.manage') OR platform.has_technical_scope('person.review') $$;
REVOKE ALL ON FUNCTION cp.age_reviewer() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.age_reviewer() TO cp_api;
CREATE POLICY age_evidence_read ON cp.age_evidence AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.age_reviewer());
CREATE POLICY age_evidence_create ON cp.age_evidence AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(cp.age_reviewer() AND verified_by_account_id=platform.current_actor_id());
CREATE POLICY age_evidence_close ON cp.age_evidence AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.age_reviewer()) WITH CHECK(cp.age_reviewer() AND status_changed_by=platform.current_actor_id());
GRANT SELECT ON cp.age_evidence TO cp_api;
GRANT INSERT(id,tenant_id,subject_id,method,minimum_age,maximum_age,predicate_result,birth_date_ciphertext,encryption_key_ref,
 encryption_key_version,verification_method,source_evidence_ref,verified_by_account_id,valid_until,supersedes_evidence_id) ON cp.age_evidence TO cp_api;
GRANT UPDATE(status,status_reason,status_changed_by,version) ON cp.age_evidence TO cp_api;

CREATE POLICY audit_age_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'AGE\_EVIDENCE\_%' OR (actor_id=platform.current_actor_id() AND cp.age_reviewer() AND resource_type='AGE_EVIDENCE'));
CREATE POLICY audit_facial_settings ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action<>'FACIAL_SETTINGS_UPDATED' OR (actor_id=platform.current_actor_id() AND platform.has_capability('memberships.manage')));
