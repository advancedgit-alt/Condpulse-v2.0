-- AC-009 e base do AC-015 (pacote Acesso Facial v1.1): direitos derivados por sujeito, contexto e área, e marcadores
-- duráveis de revogação. A publicação por destino (jobs e estados por equipamento) é a V045; nada aqui fala com hardware.
--  - cp.access_grants: direito lógico derivado de uma origem identificável (vínculo, convite, reserva ou autorização
--    funcional) e de uma revisão aprovada da política da área. Vigência sempre finita, nunca além das origens, da
--    evidência de idade e do horizonte de derivação (30 dias, renovado por nova derivação). Geração monotônica global.
--    ACTIVE é desejo lógico vigente, não confirmação nas portas. Vale de fato só enquanto cp.access_grant_current().
--  - cp.revocation_markers: marca append-only (SUBJECT, CONTEXT ou GRANT) que cobre todos os métodos (face, cartão,
--    PIN, QR e direitos em plataforma intermediária) e fixa a geração até a qual nada ressuscita. O banco revoga na
--    mesma transação os direitos cobertos e, para sujeito bloqueado ou contexto de vínculo revogado, as credenciais
--    CARD/QR existentes (fila prioritária do CP-046). Diário local encadeado por condomínio; a confirmação externa
--    da durabilidade fica PENDING até a barreira de recuperação (V050).
--  - Bloqueio de sujeito e encerramento de contexto geram a marca pelo banco (gatilhos); cancelamento de convite ou
--    reserva e mudança de vínculo derrubam o direito na hora por cp.access_grant_current() e recebem a marca na
--    próxima varredura (derivação da gestão ou revogação explícita).
-- Escrita concorrente sob o mesmo lock por condomínio das V041-V043 ('cp.installation:'||tenant).

CREATE SEQUENCE cp.access_rights_generation AS bigint MINVALUE 1 NO CYCLE;
REVOKE ALL ON SEQUENCE cp.access_rights_generation FROM PUBLIC;
GRANT USAGE ON SEQUENCE cp.access_rights_generation TO cp_api;

-- Horizonte máximo de um direito derivado (decisão provisória P16 em DECISOES_PENDENTES.md).
CREATE FUNCTION cp.access_grant_horizon() RETURNS interval
LANGUAGE sql IMMUTABLE SECURITY INVOKER SET search_path=pg_catalog AS $$ SELECT interval '30 days' $$;
REVOKE ALL ON FUNCTION cp.access_grant_horizon() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.access_grant_horizon() TO cp_api;

-- 1. Direitos.
CREATE TABLE cp.access_grants (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 subject_id uuid NOT NULL,
 context_id uuid NOT NULL,
 area_id uuid NOT NULL,
 policy_id uuid NOT NULL,
 policy_version_id uuid NOT NULL,
 origin_kind text NOT NULL CHECK(origin_kind IN ('MEMBERSHIP','VISIT_PERMIT','RESERVATION','FUNCTIONAL_AUTHORIZATION')),
 origin_membership_id uuid,
 origin_permit_id uuid,
 origin_reservation_id uuid,
 origin_functional_context_id uuid,
 origin_version bigint NOT NULL DEFAULT 0 CHECK(origin_version>=0),
 -- Identidade da origem (tipo:id), calculada pelo banco: um direito ativo por contexto, área e origem.
 origin_key text NOT NULL DEFAULT '',
 age_evidence_id uuid,
 valid_from timestamptz NOT NULL,
 valid_until timestamptz NOT NULL,
 generation bigint NOT NULL DEFAULT 0,
 subject_restriction_epoch bigint NOT NULL CHECK(subject_restriction_epoch>=0),
 derivation_key text NOT NULL DEFAULT '',
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED','EXPIRED','SUPERSEDED')),
 status_reason_code text CHECK(status_reason_code IS NULL OR status_reason_code IN ('SUBJECT_BLOCKED','CONTEXT_REVOKED','CONTEXT_EXPIRED',
  'CONTEXT_SUPERSEDED','ORIGIN_CHANGED','MANUAL_REVOCATION','WINDOW_ENDED','POLICY_REDERIVED')),
 revoked_at timestamptz,
 revocation_marker_id uuid,
 reason_codes text[] NOT NULL DEFAULT '{}' CHECK(cardinality(reason_codes)<=8 AND reason_codes <@ ARRAY['ELIGIBLE_CONTEXT','AGE_VERIFIED',
  'RESERVATION_HOST','RESERVATION_GUEST','VISIT_PERMIT','FUNCTIONAL_AUTHORIZATION']),
 derived_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,id,subject_id),
 UNIQUE(tenant_id,id,area_id),
 FOREIGN KEY(tenant_id,subject_id) REFERENCES cp.access_subjects(tenant_id,id),
 FOREIGN KEY(tenant_id,context_id,subject_id) REFERENCES cp.subject_contexts(tenant_id,id,subject_id),
 FOREIGN KEY(tenant_id,area_id) REFERENCES cp.access_areas(tenant_id,id),
 FOREIGN KEY(tenant_id,policy_id,area_id) REFERENCES cp.access_policies(tenant_id,id,area_id),
 FOREIGN KEY(tenant_id,policy_version_id,policy_id) REFERENCES cp.access_policy_versions(tenant_id,id,policy_id),
 FOREIGN KEY(tenant_id,origin_membership_id) REFERENCES cp.memberships(tenant_id,id),
 FOREIGN KEY(tenant_id,origin_permit_id) REFERENCES cp.visit_permits(tenant_id,id),
 FOREIGN KEY(tenant_id,origin_reservation_id) REFERENCES cp.reservations(tenant_id,id),
 FOREIGN KEY(tenant_id,origin_functional_context_id,subject_id) REFERENCES cp.subject_contexts(tenant_id,id,subject_id),
 FOREIGN KEY(tenant_id,age_evidence_id,subject_id) REFERENCES cp.age_evidence(tenant_id,id,subject_id),
 -- Exatamente a origem do tipo declarado (reserva de anfitrião: vínculo do contexto + reserva).
 CHECK((origin_kind IN ('MEMBERSHIP','RESERVATION'))=(origin_membership_id IS NOT NULL)
  AND (origin_kind='VISIT_PERMIT')=(origin_permit_id IS NOT NULL)
  AND (origin_kind='RESERVATION' OR origin_reservation_id IS NULL)
  AND (origin_kind<>'RESERVATION' OR origin_reservation_id IS NOT NULL)
  AND (origin_kind='FUNCTIONAL_AUTHORIZATION')=(origin_functional_context_id IS NOT NULL)),
 CHECK(isfinite(valid_from) AND isfinite(valid_until) AND valid_until>valid_from),
 CHECK((status='REVOKED')=(revocation_marker_id IS NOT NULL AND revoked_at IS NOT NULL)),
 CHECK((status='ACTIVE')=(status_reason_code IS NULL))
);
CREATE UNIQUE INDEX access_grants_one_active_origin ON cp.access_grants(tenant_id,context_id,area_id,origin_key) WHERE status='ACTIVE';
CREATE INDEX access_grants_derivation ON cp.access_grants(tenant_id,derivation_key);
CREATE INDEX access_grants_subject ON cp.access_grants(tenant_id,subject_id,status,valid_until);
CREATE INDEX access_grants_area ON cp.access_grants(tenant_id,area_id,policy_version_id,status);
CREATE INDEX access_grants_context ON cp.access_grants(tenant_id,context_id) WHERE status='ACTIVE';
CREATE INDEX access_grants_by_membership ON cp.access_grants(tenant_id,origin_membership_id) WHERE origin_membership_id IS NOT NULL;
CREATE INDEX access_grants_by_permit ON cp.access_grants(tenant_id,origin_permit_id) WHERE origin_permit_id IS NOT NULL;
CREATE INDEX access_grants_by_reservation ON cp.access_grants(tenant_id,origin_reservation_id) WHERE origin_reservation_id IS NOT NULL;

