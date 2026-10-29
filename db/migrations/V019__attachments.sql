-- Arquivos privados (CP-017), primeiro uso: anexos de pedidos da unidade (parte do CP-033). O objeto vai para um bucket
-- privado por URL assinada curta emitida pela API; a linha nasce PENDING com o tipo e o tamanho declarados e só fica
-- AVAILABLE depois que a API confere no Storage o tamanho e a assinatura do conteúdo (o tipo não vem da extensão nem do
-- navegador). Arquivo disfarçado ou de tamanho diferente fica REJECTED. O download sempre passa pela API, que confere
-- de novo se o pedido continua visível para quem pede e emite um link curto. Nada é apagado pela aplicação.

CREATE TABLE cp.attachments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 owner_id uuid NOT NULL REFERENCES platform.accounts(id),
 purpose text NOT NULL CHECK(purpose='REQUEST_ATTACHMENT'),
 entity_type text NOT NULL CHECK(entity_type='SERVICE_REQUEST'),
 entity_id uuid NOT NULL,
 file_name text NOT NULL CHECK(length(btrim(file_name)) BETWEEN 1 AND 120),
 mime_type text NOT NULL CHECK(mime_type IN ('image/jpeg','image/png','application/pdf')),
 size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520
  AND (mime_type='application/pdf' OR size_bytes<=10485760)),
 bucket text NOT NULL CHECK(bucket ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
 object_key text NOT NULL,
 sha256 text CHECK(sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','AVAILABLE','REJECTED')),
 rejection text CHECK(rejection IS NULL OR rejection IN ('SIZE_MISMATCH','TYPE_MISMATCH','NOT_UPLOADED','UPLOAD_EXPIRED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 upload_expires_at timestamptz NOT NULL,
 completed_at timestamptz,
 UNIQUE(tenant_id,id),
 UNIQUE(bucket,object_key),
 FOREIGN KEY(tenant_id,entity_id) REFERENCES cp.service_requests(tenant_id,id),
 -- Chave interna derivada do condomínio e do próprio anexo: nunca do nome enviado pelo usuário.
 CONSTRAINT attachments_object_key CHECK(object_key=tenant_id::text||'/'||id::text),
 CONSTRAINT attachments_state_fields CHECK((state='PENDING')=(completed_at IS NULL)
  AND (state='AVAILABLE')=(sha256 IS NOT NULL) AND (state='REJECTED')=(rejection IS NOT NULL))
);
CREATE INDEX attachments_by_entity ON cp.attachments(tenant_id,entity_type,entity_id,created_at,id);

-- A aplicação não escolhe estado inicial, horários, conferência nem chave fora do padrão; não apaga.
REVOKE ALL ON cp.attachments FROM PUBLIC;
GRANT SELECT ON cp.attachments TO cp_api;
GRANT INSERT(id,tenant_id,owner_id,purpose,entity_type,entity_id,file_name,mime_type,size_bytes,bucket,object_key)
 ON cp.attachments TO cp_api;
GRANT UPDATE(state,rejection,sha256) ON cp.attachments TO cp_api;
ALTER TABLE cp.attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.attachments TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Leitura: o pedido precisa estar visível para quem lê (as políticas do próprio pedido valem dentro da subconsulta:
-- unidade com vínculo vigente ou gestão com requests.manage). Quem enviou e perdeu o vínculo deixa de ver.
CREATE POLICY attachments_read ON cp.attachments AS RESTRICTIVE FOR SELECT TO cp_api
 USING(EXISTS(SELECT 1 FROM cp.service_requests r WHERE r.tenant_id=cp.attachments.tenant_id AND r.id=cp.attachments.entity_id));
-- Envio em nome da própria conta, em pedido visível e ainda não fechado.
CREATE POLICY attachments_insert ON cp.attachments AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(owner_id=platform.current_actor_id() AND EXISTS(SELECT 1 FROM cp.service_requests r
  WHERE r.tenant_id=cp.attachments.tenant_id AND r.id=cp.attachments.entity_id AND r.state<>'CLOSED'));
-- Conclusão só por quem enviou (USING(true) para negar com 42501 em vez de "não encontrado").
CREATE POLICY attachments_complete ON cp.attachments AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(true) WITH CHECK(owner_id=platform.current_actor_id());

-- Nasce PENDING, com prazo do upload pelo banco (15 minutos) e no máximo 10 anexos liberados ou ainda concluíveis por
-- pedido (pendente vencido não ocupa vaga), em série pela trava do pedido.
-- Depois só PENDING -> AVAILABLE (com hash) ou PENDING -> REJECTED (com motivo); o resto não muda.
CREATE FUNCTION cp.attachment_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 active integer;
BEGIN
 IF TG_OP='INSERT' THEN
  NEW.state := 'PENDING'; NEW.rejection := NULL; NEW.sha256 := NULL; NEW.completed_at := NULL;
  NEW.created_at := clock_timestamp(); NEW.upload_expires_at := NEW.created_at+interval '15 minutes';
  PERFORM pg_advisory_xact_lock(hashtextextended('cp.attachments:'||NEW.tenant_id::text||':'||NEW.entity_id::text,0));
  SELECT count(*) INTO active FROM cp.attachments a WHERE a.tenant_id=NEW.tenant_id AND a.entity_type=NEW.entity_type
   AND a.entity_id=NEW.entity_id
   AND (a.state='AVAILABLE' OR a.state='PENDING' AND a.upload_expires_at+interval '5 minutes'>clock_timestamp());
  IF active>=10 THEN RAISE EXCEPTION 'Limite de anexos do pedido.' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.owner_id<>OLD.owner_id OR NEW.purpose<>OLD.purpose OR NEW.entity_type<>OLD.entity_type
    OR NEW.entity_id<>OLD.entity_id OR NEW.file_name<>OLD.file_name OR NEW.mime_type<>OLD.mime_type
    OR NEW.size_bytes<>OLD.size_bytes OR NEW.bucket<>OLD.bucket OR NEW.object_key<>OLD.object_key
    OR NEW.created_at<>OLD.created_at OR NEW.upload_expires_at<>OLD.upload_expires_at THEN
  RAISE EXCEPTION 'Anexo não muda de conteúdo declarado.' USING ERRCODE='23514';
 END IF;
 IF OLD.state<>'PENDING' OR NEW.state NOT IN ('AVAILABLE','REJECTED') THEN
  RAISE EXCEPTION 'Transição de anexo não permitida.' USING ERRCODE='23514';
 END IF;
 IF NEW.state='AVAILABLE' AND clock_timestamp()>OLD.upload_expires_at+interval '5 minutes' THEN
  RAISE EXCEPTION 'Envio do anexo expirado.' USING ERRCODE='23514';
 END IF;
 NEW.completed_at := clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.attachment_guard() FROM PUBLIC;
CREATE TRIGGER attachments_guard BEFORE INSERT OR UPDATE ON cp.attachments
 FOR EACH ROW EXECUTE FUNCTION cp.attachment_guard();

-- Trilha dos anexos em nome da própria conta e de anexo visível no condomínio.
CREATE POLICY audit_attachment_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'ATTACHMENT\_%' OR (actor_id=platform.current_actor_id() AND resource_type='ATTACHMENT'
  AND EXISTS(SELECT 1 FROM cp.attachments a WHERE a.tenant_id=cp.audit_log.tenant_id AND a.id=cp.audit_log.resource_id)));
