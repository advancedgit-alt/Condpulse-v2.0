-- Concessão do papel técnico MASTER_IMPLANTACAO em um condomínio (AC-003, V037) — SQL operacional revisado.
-- NÃO EXECUTAR sem aprovação escrita do responsável nomeando a conta e o condomínio (docs/integracoes/facial/DECISOES_PENDENTES.md).
-- Pré-requisito: V037 aplicada pelo Flyway no banco de destino.
-- Executar com a identidade de operação/migração aprovada (nunca a role runtime), em psql:
--   psql "<conexão da operação>" -v account=<uuid-da-conta> -v tenant=<uuid-do-condominio> -v days=<1..90> \
--        -v reason='<motivo e referência da aprovação>' -v ON_ERROR_STOP=1 -f ops/sql/technical-grant-master-implantacao.sql
-- Tudo numa transação: qualquer pré-condição que falhe desfaz a concessão. Não imprime segredo nem e-mail.
-- Escopos concedidos: os de implantação (sem person.review, access.request_open nem audit.export, que exigem pedido
-- específico). Concessão vigente idêntica já existente: nada é gravado (repetição segura).
\set ON_ERROR_STOP 1
\if :{?account}
\else
  \echo 'Informe -v account=<uuid>.'
  SELECT 'variavel account ausente'::int;
\endif
\if :{?tenant}
\else
  \echo 'Informe -v tenant=<uuid>.'
  SELECT 'variavel tenant ausente'::int;
\endif
\if :{?days}
\else
  \echo 'Informe -v days=<1..90>.'
  SELECT 'variavel days ausente'::int;
\endif
\if :{?reason}
\else
  \echo 'Informe -v reason=<motivo e referência da aprovação>.'
  SELECT 'variavel reason ausente'::int;
\endif
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
-- Execuções simultâneas passam em série; a segunda vê a concessão gravada pela primeira.
LOCK TABLE cp.technical_grants IN SHARE ROW EXCLUSIVE MODE;
SELECT set_config('cp.grant_account',:'account',true), set_config('cp.grant_tenant',:'tenant',true),
       set_config('cp.grant_days',:'days',true), set_config('cp.grant_reason',:'reason',true);
DO $$
DECLARE
 target uuid := current_setting('cp.grant_account')::uuid;
 target_tenant uuid := current_setting('cp.grant_tenant')::uuid;
 grant_days integer := current_setting('cp.grant_days')::integer;
 grant_reason text := btrim(current_setting('cp.grant_reason'));
 wanted_scopes text[] := ARRAY['installation.read','installation.manage','installation.bind_channel','installation.commission',
  'connector.rotate_secret','gateway.manage','homologation.request'];
 ok boolean;
BEGIN
 IF current_user IN ('cp_api','cp_worker','cp_onboarding') OR EXISTS(SELECT 1 FROM pg_auth_members m
    JOIN pg_roles grp ON grp.oid=m.roleid JOIN pg_roles who ON who.oid=m.member
    WHERE who.rolname=current_user AND grp.rolname IN ('cp_api','cp_worker','cp_onboarding')) THEN
  RAISE EXCEPTION 'Identidade de execução não permitida: %', current_user;
 END IF;
 IF to_regclass('cp.technical_grants') IS NULL THEN RAISE EXCEPTION 'V037 não aplicada neste banco.'; END IF;
 IF grant_days NOT BETWEEN 1 AND 90 THEN RAISE EXCEPTION 'Validade deve ser de 1 a 90 dias.'; END IF;
 IF length(grant_reason) NOT BETWEEN 10 AND 500 THEN RAISE EXCEPTION 'Motivo deve ter de 10 a 500 caracteres.'; END IF;
 SELECT status='ACTIVE' INTO ok FROM platform.accounts WHERE id=target;
 IF ok IS NULL THEN RAISE EXCEPTION 'Conta não existe em platform.accounts.'; END IF;
 IF NOT ok THEN RAISE EXCEPTION 'Conta não está ativa.'; END IF;
 SELECT active INTO ok FROM platform.tenants WHERE id=target_tenant;
 IF ok IS NULL THEN RAISE EXCEPTION 'Condomínio não existe.'; END IF;
 IF NOT ok THEN RAISE EXCEPTION 'Condomínio não está ativo.'; END IF;
 -- Administração técnica exige segundo fator: fator TOTP verificado no Supabase Auth.
 IF to_regclass('auth.mfa_factors') IS NULL THEN RAISE EXCEPTION 'Tabela auth.mfa_factors ausente: confira o projeto.'; END IF;
 EXECUTE 'SELECT EXISTS(SELECT 1 FROM auth.mfa_factors WHERE user_id=$1 AND factor_type=''totp'' AND status=''verified'')'
  INTO ok USING target;
 IF NOT ok THEN RAISE EXCEPTION 'Conta sem fator TOTP verificado.'; END IF;
 IF EXISTS(SELECT 1 FROM cp.technical_grants g WHERE g.tenant_id=target_tenant AND g.account_id=target
    AND g.role_code='MASTER_IMPLANTACAO' AND g.status='ACTIVE' AND g.valid_from<=now() AND g.valid_until>now()
    AND g.scopes @> wanted_scopes) THEN
  RAISE NOTICE 'Concessão vigente equivalente já existe; nada gravado.';
  RETURN;
 END IF;
 INSERT INTO cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason)
 VALUES(gen_random_uuid(),target_tenant,target,'MASTER_IMPLANTACAO',wanted_scopes,clock_timestamp(),
  clock_timestamp()+make_interval(days=>grant_days),'OPERATIONS',grant_reason);
END $$;
-- Conferência na mesma transação: concessões vigentes da conta no condomínio, sem dados pessoais.
SELECT id, role_code, array_to_string(scopes,',') AS scopes, granted_via, granted_by_db_user, valid_until
FROM cp.technical_grants
WHERE tenant_id=:'tenant'::uuid AND account_id=:'account'::uuid AND status='ACTIVE' AND valid_until>now()
ORDER BY created_at DESC;
COMMIT;
