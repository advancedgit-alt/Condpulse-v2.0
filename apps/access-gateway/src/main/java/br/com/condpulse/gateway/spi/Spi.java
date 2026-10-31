package br.com.condpulse.gateway.spi;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Tipos do contrato comum dos adaptadores (AC-019, manual cap. 15). Nada aqui é API de fabricante. Campo ausente
 * continua ausente (null): o adaptador nunca preenche por suposição.
 */
public final class Spi {
 private Spi() {}

 public enum Brand { CONTROL_ID, HIKVISION, DAHUA, INTELBRAS }

 /** Como o gateway alcança o equipamento: pela LAN do condomínio ou o equipamento chama a plataforma (nuvem). */
 public enum ConnectionMode { LOCAL, NUVEM }

 /** Onde a decisão de acesso acontece (manual cap. 11). */
 public enum DecisionMode { DEVICE_LOCAL_ACL, EDGE_ONLINE }

 /** Operações do contrato. Cada adaptador declara quais implementa e com qual fonte. */
 public enum Operation {
  IDENTIFY, UPSERT_PERSON, REMOVE_PERSON, ENROLL_FACE, REMOVE_FACE, SET_CARD, REMOVE_CARD, SET_PIN, REMOVE_PIN, SET_QR,
  REMOVE_QR, APPLY_RIGHTS, REVOKE_RIGHTS, READ_STATE, OPEN, FETCH_EVENTS, RECEIVE_EVENTS, HEALTH, ONLINE_DECISION
 }

 /**
  * Estado de uma operação. ACK: equipamento recebeu; APPLIED_UNVERIFIED: aceitou sem prova suficiente; CONFIRMED:
  * leitura de volta ou resposta individual documentada comprova; PARTIAL: parte das etapas; FAILED: falha (com
  * motivo); UNKNOWN: houve envio e o resultado é incerto — consultar antes de repetir; NAO_SUPORTADO_SEM_DOCUMENTACAO:
  * a documentação oficial pública consultada não cobre a operação.
  */
 public enum OperationState { ACK, APPLIED_UNVERIFIED, CONFIRMED, PARTIAL, FAILED, UNKNOWN, NAO_SUPORTADO_SEM_DOCUMENTACAO }

 /** Capacidades do contrato proposto (OpenAPI Capabilities). */
 public enum CapabilityName {
  faceEnrollment, faceDeletion, perDoorRights, localValidity, onlineDecisionBeforeRelay, offlineRestriction,
  doorContactEvents, passageEvents, scopedRemoteOpen, eventBackfill, secureTransport, safeRecovery
 }

 /** Fonte oficial consultada para uma operação: identificador do catálogo, URL e seção. */
 public record Source(String id,String url,String section) {
  public Source { Objects.requireNonNull(id); Objects.requireNonNull(url); Objects.requireNonNull(section); }
 }

 public record OperationSupport(Operation operation,boolean implemented,List<Source> sources,List<String> limitations) {
  public OperationSupport { sources=List.copyOf(sources); limitations=List.copyOf(limitations); }
  public static OperationSupport implemented(Operation op,List<Source> sources,String... limitations) {
   if(sources.isEmpty()) throw new IllegalArgumentException("Operação implementada exige fonte: "+op);
   return new OperationSupport(op,true,sources,List.of(limitations));
  }
  public static OperationSupport missing(Operation op,String pending) {
   return new OperationSupport(op,false,List.of(),List.of(pending));
  }
 }

 /**
  * Manifesto do adaptador para um modo de conexão: operações e capacidades documentadas. Capacidade documentada null
  * significa desconhecida e nunca vale como true.
  */
 public record Manifest(Brand brand,String adapterVersion,ConnectionMode connectionMode,Map<Operation,OperationSupport> operations,
     Map<CapabilityName,Boolean> documented) {
  public Manifest {
   var ops=new EnumMap<Operation,OperationSupport>(Operation.class);
   for(Operation op:Operation.values()) ops.put(op,operations.getOrDefault(op,OperationSupport.missing(op,"NAO_SUPORTADO_SEM_DOCUMENTACAO")));
   operations=Map.copyOf(ops);
   var caps=new EnumMap<CapabilityName,Boolean>(CapabilityName.class);
   for(CapabilityName c:CapabilityName.values()) caps.put(c,documented.get(c));
   documented=java.util.Collections.unmodifiableMap(caps);
  }
  public boolean supports(Operation op) { return operations.get(op).implemented(); }
 }

 public record OperationResult(OperationState state,String reasonCode,String nativeRef) {
  public OperationResult { Objects.requireNonNull(state); }
  public static OperationResult of(OperationState state,String reason) { return new OperationResult(state,reason,null); }
  public static OperationResult notSupported(Operation op) {
   return new OperationResult(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,"NAO_SUPORTADO_SEM_DOCUMENTACAO:"+op,null);
  }
 }

