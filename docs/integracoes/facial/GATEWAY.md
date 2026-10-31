# Gateway de acesso facial do condomínio (AC-018 a AC-022, AC-031 a AC-033)

Código: `apps/access-gateway` (Java 25 / Spring Boot 4.1.2, sem dependência de terceiros além do núcleo do Spring
Boot). Contrato de referência: pacote *CondPulse Acesso Facial v1.1* (manual cap. 11, 12, 15–18 e 21–24; OpenAPI
proposto 1.1.0). Estado em 31/10/2026: **código e testes de contrato; nada homologado em bancada ou campo (G3–G6).**

Entrega em PRs empilhados: (1) núcleo, contrato comum e CI; (2) Control iD; (3) Hikvision; (4) Dahua e Intelbras. A
matriz da seção 6 descreve o conjunto completo; cada adaptador só existe no código a partir do PR dele.

> **Simulador não é homologação.** Os testes de contrato usam servidores HTTP falsos que imitam as respostas
> documentadas de cada fabricante. Eles provam que o adaptador fala o formato documentado — não que o firmware do
> equipamento instalado se comporta assim. Cada combinação (marca, SKU, firmware, API, modo) precisa de ensaio de
> bancada próprio antes de ser aprovada.

## 1. O que o gateway faz e o que não faz

| Faz | Não faz |
|---|---|
| Fala com os equipamentos por operações tipadas, caminhos fixos por adaptador e canais comissionados | Executar URL, método ou comando arbitrário (o cliente HTTP recusa caminho fora da lista) |
| Fila local idempotente com diário em disco (fsync); revogação sai antes e supera aplicação antiga | Escrever no banco central (só fala com a API central) |
| Confirma por leitura de volta; distingue ACK, APPLIED_UNVERIFIED, CONFIRMED, PARTIAL, FAILED, UNKNOWN | Repetir às cegas: resultado ambíguo vira NEEDS_READBACK |
| Abertura pontual com validade ≤ 10 s, nonce, destino e revisões conferidos, recibo durável | Reenviar abertura depois de timeout, reinício ou reconexão |
| Eventos com força de evidência explícita (`DEVICE_REPORTED`…), fila durável até o ACK da plataforma | Inferir direção, ponto ou horário que o equipamento não informou |
| Decisão local antes do relé (EDGE_ONLINE) com política fresca e relógio confiável | Consultar a nuvem no caminho do relé |
| Recusa gravar cartão, face ou direito quando a retirada não é documentada | Gravar algo que não sabe retirar |
| Confirma cartão/PIN/QR comparando o valor aplicado (material buscado de novo, nunca guardado) | Confirmar substituição de credencial ou face só por contagem ou presença (fica APPLIED_UNVERIFIED) |
| Mantém NEEDS_READBACK quando a leitura falha; reenvia só após leitura bem-sucedida mostrar ausência | Tratar leitura indisponível como prova |
| Telemetria de decisão em fila de memória, gravada e enviada no ciclo de fundo | Segurar a resposta ao equipamento esperando a plataforma |
| Resultado terminal não publicado fica pendente no diário e só o relatório é reenviado | Repetir operação física para republicar resultado |
| Diário trunca cauda incompleta (queda no meio da escrita) antes de acrescentar | Colar registro novo em linha parcial |

Invariantes verificados por teste (`InvariantsTest`): (1) nada é consumido ou confirmado antes de persistido; (2) queda
entre dois passos do diário não ressuscita estado antigo — a recuperação deriva fila e cópia de decisão dos registros,
e aplicação com versão anterior à maior aceita é recusada; (3) toda atuação confere prazo, quarentena e perfil logo
antes do envio; (4) leitura incerta nunca confirma (inclusive pessoa: campos aplicados precisam bater); (5) falha numa
etapa do ciclo não bloqueia as outras; (6) todo I/O bloqueante tem prazo (cabeçalhos e corpo) ou watchdog.

