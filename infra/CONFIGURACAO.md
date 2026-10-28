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
Worker: perfil worker sem porta publica. O consumidor outbox ainda nao foi implementado:
nao subir processo vazio como se notificacoes estivessem operacionais.

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
