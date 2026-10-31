-- AC-005 (pacote Acesso Facial v1.1): sujeito de acesso por condomínio, separado da conta, dos vínculos e das
-- credenciais, com contextos explícitos (sem união permissiva automática).
--  - cp.access_subjects: um por conta (ACCOUNT) ou pessoa visitante (VISITOR) em cada condomínio. A mesma conta em dois
--    condomínios tem dois sujeitos; nada é compartilhado. Bloqueio do sujeito é transversal e eleva restriction_epoch.
--  - cp.subject_contexts: vínculo concreto que fundamenta a tentativa. Tipo, unidade e vigência vêm da origem (vínculo,
--    convite ou autorização funcional) pelo gatilho; a origem continua a fonte canônica (source_version + função de
--    vigência), e revogar um contexto não bloqueia o sujeito.
--  - cp.access_credentials.subject_id: compatibilidade (opcional), preenchido pelo banco a partir do vínculo da credencial.
-- Nada é apagado. MANAGER e SUPPORT não geram contexto de acesso físico (cargo administrativo não é autorização física).

CREATE TABLE cp.access_subjects (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 origin_kind text NOT NULL CHECK(origin_kind IN ('ACCOUNT','VISITOR')),
 account_id uuid REFERENCES platform.accounts(id),
 visitor_id uuid,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','BLOCKED','ERASURE_PENDING','ARCHIVED')),
 restriction_epoch bigint NOT NULL DEFAULT 0 CHECK(restriction_epoch>=0),
 -- Identificador opaco e estável enviado ao fabricante: nunca CPF, unidade ou id global da conta.
 external_ref text NOT NULL DEFAULT 'cps_'||replace(gen_random_uuid()::text,'-','') CHECK(external_ref ~ '^cps_[0-9a-f]{32}$'),
 identity_verified_at timestamptz,
 identity_evidence_ref text CHECK(identity_evidence_ref IS NULL OR identity_evidence_ref ~ '^[A-Za-z0-9._:-]{8,120}$'),
 blocked_reason text CHECK(blocked_reason IS NULL OR length(btrim(blocked_reason)) BETWEEN 3 AND 300),
 blocked_by uuid REFERENCES platform.accounts(id),
 blocked_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,external_ref),
 FOREIGN KEY(tenant_id,visitor_id) REFERENCES cp.visitors(tenant_id,id),
 CHECK((origin_kind='ACCOUNT')=(account_id IS NOT NULL) AND (origin_kind='VISITOR')=(visitor_id IS NOT NULL)),
 CHECK((status='BLOCKED')=(blocked_reason IS NOT NULL AND blocked_by IS NOT NULL AND blocked_at IS NOT NULL))
);
CREATE UNIQUE INDEX access_subjects_account ON cp.access_subjects(tenant_id,account_id) WHERE account_id IS NOT NULL;
CREATE UNIQUE INDEX access_subjects_visitor ON cp.access_subjects(tenant_id,visitor_id) WHERE visitor_id IS NOT NULL;
CREATE INDEX access_subjects_status ON cp.access_subjects(tenant_id,status);

