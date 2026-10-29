-- CP-037: link compartilhável de um convite (visita prevista). O token bruto nunca chega ao banco: só o SHA-256 em
-- hexadecimal. O link vale enquanto não for revogado e o convite estiver ativo e dentro da vigência; criar outro link
-- revoga o anterior. Quem abre o link (sem conta) vê só o convite daquele hash, pelo hash definido localmente na
-- transação, como no convite de vínculo (V007).

CREATE FUNCTION cp.current_permit_link_hash() RETURNS text
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT nullif(current_setting('app.permit_link_hash',true),'') $$;
REVOKE ALL ON FUNCTION cp.current_permit_link_hash() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.current_permit_link_hash() TO cp_api;

CREATE TABLE cp.permit_links (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 permit_id uuid NOT NULL,
 -- Copiado do convite pelo gatilho: as políticas desta tabela não consultam convites (evita recursão entre políticas).
 unit_id uuid NOT NULL,
 token_hash text NOT NULL CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 revoked_by uuid REFERENCES platform.accounts(id),
 revoked_at timestamptz,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,permit_id) REFERENCES cp.visit_permits(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK((revoked_by IS NULL)=(revoked_at IS NULL))
);
CREATE UNIQUE INDEX permit_links_token ON cp.permit_links(token_hash);
-- Um link em vigor por convite.
CREATE UNIQUE INDEX permit_links_one_active ON cp.permit_links(tenant_id,permit_id) WHERE revoked_at IS NULL;

-- Criação: unidade, vigência e autoria vêm do convite visível a quem cria (o dependente só compartilha o convite que
-- criou); horário do banco; nunca nasce revogado. Mudança: só revogar, uma vez, com horário do banco.
CREATE FUNCTION cp.permit_link_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog
AS $$
DECLARE target record;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT p.unit_id,p.state,p.valid_until,p.created_by INTO target FROM cp.visit_permits p
   WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.permit_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'convite indisponível' USING ERRCODE='42501'; END IF;
  IF target.state<>'ACTIVE' OR target.valid_until<=clock_timestamp() THEN
   RAISE EXCEPTION 'convite fora de vigência' USING ERRCODE='23514';
  END IF;
  IF NOT (cp.visit_member(NEW.tenant_id,target.unit_id,NULL)
          OR (target.created_by=NEW.created_by AND cp.visit_member(NEW.tenant_id,target.unit_id,'create'))) THEN
   RAISE EXCEPTION 'link não permitido' USING ERRCODE='42501';
  END IF;
  NEW.unit_id:=target.unit_id;
  NEW.created_at:=clock_timestamp();
  NEW.revoked_by:=NULL; NEW.revoked_at:=NULL;
  RETURN NEW;
 END IF;
 IF OLD.revoked_at IS NOT NULL OR NEW.revoked_by IS NULL THEN
  RAISE EXCEPTION 'link só pode ser revogado uma vez' USING ERRCODE='23514';
 END IF;
 NEW.revoked_at:=clock_timestamp();
 RETURN NEW;
END $$;
CREATE TRIGGER permit_links_guard BEFORE INSERT OR UPDATE ON cp.permit_links
 FOR EACH ROW EXECUTE FUNCTION cp.permit_link_guard();

REVOKE ALL ON cp.permit_links FROM PUBLIC;
GRANT SELECT ON cp.permit_links TO cp_api;
GRANT INSERT(id,tenant_id,permit_id,unit_id,token_hash,created_by) ON cp.permit_links TO cp_api;
GRANT UPDATE(revoked_by,revoked_at) ON cp.permit_links TO cp_api;
ALTER TABLE cp.permit_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.permit_links FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_boundary ON cp.permit_links TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
-- Quem abre o link: só a linha do hash apresentado, sem listar nada.
CREATE POLICY permit_link_by_token ON cp.permit_links FOR SELECT TO cp_api
 USING(token_hash=cp.current_permit_link_hash());
-- A unidade vê os links dos convites que pode acompanhar (dependente: só os que criou); a portaria não vê links.
CREATE POLICY permit_links_read_scope ON cp.permit_links AS RESTRICTIVE FOR SELECT TO cp_api
 USING(token_hash=cp.current_permit_link_hash() OR cp.visit_member(tenant_id,unit_id,NULL)
  OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create')));
CREATE POLICY permit_links_create ON cp.permit_links AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id() AND revoked_at IS NULL
  AND (cp.visit_member(tenant_id,unit_id,NULL) OR cp.visit_member(tenant_id,unit_id,'create')));
CREATE POLICY permit_links_revoke ON cp.permit_links AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.visit_member(tenant_id,unit_id,NULL) OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create')))
 WITH CHECK(revoked_by=platform.current_actor_id());

-- Link em vigor para o hash da transação. Função (e não subconsulta na política): a política de convites já é expandida
-- dentro de outras políticas (visitantes), e subconsulta ali seria recursão de políticas.
CREATE FUNCTION cp.permit_link_open(target_tenant uuid,target_permit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT cp.current_permit_link_hash() IS NOT NULL AND EXISTS(SELECT 1 FROM cp.permit_links l
  WHERE l.tenant_id=target_tenant AND (target_permit IS NULL OR l.permit_id=target_permit) AND l.revoked_at IS NULL
   AND l.token_hash=cp.current_permit_link_hash()) $$;
REVOKE ALL ON FUNCTION cp.permit_link_open(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.permit_link_open(uuid,uuid) TO cp_api;

-- Quem abre o link vê o próprio convite (e, pela política existente de visitantes, a pessoa dele) e o nome do
-- condomínio, somente enquanto o link estiver em vigor.
DROP POLICY permits_read_scope ON cp.visit_permits;
CREATE POLICY permits_read_scope ON cp.visit_permits AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.visit_member(tenant_id,unit_id,NULL)
  OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create'))
  OR cp.permit_link_open(tenant_id,id));
-- Sem contexto de condomínio: quem abre o link nunca define app.tenant_id (que liberaria as tabelas só por condomínio).
CREATE POLICY permit_by_link ON cp.visit_permits FOR SELECT TO cp_api USING(cp.permit_link_open(tenant_id,id));
CREATE FUNCTION cp.permit_link_visitor(target_tenant uuid,target_visitor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT cp.current_permit_link_hash() IS NOT NULL AND EXISTS(SELECT 1 FROM cp.visit_permits p
  WHERE p.tenant_id=target_tenant AND p.visitor_id=target_visitor AND cp.permit_link_open(p.tenant_id,p.id)) $$;
REVOKE ALL ON FUNCTION cp.permit_link_visitor(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.permit_link_visitor(uuid,uuid) TO cp_api;
CREATE POLICY visitor_by_link ON cp.visitors FOR SELECT TO cp_api USING(cp.permit_link_visitor(tenant_id,id));
CREATE POLICY tenant_permit_link_read ON platform.tenants FOR SELECT TO cp_api
 USING(active AND cp.permit_link_open(id,NULL));
