package br.com.condpulse.accessintegration.simulated;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import br.com.condpulse.accessintegration.application.AccessAdapter;
import br.com.condpulse.accessintegration.application.AccessModels;
import br.com.condpulse.accessintegration.application.AccessModels.Action;
import br.com.condpulse.accessintegration.application.AccessModels.Destination;
import br.com.condpulse.accessintegration.application.AccessModels.Outcome;

/**
 * Adaptador simulado do contrato interno de acesso (CP-043): determinístico, em memória, sem rede e sem contato com
 * equipamento real. Cada equipamento recebe um {@link Scenario}; o relógio é injetado. Serve para testar quem orquestra
 * credenciais (estado desejado, repetição, revogação prioritária, conferência por destino) antes de existir parceiro.
 *
 * <p>Regras que o simulador impõe, como se espera de um parceiro real homologado:
 * <ul>
 * <li>Comando idempotente por credencial, versão e ação: repetir devolve o mesmo trabalho e não aplica de novo.</li>
 * <li>Versão menor ou igual à aplicada no equipamento nunca substitui a aplicada (revogação não é desfeita por
 * aplicação atrasada); a mesma versão com outra ação é conflito.</li>
 * <li>Confirmação de recebimento (ACCEPTED) não é aplicação; a confirmação tardia de versão antiga é descartada.</li>
 * <li>Abertura remota desligada por padrão; pedido vencido ou equipamento fora do ar nunca vira fila.</li>
 * </ul>
 */
public final class SimulatedAccessAdapter implements AccessAdapter {
 public static final String PROVIDER="simulado";
 public static final Duration RETRY_AFTER=Duration.ofSeconds(30);

 /** Comportamento de um equipamento para os próximos comandos. */
 public enum Scenario {
  /** Aplica e confirma na resposta. */
  CONFIRM,
  /** Só confirma recebimento; aplica quando {@link #deliverPending} for chamado. */
  ACK_ONLY,
  /** Aplica, mas a resposta se perde (UNKNOWN); a consulta mostra a versão aplicada. */
  TIMEOUT_APPLIED,
  /** A resposta se perde (UNKNOWN) e nada é aplicado; a consulta mostra que a versão não chegou. */
  TIMEOUT_LOST,
  /** Recusa definitiva documentada. */
  REJECT,
  /** Limite temporário com Retry-After; nada é aplicado. */
  RATE_LIMITED,
  /** Fora do ar: nada é enviado. */
  OFFLINE
 }

 private record Key(String credentialRef,long version,Action action) {}
 private record Applied(long version,Action action,Instant at) {}
 private record Pending(long version,Action action,String credentialRef) {}
 private static final class Device {
  Scenario scenario=Scenario.CONFIRM;
  final Map<String,Applied> applied=new HashMap<>();
  final Map<String,Pending> pending=new HashMap<>();
  int applications;
  Instant lastHeartbeat;
  Duration clockSkew=Duration.ZERO;
 }

 private final String connectorId;
 private final Clock clock;
 private final boolean openEnabled;
 private final Map<String,Device> devices=new LinkedHashMap<>();
 private final Map<Key,AccessModels.Application> jobs=new HashMap<>();
 private final Map<String,String> people=new HashMap<>();
 private final Map<String,AccessModels.OpenResult> opens=new HashMap<>();
 private final List<AccessModels.Event> events=new ArrayList<>();
 private int sequence;

 public SimulatedAccessAdapter(String connectorId,Clock clock,boolean openEnabled,List<String> deviceIds) {
  if(connectorId==null || connectorId.isBlank()) throw new IllegalArgumentException("Conector obrigatório.");
  this.connectorId=connectorId; this.clock=Objects.requireNonNull(clock); this.openEnabled=openEnabled;
  for(String id:deviceIds) { var device=new Device(); device.lastHeartbeat=clock.instant(); devices.put(id,device); }
 }

