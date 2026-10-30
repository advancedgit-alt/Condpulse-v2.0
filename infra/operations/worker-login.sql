-- CondPulse: login exclusivo do worker contínuo, fora do schema Flyway. NÃO EXECUTADO.
-- Executar somente com aprovação escrita do responsável, no projeto indicado, por psql como postgres:
--   psql "<conexão administrativa do cofre>" -v worker_login=cp_worker_<sufixo> -v project_ref=<ref> -f worker-login.sql
-- A role nasce SEM senha e sem poder entrar (NOLOGIN). A senha é definida pelo responsável, no próprio psql, com
-- \password <login> (o valor nunca passa por arquivo, log, chat ou repositório), e só então:
--   ALTER ROLE <login> LOGIN;
-- Orçamento: pool do worker 4 (infra/compose.worker.yaml) + 2 de margem; somar ao orçamento da ADR-0006 antes de aplicar.
\set ON_ERROR_STOP on
SELECT set_config('condpulse.worker_login', :'worker_login', false);
SELECT set_config('condpulse.project_ref', :'project_ref', false);
DO $condpulse_worker_login$
DECLARE
  login text := current_setting('condpulse.worker_login');
  expected_project text := current_setting('condpulse.project_ref');
  existing record;
  other_memberships integer;
  schema_ready boolean;
BEGIN
  PERFORM set_config('lock_timeout', '2s', true);
  PERFORM set_config('statement_timeout', '10s', true);
  IF current_user <> 'postgres' OR session_user <> 'postgres' OR current_database() <> 'postgres' THEN
    RAISE EXCEPTION 'WORKER_LOGIN_ACTOR_DENIED';
  END IF;
  IF login !~ '^cp_worker_[a-z0-9_]{4,40}$' THEN RAISE EXCEPTION 'WORKER_LOGIN_NAME_INVALID'; END IF;
  IF expected_project !~ '^[a-z0-9]{20}$' THEN RAISE EXCEPTION 'WORKER_LOGIN_PROJECT_INVALID'; END IF;
  IF to_regclass('platform.flyway_schema_history') IS NULL THEN RAISE EXCEPTION 'WORKER_LOGIN_SCHEMA_TOO_OLD'; END IF;
  EXECUTE 'SELECT EXISTS(SELECT 1 FROM platform.flyway_schema_history WHERE success AND version=''15'')' INTO schema_ready;
  IF NOT schema_ready THEN RAISE EXCEPTION 'WORKER_LOGIN_SCHEMA_TOO_OLD'; END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='cp_worker' AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls) THEN
    RAISE EXCEPTION 'WORKER_GROUP_UNSAFE';
  END IF;
  SELECT * INTO existing FROM pg_roles WHERE rolname=login;
  IF FOUND THEN
    -- Repetição: só confere, nunca altera senha nem LOGIN.
    SELECT count(*) INTO other_memberships FROM pg_auth_members m JOIN pg_roles g ON g.oid=m.roleid
     WHERE m.member=existing.oid AND g.rolname<>'cp_worker';
    IF existing.rolsuper OR existing.rolbypassrls OR existing.rolcreatedb OR existing.rolcreaterole OR existing.rolreplication
       OR NOT existing.rolinherit OR other_memberships>0
       OR NOT pg_has_role(login,'cp_worker','MEMBER') THEN
      RAISE EXCEPTION 'WORKER_LOGIN_EXISTING_UNSAFE';
    END IF;
    RAISE NOTICE 'WORKER_LOGIN_ALREADY_PRESENT';
    RETURN;
  END IF;
  EXECUTE format('CREATE ROLE %I NOLOGIN INHERIT NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 6', login);
  EXECUTE format('GRANT cp_worker TO %I', login);
  EXECUTE format('COMMENT ON ROLE %I IS %L', login, 'condpulse-worker:'||expected_project||':PENDING_PASSWORD');
  RAISE NOTICE 'WORKER_LOGIN_CREATED_NOLOGIN';
END
$condpulse_worker_login$;
