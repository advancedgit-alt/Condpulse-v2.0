package br.com.condpulse.accessintegration.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Tipos normalizados do contrato interno de acesso v1. Campo ausente continua ausente (null): nada é inferido. */
public final class AccessModels {
 private AccessModels() {}

 /** Resultado normalizado de uma operação, por destino quando houver destino. */
 public enum Outcome {
  /** Parceiro recebeu: aguardar consulta/callback; não ativar no app. */
  ACCEPTED(Conduct.AWAIT_CONFIRMATION),
  /** Versão aplicada no destino identificado: confirmar somente esse destino. */
  APPLIED(Conduct.CONFIRM_DESTINATION),
  /** Falha definitiva documentada: informar correção e não repetir sem mudança. */
  REJECTED(Conduct.FIX_BEFORE_RETRY),
  /** Limite temporário: repetir com espera crescente e variação, respeitando Retry-After. */
  RATE_LIMITED(Conduct.BACKOFF),
  /** Houve envio e o resultado é incerto: consultar o estado antes de nova mutação. */
  UNKNOWN(Conduct.QUERY_BEFORE_RETRY),
  /** Não foi possível comunicar: manter pendência e continuidade local. */
  UNAVAILABLE(Conduct.KEEP_PENDING);
  private final Conduct conduct;
  Outcome(Conduct conduct) { this.conduct=conduct; }
  public Conduct conduct() { return conduct; }
  /** Só aplicação confirmada no destino ativa algo para a pessoa. */
  public boolean activates() { return this==APPLIED; }
 }
 public enum Conduct { AWAIT_CONFIRMATION, CONFIRM_DESTINATION, FIX_BEFORE_RETRY, BACKOFF, QUERY_BEFORE_RETRY, KEEP_PENDING }

 public enum Action { APPLY, REVOKE }

 public record Capabilities(String connectorId,Set<String> operations,Set<EventKind> eventKinds,boolean confirmsApplication,
     int maxDestinationsPerCommand,int maxEventsPerPage,boolean openEnabled,boolean offlineMode) {
  public Capabilities { operations=Set.copyOf(operations); eventKinds=Set.copyOf(eventKinds); }
 }
 public record UpsertPerson(String connectorId,String correlationId,String externalRef,String displayName) {
  public UpsertPerson { requireText(connectorId,"connectorId"); requireText(correlationId,"correlationId"); requireText(externalRef,"externalRef"); }
 }
 public record PersonResult(String providerPersonId,Outcome outcome,String reason) {}

 /**
  * Credencial no estado desejado {@code desiredVersion} para os destinos. A referência da foto é temporária e nunca é
  * guardada pelo adaptador.
  */
 public record ApplyCredential(String connectorId,String correlationId,String personRef,String credentialRef,long desiredVersion,
     List<String> destinations,Instant validFrom,Instant validUntil,String temporaryPhotoRef) {
  public ApplyCredential {
   requireText(connectorId,"connectorId"); requireText(correlationId,"correlationId"); requireText(personRef,"personRef");
   requireText(credentialRef,"credentialRef"); requireVersion(desiredVersion); destinations=requireDestinations(destinations);
   if(validFrom!=null && validUntil!=null && !validFrom.isBefore(validUntil)) throw new IllegalArgumentException("Validade inválida.");
  }
 }
 public record RevokeCredential(String connectorId,String correlationId,String credentialRef,long desiredVersion,List<String> destinations,String reason) {
  public RevokeCredential {
   requireText(connectorId,"connectorId"); requireText(correlationId,"correlationId"); requireText(credentialRef,"credentialRef");
   requireVersion(desiredVersion); destinations=requireDestinations(destinations); requireText(reason,"reason");
  }
 }
 /** Consulta do estado desejado completo: versão e ação; destino com a mesma versão e outra ação não confirma. */
 public record ApplicationQuery(String connectorId,String credentialRef,long desiredVersion,Action action,List<String> destinations) {
  public ApplicationQuery {
   requireText(connectorId,"connectorId"); requireText(credentialRef,"credentialRef"); requireVersion(desiredVersion);
   Objects.requireNonNull(action,"action");
   destinations=requireDestinations(destinations);
  }
 }
 /**
  * Estado por destino. {@code appliedVersion}/{@code appliedAction}/{@code confirmedAt} só existem quando o equipamento
  * confirmou; {@code retryAfter} só em RATE_LIMITED.
  */
 public record Destination(String deviceId,Outcome outcome,Long appliedVersion,Action appliedAction,Instant confirmedAt,String reason,Duration retryAfter) {}
 /** Resultado do comando: um destino com falha aparece como falha naquele destino, sem esconder os demais. */
 public record Application(String jobRef,String credentialRef,long desiredVersion,Action action,List<Destination> destinations) {
  public Application { destinations=List.copyOf(destinations); }
  public Destination destination(String deviceId) {
   return destinations.stream().filter(d->d.deviceId().equals(deviceId)).findFirst().orElseThrow();
  }
  /** Todos os destinos confirmaram esta versão. */
  public boolean fullyApplied() { return destinations.stream().allMatch(d->d.outcome()==Outcome.APPLIED); }
  public Map<Outcome,Long> summary() {
   return destinations.stream().collect(java.util.stream.Collectors.groupingBy(Destination::outcome,java.util.TreeMap::new,java.util.stream.Collectors.counting()));
  }
 }

