# Homologacao CondPulse na Vercel

Estado em 01/11/2026: web permanece na Vercel; /auth, /v1 e readiness encaminham à Fly conforme `vercel.json`. O serviço Container Runtime da Vercel permanece declarado como alternativa, mas não recebe essas rotas. SMTP e worker já configurados; recibos em [ESTADO_ATUAL.md](ESTADO_ATUAL.md). O registro técnico iniciado em 28/10 abaixo é histórico. Decisao: ADR-0005.
Projeto: https://vercel.com/sindicoadvanced/condpulse

## Configuracao revisavel

O root do projeto e a raiz do repositorio, vazio no painel. Framework global Other,
Node24, comandos globais sem override. `vercel.json` define os dois Services: web
Vite em apps/web e API Java pelo Dockerfile.vercel. Nao importar apenas apps/web
para tentar publicar o backend. Regiao de execucao configurada: gru1.

As rotas /auth e /v1 e o caminho exato /actuator/health/readiness preservam o caminho
e chegam à API da Fly antes do fallback da SPA.
Nao criar URL publica distinta da API para contornar cookies ou CSRF. O worker,
Flyway e cadastro operacional nao sao servicos HTTP desta configuracao.

Services e Container Runtime sao beta. A imagem roda como10001, perfil api, sem
migracao automatica. O preflight recusa outro banco/projeto/porta, credencial
privilegiada e divergencia de issuer/JWKS, antes de iniciar Spring. A CA publica e
preparada em /tmp; somente JDBC verify-full e permitido.

## Histórico observado em 28/10

Registro atual de 28/10: a identidade runtime e a configuração JDBC/CA foram
aplicadas em Production pelo executor revisado no source `2f1abf3547736c247fc082b2cc6ed76c18f2d38b`,
geração `2ca929e1-1f7e-4a3e-b9ab-1b9ab147ff82`. Inspect40751736891 passou;
apply40757578769 concluiu com `applied=true/state=READY` e cleanup bem-sucedido,
após autorização explícita. Metadados confirmaram o login exclusivo, somente
`cp_api`, sem privilégios elevados, e as 16 tabelas com ENABLE/FORCE RLS.
O responsável ativou SSL enforcement antes da inspeção aprovada; o guard TLS
não foi removido ou reduzido.

O limite inicial4 foi ajustado separadamente para16 pelo SQL operacional da
ADR-0006, source `c486d5aa921b6d61df610601d127c3400bde6745`, blob
`f736df28170c6988d0eebceb1dc1b4cf904a0247`, com CI40774877934 aprovado antes
da execução. O SQL modifica somente CONNECTION LIMIT, preservando senha, marker, grants,
TLS e RLS. O readback de 28/10 às 18:52:59 (America/Sao_Paulo) confirmou o limite 16,
flags seguras, o marker READY original, associação única e 16 tabelas FORCE RLS;
nenhum valor de senha foi consultado. Hikari permanece 2; a margem mínima 20 sob max_connections=60 é uma guarda de
fotografia, sem reserva de slots ou promessa de capacidade.

O executor conferiu dez Configs públicas e uma senha Secret somente no projeto
Vercel CondPulse, em Production. BFF/reservas permanecem `false`; não houve
cadastro real ou habilitação de Auth. A evidência sanitizada e os limites ficam
em [runtime-configuration-20261028.json](evidence/runtime-configuration-20261028.json).

Depois de concluir essa operação, a PR#14 integrou o rewrite exato de readiness
em `fe0929dcc4a7e64175a0697728d53ec4c0e1fbe7`; CI40752349238 passou nos
três jobs no head `6a8fa624a358c2704189c3af1b4820fdbb372812`.
O deployment `dpl_CCKP5SJgdskJuQB3qBEiRBGVBHAZ` foi observado
Ready/Production nesse source, com 45 s de build e alias
https://project-rhvdq.vercel.app. O SHA do provisionamento é preservado
separadamente do SHA do deployment.

A [prova pública40777572663](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/40777572663)
passou no source `5060fec7d4bcff0ae8df49801f1fecd55b7c0925`, associado pelo status
Vercel ao deployment
https://vercel.com/sindicoadvanced/condpulse/SGBWbeE1Jb5citmnMRoKnkFQjdvw.
O CI40776880447 validou o head da PR#17
`5550618f509eefd6478df2b8ecca6eac2eaabff8`; o SHA integrado é o source do smoke.
Às19:09:04 de 28/10 (America/Sao_Paulo), o snapshot inicial preservou readiness 503
com JSON/OUT_OF_SERVICE e `/v1/me`401 com AUTHENTICATION_REQUIRED. Uma única
consulta extra de readiness devolveu 200/JSON/UP; no-store foi mantido em todos
os probes. Isso comprova uma conexão JDBC pontual após inicialização, sem
comprovar recuperação da mesma instância, saúde de toda a frota, desempenho,
login real ou aceite do produto. O workflow mantém `db` e `readinessState`,
oculta detalhes e admite apenas a tolerância limitada de OUT_OF_SERVICE;
DOWN, timeout e erros de transporte, formato, privacidade ou headers falham.
A associação entre status Vercel, head e alias é operacional e não uma prova
imutável do SHA servido se ocorrer um rollback externo.

### Registros anteriores de28/10

Após as migrations, no registro anterior de28/10, o banco remoto ficou com baseline oficial002 e V003-V005 aplicadas e
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

As etapas 1–5 têm registro operacional acima. A etapa 6 tem prova pontual de JDBC após uma leitura extra de startup, com o503 inicial preservado. As etapas 7–9
continuam pendentes de configuração, dados aprovados e homologação real.

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
7. Concluir o cadastro real revisado pelo CLI operacional no alvo de homologação,
   com dados e principal aprovados. Configurar chaves AES e HMAC aleatórias,
   Base64 canônico de32bytes e distintas, e a origem HTTPS exata desse alvo.
   Habilitar BFF/reservas de forma controlada nesse alvo para o ensaio; a liberação
   aos usuários depende dos gates e do isolamento entre homologação e produção.
8. Ensaiar Supabase Auth real e login, cookies Secure/HttpOnly/SameSite,
   Origin/CSRF, contexto, reserva/replay/cancelamento, refresh/logout e vínculo
   revogado. Testar429 com Retry-After e503 preservando estado quando aplicável.
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
