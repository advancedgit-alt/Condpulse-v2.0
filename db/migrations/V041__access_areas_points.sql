-- AC-004 (pacote Acesso Facial v1.1), parte 1: áreas e pontos de passagem por condomínio.
--  - cp.access_areas: espaço lógico/físico com regras, inclusive sem reserva (hall, corredor, casa de máquinas). A gestão
--    (nova capacidade area.manage, só do MANAGER) cria e edita nome, tipo, hierarquia, área reservável e estado; o MASTER de
--    implantação (installation.manage) marca rota essencial e separação física verificada. Permissão na área pai não é
--    herdada; hierarquia sem ciclo; cp.amenities continua a área reservável (amenity_id opcional).
--  - cp.access_points: passagem física entre duas áreas (exterior é uma área explícita), com sentido possível e projeto de
--    saída. Só o MASTER de implantação cria e altera; COMMISSIONED só chega pelo comissionamento (V042). Gestão e portaria
--    leem o nome do local.
-- Nada é apagado (RETIRED); RLS FORCE com isolamento por condomínio.

CREATE TABLE cp.access_areas (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 code text NOT NULL CHECK(code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
 name text NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 160),
 area_kind text NOT NULL CHECK(area_kind IN ('COMMON_AREA','AMENITY','RECEPTION','TECHNICAL_AREA','RESIDENTIAL_ROUTE','EXTERIOR')),
 block_id uuid,
 parent_area_id uuid,
 amenity_id uuid,
 timezone text NOT NULL,
 essential_route boolean NOT NULL DEFAULT false,
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','INTERDICTED','RETIRED')),
 physical_boundary_verified boolean NOT NULL DEFAULT false,
 physical_subtype text CHECK(physical_subtype IS NULL OR physical_subtype IN ('EXTERIOR','HALL','CORRIDOR','GYM','POOL','GARAGE','PARTY_ROOM',
  'PLAYGROUND','SPORTS_COURT','MACHINE_ROOM','STORAGE','OTHER')),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 updated_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,code),
 FOREIGN KEY(tenant_id,block_id) REFERENCES cp.blocks(tenant_id,id),
 FOREIGN KEY(tenant_id,parent_area_id) REFERENCES cp.access_areas(tenant_id,id),
 FOREIGN KEY(tenant_id,amenity_id) REFERENCES cp.amenities(tenant_id,id),
 CHECK(parent_area_id IS NULL OR parent_area_id<>id),
 CHECK((area_kind='AMENITY')=(amenity_id IS NOT NULL))
);
CREATE INDEX access_areas_status ON cp.access_areas(tenant_id,status,area_kind);
CREATE INDEX access_areas_parent ON cp.access_areas(tenant_id,parent_area_id);
CREATE UNIQUE INDEX access_areas_one_per_amenity ON cp.access_areas(tenant_id,amenity_id) WHERE amenity_id IS NOT NULL AND status<>'RETIRED';

