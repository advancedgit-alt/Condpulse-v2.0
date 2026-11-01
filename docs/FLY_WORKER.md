# Worker contínuo de homologação na Fly.io

Situação: preparado neste PR, **nada executado**. Nenhum app, login de banco, segredo ou máquina do worker foi criado e
nenhum teto de conexão foi alterado.

## O que muda

O worker (perfil `worker`: entrega do outbox e varredura da fila de espera) passa a poder rodar como processo contínuo
no app Fly **`condpulse-v2-0-worker`**, separado do app da API (`condpulse-v2-0`), na organização `personal`, região
`gru`. Resolve o bloqueio "hospedagem do worker contínuo" de `docs/WORKER_IMPLANTACAO.md` com o mesmo provedor já usado
pela API (FLY_API.md).

- `fly.worker.toml`: sem serviço HTTP nem checagem (o perfil `worker` não abre porta), `[[restart]] on-failure`
  (até 10), VM `shared-cpu-1x` 1 GB, `kill_timeout` 35 s (a passada em curso tem até 15 s). Configuração pública em
  `[env]`, revisada por PR: `SPRING_PROFILES_ACTIVE=worker`, `DATABASE_URL` (pooler São Paulo, `verify-full`),
  `DB_POOL_SIZE=1`, `CP_WORKER_OUTBOX_ENABLED=false`, `CP_WORKER_POLL_MILLIS=2000`, `CP_WORKER_WAITLIST_ENABLED=false`,
  `CP_WORKER_WAITLIST_MILLIS=60000`, `CP_WORKER_ACCESS_SYNC_ENABLED=false`, `CP_WORKER_STAYS_ENABLED=false` e
  `CP_WORKER_STAYS_MILLIS=60000` (alertas de permanência, V055; o launcher aceita 10 s a 10 min).
- `infra/fly-worker/Dockerfile`: mesmas bases fixadas por digest da API, JAR do backend, usuário 10001, sem `EXPOSE`.
- `infra/fly-worker/start-worker.sh`: launcher que falha fechado (código sem valor) se o perfil não for `worker`, a URL
  JDBC não for exatamente a revisada, o login não for `cp_worker_<32 hex>.rpnrqfrfwywglvyykgrg`, o pool não for 1-2,
  as flags não forem `true|false`, a reconciliação de credenciais estiver ligada, os intervalos estiverem fora da faixa
  (outbox: 500-60000 ms; fila: 10000-3600000 ms), ou houver variável privilegiada (`CP_BFF_*`, `CP_STORAGE_*`,
  `CP_MIGRATION_*`, `CP_ONBOARDING_*`, `*SERVICE_ROLE*`, `*SECRET_KEY*`) ou sobrescrita do Spring/JVM. Grava só a CA
  pública em `/tmp` e sobe o JAR com Flyway desligado.
- `.github/workflows/fly-worker.yml`: manual, só na branch principal, gate sem segredos antes do Environment
  `condpulse-database-operations`, mesmo grupo de concorrência dos executores do banco, operações
  `inspect | apply | deploy | vercel-limit`.
- `infra/scripts/fly-worker/fly-worker.mjs` (orquestração) e `FlyWorkerLogin.java` (login de banco e teto da Vercel).

O worker não recebe nada da Vercel, do BFF nem do Storage. Por isso uma flag ligada na Vercel (por exemplo
`CP_SECURITY_LOG_ENABLED`) não chega ao worker: a âncora diária do Log de Segurança segue o `fly.worker.toml`
(`CP_SECURITY_LOG_ENABLED`, ligada como `"true"` em 01/11/2026 por PR revisado + `deploy` do worker; desligar segue o mesmo caminho). Os únicos segredos da Fly são `DATABASE_USER`,
`DATABASE_PASSWORD` (gerada no runner) e `CP_DB_CA_BASE64` (CA pública do Supabase validada com openssl no runner),
gravados por `flyctl secrets import --stage` (valores só no stdin) e conferidos só pelos nomes.

## Login de banco exclusivo do worker

- Nome `cp_worker_<geração sem hífens>`, marker `condpulse-worker-fly:<projeto>:<geração>:<SHA>:<estado>`.
- Mesma política do login da API na Fly, trocando o grupo: `NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOREPLICATION NOBYPASSRLS`, associação única a `cp_worker` (ADMIN=false/INHERIT=true/SET=false), nunca `cp_api`, nenhum
  grant direto, privilégio efetivo idêntico ao de `cp_worker` em tabelas, colunas, funções e schemas, senha SCRAM de 48
  caracteres com validade de 10 minutos, prova de login (identidade exata, `row_security=on`, nenhuma tarefa do outbox
  visível sem condomínio), READY só depois de a Fly confirmar os nomes. Falha depois do STAGED deixa DISABLED.
- Conferências do grupo: `cp_worker` sem login/atributo privilegiado, sem herdar outro grupo, sem DDL/posse e sem
  TRUNCATE/REFERENCES/TRIGGER/MAINTAIN ou repasse de grant. Histórico BASELINE 002 + SQL contíguas até pelo menos a
  V023, nada pendente no JAR revisado.
- Dono de função (ADR-0012, V053): como no login da API, aceita função de `platform`/`cp` com dono `cp_demo`
  (SECURITY DEFINER) só quando a role é NOLOGIN, NOSUPERUSER, NOBYPASSRLS, NOCREATEROLE, NOCREATEDB, NOREPLICATION,
  NOINHERIT, sem membros e sem ser membro de ninguém; demais funções seguem exigindo dono `postgres`
  (`FLY_WORKER_FUNCTION_OWNER_DIVERGED`).
