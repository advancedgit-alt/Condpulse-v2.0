# Gestão local do condomínio — API

Referências: PRD cap. 8, 10, 17, 18; CP-014, CP-018, CP-021; MVP fase 3.
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
motivo opcional). A web usa sessão BFF com CSRF; aplicativos usam Bearer.

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

## Pendências

Decisões de reserva pela gestão e telas web ficam nas próximas PRs. V006/V007
ainda não foram aplicadas ao banco remoto: o executor de migrations está fixado
em V003–V005 e exige revisão e autorização próprias. Envio automático do convite
por e-mail depende de SMTP e de credencial administrativa fora da API.
