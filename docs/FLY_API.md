# API de homologação na Fly.io

Situação: preparado neste PR, **nada executado**. Nenhum login de banco, segredo da Fly ou máquina foi criado.

## O que muda

A API de homologação (`rpnrqfrfwywglvyykgrg`) passa a poder rodar como contêiner sempre ligado no app Fly
`condpulse-v2-0` (organização `personal` (exibida como "Personal" no painel), região `gru`), em vez do Container Runtime da Vercel. Motivo:
16-17 s de início a frio medidos na Vercel (STATUS, 30/10/2026). A imagem é a mesma (`Dockerfile.vercel`, bases
fixadas por digest, perfil `api`, sem migração, preflight antes do Spring).

- `fly.toml`: `primary_region = "gru"`, `auto_stop_machines = "off"`, `min_machines_running = 1`, checagem HTTP em
  `/actuator/health/readiness`, VM `shared-cpu-1x` com 1 GB. Sem segredos no arquivo.
- `.github/workflows/fly-api.yml`: manual, só na branch principal, gate sem segredos antes do Environment
  `condpulse-database-operations`, operações `inspect | apply | deploy`.
- `infra/scripts/fly-api/fly-api.mjs` (orquestração) e `FlyRuntimeLogin.java` (login de banco).
- flyctl 0.4.110, baixado do release oficial e conferido por SHA-256 no runner.

**O tráfego não muda neste PR.** `vercel.json` continua mandando `/auth`, `/v1` e a readiness para o serviço `api` da
Vercel. Se a Fly falhar, nada precisa ser desfeito.

## Login de banco exclusivo da Fly

- Nome `cp_runtime_<geração sem hífens>`, marker `condpulse-runtime-fly:<projeto>:<geração>:<SHA>:<estado>`.
- Mesma política do login da Vercel (`RuntimeConfiguration.java`): `NOLOGIN INHERIT NOSUPERUSER NOCREATEDB
  NOCREATEROLE NOREPLICATION NOBYPASSRLS`, associação única a `cp_api` com ADMIN=false/INHERIT=true/SET=false, nenhum
  grant direto, senha SCRAM de 48 caracteres com validade de 10 minutos, prova de login com TLS e `row_security=on`,
  READY (validade infinita) só depois de a Fly confirmar os nomes dos segredos. Falha depois do STAGED deixa o login
  DISABLED (NOLOGIN, senha nula).
- Diferenças, com motivo:
  - `RuntimeConfiguration` confere histórico só até a V005 e grants por uma lista fixa da V005; o banco já está na
    V033. O executor da Fly exige BASELINE 002 + SQL contíguas, nada pendente no JAR revisado, RLS/FORCE e dono
    `postgres` em todas as tabelas, e compara o privilégio efetivo do login com o de `cp_api` em todas as tabelas,
    colunas, funções e schemas da aplicação (além de proibir TRUNCATE/REFERENCES/TRIGGER/MAINTAIN e repasse de grant em
    `cp_api`).
  - O login da Vercel continua existindo. Só ele (marker da Vercel) é tolerado; outra geração da Fly ou role
    `cp_runtime_*` desconhecida bloqueia.
  - **CONNECTION LIMIT 8**, não 16. Pela margem da ADR-0006 (max_connections 60, folga mínima 20), com o login da
    Vercel em 16: as amostras registradas tinham 12-13 backends não runtime; 13 + 16 + 16 + 20 = 65 não cabe, e
    13 + 16 + 8 + 20 = 57 cabe. A Fly usa uma máquina com Hikari 2; 8 comporta a sobreposição de um deploy. O
    executor relê max_connections (precisa ser 60), backends não runtime e tetos das outras roles runtime antes de criar.
- Consequência: enquanto o login da Fly existir, o executor `runtime-configuration` (Vercel) recusa novas gerações
  (`RUNTIME_OTHER_GENERATION_EXISTS`). Esse executor também já não aceita o histórico atual (só até V005).

## Segredos gravados na Fly (apply)

