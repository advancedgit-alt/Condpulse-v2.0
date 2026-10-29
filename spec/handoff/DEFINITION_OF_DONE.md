# Definition of Done

Um ticket só está concluído se:

- Implementação, UI quando aplicável e contrato tratam sucesso, pendência, conflito, negação e falha.
- Testes unitários e de integração relevantes executados; SQL/tenant/concorrência testados com Postgres real.
- Permissões revisadas em cada rota, download, stream e tarefa assíncrona.
- Migração versionada, compatível com rollback da aplicação e executada em ambiente descartável.
- Nenhum segredo ou dado pessoal desnecessário em código/log/evidência.
- Acessibilidade e usabilidade das jornadas novas revisadas em telas reais.
- Métricas, alertas e procedimento de continuidade incluídos para novos pontos de falha.
- Revisão independente e evidências vinculadas ao commit aprovado.
- Documentação, contrato e backlog atualizados sem afirmar resultado não executado.

Para release de piloto, acrescentar restore de banco+objetos, treino por turno, dados reconciliados e responsável por incidente. Para facial, acrescentar homologação por equipamento/firmware/modo/instalação e alternativa operacional. Aprovação de loja e contrato de parceiro são dependências externas, não marcáveis como concluídas por IA.
