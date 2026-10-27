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
- 25 verificações em PostgreSQL17.6 descartável: passaram. O CI foi atualizado para17.11, a versão-base do projeto real; resultado desse head ainda a conferir.
- Inclui 50 reservas concorrentes com uma vencedora, 20 retiradas com um handoff,
  primeira decisão, FK/RLS cruzados, contexto após commit, auditoria,
  idempotência por ator, rollback de outbox e vínculo revogado.
- API iniciou com role runtime limitada; readiness UP e /v1/me sem token retornou401.

Esses resultados não são teste real de Supabase Auth, acesso de equipamento,
carga100rps, E2E web/mobile ou aceite dos60 cenários de produto.
O teste de contexto usa mesma conexão SQL; ensaio de pool JDBC real com tenants alternados continua pendente.
As confirmações de sucesso são restritas à fatia exercitada no CI.

## Infraestrutura e pendências
- Projeto Condpulse-v2.0 encontrado ACTIVE_HEALTHY em sa-east-1: rpnrqfrfwywglvyykgrg, PostgreSQL17.11.0.003.
- Sua criação não foi atribuída a esta execução. A tentativa anterior de criação foi interrompida.
- Nenhuma tabela de negócio ou bucket encontrado na inspeção inicial.
- Hardening da função administrativa rls_auto_enable aplicado via migração nativa20261027210735: EXECUTE de anon/authenticated removido, corpo/event trigger preservados; read-backfalse/false e security advisorlints=[].
- Migrations de negócio Flyway não aplicadas no projeto remoto: falta executor com conexão autorizada. Não forjar histórico nem criar um segundo dono do schema.
- Storage/Auth pelo painel/API pendentes. Browser e shell desta sessão falharam por configuração de caminhos de permissões do executor; nenhuma credencial foi solicitada em chat.
- Organização conectada Sindico Advanced; região pretendida sa-east-1.
- Custo consultado US$10/mês/projeto; homologação+produção US$20/mês base incremental,
  sujeitos a consumo/adicionais. PITR e outros opcionais não habilitados.
- Nenhum recurso ou dado Advanced alterado.
- Woodpecker, host Coolify próprio, domínio, cofre, SMTP, PITR e restore não configurados.
- Web/mobile, BFF/CSRF, MFA de operações, jornadas P0/P1, importação e integração física pendentes.
- Nenhum deploy ou backup/restore de produção realizado.