CREATE TABLE cp.access_points (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 code text NOT NULL CHECK(code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
 name text NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 160),
 from_area_id uuid NOT NULL,
 to_area_id uuid NOT NULL,
 point_kind text NOT NULL CHECK(point_kind IN ('DOOR','TURNSTILE','VEHICLE_GATE','PEDESTRIAN_GATE','ELEVATOR_CONTROL','OTHER')),
 flow_direction text NOT NULL DEFAULT 'UNKNOWN' CHECK(flow_direction IN ('FROM_TO','TO_FROM','BOTH','UNKNOWN')),
 egress_class text NOT NULL DEFAULT 'UNVERIFIED' CHECK(egress_class IN ('FREE_EGRESS','MANAGED_EGRESS','NOT_EGRESS','UNVERIFIED')),
 accessible_route_ref text CHECK(accessible_route_ref IS NULL OR accessible_route_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 emergency_design_ref text CHECK(emergency_design_ref IS NULL OR emergency_design_ref ~ '^[A-Za-z0-9._:/-]{3,120}$'),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','MAPPED','COMMISSIONED','SUSPENDED','RETIRED')),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 updated_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,code),
 FOREIGN KEY(tenant_id,from_area_id) REFERENCES cp.access_areas(tenant_id,id),
 FOREIGN KEY(tenant_id,to_area_id) REFERENCES cp.access_areas(tenant_id,id),
 CHECK(from_area_id<>to_area_id)
);
CREATE INDEX access_points_to ON cp.access_points(tenant_id,to_area_id,status);
CREATE INDEX access_points_from ON cp.access_points(tenant_id,from_area_id,status);

-- Área: fuso IANA conferido; hierarquia sem ciclo (até 16 níveis); campos físicos só pelo MASTER de implantação; código
-- e condomínio fixos; versão sequencial; autoria e horários do banco. Retirada é final.
CREATE FUNCTION cp.access_areas_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE technical boolean:=platform.has_technical_scope('installation.manage');
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=NEW.timezone) THEN
  RAISE EXCEPTION 'fuso IANA inválido' USING ERRCODE='23514';
 END IF;
 IF NEW.parent_area_id IS NOT NULL AND EXISTS(
   WITH RECURSIVE up(id,depth) AS (
    SELECT a.parent_area_id,1 FROM cp.access_areas a WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.parent_area_id
    UNION ALL SELECT a.parent_area_id,up.depth+1 FROM up JOIN cp.access_areas a ON a.tenant_id=NEW.tenant_id AND a.id=up.id
     WHERE up.id IS NOT NULL AND up.depth<17)
   SELECT 1 FROM up WHERE up.id=NEW.id OR up.depth>=16) THEN
  RAISE EXCEPTION 'hierarquia de áreas com ciclo ou profunda demais' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>0 OR NEW.status NOT IN ('DRAFT','ACTIVE') THEN RAISE EXCEPTION 'área nasce rascunho ou ativa' USING ERRCODE='23514'; END IF;
  IF (NEW.essential_route OR NEW.physical_boundary_verified) AND NOT technical THEN
   RAISE EXCEPTION 'rota essencial e separação física só pelo MASTER de implantação' USING ERRCODE='42501';
  END IF;
  NEW.updated_by:=NEW.created_by; NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.code,NEW.created_by,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.code,OLD.created_by,OLD.created_at)
   OR NEW.version<>OLD.version+1 OR OLD.status='RETIRED' THEN
  RAISE EXCEPTION 'área: código fixo, versão sequencial e retirada final' USING ERRCODE='23514';
 END IF;
 IF (NEW.essential_route,NEW.physical_boundary_verified) IS DISTINCT FROM (OLD.essential_route,OLD.physical_boundary_verified) AND NOT technical THEN
  RAISE EXCEPTION 'rota essencial e separação física só pelo MASTER de implantação' USING ERRCODE='42501';
 END IF;
 IF NEW.updated_by<>platform.current_actor_id() THEN RAISE EXCEPTION 'autoria da alteração é a própria conta' USING ERRCODE='42501'; END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_areas_guard() FROM PUBLIC;
CREATE TRIGGER access_areas_guard BEFORE INSERT OR UPDATE ON cp.access_areas FOR EACH ROW EXECUTE FUNCTION cp.access_areas_guard();

-- Ponto: áreas ativas ou rascunho do mesmo condomínio; código fixo; COMMISSIONED só pelo comissionamento (V042);
-- retirada final; versão sequencial.
CREATE FUNCTION cp.access_points_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM cp.access_areas a WHERE a.tenant_id=NEW.tenant_id AND a.id IN (NEW.from_area_id,NEW.to_area_id) AND a.status='RETIRED') THEN
  RAISE EXCEPTION 'ponto não liga área retirada' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>0 OR NEW.status NOT IN ('DRAFT','MAPPED') THEN RAISE EXCEPTION 'ponto nasce rascunho ou mapeado' USING ERRCODE='23514'; END IF;
  NEW.updated_by:=NEW.created_by; NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.code,NEW.created_by,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.code,OLD.created_by,OLD.created_at)
   OR NEW.version<>OLD.version+1 OR OLD.status='RETIRED' THEN
  RAISE EXCEPTION 'ponto: código fixo, versão sequencial e retirada final' USING ERRCODE='23514';
 END IF;
 IF NEW.status='COMMISSIONED' AND OLD.status<>'COMMISSIONED' AND coalesce(current_setting('app.point_commissioning',true),'')<>NEW.id::text THEN
  RAISE EXCEPTION 'ponto só fica comissionado pelo aceite técnico' USING ERRCODE='23514';
 END IF;
 -- Mudança física de ponto comissionado (áreas, mecanismo, sentido ou saída) o devolve a MAPPED: novo comissionamento.
 IF OLD.status='COMMISSIONED' AND NEW.status='COMMISSIONED' AND (NEW.from_area_id,NEW.to_area_id,NEW.point_kind,NEW.flow_direction,NEW.egress_class)
   IS DISTINCT FROM (OLD.from_area_id,OLD.to_area_id,OLD.point_kind,OLD.flow_direction,OLD.egress_class) THEN
  NEW.status:='MAPPED';
 END IF;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.access_points_guard() FROM PUBLIC;
