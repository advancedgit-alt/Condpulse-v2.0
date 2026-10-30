# Outbox com worker (CP-034) e caixa interna da unidade (parte do CP-035)

Flags: `CP_WORKER_OUTBOX_ENABLED` (worker, padrão `false`) e `CP_NOTIFICATIONS_ENABLED` (API, padrão `false`), sem
alteração na Vercel. V015 não foi aplicada a nenhum banco remoto e nenhum worker contínuo foi implantado. Não há push,
e-mail nem SMS neste recorte.

## O que existe

- **Outbox durável**: cada evento de negócio continua gravado na mesma transação do registro. O worker (perfil
  `worker`, sem porta pública, login membro somente de `cp_worker`) percorre os condomínios ativos e, por condomínio,
  numa transação curta encerra arrendamentos esgotados e arrenda até 10 tarefas com `FOR UPDATE SKIP LOCKED` e prazo
  de 60 s (`LEASED`, tentativa +1). Duas instâncias não pegam a mesma tarefa; arrendamento vencido volta a ser
  arrendado; tarefa pendente só é arrendada depois do horário do backoff (o gatilho recusa antes). Falha de um
  condomínio fica no log saneado e não interrompe a passada dos outros. Nenhuma passada começa antes da aplicação
  pronta, isto é, depois da `DatabaseRoleGuard`.
- **Efeito e confirmação juntos**: cada tarefa roda em transação própria — primeiro trava a linha da tarefa ainda
  arrendada nesta tentativa (`FOR NO KEY UPDATE SKIP LOCKED`, cercada por `state='LEASED'` e pela tentativa); depois o
  efeito e a conclusão (`DELIVERED`/`SKIPPED`) na mesma transação. Worker atrasado (outra instância já arrendou de
  novo) não trava a linha, não começa o efeito e não confirma nada (testes JDBC e unitário); enquanto a linha está
  travada, nenhuma outra instância a arrenda. Como o efeito de hoje é só interno ao banco, ele é desfeito junto com
  qualquer falha da transação. Ainda assim o contrato é de ao menos uma vez: o efeito da caixa é idempotente pela chave
  tarefa/destinatário/canal, e reprocessar depois de um efeito gravado sem confirmação não duplica o aviso (AC-049,
  teste JDBC).
- **Somente efeito interno**: nenhum efeito chama serviço externo dentro da transação (AGENTS.md). Canal externo
  (push, e-mail, SMS, webhook) exige desenho próprio, ainda não implementado: marcar o envio em andamento e confirmar,
  chamar o provedor fora da transação com chave de idempotência e registrar o resultado.
- **Backoff e falha visível**: falha volta para `PENDING` com novo horário — 10 s, 30 s, 2 min, 10 min e 30 min, com
  variação de até 20% — e erro saneado (categoria e SQLSTATE, nunca a mensagem do banco ou dados). Depois da sexta
  tentativa, ou com arrendamento vencido na última tentativa, a tarefa fica `FAILED` com o erro. Problema que nova
  tentativa não resolve (evento sem unidade, registro inexistente) vai direto para `FAILED`.
- **Sem nova tentativa cega**: efeito marcado como não idempotente que falha vai direto para `FAILED`, com o aviso de
  conferir antes de reprocessar. Hoje só existe o efeito idempotente da caixa.
- **Sem consumidor não é entregue**: evento sem efeito neste recorte (reservas, decisões, registros de acesso etc.),
  chegada já respondida ou vencida e encomenda já retirada ou anulada ficam `SKIPPED`, nunca `DELIVERED`.
- **Banco (V015)**: transições do outbox conferidas por gatilho (conteúdo imutável, prazo de arrendamento de até 10
  minutos, horários de conclusão pelo banco, concluída e falha são finais); a API só cria tarefa `PENDING`; o worker
  altera só as colunas de processamento. O worker lê apenas vínculos residenciais e situação de contas e unidades do
  condomínio em contexto, de chegadas e encomendas somente unidade e situação (sem nome, descrição ou local) e, dos
  avisos, só a chave tarefa/destinatário/canal usada para absorver repetição.
- **Guarda de inicialização**: API e worker recusam iniciar com role de privilégio a mais (incluindo grant por coluna
  que deixaria a API escolher estado da tarefa ou alterar aviso). A conferência por coluna do outbox só vale com a V015
  aplicada: num banco ainda na V005 (estado do remoto) a guarda passa, provado por `DatabaseRoleGuardJdbcTest` num banco
  descartável migrado pelo Flyway só até a V005 e depois até a última versão.
- **Correlação**: logs com condomínio, tarefa, tipo e tentativa (identificadores técnicos), sem dados de pessoas.

## Caixa interna da unidade