CREATE TABLE cp.subject_contexts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 subject_id uuid NOT NULL,
 context_kind text NOT NULL CHECK(context_kind IN ('RESIDENT','DEPENDENT','OWNER_NONRESIDENT','VISITOR','SERVICE_PROVIDER',
  'EMPLOYEE','EMERGENCY_RESPONDER')),
 source_kind text NOT NULL CHECK(source_kind IN ('MEMBERSHIP','VISIT_PERMIT','FUNCTIONAL_AUTHORIZATION')),
 membership_id uuid,
 permit_id uuid,
 functional_authorization_ref text CHECK(functional_authorization_ref IS NULL OR functional_authorization_ref ~ '^[A-Za-z0-9._:-]{8,120}$'),
 source_version bigint NOT NULL DEFAULT 0 CHECK(source_version>=0),
 unit_id uuid,
 guardian_subject_id uuid,
 -- Reautorização explícita da gestão depois de um contexto revogado da mesma origem (a materialização nunca desfaz revogação).
 reauthorizes_context_id uuid,
 approved_by_account_id uuid REFERENCES platform.accounts(id),
 -- now(): mesmo instante da transação usado por cp.subject_context_current.
 valid_from timestamptz NOT NULL DEFAULT now(),
 valid_until timestamptz,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED','EXPIRED','SUPERSEDED')),
 status_reason text CHECK(status_reason IS NULL OR length(btrim(status_reason)) BETWEEN 3 AND 300),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,id,subject_id),
 FOREIGN KEY(tenant_id,subject_id) REFERENCES cp.access_subjects(tenant_id,id),
 FOREIGN KEY(tenant_id,membership_id) REFERENCES cp.memberships(tenant_id,id),
 FOREIGN KEY(tenant_id,permit_id) REFERENCES cp.visit_permits(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,guardian_subject_id) REFERENCES cp.access_subjects(tenant_id,id),
 FOREIGN KEY(tenant_id,reauthorizes_context_id,subject_id) REFERENCES cp.subject_contexts(tenant_id,id,subject_id),
 -- Exatamente uma origem, coerente com source_kind.
 CHECK((source_kind='MEMBERSHIP')=(membership_id IS NOT NULL) AND (source_kind='VISIT_PERMIT')=(permit_id IS NOT NULL)
  AND (source_kind='FUNCTIONAL_AUTHORIZATION')=(functional_authorization_ref IS NOT NULL)),
 -- Visita e autorização funcional sempre têm fim; autorização funcional exige aprovador.
 CHECK(source_kind='MEMBERSHIP' OR valid_until IS NOT NULL),
 CHECK(source_kind<>'FUNCTIONAL_AUTHORIZATION' OR (approved_by_account_id IS NOT NULL
  AND context_kind IN ('EMPLOYEE','SERVICE_PROVIDER','EMERGENCY_RESPONDER'))),
 CHECK(valid_until IS NULL OR valid_until>valid_from),
 CHECK(guardian_subject_id IS NULL OR (guardian_subject_id<>subject_id AND context_kind='DEPENDENT')),
 CHECK((status='ACTIVE')=(status_reason IS NULL))
);
-- Um contexto ativo por origem e sujeito; reprocessar a mesma origem não duplica.
CREATE UNIQUE INDEX subject_contexts_membership ON cp.subject_contexts(tenant_id,subject_id,membership_id)
 WHERE membership_id IS NOT NULL AND status='ACTIVE';
CREATE UNIQUE INDEX subject_contexts_permit ON cp.subject_contexts(tenant_id,subject_id,permit_id)
 WHERE permit_id IS NOT NULL AND status='ACTIVE';
CREATE INDEX subject_contexts_current ON cp.subject_contexts(tenant_id,subject_id,status,valid_until);
CREATE INDEX subject_contexts_by_membership ON cp.subject_contexts(tenant_id,membership_id) WHERE membership_id IS NOT NULL;
CREATE INDEX subject_contexts_by_permit ON cp.subject_contexts(tenant_id,permit_id) WHERE permit_id IS NOT NULL;

-- Tipo de contexto derivado do papel do vínculo (sem escolha do cliente). OWNER com locação em vigor vira
-- OWNER_NONRESIDENT; sem locação, RESIDENT (decisão provisória registrada em DECISOES_PENDENTES.md). MANAGER e SUPPORT:
-- nulo (sem contexto físico).
CREATE FUNCTION cp.membership_context_kind(target_tenant uuid,target_membership uuid) RETURNS text
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT CASE m.role
   WHEN 'RESIDENT' THEN 'RESIDENT' WHEN 'RESPONSIBLE' THEN 'RESIDENT' WHEN 'DEPENDENT' THEN 'DEPENDENT'
   WHEN 'CONCIERGE' THEN 'EMPLOYEE'
   WHEN 'OWNER' THEN CASE WHEN EXISTS(SELECT 1 FROM cp.unit_rentals r WHERE r.tenant_id=m.tenant_id AND r.owner_membership_id=m.id
     AND r.ended_at IS NULL) THEN 'OWNER_NONRESIDENT' ELSE 'RESIDENT' END
   ELSE NULL END
  FROM cp.memberships m WHERE m.tenant_id=target_tenant AND m.id=target_membership $$;
