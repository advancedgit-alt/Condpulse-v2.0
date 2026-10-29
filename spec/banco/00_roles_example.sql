-- Executar por DBA em ambiente descartável antes de V002. Nenhuma senha é definida aqui.
-- Confirmar privilégios CREATE ROLE no projeto gerenciado. Não usar essas roles como superuser.
DO $$ BEGIN
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='cp_api') THEN
    CREATE ROLE cp_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
  END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='cp_worker') THEN
    CREATE ROLE cp_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
  END IF;
END $$;
-- Criar logins separados com segredo no cofre e conceder SOMENTE a role aplicável.
-- O usuário dono da migration NÃO deve ser usado pela API.
