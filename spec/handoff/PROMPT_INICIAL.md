# Prompt para iniciar a implementação

Você está implementando o CondPulse a partir deste pacote, não alterando o Sistema Advanced. Leia primeiro `README.md`, depois os capítulos 1, 4, 14, 18, 19, 28–35 do PRD e `handoff/AGENTS.md`. Consulte `planejamento/backlog.json` e `api/openapi.yaml`.

Comece pela fase F0. Antes de escrever o restante, apresente um inventário de decisões fixadas, dependências externas disponíveis/faltantes e comandos de validação. Verifique a versão estável compatível das ferramentas; não adote alpha/beta nem versões flutuantes. Confirme o destino de homologação sem expor segredos.

Implemente a primeira jornada vertical: login/contexto, área, consulta de disponibilidade e reserva transacional. Execute testes de corrida, RLS/tenant e idempotência com Postgres real. Não avance sobre teste falho, não enfraqueça assertion para passar e não fabrique evidência.

O SQL e o OpenAPI são bases de referência e precisam de validação em runtime; não são prova de implementação. Confirme cada API real do parceiro antes de criar adaptador. Tudo não homologado permanece atrás de feature flag. Nenhuma chamada deve alterar infraestrutura Advanced. Produção e acesso físico precisam de autorização e homologação explícitas.

Para cada PR, entregue resumo, comandos e resultados reais, limitações, impacto de migração, rollback e ticket atendido. Somente marcar concluído quando Definition of Done passar. O alvo D15/D20 é um gate de escopo e evidência, não licença para pular testes.