REVOKE ALL ON FUNCTION cp.membership_context_kind(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.membership_context_kind(uuid,uuid) TO cp_api;

-- Contexto vigente agora: ativo, dentro da vigência, sujeito ativo e origem ainda igual à observada (mesma versão, ainda
-- válida, mesmo tipo derivado). Convite vencido não afeta contexto residencial: cada contexto é avaliado isoladamente.
CREATE FUNCTION cp.subject_context_current(target_tenant uuid,target_context uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce((SELECT c.status='ACTIVE' AND s.status='ACTIVE' AND c.valid_from<=now() AND (c.valid_until IS NULL OR c.valid_until>now())
   AND CASE c.source_kind
    WHEN 'MEMBERSHIP' THEN EXISTS(SELECT 1 FROM cp.memberships m LEFT JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id
      WHERE m.tenant_id=c.tenant_id AND m.id=c.membership_id AND m.status='ACTIVE' AND m.version=c.source_version
       AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now()) AND (m.unit_id IS NULL OR u.status='ACTIVE'))
      AND cp.membership_context_kind(c.tenant_id,c.membership_id)=c.context_kind
    WHEN 'VISIT_PERMIT' THEN EXISTS(SELECT 1 FROM cp.visit_permits p WHERE p.tenant_id=c.tenant_id AND p.id=c.permit_id
      AND p.state='ACTIVE' AND p.version=c.source_version AND p.valid_from<=now() AND p.valid_until>now())
    ELSE true END
  FROM cp.subject_contexts c JOIN cp.access_subjects s ON s.tenant_id=c.tenant_id AND s.id=c.subject_id
  WHERE c.tenant_id=target_tenant AND c.id=target_context),false) $$;
REVOKE ALL ON FUNCTION cp.subject_context_current(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.subject_context_current(uuid,uuid) TO cp_api;

-- Sujeito: nasce ACTIVE na época 0; origem e identificador nunca mudam. Bloqueio/desbloqueio sobem a época e a versão;
-- a época nunca desce. Pedido de exclusão e arquivamento são finais (fatias de privacidade).
CREATE FUNCTION cp.access_subjects_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.restriction_epoch<>0 OR NEW.version<>0 OR NEW.identity_verified_at IS NOT NULL THEN
   RAISE EXCEPTION 'sujeito nasce ativo na época 0' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.origin_kind,NEW.account_id,NEW.visitor_id,NEW.external_ref,NEW.created_at)
   IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.origin_kind,OLD.account_id,OLD.visitor_id,OLD.external_ref,OLD.created_at)
   OR NEW.version<>OLD.version+1 OR OLD.status IN ('ERASURE_PENDING','ARCHIVED') AND NEW.status<>'ARCHIVED' THEN
  RAISE EXCEPTION 'sujeito: origem fixa e versão sequencial' USING ERRCODE='23514';
 END IF;
 IF NEW.status IS DISTINCT FROM OLD.status THEN
  IF NEW.restriction_epoch<>OLD.restriction_epoch+1 THEN
   RAISE EXCEPTION 'mudança de restrição eleva a época em 1' USING ERRCODE='23514';
  END IF;
  NEW.blocked_at:=CASE WHEN NEW.status='BLOCKED' THEN clock_timestamp() END;
 ELSIF NEW.restriction_epoch<>OLD.restriction_epoch OR (NEW.blocked_reason,NEW.blocked_by) IS DISTINCT FROM (OLD.blocked_reason,OLD.blocked_by) THEN
  RAISE EXCEPTION 'época e bloqueio só mudam com o estado' USING ERRCODE='23514';
 ELSE
  NEW.blocked_at:=OLD.blocked_at;
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_subjects_guard() FROM PUBLIC;
CREATE TRIGGER access_subjects_guard BEFORE INSERT OR UPDATE ON cp.access_subjects
 FOR EACH ROW EXECUTE FUNCTION cp.access_subjects_guard();

