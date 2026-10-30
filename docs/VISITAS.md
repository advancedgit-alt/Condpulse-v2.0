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

As permissões do dependente seguem o modelo das reservas e das encomendas, mas **ainda não há como concedê-las**: nem
a gestão nem o cadastro operacional gravam `permissions` (o cadastro exige vínculo sem permissões). Até existir essa
concessão, dependentes não convidam nem respondem chegadas.

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
  documento inteiro. Cada convite e cada chegada gravam a própria pessoa (`cp.visitors`), registrada pela própria conta
  e usada uma única vez (índices únicos e políticas conferem no banco): sem cadastro reaproveitável.
- **Convite**: período obrigatório de até 7 dias, em segundos inteiros, começando no máximo 10 minutos antes de agora e
  até 90 dias à frente (pelo relógio do banco). O estado é calculado pelo banco na leitura: `SCHEDULED` antes do
  início, `ACTIVE` dentro do período, `EXPIRED` depois do fim, `REVOKED` depois da revogação. Fora do período o convite
  não autoriza a entrada; a portaria vê "Vale agora" ou "Agendado" conforme o servidor, nunca pelo relógio do navegador.
- **Quem convidou precisa continuar apto**: convite de quem perdeu o vínculo (encerrado, suspenso, vencido), teve a
  conta suspensa ou de unidade inativa deixa de valer para a portaria — some das visitas previstas e não vincula
  chegada (`VISIT_PERMIT_INACTIVE`). A tela de outro adulto da unidade ainda o lista até a revogação ou o fim do
  período (o morador não enxerga o vínculo dos demais).
- **Revogação**: pela versão lida e com motivo (até 500), de convite agendado ou em vigor; o convite sai das visitas
  previstas. Revogado e vencido não voltam (gatilho de transição).
- **Chegada**: a portaria registra a pessoa e a unidade ativa, opcionalmente vinculada a um convite **da mesma unidade e
  da mesma pessoa** (nome e final do documento), ainda agendado ou em vigor e de quem ainda pode convidar (pessoa
  adiantada ou procedimento que pede confirmação). Convite revogado ou vencido é recusado com o estado atual
  (`VISIT_PERMIT_REVOKED`/`VISIT_PERMIT_EXPIRED`, AC-019/AC-020) e a portaria segue a chegada sem convite. O vínculo ao
  convite não autoriza nada: a chegada espera a resposta da unidade. O prazo de 5 minutos é gravado pelo banco; a lista
  mostra "Sem resposta no prazo" (`EXPIRED`) depois dele. Auditoria `ARRIVAL_REQUESTED` e intenção de aviso no outbox
  na mesma transação; repetir com a mesma chave devolve o mesmo registro.
- **Primeira decisão válida**: mudança condicional `WAITING → APPROVED/DENIED/CANCELLED` com versão +1; a segunda
  recebe `409 ARRIVAL_ALREADY_DECIDED` (ou `ARRIVAL_CANCELLED`). Resposta pelo aplicativo só dentro do prazo — depois
  dele recebe `409 ARRIVAL_EXPIRED` e não decide (AC-021). Decisões simultâneas da unidade e da portaria produzem uma
  única transição (teste concorrente no JDBC).
- **Sem resposta**: nada é liberado. A portaria segue o interfone ou o procedimento presencial aprovado e registra a
  confirmação com a origem (`INTERCOM`/`IN_PERSON`) e a nota de quem confirmou — o que vale também depois do prazo do
  aplicativo, enquanto a chegada não foi decidida nem encerrada. Encerrar (pessoa foi embora, registro errado) não
  autoriza nem recusa; o motivo aparece para a unidade.
- **Banco (V013)**: RLS por vínculo (`cp.visit_member` confere conta ativa, unidade ativa, papel adulto ou permissão do
  dependente, igual ao Java) e por capacidade da portaria; grants por coluna (a aplicação não escolhe estado inicial,
  prazo, horários nem responsável de outra conta); pessoa registrada pela própria conta e usada uma vez; gatilhos de
  inclusão (período do convite, prazo da chegada, convite vinculado válido, da mesma unidade e da mesma pessoa, de quem
  ainda pode convidar — `cp.permit_issuer_active`) e de transição (estados finais, versão +1, horários do banco,
  resposta do aplicativo só no prazo); eventos `VISIT_*`/`ARRIVAL_*` só para convite ou chegada visível e trilha sempre
  em nome da própria conta. Índices pela pessoa sustentam a política de leitura de visitantes.
- **Privacidade**: as respostas não trazem contas de quem criou, revogou ou decidiu; a tela só sabe se foi a própria
  conta (`createdByMe`, `decidedByMe`). Quem fez cada ação fica na auditoria do condomínio. O evento do outbox não leva
  o nome do visitante.
- **Fora deste recorte**:
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
- Concessão de `visits.create`/`visits.decide` ao dependente (tela e regra; hoje nenhum fluxo grava permissões).
- Revogação dos convites pela gestão quando o vínculo de quem convidou termina (hoje só deixam de valer para a
  portaria) e limite de convites em aberto por unidade (a lista da portaria mostra 100 e pede busca).
- Prazo configurável por condomínio (hoje 5 minutos fixos no banco).
- Aviso efetivo à unidade (CP-034/CP-035), tempo real (CP-036), registro de entrada/saída (CP-028), conclusão de dados pelo
  convidado no link (CP-037) e retenção dos dados de visitantes.

