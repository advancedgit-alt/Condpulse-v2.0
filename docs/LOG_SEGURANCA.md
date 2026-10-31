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
- `details` em jsonb de até 4 KB, sem chave que sugira biometria em qualquer nível (R5.9). `partner_id` existe sem
  chave estrangeira: `platform.partners` chega com a hierarquia (CPX-001), que acrescenta a FK em migration própria.
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

Os tipos fora do catálogo inicial do PRD (5.2) são acréscimos para as fontes existentes, sem inferir passagem
(R5.10). Ainda sem gravação: políticas (V043, `POLICY_PUBLISHED`), login/MFA/contexto, conector fora do ar e os épicos
novos (permanência, incidentes, bloqueio, escala), que gravam ao nascer.

## Leitura, exportação e consultas sensíveis (API)

- `GET /v1/tenants/{t}/security-log` (filtros `type`, `severity`, `actorId`, `from`, `until`, `beforeSeq`, `limit`) e
  `GET /v1/tenants/{t}/security-log/integrity`: `security.log.read.tenant` (MANAGER) ou `security.log.read`
  (parceiro), sempre AAL2. A gestão local vê a equipe do parceiro só pelo papel (R5.6).
- `POST /v1/tenants/{t}/security-log/exports`: só `security.log.export` e motivo (10 a 500 caracteres); intervalo
  contíguo de até 5.000 eventos com manifesto e `LOG_EXPORTED` na mesma transação (R5.7).
- `SENSITIVE_VIEWED` na mesma transação da leitura: busca e integridade do log (sempre), histórico de acesso do
  condomínio pela portaria, cadastro de pessoas (sujeitos de acesso) e evidência de idade (com a flag ligada).
- `security.log.read`, `security.log.export` e `security.log.read.shift` não são publicadas por nenhum papel de
  condomínio: chegam com a hierarquia de parceiros (CPX-001/CPX-003). As duas primeiras já valem nas políticas da V050;
  a de turno depende da escala (CPX-002) e precisa de política nova.

## Forma canônica do hash (`cpsl1`)

`hash = hex(sha256(utf8("cpsl1|" + prev_hash + "|" + J)))`, com `prev_hash` do evento anterior do mesmo condomínio
(64 zeros no primeiro) e `J` o texto do `jsonb_build_array` (saída do PostgreSQL) de: `id`, `tenant_id`, `partner_id`,
`seq`, `occurred_at` e `recorded_at` em UTC (`YYYY-MM-DDTHH:MI:SS.ffffffZ`), `clock_skew_ms`, `event_type`, `severity`,
`source`, `actor_account_id`, `actor_role`, `session_id`, `subject_type`, `subject_id`, `unit_id`, `point_id`,
`device_id`, `reason`, `evidence_ref`, `correlation_id`, `trace_id`, `corrects_id`, `details`. O harness PostgreSQL
recalcula um hash fora do banco com essa regra.

## Limites e pendências

- Retenção indefinida até decisão do responsável (DECISOES_COMPLEMENTAR.md): nenhuma rotina de expurgo.
- `platform.partner_security_log` (eventos só de parceiro) e a FK de `partner_id` ficam para depois da hierarquia.
- A gravação pelas fontes é do banco: aplicar a V050 na homologação liga a gravação mesmo com a flag desligada (a flag
  controla a API, as consultas sensíveis e a âncora). A V050 não foi aplicada em nenhum banco remoto.
- Intervenção de superusuário com `session_replication_role=replica` não grava no log (gatilhos desligados); a
  verificação de integridade detecta a alteração do que já estava gravado.
- A verificação percorre a cadeia inteira a cada chamada (custo linear); otimização a partir da última âncora fica
  para quando houver volume real.
