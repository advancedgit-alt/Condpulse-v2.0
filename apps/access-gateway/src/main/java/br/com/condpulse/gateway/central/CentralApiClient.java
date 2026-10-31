package br.com.condpulse.gateway.central;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import br.com.condpulse.gateway.open.OpenDispatcher.OpenCommand;
import br.com.condpulse.gateway.open.OpenDispatcher.Receipt;
import br.com.condpulse.gateway.queue.LocalCommandQueue.SyncCommand;
import br.com.condpulse.gateway.spi.Spi.EdgeEvent;
import br.com.condpulse.gateway.spi.Spi.OperationState;

/**
 * Cliente da API central. O gateway não escreve no banco central: tudo passa por aqui, autenticado pelo certificado
 * pela credencial do gateway (V048; mTLS fica pendente na borda central), sem tenantId no corpo. Entrega e relatório
 * de sincronização existem em contracts/openapi.json ({@code POST /v1/edge/sync-commands/lease},
 * {@code POST /v1/edge/sync-commands/{commandId}/result}); eventos, decisões, abertura, saúde e material seguem
 * PROPOSTO (lista em docs/integracoes/facial/GATEWAY.md).
 */
public interface CentralApiClient {
 /** OpenAPI {@code ingestEdgeEvents}: ACK por item depois de persistir na caixa de entrada central. */
 List<EventIngestResult> postEvents(List<EdgeEvent> events,String idempotencyKey) throws CentralUnavailable;

 /** OpenAPI {@code ingestEdgeDecision}: telemetria de decisão já tomada no gateway; a resposta nunca autoriza nada. */
 DecisionReceipt postDecision(EdgeDecision decision,String idempotencyKey) throws CentralUnavailable;

 /** {@code POST /v1/edge/sync-commands/lease} (V048): trabalhos da publicação por destino entregues a este gateway. */
 List<SyncCommand> pullSyncCommands(int max) throws CentralUnavailable;

 /** PROPOSTO {@code GET /v1/edge/open-commands}: aberturas pontuais pendentes (curta validade). */
 List<OpenCommand> pullOpenCommands(int max) throws CentralUnavailable;

 /** {@code POST /v1/edge/sync-commands/{commandId}/result} (V048): ACK de transporte ou resultado por trabalho, idempotente. */
 void reportSyncResult(SyncResult result) throws CentralUnavailable;

 /** PROPOSTO {@code POST /v1/edge/open-commands/{commandId}/receipt}: recibo durável da abertura. */
 void reportOpenReceipt(Receipt receipt) throws CentralUnavailable;

 /** PROPOSTO {@code POST /v1/edge/health}: saúde do gateway e dos equipamentos. */
 void reportHealth(GatewayHealth health) throws CentralUnavailable;

 /**
  * PROPOSTO {@code GET /v1/edge/materials/{materialRef}?deviceId=}: foto ou valor de credencial temporário, limitado
  * ao destino e com prazo curto. O gateway não guarda o retorno.
  */
 byte[] fetchMaterial(String materialRef,String deviceId) throws CentralUnavailable;

 final class CentralUnavailable extends Exception {
  public CentralUnavailable(String reason) { super(reason,null,false,false); }
 }

 enum IngestStatus { ACCEPTED, DUPLICATE, REJECTED }
 record EventIngestResult(String eventId,IngestStatus status,String reasonCode) {}

 enum DecisionResult { ALLOW, DENY, REQUIRE_ASSISTANCE, UNAVAILABLE }
 /** Subconjunto do OpenAPI EdgeDecisionRequest preenchido pelo gateway; campos sem fonte confiável vão null. */
 record EdgeDecision(String decisionId,String attemptId,String sourceConnectorId,String deviceProvisioningId,String deviceChannelId,
     String pointId,Long bindingRevision,Long installationRevision,String recognizedSubjectRef,DecisionResult result,List<String> reasonCodes,
     Instant evaluatedAt,Instant expiresAt,boolean automaticActuationAllowed,String clockQuality,long evaluationDurationMs) {
  public EdgeDecision {
   reasonCodes=List.copyOf(reasonCodes);
   if(result!=DecisionResult.ALLOW && automaticActuationAllowed) throw new IllegalArgumentException("Só ALLOW permite atuação automática.");
  }
 }
 record DecisionReceipt(String decisionId,IngestStatus status,String reasonCode) {}

 /**
  * Resultado por comando. documentHash: hash do documento entregue pela plataforma (V048); em CONFIRMED ele é também o
  * hash lido de volta (a leitura bateu com o comando derivado desse documento). null só em comando sem documento.
  */
 record SyncResult(String commandId,String deviceId,OperationState state,String reasonCode,Instant observedAt,String documentHash) {}
 record GatewayHealth(Instant observedAt,Map<String,Object> summary) {}
}
