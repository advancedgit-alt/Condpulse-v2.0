# Painel master (fase 4, OPS-01)

Flag: `CP_PLATFORM_ENABLED` (padrão `false`, inclusive na Vercel). Com a flag desligada as rotas
`/v1/platform/*` não existem. V010 não foi aplicada a nenhum banco remoto: o executor de migrations
continua fixado em V003–V005 e qualquer aplicação exige revisão e autorização próprias.

## O que o painel faz

| Operação | Rota | Capacidade |
| --- | --- | --- |
| Papel da própria conta (e se falta o segundo fator) | `GET /v1/platform/session` | papel vigente |
| Catálogo de condomínios: nome, fuso, estado, versão, contagens do onboarding | `GET /v1/platform/tenants` | `tenants.read` |
| Renomear, suspender ou reativar pela versão lida, com motivo | `PUT /v1/platform/tenants/{id}` | `tenants.manage` |
| Auditoria da plataforma, por cursor | `GET /v1/platform/audit-events` | `platform.audit.read` |

- **Papel**: `PLATFORM_ADMIN` em `platform.platform_roles`, um por conta, `ACTIVE` ou `REVOKED`, com motivo.
  A role runtime só lê o próprio papel; não concede, altera nem revoga papéis.
- **Capacidades**: publicadas em `app.platform_capabilities` pelo `PlatformTransactionExecutor` somente depois
  de conta ativa, papel vigente e token `aal2`, e somente no contexto local da transação. Sem AAL2 a sessão
  informa `mfaRequired` e toda operação recebe `403 MFA_REQUIRED`; conta sem papel recebe
  `403 PLATFORM_ACCESS_DENIED`. O papel é relido em cada requisição: revogação vale na próxima.
- **Banco (V010)**: `platform.tenants` ganha `version`/`updated_at`; leitura global e alteração de nome/estado
  só com a capacidade publicada (UPDATE recusado com 42501, não "não encontrado"); o fuso fica fora do grant.
  Do recibo de onboarding a role lê só `operation_id`, `tenant_id`, contagens e data — não o hash do manifesto
  nem o principal do banco. `platform.platform_audit_log` é append-only para a role runtime: inserção exige o
  próprio ator e capacidade de plataforma publicada; `created_at` vem do banco.
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
-- Revogação:
UPDATE platform.platform_roles SET status='REVOKED',revoked_at=now(),reason='<motivo>'
WHERE account_id='<uuid-da-conta>';
```

A conta precisa existir e estar ativa em `platform.accounts` e ter fator TOTP verificado para usar o painel.
Registrar a execução (quem aprovou, quando, run) em `STATUS.md`. Nenhuma concessão foi feita.

## Pendências

- Aplicação remota da V010 e concessão do primeiro papel, com aprovação explícita.
- Suporte temporário justificado (OPS-01), contratos/planos e situação financeira (fase 5).
- Ensaio com Supabase Auth real (AAL2) e aceite do painel em navegador com dados reais.
