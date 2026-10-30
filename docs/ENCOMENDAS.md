# Encomendas (CP-029/CP-030/CP-031)

Flag: `CP_PARCELS_ENABLED` (padrão `false`, sem alteração na Vercel). Com a flag desligada as rotas não existem.
V012 não foi aplicada a nenhum banco remoto.

## Quem faz o quê

| Pessoa | Pode | Não pode |
| --- | --- | --- |
| Portaria (`CONCIERGE`, capacidade `concierge.operate` com AAL2) | Buscar unidade ativa, registrar recebimento, listar e filtrar por situação/unidade/local, ver quem pode retirar, registrar retirada, anular registro indevido | Ver encomendas de outro condomínio; alterar quantidade, local ou datas; desfazer retirada ou anulação; entregar a quem não tem vínculo com a unidade |
| Morador e responsável da unidade | Ver as encomendas da própria unidade (a retirar e retiradas, com quem retirou e quando) | Ver encomendas de outra unidade, mesmo no mesmo condomínio; ver registros anulados; registrar recebimento, retirada ou anulação |
| Dependente da unidade | O mesmo que o morador **somente com permissão explícita** (`permissions.parcels.view`); retirar só com `permissions.parcels.pickup` | Tudo acima sem a permissão |
| Proprietário sem vínculo residencial, gestão sem portaria, conta suspensa, unidade inativa | — | Ver encomendas da unidade |

As permissões do dependente seguem o modelo das reservas (`permissions.reservations.*`), mas ainda não há como
concedê-las: nem a gestão nem o cadastro operacional gravam `permissions` (o cadastro exige vínculo sem permissões).

## Rotas