 public synchronized void scenario(String deviceId,Scenario scenario) {
  var device=device(deviceId);
  boolean wasOffline=device.scenario==Scenario.OFFLINE;
  device.scenario=Objects.requireNonNull(scenario);
  if(scenario==Scenario.OFFLINE && !wasOffline) emit(deviceId,null,AccessModels.EventKind.DEVICE_OFFLINE,null,null,null);
  if(scenario!=Scenario.OFFLINE && wasOffline) { device.lastHeartbeat=clock.instant(); emit(deviceId,null,AccessModels.EventKind.DEVICE_ONLINE,null,null,null); }
 }
 public synchronized void clockSkew(String deviceId,Duration skew) { device(deviceId).clockSkew=Objects.requireNonNull(skew); }
 /** Quantas vezes o equipamento efetivamente aplicou um comando (prova de que repetição não aplica de novo). */
 public synchronized int applications(String deviceId) { return device(deviceId).applications; }

 /**
  * Confirmação tardia do que ficou só recebido (ACK_ONLY). Versão que já não é a mais nova do equipamento é descartada:
  * confirmação antiga nunca reativa credencial revogada. Devolve quantas aplicações aconteceram.
  */
 public synchronized int deliverPending(String deviceId) {
  var device=device(deviceId);
  if(device.scenario==Scenario.OFFLINE) return 0;
  int delivered=0;
  for(var pending:List.copyOf(device.pending.values())) {
   device.pending.remove(pending.credentialRef());
   if(apply(deviceId,device,pending.credentialRef(),pending.version(),pending.action())) delivered++;
  }
  return delivered;
 }

 /** Passagem detectada pelo sensor; {@code duplicate} reenvia o mesmo evento (mesmo eventId), como faz um parceiro real. */
 public synchronized void emitPassage(String deviceId,String credentialRef,AccessModels.Direction direction,boolean duplicate) {
  device(deviceId);
  var event=emit(deviceId,credentialRef,AccessModels.EventKind.PASSAGE_DETECTED,direction,"TURNSTILE_SENSOR",null);
  if(duplicate) events.add(event);
 }

 @Override public synchronized AccessModels.Capabilities capabilities(String connector) {
  connector(connector);
  return new AccessModels.Capabilities(connectorId,Set.of("capabilities","upsertPerson","applyCredential","getApplication","revokeCredential",
   "getEvents","health","requestOpen"),EnumSet.allOf(AccessModels.EventKind.class),true,50,100,openEnabled,false);
 }

 @Override public synchronized AccessModels.PersonResult upsertPerson(AccessModels.UpsertPerson request) {
  connector(request.connectorId());
  String id=people.computeIfAbsent(request.externalRef(),ref->"sim-person-"+(people.size()+1));
  return new AccessModels.PersonResult(id,Outcome.APPLIED,null);
 }

 @Override public synchronized AccessModels.Application applyCredential(AccessModels.ApplyCredential request) {
  connector(request.connectorId());
  return command(request.credentialRef(),request.desiredVersion(),Action.APPLY,request.destinations());
 }

 @Override public synchronized AccessModels.Application revokeCredential(AccessModels.RevokeCredential request) {
  connector(request.connectorId());
  return command(request.credentialRef(),request.desiredVersion(),Action.REVOKE,request.destinations());
 }

