-- AC-012/AC-013 (pacote Acesso Facial v1.1): publicação dos direitos da V044 por destino (sujeito × equipamento).
--  - cp.rights_publications: trabalho (job) com o documento de direitos compilado pelo banco para um equipamento. É
--    desejo a aplicar, nunca sucesso físico: os estados ACK, APPLIED_UNVERIFIED, CONFIRMED, PARTIAL, FAILED e UNKNOWN
--    vêm só do relatório de quem fala com o equipamento (worker/gateway, fatia seguinte) e CONFIRMED exige leitura de volta com o
--    mesmo hash do documento. Versão desejada monotônica (sequência global das gerações da V044): o trabalho novo do
--    mesmo destino supera o anterior em aberto e resultado de trabalho superado é recusado.
--  - Origem PLAN: compilada pelo banco a partir dos direitos vigentes e da topologia ativa (gestão com vínculos e
--    publicação, varredura de destinos ligada). Origem REVOCATION: gerada na mesma transação de cada marca de revogação
--    da V044 a partir do último documento do destino (sem depender de ler a topologia): sujeito restrito vira REVOKE_ALL
--    (face, cartão, PIN, QR e intermediário); direito/contexto revogado sai do documento (REVOKE_RIGHTS se nada sobra).
--    Revogação e redução têm prioridade 0 (saem antes de qualquer ampliação).
--  - Passagem com sentido UNKNOWN, ponto não comissionado, vínculo não ativo ou equipamento retirado não recebem direito
--    (falha fechada). Mesmo lock por condomínio das V041-V044 ('cp.installation:'||tenant). Nada aqui fala com hardware.

-- 1. Trabalhos de publicação por destino.
CREATE TABLE cp.rights_publications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 operation_id uuid NOT NULL,
 subject_id uuid NOT NULL,
 device_id uuid NOT NULL,
 origin text NOT NULL CHECK(origin IN ('PLAN','REVOCATION')),
 revocation_marker_id uuid,
 action text NOT NULL DEFAULT 'APPLY_RIGHTS' CHECK(action IN ('APPLY_RIGHTS','REVOKE_RIGHTS','REVOKE_ALL')),
 priority smallint NOT NULL DEFAULT 1 CHECK(priority IN (0,1)),
 -- Versão desejada (gateway: desiredVersion), monotônica; maior que qualquer geração ou marca anterior.
 desired_version bigint NOT NULL DEFAULT 0,
 -- Maior geração entre os direitos do documento (0 quando nenhum) e época do sujeito na compilação.
 rights_generation bigint NOT NULL DEFAULT 0 CHECK(rights_generation>=0),
 subject_restriction_epoch bigint NOT NULL DEFAULT 0 CHECK(subject_restriction_epoch>=0),
 installation_revision integer NOT NULL DEFAULT 0 CHECK(installation_revision>=0),
 document jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(document)='object' AND length(document::text)<=262144),
 document_hash text NOT NULL DEFAULT '',
 grant_ids uuid[] NOT NULL DEFAULT '{}',
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','ACK','APPLIED_UNVERIFIED','CONFIRMED','PARTIAL','FAILED','UNKNOWN','SUPERSEDED')),
 confirmation_level text NOT NULL DEFAULT 'NONE' CHECK(confirmation_level IN ('NONE','TRANSPORT_ACK','STATE_READ')),
 observed_hash text CHECK(observed_hash IS NULL OR observed_hash ~ '^[0-9a-f]{64}$'),
 result_code text CHECK(result_code IS NULL OR result_code ~ '^[A-Z0-9_]{1,64}$'),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 1000000),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 reported_at timestamptz,
 confirmed_at timestamptz,
 superseded_by uuid,
 requested_by uuid REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,subject_id,device_id,desired_version),
 FOREIGN KEY(tenant_id,subject_id) REFERENCES cp.access_subjects(tenant_id,id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES cp.access_devices(tenant_id,id),
 FOREIGN KEY(tenant_id,revocation_marker_id) REFERENCES cp.revocation_markers(tenant_id,id),
 CHECK((origin='REVOCATION')=(revocation_marker_id IS NOT NULL)),
 CHECK(origin<>'REVOCATION' OR priority=0),
 CHECK((action='APPLY_RIGHTS')=(cardinality(grant_ids)>0)),
 -- ACK de transporte não é aplicação comprovada; confirmação só com leitura de volta igual ao desejado.
 CHECK(state NOT IN ('ACK','APPLIED_UNVERIFIED') OR confirmation_level='TRANSPORT_ACK'),
 CHECK((state='CONFIRMED')=(confirmation_level='STATE_READ' AND observed_hash IS NOT NULL AND observed_hash=document_hash AND confirmed_at IS NOT NULL)),
 CHECK(state<>'PENDING' OR (confirmation_level='NONE' AND reported_at IS NULL)),
 CHECK((state='SUPERSEDED')=(superseded_by IS NOT NULL))
);
CREATE INDEX rights_publications_queue ON cp.rights_publications(tenant_id,priority,next_attempt_at,desired_version)
 WHERE state NOT IN ('CONFIRMED','SUPERSEDED');
