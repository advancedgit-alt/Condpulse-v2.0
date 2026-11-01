# Configuracao por ambiente
Nenhum segredo e provisionado por estes arquivos.

API:
- SPRING_PROFILES_ACTIVE=api
- DATABASE_URL: JDBC do proprio ambiente; TLS verify-full/CA validada em recursos remotos.
- DATABASE_USER/PASSWORD: login runtime associado apenas ao grupo cp_api.
- CP_JWT_ISSUER: https://PROJECT_REF.supabase.co/auth/v1
- CP_JWT_JWK_SET_URI: https://PROJECT_REF.supabase.co/auth/v1/.well-known/jwks.json
- Auth deve usar chave assimetrica ES256/RS256; nao ha suporte ao segredo HS256 legado.
- DB_POOL_SIZE: hipotese8, ajustar ao limite contratado.
- PORT: 8080
- Expor somente rotas API/health autorizadas, com TLS e proxy correto.

Migrador: perfil migrate, URL/usuario/senha de DDL separados, job de release.
API nao recebe credencial migradora. O perfil migrate termina apos Flyway validar/aplicar.
Worker: perfil worker sem porta publica (SPRING_PROFILES_ACTIVE=worker), mesma imagem da API.
- DATABASE_USER/PASSWORD: login runtime associado apenas ao grupo cp_worker (nunca cp_api).
- DB_POOL_SIZE: 2 como hipotese inicial, somada ao orcamento de conexoes da role.
- CP_WORKER_OUTBOX_ENABLED=false por padrao: sem a flag o perfil nao processa nada.
- CP_WORKER_POLL_MILLIS: intervalo entre passadas (padrao 2000).
O consumidor gera somente a caixa interna (chegada aguardando e encomenda recebida);
push, e-mail e SMS nao existem. Nenhum worker continuo foi implantado: nao anunciar
notificacoes como operacionais. Caixa na API: CP_NOTIFICATIONS_ENABLED=false.
Ver docs/NOTIFICACOES.md.

Tempo real (CP-036): CP_REALTIME_ENABLED=false por padrao. Ajustes: CP_REALTIME_TICK_SECONDS (5),
CP_REALTIME_LIFETIME_SECONDS (300, abaixo do tempo maximo de requisicao do hospedeiro),
CP_REALTIME_MAX_STREAMS (40 por instancia) e CP_REALTIME_MAX_PER_ACCOUNT (3). Ver docs/TEMPO_REAL.md.

Log de Seguranca (V050, CPX-006 a CPX-011): CP_SECURITY_LOG_ENABLED=false por padrao (API: busca, integridade,
exportacao e SENSITIVE_VIEWED; worker: ancora diaria). A gravacao pelas fontes e do banco. Ver docs/LOG_SEGURANCA.md.
Parceiros (V049, ADR-0011): CP_PARTNERS_ENABLED=false por padrao (hierarquia de parceiro na API).

Flags na Fly (homologacao):
- API (condpulse-v2-0): recebe as flags da Vercel Production pelo executor fly-api (docs/FLY_API.md), so as chaves de
  COPIED em infra/scripts/fly-api/fly-api.mjs, inclusive CP_SECURITY_LOG_ENABLED e CP_PARTNERS_ENABLED. Ligar na
  Vercel nao basta: e preciso rodar fly-api inspect -> apply -> deploy. Com o login READY, o apply regrava todas as
  flags copiadas (a Fly so devolve nomes; um valor trocado na Vercel so chega assim). Flag cadastrada como
  sensitive/secret na Vercel aborta com FLY_VERCEL_FLAG_TYPE_INVALID: cadastrar como Plain ou Encrypted.
- Fora do COPIED de proposito: CP_FILES_ENABLED=true e CP_FINANCE_ENABLED (so com a chave do secret do GitHub),
  chaves (CP_AGE_EVIDENCE_KEY, CP_PARCEL_CODE_KEY, CP_BFF_*_KEY) e CP_WORKER_* (so do worker).
  Ainda nao copiadas (fora desta fatia; ficam com o padrao da imagem na Fly): CP_TECHNICAL_GRANT_MAX_DAYS (30) e
  CP_AGE_EVIDENCE_KEY_VERSION (v1).
- Worker (condpulse-v2-0-worker): NAO copia nada da Vercel (FLY_WORKER.md: os unicos segredos sao login, senha e CA).
  As flags ficam em fly.worker.toml [env], revisadas por PR. CP_SECURITY_LOG_ENABLED="false" ali deixa a ancora
  diaria desligada mesmo com a flag ligada na Vercel/API. Ligar a ancora = PR trocando para "true" e depois
  fly-worker deploy (V050 ja concede a cp_worker o necessario). Copiar da Vercel exigiria token da Vercel no workflow
  do worker, mudanca de escopo deliberadamente evitada.

BFF: CP_BFF_ENABLED=false por padrao. Para habilitar depois da homologacao:
- CP_BFF_ORIGIN: origem HTTPS exata da web, sem caminho/barra final.
- CP_SUPABASE_PUBLISHABLE_KEY: chave publica do proprio projeto/ambiente.
- CP_BFF_ENCRYPTION_KEY:32bytes aleatorios Base64, mantidos apenas no cofre.
- CP_BFF_SESSION_HOURS:8 como limite absoluto inicial; ver docs/BFF_INTEGRACAO.md.
Reservas: CP_RESERVATIONS_ENABLED=false ate homologar regras e jornadas reais.
Web: imagem infra/Dockerfile.web, mesma origem e reverse proxy privado para api:8080;
ver docs/WEB_IMPLANTACAO.md. Mobile permanece pendente.
Coolify: host exclusivo CondPulse, regiao Sao Paulo, cofre, firewall, limites e checks.
DNS/host/imagens por digest dependem de provisionamento e aceite.

Pacote de release: compose.app.yaml e compose.migrate.yaml sao operacoes separadas;
app.env.example e migrate.env.example documentam somente placeholders.
Validar o JSON expandido por pipe com validate-release.mjs, sem salva-lo/imprimi-lo.
Os manifestos atuais mantem BFF/reservas desligados e exigem imagens por digest,
TLS verify-full com CA readonly e identidades runtime/DDL distintas.
Procedimento, limites e gates: docs/DEPLOY_COOLIFY.md. Nao aplicar compose.local.yaml
em homologacao/producao nem importar credenciais do migrador na aplicacao.

Bootstrap legado: compose.baseline.yaml/target baseline usam CLI separado, somente
no projeto da ADR-0003. Padrao inspect, sem perfil Spring; baseline oficial002 exige
atestado integro e ACK do fingerprint, e nunca executa migrate. Ver docs/BASELINE_FLYWAY.md.
