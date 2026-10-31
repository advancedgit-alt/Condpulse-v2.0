# CondPulse — extensão proposta do modelo de dados

**Versão 1.1 · 30/10/2026 · PROPOSTA DOCUMENTAL, NÃO APLICADA**

Este anexo detalha exatamente as 20 entidades do capítulo 13 do manual. O JSON acompanha o mesmo modelo para revisão por engenharia. Não é migration nem SQL pronto. A referência inicial do ZIP v1.0 foi lida para preservar nomes e relações; o schema conectado, as migrations atuais e os equipamentos não foram inspecionados.

## 1. Convenções e limites

- Cada tabela de negócio cp tem id, tenant_id, created_at, updated_at e version. PK: id; chave UNIQUE adicional: (tenant_id, id).
- Todas as referências entre entidades cp incluem tenant_id e apontam para a chave UNIQUE completa. Referências globais a contas ou catálogo são exceções explícitas e não concedem permissão no condomínio.
- Campos N são obrigatórios; campos S aceitam null somente nos estados indicados. Datas de evento usam timestamptz; calendário de regras usa fuso IANA.
- Referências privadas e secret_ref são identificadores opacos. Senhas, chaves privadas, fotos, tokens e datas de nascimento em claro não aparecem nessas projeções.
- Conteúdo de política publicada e topologia ativa é imutável. Atualização de conteúdo cria revisão; estado operacional e confirmações permitidas usam controle de versão.
- Índices, constraints e transações abaixo são propostas. Nenhuma foi criada ou medida no banco.

**Princípio relacional:** FKs compostas precisam de chave candidata correspondente no destino; referências entre linhas/tabelas não devem ser implementadas como CHECK que consulta outros registros. A documentação oficial do PostgreSQL distingue essas garantias. [PostgreSQL — Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html).

**Isolamento:** RLS complementa a autorização do domínio. Owners e papéis com BYPASSRLS exigem atenção; o papel normal de aplicação deve operar com privilégio mínimo e contexto de tenant válido por transação. [PostgreSQL — Row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html).

## 2. Reaproveitamento da base

Preservar platform.tenants, platform.accounts, cp.memberships, cp.units, cp.amenities, cp.reservations, cp.visitors, cp.visit_permits, cp.connectors, cp.access_devices, cp.access_credentials, cp.credential_syncs, cp.integration_commands, cp.webhook_inbox, cp.outbox_events, cp.access_events, cp.audit_events, cp.legal_records, cp.privacy_requests, cp.support_access_grants e cp.idempotency_keys.

cp.idempotency_keys foi confirmado no arquivo V001 de referência. Isso não comprova seu estado na produção atual. A implementação começa por reconciliar nomes, chaves e contratos do repositório existente.

### cp.access_credentials

- Adicionar subject_id opcional na fase de compatibilidade e FK (tenant_id,subject_id).
- Backfill idempotente de account_id/visitor_id para um sujeito; conferir XOR e origem.
- Migrar leitores/escritores antes de tornar subject_id obrigatório; manter uma fonte canônica, sem dois cadastros editáveis.
- Preservar ausência de template facial nesta tabela e manter legal_record_id.

### cp.access_devices

- Registrar provisioned_instance_id estável, hardware_revision e perfil de homologação ligado à instância real.
- Substituição/movimentação de condomínio cria nova instância após limpeza comprovada; não trocar tenant_id.
- A direção do dispositivo não substitui cp.point_bindings e o sentido da tentativa.

### cp.access_events

- Acrescentar tentativa/ponto/binding/decisão/recibo como referências tenant-scoped quando conhecidos.
- Manter tipo factual granular e nível de evidência; mapear enum legado sem converter autorização em passagem.
- Preservar occurred_at e received_at; deduplicar por tenant, instância provisionada, época nativa e ID nativo comprovados.
- Uma referência opcional desconhecida fica null/quarentena; nunca inventar ponto ou sentido.

### cp.webhook_inbox

- Distinguir identidade do emissor da origem factual do evento; não criar evento novo só por gateway de transporte diferente.
- Chave de dedupe deve respeitar reset de contador/boot do equipamento e identidade de provisionamento.

### cp.integration_commands

- Ampliar tipos para publicação/revogação de direitos conforme contratos atuais.
- OPEN_DOOR permanece operação especial com TTL curto e recibo persistente; timeout após envio não cria retry comum.
- Guardar hash do payload e referências tenant-scoped; não colocar senha/foto em last_error.

### cp.outbox_events

- Mudanças de direito, publicação e revogação criam outbox no mesmo commit.
- Consumidores conferem versão/epoch atuais antes da escrita; prioridade não depende só da ordem de chegada.

### cp.idempotency_keys

- Reutilizar a tabela de idempotência existente; mesma chave com request_hash diferente é conflito.
- Não confundir idempotência da API com garantia de exatamente uma atuação física.

### cp.legal_records / cp.privacy_requests

- Reaproveitar finalidade, aviso, base legal e exercício de direitos; não criar consentimento paralelo implícito.
- Exclusão distribuída precisa de confirmação por destino e tratamento de retenção/backup autorizado.

## 3. Dicionário das 20 entidades

Campos comuns: id UUID obrigatório; created_at e updated_at timestamptz obrigatórios; version bigint positivo obrigatório. Toda entidade cp acrescenta tenant_id UUID obrigatório e FK para platform.tenants(id). O JSON expande os campos em cada entidade.

### 3.1. platform.access_model_profiles

Perfil documental de modelo, hardware e região, sem pessoas, biometria, segredos ou configuração de condomínio.

**Escopo:** GLOBAL_TECHNICAL_NO_PERSONAL_PAYLOAD. **Ciclo:** VERSIONED_TECHNICAL_CATALOG. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| manufacturer | text max 64 | N | Fabricante oficial normalizado. |
| model_sku | text max 128 | N | SKU completo com sufixos. |
| hardware_revision | text max 64 | N | Revisão exata; UNKNOWN somente na fase documental. |
| region_code | text max 32 | N | Mercado/região a que a evidência se aplica. |
| profile_revision | integer | N | Revisão positiva do perfil documental. |
| status | enum | N | Estado documental do catálogo. Valores: CANDIDATE, ACTIVE, RETIRED. |
| sources | jsonb schema source_list_v1 | N | Lista limitada de IDs e URLs públicas oficiais; sem documentos pessoais. |
| declared_capabilities | jsonb schema manufacturer_capabilities_v1 | N | Capacidades documentadas com SUPPORTED/UNSUPPORTED/UNKNOWN e fontes. |
| declared_limits | jsonb schema documented_limits_v1 | N | Limites publicados, condicionantes e conflitos; desconhecido permanece null. |
| document_hash | text sha256 hex | N | Hash da representação canônica do perfil. |
| category | enum | N | Categoria técnica compatível com o catálogo da API. Valores: FACE_TERMINAL, ACCESS_CONTROLLER, READER, EXPANSION_MODULE. |
| market_lifecycle | enum | N | Situação comercial documental publicada, separada do estado interno do registro e da homologação. Valores: DOCUMENTED_CANDIDATE, CURRENT_DECLARED, DISCONTINUED_DECLARED, SUPPORT_UNKNOWN. |

**Chaves UNIQUE propostas:**

- (manufacturer, model_sku, hardware_revision, region_code, profile_revision). Unicidade de referência ou de negócio.

**Referências:**

- Sem FK de negócio; metadados globais técnicos.

**Índices de acesso propostos:**

- BTREE (manufacturer, model_sku, status) — Localizar variantes e candidatos.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Não contém dados de moradores, fotos, templates, nomes de operadores ou configurações de tenants.
- ACTIVE no catálogo documental não significa homologação técnica aprovada.
- Campos de capacidade conflitantes não viram true ou número único por padrão.

### 3.2. platform.access_homologations

Resultado técnico versionado da combinação concreta de modelo, firmware, adaptador, modo e topologia suportada.

**Escopo:** GLOBAL_TECHNICAL_NO_PERSONAL_PAYLOAD. **Ciclo:** IMMUTABLE_APPROVED_CONTENT. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| model_profile_id | uuid | N | Perfil documental de origem. |
| homologation_revision | integer | N | Revisão positiva do certificado técnico. |
| firmware_exact | text max 128 | N | Firmware ensaiado; não aceita curinga no certificado aprovado. |
| adapter_name | text max 64 | N | Nome controlado do adaptador. |
| adapter_version | text max 64 | N | Versão exata do adaptador. |
| decision_mode | enum | N | Modo de decisão ensaiado. Valores: DEVICE_LOCAL_ACL, EDGE_ONLINE, PROVIDER_LOCAL, ASSISTED. |
| contract_schema_version | text max 32 | N | Versão do contrato de capacidades. |
| approved_capabilities | jsonb schema capability_evidence_v1 | N | Capacidade documentada/observada/aprovada e limites, separadamente. |
| topology_constraints | jsonb schema approved_topology_v1 | N | Restrições de canais, leitores, sensores e emergência, validadas pelo MASTER. |
| evidence_bundle_ref | text internal reference | N | Referência do relatório técnico minimizado; sem biometria no catálogo. |
| evidence_hash | text sha256 hex | N | Hash do conjunto de evidências aprovado. |
| status | enum | N | Estado de homologação. Valores: CANDIDATO_DOCUMENTADO, EM_ENSAIO, APROVADO_BANCADA, APROVADO_PILOTO, HOMOLOGADO, REPROVADO, SUSPENSO, EXPIRADO. |
| approved_at | timestamptz | S | Instante do aceite central. |
| approval_audit_ref | text restricted internal reference | S | Referência opaca para a trilha de aprovação central; autoria identificável fica fora do catálogo. |
| review_due_at | timestamptz | S | Data de revisão; não prorroga capacidades. |

