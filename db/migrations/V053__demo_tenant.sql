-- CPX-046 (PRD complementar Segurança v1.0, E10.4; ADR-0012): condomínio de demonstração isolado, com dados 100%
-- sintéticos, criado e resetado pela central (papel de plataforma, capacidade platform.demo.manage publicada pela API só
-- depois de papel vigente e AAL2).
--  - Marca: platform.tenants.demo e platform.partners.demo, imutáveis (gatilho) e fora dos grants das roles de
--    aplicação; só a role cp_demo grava true, e só ao criar a linha. Nome do demo sempre "[DEMO] ..."; condomínio ou
--    parceiro real nunca usa esse prefixo. Contas sintéticas: platform.accounts.synthetic, também imutável.
--  - Isolamento: conta sintética só tem vínculo em condomínio demo e papel em parceiro demo; contrato só demo × demo
--    (gatilhos). Condomínio real nunca vira demo e nunca é resetado: o reset só alcança a geração ativa registrada em
--    platform.demo_generations, e o condomínio esperado precisa ser esse demo (SQLSTATE CPD01 para condomínio real).
--  - Reset não apaga nada (logs e trilhas só acrescentam): aposenta a geração atual (condomínio suspenso, contrato
--    encerrado, parceiro suspenso, eventos na trilha da plataforma e no Log de Segurança) e cria uma geração nova.
--  - Execução: platform.demo_provision(), SECURITY DEFINER com dono cp_demo (NOLOGIN, sem BYPASSRLS, sem membro de
--    runtime). cp_demo só lê e grava linhas de condomínio/parceiro demo e contas sintéticas (RLS própria); a API chama
--    a função com o ator e as capacidades de plataforma no contexto local. Nenhuma conta real recebe papel: o vínculo
--    de pessoas reais ao demo continua pelos caminhos explícitos da central (docs/ROTEIRO_TESTES_HML.md).
--  - Nada roda sozinho: a migration só cria estrutura; o demo nasce apenas pela operação explícita da central.

-- 1. Role dona da operação.
DO $$
BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='cp_demo') THEN
  CREATE ROLE cp_demo NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
 END IF;
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='cp_demo'
   AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication OR rolcanlogin))
   OR pg_has_role('cp_api','cp_demo','MEMBER') OR pg_has_role('cp_worker','cp_demo','MEMBER')
   OR pg_has_role('cp_onboarding','cp_demo','MEMBER') OR pg_has_role('cp_demo','cp_api','MEMBER')
   OR pg_has_role('cp_demo','cp_worker','MEMBER') OR pg_has_role('cp_demo','cp_onboarding','MEMBER') THEN
  RAISE EXCEPTION 'Role cp_demo incompatível com o isolamento das roles de runtime';
 END IF;
END $$;
GRANT USAGE ON SCHEMA cp,platform TO cp_demo;

-- 2. Marcas imutáveis.
ALTER TABLE platform.tenants ADD COLUMN demo boolean NOT NULL DEFAULT false;
ALTER TABLE platform.tenants ADD CONSTRAINT tenants_demo_name CHECK(demo = (name ~ '^\[DEMO\] '));
ALTER TABLE platform.partners ADD COLUMN demo boolean NOT NULL DEFAULT false;
ALTER TABLE platform.partners ADD CONSTRAINT partners_demo_name CHECK(demo = (name ~ '^\[DEMO\] '));
ALTER TABLE platform.accounts ADD COLUMN synthetic boolean NOT NULL DEFAULT false;

CREATE FUNCTION platform.demo_mark_immutable() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 RAISE EXCEPTION 'marca de demonstração é definida só na criação e nunca muda (%)',TG_TABLE_NAME USING ERRCODE='42501';
END $$;
REVOKE ALL ON FUNCTION platform.demo_mark_immutable() FROM PUBLIC;
CREATE TRIGGER tenants_demo_immutable BEFORE UPDATE ON platform.tenants FOR EACH ROW
 WHEN (NEW.demo IS DISTINCT FROM OLD.demo) EXECUTE FUNCTION platform.demo_mark_immutable();
CREATE TRIGGER partners_demo_immutable BEFORE UPDATE ON platform.partners FOR EACH ROW
 WHEN (NEW.demo IS DISTINCT FROM OLD.demo) EXECUTE FUNCTION platform.demo_mark_immutable();
CREATE TRIGGER accounts_synthetic_immutable BEFORE UPDATE ON platform.accounts FOR EACH ROW
 WHEN (NEW.synthetic IS DISTINCT FROM OLD.synthetic) EXECUTE FUNCTION platform.demo_mark_immutable();

-- 3. Gerações do demo: no máximo uma ativa; idempotência por conta e chave; aposentar é final.
CREATE TABLE platform.demo_generations (
 id uuid PRIMARY KEY,
 generation integer NOT NULL UNIQUE CHECK(generation>=1),
 tenant_id uuid NOT NULL UNIQUE REFERENCES platform.tenants(id),
 partner_id uuid NOT NULL UNIQUE REFERENCES platform.partners(id),
 contract_id uuid NOT NULL UNIQUE REFERENCES platform.partner_tenant_contracts(id),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500),
 request_key text NOT NULL CHECK(request_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
 request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 counts jsonb NOT NULL CHECK(jsonb_typeof(counts)='object' AND length(counts::text)<=1024),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 retired_by uuid REFERENCES platform.accounts(id),
 retired_at timestamptz,
 retire_reason text CHECK(retire_reason IS NULL OR length(btrim(retire_reason)) BETWEEN 3 AND 500),
 UNIQUE(created_by,request_key),
 CHECK((status='RETIRED')=(retired_by IS NOT NULL AND retired_at IS NOT NULL AND retire_reason IS NOT NULL))
);
CREATE UNIQUE INDEX demo_generations_one_active ON platform.demo_generations((true)) WHERE status='ACTIVE';