Segredos (senha de fabricante, token de entrada de cada equipamento, senha dos keystores) vêm só do diretório de
segredos montado (`CP_GATEWAY_SECRETS_DIR`, ex.: `/run/secrets`) ou de cofre que o monte ali. A configuração guarda só
referências (`*Ref`). Nenhum log contém URL de equipamento (o Control iD leva a sessão na query), corpo, foto,
cartão, PIN ou senha.

## 2. Modos de conexão (decisão do responsável de 31/10/2026)

O modo é parâmetro **por equipamento** (`connectionMode`) dentro da configuração de cada condomínio. O mesmo núcleo
roda nos dois modos.

- **LOCAL** — container na rede do condomínio, falando com os aparelhos pela LAN (`baseUrl` + `credentialRef`).
- **NUVEM** — o aparelho se conecta de fora para a plataforma pelo modo nativo da marca. O núcleo roda como serviço
  central e só aceita conexão autenticada por aparelho e condomínio: cada aparelho tem token próprio no cofre
  (`inboundTokenRef`, 32–128 caracteres) que compõe o caminho configurado nele (`/d/<token>/...`). Token errado ou
  ausente responde 404, sem distinção. Recomenda-se TLS no servidor de entrada (`inbound.tlsKeystorePath`).
  **Não publicar na Fly nem em outro provedor sem OK do responsável (app novo tem custo).**

| Marca | LOCAL | NUVEM | Fonte do modo nuvem |
|---|---|---|---|
| Control iD | Implementado (sessão `login.fcgi`) | Implementado: **push** (`GET /push`, `POST /result`) + modo Pro (`/new_user_identified.fcgi`, `/device_is_alive.fcgi`) | CI-PUSH, CI-ONLINE |
| Hikvision | Parcial (ver matriz) | **NAO_SUPORTADO_SEM_DOCUMENTACAO** — ISUP/EHome não está na fonte pública consultada | — |
| Dahua | Parcial (ver matriz) | **NAO_SUPORTADO_SEM_DOCUMENTACAO** — auto-registro/plataforma não está na fonte pública consultada | — |
| Intelbras | Eventos ao vivo + modo online | Parcial: **modo online** (keep alive `GET` e eventos `POST` no caminho configurável `Uploadpath`/`KeepAlive.Path`; resposta `auth`) — só decisão e eventos; cadastro não | IB-ONLINE, IB-ONLINE-CFG |

Ressalva de autenticação no modo nuvem: as fontes Control iD (push) e Intelbras (online) **não descrevem
autenticação do aparelho para o servidor**. O token no caminho é a autenticação possível; se o aparelho não aceitar
caminho com token (Control iD: a confirmar em bancada), o modo nuvem daquele aparelho fica indisponível até haver túnel
autenticado aprovado (VPN/mTLS na borda do condomínio).

## 3. Abertura remota: portaria local, portaria remota e morador pelo app

Origem do pedido (`origin` no comando): `PORTARIA_LOCAL`, `PORTARIA_REMOTA` (operador fora do condomínio, comando via
plataforma → gateway) e `MORADOR_APP`. Tudo começa desligado.

- Condomínio: `openPolicy.allowedOrigins` e `openPolicy.residentAppOpenEnabled`. `MORADOR_APP` só é aceito com a opção
  do condomínio ligada (a configuração recusa o contrário).
- Equipamento: `openEnabled` e `openOrigins`.
- Morador: a opção individual (ligada/desligada por morador) é dado da plataforma; o comando `MORADOR_APP` precisa
  trazer `residentGrantRef`, que atesta que a plataforma conferiu essa opção. Sem ele: `RESIDENT_GRANT_MISSING`.
- Sempre: validade ≤ `maxOpenTtlSeconds` (máx. 10), nonce único, `pointId`/`bindingRevision`/`installationRevision`
  iguais aos comissionados, capacidade `scopedRemoteOpen` (documentada em BANCADA; aprovada em PRODUCAO) e operação
  `OPEN` documentada no adaptador.
