# Hierarquia de parceiro (E0 — CPX-001 a CPX-003)

Referências: [ADR-0011](adr/0011-hierarquia-parceiro-e-modulo-seguranca.md), PRD complementar
([§3, §4.4 e §7.1](prd/CondPulse_PRD_Complementar_Seguranca_v1.0.md)), [decisões e pendências](prd/DECISOES_COMPLEMENTAR.md).
Migration: `db/migrations/V049__partner_hierarchy.sql`. Flag: `CP_PARTNERS_ENABLED` (padrão `false`; as rotas da
central exigem também `CP_PLATFORM_ENABLED`).

## Dados

| Tabela | Conteúdo | Muda como |
| --- | --- | --- |
| `platform.partners` | Parceiro | `ACTIVE` ↔ `SUSPENDED` pela versão; nome fixo |
| `platform.partner_members` | Papel da conta no parceiro (`PARTNER_ADMIN`, `SECURITY_LEAD`, `SECURITY_SUPERVISOR`, `REMOTE_OPERATOR`, `TECHNICIAN`) | Revogação final |
| `platform.partner_tenant_contracts` | Contrato parceiro × condomínio (`SECURITY_OPERATOR`), vigência | Encerramento final; no máximo um operador de segurança por condomínio em cada instante (R3.4, restrição de exclusão) |
| `cp.operator_schedules` | Escala operador/supervisor × condomínio × janela (até 31 dias) | Cancelamento final; sem sobreposição do mesmo operador no mesmo condomínio |

Nada é apagado (sem `DELETE` para a aplicação; `DatabaseRoleGuard` recusa grants indevidos). Toda gravação exige o
evento `PARTNER_*` da trilha da plataforma, do mesmo ator e versão, conferido no `COMMIT`.

## Regras no banco

- `platform.partner_reach()`: condomínios que o ator alcança agora — parceiro ativo, papel ativo, conta ativa, contrato
  ativo e vigente e, para `REMOTE_OPERATOR` e `SECURITY_SUPERVISOR`, escala vigente (R3.1). Gestor do parceiro, líder de
  segurança e técnico alcançam todo condomínio com contrato ativo, sem escala (R3.2). Encerrar contrato, suspender
  parceiro, revogar papel ou cancelar escala corta o acesso na instrução seguinte (R3.3).
- `platform.tenants` e `platform.tenant_modules` ganham leitura pelo alcance; contratos só aparecem para gestor, líder e
  técnico do parceiro (carteira) ou para quem tem escala no condomínio.
- `platform.has_capability`: as capacidades antigas seguem só pela publicação da API; as 14 do §4.4 exigem também a
  conferência (`platform.capability_verified`): papel com alcance no condomínio do contexto (o técnico ainda precisa de
  concessão técnica vigente da V037 para `devices.commission`), papel no parceiro do contexto (`app.partner_id`, exceto
  operação no condomínio) ou, para `security.blocklist.approve` e `security.log.read.tenant`, vínculo `MANAGER` vigente.
- Matriz papel × capacidade em `platform.partner_role_capabilities` = `PartnerCapability` (Java), conferida em teste.

## API

- Central (papel de plataforma, `partners.read`/`partners.manage`, AAL2): `/v1/platform/partners` e subrotas (criar,
  suspender/reativar, vincular/desvincular condomínio, equipe e escala).
- Gestor do parceiro (`partner.team.manage`/`partner.manage`, AAL2): `/v1/partners/{id}/team`, `/schedules`,
  `/portfolio`. Nunca altera o próprio papel.
- Executor do condomínio: sem vínculo, a conta entra pelo alcance de parceiro e recebe só as capacidades do papel
  (nenhuma capacidade de equipe do condomínio); equipe de parceiro sem alcance recebe 403 `PARTNER_ACCESS_DENIED`.
- `/v1/me`: campo aditivo `partnerContexts` (papel, painel `/p/{parceiro}/{gestao|comando|turno|console|tecnico}`,
  capacidades e condomínios alcançados) para a `/entrada` (CPX-004/005).
- Web (CPX-004/005, fatia web): `/entrada` conta cada papel de parceiro como um contexto (um só → painel direto; vários →
  cartões "Parceiro – Painel"); casca `/p/{parceiro}/{painel}/{seção}` com seções geradas pelas capacidades (R4.5), AAL2
  conferido antes de qualquer consulta. Prontas: equipe, escala e carteira (gestão) e Log de Segurança por condomínio
  alcançado (`security.log.read`; exportação só com `security.log.export`). Comando (exceto o log), turno, console e
  técnico aparecem "em breve", sem dado. Flags desligadas no servidor (404) e 503 têm mensagem própria.

## Troca de contexto (R4.4, V052)

- `POST /v1/partners/{id}/context-switches`: a web chama ao entrar no painel de um papel de parceiro, antes da primeira
  consulta. Para papéis de segurança (`PARTNER_ADMIN`, `SECURITY_LEAD`, `SECURITY_SUPERVISOR`, `REMOTE_OPERATOR`; não
  `TECHNICIAN`), grava `CONTEXT_SWITCHED` no Log de Segurança de cada condomínio alcançado agora pelo destino e, quando a
  origem é outro papel de segurança, de cada condomínio que a origem ainda alcança, na mesma transação, com AAL2. O
  banco (V052) confere o alcance de novo em cada evento. Regras, idempotência e respostas com flags desligadas em
  [LOG_SEGURANCA.md](LOG_SEGURANCA.md#troca-de-contexto-context_switched-r44-v052).
- Web: trocar de contexto (condomínio, central ou painel) encerra a visão anterior — a tela sai e os dados privados dela
  saem do cache antes do novo contexto montar; o painel não consulta nada até o registro terminar.

## Parceiro do condomínio demo (V055)

- O demo da central (CPX-046, [ROTEIRO_TESTES_HML.md](ROTEIRO_TESTES_HML.md)) cria um parceiro `[DEMO]` com equipe sintética,
  contrato vigente e escala. Parceiro demo só contrata condomínio demo (e vice-versa); conta sintética só tem papel em
  parceiro demo. A central concede papel no parceiro demo a uma conta real pela aba "Condomínio demo" (mesma API
  `/v1/platform/partners/{id}/members`).

## Pendências

- Parceiro piloto (responsável): nenhum parceiro real cadastrado; só dados sintéticos nos testes.
- Convite de equipe por e-mail: hoje o papel é concedido a uma conta existente pelo identificador.
- Capacidades de condomínio para operador remoto (portaria, entrada/saída manual) ficam para E2; nesta fatia a equipe
  do parceiro não ganha nenhuma capacidade de equipe do condomínio.
- Os eventos de parceiro (equipe, contrato, escala) vão para a trilha da plataforma; o Log de Segurança por condomínio
  está em [LOG_SEGURANCA.md](LOG_SEGURANCA.md).
- Web: painéis de comando, turno, console e técnico dependem do backend de E2/E11; homologação visual com dados reais
  depende das flags ligadas.
- Troca de contexto: a saída de um painel de segurança direto para um condomínio por vínculo ou para a central ainda não
  grava o lado da origem.
- Outros tipos de contrato (integradora) aguardam decisão; só `SECURITY_OPERATOR` existe.
