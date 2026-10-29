-- Avisos da gestão (CP-032, sem anexos nem documentos): rascunho, público-alvo (condomínio, blocos ou unidades),
-- publicação e arquivamento pela gestão (capacidade announcements.publish, AAL2). A unidade vê somente avisos
-- publicados para o próprio público, com vínculo vigente: rascunho e arquivado não aparecem (AC-029). Abertura
-- autenticada pela própria conta é registrada uma vez, com horário do banco; abertura não é ciência jurídica nem
-- confirmação de recebimento. Texto simples, sem HTML: a tela exibe como texto, nunca como marcação.

CREATE TABLE cp.announcements (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 3 AND 160),
 body text NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 5000),
 audience text NOT NULL CHECK(audience IN ('TENANT','BLOCKS','UNITS')),
 audience_ids uuid[] NOT NULL DEFAULT '{}',
 state text NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED')),
 author_id uuid NOT NULL REFERENCES platform.accounts(id),
 published_by uuid REFERENCES platform.accounts(id),
 published_at timestamptz,
 archived_by uuid REFERENCES platform.accounts(id),
 archived_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 -- Condomínio inteiro sem lista; blocos ou unidades com 1 a 200 identificadores, sem repetição (conferida no gatilho).
 CONSTRAINT announcements_audience_ids CHECK((audience='TENANT')=(cardinality(audience_ids)=0) AND cardinality(audience_ids)<=200),
 CONSTRAINT announcements_published_fields CHECK((published_at IS NULL)=(published_by IS NULL)),
 CONSTRAINT announcements_archived_fields CHECK((archived_at IS NULL)=(archived_by IS NULL) AND (state='ARCHIVED')=(archived_at IS NOT NULL)),
 CONSTRAINT announcements_state_fields CHECK((state<>'PUBLISHED' OR published_at IS NOT NULL) AND (state<>'DRAFT' OR published_at IS NULL))
);
CREATE INDEX announcements_by_state ON cp.announcements(tenant_id,state,published_at DESC,id);

-- A aplicação não escolhe datas, autoria de publicação/arquivamento nem a versão inicial.
REVOKE ALL ON cp.announcements FROM PUBLIC;
GRANT SELECT ON cp.announcements TO cp_api;
GRANT INSERT(id,tenant_id,title,body,audience,audience_ids,author_id) ON cp.announcements TO cp_api;
GRANT UPDATE(title,body,audience,audience_ids,state,version) ON cp.announcements TO cp_api;
ALTER TABLE cp.announcements ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.announcements FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.announcements TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Público do aviso: conta ativa com vínculo vigente ligado a unidade ativa (morador, responsável, dependente ou
-- proprietário) no condomínio inteiro, na unidade indicada ou em unidade do bloco indicado.
CREATE FUNCTION cp.announcement_audience_member(target_tenant uuid,target_audience text,target_ids uuid[]) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
   AND m.role IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER')
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   AND (target_audience='TENANT' OR (target_audience='UNITS' AND u.id=ANY(target_ids))
    OR (target_audience='BLOCKS' AND u.block_id=ANY(target_ids)))) $$;
REVOKE ALL ON FUNCTION cp.announcement_audience_member(uuid,text,uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.announcement_audience_member(uuid,text,uuid[]) TO cp_api;

-- A gestão vê tudo do condomínio; a unidade, só o publicado para o próprio público.
CREATE POLICY announcements_read_scope ON cp.announcements AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('announcements.publish')
  OR (state='PUBLISHED' AND cp.announcement_audience_member(tenant_id,audience,audience_ids)));
CREATE POLICY announcements_draft_insert ON cp.announcements AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('announcements.publish') AND author_id=platform.current_actor_id() AND state='DRAFT');
CREATE POLICY announcements_manage ON cp.announcements AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('announcements.publish')) WITH CHECK(platform.has_capability('announcements.publish'));