 /** Estado real de cada equipamento para a versão pedida (a consulta não muda nada). */
 @Override public synchronized AccessModels.Application getApplication(AccessModels.ApplicationQuery query) {
  connector(query.connectorId());
  long version=query.desiredVersion();
  Action action=query.action();
  List<Destination> result=new ArrayList<>();
  for(String deviceId:query.destinations()) {
   var device=device(deviceId);
   var applied=device.applied.get(query.credentialRef());
   var pending=device.pending.get(query.credentialRef());
   if(device.scenario==Scenario.OFFLINE) result.add(new Destination(deviceId,Outcome.UNAVAILABLE,null,null,null,"DEVICE_OFFLINE",null));
   else if(applied!=null && applied.version()==version && applied.action()==action)
    result.add(new Destination(deviceId,Outcome.APPLIED,applied.version(),applied.action(),applied.at(),null,null));
   else if(applied!=null && applied.version()>=version)
    result.add(new Destination(deviceId,Outcome.REJECTED,applied.version(),applied.action(),applied.at(),
     applied.version()>version?"STALE_VERSION":"VERSION_CONFLICT",null));
   else if(pending!=null && pending.version()==version && pending.action()==action)
    result.add(new Destination(deviceId,Outcome.ACCEPTED,applied==null?null:applied.version(),applied==null?null:applied.action(),
     applied==null?null:applied.at(),null,null));
   else if(pending!=null && pending.version()>=version)
    result.add(new Destination(deviceId,Outcome.REJECTED,applied==null?null:applied.version(),applied==null?null:applied.action(),
     applied==null?null:applied.at(),pending.version()>version?"STALE_VERSION":"VERSION_CONFLICT",null));
   else
    // Equipamento respondeu e a versão não está lá: o comando pode ser repetido (idempotente por versão e ação).
    result.add(new Destination(deviceId,Outcome.UNKNOWN,applied==null?null:applied.version(),applied==null?null:applied.action(),
     applied==null?null:applied.at(),"VERSION_NOT_PRESENT",null));
  }
  return new AccessModels.Application(jobRef(query.credentialRef(),version,action),query.credentialRef(),version,action,result);
 }

 @Override public synchronized AccessModels.EventPage getEvents(AccessModels.EventQuery query) {
  connector(query.connectorId());
  int start=0;
  if(query.cursor()!=null) {
   if(!query.cursor().matches("sim-cursor-[0-9]{1,9}")) throw new IllegalArgumentException("Cursor inválido.");
   start=Integer.parseInt(query.cursor().substring("sim-cursor-".length()));
   if(start>events.size()) throw new IllegalArgumentException("Cursor inválido.");
  }
  List<AccessModels.Event> page=new ArrayList<>();
  int index=start;
  for(;index<events.size() && page.size()<query.limit();index++) {
   var event=events.get(index);
   if(!event.receivedAt().isBefore(query.from()) && event.receivedAt().isBefore(query.until())) page.add(event);
  }
  return new AccessModels.EventPage(page,index<events.size()?"sim-cursor-"+index:null);
 }

 @Override public synchronized AccessModels.Health health(String connector) {
  connector(connector);
  List<AccessModels.DeviceHealth> list=new ArrayList<>();
  int pending=0;
  for(var entry:devices.entrySet()) {
   var device=entry.getValue();
   boolean online=device.scenario!=Scenario.OFFLINE;
   if(online) device.lastHeartbeat=clock.instant();
   pending+=device.pending.size();
   list.add(new AccessModels.DeviceHealth(entry.getKey(),online,device.lastHeartbeat,device.clockSkew));
  }
  return new AccessModels.Health(connectorId,true,list,pending,Duration.ZERO);
 }

 @Override public synchronized AccessModels.OpenResult requestOpen(AccessModels.OpenRequest request) {
  connector(request.connectorId());
  var previous=opens.get(request.requestId());
  if(previous!=null) return previous;
  var device=device(request.deviceId());
  AccessModels.OpenResult result;
  if(!openEnabled) result=new AccessModels.OpenResult(request.requestId(),AccessModels.OpenOutcome.REJECTED,"OPEN_NOT_ENABLED");
  else if(!clock.instant().isBefore(request.expiresAt())) result=new AccessModels.OpenResult(request.requestId(),AccessModels.OpenOutcome.REJECTED,"EXPIRED");
  else if(device.scenario==Scenario.OFFLINE) result=new AccessModels.OpenResult(request.requestId(),AccessModels.OpenOutcome.REJECTED,"DEVICE_OFFLINE");
  else if(device.scenario==Scenario.TIMEOUT_APPLIED || device.scenario==Scenario.TIMEOUT_LOST)
   result=new AccessModels.OpenResult(request.requestId(),AccessModels.OpenOutcome.UNKNOWN,"TIMEOUT");
  else if(device.scenario==Scenario.REJECT || device.scenario==Scenario.RATE_LIMITED)
   result=new AccessModels.OpenResult(request.requestId(),AccessModels.OpenOutcome.REJECTED,device.scenario.name());
  else {
   emit(request.deviceId(),null,AccessModels.EventKind.RELAY_TRIGGERED,null,null,null);
   result=new AccessModels.OpenResult(request.requestId(),AccessModels.OpenOutcome.EXECUTED,null);
  }
  // Nenhum pedido de abertura é guardado para reenvio: repetir devolve a mesma resposta, nunca reabre.
  opens.put(request.requestId(),result);
  return result;
 }

