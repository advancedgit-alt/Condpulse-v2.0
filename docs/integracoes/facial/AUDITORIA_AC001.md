# Auditoria AC-001 — pacote Acesso Facial v1.1 × repositório CondPulse

Entrega do item **AC-001** do backlog do pacote ([`pacote-v1.1/planejamento/backlog.json`](pacote-v1.1/planejamento/backlog.json)).
Data: 31/10/2026. Somente leitura de código, migrations e documentos; nenhuma migration, código, banco, flag ou
equipamento foi alterado nesta fatia.

Classificação usada (critério do AC-001): **Existe** (comprovado no repositório), **Parcial** (existe algo equivalente;
o que falta está dito), **Não existe**. "Existe" quer dizer implementado e testado em CI; nada aqui está homologado
com login real nem com equipamento.

## 1. Base auditada

| Item | Valor | Evidência |
|---|---|---|
| Repositório | `advancedgit-alt/Condpulse-v2.0` | remoto `origin` |
| Branch base | `ccr-5e725112-3x8hyg` | — |
| Commit auditado | `7130b52769ad2acaac420fd2896b32b1ad7484ad` (merge do PR #125) | `git rev-parse origin/ccr-5e725112-3x8hyg` |
| Backend | Java 25, Spring Boot 4.1.2, monólito modular (API/BFF e worker por perfil) | `apps/backend/pom.xml:3,5` |
| Web / mobile | React + TypeScript + Vite (`apps/web`); Expo SDK 58 (`apps/mobile`) | `docs/STATUS.md` (CP-012) |
| Banco | PostgreSQL 17 (Supabase, São Paulo); Flyway é o único dono do schema | `CLAUDE.md`, `AGENTS.md` |
| Migrations no repositório | V001 a V036; **próxima livre: V037** (nenhum PR aberto usa V037 em 31/10/2026) | `db/migrations/` |
| Homologação (`rpnrqfrfwywglvyykgrg`) | V001–V036 aplicadas (V034, V035 e V036 em 31/10/2026) | `docs/STATUS.md`, "Situação atual" e seções por ticket |
| Produção (`tkxcujckpsuawnzkvpjg`) | Só V001–V017, sem runtime ligado | `docs/STATUS.md` |
| API | Fly.io, app `condpulse-v2-0`, região `gru`, sempre ligada | `fly.toml:4-5` |
| Worker | Fly.io, app `condpulse-v2-0-worker`, sem HTTP, outbox ligado | `fly.worker.toml:4,19` |
| Web | Vercel (projeto `condpulse`); `/auth`, `/v1` e readiness reescritos para a Fly | `vercel.json:19-22` |
| CI | Workflow "CondPulse foundation": jobs `foundation`, `web`, `mobile`, `release-config` | `.github/workflows/foundation.yml` |
| Contrato HTTP vigente | `contracts/openapi.json` | — |

### Flags que tocam acesso físico

| Flag | Valor | Evidência |
|---|---|---|
| `CP_ACCESS_CREDENTIALS_ENABLED` (credenciais, eventos, saúde) | Padrão `false`; gravada `true` na Vercel Production (apply 41083523914). A Fly recebe o valor copiado da Vercel pelo `fly-api`; o valor efetivo na Fly não foi relido nesta auditoria | `apps/backend/src/main/resources/application.yaml:60`; `infra/scripts/fly-api/fly-api.mjs:46`; `docs/STATUS.md` |
| `CP_WORKER_ACCESS_SYNC_ENABLED` (envio ao equipamento) | `false` no worker da Fly | `application.yaml:81`; `fly.worker.toml:24` |
| Abertura remota | Desligada: só existe no contrato do adaptador; o simulador recusa com `OPEN_NOT_ENABLED` | `SimulatedAccessAdapter.java:210` |
| Facial | Não existe: `cp.access_credentials.kind` aceita só `CARD` e `QR` | `V026__access_credentials.sql:41` |

Nenhum parceiro físico, adaptador real ou webhook de fabricante existe (`docs/ADAPTADOR_ACESSO.md`, CP-044 PLANEJADO).

## 2. Reaproveitamento da base citada pelo pacote (seção 2 do modelo de dados)

O pacote foi escrito sobre o ZIP v1.0 (`spec/banco/V001__core_reference.sql`). Vários nomes mudaram no repositório real:

| Base no pacote | No repositório | Situação |
|---|---|---|
| `platform.tenants`, `platform.accounts` | Iguais; `tenants.time_zone` existe | Existe — `V001:7-18` |
| `cp.memberships`, `cp.units` | Iguais; papéis `RESIDENT, RESPONSIBLE, DEPENDENT, OWNER, CONCIERGE, MANAGER, SUPPORT` | Existe — `V001:25-50` |
| `cp.amenities`, `cp.reservations` | Iguais | Existe — `V001:52-85` |
| `cp.visitors`, `cp.visit_permits` | Iguais; convidados de reserva por `visit_permits.reservation_id` | Existe — `V013:9,20`; `V021:5-6` |
| `cp.connectors` | **`cp.access_connectors`** (provider `SIMULATED`/`PARTNER`, sem segredo) | Existe com outro nome — `V026:9-17` |
| `cp.access_devices` | Igual; falta `provisioned_instance_id`, `hardware_revision` e vínculo com perfil homologado | Parcial — `V026:19-31` |
| `cp.access_credentials` | Igual; só `membership_id` (sem visitante nem `subject_id`), sem `legal_record_id`, sem `FACE` | Parcial — `V026:35-62` |
| `cp.credential_syncs` | Igual: tentativa e confirmação por credencial × equipamento | Existe — `V026:68-86` |
| `cp.integration_commands` | Não há; o envio é `credential_syncs` + reconciliador do worker | Não existe |
| `cp.webhook_inbox` | Não há caixa genérica; eventos de equipamento chegam por **busca com cursor** e deduplicação por conector (`cp.device_events`, `cp.device_event_cursors`). Webhook só no financeiro (`cp.finance_webhook_events`) | Não existe — `V027:27,43`; `V036:264` |
| `cp.outbox_events` | **`cp.outbox`** com worker, arrendamento e replay pela plataforma | Existe com outro nome — `V001:125`; `V015:9-14`; `V032` |
| `cp.access_events` (eventos de equipamento) | **`cp.device_events`**. Atenção: no repositório, `cp.access_events` é o **registro manual da portaria** (`MANUAL_IN/OUT`) | Existe com outro nome — `V027:8`; colisão de nome com `V014:9` |
| `cp.audit_events` | **`cp.audit_log`** (condomínio) e `platform.platform_audit_log` | Existe com outro nome — `V001:118`; `V010:96` |
| `cp.legal_records`, `cp.privacy_requests` | Não há | Não existe |
| `cp.support_access_grants` | Não há; `SUPPORT` não tem capacidade | Não existe — `StaffCapability.java:7-8,27-35` |
| `cp.idempotency_keys` | Igual | Existe — `V001:135` |

## 3. As 20 entidades propostas

| # | Entidade proposta | Situação | Evidência e o que falta |
|---|---|---|---|
| 1 | `platform.access_model_profiles` | Não existe | Só há `AccessModels.Capabilities` em tempo de execução, por conector (`AccessModels.java:38-39`), sem catálogo, SKU, firmware ou fonte |
| 2 | `platform.access_homologations` | Não existe | Nenhum registro de combinação, evidência ou aprovador |
| 3 | `cp.technical_grants` | Não existe | Há `platform.platform_roles` só com `PLATFORM_ADMIN` (`V010:5-7`, `PlatformCapability.java:46-63`) e papéis de condomínio `MANAGER`/`CONCIERGE` (`StaffCapability.java:27-35`). Falta o MASTER de implantação com escopo de condomínio, validade e aprovador; conectores e equipamentos hoje só entram por operação da plataforma (`V026:4-5`, sem grant de escrita para a aplicação) |
| 4 | `cp.access_areas` | Parcial | `cp.amenities` cobre só áreas reserváveis, com `policy` jsonb de reserva (`V001:52-63`). Falta área física sem reserva (hall, corredor, casa de máquinas), setor, rota essencial e `amenity_id` opcional |
| 5 | `cp.access_points` | Não existe | Só `access_devices.direction` (`V026:25`); sem origem/destino, tipo de passagem, rota acessível |
| 6 | `cp.device_channels` | Não existe | Equipamento é uma linha só; sem canal/relé/leitor/sensor |
| 7 | `cp.point_bindings` | Não existe | Sem revisão física nem `binding_revision` |
| 8 | `cp.edge_agents` | Não existe | `cp.access_connectors` (`V026:9`) é o ponto de ancoragem, sem identidade de máquina, certificado, versão ou estado do gateway |
| 9 | `cp.access_subjects` | Parcial | Pessoa está dividida entre `memberships` (conta) e `visitors` (`V001:35`; `V013:9`); credencial só por `membership_id` (`V026:39`), visitante não recebe credencial. Falta o sujeito único por condomínio |
| 10 | `cp.subject_contexts` | Parcial | Contextos existem espalhados: vínculo com papel e validade (`V001:35-50`), convite (`V013:20`), prestador recorrente (`V022:6`), convidado de reserva (`V021:5`), locação proprietário × inquilino (`V034:12`). Falta a relação explícita sujeito × contexto, as categorias `EMPLOYEE`/`SERVICE_PROVIDER`/`OWNER_NONRESIDENT` e a precedência determinística |
| 11 | `cp.age_evidence` | Não existe | Nenhuma data de nascimento ou atestado; só o papel `DEPENDENT` |
| 12 | `cp.access_policies` | Não existe | `amenities.policy`/`policy_version` são regras de reserva (`V001:58-59`), não de acesso físico |
| 13 | `cp.access_policy_versions` | Não existe | Análogo apenas em `reservations.rule_snapshot` (`V001:72`) |
| 14 | `cp.access_grants` | Não existe | Hoje a credencial é o próprio direito, ligada ao vínculo com validade copiada dele (`V026:46-47,113`); não há direito por área/ponto/sentido |
| 15 | `cp.policy_deployments` | Não existe | — |
| 16 | `cp.entitlement_syncs` | Parcial | Mesmo padrão de `cp.credential_syncs` (tentado × observado, versão desejada atual, `V026:68-86`), mas para credencial, não para direito por canal |
| 17 | `cp.access_decisions` | Não existe | `device_events` registra `AUTHORIZED`/`DENIED` observados (`V027:16`); não há decisão do CondPulse |
| 18 | `cp.access_command_receipts` | Não existe | Só o contrato `requestOpen`/`OpenOutcome` no adaptador (`AccessModels.java:120-127`), sem persistência |
| 19 | `cp.revocation_markers` | Parcial | Revogação de credencial é final e versionada, confirmação antiga não reativa (`V026:105-130`); falta marcador durável por sujeito/grant/escopo e o diário fora do banco restaurável |
| 20 | `cp.installation_acceptances` | Não existe | — |

Resumo: **0 existem por inteiro, 5 parciais (4, 9, 10, 16, 19), 15 não existem.** As tabelas da base que já
existem cobrem credenciais `CARD`/`QR`, envio idempotente por equipamento, eventos deduplicados com quarentena e
saúde dos equipamentos (CP-046/047/048), sempre só contra o adaptador simulado.

## 4. Grupos de operações do OpenAPI proposto (12 grupos, 32 operações)

Rotas atuais conferidas em `contracts/openapi.json` e `CredentialController.java:28-65`.

| Grupo (tag) | Operações propostas | Situação | Evidência |
|---|---|---|---|
| Catalogo | `GET /v1/platform/access-models[/{id}]` | Não existe | A base de rotas `/v1/platform/*` com papel de plataforma e AAL2 existe (`PlatformController.java`) |
| Homologacao | `GET/POST /v1/platform/access-homologations`, `GET /{id}`, `POST /{id}/decisions` | Não existe | — |
| Areas | `GET/POST /access-areas`, `GET/PATCH /{areaId}` | Parcial | `/management/amenities` (GET/POST/PUT) cobre só áreas reserváveis, capacidade `amenities.manage` |
| Pontos | `GET/POST /access-points`, `GET /{pointId}`, `POST /point-bindings`, `GET /{bindingId}` | Não existe | Só leitura `GET /access-connectors` |
| Politicas | `GET/POST /access-policies`, `GET /{id}`, versões, `validate`, `simulate` | Não existe | — |
| Publicacao | `POST /{policyId}/publish`, `GET /access-deployments/{id}` | Parcial (análogo) | Estado por equipamento só para credenciais: `GET /concierge/pending-revocations`, `GET /access-health` |
| Idade | `POST /age-evidence` | Não existe | — |
| Abertura | `POST/GET /access-points/{pointId}/open-requests[/{commandId}]` | Não existe | Só o contrato interno do adaptador, desligado |
| Implantacao | `POST /installations/{id}/commission` | Não existe | — |
| Segredos | `POST /connectors/{id}/rotate-secret` | Não existe | Conector não guarda segredo hoje |
| Operacoes | `GET /operations/{operationId}` | Parcial (análogo) | `/v1/platform/outbox/tasks` mostra tarefas da fila só para a plataforma (`V032`) |
| Edge | `POST /v1/edge/events`, `POST /v1/edge/decisions` | Não existe | Eventos entram pela busca do worker (`DeviceEventIngestor`, `V027:43`); não há entrada mTLS |

Rotas existentes que o pacote manda preservar: `/management/access-credentials` (+ `revoke`), `/units/{u}/access-credentials`,
`/units/{u}/device-events`, `/concierge/device-events`, `/access-health`, `/access-connectors`, convites (`/permits`),
registro manual (`/access-events`), convidados de reserva e regras de visita.

### Contrato do adaptador (capítulo 15 × `AccessAdapter`)

| Operação do pacote | No repositório (`AccessAdapter.java:9-18`) | Situação |
|---|---|---|
| discoverCapabilities | `capabilities` (booleans; sem documentada/observada/aprovada nem limites por dimensão) | Parcial |
| upsertPerson | `upsertPerson` | Existe |
| enrollFace | — | Não existe |
| applyEntitlements | `applyCredential` (credencial inteira, sem direito por canal/vigência) | Parcial |
| readAppliedState | `getApplication` | Existe |
| revokeEntitlements | `revokeCredential` | Parcial |
| deleteBiometrics | — | Não existe |
| subscribeEvents | — (só busca) | Não existe |
| backfillEvents | `getEvents` (cursor, janela de 24 h, 100 por página) | Existe |
| requestOpen | `requestOpen` (desligado) | Parcial |
| readHealth | `health` | Existe |

## 5. O que NÃO reaplicar nem duplicar

- **V001/V002 do ZIP v1.0** (`spec/banco/V001__core_reference.sql`, `spec/banco/V002__rls_reference.sql`): são
  referência; o banco já tem as V001/V002 do repositório (exceção ADR-0003) e V003–V036.
- Nenhuma migration aplicada (V001–V036) é editada ou renumerada; tudo novo é aditivo a partir de V037.
- Não criar tabelas com os nomes do ZIP quando já há equivalente: `cp.connectors` (→ `cp.access_connectors`),
  `cp.outbox_events` (→ `cp.outbox`), `cp.audit_events` (→ `cp.audit_log`), `cp.access_events` de equipamento
  (→ `cp.device_events`; o `cp.access_events` do repositório é o registro manual e não muda de sentido).
- Não aplicar `dados/modelo_logico.json` nem os índices propostos diretamente: o pacote declara que não é SQL.
- Não copiar o servidor `api.condpulse.example` nem usar os exemplos JSON como dados.
- Não marcar modelos do catálogo como homologados nem casos HAC como executados sem evidência.
- Produção: nada de acesso físico até a homologação concluir os gates (produção está só em V001–V017).

## 6. Decisão do AC-002 (responsável, 31/10/2026)

- **Piloto: Condomínio Teste 1, no ambiente de homologação.** A liberação para os outros condomínios vem depois de tudo
  funcionar lá (o pacote prevê a mesma versão imutável promovida, com dossiê e aceite próprios por condomínio).
- **Quatro marcas no escopo de implementação completa** (Control iD, Hikvision, Dahua, Intelbras), com equipamento,
  marca, modo e áreas parametrizados por condomínio. O responsável escolhe depois qual equipamento testar fisicamente.
- Consequência: o software não espera mais o piloto. **Continua BLOQUEADO só o que exige hardware: bancada (G3),
  comissionamento (G4), piloto (G5) e aceites (G6) — responsável — equipamento físico no Condomínio Teste 1.**
- Limites que continuam valendo: cada adaptador segue só a documentação oficial; operação sem documentação acessível
  fica pendente (sem endpoint inventado), e o código pronto não é homologação. O inventário real do AC-002 (SKU,
  firmware, entradas, rotas essenciais e saídas) entra no dossiê do Condomínio Teste 1 quando o equipamento for escolhido.

## 7. Ordem proposta: duas trilhas paralelas

Os nomes das migrations são propostos; o número é o próximo livre no momento do merge (hoje V037), sempre em série.

### Trilha (a) — domínio e backend, migrations V037+ em série

| Ordem | Itens | Migration proposta | Conteúdo | Precisa de hardware? |
|---|---|---|---|---|
| 1 | AC-003 | `V037__access_technical_grants.sql` | `cp.technical_grants` (MASTER de implantação por condomínio, escopos, validade, aprovador distinto); novas capacidades (`homologation.approve`, `installation.bind_channel`, `connector.rotate_secret`, `area.manage`, `policy.edit`, `policy.publish`, `person.review`, `credential.revoke`, `access.request_open`, `audit.export`) sem herança pelo `MANAGER` | Não |
| 2 | AC-005 | `V038__access_subjects.sql` | `cp.access_subjects`, `cp.subject_contexts`; `cp.access_credentials.subject_id` opcional com backfill idempotente (compatibilidade) | Não |
| 3 | AC-006 | `V039__access_age_evidence.sql` | `cp.age_evidence` (atestado ou data protegida), revisão de menores | Não |
| 4 | AC-019 (dados) | `V040__access_model_catalog.sql` | `platform.access_model_profiles`, `platform.access_homologations` (catálogo das 4 marcas como `CANDIDATO_DOCUMENTADO`) | Não para cadastrar; aprovação exige G3 |
| 5 | AC-004 | `V041__access_areas_points.sql` | `cp.access_areas` (com `amenity_id` opcional), `cp.access_points`, rota essencial/acessível | Não |
| 6 | AC-004 | `V042__access_channels_bindings.sql` | `cp.device_channels`, `cp.point_bindings`, `cp.installation_acceptances`; `access_devices` + instância provisionada, revisão de hardware e perfil | Não para o modelo; G4 para ativar |
| 7 | AC-007, AC-008 | `V043__access_policies.sql` | `cp.access_policies`, `cp.access_policy_versions`, `cp.access_decisions`; motor determinístico, validar e simular (sem acionar porta) | Não |
| 8 | AC-009 | `V044__access_grants.sql` | `cp.access_grants`, `cp.revocation_markers`; direitos derivados de convites, reservas, prestador recorrente e vínculos | Não |
| 9 | AC-012, AC-013 | `V045__access_policy_deployments.sql` | `cp.policy_deployments`, `cp.entitlement_syncs`; compilador e publicação via outbox | Não (simulador); G3 para confirmar |
| 10 | AC-016 | `V045__device_events_evidence.sql` | `cp.device_events` + ponto, `binding_revision`, decisão, nível de evidência, época nativa e instância; novos tipos | Não |
| 11 | AC-018 (dados) | `V047__access_edge_agents.sql` | `cp.edge_agents` (identidade de máquina, certificado por referência, versão, estado) | Não para o modelo; G3 para operar |
| 12 | AC-020 (dados) | `V048__access_command_receipts.sql` | `cp.access_command_receipts` (abertura sem repetição) | Não para o modelo; G3 para ligar |
| 13 | AC-014, AC-015 | `V049__access_face_enrollment.sql` | `kind FACE`, registros de finalidade/aviso/base legal (o repositório não tem `legal_records`), pedidos de exclusão por destino | Não para o fluxo; G3 para enviar face |
| 14 | AC-017 | `V050__access_recovery_barrier.sql` | Barreira de recuperação e marco do diário de revogação | Não para o modelo; checkpoint externo a definir |
| — | AC-010, AC-011 | Transversais | Cada migration acima traz RLS FORCE, chaves compostas com `tenant_id` e teste com dois condomínios; rotas entram em `contracts/openapi.json` atrás de flag | Não |

### Trilha (b) — gateway e adaptadores das quatro marcas, sem migrations

| Ordem | Itens | Entrega |
|---|---|---|
| 1 | AC-019 (código) | Evolução de `AccessAdapter` para o `AccessProviderPort` do pacote: `enrollFace`, `deleteBiometrics`, `applyEntitlements` por canal/vigência, `subscribeEvents`; manifesto de capacidades com documentada/observada/aprovada e limites; simulador estendido |
| 2 | AC-018, AC-022 | Gateway local por condomínio: conexão de saída autenticada, bootstrap de uso único, fila durável local com revogação prioritária, sem escrita no banco central e sem proxy arbitrário; métricas e saúde |
| 3 | AC-020 | Despachante de abertura no gateway: validade curta, nonce, registro durável antes do envio, sem retry após falha ambígua (`UNKNOWN`) |
| 4 | AC-021 | Adaptador Control iD (primeira prova documental do pacote) |
| 5 | AC-031 | Adaptador Hikvision (ISAPI embarcado; HikCentral só com contrato próprio) |
| 6 | AC-032 | Adaptador Dahua (CGI ou SDK aplicável) |
| 7 | AC-033 | Adaptador Intelbras (API Bio-T/CGI atual) |

Os quatro adaptadores podem andar em paralelo depois do item 1. Cada um usa fixtures sanitizadas tiradas da
documentação oficial; operação sem documentação acessível fica marcada como não suportada. A topologia nova (gateway
na rede do condomínio falando com a API, em vez do worker chamar o parceiro) deve ser registrada em ADR (próxima: ADR-0009).

### Pontos de contrato entre as trilhas

| Contrato | Produz | Consome | Depende de |
|---|---|---|---|
| `AccessProviderPort` e manifesto de capacidades (documentada/observada/aprovada, limites) | (b) | (a) validar/simular (AC-008), compilador (AC-012), catálogo (V040) | (b)1 antes de (a)7 usar capacidades reais; até lá o simulador |
| Perfil da combinação (marca, SKU, hardware, região, firmware, adaptador, modo, topologia) | (a) V040 | (b) gateway confere ao reconectar | V040 |
| Topologia física: canais nativos, leitores, sensores, `binding_revision` | (b) descoberta | (a) V042 | V042 |
| Documento de direitos compilado por destino (geração, `restriction_epoch`, `binding_revision`, vigência) e ordem revogação-antes-de-ampliação | (a) V045 | (b) aplica e confirma | V044/V045 |
| Envelope de evento `/v1/edge/events` (schemaVersion, chave de dedupe tenant + instância + época + ID nativo, nível de evidência) | (b) | (a) V045 | V045 |
| Telemetria de decisão `/v1/edge/decisions` (EDGE_ONLINE; ACK não abre porta) | (b) | (a) V043 | V043 |
| Comando de abertura e recibo (commandId, nonce, expiresAt, revisões; estados do recibo) | (a) V048 | (b) despachante | V048 |
| Identidade do gateway (bootstrap, certificado, rotação, revogação) | (a) V047 | (b) | V047 |
| Saúde (heartbeat, relógio, fila, capacidade) | (b) | (a) tabelas existentes de V028 | já existe |

## 8. Perguntas que só o responsável responde

1. Quem exerce o **MASTER de implantação** do Condomínio Teste 1: a mesma conta `PLATFORM_ADMIN`, outra conta da equipe
   ou um integrador externo?
2. **Gateway local**: haverá um computador na rede do condomínio para ele? Hospedagem e custo precisam de OK explícito.
3. **Cofre de segredos** das credenciais de fabricante: qual serviço o CondPulse vai usar (o pacote cita Infisical
   como opção; a da Advanced não vale)? Custo também precisa de OK.
4. **Idade**: guardar data de nascimento protegida ou só atestado de maioridade? Regra de quem nasceu em 29/02? Quem
   pode verificar a evidência?
5. **Biometria de menores**: o condomínio permite cadastro facial de dependentes, com qual processo do responsável legal?
6. **Documentação licenciada**: há acesso de parceiro aos guias Hikvision (TPP) e ao SDK Dahua? Sem isso, essas
   operações ficam pendentes.
7. **Abertura remota** pela portaria (e por morador) entra na primeira versão ou fica para depois?
8. Cartões e QR do CP-046 continuam como métodos ao lado da face, sob as mesmas regras de área?
