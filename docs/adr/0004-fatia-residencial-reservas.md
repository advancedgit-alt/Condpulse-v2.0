# ADR-0004 - Primeira fatia residencial de reservas

Esta entrega implementa uma parte de CP-006, CP-015, CP-021, CP-022 e CP-023.
Não representa aceite integral desses tickets. Os requisitos operacionais versionados
em backlog.json, acceptance-catalog.json, PLANO_IMPLANTACAO e DADOS_E_AUTORIZACAO
orientam o desenvolvimento. O arquivo original do PRD foi localizado, mas a leitura
atual não retornou conteúdo; esta execução não declara nova conferência integral
do documento original. A homologação final continua condicionada a essa conferência.

## Escopo e autorização

- Cada comando revalida conta ACTIVE, condomínio ativo, vínculo vigente e unidade ativa.
- RESIDENT e RESPONSIBLE atuam na própria unidade. DEPENDENT exige os booleanos JSON
  explícitos permissions.reservations.create/cancel. Strings como "true" não concedem acesso.
- OWNER, CONCIERGE, MANAGER e SUPPORT não recebem poder de reserva neste caminho residencial.
- Cancelamento exige também created_by igual ao ator. Não se presume autorização para
  cancelar a reserva de outra pessoa da mesma unidade.
- Unidades e reservas consultadas pertencem aos vínculos autorizados. Não há calendário
  com identificação de outros moradores nem cadastro administrativo nesta fatia.

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

Aprovação/expiração, bloqueios administrativos, fila, recorrência, convidados e propagação
de revogação física não pertencem a esta entrega. Cancelamento com convidados depende
de CP-038 e não pode ser declarado atendido por gravar somente um evento no outbox.