CREATE FUNCTION platform.demo_generations_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.retired_by IS NOT NULL OR NEW.retired_at IS NOT NULL OR NEW.retire_reason IS NOT NULL
    OR NOT EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=NEW.tenant_id AND t.demo)
    OR NOT EXISTS(SELECT 1 FROM platform.partners p WHERE p.id=NEW.partner_id AND p.demo) THEN
   RAISE EXCEPTION 'geração do demo nasce ativa, com condomínio e parceiro demo' USING ERRCODE='23514';
  END IF;
  NEW.created_at:=clock_timestamp();
  RETURN NEW;
 END IF;
 IF TG_OP<>'UPDATE' OR OLD.status<>'ACTIVE' OR NEW.status<>'RETIRED'
   OR (NEW.id,NEW.generation,NEW.tenant_id,NEW.partner_id,NEW.contract_id,NEW.reason,NEW.request_key,NEW.request_hash,NEW.counts,
       NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.generation,OLD.tenant_id,OLD.partner_id,OLD.contract_id,OLD.reason,OLD.request_key,OLD.request_hash,
       OLD.counts,OLD.created_by,OLD.created_at) THEN
  RAISE EXCEPTION 'geração do demo só muda por aposentadoria final' USING ERRCODE='23514';
 END IF;
 NEW.retired_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.demo_generations_guard() FROM PUBLIC;
CREATE TRIGGER demo_generations_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.demo_generations
 FOR EACH ROW EXECUTE FUNCTION platform.demo_generations_guard();
ALTER TABLE platform.demo_generations ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.demo_generations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.demo_generations FROM PUBLIC;
-- A central lê as gerações com a capacidade do demo ou com o catálogo; nenhuma role de aplicação grava.
GRANT SELECT ON platform.demo_generations TO cp_api;
CREATE POLICY demo_generations_platform_read ON platform.demo_generations FOR SELECT TO cp_api
 USING(platform.has_platform_capability('platform.demo.manage') OR platform.has_platform_capability('tenants.read'));

