# Permanências e saída (E1 — CPX-015 a CPX-019)

Referências: PRD complementar Segurança v1.0 (`docs/prd/CondPulse_PRD_Complementar_Seguranca_v1.0.md`, §7.2/E1, §5.2 e
fluxo §8.1), ADR-0011, [LOG_SEGURANCA.md](LOG_SEGURANCA.md), [PARCEIROS.md](PARCEIROS.md). Migration:
`db/migrations/V055__stays.sql`. API atrás de `CP_VISITS_ENABLED` (mesma flag e módulo `visits` do registro de entrada e
saída). Worker de alertas atrás de `CP_WORKER_STAYS_ENABLED` (padrão `false`). **HML conferida em 01/11/2026: V055 aplicada e repetida sem nova migration; alertas ligados no worker (run 41354940226). Produção operacional não foi alterada.** Consulte [ESTADO_ATUAL.md](ESTADO_ATUAL.md) para os recibos e as jornadas ainda não aceitas.

## Fontes de entrada conferidas no código

| Fonte | Abre permanência? | Motivo |
| --- | --- | --- |
| V014 registro manual (`MANUAL_IN`) por chegada autorizada (V013) | Sim, `basis=ARRIVAL` | Entrada da portaria com pessoa e unidade |
| V014 por convite (V013) | Sim, `basis=PERMIT` | Idem |
| V014 por janela de prestador recorrente (V022, convite com `rule_id`) | Sim, `basis=RULE`, sempre `SERVICE_PROVIDER` | Idem |
| V027 eventos de equipamento | **Não** | A credencial (V026) é sempre de vínculo de morador (`membership_id` obrigatório) e o evento não identifica visitante; inferir presença de passagem é proibido (R5.10). Fica para E1.8, quando existir credencial facial/QR de visitante. |

Entradas registradas antes da V055 não ganham permanência (sem backfill: a permanência só nasce da entrada gravada na
mesma transação).

## Regras (banco)

- `cp.stays`: aberta por gatilho na mesma instrução da entrada; unidade, pessoa, tipo, base, quem autorizou (autor do
  convite/regra ou quem decidiu a chegada), horários e prazos vêm do banco. `origin` = `MANUAL` nesta versão (catálogo
  aceita `FACIAL`, `QR`, `LINK`).
- Saída prevista (E1.2): convite → fim do convite; janela de prestador → fim da janela; chegada sem convite → entrada +
  tempo máximo do tipo. Tipo informado pela portaria na entrada (`stayKind`: `VISITOR`, `SERVICE_PROVIDER`, `DELIVERY`,
  `CONSTRUCTION`; padrão `VISITOR`).
- Tempo máximo e tolerâncias N1/N2 por tipo: regra do condomínio (`cp.stay_rules`, gestão com `policy.edit`, AAL2,
  versionada, `RULE_CHANGED` no log) ou **padrão provisório da plataforma** (`cp.stay_default_rule`: visitante 240 min,
  prestador 480, entregador 30, obra 600; N1 +15 min, N2 +60 min). Os valores padrão são técnicos, **a confirmar no
  piloto** (PRD §13 "Definir no piloto"). As tolerâncias ficam fixadas na abertura.
- Transições conferidas contra a fonte: saída manual válida fecha (`EXIT_RECORDED`); correção da entrada anula
  (`VOIDED`/`ENTRY_CORRECTED`); correção da saída reabre; confirmação do morador fecha (`RESIDENT_CONFIRMED`, só
  RESIDENT/RESPONSIBLE com vínculo vigente na unidade, `cp.visit_member`); justificativa uma vez, com motivo, pela
  portaria ou equipe do parceiro com alcance (encerra a escalada; a permanência segue aberta até a saída). Campos de
  abertura nunca mudam; nada é apagado (gatilho, inclusive para o dono).
- Alertas (CPX-016): `cp.stay_alert_pass()` (cp_worker, por condomínio) marca `STAY_DUE` (no horário previsto),
  `STAY_OVERDUE_L1` e `STAY_OVERDUE_L2` uma vez cada, nunca antes do horário (o banco confere), só aberta e sem
  justificativa; repetição e passadas concorrentes não gravam de novo (trava da linha). Tolerância de entrega = intervalo
  da passada (`CP_WORKER_STAYS_MILLIS`, 10 s a 10 min; padrão 60 s); se o worker ficou parado, os níveis vencidos são
  gravados juntos, em ordem, com `delayMs` em `details`. O worker lê só horários e estado (sem pessoa nem unidade).