- Extensão futura — liberação por IA (voz que liga e libera o portão): **não implementada**. O ponto de extensão é
  `OpenDispatcher.OpenOriginPolicy`; uma origem nova só pode existir com essa política, nova revisão de segurança e
  OK do responsável. Hoje nenhuma origem além das três é aceita.

Estados do recibo (OpenAPI `OpenCommandReceipt`): `REJECTED`, `DISPATCH_COMMITTED`, `SENT`, `CONFIRMED`, `UNKNOWN`,
`EXPIRED`. `SENT` = equipamento aceitou; não comprova porta aberta nem passagem.

## 4. Configurar um condomínio

Exemplo completo e válido (testado): `infra/access-gateway/gateway.example.json`. Campo desconhecido é recusado.

Variáveis de ambiente do processo:

| Variável | Uso |
|---|---|
| `CP_GATEWAY_ENABLED` | `true` para subir; qualquer outro valor encerra com "Gateway desligado" |
| `CP_GATEWAY_CONFIG` | caminho do JSON de configuração (sem segredos) |
| `CP_GATEWAY_SECRETS_DIR` | diretório com um arquivo por segredo, nome = referência |
| `CP_GATEWAY_DATA_DIR` | diretório persistente dos diários (fila, recibos, eventos, decisão); um processo por diretório (trava exclusiva) |

Segredos (um arquivo cada, sem quebra de linha final): credencial de equipamento `usuario:senha` (`credentialRef`),
token de entrada (`inboundTokenRef`), senha do keystore mTLS do gateway (`central.keystorePasswordRef`), senha do
keystore TLS de entrada (`inbound.tlsKeystorePasswordRef`).

Parâmetros por equipamento (`devices[]`):

| Campo | Significado |
|---|---|
| `deviceId`, `deviceProvisioningId` | identidades do inventário da plataforma (UUID) |
| `brand`, `sku`, `firmware`, `apiVersion` | combinação exata; precisa existir em `profiles[]` para gravar (revogação sempre pode) |
| `connectionMode` | `LOCAL` ou `NUVEM` |
| `decisionMode` | `DEVICE_LOCAL_ACL` (equipamento decide) ou `EDGE_ONLINE` (gateway decide antes do relé; exige `inboundTokenRef`) |
| `baseUrl` | só LOCAL: esquema, host e porta, sem caminho |
| `nativeDeviceId` | Control iD push: `deviceId` que o aparelho envia |
| `eventEpoch` | época dos ids nativos de evento; trocar após reset de fábrica |
| `enabled`, `openEnabled`, `openOrigins` | começam desligados |
| `phase` | `BANCADA` (capacidade documentada basta) ou `PRODUCAO` (exige observada **e** aprovada no perfil) |
| `installationRevision` | revisão da instalação conferida na abertura |
| `channels[]` | `channelId`, `pointId`, `bindingRevision`, `nativeChannel` (porta/portal nativo), `nativeOpenAction`, `direction` |
| `nativeSchedules` | faixa CondPulse → valor nativo (Control iD: `access_rules.id` quando há um canal; Hikvision: `planTemplateNo`) |
| `params` | Control iD: `rule.<channelId>.<scheduleRef>` = `access_rules.id`; Hikvision: `hikvision.deleteProgressPolls`; Intelbras: `intelbras.streamWindowMillis` |

`nativeOpenAction` (Control iD): `door:door=N`, `sec_box:id=N,reason=N` (iDFace) ou
`catra:allow=clockwise|anticlockwise|both`. Outro texto é recusado. Dahua usa `nativeChannel` numérico como `channel`.

Faixas de horário (`schedules`) ficam no fuso do condomínio (`zone`) e são usadas pela decisão local; o equipamento
recebe só a referência nativa comissionada pelo MASTER.

Perfis (`profiles[]`): `observed` e `approved` por capacidade (`faceEnrollment`, `perDoorRights`, `localValidity`,
`onlineDecisionBeforeRelay`, `offlineRestriction`, `scopedRemoteOpen`…). Ausente = desconhecido, nunca true.

### Piloto: Condomínio Teste 1 (homologação)