| Evento | Aviso | Quem recebe |
| --- | --- | --- |
| `ARRIVAL_REQUESTED` (chegada ainda aguardando) | `ARRIVAL_WAITING` | Morador e responsável da unidade; dependente só com `visits.decide` |
| `PARCEL_RECEIVED` (encomenda ainda a retirar) | `PARCEL_RECEIVED` | Morador e responsável; dependente só com `parcels.view` |
| `WAITLIST_OFFERED` (retenção da fila ainda no prazo, V030) | `WAITLIST_OFFERED` | Só quem entrou na fila (autor da retenção); dependente autor só com `reservations.create` |
| `REQUEST_UPDATED` e `REQUEST_MESSAGE_ADDED` de autor STAFF (V031) | `REQUEST_UPDATED` | Morador e responsável da unidade do pedido |
| `MOVE_APPROVED`/`MOVE_REJECTED`, decisão ainda valendo (V031) | `MOVE_DECIDED` | Morador e responsável da unidade |

- Vínculo vigente, conta ativa e unidade ativa no momento do processamento; o banco recusa aviso para destinatário
  inapto, para outra unidade ou para unidade diferente da do registro (`notifications_worker_insert`).
- O aviso não traz nome do visitante nem dado da encomenda: a tela leva à página da unidade, que consulta o detalhe
  com as permissões de quem abre. A caixa mostra a situação atual do registro (ou que ele não está mais visível).
- **Disponível na caixa não é leitura**: `AVAILABLE` até a abertura autenticada pelo destinatário
  (`POST /v1/tenants/{t}/notifications/{id}/open`), que grava `READ_IN_APP` uma vez, com horário do banco. Listar ou
  atualizar não marca nada. Quem perde o vínculo (ou a permissão do tipo) deixa de ver e de abrir os avisos. A página e
  a contagem de não abertos saem da mesma instrução, sem divergência por abertura em outra aba.
- Web: seção "Caixa da unidade" no início do morador; some sem erro técnico quando a API está desligada. Celular: a mesma
  seção, apontando Visitas, Encomendas ou a fila de espera nas reservas.
- Oferta da fila (V030): o worker confere o autor pela retenção indicada no evento (não lê a fila fora da passada de
  oferta); retenção vencida ou já decidida quando o worker chega vira `SKIPPED`, sem aviso. A caixa mostra a situação
  atual da entrada (`OFFERED`, `ACCEPTED`, `LEFT`, `EXPIRED`, `CLOSED`); oferta com prazo vencido aparece como `EXPIRED`
  mesmo antes da passada seguinte.

| Operação | Rota |
| --- | --- |
| Avisos da própria conta na unidade (50 mais recentes) e não abertos | `GET /v1/tenants/{tenantId}/units/{unitId}/notifications` |
| Abrir aviso (única forma de marcar leitura) | `POST /v1/tenants/{tenantId}/notifications/{notificationId}/open` |

## Evidências locais e de CI

- `tests/database/run.py` (`worker_checks`): transições, arrendamento por condomínio, aviso só para tarefa arrendada e
  destinatário apto, unidade real do registro, abertura única, leitura mínima do worker.
- `OutboxWorkerJdbcTest` com login próprio do worker (`cp_ci_worker`, só `cp_worker`) e API (`cp_ci_login`).
- `OutboxWorkerTest` e `OutboxSchedulingTest` (unitários): falha de um condomínio não para os outros, tarefa perdida
  não roda efeito, laço só no perfil `worker` com a flag ligada e só depois da aplicação pronta.
- `DatabaseRoleGuardJdbcTest`: logins reais da API e do worker iniciam num banco na V005 e com todas as migrations;
  grant a mais por coluna impede a inicialização.
- `tests/database/worker-smoke.sh`: jar real no perfil `worker`, sem HTTP, processa uma tarefa sintética sem
  consumidor até `SKIPPED` e uma encomenda fictícia até `DELIVERED`, com um aviso `AVAILABLE` para o morador.

## Custo e dimensionamento

- Ocioso: a cada passada (padrão 2 s), duas consultas curtas por condomínio ativo (catálogo e uma transação de
  arrendamento). Adequado ao piloto com poucos condomínios; para muitos condomínios, a seleção dos condomínios com
  tarefa pronta (índice/função própria ou `LISTEN/NOTIFY`) fica como pendência, sem teste de capacidade feito.
- Lote de até 10 tarefas e arrendamento de 60 s, com limite de 10 s por transação: lote que passe do arrendamento não
  gera efeito duplicado (a trava cercada pela tentativa recusa a tarefa que outra instância arrendou), apenas gasta uma
  tentativa.

## Pendências

- Reprocessamento seletivo pelo operador (tarefas `FAILED`/`SKIPPED`), painel operacional de falhas e alerta de fila
  antiga (mais de 60 s, PRD).
- Push (instalações, consentimento, preferências, recibos do provedor, token inválido desativado), e-mail e SMS:
  "provedor aceitou" nunca vira "morador visualizou".
- Implantação de worker contínuo, credencial `cp_worker` por ambiente, aplicação remota da V015 e ensaio com Auth real.
- Tempo real (CP-036) e limpeza/retenção de tarefas concluídas e avisos.
