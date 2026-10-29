-- Importação de blocos e unidades em staging (CP-016, recorte de unidades): um lote guarda a origem (nome do arquivo),
-- o SHA-256 do conteúdo, o mapa de colunas e o resultado de cada linha antes de qualquer gravação final. Aprovar cria
-- só o que ainda não existe; lote com linha inválida ou repetida só é aprovado com aceite explícito do parcial. O mesmo
-- arquivo aberto de novo devolve o mesmo lote (nunca dois lotes vivos com o mesmo hash) e reaprovar não cria nada.
-- Pessoas e vínculos não são importados: vínculo continua nascendo de convite revisado (PRD, seção 9).
-- Arquivo original privado no Storage depende do CP-017; aqui ficam o hash e os campos normalizados de cada linha.

CREATE TABLE cp.import_batches (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 entity_type text NOT NULL CHECK(entity_type='UNITS'),
 source text NOT NULL CHECK(length(btrim(source)) BETWEEN 1 AND 200),
 file_hash text NOT NULL CHECK(file_hash ~ '^[0-9a-f]{64}$'),
 mapping_version integer NOT NULL CHECK(mapping_version=1),
 state text NOT NULL DEFAULT 'PREVIEW' CHECK(state IN ('PREVIEW','APPROVED','DISCARDED')),
 total_rows integer NOT NULL CHECK(total_rows BETWEEN 1 AND 2000),
 valid_rows integer NOT NULL CHECK(valid_rows>=0),
 invalid_rows integer NOT NULL CHECK(invalid_rows>=0),
 duplicate_rows integer NOT NULL CHECK(duplicate_rows>=0),
 partial boolean NOT NULL DEFAULT false,
 created_units integer CHECK(created_units>=0),
 existing_units integer CHECK(existing_units>=0),
 created_blocks integer CHECK(created_blocks>=0),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 decided_by uuid REFERENCES platform.accounts(id),
 decided_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 CONSTRAINT import_batches_totals CHECK(valid_rows+invalid_rows+duplicate_rows=total_rows),
 CONSTRAINT import_batches_decision CHECK((state='PREVIEW')=(decided_at IS NULL) AND (decided_at IS NULL)=(decided_by IS NULL)),
 CONSTRAINT import_batches_counts CHECK((state='APPROVED')=(created_units IS NOT NULL)
  AND (created_units IS NULL)=(existing_units IS NULL) AND (created_units IS NULL)=(created_blocks IS NULL)
  AND (state<>'APPROVED' OR created_units+existing_units=valid_rows)),
 CONSTRAINT import_batches_partial CHECK(state<>'APPROVED' OR partial=(invalid_rows+duplicate_rows>0))
);
-- Um lote vivo (em prévia ou aprovado) por arquivo e tipo: repetir o envio reencontra o lote.
CREATE UNIQUE INDEX import_batches_live_file ON cp.import_batches(tenant_id,entity_type,file_hash) WHERE state<>'DISCARDED';
CREATE INDEX import_batches_recent ON cp.import_batches(tenant_id,created_at DESC,id);

CREATE TABLE cp.import_rows (
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 batch_id uuid NOT NULL,
 line_number integer NOT NULL CHECK(line_number BETWEEN 2 AND 2001),
 block_name text CHECK(block_name IS NULL OR length(block_name) BETWEEN 1 AND 60),
 unit_label text CHECK(unit_label IS NULL OR length(unit_label) BETWEEN 1 AND 40),
 external_id text CHECK(external_id IS NULL OR length(external_id) BETWEEN 1 AND 80),
 outcome text NOT NULL CHECK(outcome IN ('VALID','INVALID','DUPLICATE')),
 error_code text CHECK(error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
 applied text CHECK(applied IN ('CREATED','EXISTING')),
 unit_id uuid,
 PRIMARY KEY(tenant_id,batch_id,line_number),
 FOREIGN KEY(tenant_id,batch_id) REFERENCES cp.import_batches(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CONSTRAINT import_rows_outcome CHECK((outcome='INVALID')=(error_code IS NOT NULL) AND (outcome='INVALID' OR unit_label IS NOT NULL)),
 CONSTRAINT import_rows_applied CHECK((applied IS NULL)=(unit_id IS NULL) AND (applied IS NULL OR outcome='VALID'))
);

-- Sem DELETE: lote descartado continua como histórico. A aplicação não escolhe autoria, horários, estado inicial nem versão.
REVOKE ALL ON cp.import_batches,cp.import_rows FROM PUBLIC;
GRANT SELECT ON cp.import_batches,cp.import_rows TO cp_api;
GRANT INSERT(id,tenant_id,entity_type,source,file_hash,mapping_version,total_rows,valid_rows,invalid_rows,duplicate_rows)
 ON cp.import_batches TO cp_api;
GRANT UPDATE(state,partial,created_units,existing_units,created_blocks,version) ON cp.import_batches TO cp_api;
GRANT INSERT(tenant_id,batch_id,line_number,block_name,unit_label,external_id,outcome,error_code) ON cp.import_rows TO cp_api;
GRANT UPDATE(applied,unit_id) ON cp.import_rows TO cp_api;
ALTER TABLE cp.import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.import_batches FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.import_rows ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.import_rows FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.import_batches TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.import_rows TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
-- Só a gestão de unidades vê ou altera lotes; UPDATE com USING(true) para negar com 42501 em vez de "não encontrado".
CREATE POLICY import_batches_read ON cp.import_batches AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('units.manage'));
CREATE POLICY import_batches_insert ON cp.import_batches AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('units.manage'));
CREATE POLICY import_batches_update ON cp.import_batches AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(true) WITH CHECK(platform.has_capability('units.manage'));
CREATE POLICY import_rows_read ON cp.import_rows AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('units.manage'));
CREATE POLICY import_rows_insert ON cp.import_rows AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('units.manage'));
CREATE POLICY import_rows_update ON cp.import_rows AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(true) WITH CHECK(platform.has_capability('units.manage'));