**Chaves UNIQUE propostas:**

- (model_profile_id, firmware_exact, adapter_name, adapter_version, decision_mode, homologation_revision). Unicidade de referência ou de negócio.

**Referências:**

- (model_profile_id) → platform.access_model_profiles(id).

**Índices de acesso propostos:**

- BTREE (model_profile_id, status) — Resolver perfil homologado vigente.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- HOMOLOGADO exige evidência de testes críticos e aprovação do MASTER da plataforma.
- Conteúdo técnico aprovado é imutável; alteração cria nova homologation_revision. Suspender/revogar exige auditoria e não apaga o certificado.
- Catálogo global guarda metadados técnicos e referências opacas, não dados pessoais de moradores nem fotos de ensaios.
- ASSISTED representa procedimento operacional e atendimento autorizado; não declara capacidade autônoma de um terminal. Aprovações de bancada/piloto não equivalem a HOMOLOGADO para produção.

### 3.3. cp.technical_grants

Delegação técnica temporária para implantação ou suporte, independente das permissões de negócio e de abertura.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| account_id | uuid | N | Conta técnica destinatária. |
| role_code | enum | N | Papel técnico; não equivale ao gestor. Valores: MASTER_IMPLANTACAO, TECHNICAL_SUPPORT. |
| scopes | text array allowlist | N | Escopos fechados de manutenção; não aceita wildcard enviado pelo cliente. |
| valid_from | timestamptz | N | Início da delegação. |
| valid_until | timestamptz | N | Fim obrigatório da delegação. |
| granted_by_account_id | uuid | N | Conta autorizadora; autoridade central conferida no backend. |
| approved_at | timestamptz | N | Instante do aceite. |
| support_access_grant_id | uuid | S | Vínculo com concessão de suporte existente. |
| status | enum | N | Estado administrativo. Valores: ACTIVE, REVOKED, EXPIRED. |
| revoked_at | timestamptz | S | Revogação explícita. |
| revoked_by_account_id | uuid | S | Responsável pela revogação humana. |
| reason_ref | text private internal reference | N | Motivo/aprovação sem segredo. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, id, account_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (account_id) → platform.accounts(id).
- (granted_by_account_id) → platform.accounts(id).
- (revoked_by_account_id) → platform.accounts(id).
- (tenant_id, support_access_grant_id) → cp.support_access_grants(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, account_id, status, valid_until) — Validar escopo técnico vigente.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- valid_until é posterior a valid_from; validade é conferida em cada operação, sem depender de job que mude status.
- Concessão técnica não concede uso físico de áreas nem leitura irrestrita da biometria.
- Gestor não emite essa delegação para si próprio.
- revoked_at preenchido impede exercício mesmo quando o token da conta ainda for válido.

### 3.4. cp.access_areas

Espaço lógico/físico com regras, inclusive áreas sem reserva.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| code | text max 64 | N | Código estável e único no condomínio. |
| name | text max 160 | N | Nome de exibição, sem servir de chave física. |
| area_kind | enum | N | Classificação de negócio. Valores: COMMON_AREA, AMENITY, RECEPTION, TECHNICAL_AREA, RESIDENTIAL_ROUTE. |
| block_id | uuid | S | Bloco existente quando aplicável. |
| parent_area_id | uuid | S | Setor/área organizadora, sem inferência automática de permissão. |
| amenity_id | uuid | S | Área reservável existente; não obrigatória. |
| timezone | text IANA | N | Fuso validado, inicializado a partir do tenant. |
| essential_route | boolean | N | Indica rota essencial para revisão de políticas de ingresso/egresso. |
| status | enum | N | Estado lógico. Valores: DRAFT, ACTIVE, INTERDICTED, RETIRED. |
| physical_boundary_verified | boolean | N | Se a separação física foi verificada; falso não permite prometer bloqueio independente. |
| physical_subtype | text max 64 allowlist | S | Detalhe de inventário como EXTERIOR, HALL, GYM, POOL ou GARAGE. Não cria perfil nem permissão própria. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, code). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, block_id) → cp.blocks(tenant_id, id).
- (tenant_id, parent_area_id) → cp.access_areas(tenant_id, id).
- (tenant_id, amenity_id) → cp.amenities(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, status, area_kind) — Navegação e avaliação por área.
- BTREE (tenant_id, parent_area_id) — Validar e percorrer hierarquia sem cruzar tenant.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Hierarquia sem ciclos; parent_area_id não pode ser o próprio id.
- Permissão em área pai não é herdada implicitamente.
- Amenity e área física têm responsabilidades diferentes; preservar cp.amenities.

### 3.5. cp.access_points

Passagem física identificada entre origem e destino; pode ter dois sentidos sem duplicar o atuador.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| code | text max 64 | N | Identificador estável do ponto físico. |
| name | text max 160 | N | Rótulo operacional. |
| from_area_id | uuid | N | Origem de referência; exterior é uma área explícita do tenant. |
| to_area_id | uuid | N | Destino de referência. |
| point_kind | enum | N | Mecanismo físico. Valores: DOOR, TURNSTILE, VEHICLE_GATE, PEDESTRIAN_GATE, ELEVATOR_CONTROL, OTHER. |
| flow_direction | enum | N | Sentidos possíveis; o sentido da tentativa é registrado separadamente. Valores: FROM_TO, TO_FROM, BOTH, UNKNOWN. |
| egress_class | enum | N | Projeto de saída deste ponto. Valores: FREE_EGRESS, MANAGED_EGRESS, NOT_EGRESS, UNVERIFIED. |
| accessible_route_ref | text private internal reference | S | Referência da rota acessível na planta. |
| emergency_design_ref | text private internal reference | S | Projeto/circuito de emergência aprovado. |
| status | enum | N | Estado da passagem. Valores: DRAFT, MAPPED, COMMISSIONED, SUSPENDED, RETIRED. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, code). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, from_area_id) → cp.access_areas(tenant_id, id).
- (tenant_id, to_area_id) → cp.access_areas(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, to_area_id, status) — Enumerar todas as entradas da área.
- BTREE (tenant_id, from_area_id, status) — Enumerar saídas e rotas alternativas.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- from_area_id é diferente de to_area_id.
- Sentido UNKNOWN não recebe autorização automática de área.
- Duas leituras nos lados da mesma porta pertencem ao mesmo ponto/atuador, com leitores de entrada/saída na revisão do binding.
- A política de ingresso não pode bloquear o egresso de emergência; projeto físico e testes são obrigatórios.

### 3.6. cp.device_channels

Canal nativo de atuação, entrada ou sensor em equipamento provisionado.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| device_id | uuid | N | Equipamento existente; identifica a instância provisionada e não apenas o serial reutilizável. |
| native_namespace | text max 64 allowlist by adapter | N | Namespace nativo do canal, por exemplo saída de porta versus entrada de sensor. |
| external_channel | text max 64 | N | Identificador nativo exato; não obtido do nome de área. |
| channel_kind | enum | N | Função comprovada do canal. Valores: ACCESS_ACTUATOR, DOOR_CONTACT, EXIT_REQUEST, PASSAGE_SENSOR, ALARM_OUTPUT, OTHER. |
| limits | jsonb schema channel_limits_v1 | N | Limites de pulso/capacidade e estados elétricos permitidos; sem script. |
| status | enum | N | Condição técnica do canal. Valores: DISCOVERED, VERIFIED, DISABLED, RETIRED. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, device_id, native_namespace, external_channel). Unicidade de referência ou de negócio.
- (tenant_id, id, device_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, device_id) → cp.access_devices(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, device_id, status) — Resolver canais e limites do dispositivo.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Saída de alarme não é canal de acesso até homologação explícita da topologia.
- Trocar equipamento por reserva cria nova instância; não reatribuir tenant_id de um dispositivo com histórico.
- Mesmo número nativo pode existir em namespaces distintos, mas a composição física completa é única.

### 3.7. cp.point_bindings

Revisão histórica do vínculo entre uma passagem e seu atuador/leitores/sensores.

