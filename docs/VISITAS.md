# Visitas e chegadas (CP-025/CP-026)

Flag: `CP_VISITS_ENABLED` (padrão `false`, sem alteração na Vercel). Com a flag desligada as rotas não existem.
V013 não foi aplicada a nenhum banco remoto.

Convite é autorização temporal da unidade, não prova de identidade nem de entrada: a portaria segue conferindo a
pessoa conforme o procedimento do condomínio. Chegada sem convite espera a primeira decisão válida até o prazo; **sem
resposta não existe autorização automática**. Nenhuma tela marca a entrada de alguém: o registro de entrada e saída
(direção, evidência manual e operador) é o CP-028.

## Quem faz o quê

| Pessoa | Pode | Não pode |
| --- | --- | --- |
| Morador e responsável da unidade | Convidar (nome, final do documento opcional, finalidade e período de até 7 dias), ver todos os convites da unidade, revogar com motivo, ver as chegadas dos últimos 7 dias e responder pelo aplicativo dentro do prazo | Ver convites ou chegadas de outra unidade; responder depois do prazo; mudar uma resposta já registrada |
| Dependente da unidade | Convidar e ver/revogar **somente os convites que criou**, com `permissions.visits.create`; ver e responder chegadas só com `permissions.visits.decide` | Tudo acima sem a permissão; ver convites de outros adultos |
| Portaria (`CONCIERGE`, capacidade `concierge.operate` com AAL2) | Registrar chegada (com ou sem convite vinculado), ver a fila das últimas 24 horas, registrar a confirmação assistida pelo interfone ou presencial (com nota de quem confirmou), encerrar chegada aguardando ou vencida com motivo, ver as visitas previstas (em vigor ou que começam nas próximas 24 horas) | Responder pela unidade "pelo aplicativo"; criar ou revogar convite; marcar entrada; ver outro condomínio |
| Proprietário sem vínculo residencial, gestão sem portaria, conta suspensa, unidade inativa | — | Ver ou responder convites e chegadas |

As permissões do dependente seguem o modelo das reservas e das encomendas: hoje só são gravadas pelo cadastro
operacional; ainda não há tela para concedê-las.

## Rotas

