# Encomendas (CP-029/CP-030/CP-031)

Flag: `CP_PARCELS_ENABLED` (padrão `false`, sem alteração na Vercel). Com a flag desligada as rotas não existem.
V012 não foi aplicada a nenhum banco remoto.

## Quem faz o quê

| Pessoa | Pode | Não pode |
| --- | --- | --- |
| Portaria (`CONCIERGE`, capacidade `concierge.operate` com AAL2) | Buscar unidade ativa, registrar recebimento, listar e filtrar por situação/unidade/local, registrar retirada | Ver encomendas de outro condomínio; alterar quantidade, local ou datas; desfazer retirada |
| Morador, responsável e dependente da unidade | Ver as encomendas da própria unidade (a retirar e retiradas, com quem retirou e quando) | Ver encomendas de outra unidade, mesmo no mesmo condomínio; registrar recebimento ou retirada |
| Proprietário sem vínculo residencial, gestão sem portaria | — | Ver encomendas da unidade |

## Rotas

| Operação | Rota |
| --- | --- |
| Unidades ativas para o recebimento | `GET /v1/tenants/{tenantId}/concierge/units?q=` |
| Lista da portaria (`status`, `unitId`, `location`) | `GET /v1/tenants/{tenantId}/parcels` |
| Receber (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/parcels` |
| Detalhe (portaria ou vínculo residencial) | `GET /v1/tenants/{tenantId}/parcels/{parcelId}` |
| Registrar retirada (chave `Idempotency-Key`) | `POST /v1/tenants/{tenantId}/parcels/{parcelId}/handoff` |
| Encomendas da unidade | `GET /v1/tenants/{tenantId}/units/{unitId}/parcels` |

## Regras

- **Recebimento**: unidade ativa do condomínio, de 1 a 1000 volumes, descrição curta opcional (até 500) e local de
  guarda (até 160), com espaços Unicode normalizados. Grava a encomenda, a auditoria `PARCEL_RECEIVED` e a intenção de
  aviso no outbox **na mesma transação**; o aviso nunca condiciona o registro. Repetir com a mesma chave e os mesmos
  dados devolve o mesmo resultado (`Idempotency-Replayed: true`), sem segundo registro nem segunda intenção de aviso.
- **Retirada**: a portaria informa o nome de quem retira e confirma que conferiu o documento (`evidenceKind=ASSISTED`),
  pela versão lida. Uma única retirada por encomenda: `UNIQUE` no registro de retirada, mudança condicional de estado e
  gatilho diferido que só aceita `DELIVERED` junto com o registro. A segunda tentativa recebe `409
  PARCEL_ALREADY_DELIVERED`. Auditoria `PARCEL_DELIVERED` e evento no outbox na mesma transação.
- **Privacidade**: as respostas não trazem contas da equipe; quem recebeu e quem entregou ficam na auditoria do
  condomínio. A política de leitura do banco repete a regra de unidade (`cp.resident_of_unit`).
- **Fora deste recorte**: foto da embalagem (CP-017), código de retirada e delegação (CP-041), envio do aviso (worker
  CP-034 e push CP-035) e retenção de fotos.

## Telas web

- Portaria: `/portaria/{tenantId}/encomendas` (acesso pela escolha de contexto), com porta de segundo fator, formulário
  de recebimento e fila "A retirar"/"Retiradas" com filtro por local. Repetição depois de resultado incerto reaproveita a
  chave; recusa por retirada já feita aparece como aviso e a lista mostra o estado real.
- Morador: `/morador/{tenantId}/{unitId}/encomendas`, com o local de guarda e o histórico de retiradas.

## Pendências

- Aplicação remota da V012 com aprovação; ensaio com Auth real e portaria nomeada.
- Foto, código e delegação, aviso efetivo ao morador e retenção (tickets próprios).