**Escopo:** TENANT. **Ciclo:** IMMUTABLE_ACTIVE_MAPPING. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| point_id | uuid | N | Ponto físico. |
| actuator_channel_id | uuid | N | Canal que efetivamente aciona a passagem. |
| controller_device_id | uuid | N | Equipamento dono do canal atuador; protegido por FK composta. |
| entry_reader_device_id | uuid | S | Leitor de entrada ou terminal combinado; ausente só em projeto explicitamente não biométrico. |
| exit_reader_device_id | uuid | S | Leitor de saída, se houver. |
| door_contact_channel_id | uuid | S | Contato físico de porta. |
| exit_request_channel_id | uuid | S | Botoeira/entrada de solicitação de saída. |
| passage_sensor_channel_id | uuid | S | Sensor apropriado para passagem; não inferir presença a partir de relé. |
| homologation_id | uuid | N | Combinação técnica aprovada do catálogo global. |
| binding_revision | integer | N | Revisão positiva da instalação deste ponto. |
| installation_acceptance_id | uuid | S | Aceite local desta revisão; obrigatório para ativar. |
| direction_map | jsonb schema direction_map_v1 | N | Mapeamento fechado de leitores e sentidos, sem regras arbitrárias. |
| status | enum | N | Ciclo do binding. Valores: DRAFT, PENDING_COMMISSION, ACTIVE, SUPERSEDED, SUSPENDED. |
| valid_from | timestamptz | S | Início efetivo; obrigatório na ativação. |
| valid_until | timestamptz | S | Fim efetivo; obrigatório ao substituir/suspender um vínculo antes ativo. |
| wiring_evidence_ref | text private internal reference | N | Planta/circuitos e evidência de identificação dos canais. |
| configuration_hash | text sha256 hex | N | Hash da configuração técnica imutável desta revisão. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, point_id, binding_revision). Unicidade de referência ou de negócio.
- (tenant_id, id, point_id). Unicidade de referência ou de negócio.
- (tenant_id, id, actuator_channel_id). Unicidade de referência ou de negócio.
- (tenant_id, id, binding_revision). Unicidade de referência ou de negócio.
- (tenant_id, actuator_channel_id) — apenas quando status ACTIVE. No máximo um dono ativo do canal de atuação.
- (tenant_id, point_id) — apenas quando status ACTIVE. No máximo uma topologia ativa para o ponto.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, point_id) → cp.access_points(tenant_id, id).
- (tenant_id, actuator_channel_id, controller_device_id) → cp.device_channels(tenant_id, id, device_id).
- (tenant_id, entry_reader_device_id) → cp.access_devices(tenant_id, id).
- (tenant_id, exit_reader_device_id) → cp.access_devices(tenant_id, id).
- (tenant_id, door_contact_channel_id) → cp.device_channels(tenant_id, id).
- (tenant_id, exit_request_channel_id) → cp.device_channels(tenant_id, id).
- (tenant_id, passage_sensor_channel_id) → cp.device_channels(tenant_id, id).
- (homologation_id) → platform.access_homologations(id).
- (tenant_id, installation_acceptance_id) → cp.installation_acceptances(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, point_id, valid_from) — Resolver o binding correto no instante do evento.
- BTREE (tenant_id, actuator_channel_id, valid_from) — Detectar sobreposição histórica de atuação.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Campos físicos congelados após ativação; alteração de canal, leitor, sentido ou sensor cria nova linha/revisão.
- Além da unicidade parcial ACTIVE, intervalos efetivos do mesmo ponto/canal não podem se sobrepor; implementar exclusão temporal ou mecanismo transacional equivalente após inspeção do schema.
- Validar a função de cada canal em transação; FK prova identidade, não prova que um canal é sensor.
- Uma porta bidirecional compartilha um binding com dois leitores; não cadastrar dois bindings ativos no mesmo relé para representar os sentidos.
- Comando antigo é inválido após alteração de binding mesmo se o nome da porta não mudou.
- Topologias de múltiplos atuadores não cobertas pelo perfil exigem extensão aprovada, não duplicação informal.

### 3.8. cp.edge_agents

Instância técnica local, sua identidade, lease e estado de recuperação.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| connector_id | uuid | N | Conector existente do condomínio. |
| instance_id | uuid | N | Identidade da instância, nunca reciclada após reinstalação. |
| public_key_fingerprint | text sha256 hex | N | Fingerprint público exclusivo; chave privada não fica nesta tabela. |
| certificate_ref | text private internal reference | N | Referência do certificado público/cadeia e de sua gestão. |
| bootstrap_secret_ref | text secret reference | S | Referência técnica de uso único; nunca valor do token. |
| agent_version | text max 64 | N | Versão exata do gateway. |
| protocol_version | text max 32 | N | Versão de protocolo do canal autenticado. |
| status | enum | N | Estado técnico. Valores: PROVISIONED, ONLINE, DEGRADED, SUSPENDED, REVOKED. |
| lease_epoch | bigint | N | Fencing token monotônico do escritor autorizado. |
| last_seen_at | timestamptz | S | Último contato autenticado; não prova saúde física. |
| certificate_expires_at | timestamptz | N | Vencimento do certificado. |
| revoked_at | timestamptz | S | Instante de revogação da identidade. |
| revocation_sequence_seen | bigint | N | Última sequência autoritativa de revogação confirmada. |
| recovery_state | enum | N | Estado de reconcilição de restauração. Valores: NORMAL, RECONCILING, BLOCKED. |
| recovery_manifest_ref | text private internal reference | S | Checkpoint e evidência de recuperação do journal externo. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (instance_id). Unicidade de referência ou de negócio.
- (public_key_fingerprint). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, connector_id) → cp.connectors(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, connector_id, status) — Resolver gateway e alerta de indisponibilidade.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Múltiplas instâncias para HA só podem atuar com lease/fencing válido por destino; heartbeat não arbitra exclusividade.
- Chave privada, senha do fabricante e token de bootstrap não são retornados ao gestor.
- Revogação de identidade invalida canal; tenant do evento é derivado da identidade técnica.
- NORMAL após restore exige prova do ponto de recuperação e confirmação das ACLs físicas; parar apenas escrita não neutraliza ACL antiga.

### 3.9. cp.access_subjects

Sujeito de acesso no tenant, separado da conta de login, dos vínculos e das credenciais.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| origin_kind | enum | N | Tipo de cadastro de identidade existente. Valores: ACCOUNT, VISITOR. |
| account_id | uuid | S | Conta existente quando origin_kind ACCOUNT. |
| visitor_id | uuid | S | Pessoa visitante existente quando origin_kind VISITOR; não exige login. |
| status | enum | N | Estado do sujeito. Valores: ACTIVE, BLOCKED, ERASURE_PENDING, ARCHIVED. |
| restriction_epoch | bigint | N | Geração de restrição; somente fluxo autorizado pode elevar após reavaliação. |
| identity_verified_at | timestamptz | S | Confirmação de identidade; não prova automaticamente consentimento ou direito. |
| identity_evidence_ref | text private internal reference | S | Referência mínima da conferência, sem documento em texto. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, account_id) — apenas quando account_id presente. Unicidade de referência ou de negócio.
- (tenant_id, visitor_id) — apenas quando visitor_id presente. Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (account_id) → platform.accounts(id).
- (tenant_id, visitor_id) → cp.visitors(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, status) — Gestão de sujeitos sem exposição de biometria.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Exatamente um entre account_id e visitor_id é preenchido, coerente com origin_kind.
- Uma conta em dois condomínios produz dois sujeitos distintos, sem biometria compartilhada automaticamente.
- Nome, CPF e apartamento não são chave de pessoa do hardware.
- Identidades de visitante e conta não são mescladas por nome/face; correspondência exige verificação e evidência.
- A existência do sujeito não cria grant.

### 3.10. cp.subject_contexts

Vínculo concreto que fundamenta a tentativa; suporta múltiplos vínculos sem união permissiva automática.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| subject_id | uuid | N | Sujeito do tenant. |
| context_kind | enum | N | Categoria válida no contexto. Valores: RESIDENT, DEPENDENT, OWNER_NONRESIDENT, VISITOR, SERVICE_PROVIDER, EMPLOYEE, EMERGENCY_RESPONDER. |
| source_kind | enum | N | Origem formal do vínculo. Valores: MEMBERSHIP, VISIT_PERMIT, FUNCTIONAL_AUTHORIZATION. |
| membership_id | uuid | S | Vínculo já existente. |
| permit_id | uuid | S | Convite/autorização de visita existente. |
| functional_authorization_ref | text private typed reference | S | Autorização funcional aprovada e auditada; não texto livre que conceda acesso. |
| source_version | bigint | N | Versão observada da origem. |
| unit_id | uuid | S | Unidade contextual, quando aplicável. |
| guardian_subject_id | uuid | S | Responsável vinculado e verificado; não prova presença conjunta. |
| identity_match_evidence_ref | text private typed reference | S | Prova de correspondência quando a origem usa identidade diferente da raiz do sujeito. |
| approved_by_account_id | uuid | S | Aprovação de autorização funcional/correspondência. |
| valid_from | timestamptz | N | Início do vínculo. |
| valid_until | timestamptz | S | Fim; obrigatório para visita e autorização funcional. |
| status | enum | N | Estado da origem materializada. Valores: ACTIVE, REVOKED, EXPIRED, SUPERSEDED. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, id, subject_id). Unicidade de referência ou de negócio.
- (tenant_id, subject_id, membership_id) — apenas quando membership_id presente. Unicidade de referência ou de negócio.
- (tenant_id, subject_id, permit_id) — apenas quando permit_id presente. Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, subject_id) → cp.access_subjects(tenant_id, id).
- (tenant_id, membership_id) → cp.memberships(tenant_id, id).
- (tenant_id, permit_id) → cp.visit_permits(tenant_id, id).
- (tenant_id, unit_id) → cp.units(tenant_id, id).
- (tenant_id, guardian_subject_id) → cp.access_subjects(tenant_id, id).
- (approved_by_account_id) → platform.accounts(id).

