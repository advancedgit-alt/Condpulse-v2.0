# Proprietário × inquilino (V034)

Regra do responsável (chat, 30/10/2026): "proprietário que aluga perde a visão da unidade e só pode incluir/remover o
inquilino". Item 4 do `docs/PROMPT_CONTINUIDADE.md`.

## Papéis
- **Proprietário**: vínculo `OWNER` (já existia na V001; até aqui só contava como público de avisos).
- **Ocupantes**: `RESIDENT`, `RESPONSIBLE` e `DEPENDENT` da unidade. O **inquilino** é o ocupante convidado como
  `RESPONSIBLE` (titular) ou `RESIDENT`.
- **Gestão**: `MANAGER` com `memberships.manage` e AAL2.

## Modelo
`cp.unit_rentals` (V034, RLS ENABLE/FORCE, sem DELETE): uma linha por vínculo `OWNER` não revogado da unidade, com conta
do proprietário copiada do vínculo pelo gatilho, autoria e horários do banco. A unidade está alugada enquanto houver
linha em vigor (`ended_at` nulo). Só a gestão inicia e encerra; encerrar é final (outra locação é outra linha).

A posse fica nesta tabela, e não numa consulta a `cp.memberships`, para que as políticas de vínculos não dependam de
vínculos (evita recursão de políticas). As políticas de `cp.unit_rentals` não consultam vínculos.

Gatilhos:
- início exige vínculo `OWNER` não revogado da mesma unidade e recusa proprietário que ainda seja ocupante dela;
- vínculo de ocupante (inserção, aceite de convite, aprovação, reativação) é recusado para o proprietário de unidade
  alugada por ele;
- proprietário aprovado durante a locação entra nela; proprietário revogado encerra a própria linha.

## O que o banco garante enquanto a unidade estiver alugada
- **Barreira geral** (`rental_owner_hidden`, RESTRICTIVE, leitura e escrita) em toda tabela `cp` com `unit_id`, exceto
  vínculos e convites: reservas, fila de espera, encomendas, retirada delegada, convites de visita, links, regras de
  prestador, chegadas, registros de acesso, pedidos, mudanças, ocorrências, caixa interna, credenciais e eventos de
  equipamento, linhas de importação. Tabelas sem `unit_id` (entrega de encomenda, mensagem e anexo de pedido,
  sincronização de credencial) dependem da linha-mãe, já escondida. Fora de contexto de equipe: com capacidade
  publicada (AAL2) a gestão e a portaria seguem as próprias políticas. O harness confere que toda tabela nova com
  `unit_id` recebe a barreira.
- **Avisos**: vínculo na unidade alugada pelo próprio ator deixa de contar como público (inclusive avisos do condomínio
  inteiro; vínculos em outras unidades continuam valendo).
- **Única capacidade**: ver os ocupantes da unidade (nome, papel, situação, versão), revogá-los (só `REVOKED`), ver,
  criar e revogar convites `RESIDENT`/`RESPONSIBLE` da unidade e, ao remover um ocupante, revogar as credenciais de
  acesso desse vínculo já revogado (com trilha `CREDENTIAL_REVOKED`). Nada em outras unidades; nada sobre outros
  proprietários ou equipe.
- O inquilino não vê nem altera o vínculo do proprietário nem a locação; tem a visão normal de morador.
- Trilha `UNIT_RENTAL_*` só pela gestão, em nome da própria conta.

## API (flag `CP_MANAGEMENT_ENABLED`)
Gestão (AAL2): `GET /v1/tenants/{t}/management/rentals`, `POST .../management/units/{u}/rental` (motivo obrigatório;
`RENTAL_OWNER_REQUIRED`, `RENTAL_OWNER_IS_OCCUPANT`, `RENTAL_ALREADY_ACTIVE`) e `POST .../rental/end`
(`RENTAL_NOT_ACTIVE`). Auditoria `UNIT_RENTAL_STARTED`/`UNIT_RENTAL_ENDED`.

Proprietário (vínculo `OWNER` vigente da unidade, AAL1 como as demais áreas do morador): `GET /v1/tenants/{t}/units/{u}/rental`
(sem locação: só a indicação), `POST .../rental/invitations` (mesmo convite de vínculo da CP-020: link exibido uma vez,
só o hash no banco, aceite com o mesmo e-mail cria vínculo `PENDING`), `POST .../rental/invitations/{id}/revoke` e
`POST .../rental/occupants/{membershipId}/remove` (versão lida e motivo; vínculo `REVOKED`, credenciais revogadas, evento
`MEMBERSHIP_REVOKED` e auditoria na mesma transação). Todas as ações exigem locação em vigor (`RENTAL_NOT_ACTIVE`).

## Web
- Morador/proprietário: aba **Inquilinos** para vínculo `OWNER`. Com a unidade alugada é a única aba, e qualquer outra
  rota da unidade redireciona para ela.
- Gestão › Pessoas: painel **Locações** (marcar unidade como alugada com motivo, listar, encerrar com motivo).

## Testes
- PostgreSQL (`tests/database/run.py`, `rental_checks`): barreira em todas as tabelas com `unit_id`; proprietário de
  unidade alugada lê zero linhas da unidade em cada tabela mesmo com vínculo de ocupante gravado fora das regras, e o
  controle sem locação mostra as linhas de volta; escrita recusada; ocupantes só da própria unidade; só revogação; convites
  só `RESIDENT`/`RESPONSIBLE` da própria unidade, em nome próprio; credencial só de ocupante já removido; inquilino não
  administra o proprietário; coproprietário aprovado/revogado; encerramento único; worker sem acesso.
- JDBC (`RentalJdbcTest`): fluxo completo pela API de serviço, códigos de erro, contexto limpo no pool.
- Web: `api/rental.test.ts` e `e2e/rental.spec.ts` (inclui varredura de acessibilidade da tela do proprietário).

## Pendências e decisões para o responsável
- O vínculo do inquilino convidado pelo proprietário continua `PENDING` até a gestão aprovar (regra existente da CP-020).
- O proprietário remove qualquer ocupante da unidade alugada (inclusive dependentes do inquilino); convida só morador
  ou responsável.
- O proprietário de unidade alugada deixa de receber também os avisos do condomínio inteiro por esse vínculo.
- Ações do proprietário não exigem segundo fator (como o restante da área do morador).
- Encerrar a locação não revoga os vínculos do inquilino; a gestão revoga separadamente.
- Locação exige vínculo `OWNER` cadastrado; unidade sem proprietário no sistema não é marcada.
- Visitantes cadastrados pelo próprio ator antes da locação continuam visíveis a ele (`visitors` não tem `unit_id`).