-- Conteúdo e público só mudam no rascunho, pela versão lida (+1). Rascunho -> publicado (autoria e horário do banco,
-- público com ao menos um alvo válido), rascunho -> arquivado (descartado) e publicado -> arquivado. Publicado não
-- volta a rascunho nem muda de texto: para corrigir, arquivar e publicar outro. Alvos existem no condomínio.
CREATE FUNCTION cp.announcement_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 found integer;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF NEW.tenant_id<>OLD.tenant_id OR NEW.author_id<>OLD.author_id OR NEW.created_at<>OLD.created_at THEN
   RAISE EXCEPTION 'Aviso não muda de condomínio, autoria ou criação.' USING ERRCODE='23514';
  END IF;
  IF NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Versão do aviso fora de ordem.' USING ERRCODE='23514'; END IF;
  IF OLD.state<>'DRAFT' AND (NEW.title<>OLD.title OR NEW.body<>OLD.body OR NEW.audience<>OLD.audience
     OR NEW.audience_ids<>OLD.audience_ids) THEN
   RAISE EXCEPTION 'Aviso publicado ou arquivado não muda de texto nem de público.' USING ERRCODE='23514';
  END IF;
  IF OLD.state='DRAFT' AND NEW.state='PUBLISHED' THEN
   NEW.published_by := platform.current_actor_id(); NEW.published_at := clock_timestamp();
  ELSIF NEW.state='ARCHIVED' AND OLD.state IN ('DRAFT','PUBLISHED') THEN
   NEW.archived_by := platform.current_actor_id(); NEW.archived_at := clock_timestamp();
   NEW.published_by := OLD.published_by; NEW.published_at := OLD.published_at;
  ELSIF NOT (OLD.state='DRAFT' AND NEW.state='DRAFT') THEN
   RAISE EXCEPTION 'Transição de aviso não permitida.' USING ERRCODE='23514';
  ELSE
   NEW.published_by := OLD.published_by; NEW.published_at := OLD.published_at;
   NEW.archived_by := OLD.archived_by; NEW.archived_at := OLD.archived_at;
  END IF;
  NEW.updated_at := clock_timestamp();
 ELSE
  NEW.state := 'DRAFT'; NEW.version := 0; NEW.published_by := NULL; NEW.published_at := NULL;
  NEW.archived_by := NULL; NEW.archived_at := NULL;
  NEW.created_at := clock_timestamp(); NEW.updated_at := NEW.created_at;
 END IF;
 IF NEW.state<>'ARCHIVED' AND NEW.audience<>'TENANT' THEN
  IF cardinality(NEW.audience_ids)<>(SELECT count(DISTINCT x) FROM unnest(NEW.audience_ids) x) THEN
   RAISE EXCEPTION 'Público com alvo repetido.' USING ERRCODE='23514';
  END IF;
  IF NEW.audience='BLOCKS' THEN
   SELECT count(*) INTO found FROM cp.blocks b WHERE b.tenant_id=NEW.tenant_id AND b.id=ANY(NEW.audience_ids);
  ELSE
   SELECT count(*) INTO found FROM cp.units u WHERE u.tenant_id=NEW.tenant_id AND u.id=ANY(NEW.audience_ids);
  END IF;
  IF found<>cardinality(NEW.audience_ids) THEN
   RAISE EXCEPTION 'Público com bloco ou unidade fora do condomínio.' USING ERRCODE='23503';
  END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.announcement_guard() FROM PUBLIC;
CREATE TRIGGER announcements_guard BEFORE INSERT OR UPDATE ON cp.announcements
 FOR EACH ROW EXECUTE FUNCTION cp.announcement_guard();

-- Abertura autenticada: uma por conta e aviso, horário do banco, só de aviso publicado visível a quem abre.
CREATE TABLE cp.announcement_reads (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 announcement_id uuid NOT NULL,
 account_id uuid NOT NULL REFERENCES platform.accounts(id),
 read_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,announcement_id,account_id),
 FOREIGN KEY(tenant_id,announcement_id) REFERENCES cp.announcements(tenant_id,id)
);
REVOKE ALL ON cp.announcement_reads FROM PUBLIC;
GRANT SELECT ON cp.announcement_reads TO cp_api;
GRANT INSERT(tenant_id,announcement_id,account_id) ON cp.announcement_reads TO cp_api;
ALTER TABLE cp.announcement_reads ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.announcement_reads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.announcement_reads TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
-- Cada pessoa vê as próprias aberturas; a gestão vê as do condomínio (a API só devolve contagens).
CREATE POLICY announcement_reads_scope ON cp.announcement_reads AS RESTRICTIVE FOR SELECT TO cp_api
 USING(account_id=platform.current_actor_id() OR platform.has_capability('announcements.publish'));
CREATE POLICY announcement_reads_open ON cp.announcement_reads AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(account_id=platform.current_actor_id() AND EXISTS(SELECT 1 FROM cp.announcements x
  WHERE x.tenant_id=cp.announcement_reads.tenant_id AND x.id=cp.announcement_reads.announcement_id AND x.state='PUBLISHED'
   AND cp.announcement_audience_member(x.tenant_id,x.audience,x.audience_ids)));

-- Eventos e trilha dos avisos só pela gestão, em nome da própria conta e de aviso existente no condomínio.
CREATE POLICY outbox_announcement_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type NOT LIKE 'ANNOUNCEMENT\_%' OR (platform.has_capability('announcements.publish') AND EXISTS(SELECT 1
  FROM cp.announcements x WHERE x.tenant_id=cp.outbox.tenant_id AND x.id=cp.outbox.aggregate_id)));
CREATE POLICY audit_announcement_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'ANNOUNCEMENT\_%' OR (platform.has_capability('announcements.publish') AND actor_id=platform.current_actor_id()
  AND resource_type='ANNOUNCEMENT' AND EXISTS(SELECT 1 FROM cp.announcements x WHERE x.tenant_id=cp.audit_log.tenant_id
   AND x.id=cp.audit_log.resource_id)));
