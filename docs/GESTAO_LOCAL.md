# Gestão local do condomínio — API

Referências: PRD cap. 8, 10, 17, 18; CP-014, CP-018, CP-020, CP-021, CP-023; MVP fase 3.
Flag: `CP_MANAGEMENT_ENABLED` (padrão `false`, inclusive na Vercel).

## Autorização em duas barreiras

1. Serviço Java: conta ativa, vínculo vigente no condomínio, papel com a
   capacidade (`MANAGER`) e token `aal2`. Sem AAL2: `403 MFA_REQUIRED`.
   Sem papel: `403 STAFF_ACCESS_DENIED`. Outro condomínio: `404`.
2. Banco (V006): políticas RLS restritivas exigem `platform.has_capability(...)`
   para inserir/atualizar blocos, unidades e áreas e para ler auditoria. A
   capacidade só existe no contexto local da transação publicado pelo executor.
   Não há `DELETE`: unidades e áreas são inativadas.

## Rotas

| Rota | Capacidade | Observação |
| --- | --- | --- |
| `GET/POST .../management/blocks` | `units.manage` | Nome único por condomínio |
| `GET/POST .../management/units` | `units.manage` | Identificação única por bloco |
| `PUT .../management/units/{id}` | `units.manage` | `expectedVersion`; inativar bloqueia os vínculos da unidade na hora |
| `GET/POST .../management/amenities` | `amenities.manage` | Regra explícita, sem valores padrão permissivos |
| `PUT .../management/amenities/{id}` | `amenities.manage` | Mudança de regra cria nova `policyVersion`; reservas mantêm o retrato aceito |
| `GET .../management/audit-events` | `audit.read` | Cursor opaco, 30 por página (máx. 100) |

Toda alteração grava `cp.audit_log` na mesma transação (ator, ação, recurso,
motivo opcional), com uma ação por mudança: nova regra, inativação e renomeação
na mesma gravação geram três registros. Valores anteriores e novos ainda não são
gravados na auditoria. A web usa sessão BFF com CSRF; aplicativos usam Bearer.

No banco, `UPDATE` sem a capacidade é recusado com `42501` (403 na API) em vez de
não encontrar linhas. Blocos não têm `UPDATE` concedido. O `cp_api` ainda pode
gravar qualquer valor em `version`/`policy_version`: o incremento é garantido pela
API, não por gatilho no banco (pendência registrada).

## Antes de aplicar a V006 em banco com dados

A V006 cria `amenities_tenant_name` (nome único sem diferenciar maiúsculas) e
limita nomes de áreas (1–120) e blocos (1–60). O manifesto de onboarding foi
alinhado a esses limites e aos da gestão (capacidade até 10000, taxa até
10000000 centavos). Antes do Flyway remoto, rodar a consulta somente leitura
abaixo; qualquer linha retornada exige correção aprovada antes da migration:

```sql
select 'amenity_duplicate' as problem,tenant_id::text,lower(btrim(name)) as value,count(*)::text as detail
from cp.amenities group by tenant_id,lower(btrim(name)) having count(*)>1
union all
select 'amenity_name_length',tenant_id::text,id::text,length(btrim(name))::text
from cp.amenities where length(btrim(name)) not between 1 and 120
union all
select 'block_name_length',tenant_id::text,id::text,length(btrim(name))::text
from cp.blocks where length(btrim(name)) not between 1 and 60
union all
select 'amenity_limits',tenant_id::text,id::text,capacity::text||'/'||fee_cents::text
from cp.amenities where capacity>10000 or fee_cents>10000000;
```

## Vínculos e convites (V007)