-- Contexto: tipo, unidade, versão e vigência vêm da origem; a origem precisa pertencer ao sujeito. Depois de gravado,
-- só o estado muda (ACTIVE → REVOKED/EXPIRED/SUPERSEDED), com motivo e versão+1.
CREATE FUNCTION cp.subject_contexts_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE s record; m record; p record; g record;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF OLD.status<>'ACTIVE' OR NEW.status='ACTIVE' OR NEW.version<>OLD.version+1
    OR (NEW.id,NEW.tenant_id,NEW.subject_id,NEW.context_kind,NEW.source_kind,NEW.membership_id,NEW.permit_id,
        NEW.functional_authorization_ref,NEW.source_version,NEW.unit_id,NEW.guardian_subject_id,NEW.reauthorizes_context_id,NEW.approved_by_account_id,
        NEW.valid_from,NEW.valid_until,NEW.created_by,NEW.created_at)
     IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.subject_id,OLD.context_kind,OLD.source_kind,OLD.membership_id,OLD.permit_id,
        OLD.functional_authorization_ref,OLD.source_version,OLD.unit_id,OLD.guardian_subject_id,OLD.reauthorizes_context_id,OLD.approved_by_account_id,
        OLD.valid_from,OLD.valid_until,OLD.created_by,OLD.created_at) THEN
   RAISE EXCEPTION 'contexto só muda de ativo para encerrado' USING ERRCODE='23514';
  END IF;
  NEW.updated_at:=clock_timestamp();
  RETURN NEW;
 END IF;
 IF NEW.status<>'ACTIVE' OR NEW.version<>0 THEN RAISE EXCEPTION 'contexto nasce ativo' USING ERRCODE='23514'; END IF;
 SELECT origin_kind,account_id,visitor_id,status INTO s FROM cp.access_subjects WHERE tenant_id=NEW.tenant_id AND id=NEW.subject_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'sujeito inexistente' USING ERRCODE='23503'; END IF;
 IF NEW.source_kind='MEMBERSHIP' THEN
  SELECT account_id,unit_id,version,valid_from,valid_until,status INTO m FROM cp.memberships
   WHERE tenant_id=NEW.tenant_id AND id=NEW.membership_id;
  IF NOT FOUND OR s.origin_kind<>'ACCOUNT' OR m.account_id<>s.account_id THEN
   RAISE EXCEPTION 'vínculo de outra pessoa' USING ERRCODE='23514';
  END IF;
  IF m.status<>'ACTIVE' THEN RAISE EXCEPTION 'vínculo não está ativo' USING ERRCODE='23514'; END IF;
  NEW.context_kind:=cp.membership_context_kind(NEW.tenant_id,NEW.membership_id);
  IF NEW.context_kind IS NULL THEN RAISE EXCEPTION 'papel sem contexto de acesso físico' USING ERRCODE='23514'; END IF;
  NEW.unit_id:=m.unit_id; NEW.source_version:=m.version; NEW.valid_from:=m.valid_from; NEW.valid_until:=m.valid_until;
  NEW.functional_authorization_ref:=NULL; NEW.approved_by_account_id:=NULL;
 ELSIF NEW.source_kind='VISIT_PERMIT' THEN
  SELECT visitor_id,unit_id,version,valid_from,valid_until,state,rule_id INTO p FROM cp.visit_permits
   WHERE tenant_id=NEW.tenant_id AND id=NEW.permit_id;
  IF NOT FOUND OR s.origin_kind<>'VISITOR' OR p.visitor_id<>s.visitor_id THEN
   RAISE EXCEPTION 'convite de outra pessoa' USING ERRCODE='23514';
  END IF;
  IF p.state<>'ACTIVE' OR p.valid_until<=clock_timestamp() THEN RAISE EXCEPTION 'convite não está vigente' USING ERRCODE='23514'; END IF;
  NEW.context_kind:=CASE WHEN p.rule_id IS NOT NULL THEN 'SERVICE_PROVIDER' ELSE 'VISITOR' END;
  NEW.unit_id:=p.unit_id; NEW.source_version:=p.version; NEW.valid_from:=p.valid_from; NEW.valid_until:=p.valid_until;
  NEW.functional_authorization_ref:=NULL; NEW.approved_by_account_id:=NULL; NEW.guardian_subject_id:=NULL;
 ELSE
  -- Autorização funcional: tipo explícito, aprovador e fim; prazo máximo de 366 dias; sem unidade obrigatória.
  IF NEW.valid_until IS NULL OR NEW.valid_until>clock_timestamp()+interval '366 days' OR NEW.valid_from<clock_timestamp()-interval '5 minutes' THEN
   RAISE EXCEPTION 'autorização funcional com vigência inválida' USING ERRCODE='23514';
  END IF;
  NEW.source_version:=0; NEW.guardian_subject_id:=NULL;
 END IF;
 IF NEW.guardian_subject_id IS NOT NULL THEN
  SELECT origin_kind,status INTO g FROM cp.access_subjects WHERE tenant_id=NEW.tenant_id AND id=NEW.guardian_subject_id;
  IF NOT FOUND OR g.origin_kind<>'ACCOUNT' OR g.status<>'ACTIVE' THEN
   RAISE EXCEPTION 'responsável precisa ser sujeito ativo com conta' USING ERRCODE='23514';
  END IF;
 END IF;
 -- Revogação de um contexto vale até reautorização explícita: se a linha mais recente da mesma origem e sujeito está
 -- REVOKED, a nova precisa citá-la (só vínculo; convite revogado nunca volta: novo convite é nova origem). Depois de
 -- reautorizada, a cadeia segue normalmente (substituição por mudança na origem não exige nova reautorização).
 IF NEW.source_kind IN ('MEMBERSHIP','VISIT_PERMIT') THEN
  SELECT r.id,r.status INTO g FROM cp.subject_contexts r WHERE r.tenant_id=NEW.tenant_id AND r.subject_id=NEW.subject_id
   AND r.source_kind=NEW.source_kind AND r.membership_id IS NOT DISTINCT FROM NEW.membership_id AND r.permit_id IS NOT DISTINCT FROM NEW.permit_id
   ORDER BY r.created_at DESC,r.id DESC LIMIT 1;
  IF FOUND AND g.status='REVOKED' THEN
   IF NEW.source_kind<>'MEMBERSHIP' OR NEW.reauthorizes_context_id IS DISTINCT FROM g.id THEN
    RAISE EXCEPTION 'origem com contexto revogado exige reautorização explícita' USING ERRCODE='23514';
   END IF;
  ELSIF NEW.reauthorizes_context_id IS NOT NULL THEN
   RAISE EXCEPTION 'reautorização sem revogação pendente da mesma origem' USING ERRCODE='23514';
  END IF;
 ELSIF NEW.reauthorizes_context_id IS NOT NULL THEN
  RAISE EXCEPTION 'autorização funcional não é reautorizada' USING ERRCODE='23514';
 END IF;
 NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.subject_contexts_guard() FROM PUBLIC;