1. MASTER registra o inventário real (SKU, firmware, API, licença) e cria o perfil da combinação em `profiles[]`.
2. Comissiona canais, faixas e ações nativas; deixa `enabled=false` até a janela de bancada.
3. Escolhe o equipamento a testar fisicamente (decisão do responsável) e liga só ele, em `phase=BANCADA`.
4. Ensaios G3 (pré-relé, timeout, offline, revogação, recuperação) com evidência; só depois `observed/approved`.

## 5. Rodar no condomínio (container, modo LOCAL)

```sh
docker build -f infra/access-gateway/Dockerfile -t condpulse-access-gateway:local .
docker run -d --name condpulse-gateway --restart unless-stopped \
  --read-only --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp \
  -v condpulse-gateway-data:/data \
  -v /caminho/seguro/segredos:/run/secrets:ro \
  -v /caminho/gateway.json:/etc/condpulse/gateway.json:ro \
  -e CP_GATEWAY_ENABLED=true -e CP_GATEWAY_CONFIG=/etc/condpulse/gateway.json -e CP_GATEWAY_SECRETS_DIR=/run/secrets \
  condpulse-access-gateway:local
```

Rede: o host fica no segmento dos leitores; administração dos leitores sem encaminhamento público; saída só para a API
central e NTP. Se algum equipamento usa modo online/push na LAN, publique só a porta de `inbound` para esse segmento.

Modo NUVEM: a mesma imagem como serviço central, com `inbound` público em TLS e um token por aparelho. **Não
implantado** (precisa de OK de custo).

Hoje a API central ainda não expõe as rotas de comando (seção 7): o processo sobe, mantém fila e diários, mas não
recebe comandos reais até o backend existir. Só `POST /v1/edge/events` e `POST /v1/edge/decisions` têm cliente HTTP.

## 6. Matriz de operações por marca

Legenda: **I** implementado (com fonte), **I\*** implementado mas bloqueado pelo núcleo por falta de retirada
documentada (`REVOGACAO_SEM_DOCUMENTACAO`), **NS** `NAO_SUPORTADO_SEM_DOCUMENTACAO`. Fontes consultadas em 31/10/2026.

| Operação | Control iD (LOCAL e NUVEM/push) | Hikvision ISAPI (LOCAL) | Dahua CGI (LOCAL) | Intelbras CGI (LOCAL / online) |
|---|---|---|---|---|
| Identificar firmware/SKU | NS (`system_information` não consultado) | NS (`deviceInfo` fora de HK12) | NS | NS |
| Cadastrar/atualizar pessoa | I — `load/create/modify_objects` `users` | I — UserInfo Search/Record/Modify (§12, §14, §15) | NS | NS |
| Remover pessoa | I — `destroy_objects users` | I — UserInfoDetail/Delete + DeleteProcess (§16, §17) | NS | NS |
| Cadastrar face | I — `user_set_image_list.fcgi` | NS (FaceDataRecord truncado; FDSetUp só `faceURL`) | I\* — `FaceInfoManager.cgi?action=add` | NS (página recusada por robots) |
| Remover face | I — `user_destroy_image.fcgi` (só `user_id`) | NS | NS | NS |
| Cartão: gravar / retirar | I / I — `cards` | I / I — CardInfo Record/Delete (§21, §23) | I\* / NS — `recordUpdater.cgi insert AccessControlCard` | NS / NS |
| PIN: gravar / retirar | I / I — `pins` | NS / NS | NS / NS | NS / NS |
| QR: gravar / retirar | I / I — `qrcodes` (só modo alfanumérico) | NS / NS | NS / NS | NS / NS |
| Aplicar direitos por ponto e horário | I — `user_access_rules` (regras comissionadas) | I — `doorRight` + `RightPlan` (§15) | NS | NS |
| Retirar direitos | I — `destroy_objects user_access_rules/user_groups` | I — via exclusão da pessoa (§16) | NS | NS |
| Consultar estado (leitura de volta) | I — regras, grupos, cartões, PIN, QR (face: null) | I — pessoa, RightPlan (cartão/face: null) | NS | NS |
| Abertura pontual | I — `execute_actions.fcgi` (`door`/`sec_box`/`catra`) | NS (RemoteControl/door fora de HK12) | I — `accessControl.cgi?action=openDoor` | NS |
| Eventos por busca (backfill) | I — `load_objects access_logs` | NS (AcsEvent fora de HK12) | I — `recordFinder.cgi AccessControlCardRec` | NS (só fluxo ao vivo) |
| Eventos recebidos (push/fluxo) | NS (Monitor não consultado) | NS | NS | I — `eventManager.cgi attach`; `POST` do modo online |
| Decisão antes do relé (online) | I — modo Pro `new_user_identified.fcgi` | NS (`remoteCheck` fora de HK12) | NS | I — modo online, resposta `auth` |
| Saúde | I — `session_is_valid.fcgi` / último `GET /push` | I — `UserInfo/Count` (§3) | I — `recordFinder` `count=1` | I — `eventManager` com `heartbeat=1` |