| Rota | Capacidade | Observação |
| --- | --- | --- |
| `GET .../management/memberships?status=` | `memberships.manage` | Sem e-mail ou conta Auth |
| `POST .../memberships/{id}/decision` | `memberships.manage` | `APPROVE`→ACTIVE, `REJECT`→REVOKED (motivo obrigatório); versão lida |
| `POST .../memberships/{id}/revoke` | `memberships.manage` | Motivo obrigatório; evento `MEMBERSHIP_REVOKED`; nega o próprio vínculo de gestão e o último gestor ativo |
| `GET/POST .../management/invitations` | `memberships.manage` | Papéis residenciais e `CONCIERGE`; nunca `MANAGER` |
| `POST .../invitations/{id}/revoke` | `memberships.manage` | Somente convite pendente |
| `POST /v1/invitations/accept` | autenticado | Convidado com o mesmo e-mail cria só o próprio vínculo `PENDING` |

O convite tem validade de 1 a 14 dias. O token (32 bytes) aparece uma vez para a
gestão compartilhar pelo canal combinado; o banco guarda só o hash do token e o
hash do e-mail ligado ao convite. O aceite cria o espelho local da conta Auth já
existente, nunca usuário no provedor: o cadastro no Supabase Auth continua sendo
operação administrativa (cadastro público desligado). O vínculo só opera após
aprovação da gestão. No banco, o `INSERT` do convidado exige convite válido no
contexto local (`app.invitation_hash`), mesmo papel/unidade/nome e status
`PENDING`.

## Pedidos, decisões e bloqueios de reserva (V008)

| Rota | Capacidade | Observação |
| --- | --- | --- |
| `GET .../management/reservations?status=&from=&until=` | `reservations.decide` | Sem período: ainda não encerradas; com período: até 366 dias; até 200 itens. Pedido com prazo vencido aparece como `EXPIRED` |
| `POST .../reservations/{id}/decision` | `reservations.decide` | `APPROVE`/`REJECT` (motivo obrigatório na rejeição) pela versão lida. Repetição devolve o estado atual sem novo evento; decisão oposta recebe 409. Prazo vencido grava `EXPIRED` e responde 409 `RESERVATION_DECISION_EXPIRED` |
| `POST .../reservations/{id}/cancel` | `reservations.decide` (+ `amenities.manage` para bloqueio) | Motivo obrigatório; reserva confirmada, pedido pendente ou bloqueio ainda não encerrado; repetição sem novo evento |
| `POST .../amenities/{id}/maintenance-blocks` | `amenities.manage` | `Idempotency-Key`; período de até 366 dias a partir de agora; 409 `AMENITY_BLOCK_CONFLICT` enquanto houver pedido ou reserva válida no período |

Fluxo do morador: área com aprovação gera `PENDING_APPROVAL` com prazo de decisão
de até 24 horas, nunca depois do início (prazo fixo; configuração por condomínio
pendente). O pedido ocupa o período e conta na cota, mas não é confirmação. O
autor pode retirá-lo até o início; o prazo de cancelamento aceito continua valendo
para reservas confirmadas. A criação expira, sob a trava da unidade, os pedidos
vencidos da própria unidade e os que ocupam o período pedido; pedido vencido não
aparece como ocupação nem como pendente (AC-013). A aprovação exige área ativa,
unidade ativa e solicitante ainda apto a reservar pela unidade. Toda decisão grava
auditoria (ator e motivo) e evento na mesma transação; o motivo aparece para a
unidade.

No banco (V008), políticas restritivas: confirmar ou rejeitar exige
`reservations.decide`; área que exige aprovação nunca recebe `BOOKING` já
`CONFIRMED` por `INSERT`; inserir ou alterar bloqueio exige `amenities.manage`;
motivo com até 500 caracteres.

Pendências deste fluxo: revogar convites de visitantes vinculados na mesma
transação do cancelamento (o módulo de visitantes ainda não existe; o evento
`RESERVATION_CANCELLED` já é gravado junto); retenções `HELD` e fila de espera;
prazo de decisão configurável; worker de expiração (hoje a expiração acontece na
criação seguinte e na decisão tardia); notificações.

## Pendências

Telas web de gestão ficam nas próximas PRs. V006–V008 ainda não foram aplicadas
ao banco remoto: o executor de migrations está fixado em V003–V005 e exige
revisão e autorização próprias. Envio automático do convite por e-mail depende de
SMTP e de credencial administrativa fora da API.
