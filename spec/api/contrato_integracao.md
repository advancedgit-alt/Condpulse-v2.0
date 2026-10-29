# Contrato interno de integração de acesso — v1

Estado: contrato de domínio proposto, não documentação da Bravas ou de qualquer fabricante. Não há endpoint de parceiro contratado neste pacote.

## Superfície do adaptador

| Operação lógica | Entrada mínima | Saída normalizada |
| --- | --- | --- |
| capabilities | connectorId | operações, tipos de evento, precisão de confirmação, limites e modo offline |
| upsertPerson | correlationId, externalRef, dados mínimos | providerPersonId, ACCEPTED/APPLIED/REJECTED/UNKNOWN |
| applyCredential | personRef, credentialRef, desiredVersion, destinos, validade, referência temporária da foto | jobRef e estado por destino |
| getApplication | credentialRef, desiredVersion, destinos | versão confirmada, resultado e instante por equipamento |
| revokeCredential | credentialRef, desiredVersion, motivo | confirmação/pendência por destino |
| getEvents | cursor, janela limitada | página de eventos normalizados e próximo cursor |
| health | connectorId | conexão, relógio, atrasos e limites observados |
| requestOpen | requestId, deviceId, authorizationRef, expiresAt | ACK/EXECUTED/REJECTED/UNKNOWN; não habilitada no lançamento |

O identificador externo deve ser estável e isolado por tenant/conector. O adaptador implementa timeouts explícitos, limite de concorrência, rate limiting e circuit breaker. O estado desejado fica no CondPulse; a evidência de aplicação vem do equipamento/plataforma. Se o fornecedor só confirma recebimento, isso é o máximo que podemos afirmar.

## Envelope interno de evento

```json
{
  "schemaVersion": "1",
  "eventId": "id-estavel-do-provedor",
  "provider": "parceiro-homologado",
  "deviceExternalId": "leitor-entrada-1",
  "credentialExternalId": "credencial-opaca",
  "kind": "PASSAGE_DETECTED",
  "direction": "ENTRY",
  "occurredAt": "2026-10-27T13:00:00Z",
  "receivedAt": "2026-10-27T13:00:01Z",
  "evidence": "TURNSTILE_SENSOR",
  "appliedVersion": 3,
  "correlationId": "referencia-do-comando"
}
```

Esse exemplo é o formato NORMALIZADO depois de validar o webhook real, não um payload que se presume oferecido pelo fabricante. O tenant vem do mapeamento de credencial/conector autenticado. Não aceitar tenantId declarado pelo remetente sem esse vínculo.

Tipos de evento: AUTHORIZED, DENIED, RELAY_TRIGGERED, DOOR_OPENED, PASSAGE_DETECTED, CREDENTIAL_APPLIED, CREDENTIAL_REJECTED, CREDENTIAL_REVOKED, DEVICE_ONLINE, DEVICE_OFFLINE. Normalizar direction em ENTRY/EXIT/UNKNOWN. Campo ausente continua ausente; não inventar passagem ou direção.

## Autenticidade e persistência

Usar a autenticação contratada: HMAC de corpo bruto e timestamp, certificado/mTLS ou assinatura equivalente suportada. Timestamp não resolve replay sozinho: deduplicar eventId. Comparar assinaturas em tempo constante. Aceitar pequenas divergências de relógio somente conforme janela homologada. Rotação aceita chave atual e anterior durante período curto registrado. IP allowlist é defesa complementar, não substituto universal de autenticidade.

Persistir inbox durável antes do ACK. Eventos duplicados retornam resposta segura; payload incompatível entra em quarentena operacional com erro explícito. Limitar tamanho, profundidade, frequência e campos. Rejeitar segredos/fotos desnecessários no payload normalizado.

## Tabela de resultado e política de repetição

| Resultado | Significado | Conduta |
| --- | --- | --- |
| ACCEPTED | Parceiro recebeu | Aguardar consulta/callback; não ativar no app |
| APPLIED | Versão aplicada no destino identificado | Confirmar somente esse destino |
| REJECTED | Falha definitiva documentada | Informar correção e não repetir sem mudança |
| RATE_LIMITED | Limite temporário | Backoff com jitter e Retry-After, se disponível |
| UNKNOWN | Houve envio, resultado incerto | Consultar estado antes de mutação não idempotente |
| UNAVAILABLE | Não foi possível comunicar | Manter pendência e continuidade local |

Versão aplicada só atualiza se corresponde à ação desejada atual. Revogação tem prioridade. Não armazenar comandos de abertura em fila de reenvio genérica. TTL inicial proposto de 10 s precisa ser homologado. Nunca enviar um OPEN antigo depois de reconectar.

## Matriz de homologação a preencher

Registrar fornecedor, contrato, versão da API, controlador, leitor, firmware, licença, modo, sensores de entrada/saída, rede, latência, tolerância de relógio, semântica de ACK, revogação, eventos, suporte e custo. Cada teste contém esperado, obtido, evidência, data e assinatura do integrador e do responsável CondPulse.

A plataforma anterior permanece em operação até o aceite. O integrador deve preservar os dispositivos de saída de emergência e a operação local legalmente exigida. Não conectar diretamente leitores à internet pública nem usar senha padrão.
