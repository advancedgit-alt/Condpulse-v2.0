-- Concessão do primeiro papel de plataforma (PLATFORM_ADMIN) — SQL operacional revisado.
-- NÃO EXECUTAR sem aprovação escrita do responsável nomeando a conta (docs/PAINEL_MASTER.md).
-- Executar com a identidade de operação/migração aprovada (nunca a role runtime), em psql:
--   psql "<conexão da operação>" -v account=<uuid-da-conta> -v reason='<motivo e referência da aprovação>' \
--        -v ON_ERROR_STOP=1 -f ops/sql/platform-admin-grant.sql
-- Tudo numa transação: qualquer pré-condição falha desfaz a concessão. Não imprime segredo nem e-mail.
\set ON_ERROR_STOP 1
\if :{?account}
\else
  \echo 'Informe -v account=<uuid>.'
  SELECT 'variavel account ausente'::int;
\endif
\if :{?reason}
\else
  \echo 'Informe -v reason=<motivo e referência da aprovação>.'
  SELECT 'variavel reason ausente'::int;
\endif
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
-- Trava que conflita consigo mesma, mantida até o COMMIT: duas execuções simultâneas (mesmo para contas diferentes)
-- passam em série, e a segunda já vê o papel ativo gravado pela primeira e é recusada.
LOCK TABLE platform.platform_roles IN SHARE ROW EXCLUSIVE MODE;
SELECT set_config('cp.grant_account',:'account',true), set_config('cp.grant_reason',:'reason',true);
DO $$
DECLARE
 target uuid := current_setting('cp.grant_account')::uuid;
 grant_reason text := btrim(current_setting('cp.grant_reason'));
 identity_ok boolean;
BEGIN
 -- A identidade que executa não pode ser a role runtime nem um grupo da aplicação.
 IF current_user IN ('cp_api','cp_worker','cp_onboarding') OR EXISTS(SELECT 1 FROM pg_auth_members m
    JOIN pg_roles grp ON grp.oid=m.roleid JOIN pg_roles who ON who.oid=m.member
    WHERE who.rolname=current_user AND grp.rolname IN ('cp_api','cp_worker','cp_onboarding')) THEN
  RAISE EXCEPTION 'Identidade de execução não permitida: %', current_user;
 END IF;
 IF length(grant_reason) NOT BETWEEN 10 AND 500 THEN RAISE EXCEPTION 'Motivo deve ter de 10 a 500 caracteres.'; END IF;
 SELECT status='ACTIVE' INTO identity_ok FROM platform.accounts WHERE id=target;
 IF identity_ok IS NULL THEN RAISE EXCEPTION 'Conta não existe em platform.accounts.'; END IF;
 IF NOT identity_ok THEN RAISE EXCEPTION 'Conta não está ativa.'; END IF;
 -- Fator TOTP verificado no Supabase Auth: sem ele o painel exige cadastro antes de qualquer uso.
 IF to_regclass('auth.mfa_factors') IS NULL THEN RAISE EXCEPTION 'Tabela auth.mfa_factors ausente: confira o projeto.'; END IF;
 EXECUTE 'SELECT EXISTS(SELECT 1 FROM auth.mfa_factors WHERE user_id=$1 AND factor_type=''totp'' AND status=''verified'')'
  INTO identity_ok USING target;
 IF NOT identity_ok THEN RAISE EXCEPTION 'Conta sem fator TOTP verificado.'; END IF;
 IF EXISTS(SELECT 1 FROM platform.platform_roles WHERE status='ACTIVE') THEN
  RAISE EXCEPTION 'Já existe papel de plataforma ativo: este roteiro é só para o primeiro administrador.';
 END IF;
 IF EXISTS(SELECT 1 FROM platform.platform_roles WHERE account_id=target) THEN
  UPDATE platform.platform_roles SET status='ACTIVE',revoked_at=NULL,revoke_reason=NULL,granted_at=clock_timestamp(),reason=grant_reason
   WHERE account_id=target;
 ELSE
  INSERT INTO platform.platform_roles(account_id,role,status,reason) VALUES(target,'PLATFORM_ADMIN','ACTIVE',grant_reason);
 END IF;
END $$;
-- Conferência na mesma transação: um papel ativo e o evento gravado pelo gatilho.
SELECT count(*) FILTER(WHERE status='ACTIVE') AS active_roles,
       bool_and(account_id=:'account'::uuid) FILTER(WHERE status='ACTIVE') AS is_target
FROM platform.platform_roles;
SELECT operation,status,executed_by FROM platform.platform_role_events WHERE account_id=:'account'::uuid ORDER BY created_at DESC LIMIT 1;
COMMIT;
