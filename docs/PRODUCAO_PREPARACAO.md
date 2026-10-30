# Preparação da produção — roteiro sem execução

Estado em 30/10/2026: o projeto de produção `tkxcujckpsuawnzkvpjg` (CondPulse-producao, sa-east-1) tem só o schema
V001-V017, aplicado pelo Flyway normal em 29/10/2026 (migrate 40884195154, sem baseline). Não há login runtime, Auth,
Storage, origem nem aplicação apontando para ele; a Vercel Production `condpulse` continua ligada à homologação.
**Nada deste roteiro foi executado.** Cada passo de escrita em produção exige OK explícito do responsável
(CLAUDE.md, AMBIENTES.md, ADR-0007).

## Ordem proposta

| # | Passo | Quem | Onde | Pré-requisito |
| --- | --- | --- | --- | --- |
| 1 | `inspect` do banco de produção (não escreve) | Claude ou responsável | `production-database-operations.yml`, `operation=inspect` | Environment `condpulse-production-database-operations` com `CP_PRODUCTION_DATABASE_PASSWORD` (já usado no migrate de 29/10) |
| 2 | Migrate V018 até a última migration aprovada na homologação | Claude, **com OK** | mesmo workflow, `operation=migrate` | OK do responsável, janela sem outro DDL, backup compatível |
| 3 | Logins exclusivos de menor privilégio: `cp_runtime_*` membro só do grupo `cp_api` e `cp_worker_*` membro só do grupo `cp_worker` (este `NOLOGIN` até a hospedagem). Os grupos `cp_api` e `cp_worker` continuam `NOLOGIN`, como exigem `infra/validate-release.mjs` e `infra/operations/worker-login.sql` | Responsável (ou execução autorizada por escrito) | SQL operacional revisado, senha só no cofre | Decisão de onde a senha fica guardada |
| 4 | Auth do projeto: signup público desligado (feito), MFA TOTP, SMTP próprio, URLs de redirecionamento | Responsável | Painel Supabase Auth | Provedor SMTP escolhido |
| 5 | Storage: três buckets privados e políticas, chave de serviço só no cofre | Responsável | Painel Supabase Storage / cofre | — |
| 6 | Origem de produção: projeto Vercel **separado** (ou Coolify, ADR-0005), domínio, variáveis próprias (nunca cópia das de homologação) | Responsável | Vercel / DNS | Decisão de domínio e custo |
| 7 | Chaves do BFF, do código de retirada (`CP_PARCEL_CODE_KEY`) e da Storage geradas para produção | Responsável | Cofre do ambiente de produção | Passo 6 |
| 8 | Onboarding do primeiro condomínio real pelo executor de onboarding | Responsável aprova o manifesto | `onboarding-operations.yml` (alvo de produção ainda não suportado: exige PR revisada) | Passos 2-7 |
| 9 | Flags ligadas por módulo, uma por deployment, com smoke | Claude, **com OK** | Projeto Vercel de produção | Passo 8 |
| 10 | Ensaio com login real, MFA e negações entre unidades/condomínios | Responsável com Claude | URL de produção | Passo 9 |

## Comandos (modelo; não executados)

```
# 1. Somente leitura
gh workflow run production-database-operations.yml -R advancedgit-alt/Condpulse-v2.0 --ref ccr-5e725112-3x8hyg \
  -f operation=inspect -f expected_commit=<SHA_REVISADO>

# 2. Escrita — só com OK explícito do responsável
gh workflow run production-database-operations.yml -R advancedgit-alt/Condpulse-v2.0 --ref ccr-5e725112-3x8hyg \
  -f operation=migrate -f expected_commit=<SHA_REVISADO> \
  -f migration_ack=tkxcujckpsuawnzkvpjg:018-<ULTIMA>:<SHA_REVISADO> -f maintenance_confirmed=true
```

Depois do migrate: repetir o `migrate` (deve terminar sem linha nova), conferir por SQL somente leitura o histórico
Flyway, RLS FORCE em todas as tabelas `cp` e ownership `postgres`, e registrar runs em STATUS.md. Produção nunca recebe
baseline legado, cópia de dados ou de segredos da homologação.

## O que já está pronto no repositório

- Executor de banco de produção com guardas (alvo fixo, sem baseline, versões contíguas, validação antes e depois).
- Migrations V018-V025 aplicadas e repetidas na homologação, com smoke público; V026 em revisão (PR #79).
- Manifestos de release e validador (`infra/validate-release.mjs`), roteiro do worker (WORKER_IMPLANTACAO.md),
  configuração do runtime e do BFF (CONFIGURACAO_RUNTIME.md, CONFIGURACAO_BFF.md).

## Bloqueios

- BLOQUEADO: OK para escrever em produção (passos 2 e 9) — responsável — por escrito, nesta conversa ou no PR.
- BLOQUEADO: logins de produção, Auth/SMTP, Storage, origem/domínio e segredos (passos 3-7) — responsável — painéis do
  Supabase e da Vercel/Coolify e cofre.
- BLOQUEADO: suporte do executor de onboarding ao alvo de produção (passo 8) — Claude, depois do OK do responsável — PR
  revisada; os guards atuais fixam a homologação.
- BLOQUEADO: PITR/backup e ensaio de restore — responsável — plano Supabase (custo).
