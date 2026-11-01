# Log de Segurança — CPX-006 a CPX-011 (base)

Referências: PRD complementar Segurança v1.0 (`docs/prd/CondPulse_PRD_Complementar_Seguranca_v1.0.md`, seção 5 e §7.6/E11),
ADR-0011 item 3, `docs/prd/DECISOES_COMPLEMENTAR.md`. Migration: `db/migrations/V050__security_log.sql`.
Flag: `CP_SECURITY_LOG_ENABLED` (API e worker, padrão `false`; não ligada na Vercel nem na Fly).

## O que existe

- `cp.security_log`: trilha única de prova por condomínio. Só acrescenta (R5.1): a role de runtime tem só `INSERT` por
  coluna, sem `seq`, `recorded_at`, autoria nem hashes; `UPDATE`, `DELETE` e `TRUNCATE` são recusados por gatilho até
  para o dono da tabela. Correção é evento novo com `corrects_id` e motivo (R5.2).
- Sequência por condomínio sem buracos e cadeia SHA-256 calculadas no banco sob a trava da linha de
  `cp.security_log_heads` (R5.3). `recorded_at = clock_timestamp()` depois da trava; `occurred_at` é o relógio do banco,
  exceto para eventos de equipamento (horário informado; R5.5).
- `details` em jsonb de até 4 KB, sem chave que sugira biometria em qualquer nível (R5.9). `partner_id` referencia
  `platform.partners` (V049) e é definido pelo banco: parceiro do papel com alcance de quem agiu
  (`platform.partner_reach`); `actor_role` traz o papel de parceiro quando a conta não tem vínculo no condomínio.
- Gravação na mesma transação (R5.4) por gatilhos de restrição `DEFERRABLE INITIALLY DEFERRED` nas fontes. Eles rodam no
  COMMIT, depois das travas de negócio, então a trava do log é sempre a última (sem ciclo de impasse). Se o log falha,
  o COMMIT falha e a operação inteira é desfeita.
- Âncora diária (`platform.security_log_anchors`): o worker grava, por condomínio, o último hash de cada dia fechado
  (últimos 7 dias, fuso America/Sao_Paulo) ainda sem âncora; só acrescenta, e o banco confere que o hash existe no log.
- `cp.security_log_verify(tenant)`: recalcula a cadeia inteira e confere âncoras e cabeça. Problemas: `SEQ_GAP`,
  `PREV_HASH_MISMATCH`, `HASH_MISMATCH`, `ANCHOR_MISMATCH` (reescrita do fim depois de ancorado), `HEAD_MISMATCH`.

## Fontes ligadas nesta fatia

| Fonte | Evento(s) |
| --- | --- |
| V014 registro manual | `ENTRY_RECORDED`, `EXIT_RECORDED`, `EVENT_CORRECTED` (id do log = id do registro de acesso; a correção aponta para ele) |
| V033 ocorrências | `OCCURRENCE_RECORDED` (SECURITY = atenção), `OCCURRENCE_RESOLVED` |
| V026 credenciais | `CREDENTIAL_PUBLISHED` (cadastro), `CREDENTIAL_REVOKED` (com motivo) |
| V027 eventos de equipamento | `ACCESS_AUTHORIZED`, `ENTRY_DENIED`, `RELAY_TRIGGERED`, `DOOR_OPENED`, `PASSAGE_DETECTED`, `CREDENTIAL_APPLIED`, `CREDENTIAL_REJECTED`, `CREDENTIAL_REVOKED`, `DEVICE_ONLINE`, `DEVICE_OFFLINE`, `DEVICE_EVENT_QUARANTINED` |
| V028 saúde | `DEVICE_ONLINE`/`DEVICE_OFFLINE` só na primeira observação e em cada troca |
| V037 concessões técnicas | `TECH_GRANT_ISSUED`, `TECH_GRANT_REVOKED` (únicos eventos aceitos sem condomínio no contexto, com `technical_grants.manage`) |
| V044 revogações | `RIGHTS_REVOKED` |
| V047 publicações | `RIGHTS_PUBLICATION_QUEUED`, `DEVICE_COMMAND_SENT` (ACK), `CREDENTIAL_PUBLISHED` (CONFIRMED), `DEVICE_COMMAND_FAILED` (FAILED/PARTIAL/UNKNOWN) |
| V048 relatórios do gateway | `EDGE_REPORT_RECEIVED` (REJECTED = atenção) |
| Vínculos | `ROLE_GRANTED` (nasce ou passa a ACTIVE), `ROLE_REVOKED` (ACTIVE → SUSPENDED/REVOKED) |
| API, troca de contexto (V052, R4.4) | `CONTEXT_SWITCHED` (ver abaixo) |
| V054 incidentes (CPX-013) | `INCIDENT_OPENED` (severidade do incidente), `INCIDENT_ASSIGNED`, `INCIDENT_CLOSED`, com `correlation_id` = incidente; `EVIDENCE_EXPORTED` no dossiê |
| API, revisão de abertura remota (V054, CPX-014) | `REMOTE_OPEN_REVIEWED` (conferida = INFO, irregular = ATTENTION) |
| V055 permanências (E1, [PERMANENCIAS.md](PERMANENCIAS.md)) | `STAY_OPENED`, `STAY_CLOSED`, `STAY_DUE`, `STAY_OVERDUE_L1`, `STAY_OVERDUE_L2` (worker), `STAY_JUSTIFIED`, `RULE_CHANGED` (regra de tempo) |