-- 2. Marcadores de revogação (append-only; só a confirmação externa avança, na V050).
CREATE TABLE cp.revocation_markers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 target_kind text NOT NULL CHECK(target_kind IN ('SUBJECT','CONTEXT','GRANT')),
 subject_id uuid NOT NULL,
 context_id uuid,
 grant_id uuid,
 -- A revogação alcança todos os métodos do alvo: apagar só a face não é revogação.
 covered_methods text[] NOT NULL DEFAULT ARRAY['FACE','CARD','PIN','QR','INTERMEDIARY_GRANT']
  CHECK(covered_methods=ARRAY['FACE','CARD','PIN','QR','INTERMEDIARY_GRANT']),
 -- Nenhum direito ou mensagem do alvo com geração menor que esta volta a valer.
 revoked_through_generation bigint NOT NULL DEFAULT 0,
 subject_restriction_epoch bigint NOT NULL DEFAULT 0 CHECK(subject_restriction_epoch>=0),
 effective_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 reason_code text NOT NULL CHECK(reason_code IN ('SUBJECT_BLOCKED','SUBJECT_ERASURE','CONTEXT_REVOKED','CONTEXT_EXPIRED','CONTEXT_SUPERSEDED',
  'ORIGIN_CHANGED','MANUAL_REVOCATION')),
 operation_id uuid NOT NULL DEFAULT gen_random_uuid(),
 dedupe_key text NOT NULL DEFAULT '',
 -- Diário local por condomínio, contínuo e encadeado; a cópia fora do snapshot é confirmada na V050.
 journal_stream_id text NOT NULL DEFAULT '' CHECK(length(journal_stream_id)<=128),
 journal_sequence bigint NOT NULL DEFAULT 0,
 entry_hash text NOT NULL DEFAULT '',
 previous_entry_hash text CHECK(previous_entry_hash IS NULL OR previous_entry_hash ~ '^[0-9a-f]{64}$'),
 external_durability_status text NOT NULL DEFAULT 'PENDING' CHECK(external_durability_status IN ('PENDING','CONFIRMED','UNVERIFIED')),
 journal_receipt_ref text CHECK(journal_receipt_ref IS NULL OR journal_receipt_ref ~ '^[A-Za-z0-9._:/-]{8,160}$'),
 journal_persisted_at timestamptz,
 created_by uuid REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,dedupe_key),
 UNIQUE(tenant_id,journal_stream_id,journal_sequence),
 FOREIGN KEY(tenant_id,subject_id) REFERENCES cp.access_subjects(tenant_id,id),
 FOREIGN KEY(tenant_id,context_id,subject_id) REFERENCES cp.subject_contexts(tenant_id,id,subject_id),
 FOREIGN KEY(tenant_id,grant_id,subject_id) REFERENCES cp.access_grants(tenant_id,id,subject_id),
 CHECK((target_kind='SUBJECT')=(context_id IS NULL AND grant_id IS NULL)),
 CHECK(target_kind<>'CONTEXT' OR (context_id IS NOT NULL AND grant_id IS NULL)),
 CHECK(target_kind<>'GRANT' OR grant_id IS NOT NULL),
 CHECK(external_durability_status<>'CONFIRMED' OR (journal_receipt_ref IS NOT NULL AND journal_persisted_at IS NOT NULL))
);
CREATE INDEX revocation_markers_subject ON cp.revocation_markers(tenant_id,subject_id,effective_at);
CREATE INDEX revocation_markers_context ON cp.revocation_markers(tenant_id,context_id) WHERE context_id IS NOT NULL;
CREATE INDEX revocation_markers_grant ON cp.revocation_markers(tenant_id,grant_id,effective_at) WHERE grant_id IS NOT NULL;
CREATE INDEX revocation_markers_durability ON cp.revocation_markers(tenant_id,external_durability_status,journal_sequence);
ALTER TABLE cp.access_grants ADD CONSTRAINT access_grants_revocation_marker
 FOREIGN KEY(tenant_id,revocation_marker_id) REFERENCES cp.revocation_markers(tenant_id,id);