 private AccessModels.Application command(String credentialRef,long version,Action action,List<String> destinations) {
  destinations.forEach(this::device);
  var key=new Key(credentialRef,version,action);
  var previous=jobs.get(key);
  // Repetição: destinos já resolvidos devolvem o mesmo resultado; incertos, recusados por limite ou fora do ar são
  // tentados de novo (a aplicação continua idempotente pela versão).
  List<Destination> result=new ArrayList<>();
  for(String deviceId:destinations) {
   Destination before=previous==null?null:previous.destinations().stream().filter(d->d.deviceId().equals(deviceId)).findFirst().orElse(null);
   if(before!=null && (before.outcome()==Outcome.APPLIED || before.outcome()==Outcome.REJECTED || before.outcome()==Outcome.ACCEPTED)) {
    result.add(refresh(deviceId,credentialRef,version,action,before));
    continue;
   }
   result.add(send(deviceId,credentialRef,version,action));
  }
  var application=new AccessModels.Application(jobRef(credentialRef,version,action),credentialRef,version,action,result);
  // Histórico por destino: a repetição parcial atualiza só os destinos enviados e preserva os demais.
  var merged=new java.util.LinkedHashMap<String,Destination>();
  if(previous!=null) previous.destinations().forEach(d->merged.put(d.deviceId(),d));
  result.forEach(d->merged.put(d.deviceId(),d));
  jobs.put(key,new AccessModels.Application(application.jobRef(),credentialRef,version,action,List.copyOf(merged.values())));
  return application;
 }

 /** Destino já resolvido: ACCEPTED vira APPLIED se a confirmação chegou depois, ou recusa se outra versão passou à frente. */
 private Destination refresh(String deviceId,String credentialRef,long version,Action action,Destination before) {
  // Recusa é definitiva; recebido ou confirmado é revalidado contra o estado atual (sem reenviar o comando).
  if(before.outcome()!=Outcome.ACCEPTED && before.outcome()!=Outcome.APPLIED) return before;
  var applied=device(deviceId).applied.get(credentialRef);
  if(applied==null || applied.version()<version) {
   // Versão maior recebida depois substituiu esta na fila: não há mais confirmação a aguardar.
   var pending=device(deviceId).pending.get(credentialRef);
   if(pending!=null && pending.version()>version)
    return new Destination(deviceId,Outcome.REJECTED,applied==null?null:applied.version(),applied==null?null:applied.action(),
     applied==null?null:applied.at(),"STALE_VERSION",null);
   return before;
  }
  if(applied.version()==version && applied.action()==action) return new Destination(deviceId,Outcome.APPLIED,applied.version(),applied.action(),applied.at(),null,null);
  if(applied.version()==version) return new Destination(deviceId,Outcome.REJECTED,applied.version(),applied.action(),applied.at(),"VERSION_CONFLICT",null);
  return new Destination(deviceId,Outcome.REJECTED,applied.version(),applied.action(),applied.at(),"STALE_VERSION",null);
 }