| Operação | Rota |
| --- | --- |
| Convites da unidade | `GET /v1/tenants/{tenantId}/units/{unitId}/permits` |
| Convidar (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/units/{unitId}/permits` |
| Revogar pela versão lida, com motivo | `POST /v1/tenants/{tenantId}/permits/{permitId}/revoke` |
| Chegadas da unidade (7 dias) | `GET /v1/tenants/{tenantId}/units/{unitId}/arrivals` |
| Resposta da unidade pelo aplicativo | `POST /v1/tenants/{tenantId}/arrivals/{arrivalId}/decision` |
| Unidades ativas (compartilhada com encomendas) | `GET /v1/tenants/{tenantId}/concierge/units?q=` |
| Visitas previstas da portaria (`unitId`, `q`) | `GET /v1/tenants/{tenantId}/concierge/permits` |
| Fila da portaria (`status`) | `GET /v1/tenants/{tenantId}/arrivals` |
| Registrar chegada (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/arrivals` |
| Confirmação assistida (interfone/presencial) | `POST /v1/tenants/{tenantId}/arrivals/{arrivalId}/assisted-decision` |
| Encerrar chegada | `POST /v1/tenants/{tenantId}/arrivals/{arrivalId}/cancel` |

A rota de unidades existe com `CP_PARCELS_ENABLED` **ou** `CP_VISITS_ENABLED`.

## Regras

- **Identidade mínima**: nome (2 a 160 caracteres, espaços Unicode normalizados) e, quando o procedimento pedir, só os
  2 a 4 últimos caracteres do documento. Nenhum CPF ou documento completo é gravado; a URL e as respostas não trazem
  documento inteiro. Cada convite e cada chegada gravam a própria pessoa (`cp.visitors`), sem cadastro reaproveitável.
- **Convite**: período obrigatório de até 7 dias, começando no máximo 10 minutos antes de agora e até 90 dias à frente
  (pelo relógio do banco). O estado é calculado pelo banco na leitura: `SCHEDULED` antes do início, `ACTIVE` dentro do
  período, `EXPIRED` depois do fim, `REVOKED` depois da revogação. Fora do período o convite não autoriza a entrada; a
  portaria vê "Vale agora" ou "Agendado" conforme o servidor, nunca pelo relógio do navegador.
- **Revogação**: pela versão lida e com motivo (até 500), de convite agendado ou em vigor; o convite sai das visitas
  previstas. Revogado e vencido não voltam (gatilho de transição).
- **Chegada**: a portaria registra a pessoa e a unidade ativa, opcionalmente vinculada a um convite **da mesma unidade**
  (pessoa adiantada ou procedimento que pede confirmação). O prazo de 5 minutos é gravado pelo banco; a lista mostra
  "Sem resposta no prazo" (`EXPIRED`) depois dele. Auditoria `ARRIVAL_REQUESTED` e intenção de aviso no outbox na
  mesma transação; repetir com a mesma chave devolve o mesmo registro.
- **Primeira decisão válida**: mudança condicional `WAITING → APPROVED/DENIED/CANCELLED` com versão +1; a segunda
  recebe `409 ARRIVAL_ALREADY_DECIDED` (ou `ARRIVAL_CANCELLED`). Resposta pelo aplicativo só dentro do prazo — depois
  dele recebe `409 ARRIVAL_EXPIRED` e não decide (AC-021). Decisões simultâneas da unidade e da portaria produzem uma
  única transição (teste concorrente no JDBC).
- **Sem resposta**: nada é liberado. A portaria segue o interfone ou o procedimento presencial aprovado e registra a
  confirmação com a origem (`INTERCOM`/`IN_PERSON`) e a nota de quem confirmou — o que vale também depois do prazo do
  aplicativo, enquanto a chegada não foi decidida nem encerrada. Encerrar (pessoa foi embora, registro errado) não
  autoriza nem recusa.
- **Banco (V013)**: RLS por vínculo (`cp.visit_member` confere conta ativa, unidade ativa, papel adulto ou permissão do
  dependente, igual ao Java) e por capacidade da portaria; grants por coluna (a aplicação não escolhe estado inicial,
  prazo, horários nem responsável de outra conta); gatilhos de inclusão (período do convite, prazo da chegada, convite
  da mesma unidade) e de transição (estados finais, versão +1, horários do banco, resposta do aplicativo só no prazo);
  eventos `VISIT_*`/`ARRIVAL_*` só para convite ou chegada visível e trilha sempre em nome da própria conta.
- **Privacidade**: as respostas não trazem contas de quem criou, revogou ou decidiu; a tela só sabe se foi a própria
  conta (`createdByMe`, `decidedByMe`). Quem fez cada ação fica na auditoria do condomínio. O evento do outbox não leva
  o nome do visitante.
- **Fora deste recorte**: convite compartilhável por link com token (CP-037), convidados de reserva (CP-038),
  prestadores recorrentes e janelas, registro de entrada/saída (CP-028), fila operacional completa com ocorrências
  (CP-027), envio do aviso (worker CP-034 e push CP-035) e tempo real (CP-036).

## Telas web

- Morador: aba **Acessos** (`/morador/{tenantId}/{unitId}/acessos`, só para vínculo residencial), com chegadas
  aguardando resposta (consulta a cada 10 segundos enquanto a tela está aberta, sem push), histórico de 7 dias,
  convites em aberto e encerrados, formulário de convite com chave de operação (trava depois de resultado incerto) e
  revogação com motivo.
- Portaria: **Visitas** (`/portaria/{tenantId}/visitas`, ao lado de Encomendas), com registro de chegada, fila das
  últimas 24 horas (consulta a cada 10 segundos) com confirmação assistida e encerramento, e visitas previstas com
  pedido de confirmação vinculado ao convite. Não há botão que marque entrada ou libere alguém.

## Pendências

- Aplicação remota da V013 com aprovação; ensaio com Auth real, portaria nomeada e unidade real.
- Tela para conceder `visits.create`/`visits.decide` ao dependente.
- Prazo configurável por condomínio (hoje 5 minutos fixos no banco).
- Aviso efetivo à unidade (CP-034/CP-035), tempo real (CP-036), registro de entrada/saída (CP-028), link de convite
  (CP-037) e retenção dos dados de visitantes.