Fontes:

- **Control iD** — <https://www.controlid.com.br/docs/access-api-en/>: `session-management/do-login/`,
  `session-management/check-session-validity/`, `objects/create-objects/`, `objects/load-objects/`,
  `objects/modify-objects/`, `objects/destroy-objects/`, `objects/list-of-objects/`,
  `first-steps/enroll-users-and-their-rules/`, `facial-recognition/facial-enroll/`,
  `actions/remote-door-and-turnstile-opening/`, `operating-modes/online-identification-events/`,
  `push-mode/introduction-to-push/`; <https://controlid.com.br/docs/access-api-en/operating-modes/introduction-to-operating-modes>;
  <https://controlid.com.br/docs/access-api-en/particularities-of-the-products/particularities-control-id-terminals>.
- **Hikvision** — HK12, documentação oficial pública de protocolo de acesso MinMoe:
  <https://open.hikvision.com/hardware/v2/08%E5%8D%8F%E8%AE%AE%E9%80%8F%E4%BC%A0/%E6%98%8E%E7%9C%B8%E9%97%A8%E7%A6%81.html>
  (§3, §12, §14–§17, §21, §23, §26). Portal chinês: não comprova disponibilidade em SKU brasileiro (manual 22.2).
- **Dahua** — "DAHUA ACCESS CONTROL PRODUCTS INTEGRATION INSTRUCTION" V1.0, 28/09/2021, modelos ASI3XXX/6XXX/7XXX:
  <https://files.dahua.support/Solutions/Access%20Control%20Solution/Integration/DAHUA%20ACCESS%20CONTROL%20PRODUCTS%20INTEGRATION%20INSTRUCTION%20Ver1.0.pdf>.
  Documento com marca Dahua, sem nota legal de editor; confirmar com o canal Dahua antes de aprovar.
- **Intelbras** — documentação oficial de API de controle de acesso: <https://intelbras-caco-api.intelbras.com.br/modo_online>,
  `modo_online/configurar_via_api`, `obtendo_eventos/eventManager`, `parametros_retornados`,
  `configuracoes_de_credenciais_facial/captura_remota_de_face`. Confirma a API CGI (`/cgi-bin/*.cgi`) com Digest,
  da mesma família de caminhos da Dahua; as chamadas Dahua **não** são reaproveitadas por semelhança.

## 7. Contrato esperado da API central (o que o backend precisa expor)

Autenticação de máquina: mTLS com certificado do gateway; tenant e conector derivados do certificado; corpo nunca
traz `tenantId`/`actorId`. Respostas com recibo e chave de idempotência.