 public enum EventKind { AUTHORIZED, DENIED, RELAY_TRIGGERED, DOOR_OPENED, PASSAGE_DETECTED, CREDENTIAL_APPLIED, CREDENTIAL_REJECTED,
  CREDENTIAL_REVOKED, DEVICE_ONLINE, DEVICE_OFFLINE }
 public enum Direction { ENTRY, EXIT, UNKNOWN }
 /** Envelope normalizado (schemaVersion 1). direction, evidence, appliedVersion e correlationId podem estar ausentes. */
 public record Event(String schemaVersion,String eventId,String provider,String deviceExternalId,String credentialExternalId,EventKind kind,
     Direction direction,Instant occurredAt,Instant receivedAt,String evidence,Long appliedVersion,String correlationId) {
  public Event {
   if(!"1".equals(schemaVersion)) throw new IllegalArgumentException("Versão de envelope não suportada.");
   requireText(eventId,"eventId"); requireText(provider,"provider"); requireText(deviceExternalId,"deviceExternalId");
   Objects.requireNonNull(kind,"kind"); Objects.requireNonNull(occurredAt,"occurredAt"); Objects.requireNonNull(receivedAt,"receivedAt");
  }
 }
 public record EventQuery(String connectorId,String cursor,Instant from,Instant until,int limit) {
  public static final Duration MAX_WINDOW=Duration.ofHours(24);
  public EventQuery {
   requireText(connectorId,"connectorId");
   if(limit<1 || limit>100) throw new IllegalArgumentException("Página de 1 a 100 eventos.");
   if(from==null || until==null || !from.isBefore(until) || Duration.between(from,until).compareTo(MAX_WINDOW)>0)
    throw new IllegalArgumentException("Janela de eventos limitada a 24 horas.");
  }
 }
 /** {@code nextCursor} null: não há mais eventos na janela agora. */
 public record EventPage(List<Event> events,String nextCursor) {
  public EventPage { events=List.copyOf(events); }
 }
 public record DeviceHealth(String deviceId,boolean online,Instant lastHeartbeat,Duration clockSkew) {}
 public record Health(String connectorId,boolean reachable,List<DeviceHealth> devices,int pendingCommands,Duration observedDelay) {
  public Health { devices=List.copyOf(devices); }
 }
 public enum OpenOutcome { ACK, EXECUTED, REJECTED, UNKNOWN }
 public record OpenRequest(String connectorId,String requestId,String deviceId,String authorizationRef,Instant expiresAt) {
  public OpenRequest {
   requireText(connectorId,"connectorId"); requireText(requestId,"requestId"); requireText(deviceId,"deviceId");
   requireText(authorizationRef,"authorizationRef"); Objects.requireNonNull(expiresAt,"expiresAt");
  }
 }
 public record OpenResult(String requestId,OpenOutcome outcome,String reason) {}

 private static void requireText(String value,String name) {
  if(value==null || value.isBlank() || value.length()>200) throw new IllegalArgumentException("Campo obrigatório: "+name);
 }
 private static void requireVersion(long version) { if(version<1) throw new IllegalArgumentException("Versão desejada a partir de 1."); }
 private static List<String> requireDestinations(List<String> destinations) {
  if(destinations==null || destinations.isEmpty() || destinations.size()>50) throw new IllegalArgumentException("Informe de 1 a 50 destinos.");
  var copy=List.copyOf(destinations);
  copy.forEach(d->requireText(d,"destino"));
  if(Set.copyOf(copy).size()!=copy.size()) throw new IllegalArgumentException("Destino repetido.");
  return copy;
 }
}