- RLS: portaria (`concierge.operate`), parceiro com alcance (`security.command`, `ops.shift.supervise`,
  `ops.remote.operate`, conferidas por `platform.has_capability`/`partner_reach`) e a unidade (adulto ou dependente com
  `visits.decide`); proprietário de unidade alugada não vê (`rental_owner_hidden`). Quem vê a permanência vê o nome da
  pessoa (`visitors_read_scope` ampliada).

## Log de Segurança (§5.2)

`STAY_OPENED` (INFO, portaria; reabertura com `reopened=true`, ATTENTION), `STAY_CLOSED` (INFO; anulação ATTENTION;
origem `RESIDENT_APP` na confirmação do morador), `STAY_DUE` (INFO, SYSTEM), `STAY_OVERDUE_L1`/`L2` (ATTENTION, SYSTEM),
`STAY_JUSTIFIED` (INFO, motivo; CONCIERGE ou REMOTE_CENTRAL), `RULE_CHANGED`. Mesma transação (gatilho adiado ao COMMIT),
`subject_type=STAY`, `correlation_id` = permanência, `details` só com identificadores, tipo, base e horários.
Leituras da equipe gravam `SENSITIVE_VIEWED` (`STAYS_NOW`, `STAY_DAY_CLOSE` com o hash da lista).

## API

| Rota | Quem |
| --- | --- |
| `GET /v1/tenants/{t}/stays?view=open\|overdue` (CPX-017) | Portaria ou parceiro com alcance, AAL2 |
| `GET /v1/tenants/{t}/stays/day-close?date=` (CPX-019; hoje até 31 dias atrás, fuso do condomínio) | Idem |
| `POST /v1/tenants/{t}/stays/{id}/justification` | Idem |
| `GET /v1/tenants/{t}/units/{u}/stays` | Unidade (adulto ou dependente com `visits.decide`) |
| `POST /v1/tenants/{t}/units/{u}/stays/{id}/exit-confirmation` (CPX-018) | Morador/responsável da unidade |
| `GET /v1/tenants/{t}/stay-rules`, `PUT /v1/tenants/{t}/stay-rules/{kind}` | Gestão (`policy.edit`), AAL2 |

`POST .../access-events/entries` aceita `stayKind` opcional.

## Web

- Portaria (`/c/{t}/portaria/visitas`): "Quem está no condomínio agora" (vencidas primeiro e em destaque, origem de cada
  dado, justificativa com motivo), relatório de fechamento do dia sob pedido com impressão; tipo de permanência no
  registro de entrada. Sem consulta automática (cada leitura grava `SENSITIVE_VIEWED`).
- Morador (`/c/{t}/morador/{u}/acessos`): permanências abertas da unidade e "Confirmar que saiu" (adulto).
- Parceiro: seção "Permanências" no comando, turno e console, por condomínio alcançado, só leitura.

## Pendências

- Herança do padrão do parceiro (E1.2): sem função `SECURITY DEFINER`, a portaria não enxerga o contrato do parceiro;
  precisa de decisão (materializar o padrão no condomínio ao publicar, ou expor o parceiro operador do condomínio).
- Valores padrão de tempo e tolerância: decisão no piloto.
- Avisos N1 ao morador (push) e N2 ao supervisor fora da tela: dependem do Expo/push e da fila do console (E2).
- Passagem de turno com permanências abertas como pendência obrigatória (E1.6/E3).
- Saída automática por facial nas duas direções (E1.8) e permanência por facial/QR: dependem de equipamento real.
- Tela da gestão para editar as regras de tempo (só API nesta fatia); justificativa pelo painel do parceiro (API pronta,
  web só leitura).
- Worker de HML já ligado após V055, por PR #174 revisado e deploy 41354940226. Ensaio real de entrada/alerta/saída ainda exige os perfis operacionais ativos e dados apropriados; flag ligada não comprova essa jornada.