- Outro login `cp_worker_*` (inclusive um criado por `infra/operations/worker-login.sql`) ou qualquer outro membro de
  `cp_worker` bloqueia. Roles `cp_runtime_*` só são aceitas com marker da Vercel ou da API na Fly e teto finito.
- **CONNECTION LIMIT 2, pool Hikari 1.** O agendador do Spring roda uma passada por vez e cada transação do worker abre
  e fecha a sua, então 1 conexão basta; a segunda cobre a sobreposição de um reinício.

## Orçamento de conexões (ADR-0006)

Regra do executor: backends que não são `cp_runtime_*` nem `cp_worker_*` + soma dos tetos de todas as roles
`cp_runtime_*` + 2 + 20 ≤ `max_connections`, que precisa ser 60. A inspeção imprime esses números (contagens, sem nomes).

Com a leitura de 30/10/2026 citada em FLY_API.md (18 backends não runtime; a contagem inclui a própria sessão do
executor) e os tetos atuais (Vercel 16, API na Fly 4):

| Cenário | Conta | Resultado |
|---|---|---|
| Hoje | 18 + (16 + 4) + 2 + 20 = 60 | cabe, folga 0; com 19 backends, `FLY_WORKER_BUDGET_INSUFFICIENT` |
| Depois do `vercel-limit` | 18 + (4 + 4) + 2 + 20 = 48 | cabe, folga 12 |

Esses números são uma fotografia; o executor relê tudo na hora do `apply`.

### Operação separada `vercel-limit` (proposta, escrita no banco)

O tráfego de `/auth`, `/v1` e da readiness foi trocado para a Fly em 30/10/2026; o login da Vercel continua com teto 16
sem receber tráfego. A operação `vercel-limit` muda **só** o `CONNECTION LIMIT` do login READY da Vercel de 16 para 4,
com ACK próprio (`vercelLimitAck`, impresso pela inspeção apenas quando elegível):

- exige exatamente um login com marker da Vercel, READY, com LOGIN e validade infinita, associação única a `cp_api` e
  teto exatamente 16; 4 já aplicado é noop; outro valor é recusado (`FLY_WORKER_VERCEL_LIMIT_UNEXPECTED`);
- exige no máximo 4 backends atuais desse login (`FLY_WORKER_VERCEL_BACKENDS_EXCEED`);
- usa o mesmo advisory lock dos executores, confere o fingerprint da inspeção e relê teto, LOGIN, validade e marker.

Não muda senha, marker, grants nem sessões e não roda junto do `apply` do worker. Efeito colateral: se o tráfego voltar
para a Vercel (rollback da troca), o teto 4 reproduz o cenário de 53300 da ADR-0006; voltar a 16 é o
`infra/operations/runtime-connection-budget.sql` (4→16) revisado, depois de conferir a margem com o worker.

O executor da API na Fly (`FlyRuntimeLogin`) conta os backends do worker como "não runtime" (uso real, até 2).

## Como rodar (cada dispatch com OK do responsável)

Sempre na branch principal, com o SHA revisado. Geração nova: `uuidgen | tr A-Z a-z`.

1. `inspect` com `expected_commit` e `generation`: imprime em `::notice` existência do app, nomes dos segredos, estado do
   login, `budget` e `vercel`, e os ACKs `applyAck`, `deployAck` (só com app existente) e `vercelLimitAck` (só elegível).
2. Se `budget.fits` for `false` (ou para ter folga): `vercel-limit` com `generation` vazio,
   `fly_ack=<vercelLimitAck>` e `maintenance_confirmed`; depois `inspect` de novo.
3. `apply` com o mesmo `generation`, `fly_ack=<applyAck>` e `maintenance_confirmed`: cria o app se faltar (`flyctl apps
   create`, sem máquina nem IP), cria o login, grava os três segredos em stage e marca READY. Repetir com READY não escreve.
4. `inspect` de novo e `deploy` com `generation` vazio, `fly_ack=<deployAck>` e `maintenance_confirmed`: `flyctl deploy
   --config fly.worker.toml --remote-only --ha=false`; depois exige uma máquina em `gru` com estado `started` em 6
   leituras seguidas (10 s) e nenhum evento `exit` desde o início do deploy (a guarda de role encerra o processo).
5. Conferir no log da Fly a inicialização sem erro da guarda de role, com as passadas desligadas.
6. Ligar a entrega do outbox: PR trocando `CP_WORKER_OUTBOX_ENABLED` para `"true"` em `fly.worker.toml`, merge, `inspect`
   e `deploy`. Observar a fila (pendentes, falhas, arrendamentos vencidos). A fila de espera
   (`CP_WORKER_WAITLIST_ENABLED`) em PR separado, como em WORKER_IMPLANTACAO.md.

O deploy não recebe senha de banco; o `vercel-limit` não recebe o token da Fly.

## O que o responsável precisa fazer

- OK de custo: o `deploy` cria uma máquina sempre ligada (`shared-cpu-1x`, 1 GB) cobrada pela Fly na organização
  `personal`. Criar o app no `apply` não cria máquina.
- Decidir se aprova o `vercel-limit` (escrita no banco de homologação).
- Aprovar os dispatches na ordem acima.

## Rollback

- Parar o worker: parar ou destruir a máquina do app `condpulse-v2-0-worker` no painel; nada muda na API.
- Desligar uma passada: PR voltando a flag para `"false"` e `deploy`.
- Retirar o login do worker: deixá-lo DISABLED (NOLOGIN) por SQL operacional revisado; não há DROP automático.
- Teto da Vercel: `runtime-connection-budget.sql` (4→16), revisado, conferindo a margem.