CREATE INDEX rights_publications_destination ON cp.rights_publications(tenant_id,subject_id,device_id,desired_version DESC);
CREATE INDEX rights_publications_operation ON cp.rights_publications(tenant_id,operation_id,state);
CREATE INDEX rights_publications_marker ON cp.rights_publications(tenant_id,revocation_marker_id) WHERE revocation_marker_id IS NOT NULL;

-- 2. Compilação. Alvos do sujeito: cada direito ativo, por passagem comissionada com vínculo ativo cujo sentido leva à
-- área do direito, em cada equipamento que atende esse sentido: o controlador do vínculo e o leitor (de entrada ou de
-- saída) cujo sentido no mapa comissionado (direction_map, V042; padrão FROM_TO para o de entrada e TO_FROM para o de
-- saída) é o de quem entra na área. Falha fechada: sentido UNKNOWN, ponto não comissionado ou o que não for visível nega.
CREATE FUNCTION cp.rights_targets(target_tenant uuid,target_subject uuid)
RETURNS TABLE(grant_id uuid,area_id uuid,point_id uuid,binding_id uuid,binding_revision integer,channel_id uuid,direction text,device_id uuid,
 device_role text,generation bigint,valid_from timestamptz,valid_until timestamptz)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT g.id,g.area_id,p.id,b.id,b.binding_revision,b.actuator_channel_id,s.direction,r.device_id,r.device_role,g.generation,g.valid_from,g.valid_until
  FROM cp.access_grants g
  JOIN cp.access_points p ON p.tenant_id=g.tenant_id AND p.status='COMMISSIONED'
  JOIN cp.point_bindings b ON b.tenant_id=p.tenant_id AND b.point_id=p.id AND b.status='ACTIVE'
  CROSS JOIN LATERAL (SELECT 'FROM_TO'::text direction WHERE p.to_area_id=g.area_id AND p.flow_direction IN ('FROM_TO','BOTH')
   UNION ALL SELECT 'TO_FROM' WHERE p.from_area_id=g.area_id AND p.flow_direction IN ('TO_FROM','BOTH')) s
  CROSS JOIN LATERAL (SELECT b.controller_device_id device_id,'CONTROLLER'::text device_role
   UNION ALL SELECT b.entry_reader_device_id,'ENTRY_READER' WHERE b.entry_reader_device_id IS NOT NULL
    AND coalesce(b.direction_map->>'entryReader','FROM_TO')=s.direction
   UNION ALL SELECT b.exit_reader_device_id,'EXIT_READER' WHERE b.exit_reader_device_id IS NOT NULL
    AND coalesce(b.direction_map->>'exitReader','TO_FROM')=s.direction) r
  WHERE g.tenant_id=target_tenant AND g.subject_id=target_subject AND g.status='ACTIVE' $$;