-- Lote: nasce em prévia, versão 0, autoria e horário do banco. Só muda de prévia para aprovado ou descartado, pela
-- versão lida (+1); nada mais muda depois da decisão. Aprovar confere, sob a trava do lote, que as linhas gravadas
-- batem com os totais e que toda linha válida foi aplicada a uma unidade.
CREATE FUNCTION cp.import_batch_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 counted record;
BEGIN
 IF TG_OP='INSERT' THEN
  NEW.state := 'PREVIEW'; NEW.version := 0; NEW.partial := false;
  NEW.created_units := NULL; NEW.existing_units := NULL; NEW.created_blocks := NULL;
  NEW.created_by := platform.current_actor_id(); NEW.created_at := clock_timestamp();
  NEW.decided_by := NULL; NEW.decided_at := NULL;
  IF NEW.created_by IS NULL THEN RAISE EXCEPTION 'Lote sem autor.' USING ERRCODE='42501'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Versão do lote fora de ordem.' USING ERRCODE='23514'; END IF;
 IF OLD.state<>'PREVIEW' OR NEW.state NOT IN ('APPROVED','DISCARDED') THEN
  RAISE EXCEPTION 'Transição de lote não permitida.' USING ERRCODE='23514';
 END IF;
 NEW.decided_by := platform.current_actor_id(); NEW.decided_at := clock_timestamp();
 IF NEW.state='DISCARDED' THEN
  NEW.partial := false; NEW.created_units := NULL; NEW.existing_units := NULL; NEW.created_blocks := NULL;
  IF EXISTS(SELECT 1 FROM cp.import_rows r WHERE r.tenant_id=NEW.tenant_id AND r.batch_id=NEW.id AND r.applied IS NOT NULL) THEN
   RAISE EXCEPTION 'Lote com linhas aplicadas não é descartado.' USING ERRCODE='23514';
  END IF;
 ELSE
  SELECT count(*) FILTER(WHERE outcome='VALID') AS valid, count(*) FILTER(WHERE outcome='INVALID') AS invalid,
   count(*) FILTER(WHERE outcome='DUPLICATE') AS duplicate, count(*) FILTER(WHERE outcome='VALID' AND applied IS NULL) AS pending,
   count(*) FILTER(WHERE applied='CREATED') AS created, count(*) FILTER(WHERE applied='EXISTING') AS existing
  INTO counted FROM cp.import_rows r WHERE r.tenant_id=NEW.tenant_id AND r.batch_id=NEW.id;
  IF counted.valid<>OLD.valid_rows OR counted.invalid<>OLD.invalid_rows OR counted.duplicate<>OLD.duplicate_rows
     OR counted.pending<>0 OR counted.created<>NEW.created_units OR counted.existing<>NEW.existing_units THEN
   RAISE EXCEPTION 'Linhas do lote não conferem com a aprovação.' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.import_batch_guard() FROM PUBLIC;
CREATE TRIGGER import_batches_guard BEFORE INSERT OR UPDATE ON cp.import_batches
 FOR EACH ROW EXECUTE FUNCTION cp.import_batch_guard();

-- Linha: entra só em lote em prévia; depois só recebe uma vez a unidade aplicada (lote ainda em prévia, linha válida).
CREATE FUNCTION cp.import_row_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 batch_state text;
BEGIN
 SELECT b.state INTO batch_state FROM cp.import_batches b WHERE b.tenant_id=NEW.tenant_id AND b.id=NEW.batch_id;
 IF batch_state IS DISTINCT FROM 'PREVIEW' THEN RAISE EXCEPTION 'Lote já decidido.' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  NEW.applied := NULL; NEW.unit_id := NULL;
 ELSIF OLD.applied IS NOT NULL OR NEW.applied IS NULL OR OLD.outcome<>'VALID' THEN
  RAISE EXCEPTION 'Linha aplicada uma única vez.' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.import_row_guard() FROM PUBLIC;
CREATE TRIGGER import_rows_guard BEFORE INSERT OR UPDATE ON cp.import_rows
 FOR EACH ROW EXECUTE FUNCTION cp.import_row_guard();

-- Trilha da importação só pela gestão de unidades, em nome da própria conta e de lote existente no condomínio.
CREATE POLICY audit_import_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'IMPORT\_%' OR (platform.has_capability('units.manage') AND actor_id=platform.current_actor_id()
  AND resource_type='IMPORT_BATCH' AND EXISTS(SELECT 1 FROM cp.import_batches b WHERE b.tenant_id=cp.audit_log.tenant_id
   AND b.id=cp.audit_log.resource_id)));