| Operação | Rota |
| --- | --- |
| Unidades ativas para o recebimento | `GET /v1/tenants/{tenantId}/concierge/units?q=` |
| Lista da portaria (`status` RECEIVED/DELIVERED/VOIDED, `unitId`, `location`) | `GET /v1/tenants/{tenantId}/parcels` |
| Receber (chave `Idempotency-Key`; `confirmDuplicate` depois do aviso) | `POST /v1/tenants/{tenantId}/parcels` |
| Detalhe (portaria ou vínculo que vê a unidade) | `GET /v1/tenants/{tenantId}/parcels/{parcelId}` |
| Quem pode retirar (portaria) | `GET /v1/tenants/{tenantId}/parcels/{parcelId}/recipients` |
| Registrar retirada (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/parcels/{parcelId}/handoff` |
| Anular registro indevido (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/parcels/{parcelId}/void` |
| Encomendas da unidade (`status` RECEIVED/DELIVERED) | `GET /v1/tenants/{tenantId}/units/{unitId}/parcels` |

## Regras

- **Recebimento**: unidade ativa do condomínio, de 1 a 1000 volumes, descrição curta opcional (até 500) e local de
  guarda (até 160), com espaços Unicode normalizados. Grava a encomenda, a auditoria `PARCEL_RECEIVED` e a intenção de
  aviso no outbox **na mesma transação**; o aviso nunca condiciona o registro. Repetir com a mesma chave e os mesmos
  dados devolve o mesmo resultado (`Idempotency-Replayed: true`), sem segundo registro nem segunda intenção de aviso.
- **Duplicidade suspeita (POR-02)**: encomenda igual (unidade, volumes, descrição e local, sem diferença de caixa)
  ainda a retirar e registrada há menos de 10 minutos devolve `409 PARCEL_DUPLICATE_SUSPECTED` sem gravar nada; a
  portaria confere e repete com `confirmDuplicate=true` se for outra encomenda.
- **Retirada identificada**: a portaria escolhe a pessoa entre os vínculos residenciais vigentes da unidade (morador,
  responsável e dependente com `permissions.parcels.pickup`; conta ativa, unidade ativa) e confirma que conferiu o
  documento (`evidenceKind=ASSISTED`), pela versão lida. O registro de retirada grava o vínculo escolhido e o nome
  dele; o banco recusa vínculo de outra unidade, nome diferente, proprietário, conta suspensa ou dependente sem
  permissão. Retirada por outra pessoa depende de delegação e código (CP-041).
- **Retirada única**: `UNIQUE` no registro de retirada, mudança condicional de estado sob bloqueio da encomenda e
  gatilho diferido que só aceita `DELIVERED` junto com o registro. A segunda tentativa recebe `409
  PARCEL_ALREADY_DELIVERED`. Auditoria `PARCEL_DELIVERED` e evento no outbox na mesma transação.
- **Registro indevido**: unidade errada ou duplicidade são corrigidas anulando a encomenda ainda a retirar (`VOIDED`),
  pela versão lida, com motivo na auditoria (`PARCEL_VOIDED`) e evento no outbox. Nada é apagado; a unidade deixa de
  ver o registro e ele não pode mais ser retirado. Retirada e anulação são finais (o banco recusa a volta).
- **Fila da portaria**: "A retirar" vem da mais antiga para a mais nova (as antigas nunca saem da tela por excesso de
  novas); retiradas e anuladas, da mais recente. Páginas de 100 com aviso quando há mais.
- **Banco (V012)**: RLS por capacidade e por vínculo residencial (`cp.resident_of_unit` confere conta ativa, unidade
  ativa e permissão do dependente, igual ao contexto do Java); grants por coluna; horários pelo relógio do banco;
  eventos `PARCEL_*` no outbox e na auditoria só pela portaria, em nome da própria conta.
- **Privacidade**: as respostas não trazem contas da equipe; quem recebeu, entregou ou anulou fica na auditoria do
  condomínio. A portaria vê nome e papel dos vínculos residenciais do condomínio (para conferir quem retira), sem
  proprietários, gestão ou equipe.
- **Fora deste recorte**: foto da embalagem (CP-017), envio do aviso (worker CP-034 e push CP-035) e retenção de
  fotos. Código de retirada e delegação: seção CP-041 abaixo.

## Telas web

- Portaria: `/portaria/{tenantId}/encomendas` (acesso pela escolha de contexto), com porta de segundo fator,
  formulário de recebimento (aviso de duplicidade com confirmação explícita) e fila com filtro por situação, unidade e
  local. A retirada mostra as pessoas que podem retirar e exige escolher uma e marcar a conferência do documento.
  Resultado incerto trava o formulário na mesma chave até a portaria conferir a lista; recusa por retirada já feita,
  anulação ou mudança aparece como aviso e a lista mostra o estado real.
- Morador: `/morador/{tenantId}/{unitId}/encomendas` (aba só para vínculo residencial), com pendentes e retiradas em
  consultas separadas, local de guarda e histórico de retiradas.

## Pendências

- Aplicação remota da V012 com aprovação; ensaio com Auth real e portaria nomeada.
- Concessão de `parcels.view`/`parcels.pickup` ao dependente (tela e regra; hoje nenhum fluxo grava permissões).
- Foto, aviso efetivo ao morador e retenção (tickets próprios); delegação pelo celular.

## Retirada delegada com código (CP-041)

Atrás de `CP_PARCELS_ENABLED`. Banco: V024 (`cp.parcel_delegations`).

- **Autorizar**: quem pode retirar pela unidade (adulto, ou dependente com `parcels.pickup`) autoriza um terceiro para
  uma encomenda ainda a retirar: nome, final do documento (opcional) e validade de 15 minutos a 7 dias (padrão 48 h). O
  código de 8 dígitos aparece só na resposta da criação; o banco guarda só HMAC-SHA256(`CP_PARCEL_CODE_KEY`,
  "delegação:código"), com a chave fora do banco (PRD, "Delegar"), e a resposta
  idempotente guarda só o identificador (repetir a mesma chave devolve a autorização sem código). Uma autorização em
  vigor por encomenda: a nova encerra a anterior. Cancelar é pelo identificador e pela versão da autorização exibida
  (uma confirmação de autorização já substituída é recusada).
- **Conferir**: a portaria (capacidade e AAL2) informa o código da própria encomenda e confirma o documento. A
  aplicação calcula o autenticador com a chave para a autorização em vigor lida e o banco só compara
  (`cp.parcel_delegation_check`, sob a trava da autorização; autorização trocada no meio responde `DELEGATION_CHANGED`
  sem contar tentativa): código errado conta
  tentativa, gravada mesmo com a recusa; na quinta errada a autorização fica bloqueada; vencida não vale; código de
  outra encomenda nunca vale (não há autorização em vigor nela). Código certo marca a autorização como usada e a
  retirada é registrada na mesma transação, com evidência `DELEGATION` e o nome autorizado; código usado não volta a
  valer. Retirada assistida ou anulação encerram a autorização em vigor.
- **API**: `GET`/`POST /v1/tenants/{t}/parcels/{p}/delegation` (`Idempotency-Key`), `POST .../delegation/revoke`,
  `POST /v1/tenants/{t}/parcels/{p}/handoff-code` (`Idempotency-Key`, gravada só depois do código aceito; o código
  não entra no hash da intenção; repetição simultânea com a mesma chave devolve a retirada feita). Sem
  `CP_PARCEL_CODE_KEY`, autorizar e conferir por código respondem 503 `DELEGATION_UNAVAILABLE`.
- **Web**: na encomenda a retirar, "Autorizar retirada por terceiro" (código exibido uma vez), "Gerar nova
  autorização" e "Cancelar autorização"; na portaria, "Retirada por código" com o resumo da autorização, tentativas
  restantes e conferência do documento.
- **Evidências**: `tests/database/run.py` (`delegation_checks`), `DelegationJdbcTest`, `DatabaseRoleGuardJdbcTest`; web
  `api/parcels.test.ts` e `e2e/parcel-delegation.spec.ts`.
- **Pendentes**: delegação pelo celular, aviso ao morador quando o código é usado ou bloqueado (worker) e ensaio com
  Auth real.