Todos por `flyctl secrets import --stage` (valores só no stdin, nunca em argumento ou log). Depois, só os nomes são
lidos de volta e precisam bater exatamente.

- Banco: `DATABASE_USER`, `DATABASE_PASSWORD` (gerada no runner), `DATABASE_URL` (pooler São Paulo,
  `verify-full`), `CP_VERCEL_DB_CA_BASE64` (CA pública do Supabase validada com openssl no runner), `DB_POOL_SIZE=2`.
- Fixos: `PORT=8080`, `CP_JWT_ISSUER`, `CP_JWT_JWK_SET_URI`, `CP_BFF_ORIGIN=https://project-rhvdq.vercel.app`.
- `CP_SUPABASE_PUBLISHABLE_KEY`: do secret do GitHub.
- Gerados no runner, 32 bytes cada, diferentes entre si: `CP_BFF_ENCRYPTION_KEY`, `CP_BFF_RATE_LIMIT_KEY`,
  `CP_PARCEL_CODE_KEY`.
- Copiados da Vercel Production **só se existirem** e só valores não sensíveis em formato fechado: flags
  `CP_*_ENABLED` (BFF, reservas, gestão, plataforma, cobrança, encomendas, visitas, notificações, avisos, pedidos,
  credenciais de acesso, recuperação, tempo real), `CP_FILES_ENABLED` (só `false`), `CP_BFF_SESSION_HOURS`,
  `CP_REALTIME_*`, `CP_STORAGE_BUCKET`. Variável `sensitive` nunca é lida.

Efeito das chaves novas: quando o tráfego for trocado, as sessões web existentes deixam de abrir **uma vez** (cifra do
BFF diferente) e todos entram de novo; códigos de retirada delegada emitidos pela Vercel deixam de valer na Fly.

## Como rodar

Sempre na branch principal, com o SHA revisado. A geração é um UUID novo (por exemplo `uuidgen | tr A-Z a-z`).

1. `inspect` com `expected_commit` e `generation`: imprime em `::notice` o estado (nomes dos segredos da Fly, valores
   copiados da Vercel, estado do login) e dois ACKs: `applyAck` e `deployAck`.
2. `apply` com o mesmo `generation`, `fly_ack=<applyAck>` e `maintenance_confirmed`: cria o login, grava os segredos em
   stage e marca READY. Repetir com o login READY não escreve nada.
3. `inspect` de novo (o estado mudou) e `deploy` com `generation` vazio, `fly_ack=<deployAck>` e
   `maintenance_confirmed`: `flyctl deploy --remote-only --ha=false`, depois espera
   `https://condpulse-v2-0.fly.dev/actuator/health/readiness` responder 200/JSON/UP e confere uma máquina em `gru`.

O deploy não recebe senha de banco, token da Vercel nem chave publicável.

## O que o responsável precisa fazer

- Cadastrar cartão de cobrança na organização `personal` (exibida como "Personal" no painel) (máquina sempre ligada é paga). Sem isso a
  Fly recusa criar a máquina.
- Aprovar, nesta ordem, os dispatches `inspect`, `apply` e `deploy`.

## Rollback

Não mexer na Vercel. Enquanto o tráfego não for trocado, a Vercel segue atendendo. Para desligar a Fly: parar ou
destruir a máquina no painel. Para retirar o login de banco da Fly: deixá-lo DISABLED (NOLOGIN) por SQL operacional
revisado; não há DROP automático.

## Troca de tráfego (PR separado, depois de verificar a Fly)

1. Smoke na URL da Fly: readiness UP, `/v1/me` 401 sem sessão.
2. PR que troca em `vercel.json` os rewrites de `/auth/:path*`, `/v1/:path*` e `/actuator/health/readiness` para
   `https://condpulse-v2-0.fly.dev/...` (mesma origem para o navegador, então `CP_BFF_ORIGIN` continua o domínio da
   Vercel), com teste em `tests/vercel-config.test.mjs` e CSP inalterada.
3. Depois do merge: smoke público, login real e registro no STATUS. Rollback dessa etapa: reverter o PR.
