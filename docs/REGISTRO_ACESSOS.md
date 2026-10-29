# Registro manual de entrada e saída (CP-028) e ações da fila da portaria (CP-027)

Flag: `CP_VISITS_ENABLED` (padrão `false`, sem alteração na Vercel), a mesma das visitas. V014 não foi aplicada a
nenhum banco remoto. Base: [VISITAS.md](VISITAS.md).

O registro é **manual e da portaria**: o tipo é sempre `MANUAL_IN`/`MANUAL_OUT` com origem `CONCIERGE` e nunca é
apresentado como passagem detectada por equipamento. Não há integração física neste recorte (CP-043 a CP-048).

## Quem faz o quê

| Pessoa | Pode | Não pode |
| --- | --- | --- |
| Portaria (`CONCIERGE`, capacidade `concierge.operate` com AAL2) | Registrar entrada de quem tem autorização vigente, registrar saída de entrada registrada, corrigir entrada ou saída com motivo, ver os registros das últimas 24 horas | Registrar entrada de chegada aguardando, recusada, vencida ou encerrada, nem por convite agendado, vencido ou revogado; alterar ou apagar registro; escolher unidade, pessoa, horário ou origem |
| Morador e responsável da unidade | Ver as entradas e saídas registradas para a unidade nos últimos 7 dias | Ver registros de outra unidade; registrar ou corrigir |
| Dependente da unidade | O mesmo que o morador **somente com** `permissions.visits.decide` (o mesmo escopo das chegadas); registros por convite criado por outra pessoa da unidade não aparecem na API, porque o visitante desse convite não é visível a ele (o banco libera a linha do registro, sem os dados do visitante) | Tudo acima sem a permissão |

## Rotas

