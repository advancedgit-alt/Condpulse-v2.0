# Homologacao CondPulse na Vercel

Atualizado: 28/10/2026, America/Sao_Paulo. Decisao: ADR-0005.
Projeto: https://vercel.com/sindicoadvanced/condpulse

## Configuracao revisavel

O root do projeto e a raiz do repositorio, vazio no painel. Framework global Other,
Node24, comandos globais sem override. `vercel.json` define os dois Services: web
Vite em apps/web e API Java pelo Dockerfile.vercel. Nao importar apenas apps/web
para tentar publicar o backend. Regiao de execucao configurada: gru1.

As rotas /auth e /v1 preservam o caminho e chegam a API antes do fallback da SPA.
Nao criar URL publica distinta da API para contornar cookies ou CSRF. O worker,
Flyway e cadastro operacional nao sao servicos HTTP desta configuracao.

Services e Container Runtime sao beta. A imagem roda como10001, perfil api, sem
migracao automatica. O preflight recusa outro banco/projeto/porta, credencial
privilegiada e divergencia de issuer/JWKS, antes de iniciar Spring. A CA publica e
preparada em /tmp; somente JDBC verify-full e permitido.

## Estado observado

Atualizacao de28/10: banco remoto com baseline oficial002 e V003-V005 aplicadas e
validadas no commit ace821b, runs40730350359/40730656930. As16 tabelas de aplicacao
mantem ENABLE/FORCE RLS. Login runtime e transporte seguro de credenciais para a
Vercel ainda pendentes; BFF/reservas permanecem false. O sucesso das migrations
nao comprova conectividade do container Vercel, onboarding ou Auth real.

Registro anterior de28/10: deployment daquela conferencia dpl_45ufsie9cUqg4GHb3UZyt4418eWS,
commit401605522fa2f2a470bb480a406c598f7f508042, Ready. CI40620673228 passou
nos tres jobs no merge. API ainda retorna500: o launcher aborta com
VERCEL_API_RUNTIME_IDENTITY_REQUIRED antes de Spring. Nao ha usuario runtime,
credenciais JDBC ou CA configurados. O banco continua sem baseline Flyway/V003-V005.
Evidencia: evidence/vercel-readiness-20261028.json. Os dados abaixo preservam a
primeira observacao; nao equivalem a aceite funcional.

Primeiro deployment Ready: dpl_7NBrikhozHriUpTWymxr7KmwgJmA, commit9e5b5fb638c261b6d6f6f26c936f85a6e48d1160.
Logs confirmam web e container API; imagem publicada:
`vcr.vercel.com/sindicoadvanced/condpulse/api@sha256:f4148627f9f5c35ffcbb802a54dbf66af008c04d254ddb1e8ff8c36b22dda850`.

A interface publica carrega em https://project-rhvdq.vercel.app, mas o acesso nao
pode ser verificado. Isso e uma publicacao incompleta, nao aceite funcional.
Nao usar Ready ou uma resposta200 do fallback /actuator como prova de JDBC/Auth.

As sete configs iniciais foram salvas somente em Production: PORT8080, DB_POOL_SIZE2,
issuer/JWKS do Supabase dedicado, origem do alias acima e flags BFF/reservas false.
Mudancas de ambiente precisam de novo deployment. Nao ha credenciais runtime no
projeto. Previews nao recebem os segredos da homologacao.

## Ordem de liberacao

1. Aprovar revisao e CI no commit exato; registrar imagem e deployment resultantes.
2. Configurar o executor JDBC segregado e a CA validada. Seguir o runbook do
   baseline oficial002 e executar V003–V005 pelo Flyway, nunca via startup da API.
3. Provisionar identidade LOGIN propria cp_runtime_..., membro somente cp_api,
   sem DDL, SUPERUSER ou BYPASSRLS; confirmar os privilegios efetivos.
4. Cadastrar os segredos apenas no ambiente apropriado, conforme
   infra/vercel.env.example. Nunca usar VITE_ ou NEXT_PUBLIC_, nem adicionar
   senha DDL, service_role ou chave administrativa de onboarding ao projeto.
5. Definir DATABASE_URL direto ou pooler de sessao SP, porta5432/banco postgres,
   sslmode=verify-full e sslrootcert=/tmp/condpulse-db-ca.crt. Pool inicial maximo2;
   medir soma de conexoes em todas as instancias, nao apenas por processo.
6. Ensaiar primeiro a API com flags false e conferir banco/identidade/readiness
   pelo caminho operacional real. Rede IPv6 direta ou IPv4 do pooler precisa ser
   comprovada no runtime Vercel; nao reduzir TLS para resolver conectividade.
7. Concluir cadastro real revisado pelo CLI operacional e comprovar Auth real.
   Habilitar BFF e reservas somente depois disso. Chaves AES e HMAC aleatorias,
   Base64 canonico32bytes e distintas; origem HTTPS exata do alias estavel.
8. Executar login, cookies Secure/HttpOnly/SameSite, Origin/CSRF, consulta de
   contexto, reserva/replay/cancelamento, refresh/logout e vinculo revogado.
   Testar429 com Retry-After e503 preservando estado quando aplicavel.
9. Medir cold start, carga, escala/conexoes, SIGTERM e recuperacao antes de
   promover uso real. Homologacao/producao continuam exigindo isolamento proprio.

## Custos e operacao

Nao houve compra de dominio, VPS ou upgrade do plano nesta etapa. A conta Pro
existente cobra consumo conforme uso; nao ha promessa de operacao gratuita.
On-demand concurrent builds foi desativado somente no CondPulse. Nao aumentar
maquina, adicionar produto pago ou alterar limites de toda a equipe sem aceite.
O endereco vercel.app permite homologar sem comprar dominio.

API escala a zero. Um worker continuo e notificacoes nao ficam entregues por
existir uma imagem HTTP. A alternativa de execucao exige decisao e teste proprios.
