# Fila de espera de reservas (CP-040)

Atrás de `CP_RESERVATIONS_ENABLED` (a mesma flag das reservas). Banco: V023 (`cp.reservation_waitlist`). PRD §10.

## Regras

- **Entrar**: só num período ocupado (reserva, pedido ou retenção em vigor de outra unidade); período livre responde
  `WAITLIST_SLOT_AVAILABLE` e a unidade reserva direto. A entrada guarda o retrato das regras vigentes (mesma validação
  da reserva: horário, antecedência, duração, limpeza e versão aceita). Uma entrada aberta por unidade e período; até 5
  entradas abertas por unidade. Quem pode reservar pela unidade entra (adulto, ou dependente com `reservations.create`).
- **Ordem**: ordem de entrada (`created_at`, `id`), estável. A posição mostrada conta só quem aguarda um período que se
  sobrepõe ao seu; nenhum dado de outra unidade é exposto.
- **Oferta**: cancelamento, rejeição ou expiração liberam o período; a oferta roda em transação própria, sob a trava da
  fila da área, e cria uma retenção `HELD` em nome de quem entrou na fila, por até 15 minutos (nunca depois do início).
  A exclusão GiST continua sendo a única garantia de ocupação: duas ofertas nunca capturam o mesmo período, e a criação
  direta do período retido conflita. A oferta acontece depois do commit do cancelamento (residencial e da gestão), da
  rejeição e da expiração, e antes da criação, da consulta de ocupação e da lista da fila (a fila tem prioridade sobre
  quem chega depois). Falha da oferta nunca desfaz o comando que liberou o período: a próxima passada oferece.
- **Sem requisição**: o worker (perfil `worker`, flag `CP_WORKER_WAITLIST_ENABLED`, padrão `false`, passada a cada
  `CP_WORKER_WAITLIST_MILLIS`, padrão 60 s) roda a mesma oferta por área, como `cp_worker` sem conta: vence oferta e
  pedido parados e oferece período liberado. O worker só lê/altera a fila e só cria retenção `HELD` e os eventos
  `WAITLIST_OFFERED`/`RESERVATION_EXPIRED` dentro da marca da oferta da área. Enquanto o worker não estiver implantado,
  a fila anda nas requisições da API.
- **Quem recebe a oferta**: o primeiro da fila cujo autor ainda tem vínculo elegível (senão a entrada é encerrada) e
  cuja unidade está abaixo da cota das regras aceitas (senão a entrada continua aguardando e o próximo recebe). A cota é
  conferida antes de escolher o lote (unidade sem cota não ocupa a passada) e de novo sob a trava da unidade; se outra operação da unidade estiver em andamento, a passada para ali e a próxima
  continua da mesma posição.
- **Prioridade sobre a reserva direta**: só quem a oferta consegue atender (autor elegível, unidade com cota e período
  aguardado inteiramente livre) bloqueia a reserva direta sobreposta; entrada de período ainda ocupado em parte não impede os horários livres ao lado.
- **Aceitar**: só quem entrou na fila, dentro do prazo, com chave de operação. A retenção vira reserva confirmada, ou
  pedido `PENDING_APPROVAL` (prazo de decisão de até 24 h, nunca depois do início) se as regras aceitas exigem
  aprovação. A cota da unidade é conferida com as regras aceitas. Oferta vencida nunca é confirmada.
- **Recusar / sair**: recusar cancela a retenção e passa a vez; sair da fila encerra a entrada aguardando.
- **Vencer**: oferta não aceita no prazo expira (retenção e entrada) e o período vai ao próximo da fila.
- **Período iniciado sem vaga**: a entrada aguardando é encerrada (`CLOSED`).

## Banco (V023)

- `cp.reservation_waitlist` com ENABLE/FORCE RLS, sem DELETE, INSERT só dos dados de entrada e UPDATE só dos campos de
  estado; máquina de estados e horários pelo banco.
- Retenção `HELD` só nasce pela oferta (marca local da transação dentro de `cp.waitlist_offer`); confirmar sem a
  capacidade de decidir só pelo aceite da própria oferta (`cp.waitlist_accept`). Retenção e entrada mudam juntas por
  gatilho em qualquer caminho.
- Ordem de travas: a oferta toma primeiro a trava da fila da área e lê as linhas de reserva com `SKIP LOCKED`; os
  comandos da unidade não tomam a trava da fila. Aceite, recusa e saída travam a entrada antes da retenção; ao vencer
  uma retenção, a passada só tenta travar a entrada da oferta (`SKIP LOCKED`) e, se ela estiver ocupada, deixa a
  retenção para a próxima passada, sem impasse.

## API e web

- `GET /v1/tenants/{t}/units/{u}/waitlist`, `POST /v1/tenants/{t}/waitlist` (`Idempotency-Key`),
  `POST /v1/tenants/{t}/waitlist/{id}/leave`, `POST /v1/tenants/{t}/waitlist/{id}/accept` (`Idempotency-Key`).
- Web: no formulário de reserva, período ocupado com as regras aceitas oferece "Entrar na fila de espera"; a seção
  "Fila de espera" das reservas mostra posição, prazo da oferta, aceitar, recusar e sair (com confirmação), e atualiza a
  cada 30 segundos enquanto houver entrada aberta (e pelo canal de tempo real, quando ligado).

## Evidências

`tests/database/run.py` (`waitlist_checks`, incluindo a passada como `cp_worker`), `WaitlistJdbcTest`, `WaitlistWorkerJdbcTest`,
`WaitlistSchedulingTest`, `DatabaseRoleGuardJdbcTest`; web
`api/waitlist.test.ts` e `e2e/waitlist.spec.ts`.

## Pendentes

Aviso da oferta na caixa interna/push (o evento `WAITLIST_OFFERED` vai ao outbox; o worker não está implantado — BLOQUEADO na implantação do worker), fila
pelo celular, visão da fila para a gestão, conferência de vínculo do autor no momento da oferta (hoje conferido no
aceite) e ensaio com Auth real.
