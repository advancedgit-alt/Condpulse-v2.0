# Outbox com worker (CP-034) e caixa interna da unidade (parte do CP-035)

Flags: `CP_WORKER_OUTBOX_ENABLED` (worker, padrão `false`) e `CP_NOTIFICATIONS_ENABLED` (API, padrão `false`), sem
alteração na Vercel. V015 não foi aplicada a nenhum banco remoto e nenhum worker contínuo foi implantado. Não há push,
e-mail nem SMS neste recorte.

## O que existe

- **Outbox durável**: cada evento de negócio continua gravado na mesma transação do registro. O worker (perfil
  `worker`, sem porta pública, login membro somente de `cp_worker`) percorre os condomínios ativos e, por condomínio,
  arrenda um lote com `FOR UPDATE SKIP LOCKED` e prazo de 60 s (`LEASED`, tentativa +1). Duas instâncias não pegam a
  mesma tarefa; arrendamento vencido volta a ser arrendado.
- **Efeito e confirmação juntos**: cada tarefa roda em transação própria — efeito e conclusão (`DELIVERED`/`SKIPPED`)
  na mesma transação, cercados por `state='LEASED'` e pela tentativa arrendada. Worker atrasado (outra instância já
  arrendou de novo) não confirma nada e o efeito dele volta junto (teste JDBC). Entrega ao menos uma vez: o efeito da
  caixa é idempotente pela chave tarefa/destinatário/canal, então reprocessar depois de uma queda entre efeito e
  confirmação não duplica o aviso (AC-049, teste JDBC).
- **Backoff e falha visível**: falha volta para `PENDING` com novo horário — 10 s, 30 s, 2 min, 10 min e 30 min, com
  variação de até 20% — e erro saneado (categoria e SQLSTATE, nunca a mensagem do banco ou dados). Depois da sexta
  tentativa, ou com arrendamento vencido na última tentativa, a tarefa fica `FAILED` com o erro. Problema que nova
  tentativa não resolve (evento sem unidade, registro inexistente) vai direto para `FAILED`.
- **Sem nova tentativa cega**: efeito marcado como não idempotente que falha vai direto para `FAILED`, com o aviso de
  conferir antes de reprocessar. Hoje só existe o efeito idempotente da caixa; o contrato vale para push/e-mail futuros.
- **Sem consumidor não é entregue**: evento sem efeito neste recorte (reservas, decisões, registros de acesso etc.),
  chegada já respondida ou vencida e encomenda já retirada ou anulada ficam `SKIPPED`, nunca `DELIVERED`.
- **Banco (V015)**: transições do outbox conferidas por gatilho (conteúdo imutável, prazo de arrendamento de até 10
  minutos, horários de conclusão pelo banco, concluída e falha são finais); a API só cria tarefa `PENDING`; o worker
  altera só as colunas de processamento. O worker lê apenas vínculos residenciais e situação de contas e unidades do
  condomínio em contexto, e de chegadas e encomendas somente unidade e situação (sem nome, descrição ou local).
- **Correlação**: logs com condomínio, tarefa, tipo e tentativa (identificadores técnicos), sem dados de pessoas.

## Caixa interna da unidade

| Evento | Aviso | Quem recebe |
| --- | --- | --- |
| `ARRIVAL_REQUESTED` (chegada ainda aguardando) | `ARRIVAL_WAITING` | Morador e responsável da unidade; dependente só com `visits.decide` |
| `PARCEL_RECEIVED` (encomenda ainda a retirar) | `PARCEL_RECEIVED` | Morador e responsável; dependente só com `parcels.view` |

- Vínculo vigente, conta ativa e unidade ativa no momento do processamento; o banco recusa aviso para destinatário
  inapto, para outra unidade ou para unidade diferente da do registro (`notifications_worker_insert`).
- O aviso não traz nome do visitante nem dado da encomenda: a tela leva à página da unidade, que consulta o detalhe
  com as permissões de quem abre. A caixa mostra a situação atual do registro (ou que ele não está mais visível).
- **Disponível na caixa não é leitura**: `AVAILABLE` até a abertura autenticada pelo destinatário
  (`POST /v1/tenants/{t}/notifications/{id}/open`), que grava `READ_IN_APP` uma vez, com horário do banco. Listar ou
  atualizar não marca nada. Quem perde o vínculo deixa de ver os avisos.
- Web: seção "Caixa da unidade" no início do morador; some sem erro técnico quando a API está desligada.

| Operação | Rota |
| --- | --- |
| Avisos da própria conta na unidade (50 mais recentes) e não abertos | `GET /v1/tenants/{tenantId}/units/{unitId}/notifications` |
| Abrir aviso (única forma de marcar leitura) | `POST /v1/tenants/{tenantId}/notifications/{notificationId}/open` |

## Evidências locais e de CI

- `tests/database/run.py` (`worker_checks`): transições, arrendamento por condomínio, aviso só para tarefa arrendada e
  destinatário apto, unidade real do registro, abertura única, leitura mínima do worker.
- `OutboxWorkerJdbcTest` com login próprio do worker (`cp_ci_worker`, só `cp_worker`) e API (`cp_ci_login`).
- `tests/database/worker-smoke.sh`: jar real no perfil `worker`, sem HTTP, processa uma tarefa sintética sem
  consumidor até `SKIPPED`.

## Pendências

- Reprocessamento seletivo pelo operador (tarefas `FAILED`/`SKIPPED`), painel operacional de falhas e alerta de fila
  antiga (mais de 60 s, PRD).
- Push (instalações, consentimento, preferências, recibos do provedor, token inválido desativado), e-mail e SMS:
  "provedor aceitou" nunca vira "morador visualizou".
- Implantação de worker contínuo, credencial `cp_worker` por ambiente, aplicação remota da V015 e ensaio com Auth real.
- Tempo real (CP-036) e limpeza/retenção de tarefas concluídas e avisos.
