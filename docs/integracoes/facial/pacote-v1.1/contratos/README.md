# Contratos propostos de acesso físico — CondPulse 1.1

Especificação de 30/10/2026. Este diretório contém um **delta OpenAPI 3.1.0 proposto**, para conciliação com o repositório, autenticação, erros e operações existentes. Não representa implementação publicada, API de fabricante ou homologação física. O servidor `.example` é ilustrativo. Os dois exemplos JSON são sintéticos.

## Autoridade e autenticação

O servidor resolve ator, tenant e escopo efetivos. O tenant no caminho é um seletor sujeito a autorização; não é uma concessão. Requisições não aceitam `tenantId` ou `actorId` livres no corpo. Na ingestão de máquina, tenant e conector vêm do certificado cadastrado; os identificadores de dispositivo e proveniência são conferidos contra esse registro.

Escrita em sessão de navegador exige **cookie + CSRF juntos**, com validação do token e de origem. A alternativa bearer destina-se ao cliente não-browser aprovado; não permite contornar CSRF de uma sessão de navegador. Rotas edge aceitam somente identidade mTLS. Nenhuma resposta expõe senha, token de fabricante, chave privada ou referência de cofre.

## Correspondência entre conceitos do manual e campos de transporte

| Conceito do manual | Campo no contrato | Semântica |
|---|---|---|
| Conector de origem (`connectorId`) | `sourceConnectorId` | Proveniência da entrega, conferida com o certificado; não integra a identidade estável de deduplicação. |
| Equipamento | `deviceId` | UUID do equipamento no inventário. |
| Instância provisionada | `deviceProvisioningId` | Identidade estável do provisionamento; evita confusão após limpeza e transferência de tenant. |
| Identidade nativa do evento | `eventEpoch` + `nativeEventId` | Composição ensaiada por adaptador; deduplicação inclui tenant e instância provisionada. |
| Canal observado | `deviceChannelId` / `nativeChannelId` | Referência resolvida e valor nativo; desconhecido permanece null, sem escolher outro canal por padrão. |
| Revisão do vínculo físico | `bindingRevision` | Inteiro monotônico por ponto dentro do tenant; não é o UUID da linha. |
| Identificador da linha de vínculo | `PointBinding.id` | UUID de `cp.point_bindings.id`; cada revisão tem sua identidade. |
| Revisão da instalação | `installationRevision` | Inteiro monotônico por tenant; UUID de aceite e número da revisão são conceitos distintos. |
| Versão da política | `policyVersion` / `policyVersionId` | UUID de `cp.access_policy_versions.id`; `PolicyVersion.revision` corresponde à sequência da versão. |
| Nível de evidência (`evidenceLevel`) | `evidence.level` | Força da evidência; mTLS de transporte não transforma inferência em prova física. |
| Recebimento no backend (`receivedAt`) | Resultado de ingestão e registro persistido | Timestamp criado pelo servidor; não é aceito no corpo do evento recebido. |

Eventos atrasados usam o vínculo histórico. Quando a associação não for demonstrável, registrar desconhecimento ou inferência explícita e preservar a evidência original; não atualizar presença com o mapeamento atual por conveniência.

## Decisão local e sua telemetria

Os resultados canônicos são `ALLOW`, `DENY`, `REQUIRE_ASSISTANCE` e `UNAVAILABLE`. Os dois últimos **não autorizam atuação automática**. `REQUIRE_ASSISTANCE` orienta atendimento; `UNAVAILABLE` registra que a avaliação não pôde ser concluída.

Os modos gerais são `DEVICE_LOCAL_ACL`, `EDGE_ONLINE`, `PROVIDER_LOCAL` e `ASSISTED`. `ASSISTED` é procedimento operacional confirmado, não capacidade automática do hardware. O endpoint `/v1/edge/decisions` registra a decisão já tomada pelo gateway/adaptador local em `EDGE_ONLINE`: é telemetria idempotente, não RPC obrigatório de autorização na nuvem. Seu ACK não abre porta, não renova validade e não cria grant. Decisões nativas de outros modos continuam representadas pelos eventos e evidências homologados.

## Vínculo físico único e concorrência

Uma revisão de `PointBinding` contém atuador, controlador, leitores de entrada/saída, sensores e mapa de sentidos. Uma mudança de leitor ou direção produz **nova revisão completa do mesmo vínculo**; não cria outro binding ativo para o mesmo relé. O servidor mantém no máximo um ACTIVE por tenant+ponto e tenant+atuador. Mudanças conferem revisão anterior, invalidam comandos antigos e dependem do comissionamento aplicável.

`X-Expected-Revision` expressa a revisão atual indicada em `x-concurrency-resource`; conflitos retornam 409. Campos `expected...Revision` são inteiros e conferem o objeto físico correspondente. IDs de recursos e parâmetros de caminho continuam UUID. Não substituir essa semântica por `If-Match` sem também adotar as regras HTTP correspondentes.

## Publicação, idade e abertura

`PublicationAccepted` é recibo inicial imutável, com estado APPROVED ou PUBLISHING e `applicationConfirmed=false`. A repetição idempotente retorna esse recibo; o estado atual, inclusive CONFIRMED, PARTIAL ou FAILED, vem de `GET access-deployments`. HTTP 202 não significa aplicação ou abertura.

`minimumAgeYears` e `maximumAgeYears` são limites inclusivos de anos completos no calendário local; null desativa o limite correspondente. O servidor rejeita faixa invertida. Qualquer faixa configurada exige evidência etária verificada. O schema não estima idade por rosto e não substitui o gate específico de biometria de menores. `defaultEffect=DENY` refere-se ao ingresso sem autorização, preservando saída física e tratamento das rotas residenciais essenciais.

Abertura é diferente de sincronização e de telemetria. Após compromisso durável de envio, falha ambígua ou expiração, nenhum retry de HTTP, SDK, proxy, fila ou failover reexecuta o mesmo comando. `UNKNOWN` não equivale a “não enviado”. A retomada exige verificação e nova intenção consciente. Estados do comando, relé, porta e passagem permanecem separados; o contrato não promete execução exatamente uma vez sobre hardware sem esse suporte.

## Limites da verificação deste arquivo

Foram realizadas verificações estruturais locais de YAML, referências internas, operações, segurança e exemplos; o resumo de QA acompanha o processo de geração. Essa verificação não executa endpoints, não demonstra autorização de runtime e não certifica equipamento. A integração precisa dos testes de contrato, bancada e campo previstos no pacote.