**Índices de acesso propostos:**

- BTREE (tenant_id, subject_id, status, valid_until) — Selecionar contextos atualmente elegíveis.
- BTREE (tenant_id, membership_id) — Propagar alteração/revogação do vínculo.
- BTREE (tenant_id, permit_id) — Propagar alteração/revogação do convite.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Exatamente uma origem: membership_id, permit_id ou functional_authorization_ref, coerente com source_kind.
- Membership deve pertencer à conta do sujeito; permit deve pertencer à pessoa visitante vinculada, salvo correspondência verificada explícita. Conferência transacional obrigatória.
- Contexto de dependente sem conta não obriga a criar login fictício; reconciliar suporte do domínio atual e autorização/responsável antes de habilitar.
- Vigência não supera a origem; alteração de origem invalida projeção desatualizada.
- guardian_subject_id diferente do próprio subject_id; acompanhamento físico precisa de mecanismo adicional.
- Convite vencido não bloqueia automaticamente um contexto residencial independente; bloqueio do sujeito é transversal.

### 3.11. cp.age_evidence

Evidência restrita e revogável de idade, separada das projeções de portaria e hardware.

**Escopo:** TENANT. **Ciclo:** IMMUTABLE_EVIDENCE_WITH_REVOCATION. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| subject_id | uuid | N | Sujeito a que a evidência se refere. |
| method | enum | N | Formato mínimo da evidência. Valores: AGE_PREDICATE_ATTESTATION, ENCRYPTED_BIRTH_DATE. |
| minimum_age | smallint | S | Limite inferior de predicado atestado. |
| maximum_age | smallint | S | Limite superior de predicado atestado. |
| predicate_result | boolean | S | Resultado verificado do predicado; não inferido do rosto. |
| birth_date_ciphertext | bytea encrypted envelope | S | Data cifrada, sem coluna paralela em claro. |
| encryption_key_ref | text secret reference | S | Referência da chave de proteção de campo. |
| encryption_key_version | text max 64 | S | Versão da chave, necessária para rotação. |
| assessment_date | date | N | Data civil local usada para avaliar a idade. |
| timezone | text IANA | N | Fuso da avaliação. |
| verified_by_account_id | uuid | N | Verificador com escopo específico. |
| verified_at | timestamptz | N | Instante da verificação. |
| valid_until | timestamptz | N | Fim da confiança; considerar aniversário, mudança ou contestação. |
| source_evidence_ref | text private typed reference | N | Referência minimizada da prova; sem cópia de documento na projeção. |
| status | enum | N | Condição da evidência. Valores: VALID, REVOKED, SUPERSEDED. |
| supersedes_evidence_id | uuid | S | Evidência anterior do mesmo sujeito. |
| verification_method | enum | N | Método de conferência, separado da representação armazenada. Valores: DOCUMENT_REVIEW, TRUSTED_EXISTING_RECORD. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, id, subject_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, subject_id) → cp.access_subjects(tenant_id, id).
- (verified_by_account_id) → platform.accounts(id).
- (tenant_id, supersedes_evidence_id, subject_id) → cp.age_evidence(tenant_id, id, subject_id).

**Índices de acesso propostos:**

- BTREE (tenant_id, subject_id, status, valid_until) — Selecionar evidência atual com acesso restrito.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- AGE_PREDICATE_ATTESTATION exige pelo menos um limite, predicate_result e ausência de data cifrada/chave; ENCRYPTED_BIRTH_DATE exige ciphertext/chave e não duplica atestação na mesma linha.
- Idades são limites plausíveis definidos pelo esquema; maximum_age não pode ser inferior a minimum_age.
- Data, prova e chave não aparecem no JWT, no payload do dispositivo, no log ou na tela da portaria.
- valid_until posterior a verified_at; status VALID não dispensa conferência do prazo.
- Evidência não concede acesso isoladamente e não estima idade por biometria.

### 3.12. cp.access_policies

Identidade estável da regra de uma área e ponteiro para sua revisão publicada.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| area_id | uuid | N | Área da política. |
| name | text max 160 | N | Nome de exibição. |
| status | enum | N | Estado do conjunto de regras. Valores: DRAFT, ACTIVE, SUSPENDED, RETIRED. |
| current_published_version_id | uuid | S | Revisão atualmente publicada; deve pertencer a esta política. |
| envelope_acceptance_id | uuid | S | Aceite técnico que delimita capacidades permitidas na instalação. |
| envelope_hash | text sha256 hex | S | Hash do envelope técnico aplicado. |
| created_by_account_id | uuid | N | Autor gestor autorizado. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, area_id). Unicidade de referência ou de negócio.
- (tenant_id, id, area_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, area_id) → cp.access_areas(tenant_id, id).
- (tenant_id, current_published_version_id, id) → cp.access_policy_versions(tenant_id, id, policy_id).
- (tenant_id, envelope_acceptance_id) → cp.installation_acceptances(tenant_id, id).
- (created_by_account_id) → platform.accounts(id).

**Índices de acesso propostos:**

- BTREE (tenant_id, status) — Listar políticas operacionais.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Uma identidade de política por área; múltiplas condições/perfis ficam em revisões validadas dessa regra.
- Ponteiro só aponta para revisão publicada da própria política e do tenant.
- Política exige compatibilidade com todos os destinos da área; um único terminal compatível não aprova o conjunto.
- Suspender política dispara revogação/reconciliação de direitos derivados, sem apagar histórico.

### 3.13. cp.access_policy_versions

Documento de regra validado, com publicação imutável e autoria registrada.

**Escopo:** TENANT. **Ciclo:** IMMUTABLE_PUBLISHED_DOCUMENT. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| policy_id | uuid | N | Política lógica. |
| revision_number | integer | N | Revisão positiva e monotônica por política. |
| state | enum | N | Estado da revisão. Valores: DRAFT, VALIDATED, APPROVED, PUBLISHED, SUPERSEDED, REJECTED. |
| document_schema_version | text max 32 | N | Versão do esquema fechado de regras. |
| document | jsonb schema access_policy_document_v1 | N | Categorias, idade, horários, reserva, convite, acompanhamento, offline e exceções em DSL restrita. |
| canonical_hash | text sha256 hex | N | Hash sobre documento canônico e versão do esquema. |
| envelope_hash | text sha256 hex | S | Envelope técnico contra o qual foi validada. |
| validation_report_ref | text private internal reference | S | Erros, incompatibilidades e simulação aprovada. |
| authored_by_account_id | uuid | N | Autor da revisão. |
| approved_by_account_id | uuid | S | Gestor competente que aprova as regras. |
| published_at | timestamptz | S | Instante em que virou revisão publicada. |
| valid_from | timestamptz | S | Início de vigência pretendida; obrigatório ao publicar. |
| valid_until | timestamptz | S | Fim opcional da regra. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, policy_id, revision_number). Unicidade de referência ou de negócio.
- (tenant_id, id, policy_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, policy_id) → cp.access_policies(tenant_id, id).
- (authored_by_account_id) → platform.accounts(id).
- (approved_by_account_id) → platform.accounts(id).

**Índices de acesso propostos:**

- BTREE (tenant_id, policy_id, state, revision_number) — Histórico e publicação.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Publicado congela documento, hash, esquema, vigência, autoria e aprovação; edição cria nova linha/revisão.
- JSON validado com campos permitidos, tipos, enums, limites de tamanho/profundidade, fuso e referências no mesmo tenant. Não é editor de SQL/JavaScript, código, endpoint ou expressão arbitrária.
- VALIDATED/PUBLISHED exigem validação de capacidade contra todos os bindings; UNKNOWN não é aprovação.
- Publicar cria outbox na mesma transação da alteração do ponteiro. Estado PUBLISHED não comprova aplicação nos terminais.
- Condições em outros registros devem ser verificadas por FKs e transação/constraints apropriadas, não CHECK com consulta a outras tabelas.
- APPROVED representa aceite da regra antes da publicação; SUPERSEDED é metadado de substituição que preserva conteúdo, hash e assinatura imutáveis da versão publicada.

