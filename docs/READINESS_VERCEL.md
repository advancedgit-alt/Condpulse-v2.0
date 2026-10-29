# Prova de readiness da API na Vercel

Este patch prepara somente o encaminhamento exato de
`/actuator/health/readiness` ao Service `api`, antes do fallback da SPA. Os demais
caminhos do Actuator continuam no fallback web. Não altera autenticação, backend,
variáveis, grants ou flags BFF/reservas. Não foi implantado nem comprova JDBC real
a partir da Vercel.

O backend já permite esse probe sem autenticação, com `show-details: never` e
grupo `readinessState,db`. Assim o status depende da prontidão da aplicação e da
checagem de sua conexão JDBC. Não expor `/actuator/:path*`, `env`, `metrics`,
componentes ou detalhes para obter essa prova.

## Ordem de integração e verificação

1. Concluir o `apply` runtime na revisão original e confirmar `READY` pelo executor
   e seus metadados, conforme [CONFIGURACAO_RUNTIME.md](CONFIGURACAO_RUNTIME.md).
   Manter o SHA da operação em curso: integrar este patch antes desse encerramento
   mudaria o SHA/ACK esperado pelo provisionamento. Não repetir `apply` em outro
   SHA para preparar o probe.
2. Após revisão e CI, integrar o rewrite e realizar um novo deployment Production
   do SHA integrado, usando a configuração runtime já aprovada. Registrar esse
   deployment e o SHA separadamente da operação runtime. Este patch não dispara
   redeploy nem autoriza habilitar BFF/reservas; ambas as flags permanecem `false`.
3. No alias HTTPS estável do projeto CondPulse, solicitar
   `GET /actuator/health/readiness` com `Accept: application/json`, sem credenciais
   de usuário. Exigir HTTP200, Content-Type JSON (`application/json` ou um tipo
   `+json`) e corpo com `status=UP`. Uma página HTML com200 é o fallback da SPA e
   não satisfaz o teste.503 com status `DOWN` ou `OUT_OF_SERVICE` exige investigar
   o runtime; não remover `db` do grupo nem relaxar TLS.
4. Conferir também `GET /v1/me` sem Bearer: deve continuar401 com o problema
   `AUTHENTICATION_REQUIRED`. Esse resultado demonstra o roteamento e a proteção
   da API, mas isoladamente não alcança o JDBC.
5. Registrar horário, deployment, SHA, geração runtime, caminhos, status HTTP,
   Content-Type e status de readiness, sem valores de variáveis ou credenciais.
   Conferir que a resposta mantém `Cache-Control: no-store` e não divulga detalhes.

## Limites da evidência

`READY` no painel Vercel e o JDBC confirmado pelo executor GitHub Actions não
comprovam, por si só, conectividade do container Vercel. O probe JSON `UP` no novo
deployment fornece essa evidência pontual para a conexão configurada naquele
runtime. Não atesta Auth/Supabase real, onboarding, login, reservas, RLS funcional
por tenant, escala ou recuperação. Esses aceites continuam separados.

Os vizinhos `/actuator/health`, `/actuator/health/liveness`, `/actuator/env` e
`/actuator/metrics` não foram encaminhados à API por este patch; seu eventual200
HTML continua sendo web. Não usá-los como alternativa ao probe exato.

Validação local: `node --test tests/vercel-config.test.mjs`. O teste de preflight
Java requer JDK e deve ser registrado como não executado quando indisponível.

## 503 inicial depois de início a frio (29/10/2026)

Os smokes públicos registraram `503`/`OUT_OF_SERVICE` na primeira consulta e
`200`/`UP` na repetição. `OUT_OF_SERVICE` é o estado `REFUSING_TRAFFIC` da
prontidão, não falha do `db` (que daria `DOWN`). Causa no código: a
`DatabaseRoleGuard` era um `ApplicationRunner`, que o Spring Boot executa depois
de o servidor web abrir a porta e antes de publicar `ACCEPTING_TRAFFIC`; durante a
conexão TLS ao banco e as duas consultas da guarda, a porta já respondia com a
prontidão recusada (e outra rota podia ser atendida antes da conferência da role).
A guarda passou a rodar em `afterSingletonsInstantiated`, durante a criação do
contexto e antes de qualquer componente com ciclo de vida, entre eles o servidor
web. `DatabaseRoleGuardStartupTest` comprova a ordem. Continua possível um 503 se a
Vercel encaminhar a requisição no intervalo curto entre abrir a porta e publicar a
prontidão; o smoke mantém a repetição. A confirmação no ambiente vem do próximo
smoke público depois do deployment.
