# Homologacao integrada da sessao residencial

Esta etapa prepara o ambiente de ensaio. Nao publica a aplicacao nem aplica baseline,
V003, contas, vinculos ou fixtures no Supabase. A stack base continua com BFF e reservas
desligados. A homologacao com Supabase Auth real permanece um gate separado.

## Stack de homologacao

`infra/compose.homologation.yaml` e um complemento explicito a `compose.app.yaml`.
Ele injeta a chave publicavel e a chave AES256 somente na API e habilita BFF/reservas
para o ensaio. O arquivo nao se chama compose.override.yaml e nao e carregado pelo
comando da stack base. Nao adicionar este complemento ao recurso publico.

Usar projeto e redes com prefixo `condpulse-hml-`, projeto Supabase/credenciais
exclusivos do ambiente, dominio HTTPS e ingresso restrito aos operadores de teste.
O manifesto nao configura o controle de ingresso: VPN ou restricao equivalente
precisa existir no proxy/host antes da ativacao. Um nome de projeto nao comprova
isolamento do banco, do dominio ou do trafego. Conferir essas separacoes por ambiente.

O inventario completo esta em `infra/homologation.env.example`, com marcadores
inertes. O cofre deve gerar a chave de cifra aleatoria de 32 bytes em Base64 e fornecer
a chave `sb_publishable_` do projeto correto. Nao usar chave secreta/service_role,
copiar chaves da Advanced, imprimir valores ou compartilhar o Compose expandido.
O preflight confere formato e rejeita chaves degeneradas evidentes; nao atesta
entropia, autenticidade da chave publicavel ou posse do dominio.

Antes de ligar os processos, conferir:

1. Baseline oficial 002 atestado no projeto existente, depois V003 pelo migrador
   separado. Um projeto novo recebe V001/V002/V003 pelo migrate normal.
2. Identidade runtime membro somente de cp_api e sem DDL/SUPERUSER/BYPASSRLS;
   identidade DDL nunca entra na API. CA validada e JDBC com verify-full.
3. Conta de teste aprovada em platform.accounts, com UUID do Auth, status ACTIVE,
   vinculo e unidade explicitos. Criar usuario no Auth nao concede esses acessos.
   As fixtures do CI nao sao um procedimento de onboarding remoto.
4. Imagens aprovadas por digest, ambiente exclusivo, cofre, backup e ingresso restrito.

Renderizar com Compose5.2.0, sem persistir ou imprimir a configuracao expandida:

```sh
set -o pipefail
docker compose --project-name condpulse-hml-residential --env-file /run/condpulse/homologation.env -f infra/compose.app.yaml -f infra/compose.homologation.yaml config --format json | node infra/validate-release.mjs --homologation
```

O modo --app continua rejeitando flags ativas e variaveis de cifra/publicavel. O modo
--homologation exige as duas flags, ambas as chaves, sessao de oito horas e redes
proprias, preservando os limites da stack base. Nao verifica o banco por rede,
migrations aplicadas, permissoes reais ou a restricao de ingresso.

Apos esses gates e a revisao da configuracao efetiva do proxy, o operador pode iniciar
somente o ambiente restrito com os mesmos dois arquivos. A stack publica exige sua
propria revisao de liberacao e os gates de DEPLOY_COOLIFY.md. Este documento nao declara
nenhum desses ambientes existente ou publicado.

## Ensaio automatizado no CI

Comando: `node tests/integration/https-bff-smoke.mjs`, no job foundation depois do
build do backend, migrations, criacao da role runtime e build da web.

O runner exige CP_DB_TEST_OK=DISPOSABLE, banco condpulse_ci em localhost e credenciais
sinteticas especificas. Gera certificado TLS SAN localhost/127.0.0.1 e truststore Java
temporarios, JWT RSA/JWKS e chave AES aleatorios; nao desliga validacao TLS.
API usa cp_ci_login/cp_api; somente preparacao e limpeza das fixtures usam postgres.

O percurso passa por uma origem HTTPS de teste que serve assets reais da web e
encaminha /auth e /v1 para o JAR Spring real. O Auth sandbox implementa o protocolo
de senha, JWKS, refresh de uso unico e logout. Nao ha mock dos controllers, servicos,
decoder JWT, cifra, pool JDBC ou consultas da API.

Verificacoes previstas:

- Negacao anonima e de login sem CSRF ou com Origin divergente antes de chamar Auth.
- Cookies __Host-/Secure/HttpOnly/SameSite=Lax, sessao opaca e tokens cifrados no banco.
- Login, /v1/me, unidades, areas, criacao/replay/cancelamento de reserva e contagens
  reais de reserva/auditoria/outbox.
- Refresh com token rotacionado e commit duravel, vinculo revogado negado,
  logout removendo sessao e replay do cookie antigo negado.
- Limpeza transacional das fixtures sinteticas e remocao de material TLS temporario.

Este e um ensaio HTTP integrado. Nao executa a interface no navegador, nao usa o
Nginx da imagem final, nao conecta Supabase e nao comprova TLS do PostgreSQL gerenciado.
As sete jornadas Playwright e os testes do proxy/imagens continuam em seus jobs;
eles cobrem outras fronteiras e nao substituem o ensaio com o provedor real.

Resultados executados, commit e limitacoes devem constar em STATUS.md e nos tickets.
Nao marcar CP-011, CP-020 ou lancamento completos com base neste runner.

## Limites remanescentes

Credenciais JDBC/CA/cofre e executor disponivel, baseline/V003 remotos, dominio HTTPS,
host dedicado, Auth/refresh/logout reais sob falha de rede, onboarding aprovado,
rate limiting de producao, SMTP, MFA, segundo ambiente e backup/restore continuam
dependencias da liberacao. A previa HTTP em 127.0.0.1:4173 serve para consultar a
interface; nao e uma homologacao do login seguro.

Referencias:

- https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/
- https://supabase.com/docs/guides/getting-started/api-keys
- docs/BASELINE_FLYWAY.md
- docs/DEPLOY_COOLIFY.md