 /** Pessoa no equipamento. subjectRef é referência opaca do CondPulse, não CPF, nome ou e-mail. */
 public record PersonSpec(String subjectRef,String displayName,Instant validFrom,Instant validUntil) {
  public PersonSpec {
   requireRef(subjectRef);
   if(displayName==null || displayName.isBlank() || displayName.length()>64) throw new IllegalArgumentException("Nome de exibição de 1 a 64 caracteres.");
   if(validFrom!=null && validUntil!=null && !validFrom.isBefore(validUntil)) throw new IllegalArgumentException("Validade inválida.");
  }
  @Override public String toString() { return "PersonSpec[subjectRef="+subjectRef+"]"; }
 }

 /** Foto para cadastro facial: bytes JPEG obtidos por fluxo autenticado, nunca persistidos nem registrados. */
 public record FaceSpec(String subjectRef,byte[] jpeg) {
  public FaceSpec {
   requireRef(subjectRef);
   if(jpeg==null || jpeg.length<4 || jpeg.length>2*1024*1024) throw new IllegalArgumentException("Foto ausente ou acima de 2 MB.");
   if((jpeg[0]&0xff)!=0xFF || (jpeg[1]&0xff)!=0xD8) throw new IllegalArgumentException("Foto não é JPEG.");
  }
  @Override public String toString() { return "FaceSpec[subjectRef="+subjectRef+", bytes="+jpeg.length+"]"; }
 }

 public enum CredentialKind { CARD, PIN, QR }

 /** Credencial não biométrica. O valor nunca aparece em toString nem em log. */
 public record CredentialSpec(String subjectRef,CredentialKind kind,String value) {
  public CredentialSpec {
   requireRef(subjectRef); Objects.requireNonNull(kind);
   if(value==null || value.isEmpty() || value.length()>128) throw new IllegalArgumentException("Valor da credencial inválido.");
   if(kind==CredentialKind.CARD && !value.matches("\\d{1,20}")) throw new IllegalArgumentException("Cartão deve ser numérico.");
   if(kind==CredentialKind.PIN && !value.matches("\\d{4,12}")) throw new IllegalArgumentException("PIN de 4 a 12 dígitos.");
  }
  @Override public String toString() { return "CredentialSpec[subjectRef="+subjectRef+", kind="+kind+", value=***]"; }
 }

 /** Direito num canal comissionado, numa faixa de horário cadastrada (scheduleRef da configuração). */
 public record Right(String channelId,String scheduleRef) {
  public Right { requireRef(channelId); requireRef(scheduleRef); }
 }

 /** Direitos desejados completos da pessoa neste equipamento: substitui os anteriores. */
 public record RightsSpec(String subjectRef,List<Right> rights,Instant validFrom,Instant validUntil) {
  public RightsSpec {
   requireRef(subjectRef); rights=List.copyOf(rights);
   if(rights.size()>64) throw new IllegalArgumentException("Até 64 direitos por pessoa e equipamento.");
   if(Set.copyOf(rights).size()!=rights.size()) throw new IllegalArgumentException("Direito repetido.");
   if(validFrom!=null && validUntil!=null && !validFrom.isBefore(validUntil)) throw new IllegalArgumentException("Validade inválida.");
  }
 }

 /**
  * Estado observado no equipamento para uma pessoa. null em qualquer campo significa "não foi possível observar".
  * rightKeys são as chaves nativas de direito lidas de volta (comparáveis a {@link AccessProviderAdapter#rightKeys});
  * personAttributes, os campos da pessoa lidos de volta (comparáveis a {@link AccessProviderAdapter#personAttributes}).
  */
 public record AppliedState(OperationState state,String reasonCode,Boolean personPresent,Set<String> rightKeys,
     Map<CredentialKind,Integer> credentialCounts,Boolean facePresent,Map<String,String> personAttributes) {
  public AppliedState { rightKeys=rightKeys==null?null:Set.copyOf(rightKeys); credentialCounts=credentialCounts==null?null:Map.copyOf(credentialCounts);
   personAttributes=personAttributes==null?null:Map.copyOf(personAttributes); }
  public AppliedState(OperationState state,String reasonCode,Boolean personPresent,Set<String> rightKeys,Map<CredentialKind,Integer> credentialCounts,Boolean facePresent) {
   this(state,reasonCode,personPresent,rightKeys,credentialCounts,facePresent,null);
  }
  public static AppliedState unavailable(OperationState state,String reason) { return new AppliedState(state,reason,null,null,null,null,null); }
 }

 /** Abertura já validada pelo despachante; o adaptador só traduz o canal comissionado para a ação nativa. */
 public record OpenAction(String commandId,String channelId,String nativeChannel,String nativeOpenAction,Instant expiresAt) {}

