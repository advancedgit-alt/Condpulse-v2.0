# Painel master (fase 4, OPS-01)

Flag: `CP_PLATFORM_ENABLED` (padrão `false`, inclusive na Vercel). Com a flag desligada as rotas
`/v1/platform/*` não existem. V010 não foi aplicada a nenhum banco remoto: o executor de migrations
continua fixado em V003–V005 e qualquer aplicação exige revisão e autorização próprias.

## O que o painel faz

| Operação | Rota | Capacidade |
| --- | --- | --- |
| Papel da própria conta (e se falta o segundo fator) | `GET /v1/platform/session` | papel vigente |
| Catálogo de condomínios: nome, fuso, estado, versão, contagens do onboarding; busca opcional `q` | `GET /v1/platform/tenants` | `tenants.read` |
| Renomear, suspender ou reativar pela versão lida, com motivo | `PUT /v1/platform/tenants/{id}` | `tenants.manage` |
| Auditoria da plataforma, por cursor | `GET /v1/platform/audit-events` | `platform.audit.read` |

- **Papel**: `PLATFORM_ADMIN` em `platform.platform_roles`, um por conta, `ACTIVE` ou `REVOKED`, com motivo.
  A role runtime só lê o próprio papel; não concede, altera nem revoga papéis.
- **Capacidades**: publicadas em `app.platform_capabilities` pelo `PlatformTransactionExecutor` somente depois
  de conta ativa, papel vigente e token `aal2`, e somente no contexto local da transação. Sem AAL2 a sessão
  informa `mfaRequired` e toda operação recebe `403 MFA_REQUIRED`; conta sem papel recebe
  `403 PLATFORM_ACCESS_DENIED`. O papel é relido em cada requisição: revogação vale na próxima.
- **Banco (V010)**: `platform.tenants` ganha `version`/`updated_at`; leitura global só com `tenants.read`; o
  UPDATE só alcança linhas com `tenants.read` e grava só com `tenants.manage` (sem ela, 42501); membro do
  condomínio não alcança nem trava a linha do catálogo; o fuso fica fora do grant. Do recibo de onboarding a
  role lê só `operation_id`, `tenant_id`, contagens e data — não o hash do manifesto nem o principal do banco.
  `platform.platform_audit_log` é append-only para a role runtime: inserção exige o próprio ator e
  `tenants.manage`; `id` e `created_at` vêm do banco. A gravação da trilha a cada mudança é garantida pelo
  serviço, na mesma transação da alteração (o banco não exige a linha de auditoria; risco residual registrado).
- **Convite em condomínio suspenso**: o aceite devolve `404 INVITATION_NOT_AVAILABLE` e nada é gravado; o banco
  também recusa o vínculo. O convidado sem vínculo só enxerga o condomínio ativo do convite pendente apresentado.
- **Busca**: `q` procura trecho do nome sem diferenciar maiúsculas (acentos contam; `%` e `_` são texto) ou o id
  exato; alcança condomínios além dos 200 primeiros da lista em ordem alfabética.
- **Nome**: comprimento em code points (como o banco e o onboarding), qualquer espaço Unicode vira um espaço e
  caracteres de controle ou de formatação invisível (U+200B, U+202E…) são recusados — a web aplica a mesma regra.
  No `PUT`, `name` ausente ou `null` mantém o nome atual: suspender ou reativar nunca renomeia.
- **Mudanças**: pedido igual ao estado atual devolve `applied=false` sem gravar (repetição natural); versão
  divergente com mudança real é `409 TENANT_CHANGED`. Cada mudança grava um evento: `TENANT_RENAMED` (com nome
  anterior e novo), `TENANT_SUSPENDED` ou `TENANT_REACTIVATED`, com o motivo.
- **Suspensão**: o condomínio sai de `/v1/me` e o executor de tenant passa a recusar o contexto
  (`404 TENANT_ACCESS_DENIED`) na próxima requisição de moradores e gestão local. Não apaga dados, sessões ou
  reservas e não é automática por atraso de pagamento (fase 5).

## Tela web (`/master`)

Sem link na escolha de contexto: a administração acessa `/master` diretamente. A tela consulta
`/v1/platform/session`; conta sem papel vê "Área restrita" sem nenhum dado da plataforma; flag desligada mostra
"Painel indisponível"; sem AAL2 a tela cadastra/verifica o autenticador antes de qualquer outra rota. Seções:
Condomínios (lista com estado, fuso, versão e recibo do onboarding; alterar nome ou estado com motivo, pela
versão lida ao abrir, com aviso e recarga quando outra pessoa mudou o condomínio) e Auditoria (por cursor,
horários em America/Sao_Paulo). Repetição natural aparece como informação, nunca como sucesso; `403
PLATFORM_ACCESS_DENIED` em qualquer consulta remove os dados da tela.

