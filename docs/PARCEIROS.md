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
  conferido antes de qualquer consulta. Prontas: equipe, escala e carteira (gestão), Log de Segurança por condomínio
  alcançado (`security.log.read`; exportação só com `security.log.export`) e o Comando de Segurança (abaixo). Turno,
  console e técnico aparecem "em breve", sem dado. Flags desligadas no servidor (404) e 503 têm mensagem própria.

## Troca de contexto (R4.4, V052)

- `POST /v1/partners/{id}/context-switches`: a web chama ao entrar no painel de um papel de parceiro, antes da primeira
  consulta. Para papéis de segurança (`PARTNER_ADMIN`, `SECURITY_LEAD`, `SECURITY_SUPERVISOR`, `REMOTE_OPERATOR`; não
  `TECHNICIAN`), grava `CONTEXT_SWITCHED` no Log de Segurança de cada condomínio alcançado agora pelo destino e, quando a
  origem é outro papel de segurança, de cada condomínio que a origem ainda alcança, na mesma transação, com AAL2. O
  banco (V052) confere o alcance de novo em cada evento. Regras, idempotência e respostas com flags desligadas em
  [LOG_SEGURANCA.md](LOG_SEGURANCA.md#troca-de-contexto-context_switched-r44-v052).
- Web: trocar de contexto (condomínio, central ou painel) encerra a visão anterior — a tela sai e os dados privados dela
  saem do cache antes do novo contexto montar; o painel não consulta nada até o registro terminar.
- `POST /v1/partners/{id}/context-exits` (CPX-012, fecha a pendência do PR #161): sair de um painel de parceiro direto para
  um condomínio por vínculo ou para a central grava o lado da origem (`side=FROM`) em cada condomínio que o papel de origem
  ainda alcança. A web chama ao montar o novo contexto, sem segurá-lo (até três tentativas com a mesma chave quando o
  servidor está indisponível).

## Comando de Segurança (CPX-012 a CPX-014, V054)

Painel `/p/{parceiro}/comando` do gestor líder (`SECURITY_LEAD`, capacidade `security.command`, AAL2). Rotas
`/v1/partners/{id}/command/...` só existem com `CP_PARTNERS_ENABLED` **e** `CP_SECURITY_LOG_ENABLED` ligadas (as duas já
ligadas na homologação; a V054 ainda não foi aplicada em nenhum banco remoto). Tudo roda na transação do parceiro
(conta ativa, papel ativo, AAL2); cada condomínio só entra no contexto depois de conferido no alcance de líder agora, e o
banco confere o alcance de novo em cada política (`cp.partner_reach_grants`, `cp.incident_command`, sobre
`platform.partner_reach()`), não só a capacidade publicada.

### Visão da carteira — critério do semáforo `cpx012-v1`

Um condomínio por contrato ativo do parceiro (até 200). Contagens de `cp.security_command_summary()` (V054), só números,
sem dado pessoal (por isso a carteira não grava `SENSITIVE_VIEWED`):

| Indicador | Fonte | Cor |
| --- | --- | --- |
| Alerta crítico sem incidente | Log de Segurança (V050): evento `CRITICAL` gravado nas últimas 24 h que nenhum incidente do parceiro anexou (eventos `INCIDENT_*` não contam) | **Vermelho** |
| Incidente crítico aberto | `cp.incidents` (V054), severidade `CRITICAL`, estado `OPEN` | **Vermelho** |
| Equipamento fora do ar | `cp.access_devices` ativos + `cp.access_device_health` (V026/V028): informou fora do ar ou sinal mais velho que 5 min (mesma regra de `/access-health`) | Amarelo |
| Equipamento sem informação recente | idem: conector inativo, sem resposta ou sem verificação nos últimos 10 min, ou equipamento sem observação recente/sem sinal (nunca "no ar" presumido) | Amarelo |
| Central sem operador escalado | `cp.operator_schedules` (V049): nenhum operador remoto ou supervisor do parceiro com papel e conta ativos e escala vigente agora (mesma regra de `platform.partner_reach()`; conta suspensa ou encerrada não cobre a central) | Amarelo |
| Ocorrência de segurança aberta | `cp.concierge_occurrences` (V033), categoria `SECURITY`, estado `OPEN` | Amarelo |
| Incidente aberto (não crítico) | `cp.incidents`, severidade `ATTENTION` | Amarelo |
| Aberturas remotas sem revisão (7 dias) | Log: `REMOTE_OPEN` sem `REMOTE_OPEN_REVIEWED` | sem cor (revisão por amostragem, §8.2) |

Verde: nenhum item amarelo ou vermelho. A lista vem do mais grave para o mais leve e mostra os motivos. **Sem fonte nesta
versão** (aparecem como indisponíveis, nunca como zero): portas abertas (`DOOR_HELD_OPEN` não é gravado por nenhuma fonte;
`DOOR_OPENED` do equipamento não informa fechamento) e permanências vencidas (`cp.stays`, CPX-015, ainda não existe).
Leitura nova concedida ao líder com alcance (só `SELECT`): equipamentos, conectores e saúde (V026/V028/V048/V051) e
ocorrências (a política restritiva da V033 foi separada por comando: só a leitura ganhou o líder; gravação e encerramento
seguem como antes); escala e equipe do próprio parceiro (mesmo padrão da leitura do gestor, V049).

### Incidentes (CPX-013)

- `cp.incidents` (condomínio, parceiro que abriu, título, severidade `ATTENTION`/`CRITICAL`, responsável, desfecho
  `RESOLVED`/`FALSE_ALARM`/`REFERRED` e relatório) e `cp.incident_links` (eventos do log anexados, até 50, só em incidente
  aberto). Nada é apagado; encerrar é final; parceiro, autoria e horários vêm do banco. Só o líder do mesmo parceiro com
  contrato ativo lê e muda, sempre no contexto desse parceiro (parceiro que assumir o condomínio depois não vê os incidentes do anterior; a mesma conta líder em dois parceiros, no painel de um, não vê os do outro).
- Responsável: líder, supervisor ou operador ativo do parceiro (o banco recusa gestor, técnico e outro parceiro).
- Log (mesma transação, gatilho adiado ao COMMIT): `INCIDENT_OPENED` (severidade do incidente, eventos anexados),
  `INCIDENT_ASSIGNED`, `INCIDENT_CLOSED` (desfecho e eventos anexados), todos com `correlation_id` = incidente. Título e
  relatório ficam fora do log.
- Linha do tempo = eventos do log com o `correlation_id` do incidente + eventos anexados, em ordem de seq (até 500); ler
  grava `SENSITIVE_VIEWED` (`INCIDENT_TIMELINE`). Dossiê: ver [LOG_SEGURANCA.md](LOG_SEGURANCA.md#dossiê-de-incidente-cpdx1).

### Revisão de aberturas remotas (CPX-014)

Lista por condomínio dos `REMOTE_OPEN` do log (operador, papel, motivo, ponto, equipamento, evidência, horários e a
revisão mais recente), com `SENSITIVE_VIEWED` (`REMOTE_OPEN_REVIEW`). Marcar como conferida ou irregular grava
`REMOTE_OPEN_REVIEWED` (regras em LOG_SEGURANCA.md). Nenhuma fonte grava `REMOTE_OPEN` ainda (comandos remotos pelo
gateway, CPX-027): a lista fica vazia até lá.

### Web

Seções "Visão da carteira", "Incidentes", "Revisão de aberturas" e "Log de Segurança" prontas; o líder entra na visão da
carteira. Políticas e lista de bloqueio seguem "em breve". Leituras de eventos do log (linha do tempo, busca de eventos para
anexar, aberturas remotas) só por pedido. Recusas vão ao tratador único (`scope.refuse`); recusa de condomínio apaga do
cache o log dele e todos os dados do comando.

### Fora desta fatia

"Abrir o condomínio em modo leitura", "acionar supervisor", alertas críticos ao vivo (push/tempo real), anexo de arquivo
como evidência (hoje a evidência é um evento do log anexado), permanências, turnos, desempenho, lista de bloqueio e
políticas do §4.3.4.

## Pendências

- Parceiro piloto (responsável): nenhum parceiro real cadastrado; só dados sintéticos nos testes.
- Convite de equipe por e-mail: hoje o papel é concedido a uma conta existente pelo identificador.
- Capacidades de condomínio para operador remoto (portaria, entrada/saída manual) ficam para E2; nesta fatia a equipe
  do parceiro não ganha nenhuma capacidade de equipe do condomínio.
- Os eventos de parceiro (equipe, contrato, escala) vão para a trilha da plataforma; o Log de Segurança por condomínio
  está em [LOG_SEGURANCA.md](LOG_SEGURANCA.md).
- Web: painéis de turno, console e técnico dependem do backend de E2/E11; homologação visual com dados reais (inclusive do
  Comando) depende da V054 aplicada e de login real com AAL2.
- Troca de contexto: a saída é registrada pela web sem segurar o contexto novo; se as três tentativas falharem, a saída fica
  sem registro (a entrada seguinte num painel de parceiro continua registrada).
- Outros tipos de contrato (integradora) aguardam decisão; só `SECURITY_OPERATOR` existe.
