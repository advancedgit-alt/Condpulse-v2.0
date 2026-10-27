# Estado verificável
Data de referência: 27/10/2026.

## Fundação implementada
- Branch feat/condpulse-foundation-20261027; PR #1.
- Arquitetura, alternativas, custos, dados, operação e sequência de implantação registrados.
- 68 tickets e 60 casos de aceite preservados, sem aceite fictício de produto.
- PostgreSQL único principal; projetos Supabase CondPulse exclusivos em São Paulo.
- Schema Flyway: contas, tenants, blocos, unidades, vínculos, áreas, reservas,
  encomendas/handoffs, chegadas, auditoria, outbox e idempotência.
- FKs compostas, FORCE RLS, runtime sem privilégios elevados e contextos locais.
- API inicial GET /v1/me com JWT, conta ativa e consulta de vínculos.
- BFF, mutações e interfaces não são disponibilizados por este scaffold.

## Evidência executada
Commit de código validado: 8aed52a327c03912333907b3f2efddef45a24185.
CI: https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/40583266112
Resultado do job foundation: SUCCESS, conferido pelos passos e logs.
- 4 verificações Node de rastreabilidade/contrato/limite de domínio/tokens: passaram.
- 9 testes Java de claims/issuer/expiração e intervalo/limpeza: passaram.
- Maven verify: BUILD SUCCESS.
- Flyway aplicou V001/V002 e validou segunda execução sem reaplicar schema.
- 25 verificações em PostgreSQL17.6 descartável: passaram.
- Inclui 50 reservas concorrentes com uma vencedora, 20 retiradas com um handoff,
  primeira decisão, FK/RLS cruzados, contexto após commit, auditoria,
  idempotência por ator, rollback de outbox e vínculo revogado.
- API iniciou com role runtime limitada; readiness UP e /v1/me sem token retornou401.

Esses resultados não são teste real de Supabase Auth, acesso de equipamento,
carga100rps, E2E web/mobile ou aceite dos60 cenários de produto.
O teste de contexto usa mesma conexão SQL; ensaio de pool JDBC real com tenants alternados continua pendente.
As confirmações de sucesso são restritas à fatia exercitada no CI.

## Infraestrutura e pendências
- Nenhum projeto Supabase CondPulse criado: chamada de criação interrompida.
- Organização conectada Sindico Advanced; região pretendida sa-east-1.
- Custo consultado US$10/mês/projeto; homologação+produção US$20/mês base incremental,
  sujeitos a consumo/adicionais. PITR e outros opcionais não habilitados.
- Nenhum recurso ou dado Advanced alterado.
- Woodpecker, host Coolify próprio, domínio, cofre, SMTP, PITR e restore não configurados.
- Web/mobile, BFF/CSRF, MFA de operações, jornadas P0/P1, importação e integração física pendentes.
- Nenhum deploy ou backup/restore de produção realizado.