### 3.14. cp.access_grants

Direito derivado de origem identificável, por sujeito, contexto, área, política e janela.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| subject_id | uuid | N | Sujeito autorizado. |
| context_id | uuid | N | Contexto específico do sujeito. |
| area_id | uuid | N | Área destino. |
| policy_id | uuid | N | Política da mesma área. |
| policy_version_id | uuid | N | Revisão publicada usada na derivação. |
| origin_kind | enum | N | Origem principal rastreável. Valores: MEMBERSHIP, VISIT_PERMIT, RESERVATION, FUNCTIONAL_AUTHORIZATION, MANUAL_EXCEPTION. |
| origin_membership_id | uuid | S | Vínculo de origem. |
| origin_permit_id | uuid | S | Convite de origem. |
| origin_reservation_id | uuid | S | Reserva de origem. |
| origin_functional_context_id | uuid | S | Contexto com autorização funcional de origem. |
| exception_audit_event_id | uuid | S | Aceite de exceção temporária registrado em auditoria. |
| origin_version | bigint | N | Versão da origem usada para gerar o direito. |
| age_evidence_id | uuid | S | Evidência etária aplicada, quando a política exige. |
| valid_from | timestamptz | N | Início do direito. |
| valid_until | timestamptz | N | Fim explícito; concessão derivada não é infinita. |
| generation | bigint | N | Geração monotônica do direito para sincronização. |
| subject_restriction_epoch | bigint | N | Época de restrição do sujeito lida na derivação. |
| derivation_key | text sha256 hex | N | Chave determinística de derivação idempotente. |
| status | enum | N | Estado desejado do direito. Valores: PENDING, ACTIVE, REVOKED, EXPIRED, SUPERSEDED. |
| revoked_at | timestamptz | S | Instante de revogação lógica. |
| reason_codes | text array allowlist | N | Fundamentos da derivação ou restrição, sem dados sensíveis em texto livre. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, derivation_key). Unicidade de referência ou de negócio.
- (tenant_id, id, subject_id). Unicidade de referência ou de negócio.
- (tenant_id, id, area_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, subject_id) → cp.access_subjects(tenant_id, id).
- (tenant_id, context_id, subject_id) → cp.subject_contexts(tenant_id, id, subject_id).
- (tenant_id, area_id) → cp.access_areas(tenant_id, id).
- (tenant_id, policy_id, area_id) → cp.access_policies(tenant_id, id, area_id).
- (tenant_id, policy_version_id, policy_id) → cp.access_policy_versions(tenant_id, id, policy_id).
- (tenant_id, origin_membership_id) → cp.memberships(tenant_id, id).
- (tenant_id, origin_permit_id) → cp.visit_permits(tenant_id, id).
- (tenant_id, origin_reservation_id) → cp.reservations(tenant_id, id).
- (tenant_id, origin_functional_context_id, subject_id) → cp.subject_contexts(tenant_id, id, subject_id).
- (tenant_id, exception_audit_event_id) → cp.audit_events(tenant_id, id).
- (tenant_id, age_evidence_id, subject_id) → cp.age_evidence(tenant_id, id, subject_id).

**Índices de acesso propostos:**

- BTREE (tenant_id, subject_id, status, valid_until) — Leitura de direitos do sujeito.
- BTREE (tenant_id, area_id, policy_version_id, status) — Recompilar após mudança de política.
- BTREE (tenant_id, origin_membership_id) — Revogação por origem.
- BTREE (tenant_id, origin_permit_id) — Revogação de convite.
- BTREE (tenant_id, origin_reservation_id) — Cancelamento de reserva.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Exatamente um campo de origem principal corresponde a origin_kind. A presença de credencial facial nunca é origem de grant.
- Contexto e origem devem pertencer ao mesmo sujeito/unidade autorizados; reserva de visitante também respeita seu permit contextual.
- valid_until não supera contexto, convite, reserva, exceção ou evidência limitante; usar a interseção efetiva.
- Revogar origem invalida descendentes e gera marcador durável e outbox prioritário.
- ACTIVE significa desejo lógico vigente, não confirmação em todas as portas.
- Reativação não edita marcador antigo; cria nova geração após reavaliação e epoch autorizada.

### 3.15. cp.policy_deployments

Uma linha por destino de uma publicação; operation_id agrega o lote sem esconder falhas parciais.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| operation_id | uuid | N | Identificador compartilhado das linhas do mesmo lote. |
| policy_id | uuid | N | Política da publicação. |
| policy_version_id | uuid | N | Revisão publicada a aplicar. |
| target_binding_id | uuid | N | Destino físico imutável desta operação. |
| binding_revision | integer | N | Revisão física capturada. |
| requested_by_account_id | uuid | N | Gestor ou técnico autorizado. |
| desired_generation | bigint | N | Geração de publicação. |
| desired_hash | text sha256 hex | N | Hash do estado compilado desejado no destino. |
| observed_hash | text sha256 hex | S | Hash normalizado de estado lido, quando possível. |
| state | enum | N | Estado individual do destino. Valores: PENDING, PUBLISHING, APPLIED_UNVERIFIED, CONFIRMED, FAILED, OFFLINE, UNKNOWN. |
| confirmation_level | enum | N | Força da prova obtida. Valores: NONE, TRANSPORT_ACK, STATE_READ, PHYSICAL_TEST. |
| command_id | uuid | S | Comando existente que executa a publicação. |
| last_observed_at | timestamptz | S | Última conferência do estado. |
| next_attempt_at | timestamptz | S | Próxima tentativa segura de configuração; não usada para repetir abertura. |
| last_error_code | text max 64 allowlist | S | Erro normalizado sem segredo. |
| processing_phase | enum | N | Fase interna do worker, distinta do estado público de aplicação no destino. Valores: PLANNED, COMPILED, QUEUED, DISPATCHED, FINALIZED, SUPERSEDED. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, operation_id, target_binding_id). Unicidade de referência ou de negócio.
- (tenant_id, id, target_binding_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, policy_id) → cp.access_policies(tenant_id, id).
- (tenant_id, policy_version_id, policy_id) → cp.access_policy_versions(tenant_id, id, policy_id).
- (tenant_id, target_binding_id, binding_revision) → cp.point_bindings(tenant_id, id, binding_revision).
- (requested_by_account_id) → platform.accounts(id).
- (tenant_id, command_id) → cp.integration_commands(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, operation_id, state) — Calcular progresso real do lote.
- BTREE (tenant_id, state, next_attempt_at) — Processar trabalhos pendentes.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- O conjunto de destinos do lote é congelado na criação; mudança de topologia exige nova operação.
- Resumo de lote é calculado sobre todas as linhas; não marcar sucesso global se houver FAILED/UNKNOWN.
- ACK de transporte sozinho não equivale a CONFIRMED de regra aplicada; nível mínimo depende do perfil aprovado.
- Uma revisão antiga não pode sobrescrever geração mais nova nem remover revogação posterior.
- processing_phase é interno. PENDING cobre planejamento/fila; PUBLISHING cobre envio; APPLIED_UNVERIFIED indica aceite sem prova suficiente. OFFLINE exige evidência de desconexão. Operação superada usa FAILED com last_error_code SUPERSEDED e não entra como sucesso global.

### 3.16. cp.entitlement_syncs

Estado desejado e observado do direito em canal/revisão, separado da cópia da credencial.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| grant_id | uuid | N | Direito a sincronizar. |
| device_id | uuid | N | Equipamento dono do canal. |
| channel_id | uuid | N | Canal físico do direito. |
| binding_id | uuid | N | Revisão de instalação do destino. |
| deployment_id | uuid | S | Publicação de política que prepara esse destino, quando aplicável. |
| desired_generation | bigint | N | Geração do grant que deveria vigorar. |
| observed_generation | bigint | S | Geração realmente identificada; pode indicar drift. |
| desired_state | enum | N | Intenção atual. Valores: ALLOW, REVOKE. |
| observed_state | enum | N | Estado lido, sem presumir por ACK. Valores: UNKNOWN, ALLOW, REVOKED, ABSENT, PARTIAL. |
| state | enum | N | Condição de sincronização. Valores: PENDING, SENT, CONFIRMED, FAILED, UNKNOWN, DRIFT, REVOKED. |
| desired_hash | text sha256 hex | N | Representação normalizada desejada. |
| observed_hash | text sha256 hex | S | Estado observado ou null quando não legível. |
| proof_kind | enum | N | Evidência de aplicação. Valores: NONE, TRANSPORT_ACK, STATE_READ, NEGATIVE_ACCESS_TEST. |
| confirmed_at | timestamptz | S | Confirmação sustentada por evidência. |
| evidence_event_id | uuid | S | Evento factual relacionado à prova. |
| next_attempt_at | timestamptz | S | Próxima tentativa idempotente permitida. |
| last_error_code | text max 64 allowlist | S | Erro normalizado. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, grant_id, channel_id, binding_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, grant_id) → cp.access_grants(tenant_id, id).
- (tenant_id, channel_id, device_id) → cp.device_channels(tenant_id, id, device_id).
- (tenant_id, binding_id, channel_id) → cp.point_bindings(tenant_id, id, actuator_channel_id).
- (tenant_id, deployment_id, binding_id) → cp.policy_deployments(tenant_id, id, target_binding_id).
- (tenant_id, evidence_event_id) → cp.access_events(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, grant_id, state) — Exibir destinos efetivamente prontos.
- BTREE (tenant_id, device_id, state) — Reconciliar equipamento.
- BTREE (tenant_id, state, next_attempt_at) — Priorizar revogações e pendências.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Direito deve alcançar área permitida através do ponto/sentido aprovado; FK não substitui essa validação de domínio.
- CONFIRMED exige correspondência de geração/hash e prova suficiente; callbacks antigos não avançam a geração atual.
- Geração observada maior que a esperada é DRIFT e deve ser investigada, não descartada ou truncada.
- Credencial presente e direito presente são estados distintos; preservar cp.credential_syncs para a primeira função.

