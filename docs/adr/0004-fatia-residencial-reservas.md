# ADR-0004 - Primeira fatia residencial de reservas

Esta entrega implementa uma parte de CP-006, CP-015, CP-021, CP-022 e CP-023.
Não representa aceite integral desses tickets. Os requisitos operacionais versionados
em backlog.json, acceptance-catalog.json, PLANO_IMPLANTACAO e DADOS_E_AUTORIZACAO
orientam o desenvolvimento. As conferências do PRD original e as evidências de
cada etapa ficam em STATUS.md. Este ADR delimita o subset e não substitui os testes
do commit ou a homologação das jornadas com os serviços reais.

## Escopo e autorização

- Cada comando revalida conta ACTIVE, condomínio ativo, vínculo vigente e unidade ativa.
- RESIDENT e RESPONSIBLE atuam na própria unidade. DEPENDENT exige os booleanos JSON
  explícitos permissions.reservations.create/cancel. Strings como "true" não concedem acesso.
- OWNER, CONCIERGE, MANAGER e SUPPORT não recebem poder de reserva neste caminho residencial.
- Cancelamento exige também created_by igual ao ator. Não se presume autorização para
  cancelar a reserva de outra pessoa da mesma unidade.
- A listagem de unidades e de reservas identificadas pertence aos vínculos autorizados.
  A consulta de ocupação por área descrita abaixo agrega intervalos sem identificar
  outras unidades ou moradores. Não há cadastro administrativo nesta fatia.

## Extensão: consulta de ocupação por área

`GET /v1/tenants/{tenantId}/amenities/{amenityId}/availability` recebe `unitId`,
`from` e `until`. É uma rota autenticada, condicionada a CP_RESERVATIONS_ENABLED;
"ocupação anônima" descreve os dados retornados, não acesso público. A mesma
transação revalida conta, condomínio, vínculo e unidade. O ator precisa poder criar
reservas na unidade informada; permissão apenas para cancelar não libera a consulta.
A área precisa estar ativa, com política válida e sem aprovação obrigatória,
preservando as limitações da criação residencial existente.

A resposta inclui somente o contexto solicitado (`tenantId`, `unitId`, `amenityId`),
fuso do condomínio, período, `observedAt` coletado no servidor e `blockedIntervals`.
Cada intervalo contém apenas `occupiedFrom` e `occupiedUntil`. Não inclui reserva,
unidade de terceiros, ator, nome, motivo ou status individual. A resposta é no-store;
a agenda identificada da própria unidade continua em `/reservations`.

A ocupação usa os mesmos limites persistidos da exclusão GiST, incluindo limpeza,
BOOKING/BLOCK e os estados HELD, PENDING_APPROVAL e CONFIRMED. Um prazo `expires_at`
vencido não libera o período enquanto não houver transição durável de estado.
CANCELLED, EXPIRED e REJECTED não bloqueiam. A leitura não cria bloqueios
administrativos, aprova pedidos ou executa um worker de expiração.

O período obrigatório é crescente, de até31 dias, com precisão máxima de
microssegundos. A consulta considera a interseção dos intervalos semiabertos com
`[from,until)`, recorta os resultados para essa janela, ordena e une intervalos
adjacentes ou sobrepostos. Mais de1000 ocupações de origem retornam503
AMENITY_AVAILABILITY_UNAVAILABLE; não há página parcial que possa sugerir vaga.

O resultado é uma observação de ocupação, não uma reserva de horário. Uma lista
vazia não comprova atendimento de regras, cota ou disponibilidade no instante da
confirmação. A criação continua revalidando o pedido, e a exclusão GiST decide a
disputa concorrente. Esta extensão não altera DDL, RLS, grupos ou grants.
Sua validação depende do CI do commit e do ensaio no ambiente de homologação;
não representa aceite integral de CP-022 ou do módulo de reservas.

## Regras e transação

O formato inicial de policy é explicitamente schemaVersion=1: weekDays (ISO 1..7),
opensAt, closesAt, minimumLeadMinutes, maximumAdvanceDays, maximumDurationMinutes,
maximumActivePerUnit e cancellationLeadMinutes. Todos os campos são obrigatórios.
Uma janela diária local, sem atravessar a meia-noite; horários usam o fuso do condomínio.
Política vazia/inválida ou aprovação obrigatória bloqueia novas reservas neste caminho.
Não há valores padrão permissivos. acceptedPolicyVersion deve coincidir com a versão
vigente; a reserva congela o snapshot, a taxa informativa e a ocupação com limpeza.

A cota é serializada por advisory_xact_lock de tenant/unidade entre áreas.
maximumActivePerUnit é critério de admissão da área solicitada: a contagem de reservas
ativas da unidade em todas as áreas precisa estar abaixo desse limite para criar a nova.
Áreas com limites diferentes podem admitir quantidades diferentes; esta entrega não
implementa um teto único permanente por condomínio nem altera reservas antigas para isso.
Não se amplia UPDATE em units/amenities para obter SELECT FOR UPDATE.
Idempotência é por tenant, ator, método, rota e chave, com hash do pedido tipado.
Replay revalida acesso; chave com outro pedido retorna conflito. Cancelamento tem versão
esperada. Reserva, auditoria, outbox e resposta idempotente persistem na mesma transação.
O GiST existente continua sendo a autoridade de conflito por área e intervalo.

## Habilitação e limites

CP_RESERVATIONS_ENABLED=false por padrão. A cadeia mantém autenticação e CSRF nas mutações,
inclusive com Bearer; clientes dessa fatia devem obter o token CSRF pelo BFF configurado.
Não habilitar publicamente sem validar JDBC, Auth real, HTTPS, limitação de tentativas,
configuração revisada das áreas e ambiente isolado. Não foi criado dado de demonstração remoto.

Aprovação/expiração, bloqueios administrativos, fila (CP-040, ver FILA_ESPERA.md), recorrência, convidados e propagação
de revogação física não pertencem a esta entrega. Cancelamento com convidados depende
de CP-038 e não pode ser declarado atendido por gravar somente um evento no outbox.