 private Destination send(String deviceId,String credentialRef,long version,Action action) {
  var device=device(deviceId);
  var applied=device.applied.get(credentialRef);
  switch(device.scenario) {
   case OFFLINE: return new Destination(deviceId,Outcome.UNAVAILABLE,null,null,null,"DEVICE_OFFLINE",null);
   case RATE_LIMITED: return new Destination(deviceId,Outcome.RATE_LIMITED,null,null,null,"RATE_LIMITED",RETRY_AFTER);
   case REJECT:
    emit(deviceId,credentialRef,AccessModels.EventKind.CREDENTIAL_REJECTED,null,null,version);
    return new Destination(deviceId,Outcome.REJECTED,null,null,null,"DEVICE_REJECTED",null);
   default: break;
  }
  if(applied!=null && (applied.version()>version || applied.version()==version && applied.action()!=action))
   return new Destination(deviceId,Outcome.REJECTED,applied.version(),applied.action(),applied.at(),
    applied.version()>version?"STALE_VERSION":"VERSION_CONFLICT",null);
  // A pendência recebida vale em qualquer cenário: mudar o equipamento para confirmar não troca a ação já aceita.
  var pending=device.pending.get(credentialRef);
  if(pending!=null && pending.version()>version)
   return new Destination(deviceId,Outcome.REJECTED,null,null,null,"STALE_VERSION",null);
  if(pending!=null && pending.version()==version && pending.action()!=action)
   return new Destination(deviceId,Outcome.REJECTED,null,null,null,"VERSION_CONFLICT",null);
  switch(device.scenario) {
   case ACK_ONLY:
    device.pending.put(credentialRef,new Pending(version,action,credentialRef));
    return new Destination(deviceId,Outcome.ACCEPTED,null,null,null,null,null);
   case TIMEOUT_LOST: return new Destination(deviceId,Outcome.UNKNOWN,null,null,null,"TIMEOUT",null);
   case TIMEOUT_APPLIED:
    apply(deviceId,device,credentialRef,version,action);
    return new Destination(deviceId,Outcome.UNKNOWN,null,null,null,"TIMEOUT",null);
   default:
    apply(deviceId,device,credentialRef,version,action);
    var now=device.applied.get(credentialRef);
    return new Destination(deviceId,Outcome.APPLIED,now.version(),now.action(),now.at(),null,null);
  }
 }

 /** Aplica só versão mais nova que a do equipamento; a mesma versão e ação já aplicada não conta de novo. */
 private boolean apply(String deviceId,Device device,String credentialRef,long version,Action action) {
  var applied=device.applied.get(credentialRef);
  if(applied!=null && applied.version()>=version) return false;
  device.applied.put(credentialRef,new Applied(version,action,clock.instant()));
  device.applications++;
  var pending=device.pending.get(credentialRef);
  if(pending!=null && pending.version()<=version) device.pending.remove(credentialRef);
  emit(deviceId,credentialRef,action==Action.APPLY?AccessModels.EventKind.CREDENTIAL_APPLIED:AccessModels.EventKind.CREDENTIAL_REVOKED,null,null,version);
  return true;
 }

 private AccessModels.Event emit(String deviceId,String credentialRef,AccessModels.EventKind kind,AccessModels.Direction direction,String evidence,Long version) {
  Instant now=clock.instant();
  var event=new AccessModels.Event("1","sim-event-"+(++sequence),PROVIDER,deviceId,credentialRef,kind,direction,now.plus(device(deviceId).clockSkew),now,
   evidence,version,null);
  events.add(event);
  return event;
 }
 private static String jobRef(String credentialRef,long version,Action action) {
  return "sim-job-"+credentialRef+"-"+version+(action==null?"":"-"+action.name().toLowerCase(java.util.Locale.ROOT));
 }
 private Device device(String deviceId) {
  var device=devices.get(deviceId);
  if(device==null) throw new IllegalArgumentException("Equipamento desconhecido no simulador: "+deviceId);
  return device;
 }
 private void connector(String connector) {
  if(!connectorId.equals(connector)) throw new IllegalArgumentException("Conector diferente do simulador.");
 }
}