### 3.17. cp.access_decisions

Decisão ou observação de decisão sobre tentativa; não prova abertura ou passagem.

**Escopo:** TENANT. **Ciclo:** APPEND_ONLY_DECISION. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| attempt_id | uuid | N | Identificador interno da tentativa normalizada. |
| evaluation_sequence | integer | N | Sequência de reavaliação da mesma tentativa. |
| subject_id | uuid | S | Sujeito reconhecido; pode ser desconhecido numa negativa. |
| context_id | uuid | S | Contexto selecionado. |
| grant_id | uuid | S | Direito avaliado. |
| point_id | uuid | N | Passagem identificada. |
| binding_id | uuid | N | Revisão física conhecida no momento. |
| target_area_id | uuid | N | Área realmente pretendida conforme sentido. |
| travel_direction | enum | N | Sentido da tentativa. Valores: FROM_TO, TO_FROM, UNKNOWN. |
| policy_id | uuid | S | Política avaliada quando identificável. |
| policy_version_id | uuid | S | Revisão usada. |
| result | enum | N | Resultado canônico do manual/API: REQUIRE_ASSISTANCE exige atendimento; UNAVAILABLE significa falha de avaliação; ambos proíbem atuação automática. Valores: ALLOW, DENY, REQUIRE_ASSISTANCE, UNAVAILABLE. |
| decision_role | enum | N | Distingue enforcement, observação e simulação. Valores: AUTHORITATIVE, OBSERVATION, SIMULATION. |
| origin | enum | N | Onde a avaliação aconteceu. Valores: BACKEND, EDGE, DEVICE, MANUAL. |
| enforcement_phase | enum | N | Evidência da posição temporal da decisão. Valores: BEFORE_RELAY, AFTER_RELAY_EVENT, UNKNOWN, NOT_APPLICABLE. |
| reason_codes | text array allowlist | N | Motivos minimizados. |
| facts_snapshot | jsonb schema decision_facts_v1 | N | Versões e fatos mínimos; sem DOB, foto, segredo ou payload livre. |
| subject_version_seen | bigint | S | Versão de sujeito usada na decisão. |
| revocation_sequence_seen | bigint | N | Ponto autoritativo de revogação considerado. |
| decided_at | timestamptz | N | Instante da decisão. |
| expires_at | timestamptz | S | Fim da validade; obrigatório para ALLOW acionável. |
| request_hash | text sha256 hex | N | Hash do pedido minimizado para idempotência. |
| source_event_id | uuid | S | Evento do equipamento quando a linha é observação. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, attempt_id, evaluation_sequence). Unicidade de referência ou de negócio.
- (tenant_id, id, binding_id). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, subject_id) → cp.access_subjects(tenant_id, id).
- (tenant_id, context_id, subject_id) → cp.subject_contexts(tenant_id, id, subject_id).
- (tenant_id, grant_id, subject_id) → cp.access_grants(tenant_id, id, subject_id).
- (tenant_id, grant_id, target_area_id) → cp.access_grants(tenant_id, id, area_id).
- (tenant_id, binding_id, point_id) → cp.point_bindings(tenant_id, id, point_id).
- (tenant_id, target_area_id) → cp.access_areas(tenant_id, id).
- (tenant_id, policy_id) → cp.access_policies(tenant_id, id).
- (tenant_id, policy_version_id, policy_id) → cp.access_policy_versions(tenant_id, id, policy_id).
- (tenant_id, source_event_id) → cp.access_events(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, point_id, decided_at) — Auditar decisões no ponto.
- BTREE (tenant_id, subject_id, decided_at) — Consulta restrita do histórico do sujeito.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- context_id/grant_id presentes exigem subject_id. policy_version_id exige policy_id.
- ALLOW acionável exige papel AUTHORITATIVE, fase BEFORE_RELAY, capacidade homologada e expires_at futuro curto.
- OBSERVATION registra retorno do terminal sem afirmar que CondPulse decidiu antes do relé. SIMULATION nunca gera acionamento.
- Nova reavaliação cria evaluation_sequence; não reescrever decisão histórica.
- ALLOW é decisão, não evento RELAY_TRIGGERED, DOOR_OPENED ou PASSAGE_DETECTED.
- Fatos e versões lidos consistentemente; revalidar antes do envio sensível, reconhecendo a corrida residual com hardware remoto.
- DENY, REQUIRE_ASSISTANCE e UNAVAILABLE não autorizam acionamento automático. Em hardware binário, todos resultam em não abertura automática; manter o motivo distinto e procedimento de atendimento.
- O envio de cp.access_decisions por /v1/edge/decisions é telemetria de decisão já produzida localmente, não RPC síncrono que faça a liberação depender de ida à nuvem.

### 3.18. cp.access_command_receipts

Registro persistente de abertura pontual e de incerteza, ligado ao comando já existente.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| command_id | uuid | N | Comando OPEN_DOOR em cp.integration_commands. |
| point_id | uuid | N | Destino físico autorizado. |
| binding_id | uuid | N | Binding imutável que o pedido autorizou. |
| decision_id | uuid | N | Decisão ALLOW acionável do mesmo binding. |
| issuer_kind | enum | N | Tipo de solicitante identificado. Valores: ACCOUNT, EDGE_AGENT, SYSTEM_SERVICE. |
| issuer_account_id | uuid | S | Conta humana solicitante. |
| issuer_edge_agent_id | uuid | S | Gateway emissor autenticado. |
| issuer_service_code | text allowlist | S | Identidade interna de serviço, nunca texto livre do cliente. |
| nonce_hash | text sha256 hex | N | Hash de nonce de uso único; impede replay local. |
| payload_hash | text sha256 hex | N | Hash do comando exato; replay com conteúdo diferente conflita. |
| idempotency_record_id | uuid | S | Registro de idempotência da API existente. |
| fencing_token | bigint | N | Época do escritor autorizado no destino. |
| issued_at | timestamptz | N | Criação do comando. |
| expires_at | timestamptz | N | Prazo curto absoluto, sem extensão automática. |
| sent_at | timestamptz | S | Instante de envio efetivo. |
| acknowledged_at | timestamptz | S | ACK de recebimento, sem presumir efeito físico. |
| state | enum | N | Estado do envio/recebimento. Valores: RECEIVED, REJECTED, DISPATCH_COMMITTED, SENT, CONFIRMED, UNKNOWN, EXPIRED. |
| send_attempts | integer | N | Tentativas de envio comprometidas no transmissor; não comprova recebimento ou atuação física. |
| actuation_event_id | uuid | S | Evidência de acionamento, se efetivamente recebida. |
| door_open_event_id | uuid | S | Contato de porta aberta, se disponível. |
| passage_event_id | uuid | S | Passagem detectada por sensor apropriado, se disponível. |
| receipt_evidence_ref | text private internal reference | S | Evidência minimizada do adaptador. |
| dispatch_committed_at | timestamptz | S | Commit persistente da intenção de transmissão no agente que envia; anterior ao efeito de rede. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, command_id). Unicidade de referência ou de negócio.
- (tenant_id, nonce_hash). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, command_id) → cp.integration_commands(tenant_id, id).
- (tenant_id, binding_id, point_id) → cp.point_bindings(tenant_id, id, point_id).
- (tenant_id, decision_id, binding_id) → cp.access_decisions(tenant_id, id, binding_id).
- (issuer_account_id) → platform.accounts(id).
- (tenant_id, issuer_edge_agent_id) → cp.edge_agents(tenant_id, id).
- (tenant_id, idempotency_record_id) → cp.idempotency_keys(tenant_id, id).
- (tenant_id, actuation_event_id) → cp.access_events(tenant_id, id).
- (tenant_id, door_open_event_id) → cp.access_events(tenant_id, id).
- (tenant_id, passage_event_id) → cp.access_events(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, point_id, issued_at) — Auditoria de aberturas.
- BTREE (tenant_id, state, expires_at) — Expirar pedidos sem reenviar.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Exatamente uma identidade de emissor corresponde a issuer_kind.
- command_id deve ser OPEN_DOOR do mesmo conector/dispositivo/binding; validar em transação e gateway.
- Decisão SIMULATION/OBSERVATION ou resultado diferente de ALLOW não pode produzir recibo acionável.
- Sem idempotência física comprovada no perfil, no máximo um envio efetivo; timeout após envio torna UNKNOWN e não gera retry cego.
- CONFIRMED confirma o protocolo do comando e não significa abertura nem passagem; eventos devem ter tipo, ponto, instante e força de correlação coerentes.
- Comando vencido ou binding substituído é rejeitado mesmo após restore/reconexão.
- Nenhum exatamente-uma-vez físico é prometido apenas pela unicidade no banco.
- DISPATCH_COMMITTED é registrado duravelmente no transmissor antes do envio. Crash nessa janela mantém envio incerto e proíbe retransmissão cega; cancelamento antes de envio usa REJECTED e motivo específico.
- CONFIRMED do recibo não é CONFIRMED de política/direito aplicado: os objetos têm provas diferentes. physicalEvidence deriva exclusivamente de eventos apropriados.