 /**
  * Resultado da abertura no adaptador. NOT_SENT: comprovadamente não chegou ao equipamento (conexão recusada antes do
  * envio, ou nunca retirado da fila do modo nuvem). AMBIGUOUS: pode ter chegado; nunca repetir.
  */
 public enum OpenOutcome { ACCEPTED, CONFIRMED, NOT_SENT, REJECTED_BY_DEVICE, AMBIGUOUS, NAO_SUPORTADO_SEM_DOCUMENTACAO }
 public record OpenResult(OpenOutcome outcome,String reasonCode) {}

 public enum EventKind {
  IDENTITY_RECOGNIZED, ACCESS_ALLOWED, ACCESS_DENIED, OPEN_COMMAND_ACCEPTED, RELAY_TRIGGERED, DOOR_OPENED, PASSAGE_DETECTED,
  DOOR_FORCED, DOOR_HELD, ENTITLEMENT_APPLIED, CREDENTIAL_REVOKED
 }
 public enum Direction { IN, OUT, UNKNOWN }
 public enum TimeQuality { VERIFIED, DEVICE_UNVERIFIED, UNKNOWN }
 public enum EvidenceLevel { DEVICE_REPORTED, SENSOR_OBSERVED, GATEWAY_OBSERVED, INFERRED, UNKNOWN }
 public enum EvidenceSource { DEVICE_EVENT, DOOR_CONTACT, PASSAGE_SENSOR, GATEWAY_RECEIPT, PLATFORM_INFERENCE }

 /** Força da evidência explícita (OpenAPI EventEvidence). Sem foto, base64 ou payload livre. */
 public record Evidence(EvidenceLevel level,EvidenceSource sourceKind,String nativeKind,String evidenceRef,String inferenceReason) {
  public Evidence {
   Objects.requireNonNull(level); Objects.requireNonNull(sourceKind);
   if(nativeKind!=null && !nativeKind.matches("[A-Za-z0-9_.:-]{1,80}")) throw new IllegalArgumentException("nativeKind fora do formato.");
  }
 }

 /**
  * Evento normalizado no formato do OpenAPI EdgeEvent 1.0. bindingRevision e pointId ficam null: o servidor resolve o
  * vínculo histórico pelo canal. eventId é derivado da chave de deduplicação (equipamento provisionado, época,
  * identificador nativo), de modo que reenvio mantém o mesmo eventId.
  */
 public record EdgeEvent(String eventId,String deviceId,String deviceProvisioningId,String eventEpoch,String nativeEventId,
     String sourceConnectorId,String subjectRef,EventKind kind,Instant occurredAt,Direction direction,TimeQuality timeQuality,
     Evidence evidence,String deviceChannelId,String nativeChannelId) {
  public EdgeEvent {
   requireRef(deviceId); requireRef(deviceProvisioningId); requireRef(eventEpoch); requireRef(sourceConnectorId);
   if(nativeEventId==null || nativeEventId.isBlank() || nativeEventId.length()>200) throw new IllegalArgumentException("nativeEventId obrigatório.");
   Objects.requireNonNull(kind); Objects.requireNonNull(direction); Objects.requireNonNull(timeQuality); Objects.requireNonNull(evidence);
   if(eventId==null) eventId=deterministicId(deviceProvisioningId,eventEpoch,nativeEventId);
  }
  public String dedupKey() { return deviceProvisioningId+"|"+eventEpoch+"|"+nativeEventId; }
  public static String deterministicId(String provisioning,String epoch,String nativeId) {
   return UUID.nameUUIDFromBytes((provisioning+"|"+epoch+"|"+nativeId).getBytes(StandardCharsets.UTF_8)).toString();
  }
 }

 /** Página de eventos. nextCursor null: sem mais eventos agora. unmapped: eventos nativos sem tipo normalizado. */
 public record EventPage(OperationState state,String reasonCode,List<EdgeEvent> events,String nextCursor,int unmapped) {
  public EventPage { events=List.copyOf(events); }
  public static EventPage unavailable(OperationState state,String reason,String cursor) { return new EventPage(state,reason,List.of(),cursor,0); }
 }

 /** Saúde observada. reachable só é true quando a sonda documentada respondeu agora; ausência de eventos não conta. */
 public record HealthReport(boolean reachable,String reasonCode,Long clockSkewMillis,String firmwareReported,String serialReported) {}

 /** Identificação real do equipamento, para confrontar com o perfil da combinação na conexão e na reconexão. */
 public record DeviceIdentity(OperationState state,String reasonCode,String sku,String firmware,String serial) {}

 private static void requireRef(String value) {
  if(value==null || value.isBlank() || value.length()>200) throw new IllegalArgumentException("Referência obrigatória de até 200 caracteres.");
 }
}
