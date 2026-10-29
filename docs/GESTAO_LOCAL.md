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

## Pendências

Vínculos/moradores, convites, decisões de reserva pela gestão e telas web ficam
nas próximas PRs. A V006 ainda não foi aplicada ao banco remoto: o executor de
migrations está fixado em V003–V005 e exige revisão e autorização próprias.