-- 4. Consultas de marca (dono cp_demo, que só enxerga linhas demo/sintéticas): verdadeiro só para o que é demo.
CREATE FUNCTION platform.tenant_is_demo(target uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog
AS $$ SELECT coalesce((SELECT t.demo FROM platform.tenants t WHERE t.id=target),false) $$;
CREATE FUNCTION platform.partner_is_demo(target uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog
AS $$ SELECT coalesce((SELECT p.demo FROM platform.partners p WHERE p.id=target),false) $$;
CREATE FUNCTION platform.account_is_synthetic(target uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog
AS $$ SELECT coalesce((SELECT a.synthetic FROM platform.accounts a WHERE a.id=target AND a.synthetic),false) $$;

-- 5. Isolamento (invocador; as consultas de marca acima respondem para qualquer role).
CREATE FUNCTION platform.demo_isolation_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_TABLE_NAME='memberships' THEN
  IF platform.account_is_synthetic(NEW.account_id) AND NOT platform.tenant_is_demo(NEW.tenant_id) THEN
   RAISE EXCEPTION 'conta sintética só tem vínculo em condomínio demo' USING ERRCODE='42501';
  END IF;
 ELSIF TG_TABLE_NAME='partner_members' THEN
  IF platform.account_is_synthetic(NEW.account_id) AND NOT platform.partner_is_demo(NEW.partner_id) THEN
   RAISE EXCEPTION 'conta sintética só tem papel em parceiro demo' USING ERRCODE='42501';
  END IF;
 ELSIF TG_TABLE_NAME='partner_tenant_contracts' THEN
  IF platform.partner_is_demo(NEW.partner_id) IS DISTINCT FROM platform.tenant_is_demo(NEW.tenant_id) THEN
   RAISE EXCEPTION 'parceiro demo só contrata condomínio demo, e vice-versa' USING ERRCODE='42501';
  END IF;
 ELSIF TG_TABLE_NAME='operator_schedules' OR TG_TABLE_NAME='technical_grants' THEN
  IF platform.account_is_synthetic(NEW.account_id) AND NOT platform.tenant_is_demo(NEW.tenant_id) THEN
   RAISE EXCEPTION 'conta sintética só atua em condomínio demo' USING ERRCODE='42501';
  END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.demo_isolation_guard() FROM PUBLIC;
CREATE TRIGGER memberships_demo_isolation BEFORE INSERT OR UPDATE OF account_id,tenant_id ON cp.memberships
 FOR EACH ROW EXECUTE FUNCTION platform.demo_isolation_guard();
CREATE TRIGGER partner_members_demo_isolation BEFORE INSERT OR UPDATE OF account_id,partner_id ON platform.partner_members
 FOR EACH ROW EXECUTE FUNCTION platform.demo_isolation_guard();
CREATE TRIGGER partner_contracts_demo_isolation BEFORE INSERT OR UPDATE OF partner_id,tenant_id ON platform.partner_tenant_contracts
 FOR EACH ROW EXECUTE FUNCTION platform.demo_isolation_guard();
CREATE TRIGGER operator_schedules_demo_isolation BEFORE INSERT OR UPDATE OF account_id,tenant_id ON cp.operator_schedules
 FOR EACH ROW EXECUTE FUNCTION platform.demo_isolation_guard();
CREATE TRIGGER technical_grants_demo_isolation BEFORE INSERT OR UPDATE OF account_id,tenant_id ON cp.technical_grants
 FOR EACH ROW EXECUTE FUNCTION platform.demo_isolation_guard();

-- 6. Privilégios e RLS da role do demo: só linhas de condomínio/parceiro demo e contas sintéticas (mais a própria conta
-- do ator, para conferir o papel de plataforma). Sem DELETE/TRUNCATE em nada.
GRANT SELECT,INSERT(id,status,synthetic) ON platform.accounts TO cp_demo;
CREATE POLICY demo_accounts_read ON platform.accounts FOR SELECT TO cp_demo USING(synthetic OR id=platform.current_actor_id());
CREATE POLICY demo_accounts_insert ON platform.accounts FOR INSERT TO cp_demo WITH CHECK(synthetic AND status='ACTIVE');
GRANT SELECT ON platform.platform_roles TO cp_demo;
CREATE POLICY demo_platform_role_self ON platform.platform_roles FOR SELECT TO cp_demo USING(account_id=platform.current_actor_id());
GRANT SELECT,INSERT(id,name,time_zone,active,demo),UPDATE(active,version,updated_at) ON platform.tenants TO cp_demo;
CREATE POLICY demo_tenants_read ON platform.tenants FOR SELECT TO cp_demo USING(demo);
CREATE POLICY demo_tenants_insert ON platform.tenants FOR INSERT TO cp_demo WITH CHECK(demo AND active AND version=0);
CREATE POLICY demo_tenants_update ON platform.tenants FOR UPDATE TO cp_demo USING(demo) WITH CHECK(demo);
GRANT SELECT,INSERT(id,name,created_by,updated_by,demo),UPDATE(status,version,updated_by) ON platform.partners TO cp_demo;
CREATE POLICY demo_partners_read ON platform.partners FOR SELECT TO cp_demo USING(demo);
CREATE POLICY demo_partners_insert ON platform.partners FOR INSERT TO cp_demo WITH CHECK(demo);
CREATE POLICY demo_partners_update ON platform.partners FOR UPDATE TO cp_demo USING(demo) WITH CHECK(demo);
GRANT SELECT,INSERT(id,partner_id,account_id,role,display_name,created_by) ON platform.partner_members TO cp_demo;
CREATE POLICY demo_partner_members ON platform.partner_members TO cp_demo
 USING(EXISTS(SELECT 1 FROM platform.partners p WHERE p.id=partner_id AND p.demo))
 WITH CHECK(EXISTS(SELECT 1 FROM platform.partners p WHERE p.id=partner_id AND p.demo));
GRANT SELECT,INSERT(id,partner_id,tenant_id,service,starts_at,ends_at,reason,created_by),UPDATE(status,ended_by,end_reason,version)
 ON platform.partner_tenant_contracts TO cp_demo;
CREATE POLICY demo_partner_contracts ON platform.partner_tenant_contracts TO cp_demo
 USING(EXISTS(SELECT 1 FROM platform.partners p WHERE p.id=partner_id AND p.demo)
  AND EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=tenant_id AND t.demo))
 WITH CHECK(EXISTS(SELECT 1 FROM platform.partners p WHERE p.id=partner_id AND p.demo)
  AND EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=tenant_id AND t.demo));
GRANT SELECT,INSERT(id,status,generation,tenant_id,partner_id,contract_id,reason,request_key,request_hash,counts,created_by),
 UPDATE(status,retired_by,retire_reason) ON platform.demo_generations TO cp_demo;
CREATE POLICY demo_generations_owner ON platform.demo_generations TO cp_demo USING(true) WITH CHECK(true);
-- Trilha da plataforma: eventos DEMO_* e PARTNER_* do demo, do próprio ator e com a capacidade publicada.
GRANT SELECT,INSERT(actor_id,action,tenant_id,reason,details) ON platform.platform_audit_log TO cp_demo;
CREATE POLICY demo_platform_audit_insert ON platform.platform_audit_log FOR INSERT TO cp_demo
 WITH CHECK(actor_id=platform.current_actor_id() AND platform.has_platform_capability('platform.demo.manage')
  AND details->>'demo'='true' AND (action IN ('DEMO_TENANT_PROVISIONED','DEMO_TENANT_RETIRED') OR action LIKE 'PARTNER\_%'));
CREATE POLICY demo_platform_audit_read ON platform.platform_audit_log FOR SELECT TO cp_demo USING(details->>'demo'='true');

DO $$
DECLARE spec record;
BEGIN
 -- Tabelas por condomínio que o demo semeia ou que os gatilhos das fontes leem.
 FOR spec IN SELECT * FROM (VALUES
   ('blocks','INSERT(id,tenant_id,name)'),
   ('units','INSERT(id,tenant_id,block_id,label,status)'),
   ('amenities','INSERT(id,tenant_id,name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy,active)'),
   ('memberships','INSERT(id,tenant_id,account_id,unit_id,display_name,role,status)'),
   ('unit_rentals',NULL),
   ('announcements','INSERT(id,tenant_id,title,body,audience,audience_ids,author_id),UPDATE(state,version)'),
   ('parcels','INSERT(id,tenant_id,unit_id,quantity,description,storage_location,received_by)'),
   ('parcel_handoffs',NULL),
   ('visit_rules',NULL),
   ('reservations',NULL),
   ('visitors','INSERT(id,tenant_id,full_name,document_last4,created_by)'),
   ('visit_permits','INSERT(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until)'),
   ('concierge_occurrences','INSERT(id,tenant_id,category,title,description,unit_id),UPDATE(state,resolution,version)'),
   ('service_requests','INSERT(id,tenant_id,unit_id,requester_id,category,title,description),UPDATE(state,assignee_membership_id,due_on,version)'),
   ('request_messages','INSERT(id,tenant_id,request_id,author_id,author_role,body,visibility)'),
   ('operator_schedules','INSERT(id,tenant_id,partner_id,member_id,account_id,starts_at,ends_at,reason,created_by)'),
   ('security_log_heads','INSERT(tenant_id),UPDATE(last_seq,last_hash)')) AS t(name,extra) LOOP
  EXECUTE format('GRANT SELECT%s ON cp.%I TO cp_demo',CASE WHEN spec.extra IS NULL THEN '' ELSE ','||spec.extra END,spec.name);
  EXECUTE format('CREATE POLICY demo_seed ON cp.%I TO cp_demo USING(EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=tenant_id AND t.demo))'
   ' WITH CHECK(EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=tenant_id AND t.demo))',spec.name);
 END LOOP;
END $$;
-- Log de Segurança: só INSERT por coluna, como a API (autoria, horários, sequência e hash continuam do banco).
GRANT INSERT(id,tenant_id,occurred_at,clock_skew_ms,event_type,severity,source,session_id,subject_type,subject_id,unit_id,
 point_id,device_id,reason,evidence_ref,correlation_id,trace_id,corrects_id,details) ON cp.security_log TO cp_demo;
CREATE POLICY demo_security_log_insert ON cp.security_log FOR INSERT TO cp_demo
 WITH CHECK(EXISTS(SELECT 1 FROM platform.tenants t WHERE t.id=tenant_id AND t.demo));

GRANT EXECUTE ON FUNCTION platform.current_actor_id(),platform.current_tenant_id(),platform.has_platform_capability(text),
 platform.has_capability(text),platform.capability_published(text),platform.capability_verified(text),
 platform.security_capabilities(),platform.partner_role_capabilities(text),platform.partner_role_grants(uuid,text),
 platform.current_partner_id(),platform.partner_reach(),
 cp.security_log_event_types(),cp.security_log_details_valid(jsonb),cp.security_log_digest(text,cp.security_log),
 cp.security_log_partner_rank(text),cp.security_log_actor_partner(uuid,text),cp.security_log_actor_role(uuid),
 cp.security_log_append(uuid,text,text,text,text,uuid,uuid,uuid,uuid,text,uuid,jsonb,timestamptz,bigint,uuid),
 cp.concierge_occurrence_actor_name(uuid) TO cp_demo;

-- 7. Operação da central: cria (ou reseta) o demo. Uma chamada = uma geração nova, na mesma transação da API.
--  expected_tenant: o demo ativo que a tela mostrou (NULL quando não havia demo). Diferente do ativo: CPD02 (recarregar).
--  Condomínio que não é demo (real ou inexistente): CPD01, nada muda. Chave repetida com outro pedido: CPD03.
CREATE FUNCTION platform.demo_provision(expected_tenant uuid,motive text,request_key text,
 OUT generation_id uuid,OUT generation integer,OUT tenant_id uuid,OUT tenant_name text,OUT partner_id uuid,OUT partner_name text,
 OUT retired_tenant_id uuid,OUT applied boolean,OUT created_at timestamptz,OUT counts jsonb)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE
 actor uuid:=platform.current_actor_id();
 reason_text text:=btrim(coalesce(motive,''));
 hash text;
 found_gen platform.demo_generations;
 current_gen platform.demo_generations;
 contract_row record;
 partner_row record;
 gen_no integer;
 new_tenant uuid:=gen_random_uuid();
 new_partner uuid:=gen_random_uuid();
 new_contract uuid:=gen_random_uuid();
 block_a uuid:=gen_random_uuid();
 block_b uuid:=gen_random_uuid();
 manager_acc uuid; concierge_acc uuid; maintenance_acc uuid;
 maintenance_member uuid:=gen_random_uuid();
 resident record;
 residents jsonb:='{}'::jsonb;
 unit_ids jsonb:='{}'::jsonb;
 unit_id uuid;
 member_ids jsonb:='{}'::jsonb;
 member_accounts jsonb:='{}'::jsonb;
 staff record;
 member uuid; acc uuid;
 a1 uuid:=gen_random_uuid(); a2 uuid:=gen_random_uuid(); a3 uuid:=gen_random_uuid();
 o1 uuid:=gen_random_uuid(); o2 uuid:=gen_random_uuid();
 r1 uuid:=gen_random_uuid(); r2 uuid:=gen_random_uuid();
 v1 uuid:=gen_random_uuid(); v2 uuid:=gen_random_uuid();
 sched record;
 schedule_id uuid;
 floor_no integer; apt integer; block_row record;
 n_units integer:=0; n_members integer:=0; n_schedules integer:=0; n_events integer:=0;
 started timestamptz:=now();
BEGIN
 -- Quem chama: papel de plataforma vigente com a capacidade do demo (publicada pela API só com AAL2), sem condomínio no
 -- contexto. A conferência é repetida aqui porque a função roda com os privilégios de cp_demo.
 IF actor IS NULL OR NOT platform.has_platform_capability('platform.demo.manage')
   OR nullif(current_setting('app.tenant_id',true),'') IS NOT NULL
   OR NOT EXISTS(SELECT 1 FROM platform.platform_roles r JOIN platform.accounts a ON a.id=r.account_id AND a.status='ACTIVE'
    WHERE r.account_id=actor AND r.status='ACTIVE' AND NOT a.synthetic) THEN
  RAISE EXCEPTION 'condomínio demo só pela central, com papel de plataforma e segundo fator' USING ERRCODE='42501';
 END IF;
 IF length(reason_text) NOT BETWEEN 3 AND 500 OR request_key IS NULL OR request_key !~ '^[A-Za-z0-9._:-]{16,128}$' THEN
  RAISE EXCEPTION 'motivo (3 a 500 caracteres) e chave de idempotência são obrigatórios' USING ERRCODE='22023';
 END IF;
 hash:=encode(sha256(convert_to(coalesce(expected_tenant::text,'-')||'|'||reason_text,'UTF8')),'hex');
 -- Uma operação de demo por vez; a repetição da mesma chave devolve a geração já criada.
 PERFORM pg_advisory_xact_lock(hashtextextended('platform.demo_generations',0));
 SELECT * INTO found_gen FROM platform.demo_generations g WHERE g.created_by=actor AND g.request_key=demo_provision.request_key;
 IF FOUND THEN
  IF found_gen.request_hash<>hash THEN
   RAISE EXCEPTION 'chave de idempotência já usada com outro pedido' USING ERRCODE='CPD03';
  END IF;
  SELECT found_gen.id,found_gen.generation,found_gen.tenant_id,t.name,found_gen.partner_id,p.name,
    (SELECT g.tenant_id FROM platform.demo_generations g WHERE g.generation=found_gen.generation-1),false,found_gen.created_at,found_gen.counts
   INTO generation_id,generation,tenant_id,tenant_name,partner_id,partner_name,retired_tenant_id,applied,created_at,counts
   FROM platform.tenants t,platform.partners p WHERE t.id=found_gen.tenant_id AND p.id=found_gen.partner_id;
  RETURN;
 END IF;
 IF expected_tenant IS NOT NULL AND NOT platform.tenant_is_demo(expected_tenant) THEN
  RAISE EXCEPTION 'somente o condomínio demo pode ser resetado; condomínio real nunca é resetado' USING ERRCODE='CPD01';
 END IF;
 SELECT * INTO current_gen FROM platform.demo_generations g WHERE g.status='ACTIVE';
 IF expected_tenant IS DISTINCT FROM current_gen.tenant_id THEN
  RAISE EXCEPTION 'o condomínio demo mudou desde a última leitura' USING ERRCODE='CPD02';
 END IF;
 -- Gatilhos adiados (trilha exigida no COMMIT, Log de Segurança das fontes) passam a rodar no fim de cada instrução,
 -- aqui dentro: com o ator de cada etapa e as políticas de cp_demo.
 SET CONSTRAINTS ALL IMMEDIATE;

 -- Aposenta a geração atual: nada é apagado.
 IF current_gen.id IS NOT NULL THEN
  retired_tenant_id:=current_gen.tenant_id;
  SELECT c.status,c.version INTO contract_row FROM platform.partner_tenant_contracts c WHERE c.id=current_gen.contract_id;
  IF contract_row.status='ACTIVE' THEN
   INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES(actor,'PARTNER_CONTRACT_ENDED',
    current_gen.tenant_id,reason_text,jsonb_build_object('demo','true','partnerId',current_gen.partner_id::text,
     'contractId',current_gen.contract_id::text,'version',(contract_row.version+1)::text));
   UPDATE platform.partner_tenant_contracts c SET status='ENDED',ended_by=actor,end_reason=reason_text,version=contract_row.version+1
    WHERE c.id=current_gen.contract_id;
   INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,reason,details)
    VALUES(current_gen.tenant_id,'PARTNER_CONTRACT_CHANGED','INFO','PLATFORM','PARTNER_CONTRACT',current_gen.contract_id,reason_text,
     jsonb_build_object('change','ENDED','partnerId',current_gen.partner_id,'demo',true));
  END IF;
  SELECT p.status,p.version INTO partner_row FROM platform.partners p WHERE p.id=current_gen.partner_id;
  IF partner_row.status='ACTIVE' THEN
   INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES(actor,'PARTNER_SUSPENDED',NULL,reason_text,
    jsonb_build_object('demo','true','partnerId',current_gen.partner_id::text,'version',(partner_row.version+1)::text));
   UPDATE platform.partners p SET status='SUSPENDED',version=partner_row.version+1,updated_by=actor WHERE p.id=current_gen.partner_id;
  END IF;
  UPDATE platform.tenants t SET active=false,version=t.version+1,updated_at=clock_timestamp() WHERE t.id=current_gen.tenant_id AND t.active;
  UPDATE platform.demo_generations g SET status='RETIRED',retired_by=actor,retire_reason=reason_text WHERE g.id=current_gen.id;
  INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES(actor,'DEMO_TENANT_RETIRED',current_gen.tenant_id,
   reason_text,jsonb_build_object('demo','true','generation',current_gen.generation::text,'partnerId',current_gen.partner_id::text));
 END IF;

 -- Geração nova: condomínio, torres, unidades e áreas comuns com regras de reserva.
 SELECT coalesce(max(g.generation),0)+1 INTO gen_no FROM platform.demo_generations g;
 tenant_name:='[DEMO] Residencial Jardim Fictício (geração '||gen_no||')';
 partner_name:='[DEMO] Vigilância Fictícia Aurora (geração '||gen_no||')';
 INSERT INTO platform.tenants(id,name,time_zone,active,demo) VALUES(new_tenant,tenant_name,'America/Sao_Paulo',true,true);
 INSERT INTO cp.blocks(id,tenant_id,name) VALUES(block_a,new_tenant,'Torre A (fictícia)'),(block_b,new_tenant,'Torre B (fictícia)');
 FOR block_row IN SELECT * FROM (VALUES('A',block_a),('B',block_b)) AS b(code,id) LOOP
  FOR floor_no IN 1..4 LOOP
   FOR apt IN 1..4 LOOP
    unit_id:=gen_random_uuid();
    INSERT INTO cp.units(id,tenant_id,block_id,label,status) VALUES(unit_id,new_tenant,block_row.id,(floor_no*100+apt)::text,'ACTIVE');
    unit_ids:=unit_ids||jsonb_build_object(block_row.code||'-'||(floor_no*100+apt),unit_id);
    n_units:=n_units+1;
   END LOOP;
  END LOOP;
 END LOOP;
 INSERT INTO cp.amenities(id,tenant_id,name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy,active) VALUES
  (gen_random_uuid(),new_tenant,'Salão de festas (fictício)',60,true,15000,60,1,jsonb_build_object('schemaVersion',1,
    'weekDays',jsonb_build_array(1,2,3,4,5,6,7),'opensAt','10:00','closesAt','23:00','minimumLeadMinutes',1440,'maximumAdvanceDays',90,
    'maximumDurationMinutes',360,'maximumActivePerUnit',2,'cancellationLeadMinutes',2880),true),
  (gen_random_uuid(),new_tenant,'Churrasqueira (fictícia)',20,false,0,30,1,jsonb_build_object('schemaVersion',1,
    'weekDays',jsonb_build_array(5,6,7),'opensAt','11:00','closesAt','22:00','minimumLeadMinutes',120,'maximumAdvanceDays',60,
    'maximumDurationMinutes',240,'maximumActivePerUnit',1,'cancellationLeadMinutes',240),true),
  (gen_random_uuid(),new_tenant,'Quadra poliesportiva (fictícia)',12,false,0,0,1,jsonb_build_object('schemaVersion',1,
    'weekDays',jsonb_build_array(1,2,3,4,5,6,7),'opensAt','07:00','closesAt','22:00','minimumLeadMinutes',30,'maximumAdvanceDays',14,
    'maximumDurationMinutes',60,'maximumActivePerUnit',1,'cancellationLeadMinutes',60),true);

 -- Pessoas sintéticas: contas sem login (sem usuário no Auth), marcadas como sintéticas, só neste condomínio.
 -- Sem gestor sintético: o primeiro gestor do demo é uma pessoa real, pelo convite de gestor da central (a central só
 -- convida quando não há gestor vigente). A conta sintética da gestão só assina avisos e a triagem de exemplo.
 manager_acc:=gen_random_uuid();
 INSERT INTO platform.accounts(id,status,synthetic) VALUES(manager_acc,'ACTIVE',true);
 FOR staff IN SELECT * FROM (VALUES('CONCIERGE','Porteiro Fictício (demo)'),
   ('MAINTENANCE','Zelador Fictício (demo)')) AS s(role,name) LOOP
  acc:=gen_random_uuid();
  INSERT INTO platform.accounts(id,status,synthetic) VALUES(acc,'ACTIVE',true);
  INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status)
   VALUES(CASE WHEN staff.role='MAINTENANCE' THEN maintenance_member ELSE gen_random_uuid() END,new_tenant,acc,NULL,staff.name,staff.role,'ACTIVE');
  n_members:=n_members+1;
  IF staff.role='CONCIERGE' THEN concierge_acc:=acc; ELSE maintenance_acc:=acc; END IF;
 END LOOP;
 FOR resident IN SELECT * FROM (VALUES('A-101','RESPONSIBLE','Moradora Fictícia A-101'),('A-101','RESIDENT','Morador Fictício A-101 (2)'),
   ('A-102','RESPONSIBLE','Morador Fictício A-102'),('A-201','RESPONSIBLE','Moradora Fictícia A-201'),
   ('B-101','RESPONSIBLE','Morador Fictício B-101'),('B-302','RESPONSIBLE','Moradora Fictícia B-302'),
   ('B-404','OWNER','Proprietário Fictício B-404')) AS r(unit,role,name) LOOP
  acc:=gen_random_uuid();
  INSERT INTO platform.accounts(id,status,synthetic) VALUES(acc,'ACTIVE',true);
  INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
   VALUES(new_tenant,acc,(unit_ids->>resident.unit)::uuid,resident.name,resident.role,'ACTIVE');
  IF resident.role<>'RESIDENT' THEN residents:=residents||jsonb_build_object(resident.unit,acc); END IF;
  n_members:=n_members+1;
 END LOOP;

 -- Avisos (dois publicados pela conta sintética da gestão e um rascunho).
 INSERT INTO cp.announcements(id,tenant_id,title,body,audience,audience_ids,author_id) VALUES
  (a1,new_tenant,'Boas-vindas ao condomínio demo','Este condomínio é fictício e serve só para testes. Nenhum dado aqui é real.',
   'TENANT','{}',manager_acc),
  (a2,new_tenant,'Manutenção fictícia do elevador da Torre A','Aviso de exemplo: o elevador social da Torre A (fictícia) fica parado amanhã.',
   'BLOCKS',ARRAY[block_a],manager_acc),
  (a3,new_tenant,'Rascunho: assembleia fictícia','Rascunho de exemplo, ainda não publicado.','TENANT','{}',manager_acc);
 PERFORM set_config('app.actor_id',manager_acc::text,true);
 UPDATE cp.announcements a SET state='PUBLISHED',version=1 WHERE a.id IN (a1,a2);

 -- Encomendas recebidas pela portaria sintética, aguardando retirada.
 INSERT INTO cp.parcels(tenant_id,unit_id,quantity,description,storage_location,received_by) VALUES
  (new_tenant,(unit_ids->>'A-101')::uuid,1,'Caixa pequena (fictícia)','Armário fictício 1',concierge_acc),
  (new_tenant,(unit_ids->>'A-201')::uuid,2,'Envelopes (fictícios)','Armário fictício 2',concierge_acc),
  (new_tenant,(unit_ids->>'B-302')::uuid,1,'Pacote de mercado (fictício)','Geladeira da portaria (fictícia)',concierge_acc);

 -- Visitas: convites criados pelas unidades sintéticas.
 INSERT INTO cp.visitors(id,tenant_id,full_name,document_last4,created_by) VALUES
  (v1,new_tenant,'Visitante Fictício Um','0001',(residents->>'A-101')::uuid),
  (v2,new_tenant,'Visitante Fictícia Dois',NULL,(residents->>'B-302')::uuid);
 INSERT INTO cp.visit_permits(tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until) VALUES
  (new_tenant,v1,(unit_ids->>'A-101')::uuid,'Visita fictícia',(residents->>'A-101')::uuid,clock_timestamp()+interval '1 hour',
   clock_timestamp()+interval '6 hours'),
  (new_tenant,v2,(unit_ids->>'B-302')::uuid,'Entrega fictícia agendada',(residents->>'B-302')::uuid,clock_timestamp()+interval '1 day',
   clock_timestamp()+interval '1 day 4 hours');

 -- Ocorrências registradas pela portaria sintética (uma encerrada).
 PERFORM set_config('app.actor_id',concierge_acc::text,true);
 INSERT INTO cp.concierge_occurrences(id,tenant_id,category,title,description,unit_id) VALUES
  (o1,new_tenant,'EQUIPMENT','Lâmpada queimada no hall (fictício)','Ocorrência de exemplo: lâmpada do hall da Torre B (fictícia).',NULL),
  (o2,new_tenant,'SECURITY','Portão da garagem aberto por tempo excessivo (fictício)','Ocorrência de exemplo para o Log de Segurança.',NULL);
 UPDATE cp.concierge_occurrences c SET state='RESOLVED',resolution='Lâmpada trocada (fictício).',version=1 WHERE c.id=o1;

 -- Pedidos das unidades: um atribuído à zeladoria sintética, outro aberto.
 PERFORM set_config('app.actor_id',(residents->>'A-101')::text,true);
 INSERT INTO cp.service_requests(id,tenant_id,unit_id,requester_id,category,title,description) VALUES
  (r1,new_tenant,(unit_ids->>'A-101')::uuid,(residents->>'A-101')::uuid,'MAINTENANCE','Vazamento na pia (fictício)',
   'Pedido de exemplo: pinga água embaixo da pia da cozinha.');
 INSERT INTO cp.request_messages(tenant_id,request_id,author_id,author_role,body,visibility)
  VALUES(new_tenant,r1,(residents->>'A-101')::uuid,'UNIT','Pode vir à tarde (mensagem fictícia).','RESIDENT');
 PERFORM set_config('app.actor_id',(residents->>'B-302')::text,true);
 INSERT INTO cp.service_requests(id,tenant_id,unit_id,requester_id,category,title,description) VALUES
  (r2,new_tenant,(unit_ids->>'B-302')::uuid,(residents->>'B-302')::uuid,'CLEANING','Limpeza do corredor (fictício)',
   'Pedido de exemplo ainda sem triagem.');
 PERFORM set_config('app.actor_id',manager_acc::text,true);
 UPDATE cp.service_requests s SET state='TRIAGED',assignee_membership_id=maintenance_member,version=1 WHERE s.id=r1;

 -- Parceiro de segurança sintético, equipe, contrato vigente e escala. Autoria: a conta da central que criou o demo.
 PERFORM set_config('app.actor_id',actor::text,true);
 INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES(actor,'PARTNER_CREATED',NULL,reason_text,
  jsonb_build_object('demo','true','partnerId',new_partner::text,'version','0','name',partner_name));
 INSERT INTO platform.partners(id,name,created_by,updated_by,demo) VALUES(new_partner,partner_name,actor,actor,true);
 FOR staff IN SELECT * FROM (VALUES('PARTNER_ADMIN','Gestora do Parceiro Fictícia'),('SECURITY_LEAD','Líder de Segurança Fictício'),
   ('SECURITY_SUPERVISOR','Supervisora Fictícia'),('REMOTE_OPERATOR','Operador Remoto Fictício 1'),
   ('REMOTE_OPERATOR','Operadora Remota Fictícia 2'),('TECHNICIAN','Técnico Fictício')) AS s(role,name) LOOP
  acc:=gen_random_uuid(); member:=gen_random_uuid();
  INSERT INTO platform.accounts(id,status,synthetic) VALUES(acc,'ACTIVE',true);
  INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES(actor,'PARTNER_MEMBER_ADDED',NULL,reason_text,
   jsonb_build_object('demo','true','partnerId',new_partner::text,'memberId',member::text,'version','0','role',staff.role));
  INSERT INTO platform.partner_members(id,partner_id,account_id,role,display_name,created_by)
   VALUES(member,new_partner,acc,staff.role,staff.name,actor);
  member_ids:=member_ids||jsonb_build_object(staff.name,member);
  member_accounts:=member_accounts||jsonb_build_object(staff.name,acc);
 END LOOP;
 INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES(actor,'PARTNER_CONTRACT_STARTED',new_tenant,
  reason_text,jsonb_build_object('demo','true','partnerId',new_partner::text,'contractId',new_contract::text,'version','0'));
 INSERT INTO platform.partner_tenant_contracts(id,partner_id,tenant_id,service,starts_at,ends_at,reason,created_by)
  VALUES(new_contract,new_partner,new_tenant,'SECURITY_OPERATOR',started,started+interval '365 days',reason_text,actor);
 INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,reason,details)
  VALUES(new_tenant,'PARTNER_CONTRACT_CHANGED','INFO','PLATFORM','PARTNER_CONTRACT',new_contract,reason_text,
   jsonb_build_object('change','STARTED','partnerId',new_partner,'demo',true));
 n_events:=n_events+1;
 FOR sched IN SELECT * FROM (VALUES('Supervisora Fictícia',interval '0',interval '7 days'),
   ('Operador Remoto Fictício 1',interval '0',interval '12 hours'),
   ('Operadora Remota Fictícia 2',interval '12 hours',interval '24 hours'),
   ('Operador Remoto Fictício 1',interval '24 hours',interval '36 hours')) AS s(name,from_offset,to_offset) LOOP
  schedule_id:=gen_random_uuid();
  INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES(actor,'PARTNER_SCHEDULE_CREATED',new_tenant,
   reason_text,jsonb_build_object('demo','true','partnerId',new_partner::text,'scheduleId',schedule_id::text,'version','0',
    'memberId',member_ids->>sched.name));
  INSERT INTO cp.operator_schedules(id,tenant_id,partner_id,member_id,account_id,starts_at,ends_at,reason,created_by)
   VALUES(schedule_id,new_tenant,new_partner,(member_ids->>sched.name)::uuid,(member_accounts->>sched.name)::uuid,
    started+sched.from_offset,started+sched.to_offset,reason_text,actor);
  INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,details)
   VALUES(new_tenant,'SCHEDULE_ASSIGNED','INFO','PLATFORM','OPERATOR_SCHEDULE',schedule_id,
    jsonb_build_object('memberId',member_ids->>sched.name,'partnerId',new_partner,'demo',true));
  n_schedules:=n_schedules+1; n_events:=n_events+1;
 END LOOP;

 -- Eventos de exemplo do turno, gravados com o operador sintético em escala (papel e parceiro definidos pelo banco) e,
 -- para o equipamento fictício, sem ator.
 PERFORM set_config('app.actor_id',(member_accounts->>'Operador Remoto Fictício 1')::text,true);
 INSERT INTO cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,details) VALUES
  (new_tenant,'SHIFT_STARTED','INFO','REMOTE_CENTRAL','OPERATOR_SCHEDULE',NULL,jsonb_build_object('demo',true)),
  (new_tenant,'CHECKLIST_DONE','INFO','REMOTE_CENTRAL',NULL,NULL,jsonb_build_object('checklist','Abertura de turno (fictício)','demo',true)),
  (new_tenant,'PATROL_CHECKPOINT','INFO','REMOTE_CENTRAL',NULL,NULL,jsonb_build_object('checkpoint','Portão fictício da garagem','demo',true)),
  (new_tenant,'INCIDENT_OPENED','ATTENTION','REMOTE_CENTRAL','OCCURRENCE',o2,jsonb_build_object('demo',true));
 PERFORM set_config('app.actor_id','',true);
 INSERT INTO cp.security_log(tenant_id,event_type,severity,source,details) VALUES
  (new_tenant,'DOOR_HELD_OPEN','ATTENTION','SYSTEM',jsonb_build_object('point','Portão fictício da garagem','demo',true)),
  (new_tenant,'DEVICE_OFFLINE','ATTENTION','SYSTEM',jsonb_build_object('device','Leitor fictício da Torre B','demo',true)),
  (new_tenant,'DEVICE_ONLINE','INFO','SYSTEM',jsonb_build_object('device','Leitor fictício da Torre B','demo',true));
 n_events:=n_events+7;
 PERFORM set_config('app.actor_id',actor::text,true);

 counts:=jsonb_build_object('blocks',2,'units',n_units,'amenities',3,'memberships',n_members,'announcements',3,'parcels',3,
  'visits',2,'occurrences',2,'requests',2,'partnerMembers',6,'schedules',n_schedules,'sampleSecurityEvents',n_events);
 generation_id:=gen_random_uuid(); generation:=gen_no; tenant_id:=new_tenant; partner_id:=new_partner; applied:=true;
 INSERT INTO platform.demo_generations(id,status,generation,tenant_id,partner_id,contract_id,reason,request_key,request_hash,counts,created_by)
  VALUES(generation_id,'ACTIVE',gen_no,new_tenant,new_partner,new_contract,reason_text,demo_provision.request_key,hash,counts,actor);
 SELECT g.created_at INTO created_at FROM platform.demo_generations g WHERE g.id=generation_id;
 INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES(actor,'DEMO_TENANT_PROVISIONED',new_tenant,reason_text,
  jsonb_build_object('demo','true','generation',gen_no::text,'partnerId',new_partner::text,'units',n_units::text,
   'memberships',n_members::text,'retiredTenantId',retired_tenant_id::text));