CREATE TRIGGER access_points_guard BEFORE INSERT OR UPDATE ON cp.access_points FOR EACH ROW EXECUTE FUNCTION cp.access_points_guard();

ALTER TABLE cp.access_areas ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_areas FORCE ROW LEVEL SECURITY;
ALTER TABLE cp.access_points ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.access_points FORCE ROW LEVEL SECURITY;
REVOKE ALL ON cp.access_areas,cp.access_points FROM PUBLIC;
CREATE POLICY tenant_boundary ON cp.access_areas TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY tenant_boundary ON cp.access_points TO cp_api,cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Leitura do mapa: gestão (area.manage), portaria e MASTER de implantação (installation.read/manage).
CREATE FUNCTION cp.access_map_reader() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT platform.has_capability('area.manage') OR platform.has_capability('concierge.operate')
  OR platform.has_technical_scope('installation.read') OR platform.has_technical_scope('installation.manage') $$;
REVOKE ALL ON FUNCTION cp.access_map_reader() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.access_map_reader() TO cp_api;

CREATE POLICY access_areas_read ON cp.access_areas AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.access_map_reader());
CREATE POLICY access_areas_create ON cp.access_areas AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK((platform.has_capability('area.manage') OR platform.has_technical_scope('installation.manage')) AND created_by=platform.current_actor_id());
CREATE POLICY access_areas_change ON cp.access_areas AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('area.manage') OR platform.has_technical_scope('installation.manage'))
 WITH CHECK(platform.has_capability('area.manage') OR platform.has_technical_scope('installation.manage'));
CREATE POLICY access_points_read ON cp.access_points AS RESTRICTIVE FOR SELECT TO cp_api USING(cp.access_map_reader());
CREATE POLICY access_points_create ON cp.access_points AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_technical_scope('installation.manage') AND created_by=platform.current_actor_id());
CREATE POLICY access_points_change ON cp.access_points AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_technical_scope('installation.manage'))
 WITH CHECK(platform.has_technical_scope('installation.manage') AND updated_by=platform.current_actor_id());

GRANT SELECT ON cp.access_areas,cp.access_points TO cp_api;
GRANT INSERT(id,tenant_id,code,name,area_kind,block_id,parent_area_id,amenity_id,timezone,essential_route,status,physical_boundary_verified,
 physical_subtype,created_by) ON cp.access_areas TO cp_api;
GRANT UPDATE(name,area_kind,block_id,parent_area_id,amenity_id,timezone,essential_route,status,physical_boundary_verified,physical_subtype,
 updated_by,version) ON cp.access_areas TO cp_api;
GRANT INSERT(id,tenant_id,code,name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,accessible_route_ref,emergency_design_ref,
 status,created_by) ON cp.access_points TO cp_api;
GRANT UPDATE(name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,accessible_route_ref,emergency_design_ref,status,updated_by,version)
 ON cp.access_points TO cp_api;
-- Worker (compilador de direitos, fatias seguintes): leitura do mapa, sem referências de projeto.
GRANT SELECT(id,tenant_id,code,area_kind,parent_area_id,amenity_id,timezone,essential_route,status) ON cp.access_areas TO cp_worker;
GRANT SELECT(id,tenant_id,code,from_area_id,to_area_id,point_kind,flow_direction,egress_class,status) ON cp.access_points TO cp_worker;

-- Trilha do condomínio: AREA_* pela gestão ou MASTER de implantação; POINT_* só pelo MASTER de implantação.
CREATE POLICY audit_area_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'AREA\_%' OR (actor_id=platform.current_actor_id() AND resource_type='ACCESS_AREA'
  AND (platform.has_capability('area.manage') OR platform.has_technical_scope('installation.manage'))));
CREATE POLICY audit_point_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'POINT\_%' OR (actor_id=platform.current_actor_id() AND resource_type='ACCESS_POINT'
  AND platform.has_technical_scope('installation.manage')));