| Operação | Rota |
| --- | --- |
| Registros da portaria (24 horas) | `GET /v1/tenants/{tenantId}/access-events` |
| Registrar entrada (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/access-events/entries` |
| Registrar saída (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/access-events/{entryId}/exit` |
| Corrigir entrada ou saída (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/access-events/{eventId}/correction` |
| Histórico da unidade (7 dias) | `GET /v1/tenants/{tenantId}/units/{unitId}/access-events` |

## Regras

- **Entrada só com autorização vigente** (CP-027: não há botão nem rota que marque entrada de quem ainda não foi
  autorizado): exatamente uma base — chegada **autorizada** pela unidade (aplicativo ou confirmação assistida) e
  registrada nas últimas 24 horas, ou convite **em vigor agora** pelo relógio do banco (antes do início, vencido ou
  revogado não autoriza). A unidade precisa estar ativa nos dois casos (`ACCESS_UNIT_INACTIVE` para a chegada; o
  convite já exige quem convidou com vínculo vigente em unidade ativa). A portaria indica a conferência feita:
  documento conferido ou pessoa reconhecida por quem autorizou, na portaria. Observação interna opcional (até 300).
- **Uma entrada válida por chegada**; o convite admite novas entradas no período (sair e voltar). **Uma saída válida
  por entrada**, só de entrada não corrigida. Repetir com a mesma chave e os mesmos dados devolve o mesmo registro.
- **Correção humana** (até 300, obrigatória) é um evento adicional que referencia a entrada ou a saída; nada é
  alterado nem apagado. Entrada corrigida não recebe saída e permite registrar de novo a entrada da mesma chegada;
  saída corrigida permite registrar nova saída. Correção não se corrige.
- **Horários e origem pelo banco**: unidade e pessoa vêm da autorização de base ou da entrada; `occurred_at` e
  `received_at` são o relógio do banco no registro (manual, sem horário informado).
- **Concorrência**: travas consultivas pela chegada, pelo convite e pela entrada deixam em série os registros que
  dependem do mesmo estado. A revogação do convite toma a trava do convite (`cp.lock_permit_access`) **antes** de
  travar a linha (`FOR NO KEY UPDATE`), na mesma ordem da entrada (trava e depois conferência da chave estrangeira na
  linha); a ordem inversa produzia impasse (40P01), apontado na revisão independente. Testes JDBC: saídas simultâneas e
  revogação segurando o convite enquanto a portaria registra a entrada (a entrada espera e encontra o convite revogado).
- **Banco (V014)**: tabela `cp.access_events` com RLS por capacidade e por vínculo (`cp.visit_member` com `decide`),
  grants por coluna sem `UPDATE`/`DELETE`, gatilho de conferência antes da gravação. Saída e correção não carregam
  chegada nem convite próprios (CHECK): a base é sempre a da entrada de referência. Eventos `ACCESS_*` no outbox e
  trilha `ACCESS_*` somente com a capacidade da portaria, do registro gravado pela própria conta e, no evento, com a
  unidade real do registro em `payload.unitId`. A guarda de inicialização (`DatabaseRoleGuard`) recusa runtime com
  `UPDATE` (inclusive por coluna), `DELETE`/`TRUNCATE` ou `INSERT` nas colunas que o banco preenche (unidade, pessoa,
  horários e origem). Índice da correção também atende à chave estrangeira. Auditoria `ACCESS_ENTRY_RECORDED`,
  `ACCESS_EXIT_RECORDED` e `ACCESS_EVENT_CORRECTED` (com motivo).
- **Privacidade e linguagem**: as respostas não trazem contas da equipe (só `recordedByMe`). Observações da portaria
  (entrada e saída) são internas: a visão da unidade recebe `note` nulo. O motivo da correção aparece para a unidade, e
  o formulário avisa para não registrar dados pessoais. "Sem registro de saída" não significa que a pessoa continua no
  condomínio; a tela nunca infere presença.
- **Listas**: portaria e unidade são consultadas pelo horário de recebimento no servidor (índices
  `access_events_recent` e `access_events_by_unit`); a saída mostrada é a válida mais recente (ou a última corrigida,
  quando nenhuma é válida). `linkedPermitId` indica o convite ao qual a chegada da entrada foi vinculada.

## Telas web

- Portaria, seção Visitas: na fila, "Registrar entrada" aparece somente para chegada autorizada e ainda sem entrada
  válida; nas visitas previstas, somente para convite que o servidor informa como em vigor. Seção "Entradas e saídas"
  com saída, correção de entrada e de saída. Resultado incerto trava o envio na mesma chave e com os mesmos dados, por
  alvo (chegada, convite, entrada ou evento corrigido): fechar e reabrir o formulário — Voltar, filtro, outra ação —
  mantém a trava, e "Tentar confirmar o mesmo registro" devolve o registro já gravado em vez de criar outro. A pendência
  vale enquanto a página estiver aberta (recarregar descarta; conferir a lista antes de repetir). Recusa do servidor
  (autorização mudou, já registrada) fecha com aviso e a lista mostra o estado real. A correção fica presa ao evento
  aberto, mesmo que a lista mude enquanto o formulário está aberto. "Última entrada" do convite inclui a entrada pela
  chegada vinculada a ele.
- Morador, aba Acessos: seção "Entradas e saídas" com o que a portaria registrou nos últimos 7 dias, saída ou "sem
  registro de saída" e correções com motivo.

## Pendências

- Aplicação remota da V014 com aprovação; ensaio com Auth real e portaria nomeada.
- Ocorrências da portaria (parte do CP-027), tempo real (CP-036) e aviso à unidade (CP-034/CP-035).
- Eventos de equipamento, saída identificada pelo dispositivo e histórico residencial completo (CP-047 a CP-049).
- Retenção dos registros e dos dados de visitantes.
- Lista da portaria limitada às 100 entradas mais recentes de 24 horas, sem paginação nem filtro: acima disso, entradas
  mais antigas do dia não recebem saída ou correção pela tela (o servidor continua recusando duplicidade).
- Pendência de envio incerto guardada só na memória da página (não sobrevive a recarregar).