CREATE TRIGGER subject_contexts_guard BEFORE INSERT OR UPDATE ON cp.subject_contexts
 FOR EACH ROW EXECUTE FUNCTION cp.subject_contexts_guard();

-- Pessoa e contextos visíveis à gestão e à portaria; a própria conta vê o próprio sujeito; MASTER de implantação só com
-- person.review delegado. Escrita: gestão (contas e autorizações funcionais), portaria (visitantes de convite vigente).
ALTER TABLE cp.access_subjects ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_subjects FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.subject_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.subject_contexts FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.access_subjects,cp.subject_contexts FROM PUBLIC;
CREATE POLICY tenant_boundary ON cp.access_subjects TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.subject_contexts TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Barreira da locação (V034): o proprietário de unidade alugada não vê nada da unidade, inclusive contextos.
CREATE POLICY rental_owner_hidden ON cp.subject_contexts AS RESTRICTIVE FOR ALL TO cp_api
 USING(NOT cp.rental_owner_hidden(tenant_id,unit_id)) WITH CHECK(NOT cp.rental_owner_hidden(tenant_id,unit_id));

-- Conferência da vigência de convites na listagem de sujeitos: a API liga app.subject_context_scan só durante a consulta
-- que devolve booleanos (cp.subject_context_current); a gestão continua sem listar convites fora dela.
CREATE FUNCTION cp.subject_context_scan() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(current_setting('app.subject_context_scan',true),'')='on' $$;
REVOKE ALL ON FUNCTION cp.subject_context_scan() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.subject_context_scan() TO cp_api;

CREATE FUNCTION cp.subject_staff() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_capability('memberships.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('person.review') $$;
REVOKE ALL ON FUNCTION cp.subject_staff() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.subject_staff() TO cp_api;
DROP POLICY permits_read_scope ON cp.visit_permits;
CREATE POLICY permits_read_scope ON cp.visit_permits AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.visit_member(tenant_id,unit_id,NULL)
  OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create'))
  OR cp.permit_link_open(tenant_id,id) OR cp.reservation_closing(reservation_id)
  OR (cp.subject_context_scan() AND cp.subject_staff()));