## O que o painel não faz

- Não cria condomínio. O cadastro segue pelo onboarding operacional (`OPERACAO_ONBOARDING.md`), que confere as
  contas no Auth com credencial administrativa que a API não recebe (ADR-0007).
- Não lê dados de negócio dos condomínios (unidades, moradores, reservas, auditoria local): as políticas
  `tenant_boundary` do schema `cp` continuam fechadas sem condomínio no contexto.
- Não age em nome de outra conta, não concede papel de plataforma e não dá poder de plataforma a `MANAGER`.
  Suporte temporário com finalidade, vigência e escopo (OPS-01) continua pendente.

## Conceder e revogar o papel (operação, fora da API)

O primeiro administrador é concedido por SQL operacional revisado, executado pela identidade de migração ou de
operação aprovada — nunca pela role runtime. Modelo, com valores reais preenchidos só na execução revisada:

```sql
INSERT INTO platform.platform_roles(account_id,role,status,reason)
VALUES('<uuid-da-conta-existente>','PLATFORM_ADMIN','ACTIVE','<motivo e referência da aprovação>');
-- Revogação (o motivo da concessão é preservado em reason):
UPDATE platform.platform_roles SET status='REVOKED',revoked_at=clock_timestamp(),revoke_reason='<motivo e referência>'
WHERE account_id='<uuid-da-conta>';
-- Nova concessão depois de revogar:
UPDATE platform.platform_roles SET status='ACTIVE',revoked_at=NULL,revoke_reason=NULL,granted_at=clock_timestamp(),
 reason='<motivo e referência da nova aprovação>' WHERE account_id='<uuid-da-conta>';
```

Cada INSERT, UPDATE ou DELETE em `platform_roles` grava, por gatilho e na mesma transação,
`platform.platform_role_events` (operação, estado, motivos e `executed_by` = identidade do banco que executou).
Nenhuma role de aplicação lê ou escreve essa trilha. As duas tabelas têm RLS forçado sem política para escrita:
o SQL só funciona com identidade de migração/operação que ignore RLS (por exemplo, com `BYPASSRLS`) — conferir
a identidade antes da execução revisada. A conta precisa existir e estar ativa em `platform.accounts` e ter fator
TOTP verificado para usar o painel. Registrar a execução (quem aprovou, quando, run) em `STATUS.md`. Nenhuma
concessão foi feita.

### Roteiro revisado do primeiro administrador

[`ops/sql/platform-admin-grant.sql`](../ops/sql/platform-admin-grant.sql) executa a concessão inicial numa única
transação, sob trava de `platform_roles` que serializa execuções simultâneas, e só conclui se todas as pré-condições valerem: identidade de execução fora de `cp_api`/`cp_worker`/
`cp_onboarding` (nem membro desses grupos), motivo de 10 a 500 caracteres, conta existente e ativa em
`platform.accounts`, fator TOTP verificado em `auth.mfa_factors` e nenhum papel de plataforma ativo (o roteiro serve
só para o primeiro). No fim confere, na mesma transação, um único papel ativo da conta e o evento gravado pelo gatilho.
Ensaiado em PostgreSQL descartável com a cadeia V001 em diante e um `auth.mfa_factors` sintético: sem TOTP, motivo
curto, conta inexistente, segundo administrador, variável ausente e execução como `cp_api` falharam sem gravar; o caso
válido gravou um papel e um evento `INSERT/ACTIVE`. Duas execuções simultâneas para contas diferentes: sem a trava as duas
concediam o papel; com a trava a segunda espera e é recusada.

BLOQUEADO: aprovação escrita do responsável nomeando a conta que recebe `PLATFORM_ADMIN`, cadastro do TOTP dessa
conta e escolha da identidade de operação que executa — responsável — canal privado; a execução registra run/horário
e quem aprovou em `STATUS.md`. Gestão local não implica papel de plataforma.

## Pendências

- Aplicação remota da V010 e concessão do primeiro papel, com aprovação explícita.
- Suporte temporário justificado (OPS-01). Contratos/planos e situação financeira: ver
  [FINANCEIRO_PLATAFORMA.md](FINANCEIRO_PLATAFORMA.md) (fase 5).
- Ensaio com Supabase Auth real (AAL2) e aceite do painel em navegador com dados reais.
- Exigir no banco a linha de auditoria a cada mudança de `platform.tenants` (hoje garantida pelo serviço).
- Teste de duas alterações simultâneas pelo HTTP e paginação por cursor do catálogo (hoje: 200 + busca).