REVOKE ALL ON FUNCTION cp.rights_targets(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rights_targets(uuid,uuid) TO cp_api;

-- Entradas do documento de um equipamento: alvos dele cujo direito vale na origem (no início da janela, quando futura).
CREATE FUNCTION cp.rights_entries(target_tenant uuid,target_subject uuid,target_device uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce(jsonb_agg(jsonb_build_object('grantId',x.grant_id,'areaId',x.area_id,'pointId',x.point_id,'bindingId',x.binding_id,
   'bindingRevision',x.binding_revision,'channelId',x.channel_id,'direction',x.direction,'deviceRole',x.device_role,'generation',x.generation,
   'validFrom',to_char(x.valid_from AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
   'validUntil',to_char(x.valid_until AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
   ORDER BY x.grant_id,x.point_id,x.direction,x.device_role),'[]'::jsonb)
  FROM cp.rights_targets(target_tenant,target_subject) x
  JOIN cp.access_devices d ON d.tenant_id=target_tenant AND d.id=x.device_id AND d.status='ACTIVE'
  WHERE x.device_id=target_device AND x.valid_until>clock_timestamp()
   AND cp.access_grant_current(target_tenant,x.grant_id,greatest(clock_timestamp(),x.valid_from)) $$;
REVOKE ALL ON FUNCTION cp.rights_entries(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rights_entries(uuid,uuid,uuid) TO cp_api;

-- Destinos de um sujeito: equipamentos dos alvos vigentes e equipamentos que já receberam trabalho dele (para retirar
-- o que deixou de valer).
CREATE FUNCTION cp.rights_destinations(target_tenant uuid,target_subject uuid) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT x.device_id FROM cp.rights_targets(target_tenant,target_subject) x
  UNION SELECT r.device_id FROM cp.rights_publications r WHERE r.tenant_id=target_tenant AND r.subject_id=target_subject $$;
REVOKE ALL ON FUNCTION cp.rights_destinations(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rights_destinations(uuid,uuid) TO cp_api;

CREATE FUNCTION cp.rights_document_hash(doc jsonb) RETURNS text
LANGUAGE sql IMMUTABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT encode(sha256(convert_to(doc::text,'UTF8')),'hex') $$;
REVOKE ALL ON FUNCTION cp.rights_document_hash(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rights_document_hash(jsonb) TO cp_api,cp_worker;

-- Documento que a compilação gravaria agora para o destino (mesma função do gatilho e do planejador).
CREATE FUNCTION cp.rights_compiled(target_tenant uuid,target_subject uuid,target_device uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT CASE WHEN s.status<>'ACTIVE' THEN jsonb_build_object('schemaVersion','1.0','subjectRef',s.external_ref,'action','REVOKE_ALL',
    'subjectRestrictionEpoch',s.restriction_epoch,'coveredMethods',jsonb_build_array('FACE','CARD','PIN','QR','INTERMEDIARY_GRANT'),'entries','[]'::jsonb)
   ELSE (SELECT jsonb_build_object('schemaVersion','1.0','subjectRef',s.external_ref,
     'action',CASE WHEN jsonb_array_length(e)>0 THEN 'APPLY_RIGHTS' ELSE 'REVOKE_RIGHTS' END,
     'subjectRestrictionEpoch',s.restriction_epoch,'coveredMethods','[]'::jsonb,'entries',e)
    FROM cp.rights_entries(target_tenant,target_subject,target_device) e) END
  FROM cp.access_subjects s WHERE s.tenant_id=target_tenant AND s.id=target_subject $$;
REVOKE ALL ON FUNCTION cp.rights_compiled(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rights_compiled(uuid,uuid,uuid) TO cp_api;

-- 3. Guarda: o banco compila o documento, a ação, a prioridade e a versão; o cliente informa só o destino e a origem.
CREATE FUNCTION cp.rights_publications_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE last record; m record; doc jsonb; entries jsonb; kept jsonb; previous_pairs text[]; new_pairs text[];
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.installation:'||NEW.tenant_id::text,0));
 IF TG_OP='UPDATE' THEN
  IF (NEW.id,NEW.tenant_id,NEW.operation_id,NEW.subject_id,NEW.device_id,NEW.origin,NEW.revocation_marker_id,NEW.action,NEW.priority,NEW.desired_version,
      NEW.rights_generation,NEW.subject_restriction_epoch,NEW.installation_revision,NEW.document,NEW.document_hash,NEW.grant_ids,NEW.requested_by,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.operation_id,OLD.subject_id,OLD.device_id,OLD.origin,OLD.revocation_marker_id,OLD.action,OLD.priority,
      OLD.desired_version,OLD.rights_generation,OLD.subject_restriction_epoch,OLD.installation_revision,OLD.document,OLD.document_hash,OLD.grant_ids,
      OLD.requested_by,OLD.created_at)
    OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'trabalho de publicação é imutável; só o estado avança' USING ERRCODE='23514';
  END IF;
  IF OLD.state IN ('CONFIRMED','SUPERSEDED') THEN
   RAISE EXCEPTION 'trabalho confirmado ou superado não recebe resultado' USING ERRCODE='23514';
  END IF;
  IF NEW.state='SUPERSEDED' THEN
   -- Só um trabalho mais novo do mesmo destino supera (gatilho de inserção, mesma transação).
   IF NOT EXISTS(SELECT 1 FROM cp.rights_publications r WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.superseded_by AND r.subject_id=NEW.subject_id
     AND r.device_id=NEW.device_id AND r.desired_version>NEW.desired_version) THEN
    RAISE EXCEPTION 'superação exige trabalho mais novo do mesmo destino' USING ERRCODE='23514';
   END IF;
   IF (NEW.confirmation_level,NEW.observed_hash,NEW.result_code,NEW.attempts,NEW.reported_at,NEW.confirmed_at)
     IS DISTINCT FROM (OLD.confirmation_level,OLD.observed_hash,OLD.result_code,OLD.attempts,OLD.reported_at,OLD.confirmed_at) THEN
    RAISE EXCEPTION 'superação não altera o resultado' USING ERRCODE='23514';
   END IF;
  ELSE
   -- Relatório do executor: estado de resultado, relógio do banco, nunca volta a PENDING nem confirma sem leitura igual.
   IF NEW.state='PENDING' OR NEW.superseded_by IS NOT NULL THEN
    RAISE EXCEPTION 'resultado inválido para o trabalho' USING ERRCODE='23514';
   END IF;
   IF NEW.attempts<OLD.attempts THEN RAISE EXCEPTION 'tentativas não regridem' USING ERRCODE='23514'; END IF;
   NEW.reported_at:=clock_timestamp();
   NEW.confirmed_at:=CASE WHEN NEW.state='CONFIRMED' THEN NEW.reported_at END;
  END IF;
  NEW.updated_at:=clock_timestamp();
  RETURN NEW;
 END IF;

 IF NEW.state<>'PENDING' OR NEW.confirmation_level<>'NONE' OR NEW.observed_hash IS NOT NULL OR NEW.result_code IS NOT NULL OR NEW.attempts<>0
   OR NEW.reported_at IS NOT NULL OR NEW.confirmed_at IS NOT NULL OR NEW.superseded_by IS NOT NULL OR NEW.version<>0 THEN
  RAISE EXCEPTION 'trabalho nasce pendente' USING ERRCODE='23514';
 END IF;
 SELECT * INTO last FROM cp.rights_publications r WHERE r.tenant_id=NEW.tenant_id AND r.subject_id=NEW.subject_id AND r.device_id=NEW.device_id
  AND r.state<>'SUPERSEDED' ORDER BY r.desired_version DESC LIMIT 1;
 IF NEW.origin='PLAN' THEN
  IF NOT cp.grant_manager() OR NOT cp.policy_destination_scan() THEN
   RAISE EXCEPTION 'compilação exige gestão de vínculos e publicação com a varredura de destinos' USING ERRCODE='42501';
  END IF;
  IF NEW.requested_by IS DISTINCT FROM platform.current_actor_id() THEN
   RAISE EXCEPTION 'autoria da publicação é o ator da sessão' USING ERRCODE='42501';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM cp.access_devices d WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.device_id) THEN
   RAISE EXCEPTION 'equipamento inexistente' USING ERRCODE='23503';
  END IF;
  doc:=cp.rights_compiled(NEW.tenant_id,NEW.subject_id,NEW.device_id);
  IF doc IS NULL THEN RAISE EXCEPTION 'sujeito inexistente' USING ERRCODE='23503'; END IF;
  IF jsonb_array_length(doc->'entries')>512 THEN RAISE EXCEPTION 'documento acima de 512 entradas' USING ERRCODE='23514'; END IF;
  IF last.id IS NULL AND doc->>'action'<>'APPLY_RIGHTS' THEN
   RAISE EXCEPTION 'nada a retirar de destino que nunca recebeu' USING ERRCODE='23514';
  END IF;
  IF last.id IS NOT NULL AND last.document_hash=cp.rights_document_hash(doc) THEN
   RAISE EXCEPTION 'documento igual ao último do destino' USING ERRCODE='23514';
  END IF;
  NEW.revocation_marker_id:=NULL;
 ELSE
  -- Revogação: deriva do último documento do destino, sem ler a topologia (quem revoga pode não enxergar vínculos).
  SELECT * INTO m FROM cp.revocation_markers WHERE tenant_id=NEW.tenant_id AND id=NEW.revocation_marker_id AND subject_id=NEW.subject_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'marca de revogação inexistente para o sujeito' USING ERRCODE='23503'; END IF;
  IF last.id IS NULL OR last.action='REVOKE_ALL' THEN
   RAISE EXCEPTION 'destino sem direitos publicados a retirar' USING ERRCODE='23514';
  END IF;
  IF m.target_kind='SUBJECT' THEN
   doc:=jsonb_build_object('schemaVersion','1.0','subjectRef',last.document->'subjectRef','action','REVOKE_ALL',
    'subjectRestrictionEpoch',m.subject_restriction_epoch,'coveredMethods',jsonb_build_array('FACE','CARD','PIN','QR','INTERMEDIARY_GRANT'),
    'entries','[]'::jsonb);
  ELSE
   SELECT coalesce(jsonb_agg(e ORDER BY ord),'[]'::jsonb) INTO kept FROM jsonb_array_elements(last.document->'entries') WITH ORDINALITY t(e,ord)
    WHERE EXISTS(SELECT 1 FROM cp.access_grants g WHERE g.tenant_id=NEW.tenant_id AND g.id=(e->>'grantId')::uuid AND g.status='ACTIVE');
   IF jsonb_array_length(kept)=jsonb_array_length(last.document->'entries') THEN
    RAISE EXCEPTION 'marca não retira nada deste destino' USING ERRCODE='23514';
   END IF;
   doc:=jsonb_set(jsonb_set(last.document,'{entries}',kept),'{action}',
    to_jsonb(CASE WHEN jsonb_array_length(kept)>0 THEN 'APPLY_RIGHTS' ELSE 'REVOKE_RIGHTS' END));
  END IF;
  NEW.requested_by:=platform.current_actor_id();
 END IF;
 entries:=doc->'entries';
 NEW.action:=doc->>'action';
 NEW.document:=doc;
 NEW.document_hash:=cp.rights_document_hash(doc);
 NEW.subject_restriction_epoch:=(doc->>'subjectRestrictionEpoch')::bigint;
 NEW.grant_ids:=coalesce((SELECT array_agg(DISTINCT (e->>'grantId')::uuid) FROM jsonb_array_elements(entries) e),'{}');
 NEW.rights_generation:=coalesce((SELECT max((e->>'generation')::bigint) FROM jsonb_array_elements(entries) e),0);
 NEW.installation_revision:=cp.installation_current_revision(NEW.tenant_id);
 -- A referência é tudo o que pode estar no equipamento: o último documento confirmado e todos os trabalhos posteriores
 -- (inclusive superados e ainda não confirmados). Assim, uma retirada pendente superada por uma ampliação não perde a
 -- prioridade: o sucessor que ainda não tira o que a retirada tirava continua prioridade 0.
 previous_pairs:=coalesce((SELECT array_agg(DISTINCT (e->>'grantId')||':'||(e->>'pointId')||':'||(e->>'direction')||':'||(e->>'deviceRole'))
  FROM cp.rights_publications r CROSS JOIN LATERAL jsonb_array_elements(r.document->'entries') e
  WHERE r.tenant_id=NEW.tenant_id AND r.subject_id=NEW.subject_id AND r.device_id=NEW.device_id
   AND r.desired_version>=coalesce((SELECT max(c.desired_version) FROM cp.rights_publications c WHERE c.tenant_id=NEW.tenant_id
    AND c.subject_id=NEW.subject_id AND c.device_id=NEW.device_id AND c.state='CONFIRMED'),0)),'{}');
 new_pairs:=coalesce((SELECT array_agg(DISTINCT (e->>'grantId')||':'||(e->>'pointId')||':'||(e->>'direction')||':'||(e->>'deviceRole'))
  FROM jsonb_array_elements(entries) e),'{}');
 NEW.priority:=CASE WHEN NEW.origin='REVOCATION' OR NEW.action<>'APPLY_RIGHTS' OR NOT (previous_pairs<@new_pairs) THEN 0 ELSE 1 END;
 NEW.desired_version:=nextval('cp.access_rights_generation');
 NEW.next_attempt_at:=clock_timestamp();
 NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.rights_publications_guard() FROM PUBLIC;
CREATE TRIGGER rights_publications_guard BEFORE INSERT OR UPDATE ON cp.rights_publications FOR EACH ROW EXECUTE FUNCTION cp.rights_publications_guard();

-- O trabalho novo supera o anterior em aberto do mesmo destino (um só em aberto por destino).
CREATE FUNCTION cp.rights_publications_supersede() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 UPDATE cp.rights_publications r SET state='SUPERSEDED',superseded_by=NEW.id,version=r.version+1
  WHERE r.tenant_id=NEW.tenant_id AND r.subject_id=NEW.subject_id AND r.device_id=NEW.device_id AND r.id<>NEW.id
   AND r.state NOT IN ('CONFIRMED','SUPERSEDED') AND r.desired_version<NEW.desired_version;
 IF EXISTS(SELECT 1 FROM cp.rights_publications r WHERE r.tenant_id=NEW.tenant_id AND r.subject_id=NEW.subject_id AND r.device_id=NEW.device_id
   AND r.id<>NEW.id AND r.state NOT IN ('CONFIRMED','SUPERSEDED')) THEN
  RAISE EXCEPTION 'trabalho anterior em aberto não foi superado' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.rights_publications_supersede() FROM PUBLIC;
CREATE TRIGGER rights_publications_supersede AFTER INSERT ON cp.rights_publications FOR EACH ROW EXECUTE FUNCTION cp.rights_publications_supersede();

-- 4. Cada marca de revogação publica a retirada na mesma transação, em todos os destinos do sujeito que ainda têm direito
-- publicado (depois de revocation_markers_apply, que já revogou os direitos alcançados: ordem alfabética dos gatilhos).
CREATE FUNCTION cp.revocation_markers_publish() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE r record;
BEGIN
 FOR r IN SELECT DISTINCT ON (p.device_id) p.device_id,p.action,p.document FROM cp.rights_publications p
   WHERE p.tenant_id=NEW.tenant_id AND p.subject_id=NEW.subject_id AND p.state<>'SUPERSEDED' ORDER BY p.device_id,p.desired_version DESC LOOP
  CONTINUE WHEN r.action='REVOKE_ALL';
  CONTINUE WHEN NEW.target_kind<>'SUBJECT' AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(r.document->'entries') e
   WHERE NOT EXISTS(SELECT 1 FROM cp.access_grants g WHERE g.tenant_id=NEW.tenant_id AND g.id=(e->>'grantId')::uuid AND g.status='ACTIVE'));
  INSERT INTO cp.rights_publications(tenant_id,operation_id,subject_id,device_id,origin,revocation_marker_id,priority)
   VALUES(NEW.tenant_id,NEW.operation_id,NEW.subject_id,r.device_id,'REVOCATION',NEW.id,0);
 END LOOP;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.revocation_markers_publish() FROM PUBLIC;
CREATE TRIGGER revocation_markers_publish AFTER INSERT ON cp.revocation_markers FOR EACH ROW EXECUTE FUNCTION cp.revocation_markers_publish();

-- 5. RLS: leem a gestão de direitos e a portaria (cascata); compilação só pela gestão (guarda); a revogação insere pela
-- cascata de quem revoga; a API só supera; o resultado vem do executor (cp_worker), nunca da API.
ALTER TABLE cp.rights_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.rights_publications FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.rights_publications FROM PUBLIC;
CREATE POLICY tenant_boundary ON cp.rights_publications TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY rights_publications_read ON cp.rights_publications AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.grant_reader());
CREATE POLICY rights_publications_create ON cp.rights_publications AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(cp.grant_reader() AND (origin='REVOCATION' OR cp.grant_manager()));
CREATE POLICY rights_publications_supersede ON cp.rights_publications AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.grant_reader()) WITH CHECK(state='SUPERSEDED');
GRANT SELECT ON cp.rights_publications TO cp_api;
GRANT INSERT(id,tenant_id,operation_id,subject_id,device_id,origin,revocation_marker_id,priority,requested_by) ON cp.rights_publications TO cp_api;
GRANT UPDATE(state,superseded_by,version) ON cp.rights_publications TO cp_api;
-- Executor (worker hoje; API do gateway na fatia seguinte): lê a fila sem autoria e grava só o resultado.
GRANT SELECT(id,tenant_id,operation_id,subject_id,device_id,origin,action,priority,desired_version,rights_generation,subject_restriction_epoch,
 installation_revision,document,document_hash,grant_ids,state,confirmation_level,observed_hash,result_code,attempts,next_attempt_at,reported_at,
 confirmed_at,superseded_by,version) ON cp.rights_publications TO cp_worker;
GRANT UPDATE(state,confirmation_level,observed_hash,result_code,attempts,next_attempt_at,version) ON cp.rights_publications TO cp_worker;
GRANT EXECUTE ON FUNCTION cp.installation_current_revision(uuid) TO cp_worker;

-- 6. Trilha RIGHTS_PUBLICATION_* da gestão (o prefixo RIGHTS_ já exige gestão com vínculos e publicação, V044).
DROP POLICY audit_rights_actions ON cp.audit_log;
CREATE POLICY audit_rights_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'RIGHTS\_%' OR (actor_id=platform.current_actor_id() AND cp.grant_manager()
  AND resource_type IN ('ACCESS_POLICY','ACCESS_GRANT','ACCESS_SUBJECT','ACCESS_PUBLICATION')));