END $$;

-- Dono cp_demo (sem BYPASSRLS): as funções acima só alcançam o que as políticas de cp_demo liberam. Quem executa a
-- migration precisa poder assumir cp_demo para transferir a posse (CREATE no schema só durante a transferência).
DO $$
DECLARE granted boolean:=false; fn text;
BEGIN
 IF NOT pg_has_role(current_user,'cp_demo','SET') THEN
  EXECUTE format('GRANT cp_demo TO %I WITH INHERIT FALSE, SET TRUE',current_user);
  granted:=true;
 END IF;
 GRANT CREATE ON SCHEMA platform TO cp_demo;
 FOREACH fn IN ARRAY ARRAY['platform.tenant_is_demo(uuid)','platform.partner_is_demo(uuid)','platform.account_is_synthetic(uuid)',
   'platform.demo_provision(uuid,text,text)'] LOOP
  EXECUTE format('ALTER FUNCTION %s OWNER TO cp_demo',fn);
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',fn);
 END LOOP;
 REVOKE CREATE ON SCHEMA platform FROM cp_demo;
 IF granted THEN EXECUTE format('REVOKE cp_demo FROM %I',current_user); END IF;
END $$;
GRANT EXECUTE ON FUNCTION platform.tenant_is_demo(uuid),platform.partner_is_demo(uuid),platform.account_is_synthetic(uuid)
 TO cp_api,cp_worker,cp_onboarding;
GRANT EXECUTE ON FUNCTION platform.demo_provision(uuid,text,text) TO cp_api;