## Link compartilhável do convite (CP-037)

Atrás de `CP_VISITS_ENABLED`; a abertura sem conta exige também `CP_BFF_ENABLED`. Banco: V020 (`cp.permit_links`).

- **Token**: 32 bytes aleatórios em Base64 URL (43 caracteres), devolvido uma única vez em
  `POST /v1/tenants/{t}/permits/{p}/link`. O banco guarda só o SHA-256 em hexadecimal; nada de token em log, auditoria,
  banco ou URL do servidor. A web monta `/visita#token`: o fragmento não vai ao servidor, a página limpa a barra de
  endereço na abertura e guarda o token só na aba (para recarregar). `Referrer-Policy: no-referrer`.
- **Quem pode**: a mesma regra da revogação do convite (adulto da unidade ou quem criou, com a permissão de convidar).
  Só convite em vigor ou agendado. Gerar outro link revoga o anterior na mesma transação (um link em vigor por convite).
  `POST .../link/revoke` revoga o link sem revogar o convite. Sem chave de operação: guardar a resposta exigiria guardar o
  token; resposta perdida se resolve gerando outro link.
- **Quem abre o link** (`POST /auth/permit-links/view`, Origin e CSRF do BFF, limite global de 120/min e 20 a cada 15 min
  por hash): vê o nome do condomínio, o primeiro nome, a finalidade, o período e se está agendado ou em vigor. Sem
  unidade, sem identificadores. Link desconhecido, revogado, de convite revogado ou vencido têm a mesma resposta neutra.
- **Banco**: a leitura sem conta não define condomínio nem ator; as políticas liberam só o link daquele hash, o convite
  dele, a pessoa e o nome do condomínio, enquanto o link estiver em vigor (funções `cp.permit_link_open` e
  `cp.permit_link_visitor`, evitando recursão entre políticas). A portaria não vê links. Link nunca é apagado; só a
  revogação muda, com horário do banco.
- **Não é prova de entrada**: o link não abre portas nem substitui a conferência da portaria.
- **Pendentes**: o convidado concluir dados pelo link (ex.: documento), envio do link pelo aplicativo do celular, uso do
  link pela portaria e conferência de vínculo ativo de quem convidou na própria visão do link (hoje a portaria confere
  ao receber a pessoa).
- **Evidências**: `tests/database/run.py` (`permit_link_checks`, 30 verificações), `PermitLinkJdbcTest`,
  `PermitLinkSecurityIntegrationTest`, `PermitLinkTokensTest`, `DatabaseRoleGuardJdbcTest`; web `api/permitLink.test.ts`,
  `e2e/visit-link.spec.ts` e os testes de link em `e2e/visits.spec.ts`.

## Convidados de reserva (CP-038)

Atrás de `CP_VISITS_ENABLED`. Banco: V021 (`cp.visit_permits.reservation_id`).

- **Quem**: quem pode convidar pela unidade da reserva (adulto, ou dependente com a permissão de convidar).
- **O quê**: cada convidado é um convite da unidade com a janela do evento — de até 1 hora antes do início (ou de agora,
  durante o evento) até o fim do uso —, finalidade "Convidado: <área>", até a capacidade aceita na reserva (snapshot das regras; mudar a área depois não altera
  a reserva) em convidados ativos, contados por inteiro mesmo quando quem convida é dependente (que continua vendo só os
  próprios).
  Só reserva de uso confirmada e ainda não encerrada. A portaria vê os convidados nas visitas previstas como qualquer
  convite; o link compartilhável (CP-037) também vale para eles.
- **Encerramento**: cancelar, rejeitar ou vencer a reserva revoga todos os convidados na mesma transação, por gatilho no
  banco (qualquer caminho: morador, gestão, expiração), em nome de quem encerrou, com motivo e um evento
  `VISIT_PERMIT_REVOKED` por convidado no outbox. Repetir o cancelamento não reativa nada. Encerramento sem conta (ex.:
  worker) com convidados falha fechado em vez de deixar convidado válido. A gestão não vê convidados no dia a dia; a
  liberação para revogar vale só durante o encerramento.
  Inclusão e encerramento usam a mesma trava da unidade dos comandos de reserva, sempre antes da linha: não há convidado
  ativo em reserva encerrada nem deadlock entre os dois caminhos.
- **API**: `GET` e `POST /v1/tenants/{t}/reservations/{r}/guests` (`Idempotency-Key` obrigatória); remoção pela revogação
  comum do convite.
- **Web**: "Convidados" na reserva confirmada (lista, adicionar com chave de operação travada em resultado incerto,
  remover com confirmação); a confirmação de cancelamento avisa que os convidados perdem o convite.
- **Evidências**: `tests/database/run.py` (`reservation_guest_checks`, 36 verificações, incluindo inclusão e cancelamento simultâneos nas duas ordens e na ordem de travas da API, expiração de pedido de outra unidade e remoção de convidado concorrente), `ReservationGuestJdbcTest`,
  `VisitSecurityIntegrationTest`; web `e2e/reservation-guests.spec.ts`.
- **Pendentes**: convidados pelo celular, aviso aos convidados quando a reserva é cancelada e ensaio com Auth real.