Os tipos fora do catálogo inicial do PRD (5.2) são acréscimos para as fontes existentes, sem inferir passagem
(R5.10). Ainda sem gravação: políticas (V043, `POLICY_PUBLISHED`), login/MFA, conector fora do ar, abertura remota
(`REMOTE_OPEN`, CPX-027) e os épicos novos (bloqueio, escala), que gravam ao nascer. Permanências gravam pela V055.

## Troca de contexto (`CONTEXT_SWITCHED`, R4.4, V052)

- **Papéis de segurança** (definição desta fatia, a partir do §4.4 do PRD complementar): papéis de parceiro com alguma
  capacidade `security.*` ou `ops.*` — `PARTNER_ADMIN` (lê o log da carteira), `SECURITY_LEAD`, `SECURITY_SUPERVISOR` e
  `REMOTE_OPERATOR`. `TECHNICIAN` (só `devices.commission`, já registrado por `TECH_GRANT_*`) e os papéis de condomínio
  (portaria, gestão, moradores) ficam fora; a lista está em `cp.security_log_context_roles()` e em
  `ContextSwitchService.SECURITY_ROLES`, conferidas entre si no teste.
- `POST /v1/partners/{partnerId}/context-switches` (`CP_PARTNERS_ENABLED`; AAL2 quando algum lado é papel de segurança):
  a web chama ao entrar no painel de um papel de parceiro, antes da primeira consulta do painel, com a chave da troca
  (`switchId`), o papel de destino e o contexto anterior da janela (`ENTRY`, `PLATFORM`, `TENANT` ou `PARTNER`).
- Na mesma transação: confere conta ativa e papel ativo no parceiro, busca em `platform.partner_reach()` os condomínios
  ativos alcançados agora pelo destino (foco num condomínio fora do alcance → 403 `PARTNER_ACCESS_DENIED`) e, quando a
  origem é outro papel de segurança, os que a origem ainda alcança (fim da visão anterior). Grava um evento por
  condomínio, com o condomínio do contexto trocado a cada evento (política de gravação da V050), em ordem de id.
- `details` só com `from`, `to` (tipo e identificadores: `partnerId`, `role`, `tenantId`) e `side` (`TO`, `FROM` ou
  `BOTH`); `subject_type=PARTNER` e `subject_id` = parceiro do lado gravado; `INFO`; origem `REMOTE_CENTRAL`
  (`MANAGEMENT` para `PARTNER_ADMIN`); `session_id` do token quando houver. Autoria, papel, parceiro e horários vêm do
  banco, como nos demais eventos.
- O banco (gatilho `security_log_a_context_switch`, antes da cadeia) recusa `CONTEXT_SWITCHED` sem ator, fora da API
  (worker/onboarding), com forma diferente da acima ou quando o contexto do lado declarado não é papel de segurança com
  alcance naquele condomínio (`42501`).
- **Idempotência:** o id de cada evento deriva da conta, da chave da troca, do conteúdo (origem e destino) e do
  condomínio. A repetição (duplo clique, nova tentativa depois de falha de rede ou 503) bate na chave primária; a
  transação inteira é desfeita, a cabeça da cadeia não avança e a resposta é `ALREADY_RECORDED`. A web mantém a mesma
  chave enquanto a mesma troca não termina e não chama o servidor ao voltar ao mesmo painel sem outro contexto no meio.
- Respostas: `RECORDED` (n eventos), `ALREADY_RECORDED`, `NOT_REQUIRED` (nenhum lado é papel de segurança, ou origem =
  destino), `NO_TENANT_REACHED` (por exemplo, operador sem escala vigente) e `LOG_DISABLED`: com
  `CP_SECURITY_LOG_ENABLED=false` papel e alcance são conferidos e nada é gravado. Com `CP_PARTNERS_ENABLED=false` a rota
  não existe (404) e a web segue sem registro.