| Rota | Estado | Uso |
|---|---|---|
| `POST /v1/edge/events` | no OpenAPI 1.1.0 (`ingestEdgeEvents`) | lote ≤ 100 `EdgeEvent`; resultado por item `ACCEPTED/DUPLICATE/REJECTED` |
| `POST /v1/edge/decisions` | no OpenAPI 1.1.0 (`ingestEdgeDecision`) | telemetria da decisão local; nunca autoriza nada |
| `GET /v1/edge/sync-commands?max=` | **PROPOSTO** | comandos tipados: `commandId`, `idempotencyKey`, `deviceId`, `subjectRef`, `type` (`UPSERT_PERSON`, `ENROLL_FACE`, `SET_CREDENTIAL`, `APPLY_RIGHTS`, `REMOVE_FACE`, `REMOVE_CREDENTIAL`, `REVOKE_RIGHTS`, `REVOKE_ALL`), `desiredVersion`, `payload` sem segredo |
| `POST /v1/edge/sync-commands/{commandId}/result` | **PROPOSTO** | estado por destino (`CONFIRMED`, `APPLIED_UNVERIFIED`, `PARTIAL`, `FAILED`, `UNKNOWN`, `NAO_SUPORTADO_SEM_DOCUMENTACAO`) e motivo |
| `GET /v1/edge/open-commands?max=` | **PROPOSTO** | `OpenCommand`: `commandId`, `nonce`, `deviceId`, `channelId`, `pointId`, `bindingRevision`, `installationRevision`, `issuedAt`, `expiresAt`, `origin`, `authorizationRef`, `residentGrantRef` |
| `POST /v1/edge/open-commands/{commandId}/receipt` | **PROPOSTO** | recibo durável (`replayProhibited=true`) |
| `POST /v1/edge/health` | **PROPOSTO** | resumo: fila, revogações pendentes e a mais antiga, eventos pendentes/quarentena, alcance e quarentena por equipamento |
| `GET /v1/edge/materials/{materialRef}?deviceId=` | **PROPOSTO** | foto ou valor de cartão/PIN/QR temporário, limitado ao destino e prazo curto; o gateway não guarda |

Além disso, o backend precisa: conferir no comando `MORADOR_APP` a opção individual do morador e a do condomínio
antes de emitir `residentGrantRef`; emitir `expiresAt − issuedAt ≤ 10 s`; manter revogação com versão maior que a
última aplicação; e expor o horário do servidor para o gateway medir desvio de relógio (sem isso a decisão local nega
com `CLOCK_UNTRUSTED`).

## 8. Pendências (NAO_SUPORTADO_SEM_DOCUMENTACAO e bancada)

- Control iD: `system_information` (identificação/firmware), formato das notificações do Monitor, unidade de
  `users.begin_time/end_time` (validade local não é gravada) e de `access_logs.time` (`occurredAt` null), presença de
  face na leitura de volta, numeração de `access_logs.event` além de 3 e 7 (inferida pela ordem da tabela), uso de
  caminho com token no servidor de push/online. Páginas bloqueadas nesta sessão: `system/get-system-information/`,
  `monitor/introduction-to-monitor/`.
- Hikvision: abertura (`RemoteControl/door`), eventos (`AcsEvent`), `deviceInfo`, `remoteCheck`, face por binário,
  PIN, QR, ISUP/EHome. Obter guia TPP aplicável por série (HK10) e resolver divergências de capacidade (manual 22.1).
- Dahua: atualização/remoção de registro e face, pessoa, direitos, leitura de estado, autenticação, auto-registro.
  Sem retirada documentada, cartão e face ficam bloqueados.
- Intelbras: cadastro de pessoa, face, cartão, PIN, QR, direitos, remoção, abertura, leitura de estado, backfill. A
  página oficial "Cadastrar Faces" existe mas foi recusada por robots nesta sessão; documento v1.3 (2020) e "API Bio-T"
  (2022) do catálogo não foram obtidos. **Risco documentado: no timeout do modo online todos os usuários locais têm
  privilégio VIP 24/7** — base local precisa ser mínima e a área não pode depender só da decisão online.
- Núcleo: failover para gateway reserva (eleição/fencing) não implementado — um processo por diretório de dados;
  compactação dos diários; métricas exportadas (hoje só resumo `healthSummary`).
- Tudo de G3–G6 (bancada, comissionamento, piloto, aceite) depende de hardware.
