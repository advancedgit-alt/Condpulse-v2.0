package br.com.condpulse.gateway.spi;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.SecretProvider.DeviceCredential;
import br.com.condpulse.gateway.http.DeviceHttpClient;
import br.com.condpulse.gateway.http.DeviceHttpClient.DeviceCallException;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Base dos adaptadores: toda operação começa como {@link OperationState#NAO_SUPORTADO_SEM_DOCUMENTACAO} e só deixa de
 * ser quando o adaptador a implementa com fonte oficial declarada no manifesto.
 */
public abstract class BaseAdapter implements AccessProviderAdapter {
 /** Dados do equipamento entregues ao adaptador. credential é null no modo nuvem. */
 public record DeviceContext(GatewayConfig.Device device,String connectorId,DeviceCredential credential,Duration timeout,Duration pushPickupTimeout,Clock clock,java.time.ZoneId zone) {
  public DeviceHttpClient http(Set<String> allowedPaths) {
   if(device.baseUrl()==null) throw new IllegalStateException("Equipamento sem baseUrl (modo NUVEM).");
   return new DeviceHttpClient(device.baseUrl(),timeout,credential,allowedPaths);
  }
 }

 protected final DeviceContext context;
 protected BaseAdapter(DeviceContext context) { this.context=context; }

 protected GatewayConfig.Device device() { return context.device(); }

 /** Falha de transporte: não enviado vira FAILED/DEVICE_UNREACHABLE (pendência); ambíguo vira UNKNOWN. */
 protected static OperationResult failure(DeviceCallException e) {
  return e.failure()==DeviceHttpClient.Failure.NOT_SENT?OperationResult.of(OperationState.FAILED,"DEVICE_UNREACHABLE")
   :OperationResult.of(OperationState.UNKNOWN,e.getMessage());
 }
 protected static OpenResult openFailure(DeviceCallException e) {
  return new OpenResult(e.failure()==DeviceHttpClient.Failure.NOT_SENT?OpenOutcome.NOT_SENT:OpenOutcome.AMBIGUOUS,e.getMessage());
 }

 /** Evento com os campos fixos deste equipamento e evidência DEVICE_REPORTED. */
 protected EdgeEvent event(String nativeEventId,EventKind kind,java.time.Instant occurredAt,Direction direction,TimeQuality quality,
     String subjectRef,String nativeKind,String nativeChannel) {
  String channelId=nativeChannel==null?null:device().channelByNative(nativeChannel).map(GatewayConfig.Channel::channelId).orElse(null);
  return new EdgeEvent(null,device().deviceId(),device().deviceProvisioningId(),device().eventEpoch(),nativeEventId,context.connectorId(),
   subjectRef,kind,occurredAt,direction,quality,new Evidence(EvidenceLevel.DEVICE_REPORTED,EvidenceSource.DEVICE_EVENT,nativeKind,null,null),
   channelId,nativeChannel);
 }

 @Override public DeviceIdentity identify() { return new DeviceIdentity(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,"NAO_SUPORTADO_SEM_DOCUMENTACAO",null,null,null); }
 @Override public OperationResult upsertPerson(PersonSpec person) { return OperationResult.notSupported(Operation.UPSERT_PERSON); }
 @Override public OperationResult removePerson(String subjectRef) { return OperationResult.notSupported(Operation.REMOVE_PERSON); }
 @Override public OperationResult enrollFace(FaceSpec face) { return OperationResult.notSupported(Operation.ENROLL_FACE); }
 @Override public OperationResult removeFace(String subjectRef) { return OperationResult.notSupported(Operation.REMOVE_FACE); }
 @Override public OperationResult setCredential(CredentialSpec credential) {
  return OperationResult.notSupported(switch(credential.kind()) { case CARD -> Operation.SET_CARD; case PIN -> Operation.SET_PIN; case QR -> Operation.SET_QR; });
 }
 @Override public OperationResult removeCredential(String subjectRef,CredentialKind kind) {
  return OperationResult.notSupported(switch(kind) { case CARD -> Operation.REMOVE_CARD; case PIN -> Operation.REMOVE_PIN; case QR -> Operation.REMOVE_QR; });
 }
 @Override public OperationResult applyRights(RightsSpec rights) { return OperationResult.notSupported(Operation.APPLY_RIGHTS); }
 @Override public OperationResult revokeRights(String subjectRef) { return OperationResult.notSupported(Operation.REVOKE_RIGHTS); }
 @Override public AppliedState readState(String subjectRef) { return AppliedState.unavailable(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,"NAO_SUPORTADO_SEM_DOCUMENTACAO:READ_STATE"); }
 @Override public Optional<Set<String>> rightKeys(RightsSpec rights) { return Optional.empty(); }
 @Override public OpenResult open(OpenAction action) { return new OpenResult(OpenOutcome.NAO_SUPORTADO_SEM_DOCUMENTACAO,"NAO_SUPORTADO_SEM_DOCUMENTACAO:OPEN"); }
 @Override public EventPage fetchEvents(String cursor,int limit) { return EventPage.unavailable(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,"NAO_SUPORTADO_SEM_DOCUMENTACAO:FETCH_EVENTS",cursor); }
 @Override public HealthReport health() { return new HealthReport(false,"NAO_SUPORTADO_SEM_DOCUMENTACAO:HEALTH",null,null,null); }

 public static Operation setOperation(CredentialKind kind) { return switch(kind) { case CARD -> Operation.SET_CARD; case PIN -> Operation.SET_PIN; case QR -> Operation.SET_QR; }; }
 public static Operation removeOperation(CredentialKind kind) { return switch(kind) { case CARD -> Operation.REMOVE_CARD; case PIN -> Operation.REMOVE_PIN; case QR -> Operation.REMOVE_QR; }; }
}