CREATE POLICY access_subjects_read ON cp.access_subjects AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.subject_staff() OR account_id=platform.current_actor_id());
CREATE POLICY access_subjects_create ON cp.access_subjects AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK((origin_kind='ACCOUNT' AND (platform.has_capability('memberships.manage') OR platform.has_technical_scope('person.review')))
  OR (origin_kind='VISITOR' AND platform.has_capability('concierge.operate')));
CREATE POLICY access_subjects_restrict ON cp.access_subjects AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('memberships.manage'))
 WITH CHECK(platform.has_capability('memberships.manage') AND status IN ('ACTIVE','BLOCKED')
  AND (status='ACTIVE' OR blocked_by=platform.current_actor_id()));
CREATE POLICY subject_contexts_read ON cp.subject_contexts AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.subject_staff() OR EXISTS(SELECT 1 FROM cp.access_subjects s WHERE s.tenant_id=subject_contexts.tenant_id
  AND s.id=subject_contexts.subject_id AND s.account_id=platform.current_actor_id()));
CREATE POLICY subject_contexts_create ON cp.subject_contexts AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id()
 AND (reauthorizes_context_id IS NULL OR platform.has_capability('memberships.manage')) AND (
  (source_kind IN ('MEMBERSHIP','FUNCTIONAL_AUTHORIZATION') AND (platform.has_capability('memberships.manage') OR platform.has_technical_scope('person.review'))
   AND (source_kind='MEMBERSHIP' OR approved_by_account_id=platform.current_actor_id()))
  OR (source_kind='VISIT_PERMIT' AND platform.has_capability('concierge.operate'))));
CREATE POLICY subject_contexts_close ON cp.subject_contexts AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('memberships.manage') OR (source_kind='VISIT_PERMIT' AND platform.has_capability('concierge.operate')))
 WITH CHECK(platform.has_capability('memberships.manage') OR (source_kind='VISIT_PERMIT' AND platform.has_capability('concierge.operate')));

GRANT SELECT ON cp.access_subjects,cp.subject_contexts TO cp_api;
GRANT INSERT(id,tenant_id,origin_kind,account_id,visitor_id) ON cp.access_subjects TO cp_api;
GRANT UPDATE(status,restriction_epoch,blocked_reason,blocked_by,version) ON cp.access_subjects TO cp_api;
GRANT INSERT(id,tenant_id,subject_id,context_kind,source_kind,membership_id,permit_id,functional_authorization_ref,
 guardian_subject_id,reauthorizes_context_id,approved_by_account_id,valid_from,valid_until,created_by) ON cp.subject_contexts TO cp_api;
GRANT UPDATE(status,status_reason,version) ON cp.subject_contexts TO cp_api;
-- Worker: leitura mínima para o compilador de direitos (fatias seguintes); nada de escrita.
GRANT SELECT(id,tenant_id,status,restriction_epoch,external_ref) ON cp.access_subjects TO cp_worker;
GRANT SELECT(id,tenant_id,subject_id,context_kind,unit_id,valid_from,valid_until,status) ON cp.subject_contexts TO cp_worker;

-- Trilha da gestão para sujeitos e contextos.
-- Prefixo SUBJECT_ (ACCESS_ já é do registro manual da portaria, V014).
CREATE POLICY audit_subject_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'SUBJECT\_%' OR (actor_id=platform.current_actor_id() AND cp.subject_staff()
  AND resource_type IN ('ACCESS_SUBJECT','SUBJECT_CONTEXT')));

-- Compatibilidade da credencial (CP-046): sujeito sempre o da conta do vínculo da credencial, preenchido pelo banco na
-- criação (o sujeito é criado se faltar); credencial antiga recebe o sujeito pelo backfill idempotente abaixo. A aplicação
-- não tem UPDATE em subject_id: a ligação só acontece no banco (gatilho e migration).
ALTER TABLE cp.access_credentials ADD COLUMN subject_id uuid;
ALTER TABLE cp.access_credentials ADD CONSTRAINT access_credentials_subject_fk
 FOREIGN KEY(tenant_id,subject_id) REFERENCES cp.access_subjects(tenant_id,id);
CREATE INDEX access_credentials_by_subject ON cp.access_credentials(tenant_id,subject_id) WHERE subject_id IS NOT NULL;