### 3.19. cp.revocation_markers

Marca de revogação monotônica e reconstruível após backup, sem reativação por replay de estado antigo.

**Escopo:** TENANT. **Ciclo:** APPEND_ONLY_REVOCATION_CORE. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| target_kind | enum | N | Alvo da revogação. Valores: SUBJECT, GRANT. |
| subject_id | uuid | S | Sujeito inteiro quando target_kind SUBJECT. |
| grant_id | uuid | S | Direito específico quando target_kind GRANT. |
| source_context_id | uuid | S | Origem cujo término causou a revogação. |
| revoked_through_version | bigint | N | Geração/época do alvo que não pode ressuscitar. |
| effective_at | timestamptz | N | Instante a partir do qual a revogação é válida. |
| reason_code | text allowlist | N | Fundamento controlado. |
| operation_id | uuid | N | Operação que agrega marcas correlatas. |
| dedupe_key | text sha256 hex | N | Identidade estável da marca para reaplicação idempotente. |
| journal_stream_id | text max 128 | N | Identidade estável do journal autoritativo externo ou fluxo WAL preservado. |
| journal_sequence | bigint | N | Sequência autoritativa do fluxo, não contador local reiniciado no restore. |
| entry_hash | text sha256 hex | N | Integridade canônica do registro. |
| previous_entry_hash | text sha256 hex | S | Encadeamento quando o mecanismo adotado oferece essa propriedade. |
| external_durability_status | enum | N | Estado da confirmação externa. Valores: PENDING, CONFIRMED, UNVERIFIED. |
| journal_receipt_ref | text private internal reference | S | Comprovante do armazenamento fora do snapshot restaurado. |
| journal_persisted_at | timestamptz | S | Instante da confirmação da durabilidade externa. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, dedupe_key). Unicidade de referência ou de negócio.
- (tenant_id, journal_stream_id, journal_sequence). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, subject_id) → cp.access_subjects(tenant_id, id).
- (tenant_id, grant_id) → cp.access_grants(tenant_id, id).
- (tenant_id, source_context_id) → cp.subject_contexts(tenant_id, id).

**Índices de acesso propostos:**

- BTREE (tenant_id, subject_id, effective_at) — Aplicar bloqueios transversais.
- BTREE (tenant_id, grant_id, effective_at) — Consultar revogação do grant.
- BTREE (tenant_id, external_durability_status, journal_sequence) — Conferir lacunas de recuperação.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- Exatamente um alvo, coerente com target_kind; marca nunca é apagada ou transformada em permissão.
- Core da marca é imutável; somente confirmação de durabilidade pode avançar com auditoria.
- Persistir mudança e outbox na mesma transação e confirmar journal externo; gravar tudo apenas no mesmo banco/snapshot não resolve perda pós-backup.
- Se sequência autoritativa externa não estiver disponível na criação, reservar identidade/ordem no serviço de journal antes de confirmar operação; desenho final deve provar atomicidade/idempotência, sem inventar sequência recuperável.
- Restore sem ponto autoritativo comprovado entra em RECONCILING/BLOCKED. Direitos restaurados não são presumidos válidos.
- Reativação legítima exige nova geração e autorização; não invalida a marca que protege versões antigas.
- Fonte externa precisa de retenção, controle de acesso, backup e verificação de integridade; hash sozinho não garante durabilidade.

### 3.20. cp.installation_acceptances

Aceite de instalação por revisão, com aprovação técnica e das regras e referência a evidências.

**Escopo:** TENANT. **Ciclo:** MUTABLE_CAS. **PK:** id.

| Campo | Tipo lógico | Nulo? | Significado |
|---|---|---|---|
| installation_revision | integer | N | Revisão positiva única de instalação do tenant. |
| status | enum | N | Situação do aceite. Valores: DRAFT, TESTING, APPROVED, REJECTED, SUSPENDED, SUPERSEDED. |
| plant_ref | text private internal reference | N | Planta e rotas completas. |
| plant_hash | text sha256 hex | N | Hash da planta aprovada. |
| topology_manifest_ref | text private internal reference | N | Manifesto fechado e versionado de pontos/bindings; referências verificadas no mesmo tenant. |
| topology_hash | text sha256 hex | N | Hash do manifesto. |
| capability_envelope_hash | text sha256 hex | N | Envelope técnico que limita regras publicáveis. |
| test_suite_version | text max 64 | N | Versão do roteiro executado. |
| test_report_ref | text private internal reference | N | Relatório de ensaios com evidência. |
| critical_tests_pending | integer | N | Quantidade calculada dos testes críticos pendentes. |
| critical_tests_failed | integer | N | Quantidade calculada dos testes críticos reprovados. |
| technical_grant_id | uuid | S | Delegação técnica do aprovador no tenant. |
| technical_approver_account_id | uuid | S | MASTER de implantação que assina. |
| technical_signed_at | timestamptz | S | Assinatura técnica. |
| rules_approver_membership_id | uuid | S | Vínculo competente do gestor que aceita as regras. |
| rules_approver_account_id | uuid | S | Conta do gestor; deve corresponder ao membership informado. |
| rules_signed_at | timestamptz | S | Assinatura das regras. |
| emergency_test_ref | text private internal reference | N | Teste do circuito de egresso/emergência. |
| rollback_plan_ref | text private internal reference | N | Plano e ensaio de retorno seguro. |
| revocation_checkpoint_ref | text private internal reference | S | Ponto de recuperação e revogações verificado para ativação. |
| effective_at | timestamptz | S | Início da autorização local. |
| review_due_at | timestamptz | S | Revisão prevista. |

**Chaves UNIQUE propostas:**

- (tenant_id, id). Unicidade de referência ou de negócio.
- (tenant_id, installation_revision). Unicidade de referência ou de negócio.

**Referências:**

- (tenant_id) → platform.tenants(id).
- (tenant_id, technical_grant_id, technical_approver_account_id) → cp.technical_grants(tenant_id, id, account_id).
- (technical_approver_account_id) → platform.accounts(id).
- (tenant_id, rules_approver_membership_id) → cp.memberships(tenant_id, id).
- (rules_approver_account_id) → platform.accounts(id).

**Índices de acesso propostos:**

- BTREE (tenant_id, status, installation_revision) — Resolver última instalação aprovada e histórico.

**Invariantes e aplicação:**

- id e tenant_id, quando presente, são imutáveis. version é positiva. Nenhuma cascata apaga evidência operacional.
- APPROVED exige zero testes críticos pendentes/reprovados, assinaturas válidas, revisão física e perfil central HOMOLOGADO.
- Vínculo do gestor deve corresponder à conta e competência; validar situação e escopo no instante da assinatura.
- Papel técnico e papel de negócio são verificados separadamente; política de dupla aprovação define pessoas distintas quando exigido.
- Conteúdo aprovado e assinaturas são imutáveis; nova instalação cria nova revisão. Suspensão preserva evidência.
- Campos contadores são derivados do relatório fechado, não editáveis livremente para obter aprovação.
- Ciclo de criação: aceite DRAFT, bindings DRAFT vinculados, testes, assinaturas, aprovação e ativação atômica das referências permitidas.

## 4. Integridade que não cabe em uma FK simples

Uma FK garante existência e, quando composta, vínculo com o tenant. Ela não prova que o canal é uma saída de porta, que o aprovador é competente, que o convite pertence à pessoa reconhecida ou que o direito ainda está válido. Essas condições precisam de verificação transacional e mecanismos de banco adequados, além de teste de concorrência.