- Web: ao mudar de contexto (condomínio, central ou painel de parceiro, por qualquer caminho), a casca desmonta a tela,
  apaga do cache os dados privados da visão anterior (fica só a decisão da entrada e o estado do segundo fator) e só então
  monta o novo contexto. O painel de parceiro não mostra nenhuma seção nem consulta dados enquanto o registro não
  terminar: falha mostra nova tentativa (mesma chave), recusa vai para o tratador único do painel.
- Limites: o registro é feito pela web; não autoriza nada (cada consulta continua conferida por requisição e as leituras
  sensíveis gravam `SENSITIVE_VIEWED`), e um cliente que não chame a rota só deixa de registrar a própria troca;
  recarregar a página começa com origem `ENTRY`.
- **Saída do painel (CPX-012, sem migration nova; fechava a pendência do PR #161):** `POST /v1/partners/{partnerId}/context-exits` com a chave, o papel
  de origem e o destino (`TENANT` com `tenantId`, ou `PLATFORM`). Para papel de segurança grava `side=FROM` em cada
  condomínio que a origem ainda alcança (o banco confere o alcance do lado `FROM` pela mesma guarda da V052); o id deriva
  também do conteúdo, então entrada e saída com a mesma chave nunca colidem. Mesmas respostas (`RECORDED`,
  `ALREADY_RECORDED`, `NOT_REQUIRED`, `NO_TENANT_REACHED`, `LOG_DISABLED`). A web chama ao montar o condomínio ou a
  central depois de um painel de parceiro, sem segurar o contexto novo (que tem autorização própria), com até três
  tentativas na mesma chave quando o servidor está indisponível; recarregar a página continua começando em `ENTRY`.

## Leitura, exportação e consultas sensíveis (API)

- `GET /v1/tenants/{t}/security-log` (filtros `type`, `severity`, `actorId`, `from`, `until`, `beforeSeq`, `limit`) e
  `GET /v1/tenants/{t}/security-log/integrity`: `security.log.read.tenant` (MANAGER) ou `security.log.read`
  (parceiro), sempre AAL2. A gestão local vê a equipe do parceiro só pelo papel (R5.6).
- `POST /v1/tenants/{t}/security-log/exports`: só `security.log.export` e motivo (10 a 500 caracteres); intervalo
  contíguo de até 5.000 eventos com manifesto e `LOG_EXPORTED` na mesma transação (R5.7).
- `SENSITIVE_VIEWED` na mesma transação da leitura: busca e integridade do log (sempre), histórico de acesso do
  condomínio pela portaria, cadastro de pessoas (sujeitos de acesso) e evidência de idade (com a flag ligada).
- `security.log.read` (PARTNER_ADMIN, SECURITY_LEAD) e `security.log.export` (SECURITY_LEAD) vêm dos papéis de
  parceiro com alcance no condomínio (V049, `PartnerCapability`) e são conferidas de novo por `platform.has_capability`.
  `security.log.read.shift` (supervisor) ainda não tem política no log: recusada até uma fatia própria (log do turno).

## Comando de Segurança: incidentes e aberturas remotas (CPX-012 a CPX-014, V054)

- **Incidentes:** `cp.incidents` grava no log, no COMMIT, `INCIDENT_OPENED`/`INCIDENT_ASSIGNED`/`INCIDENT_CLOSED` com
  `subject_type=INCIDENT`, `correlation_id` = incidente e `details` só com severidade, responsável (id do membro),
  desfecho e ids dos eventos anexados — nunca título nem relatório. A linha do tempo é montada a partir do log (eventos
  correlacionados + anexados), e lê-la grava `SENSITIVE_VIEWED` (`view=INCIDENT_TIMELINE`, `incidentId`, `count`).
- **Revisão de abertura remota:** `REMOTE_OPEN_REVIEWED` só pela API (`cp_api`, com ator) e por quem tem
  `security.remote_open.review` com alcance no condomínio (gatilho `security_log_a_remote_open_review`, antes da cadeia).
  Forma fechada: `subject_type=SECURITY_LOG_EVENT`, `subject_id` = um `REMOTE_OPEN` do mesmo condomínio e legível por quem
  revisa, `details` só `{verdict}` (`CONFERRED`→INFO, `IRREGULAR`→ATTENTION), origem `REMOTE_CENTRAL`, motivo obrigatório
  para irregular. Uma revisão por abertura (índice único parcial); mudar é correção (`corrects_id` = revisão mais recente
  da mesma abertura, com motivo; uma correção por revisão, também por índice único). Quem abriu não revisa a própria
  abertura (`42501`). A lista de aberturas grava `SENSITIVE_VIEWED` (`view=REMOTE_OPEN_REVIEW`).
- **Visão da carteira:** só contagens (`cp.security_command_summary`), sem conteúdo de evento nem dado pessoal; por isso não
  grava `SENSITIVE_VIEWED`. Critério do semáforo em [PARCEIROS.md](PARCEIROS.md#visão-da-carteira--critério-do-semáforo-cpx012-v1).

### Dossiê de incidente (`cpdx1`)

`POST /v1/partners/{p}/command/tenants/{t}/incidents/{i}/dossier` segue as regras da exportação (R5.7): só
`security.log.export` (líder com alcance), motivo de 10 a 500 caracteres e `EVIDENCE_EXPORTED` na mesma transação, com
`correlation_id` = incidente e `details` com `count`, `firstSeq`, `lastSeq`, `eventsDigest` e `canonicalization`. Antes de
exportar, cada evento da linha do tempo é conferido no banco (digest `cpsl1` recalculado igual ao hash gravado e
`prev_hash` igual ao hash do evento anterior do condomínio); elo quebrado responde 409 `SECURITY_LOG_CHAIN_BROKEN` e nada
sai. Quem exporta é equipe do parceiro: os eventos saem sem a máscara da gestão local, como na exportação do log
(`maskedForTenant` só vale para `security.log.read.tenant`). O manifesto traz:

`eventsDigest = hex(sha256(utf8("cpdx1|" + tenantId + "|" + incidentId + "|" + seq1 + ":" + hash1 + "|" + seq2 + ":" + hash2 ...)))`

com os eventos em ordem de seq. Cada `hash` pode ser recalculado pela forma `cpsl1` abaixo e conferido contra a cadeia e
as âncoras; o incidente (título, relatório) vai junto no arquivo, mas não é encadeado.

## Forma canônica do hash (`cpsl1`)

`hash = hex(sha256(utf8("cpsl1|" + prev_hash + "|" + J)))`, com `prev_hash` do evento anterior do mesmo condomínio
(64 zeros no primeiro) e `J` o texto do `jsonb_build_array` (saída do PostgreSQL) de: `id`, `tenant_id`, `partner_id`,
`seq`, `occurred_at` e `recorded_at` em UTC (`YYYY-MM-DDTHH:MI:SS.ffffffZ`), `clock_skew_ms`, `event_type`, `severity`,
`source`, `actor_account_id`, `actor_role`, `session_id`, `subject_type`, `subject_id`, `unit_id`, `point_id`,
`device_id`, `reason`, `evidence_ref`, `correlation_id`, `trace_id`, `corrects_id`, `details`. O harness PostgreSQL
recalcula um hash fora do banco com essa regra.

## Condomínio demo (V053)

- Criar o demo grava no log do demo `ROLE_GRANTED` (vínculos sintéticos), `OCCURRENCE_*`, `PARTNER_CONTRACT_CHANGED`,
  `SCHEDULE_ASSIGNED` e eventos de exemplo (`SHIFT_STARTED`, `CHECKLIST_DONE`, `PATROL_CHECKPOINT`, `INCIDENT_OPENED` pelo
  operador sintético em escala; `DOOR_HELD_OPEN`, `DEVICE_OFFLINE`, `DEVICE_ONLINE` sem ator), com a mesma cadeia e autoria
  do banco. O reset grava `PARTNER_CONTRACT_CHANGED` (encerramento) no log da geração aposentada. Nenhum log real é tocado.

## Limites e pendências

- Retenção indefinida até decisão do responsável (DECISOES_COMPLEMENTAR.md): nenhuma rotina de expurgo.
- `platform.partner_security_log` (eventos só de parceiro, sem condomínio) e o log do turno ficam para depois.
- A gravação pelas fontes é do banco: aplicar a V050 na homologação liga a gravação mesmo com a flag desligada (a flag
  controla a API, as consultas sensíveis e a âncora). A V050 não foi aplicada em nenhum banco remoto.
- Intervenção de superusuário com `session_replication_role=replica` não grava no log (gatilhos desligados); a
  verificação de integridade detecta a alteração do que já estava gravado.
- A verificação percorre a cadeia inteira a cada chamada (custo linear); otimização a partir da última âncora fica
  para quando houver volume real.
