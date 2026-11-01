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
  - Dono de função (ADR-0012, V053): os três executores de login (`FlyRuntimeLogin`, `FlyWorkerLogin`, `RuntimeConfiguration`)
    recusavam qualquer função de `platform`/`cp` com dono diferente de `postgres` ou `SECURITY DEFINER`. Passam a aceitar,
    só para funções, dono `cp_demo` (com `SECURITY DEFINER`) quando a role é NOLOGIN, NOSUPERUSER, NOBYPASSRLS,
    NOCREATEROLE, NOCREATEDB, NOREPLICATION, NOINHERIT, sem membros e sem ser membro de ninguém; tabelas e schemas
    continuam exigindo `postgres`. Fora disso, `*_FUNCTION_OWNER_DIVERGED` como antes.
  - O login da Vercel continua existindo. Só ele (marker da Vercel) é tolerado; outra geração da Fly ou role
    `cp_runtime_*` desconhecida bloqueia.
  - **CONNECTION LIMIT 4**, não 16. Pela margem da ADR-0006 (max_connections 60, folga mínima 20), com o login da
    Vercel em 16: o inspect 41106739389 recusou o teto 8 (`FLY_RUNTIME_BUDGET_INSUFFICIENT`); leitura de 30/10 mostrou
    18 backends não runtime, logo 18 + 16 + 8 + 20 = 62 não cabe e 18 + 16 + 4 + 20 = 58 cabe. A Fly usa uma máquina
    com Hikari 2; 4 comporta a sobreposição de um deploy (2 + 2). O
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
  credenciais de acesso, acesso facial, recuperação, tempo real, Log de Segurança, parceiros), `CP_FILES_ENABLED` (só
  `false`), `CP_BFF_SESSION_HOURS`, `CP_REALTIME_*`, `CP_STORAGE_BUCKET`. Flag `true|false` exatos; variável
  `sensitive`/`secret` na Vercel nunca é lida e aborta com `FLY_VERCEL_FLAG_TYPE_INVALID` (cadastrar como Plain ou
  Encrypted).
- Com o login já READY, o `apply` regrava em stage **todas** as copiadas presentes na Vercel, não só as ausentes: a
  Fly devolve só nomes, então trocar `false` → `true` na Vercel só chega à Fly assim. Repetir o `apply` sempre regrava
  essas flags (mesmo valor, sem reinício); valem no próximo `deploy`. Flag removida da Vercel e ainda presente na Fly
  continua `FLY_STALE_COPIED_SECRET`.

### Anexos (chave do Storage)

- Opcional: secret do GitHub `CP_STORAGE_SERVICE_KEY` no Environment `condpulse-database-operations`, formato
  `sb_secret_` + 24 ou mais caracteres `[A-Za-z0-9_-]` (fora disso: `FLY_STORAGE_KEY_INVALID`, sem eco do valor).
- Só o `apply` recebe a chave (nunca `inspect` nem `deploy`). Presente: grava `CP_STORAGE_SERVICE_KEY` e
  `CP_FILES_ENABLED=true`. Ausente: nada muda (`CP_FILES_ENABLED` segue ausente ou `false` vindo da Vercel).
- Com o login já READY, o `apply` grava esses dois segredos em stage (junto com as flags copiadas da Vercel), sem
  tocar no banco, e confere os nomes de volta (exatamente os anteriores mais os gravados). Valem no próximo `deploy`.
- Mesmo `generation` do login existente, com o SHA novo: o executor aceita o login READY marcado por um commit
  anterior da mesma geração, só para leitura (nenhuma escrita no banco). STAGED/DISABLED de outro commit ou marker de
  outra geração continuam `FLY_RUNTIME_ROLE_OWNERSHIP_CONFLICT`.
- Depois de gravada, um `apply` sem a chave é recusado (`FLY_STORAGE_KEY_REQUIRED`): o import não remove segredo.
  Desligar anexos exige `flyctl secrets unset` revisado; não há caminho automático.

### Financeiro condominial (chave do Asaas)

- Opcional: secret do GitHub `CP_ASAAS_API_KEY` no Environment `condpulse-database-operations`, somente sandbox
  (`$aact_hmlg_` + 20 ou mais caracteres `[A-Za-z0-9_:$.=+/-]`; fora disso `FLY_ASAAS_KEY_INVALID`, sem eco). Só o `apply`
  recebe (nunca `inspect` nem `deploy`).
- Presente: grava `CP_ASAAS_API_KEY` e `CP_FINANCE_ENABLED=true`. Se a Fly ainda não tem `CP_ASAAS_WEBHOOK_TOKEN`, gera o
  token no runner, cria/atualiza o webhook no Asaas sandbox com ele (URL fixa da homologação; exige a variável
  `CP_ASAAS_WEBHOOK_EMAIL`) e só então grava o token na Fly. Nenhum valor é impresso. Detalhes em
  [FINANCEIRO_CONDOMINIAL.md](FINANCEIRO_CONDOMINIAL.md).
- Mesmo comportamento do Storage: com o login READY grava só esses segredos (sem tocar no banco) e confere os nomes;
  depois de gravada, `apply` sem a chave é recusado (`FLY_ASAAS_KEY_REQUIRED`).

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

O deploy não recebe senha de banco, token da Vercel, chave publicável nem chave do Storage.

## O que o responsável precisa fazer

- Cadastrar cartão de cobrança na organização `personal` (exibida como "Personal" no painel) (máquina sempre ligada é paga). Sem isso a
  Fly recusa criar a máquina.
- Aprovar, nesta ordem, os dispatches `inspect`, `apply` e `deploy`.

## Rollback

Não mexer na Vercel. Enquanto o tráfego não for trocado, a Vercel segue atendendo. Para desligar a Fly: parar ou
destruir a máquina no painel. Para retirar o login de banco da Fly: deixá-lo DISABLED (NOLOGIN) por SQL operacional
revisado; não há DROP automático.

## Troca de tráfego

Feita em 30/10/2026 depois do deploy 41109257267 (readiness 200/UP). Rollback: reverter o PR da troca; a Vercel volta a usar o serviço `api` próprio.

Passos originais:

1. Smoke na URL da Fly: readiness UP, `/v1/me` 401 sem sessão.
2. PR que troca em `vercel.json` os rewrites de `/auth/:path*`, `/v1/:path*` e `/actuator/health/readiness` para
   `https://condpulse-v2-0.fly.dev/...` (mesma origem para o navegador, então `CP_BFF_ORIGIN` continua o domínio da
   Vercel), com teste em `tests/vercel-config.test.mjs` e CSP inalterada.
3. Depois do merge: smoke público, login real e registro no STATUS. Rollback dessa etapa: reverter o PR.
