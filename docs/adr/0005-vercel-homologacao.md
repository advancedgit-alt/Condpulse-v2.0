# ADR-0005 - Homologacao na Vercel sem dominio proprio

Data: 27/10/2026. Estado: alternativa solicitada pelo responsavel, em preparacao
e validacao; nao constitui promocao de producao ou aceite de gasto adicional.

## Contexto

O responsavel confirmou que ainda nao existe servidor exclusivo CondPulse e pediu
avaliar a Vercel e o uso sem dominio comprado. A infraestrutura conectada do Coolify
possui somente o servidor utilizado pela Advanced. Nao reutilizar essa capacidade.
O PRD permite registrar alternativas; dados, identidade e operacao continuam proprios.

## Decisao desta etapa

Preparar web React e API Java/Spring em um projeto Vercel exclusivo, usando a URL
gerada vercel.app e HTTPS, com Supabase Sao Paulo como PostgreSQL/Auth/Storage.
Rotas /auth e /v1 continuam na mesma origem publica da web. Nao publicar credenciais
da API no bundle, dar DDL ao runtime ou executar Flyway ao iniciar a API.

Services e Container Runtime da Vercel estao em beta. A viabilidade de configuracao
na documentacao nao comprova que a API, JDBC/TLS ou disponibilidade foram homologados.
Validar build, cold start, encerramento SIGTERM, cookies, CSRF, rotacao de sessao,
RLS/tenant e limite agregado de conexoes antes de promover o ambiente.

O worker continuo e os jobs manuais de migracao/onboarding nao devem ser tratados
como funcoes HTTP comuns. Permanecem processos com ciclo de vida proprio. Uma futura
escolha entre Daemons, agendamento duravel ou host dedicado exige ensaio e decisao
especifica; esta etapa nao sobe um worker vazio nem afirma notificacoes operacionais.

## Provisionamento observado

Projeto criado pelo painel autenticado, sem build, Git conectado ou deployment:

- Nome: condpulse.
- ID: prj_IeUa4Zjg1IwaPqJQwv525H14I837.
- Time: sindicoadvanced (conta Pro existente).
- Painel: https://vercel.com/sindicoadvanced/condpulse

Organizacao/faturamento compartilhados nao autorizam importar variaveis, recursos ou
segredos da Advanced. A conexao automatica recusou criacao com403 e nao enxerga o novo
projeto; o painel do proprio responsavel permitiu a criacao. Nao foi feito upgrade,
adicionado dominio pago, contratado VPS ou iniciado build nesta operacao.

## Gates restantes

- Revisao e CI da configuracao Vercel e commit exato.
- Credenciais runtime proprias, CA PostgreSQL validada e origem HTTPS estavel.
- Baseline002 oficial e migrations posteriores pelo executor separado.
- Teste real com Supabase Auth, regras aprovadas, cadastro e vinculos operacionais.
- Isolamento entre homologacao/producao, limites de uso/custo e recuperacao de dados.

O pacote Coolify existente permanece uma alternativa revisada. Nao altera-lo nem
usar recursos Advanced para contornar os gates desta homologacao.

## Fontes primarias consultadas

- https://vercel.com/docs/services
- https://vercel.com/docs/functions/runtimes/container
- https://vercel.com/docs/deployments/generated-urls
- https://vercel.com/docs/functions/configuring-functions/region