CREATE FUNCTION cp.credential_subject(target_tenant uuid,target_membership uuid) RETURNS uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT s.id FROM cp.memberships m JOIN cp.access_subjects s ON s.tenant_id=m.tenant_id AND s.account_id=m.account_id
  WHERE m.tenant_id=target_tenant AND m.id=target_membership $$;
REVOKE ALL ON FUNCTION cp.credential_subject(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.credential_subject(uuid,uuid) TO cp_api;

CREATE OR REPLACE FUNCTION cp.access_credentials_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.desired_state<>'ACTIVE' OR NEW.desired_version<>1 OR NEW.version<>0 THEN
   RAISE EXCEPTION 'credencial nasce ativa na versão 1' USING ERRCODE='23514';
  END IF;
  SELECT m.valid_until INTO NEW.valid_until FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.membership_id;
  IF NEW.valid_until IS NOT NULL AND NEW.valid_until<=clock_timestamp() THEN
   RAISE EXCEPTION 'vínculo vencido' USING ERRCODE='23514';
  END IF;
  -- Sujeito da conta do vínculo, criado se faltar (a gestão que cria a credencial também cria sujeitos).
  INSERT INTO cp.access_subjects(tenant_id,origin_kind,account_id)
   SELECT m.tenant_id,'ACCOUNT',m.account_id FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.membership_id
  ON CONFLICT (tenant_id,account_id) WHERE account_id IS NOT NULL DO NOTHING;
  NEW.subject_id:=cp.credential_subject(NEW.tenant_id,NEW.membership_id);
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 -- Ligação de compatibilidade: só null → sujeito da conta do vínculo, sem nenhuma outra mudança.
 IF OLD.subject_id IS NULL AND NEW.subject_id IS NOT NULL AND NEW.version=OLD.version
   AND (NEW.desired_state,NEW.desired_version,NEW.revoke_reason,NEW.revoked_by,NEW.revoked_at)
    IS NOT DISTINCT FROM (OLD.desired_state,OLD.desired_version,OLD.revoke_reason,OLD.revoked_by,OLD.revoked_at)
   AND (NEW.tenant_id,NEW.connector_id,NEW.membership_id,NEW.unit_id,NEW.kind,NEW.label,NEW.external_ref,NEW.valid_until,NEW.created_by,NEW.created_at)
    IS NOT DISTINCT FROM (OLD.tenant_id,OLD.connector_id,OLD.membership_id,OLD.unit_id,OLD.kind,OLD.label,OLD.external_ref,OLD.valid_until,OLD.created_by,OLD.created_at) THEN
  IF NEW.subject_id IS DISTINCT FROM cp.credential_subject(NEW.tenant_id,NEW.membership_id) THEN
   RAISE EXCEPTION 'sujeito diferente da conta do vínculo' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF OLD.desired_state<>'ACTIVE' OR NEW.desired_state<>'REVOKED' OR NEW.desired_version<>OLD.desired_version+1 OR NEW.version<>OLD.version+1
   OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
   OR (NEW.tenant_id,NEW.connector_id,NEW.membership_id,NEW.unit_id,NEW.kind,NEW.label,NEW.external_ref,NEW.valid_until,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.tenant_id,OLD.connector_id,OLD.membership_id,OLD.unit_id,OLD.kind,OLD.label,OLD.external_ref,OLD.valid_until,OLD.created_by,OLD.created_at) THEN
  RAISE EXCEPTION 'credencial revogada não volta a valer' USING ERRCODE='23514';
 END IF;
 NEW.revoked_at:=clock_timestamp(); NEW.updated_at:=NEW.revoked_at;
 RETURN NEW;
END $$;

-- Backfill idempotente: um sujeito por conta com credencial (no condomínio da credencial) e a ligação da credencial.
-- Repetir não duplica (índice único por conta) nem altera credencial já ligada.
INSERT INTO cp.access_subjects(tenant_id,origin_kind,account_id)
SELECT DISTINCT c.tenant_id,'ACCOUNT',m.account_id FROM cp.access_credentials c
 JOIN cp.memberships m ON m.tenant_id=c.tenant_id AND m.id=c.membership_id
ON CONFLICT (tenant_id,account_id) WHERE account_id IS NOT NULL DO NOTHING;
UPDATE cp.access_credentials c SET subject_id=cp.credential_subject(c.tenant_id,c.membership_id) WHERE c.subject_id IS NULL;
