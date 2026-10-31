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