Nas FKs com IDs opcionais, tenant_id continua obrigatório. O registro só pode omitir a relação inteira nas situações permitidas. Por exemplo, context_id preenchido exige subject_id; versão de política exige policy_id; assinatura técnica exige conta e concessão correspondente. Não depender do comportamento de null em uma FK composta para aceitar vínculo parcial.

A exclusividade física exige uma topologia ativa por ponto/canal e intervalos históricos sem sobreposição. A unicidade parcial do estado ACTIVE não basta para provar que dois intervalos antigos nunca conflitaram. Escolher exclusão temporal ou mecanismo equivalente após confirmar versão, extensões e carga do PostgreSQL instalado.

As dependências circulares são intencionais: criar política sem ponteiro, criar/validar versão e publicar atomicamente; criar aceite e bindings em rascunho, executar os testes e ativar somente após assinaturas. Não remover FKs para resolver a ordem de criação.

## 5. Publicação, confirmação e concorrência

Cada linha de cp.policy_deployments representa um destino físico. operation_id agrega o lote, permitindo expor pendências individuais. O estado global é uma projeção do conjunto de destinos congelado no início da operação; um destino com ACK não representa aplicação em todos os equipamentos.

cp.entitlement_syncs registra direitos. cp.credential_syncs continua registrando credenciais. A biometria pode estar presente sem grant; o grant pode estar publicado com a foto ainda rejeitada. A interface deve mostrar essa diferença.

Os consumidores verificam generation, restriction_epoch, binding_revision e revogações antes da escrita externa. O retorno atrasado de uma operação antiga não sobrescreve o estado novo. O relógio da fila não autoriza atrasar abertura: OPEN_DOOR vencido é descartado.

cp.access_decisions diferencia decisão autoritativa, observação de decisão do fabricante e simulação. Nenhuma dessas linhas, isoladamente, comprova abertura ou passagem. Os recibos e eventos correlacionados devem manter o nível de evidência e a incerteza.

## 6. Revogação após restauração de backup

- Registrar snapshot e último checkpoint que ele contém.
- Obter último checkpoint autoritativo sem depender apenas do banco restaurado.
- Reaplicar marcadores posteriores de forma idempotente e detectar lacunas.
- Recalcular grants de origens ainda válidas e preservar epochs de restrição.
- Reconciliar ACLs reais; conter fisicamente pontos com ACL antiga quando necessário.
- Invalidar OPEN_DOOR vencidos e leases/fencing antigos.
- Somente então sair de RECONCILING/BLOCKED, com aceite e evidência.

**Limite operacional:** nenhuma estrutura de banco faz um terminal desconectado receber uma revogação instantaneamente. Direitos locais precisam de expiração/fallback homologados e procedimento de contenção. Bloquear novas escritas no gateway não elimina uma ACL já gravada no leitor.

**Journal:** a ordem autoritativa deve sobreviver ao snapshot restaurado. Um contador salvo só no mesmo banco pode regredir após restore. Usar fonte durável comprovada, protocolo de reserva/confirmação/idempotência e checkpoint externo; registrar lacunas como bloqueio de recuperação. O JSON não implementa esse serviço.

## 7. Validação dos documentos JSON e proteção das regras

Definir esquemas fechados para policy document, capabilities, topology, decision facts e manifests. Eles controlam campos permitidos, tipos, enums, tamanho, profundidade e referências. Validação sintática não basta: validar tenant, categorias, fuso, sobreposição temporal, fonte de idade, capacidade de cada destino, limites de hardware e comportamento offline.

Não aceitar SQL, JavaScript, expressões interpretadas, rotas de fabricante, URLs de comando ou nomes de segredo dentro da regra do gestor. Publicar somente pelo endpoint autorizado, com versão esperada, idempotência, simulação, hash e envelope técnico. A representação JSON é um formato interno, não acesso administrativo irrestrito.

## 8. Gates de implementação e testes pendentes

- Inventariar schema/migrations reais e reaproveitar equivalentes.
- Validar chaves UNIQUE completas de todas as FKs antes de definir DDL.
- Resolver ciclo de criação de políticas/versões e aceites/bindings.
- Criar somente migration incremental pelo processo vigente do repositório.
- Testar dois tenants, referências cruzadas, papéis e pool de conexões.
- Testar exclusividade temporal de canais e CAS de publicação.
- Testar origem expirada, revogada e corrida com envio/retorno tardio.
- Testar restore com journal posterior ao backup e equipamento com ACL antiga.
- Medir consultas com população realista; revisar índices/particionamento.
- Piloto físico e aceite MASTER/gestor antes de ativar escrita em produção.

## 9. Verificação realizada nesta entrega

- Contagem: 20 entidades propostas, exatamente os nomes do capítulo 13, sem duplicatas.
- Campos: 392 definições expandidas no JSON.
- Cada FK para nova entidade foi conferida estruturalmente contra PK ou UNIQUE completa, sem depender de índice parcial.
- Cada FK entre entidades cp inclui tenant_id.
- Referências para a base foram confrontadas com uma lista explícita de chaves exigidas; sua existência em produção continua pendente.
- JSON lido e validado estruturalmente; nenhum schema, índice, RLS, migration, query de desempenho ou teste físico foi executado.

**Próximo passo de engenharia:** inspecionar o repositório e o banco atuais, reconciliar equivalentes, resolver os contratos pendentes e gerar a migration incremental pelo processo oficial do projeto. Esta proposta não autoriza aplicação cega sobre produção.

## 10. Correspondência explícita entre modelo lógico, manual e API

Os nomes internos não podem ser convertidos por semelhança de texto. Usar as seguintes correspondências e manter os motivos/níveis de prova.

| Conceito | Campo lógico | Contrato público e significado |
|---|---|---|
| Modos de operação | platform.access_homologations.decision_mode | DEVICE_LOCAL_ACL / EDGE_ONLINE / PROVIDER_LOCAL / ASSISTED, sem aliases silenciosos. ASSISTED é procedimento operacional. |
| Resultado da avaliação | cp.access_decisions.result | ALLOW / DENY / REQUIRE_ASSISTANCE / UNAVAILABLE; apenas ALLOW autoritativo vigente pode fundamentar atuação automática. |
| Estados de homologação | platform.access_homologations.status | Mesmos oito estados públicos; bancada e piloto não são produção homologada. |
| IDs e números de revisão | cp.point_bindings / cp.installation_acceptances / cp.access_policy_versions | bindingRevision é binding_revision integer por ponto; installationRevision é installation_revision integer por tenant. policyVersion é UUID de access_policy_versions.id; revision_number é sequência de exibição por política. IDs de recursos continuam UUID. |
| Binding bidirecional | cp.point_bindings.entry_reader_device_id / exit_reader_device_id | Um vínculo físico por ponto/atuador. Os dois leitores integram a mesma revisão; alterar qualquer lado cria nova revisão, nunca segundo ACTIVE no mesmo relé. |
| Sentido | cp.access_points.flow_direction / cp.access_decisions.travel_direction | FROM_TO e TO_FROM são orientação geométrica interna; BOTH projeta BIDIRECTIONAL. IN/OUT depende do direction_map validado. UNKNOWN nunca é convertido em IN/OUT para conveniência. |
| Tipo do ponto | cp.access_points.point_kind | DOOR e TURNSTILE são diretos. VEHICLE_GATE e PEDESTRIAN_GATE projetam GATE preservando subtipo no cadastro técnico. ELEVATOR_CONTROL e OTHER não recebem projeção automática como porta/passage: dependem de contrato e homologação específicos. |
| Ciclo documental do modelo | platform.access_model_profiles.status / market_lifecycle | lifecycle projeta market_lifecycle; status é estado interno do registro, não prova disponibilidade comercial nem aprovação de integração. |
| Publicação por destino | cp.policy_deployments.state / processing_phase | state usa os sete estados do destino público. processing_phase é interno. Deployment.status é agregação de todas as linhas operation_id e não cópia do estado de um terminal. |
| Evidência etária | cp.age_evidence.method / status | method define representação armazenada (atestado ou data cifrada), enquanto verificationMethod descreve método de conferência. VALID vigente projeta VERIFIED. PENDING_REVIEW/REJECTED são estados do fluxo de conferência, não atestados válidos. Revogação/superação invalida a prova e os direitos dependentes; não retorna VERIFIED. |
| Recibo de abertura | cp.access_command_receipts.state | Mesmos sete estados. CONFIRMED confirma o protocolo do comando; relayTriggered, doorOpened e passageDetected continuam null sem evidência física correspondente. |
| Estado desejado e observado | cp.access_grants.status / cp.entitlement_syncs.state / cp.credential_syncs.state | Domínios internos diferentes: grant desejado, direito observado e credencial observada não são intercambiáveis. Cada projeção explicita o objeto e a força da evidência, sem cast automático de enum. |

A revisão dos enums é documental. Nenhuma serialização real, migration ou chamada de API foi executada.