-- 3. Funções de leitura.
-- Chave determinística da derivação: os mesmos fatos dão a mesma chave; revogação de uma chave vale até mudar a origem,
-- o contexto, a época do sujeito, a revisão da política ou a evidência.
CREATE FUNCTION cp.access_grant_key(subject uuid,context uuid,area uuid,policy_version uuid,origin_kind text,origin_id uuid,origin_version bigint,
  epoch bigint,age_evidence uuid) RETURNS text
LANGUAGE sql IMMUTABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT encode(sha256(convert_to(concat_ws('|','v1',subject,context,area,policy_version,origin_kind,origin_id,origin_version,epoch,
  coalesce(age_evidence::text,'-')),'UTF8')),'hex') $$;
REVOKE ALL ON FUNCTION cp.access_grant_key(uuid,uuid,uuid,uuid,text,uuid,bigint,bigint,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.access_grant_key(uuid,uuid,uuid,uuid,text,uuid,bigint,bigint,uuid) TO cp_api;

-- Revogação que alcança o direito: marca do próprio direito, do contexto ou do sujeito na época do direito ou depois.
CREATE FUNCTION cp.access_grant_revoked(target_tenant uuid,target_grant uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.access_grants g JOIN cp.revocation_markers m ON m.tenant_id=g.tenant_id AND m.subject_id=g.subject_id
  WHERE g.tenant_id=target_tenant AND g.id=target_grant AND m.revoked_through_generation>g.generation
   AND ((m.target_kind='SUBJECT' AND m.subject_restriction_epoch>=g.subject_restriction_epoch)
    OR (m.target_kind='CONTEXT' AND m.context_id=g.context_id) OR (m.target_kind='GRANT' AND m.grant_id=g.id))) $$;
REVOKE ALL ON FUNCTION cp.access_grant_revoked(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.access_grant_revoked(uuid,uuid) TO cp_api;

-- Direito vigente no instante: ativo, na janela, sujeito ativo na mesma época, contexto vigente na origem, origem
-- ainda igual (convite ativo, reserva confirmada, na mesma versão), política não retirada, revisão aprovada ou
-- publicada, evidência de idade válida e nenhuma revogação que o alcance. Falha fechada: o que não for visível nega.
CREATE FUNCTION cp.access_grant_current(target_tenant uuid,target_grant uuid,at timestamptz) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce((SELECT g.status='ACTIVE' AND g.valid_from<=at AND g.valid_until>at
   AND s.status='ACTIVE' AND s.restriction_epoch=g.subject_restriction_epoch
   AND cp.subject_context_current(g.tenant_id,g.context_id)
   AND p.status<>'RETIRED' AND v.state IN ('APPROVED','PUBLISHED')
   AND a.status='ACTIVE'
   AND CASE g.origin_kind
    -- Convidado de reserva: o convite vale só enquanto a reserva do anfitrião continuar confirmada.
    WHEN 'VISIT_PERMIT' THEN EXISTS(SELECT 1 FROM cp.visit_permits vp WHERE vp.tenant_id=g.tenant_id AND vp.id=g.origin_permit_id
      AND vp.state='ACTIVE' AND vp.version=g.origin_version AND vp.valid_until>at
      AND (vp.reservation_id IS NULL OR EXISTS(SELECT 1 FROM cp.reservations r WHERE r.tenant_id=vp.tenant_id AND r.id=vp.reservation_id
       AND r.status='CONFIRMED')))
    WHEN 'RESERVATION' THEN EXISTS(SELECT 1 FROM cp.reservations r WHERE r.tenant_id=g.tenant_id AND r.id=g.origin_reservation_id
      AND r.status='CONFIRMED' AND r.version=g.origin_version)
    ELSE true END
   AND (g.age_evidence_id IS NULL OR EXISTS(SELECT 1 FROM cp.age_evidence e WHERE e.tenant_id=g.tenant_id AND e.id=g.age_evidence_id
      AND e.status='VALID' AND e.valid_until>at))
   AND NOT cp.access_grant_revoked(g.tenant_id,g.id)
  FROM cp.access_grants g
  JOIN cp.access_subjects s ON s.tenant_id=g.tenant_id AND s.id=g.subject_id
  JOIN cp.access_policies p ON p.tenant_id=g.tenant_id AND p.id=g.policy_id
  JOIN cp.access_policy_versions v ON v.tenant_id=g.tenant_id AND v.id=g.policy_version_id
  JOIN cp.access_areas a ON a.tenant_id=g.tenant_id AND a.id=g.area_id
  WHERE g.tenant_id=target_tenant AND g.id=target_grant),false) $$;
REVOKE ALL ON FUNCTION cp.access_grant_current(uuid,uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.access_grant_current(uuid,uuid,timestamptz) TO cp_api;

-- Quem deriva e lê direitos: gestão com vínculos e publicação; a portaria lê (visitantes) e revoga por cascata.
CREATE FUNCTION cp.grant_manager() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_capability('memberships.manage') AND platform.has_capability('policy.publish') $$;
REVOKE ALL ON FUNCTION cp.grant_manager() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.grant_manager() TO cp_api;
CREATE FUNCTION cp.grant_reader() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT cp.subject_staff() OR platform.has_capability('policy.publish') $$;
REVOKE ALL ON FUNCTION cp.grant_reader() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.grant_reader() TO cp_api;

-- 4. Guarda dos direitos: tudo o que a derivação afirma é conferido de novo pelo banco.
CREATE FUNCTION cp.access_grants_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE s record; c record; pv record; doc jsonb; ar record; vp record; rs record; ev record; lim timestamptz; mode text;
 before_s integer; after_s integer; origin uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 IF TG_OP='UPDATE' THEN
  IF OLD.status<>'ACTIVE' OR NEW.status='ACTIVE' OR NEW.version<>OLD.version+1
    OR (NEW.id,NEW.tenant_id,NEW.subject_id,NEW.context_id,NEW.area_id,NEW.policy_id,NEW.policy_version_id,NEW.origin_kind,NEW.origin_membership_id,
        NEW.origin_permit_id,NEW.origin_reservation_id,NEW.origin_functional_context_id,NEW.origin_version,NEW.origin_key,NEW.age_evidence_id,
        NEW.valid_from,NEW.valid_until,NEW.generation,NEW.subject_restriction_epoch,NEW.derivation_key,NEW.reason_codes,NEW.derived_by,NEW.created_at)
     IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.subject_id,OLD.context_id,OLD.area_id,OLD.policy_id,OLD.policy_version_id,OLD.origin_kind,OLD.origin_membership_id,
        OLD.origin_permit_id,OLD.origin_reservation_id,OLD.origin_functional_context_id,OLD.origin_version,OLD.origin_key,OLD.age_evidence_id,
        OLD.valid_from,OLD.valid_until,OLD.generation,OLD.subject_restriction_epoch,OLD.derivation_key,OLD.reason_codes,OLD.derived_by,OLD.created_at) THEN
   RAISE EXCEPTION 'direito só sai de ativo, uma vez, sem mudar a origem' USING ERRCODE='23514';
  END IF;
  IF NEW.status='REVOKED' THEN
   -- A marca citada precisa alcançar este direito.
   IF NOT EXISTS(SELECT 1 FROM cp.revocation_markers m WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.revocation_marker_id AND m.subject_id=NEW.subject_id
     AND m.revoked_through_generation>NEW.generation
     AND ((m.target_kind='SUBJECT' AND m.subject_restriction_epoch>=NEW.subject_restriction_epoch) OR (m.target_kind='CONTEXT' AND m.context_id=NEW.context_id)
      OR (m.target_kind='GRANT' AND m.grant_id=NEW.id))) THEN
    RAISE EXCEPTION 'revogação de direito exige marca que o alcance' USING ERRCODE='23514';
   END IF;
  ELSIF NEW.revocation_marker_id IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
   RAISE EXCEPTION 'só a revogação cita marca' USING ERRCODE='23514';
  ELSIF NEW.status='EXPIRED' THEN
   IF NEW.valid_until>clock_timestamp() AND cp.access_grant_current(NEW.tenant_id,NEW.id,greatest(clock_timestamp(),NEW.valid_from)) THEN
    RAISE EXCEPTION 'direito vigente ou ainda por começar não expira' USING ERRCODE='23514';
   END IF;
  ELSIF NEW.status='SUPERSEDED' AND NOT cp.grant_manager() AND current_setting('is_superuser')<>'on' THEN
   RAISE EXCEPTION 'substituição só pela derivação da gestão' USING ERRCODE='42501';
  END IF;
  NEW.updated_at:=clock_timestamp();
  RETURN NEW;
 END IF;

 -- Derivação só pela gestão com vínculos e publicação (antes de qualquer leitura, para a negação ser explícita).
 IF NOT cp.grant_manager() AND current_setting('is_superuser')<>'on' THEN
  RAISE EXCEPTION 'derivação de direitos exige gestão de vínculos e publicação de políticas' USING ERRCODE='42501';
 END IF;
 IF NEW.status<>'ACTIVE' OR NEW.version<>0 OR NEW.revocation_marker_id IS NOT NULL OR NEW.revoked_at IS NOT NULL OR NEW.status_reason_code IS NOT NULL THEN
  RAISE EXCEPTION 'direito nasce ativo' USING ERRCODE='23514';
 END IF;
 SELECT status,restriction_epoch INTO s FROM cp.access_subjects WHERE tenant_id=NEW.tenant_id AND id=NEW.subject_id;
 IF NOT FOUND OR s.status<>'ACTIVE' OR s.restriction_epoch<>NEW.subject_restriction_epoch THEN
  RAISE EXCEPTION 'sujeito bloqueado ou época desatualizada: revogação vence a derivação antiga' USING ERRCODE='23514';
 END IF;
 SELECT context_kind,source_kind,membership_id,permit_id,unit_id,source_version,valid_until INTO c FROM cp.subject_contexts
  WHERE tenant_id=NEW.tenant_id AND id=NEW.context_id AND subject_id=NEW.subject_id;
 IF NOT FOUND OR NOT cp.subject_context_current(NEW.tenant_id,NEW.context_id) THEN
  RAISE EXCEPTION 'contexto não vigente na origem' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM cp.revocation_markers m WHERE m.tenant_id=NEW.tenant_id AND m.target_kind='CONTEXT' AND m.context_id=NEW.context_id) THEN
  RAISE EXCEPTION 'contexto revogado não recebe direito' USING ERRCODE='23514';
 END IF;
 SELECT pol.status,ver.state,ver.document,ver.valid_until INTO pv FROM cp.access_policies pol
  JOIN cp.access_policy_versions ver ON ver.tenant_id=pol.tenant_id AND ver.policy_id=pol.id
  WHERE pol.tenant_id=NEW.tenant_id AND pol.id=NEW.policy_id AND pol.area_id=NEW.area_id AND ver.id=NEW.policy_version_id;
 IF NOT FOUND OR pv.status='RETIRED' OR pv.state NOT IN ('APPROVED','PUBLISHED') THEN
  RAISE EXCEPTION 'direito só de revisão aprovada de política não retirada' USING ERRCODE='23514';
 END IF;
 doc:=pv.document;
 SELECT status,amenity_id INTO ar FROM cp.access_areas WHERE tenant_id=NEW.tenant_id AND id=NEW.area_id;
 IF ar.status<>'ACTIVE' THEN RAISE EXCEPTION 'área não ativa' USING ERRCODE='23514'; END IF;
 IF NOT (doc->'eligibleContexts') ? c.context_kind THEN RAISE EXCEPTION 'contexto não elegível na regra' USING ERRCODE='23514'; END IF;
 IF doc->'maximumAgeYears' IS NOT NULL AND jsonb_typeof(doc->'maximumAgeYears')<>'null' THEN
  RAISE EXCEPTION 'idade máxima ainda não suportada' USING ERRCODE='23514';
 END IF;
 lim:=clock_timestamp()+cp.access_grant_horizon();
 IF c.valid_until IS NOT NULL THEN lim:=least(lim,c.valid_until); END IF;
 IF pv.valid_until IS NOT NULL THEN lim:=least(lim,pv.valid_until); END IF;
 -- Idade: só por evidência cadastral válida do próprio sujeito (o predicado é conferido pela API, que decifra a data).
 IF doc->'minimumAgeYears' IS NOT NULL AND jsonb_typeof(doc->'minimumAgeYears')<>'null' THEN
  SELECT valid_until INTO ev FROM cp.age_evidence WHERE tenant_id=NEW.tenant_id AND id=NEW.age_evidence_id AND subject_id=NEW.subject_id
   AND status='VALID' AND valid_until>clock_timestamp();
  IF NOT FOUND THEN RAISE EXCEPTION 'regra com idade exige evidência válida' USING ERRCODE='23514'; END IF;
  lim:=least(lim,ev.valid_until);
 ELSIF NEW.age_evidence_id IS NOT NULL THEN
  RAISE EXCEPTION 'evidência de idade só em regra com idade' USING ERRCODE='23514';
 END IF;
 mode:=coalesce(doc#>>'{reservation,mode}','');
 before_s:=coalesce((doc#>>'{reservation,entryGraceBeforeSeconds}')::integer,0);
 after_s:=coalesce((doc#>>'{reservation,entryGraceAfterSeconds}')::integer,0);
 IF NEW.origin_kind='MEMBERSHIP' THEN
  IF c.source_kind<>'MEMBERSHIP' OR NEW.origin_membership_id<>c.membership_id OR NEW.origin_version<>c.source_version OR mode<>'NOT_REQUIRED' THEN
   RAISE EXCEPTION 'origem de vínculo incoerente com o contexto ou com a regra' USING ERRCODE='23514';
  END IF;
  origin:=NEW.origin_membership_id;
 ELSIF NEW.origin_kind='FUNCTIONAL_AUTHORIZATION' THEN
  IF c.source_kind<>'FUNCTIONAL_AUTHORIZATION' OR NEW.origin_functional_context_id<>NEW.context_id OR NEW.origin_version<>0 OR mode<>'NOT_REQUIRED' THEN
   RAISE EXCEPTION 'origem funcional incoerente com o contexto ou com a regra' USING ERRCODE='23514';
  END IF;
  origin:=NEW.context_id;
 ELSIF NEW.origin_kind='VISIT_PERMIT' THEN
  SELECT state,version,valid_from,valid_until,reservation_id INTO vp FROM cp.visit_permits WHERE tenant_id=NEW.tenant_id AND id=NEW.origin_permit_id;
  IF c.source_kind<>'VISIT_PERMIT' OR NEW.origin_permit_id<>c.permit_id OR NOT FOUND OR vp.state<>'ACTIVE' OR vp.version<>NEW.origin_version THEN
   RAISE EXCEPTION 'origem de convite incoerente ou não vigente' USING ERRCODE='23514';
  END IF;
  lim:=least(lim,vp.valid_until);
  IF vp.reservation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cp.reservations x WHERE x.tenant_id=NEW.tenant_id AND x.id=vp.reservation_id AND x.status='CONFIRMED') THEN
   RAISE EXCEPTION 'convidado de reserva não confirmada' USING ERRCODE='23514';
  END IF;
  IF mode='HOST_OR_LISTED_GUEST' THEN
   SELECT starts_at,ends_at INTO rs FROM cp.reservations WHERE tenant_id=NEW.tenant_id AND id=vp.reservation_id AND status='CONFIRMED' AND amenity_id=ar.amenity_id;
   IF NOT FOUND THEN RAISE EXCEPTION 'convidado só de reserva confirmada deste espaço' USING ERRCODE='23514'; END IF;
   lim:=least(lim,rs.ends_at+make_interval(secs=>after_s));
   IF NEW.valid_from<rs.starts_at-make_interval(secs=>before_s) THEN RAISE EXCEPTION 'direito antes da reserva' USING ERRCODE='23514'; END IF;
  ELSIF mode<>'NOT_REQUIRED' THEN
   RAISE EXCEPTION 'regra exige reserva do anfitrião' USING ERRCODE='23514';
  END IF;
  origin:=NEW.origin_permit_id;
 ELSE -- RESERVATION: anfitrião morador da unidade da reserva confirmada deste espaço.
  SELECT unit_id,version,starts_at,ends_at INTO rs FROM cp.reservations WHERE tenant_id=NEW.tenant_id AND id=NEW.origin_reservation_id
   AND status='CONFIRMED' AND kind='BOOKING' AND amenity_id=ar.amenity_id;
  IF NOT FOUND OR mode NOT IN ('VALID_RESERVATION','HOST_OR_LISTED_GUEST') OR c.source_kind<>'MEMBERSHIP' OR NEW.origin_membership_id<>c.membership_id
    OR c.context_kind NOT IN ('RESIDENT','DEPENDENT') OR c.unit_id IS DISTINCT FROM rs.unit_id OR rs.version<>NEW.origin_version THEN
   RAISE EXCEPTION 'origem de reserva incoerente com o contexto, o espaço ou a regra' USING ERRCODE='23514';
  END IF;
  lim:=least(lim,rs.ends_at+make_interval(secs=>after_s));
  IF NEW.valid_from<rs.starts_at-make_interval(secs=>before_s) THEN RAISE EXCEPTION 'direito antes da reserva' USING ERRCODE='23514'; END IF;
  origin:=NEW.origin_reservation_id;
 END IF;
 NEW.valid_from:=greatest(NEW.valid_from,clock_timestamp()-interval '1 minute');
 IF NEW.valid_until>lim OR NEW.valid_until<=NEW.valid_from THEN
  RAISE EXCEPTION 'vigência além das origens, da evidência ou do horizonte' USING ERRCODE='23514';
 END IF;
 NEW.origin_key:=NEW.origin_kind||':'||origin::text;
 NEW.derivation_key:=cp.access_grant_key(NEW.subject_id,NEW.context_id,NEW.area_id,NEW.policy_version_id,NEW.origin_kind,origin,NEW.origin_version,
  NEW.subject_restriction_epoch,NEW.age_evidence_id);
 -- Revogação vence: a mesma derivação revogada não volta (nova origem, época ou revisão gera outra chave).
 IF EXISTS(SELECT 1 FROM cp.access_grants g WHERE g.tenant_id=NEW.tenant_id AND g.derivation_key=NEW.derivation_key AND g.status='REVOKED') THEN
  RAISE EXCEPTION 'derivação revogada não é refeita' USING ERRCODE='23514';
 END IF;
 NEW.generation:=nextval('cp.access_rights_generation');
 NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_grants_guard() FROM PUBLIC;
CREATE TRIGGER access_grants_guard BEFORE INSERT OR UPDATE ON cp.access_grants FOR EACH ROW EXECUTE FUNCTION cp.access_grants_guard();

-- 5. Guarda das marcas: alvo coerente com fatos já gravados; geração, diário e hash pelo banco.
CREATE FUNCTION cp.revocation_markers_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE s record; c record; g record; last record;
BEGIN
 IF TG_OP='UPDATE' THEN
  -- Núcleo imutável; só a confirmação externa avança, uma vez (V050).
  IF (NEW.id,NEW.tenant_id,NEW.target_kind,NEW.subject_id,NEW.context_id,NEW.grant_id,NEW.covered_methods,NEW.revoked_through_generation,
      NEW.subject_restriction_epoch,NEW.effective_at,NEW.reason_code,NEW.operation_id,NEW.dedupe_key,NEW.journal_stream_id,NEW.journal_sequence,
      NEW.entry_hash,NEW.previous_entry_hash,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.target_kind,OLD.subject_id,OLD.context_id,OLD.grant_id,OLD.covered_methods,OLD.revoked_through_generation,
      OLD.subject_restriction_epoch,OLD.effective_at,OLD.reason_code,OLD.operation_id,OLD.dedupe_key,OLD.journal_stream_id,OLD.journal_sequence,
      OLD.entry_hash,OLD.previous_entry_hash,OLD.created_by,OLD.created_at)
    OR OLD.external_durability_status<>'PENDING' OR NEW.external_durability_status='PENDING' THEN
   RAISE EXCEPTION 'marca de revogação é imutável' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF NOT (cp.subject_staff() OR cp.grant_manager()) AND current_setting('is_superuser')<>'on' THEN
  RAISE EXCEPTION 'marca de revogação só pela gestão ou portaria' USING ERRCODE='42501';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 IF NEW.external_durability_status<>'PENDING' OR NEW.journal_receipt_ref IS NOT NULL OR NEW.journal_persisted_at IS NOT NULL THEN
  RAISE EXCEPTION 'marca nasce pendente de confirmação externa' USING ERRCODE='23514';
 END IF;
 SELECT status,restriction_epoch INTO s FROM cp.access_subjects WHERE tenant_id=NEW.tenant_id AND id=NEW.subject_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'sujeito inexistente' USING ERRCODE='23503'; END IF;
 NEW.subject_restriction_epoch:=s.restriction_epoch;
 IF NEW.target_kind='SUBJECT' THEN
  IF s.status='ACTIVE' OR NEW.reason_code NOT IN ('SUBJECT_BLOCKED','SUBJECT_ERASURE') THEN
   RAISE EXCEPTION 'marca de sujeito só com o sujeito restrito' USING ERRCODE='23514';
  END IF;
  NEW.dedupe_key:='SUBJECT:'||NEW.subject_id::text||':'||s.restriction_epoch::text;
 ELSIF NEW.target_kind='CONTEXT' THEN
  SELECT status INTO c FROM cp.subject_contexts WHERE tenant_id=NEW.tenant_id AND id=NEW.context_id AND subject_id=NEW.subject_id;
  IF NOT FOUND OR c.status='ACTIVE' OR NEW.reason_code<>'CONTEXT_'||c.status THEN
   RAISE EXCEPTION 'marca de contexto só com o contexto encerrado e o motivo do encerramento' USING ERRCODE='23514';
  END IF;
  NEW.dedupe_key:='CONTEXT:'||NEW.context_id::text;
 ELSE
  SELECT id,context_id,valid_from INTO g FROM cp.access_grants WHERE tenant_id=NEW.tenant_id AND id=NEW.grant_id AND subject_id=NEW.subject_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'direito inexistente' USING ERRCODE='23503'; END IF;
  -- Revogação explícita pela gestão; a varredura só marca direito que já deixou de valer na origem.
  IF NEW.reason_code='MANUAL_REVOCATION' THEN
   IF NOT cp.grant_manager() AND current_setting('is_superuser')<>'on' THEN
    RAISE EXCEPTION 'revogação manual só pela gestão' USING ERRCODE='42501';
   END IF;
  -- Origem avaliada no início da janela quando ela é futura: direito ainda por começar não é origem inválida.
  ELSIF NEW.reason_code<>'ORIGIN_CHANGED' OR cp.access_grant_current(NEW.tenant_id,NEW.grant_id,greatest(clock_timestamp(),g.valid_from)) THEN
   RAISE EXCEPTION 'varredura só marca direito que deixou de valer na origem' USING ERRCODE='23514';
  END IF;
  NEW.context_id:=g.context_id;
  NEW.dedupe_key:='GRANT:'||NEW.grant_id::text;
 END IF;
 NEW.revoked_through_generation:=nextval('cp.access_rights_generation');
 NEW.effective_at:=clock_timestamp(); NEW.created_at:=NEW.effective_at;
 NEW.created_by:=platform.current_actor_id();
 NEW.covered_methods:=ARRAY['FACE','CARD','PIN','QR','INTERMEDIARY_GRANT'];
 NEW.journal_stream_id:='cp-db:'||NEW.tenant_id::text;
 SELECT journal_sequence,entry_hash INTO last FROM cp.revocation_markers WHERE tenant_id=NEW.tenant_id AND journal_stream_id=NEW.journal_stream_id
  ORDER BY journal_sequence DESC LIMIT 1;
 NEW.journal_sequence:=coalesce(last.journal_sequence,0)+1;
 NEW.previous_entry_hash:=last.entry_hash;
 NEW.entry_hash:=encode(sha256(convert_to(concat_ws('|','v1',NEW.tenant_id,NEW.id,NEW.target_kind,NEW.subject_id,coalesce(NEW.context_id::text,'-'),
  coalesce(NEW.grant_id::text,'-'),NEW.revoked_through_generation,NEW.subject_restriction_epoch,NEW.reason_code,NEW.dedupe_key,NEW.journal_sequence,
  coalesce(NEW.previous_entry_hash,'-'),to_char(NEW.effective_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),'UTF8')),'hex');
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.revocation_markers_guard() FROM PUBLIC;
CREATE TRIGGER revocation_markers_guard BEFORE INSERT OR UPDATE ON cp.revocation_markers FOR EACH ROW EXECUTE FUNCTION cp.revocation_markers_guard();

-- Efeito da marca na mesma transação: direitos alcançados saem de ACTIVE; sujeito restrito ou vínculo revogado também
-- revogam as credenciais CARD/QR (a fila prioritária do CP-046 retira dos equipamentos). Sem ator para assinar a
-- revogação da credencial, a operação é recusada (nunca fica cartão ativo de sujeito restrito).
CREATE FUNCTION cp.revocation_markers_apply() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE actor uuid; membership uuid; credential_scope boolean:=false;
BEGIN
 UPDATE cp.access_grants g SET status='REVOKED',status_reason_code=CASE NEW.reason_code WHEN 'SUBJECT_ERASURE' THEN 'SUBJECT_BLOCKED' ELSE NEW.reason_code END,
   revoked_at=NEW.effective_at,revocation_marker_id=NEW.id,version=g.version+1
  WHERE g.tenant_id=NEW.tenant_id AND g.subject_id=NEW.subject_id AND g.status='ACTIVE' AND g.generation<NEW.revoked_through_generation
   AND ((NEW.target_kind='SUBJECT' AND g.subject_restriction_epoch<=NEW.subject_restriction_epoch) OR (NEW.target_kind='CONTEXT' AND g.context_id=NEW.context_id)
    OR (NEW.target_kind='GRANT' AND g.id=NEW.grant_id));
 IF NEW.target_kind='SUBJECT' THEN
  credential_scope:=true;
 ELSIF NEW.target_kind='CONTEXT' AND NEW.reason_code='CONTEXT_REVOKED' THEN
  SELECT c.membership_id INTO membership FROM cp.subject_contexts c WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.context_id;
  credential_scope:=membership IS NOT NULL;
 END IF;
 IF credential_scope AND EXISTS(SELECT 1 FROM cp.access_credentials k WHERE k.tenant_id=NEW.tenant_id AND k.subject_id=NEW.subject_id AND k.desired_state='ACTIVE'
   AND (membership IS NULL OR k.membership_id=membership)) THEN
  actor:=coalesce(platform.current_actor_id(),(SELECT blocked_by FROM cp.access_subjects WHERE tenant_id=NEW.tenant_id AND id=NEW.subject_id));
  IF actor IS NULL THEN RAISE EXCEPTION 'revogação de credenciais exige ator identificado' USING ERRCODE='23514'; END IF;
  WITH revoked AS (
   UPDATE cp.access_credentials k SET desired_state='REVOKED',desired_version=k.desired_version+1,revoke_reason='Revogação de acesso: '||NEW.reason_code,
     revoked_by=actor,version=k.version+1
    WHERE k.tenant_id=NEW.tenant_id AND k.subject_id=NEW.subject_id AND k.desired_state='ACTIVE' AND (membership IS NULL OR k.membership_id=membership)
   RETURNING k.id,k.unit_id,k.desired_state,k.desired_version,k.version)
  INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   SELECT NEW.tenant_id,'CREDENTIAL_REVOKED',id,version,jsonb_build_object('credentialId',id,'unitId',unit_id,'desiredState',desired_state,'desiredVersion',desired_version)
   FROM revoked;
  IF EXISTS(SELECT 1 FROM cp.access_credentials k WHERE k.tenant_id=NEW.tenant_id AND k.subject_id=NEW.subject_id AND k.desired_state='ACTIVE'
    AND (membership IS NULL OR k.membership_id=membership)) THEN
   RAISE EXCEPTION 'credencial ativa sobreviveria à revogação' USING ERRCODE='42501';
  END IF;
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.revocation_markers_apply() FROM PUBLIC;
CREATE TRIGGER revocation_markers_apply AFTER INSERT ON cp.revocation_markers FOR EACH ROW EXECUTE FUNCTION cp.revocation_markers_apply();

-- 6. Cascatas: sujeito restrito e contexto encerrado geram a marca na mesma transação.
CREATE FUNCTION cp.access_subjects_revocation() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.status='ACTIVE' AND NEW.status<>'ACTIVE' THEN
  INSERT INTO cp.revocation_markers(tenant_id,target_kind,subject_id,reason_code)
   SELECT NEW.tenant_id,'SUBJECT',NEW.id,CASE WHEN NEW.status='BLOCKED' THEN 'SUBJECT_BLOCKED' ELSE 'SUBJECT_ERASURE' END
   WHERE NOT EXISTS(SELECT 1 FROM cp.revocation_markers m WHERE m.tenant_id=NEW.tenant_id AND m.dedupe_key='SUBJECT:'||NEW.id::text||':'||NEW.restriction_epoch::text);
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.access_subjects_revocation() FROM PUBLIC;
CREATE TRIGGER access_subjects_revocation AFTER UPDATE OF status ON cp.access_subjects FOR EACH ROW EXECUTE FUNCTION cp.access_subjects_revocation();

CREATE FUNCTION cp.subject_contexts_revocation() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.status='ACTIVE' AND NEW.status<>'ACTIVE' THEN
  INSERT INTO cp.revocation_markers(tenant_id,target_kind,subject_id,context_id,reason_code)
   SELECT NEW.tenant_id,'CONTEXT',NEW.subject_id,NEW.id,'CONTEXT_'||NEW.status
   WHERE NOT EXISTS(SELECT 1 FROM cp.revocation_markers m WHERE m.tenant_id=NEW.tenant_id AND m.dedupe_key='CONTEXT:'||NEW.id::text);
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.subject_contexts_revocation() FROM PUBLIC;
CREATE TRIGGER subject_contexts_revocation AFTER UPDATE OF status ON cp.subject_contexts FOR EACH ROW EXECUTE FUNCTION cp.subject_contexts_revocation();

-- Credencial nova não nasce para sujeito restrito (a revogação alcança cartão e QR também no cadastro). Mesmo lock por
-- condomínio da marca de revogação: se o bloqueio vier antes, a conferência (instrução nova, depois do lock) já o vê; se
-- vier depois, a cascata do bloqueio espera este commit e revoga a credencial criada.
CREATE FUNCTION cp.access_credentials_subject_active() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 IF EXISTS(SELECT 1 FROM cp.access_subjects s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.subject_id AND s.status<>'ACTIVE') THEN
  RAISE EXCEPTION 'sujeito restrito não recebe credencial' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.access_credentials_subject_active() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER access_credentials_subject_active AFTER INSERT ON cp.access_credentials
 FOR EACH ROW EXECUTE FUNCTION cp.access_credentials_subject_active();

-- 7. RLS.
ALTER TABLE cp.access_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_grants FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.revocation_markers ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.revocation_markers FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.access_grants,cp.revocation_markers FROM PUBLIC;
CREATE POLICY tenant_boundary ON cp.access_grants TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.revocation_markers TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY access_grants_read ON cp.access_grants AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.grant_reader());
CREATE POLICY access_grants_create ON cp.access_grants AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(cp.grant_manager() AND derived_by=platform.current_actor_id());
-- Saída de ACTIVE: revogação por marca (cascata da gestão ou portaria) ou expiração/substituição pela gestão.
CREATE POLICY access_grants_close ON cp.access_grants AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.grant_reader()) WITH CHECK(status='REVOKED' OR cp.grant_manager());
CREATE POLICY revocation_markers_read ON cp.revocation_markers AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.grant_reader());
CREATE POLICY revocation_markers_create ON cp.revocation_markers AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(cp.subject_staff() OR cp.grant_manager());

GRANT SELECT ON cp.access_grants,cp.revocation_markers TO cp_api;
GRANT INSERT(id,tenant_id,subject_id,context_id,area_id,policy_id,policy_version_id,origin_kind,origin_membership_id,origin_permit_id,
 origin_reservation_id,origin_functional_context_id,origin_version,age_evidence_id,valid_from,valid_until,subject_restriction_epoch,reason_codes,derived_by)
 ON cp.access_grants TO cp_api;
GRANT UPDATE(status,status_reason_code,revoked_at,revocation_marker_id,version) ON cp.access_grants TO cp_api;
GRANT INSERT(tenant_id,target_kind,subject_id,context_id,grant_id,reason_code,operation_id) ON cp.revocation_markers TO cp_api;
-- Worker (publicação por destino, V045): estado desejado e marcas, sem autoria nem origem pessoal.
GRANT SELECT(id,tenant_id,subject_id,context_id,area_id,policy_id,policy_version_id,valid_from,valid_until,generation,subject_restriction_epoch,
 status,revoked_at,revocation_marker_id) ON cp.access_grants TO cp_worker;
-- Worker de credenciais (CP-046): não ativa credencial de sujeito restrito (CredentialSyncStore.due).
GRANT SELECT(subject_id) ON cp.access_credentials TO cp_worker;
GRANT SELECT(id,tenant_id,target_kind,subject_id,context_id,grant_id,covered_methods,revoked_through_generation,subject_restriction_epoch,
 effective_at,journal_stream_id,journal_sequence,entry_hash,previous_entry_hash,external_durability_status) ON cp.revocation_markers TO cp_worker;

-- 8. Trilha RIGHTS_* (prefixo ACCESS_ é do registro manual da portaria, V014).
CREATE POLICY audit_rights_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'RIGHTS\_%' OR (actor_id=platform.current_actor_id() AND cp.grant_manager()
  AND resource_type IN ('ACCESS_POLICY','ACCESS_GRANT','ACCESS_SUBJECT')));
