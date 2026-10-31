package br.com.condpulse.gateway.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import br.com.condpulse.gateway.capability.CapabilityRegistry;
import br.com.condpulse.gateway.capability.CapabilityRegistry.IdentityCheck;
import br.com.condpulse.gateway.central.CentralApiClient;
import br.com.condpulse.gateway.central.CentralApiClient.CentralUnavailable;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.GatewayConfig.Device;
import br.com.condpulse.gateway.decision.LocalDecisionEngine;
import br.com.condpulse.gateway.events.EventOutbox;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.open.OpenDispatcher;
import br.com.condpulse.gateway.queue.LocalCommandQueue;
import br.com.condpulse.gateway.queue.LocalCommandQueue.CommandType;
import br.com.condpulse.gateway.queue.LocalCommandQueue.Entry;
import br.com.condpulse.gateway.queue.LocalCommandQueue.EnqueueResult;
import br.com.condpulse.gateway.queue.LocalCommandQueue.Status;
import br.com.condpulse.gateway.queue.LocalCommandQueue.SyncCommand;
import br.com.condpulse.gateway.spi.AccessProviderAdapter;
import br.com.condpulse.gateway.spi.BaseAdapter;
import br.com.condpulse.gateway.spi.InboundEndpoint;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Núcleo do gateway, o mesmo nos modos LOCAL (container na LAN do condomínio) e NUVEM (serviço central que recebe
 * conexões autenticadas dos equipamentos). Executa a fila local por equipamento, verifica por leitura de volta,
 * encaminha eventos, despacha aberturas e atende chamadas dos equipamentos com decisão local.
 */
public final class GatewayService {
 private static final System.Logger LOG=System.getLogger(GatewayService.class.getName());

 /** Situação de cada equipamento vista pelo gateway. */
 public record DeviceStatus(boolean reachable,String reasonCode,Instant lastCheckedAt,Instant lastHeartbeatAt,IdentityCheck identity,boolean quarantined) {}

 private final GatewayConfig config;
 private final CapabilityRegistry registry;
 private final Map<String,AccessProviderAdapter> adapters;
 private final LocalCommandQueue queue;
 private final OpenDispatcher opens;
 private final EventOutbox outbox;
 private final LocalDecisionEngine decisions;
 private final CentralApiClient central;
 private final Clock clock;
 private final Map<String,DeviceStatus> status=new ConcurrentHashMap<>();

 public GatewayService(GatewayConfig config,CapabilityRegistry registry,Map<String,AccessProviderAdapter> adapters,LocalCommandQueue queue,
     OpenDispatcher opens,EventOutbox outbox,LocalDecisionEngine decisions,CentralApiClient central,Clock clock) {
  this.config=config; this.registry=registry; this.adapters=Map.copyOf(adapters); this.queue=queue; this.opens=opens;
  this.outbox=outbox; this.decisions=decisions; this.central=central; this.clock=clock;
 }

 public Optional<AccessProviderAdapter> adapter(String deviceId) { return Optional.ofNullable(adapters.get(deviceId)); }
 public Optional<DeviceStatus> status(String deviceId) { return Optional.ofNullable(status.get(deviceId)); }

 /**
  * Recebe um comando da plataforma. Revogação vale na decisão local no mesmo instante; direitos novos entram na
  * decisão local quando aceitos e não superados.
  */
 public EnqueueResult submit(SyncCommand command) {
  var device=config.device(command.deviceId()).orElseThrow(()->new IllegalArgumentException("Equipamento desconhecido."));
  var result=queue.enqueue(command);
  if(result.outcome()!=LocalCommandQueue.EnqueueOutcome.ACCEPTED) return result;
  switch(command.type()) {
   case REVOKE_ALL,REVOKE_RIGHTS -> decisions.revoke(device.deviceId(),command.subjectRef(),command.desiredVersion());
   case APPLY_RIGHTS -> {
    var spec=rights(command);
    decisions.grant(device.deviceId(),command.subjectRef(),command.desiredVersion(),
     spec.rights().stream().map(r->new String[]{r.channelId(),r.scheduleRef()}).toList(),spec.validFrom(),spec.validUntil());
   }
   case UPSERT_PERSON -> {
    String nativeRef=Json.string(command.payload(),"nativeUserRef");
    if(nativeRef!=null) decisions.mapNativeUser(device.deviceId(),nativeRef,command.subjectRef());
   }
   default -> {}
  }
  return result;
 }

 /** Uma passada: até {@code perDevice} comandos por equipamento habilitado. */
 public void processQueues(int perDevice) {
  for(Device d:config.devices()) {
   if(!d.enabled()) continue;
   for(int i=0;i<perDevice;i++) {
    var next=queue.next(d.deviceId());
    if(next.isEmpty()) break;
    if(!process(d,next.get())) break;
   }
  }
 }

 /** Processa um comando. Devolve false quando o equipamento está fora do ar (para a passada nele). */
 boolean process(Device device,Entry entry) {
  var command=entry.command();
  var adapter=adapters.get(device.deviceId());
  if(adapter==null) { queue.mark(command.commandId(),Status.PENDING,null,"ADAPTER_UNAVAILABLE"); return false; }
  var st=status.get(device.deviceId());
  if(st!=null && st.quarantined() && !command.type().revocation()) {
   queue.mark(command.commandId(),Status.PENDING,null,"DEVICE_IDENTITY_MISMATCH"); return false;
  }
  if(entry.status()==Status.NEEDS_READBACK) {
   var verdict=verify(adapter,command);
   if(verdict==OperationState.CONFIRMED) { finish(command,Status.DONE,OperationState.CONFIRMED,"CONFIRMED_BY_READBACK"); return true; }
   if(verdict==OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO && !command.type().revocation()) {
    // Sem leitura de volta, aplicação ambígua não é repetida às cegas: fica visível para decisão humana.
    queue.mark(command.commandId(),Status.NEEDS_READBACK,OperationState.UNKNOWN,"READBACK_NOT_AVAILABLE");
    return true;
   }
   if(verdict==OperationState.FAILED) return false;
   // Leitura mostrou que não chegou (ou é revogação idempotente sem leitura): reenvio é seguro.
  }
  String gate=gate(device,adapter,command);
  if(gate!=null) {
   boolean terminal=gate.startsWith("NAO_SUPORTADO") || gate.startsWith("REVOGACAO_SEM_DOCUMENTACAO") || gate.equals("LOCAL_VALIDITY_NOT_SUPPORTED") || gate.equals("RIGHT_NOT_REPRESENTABLE");
   if(terminal) finish(command,Status.FAILED,OperationState.FAILED,gate);
   else queue.mark(command.commandId(),Status.PENDING,null,gate);
   return true;
  }
  queue.mark(command.commandId(),Status.IN_FLIGHT,null,null);
  OperationResult result;
  try { result=execute(device,adapter,command); }
  catch(MaterialUnavailable e) { queue.mark(command.commandId(),Status.PENDING,null,"MATERIAL_UNAVAILABLE"); return true; }
  catch(RuntimeException e) {
   LOG.log(System.Logger.Level.WARNING,"Adaptador falhou: {0}",e.getClass().getSimpleName());
   result=OperationResult.of(OperationState.UNKNOWN,"ADAPTER_ERROR");
  }
  if(result.nativeRef()!=null && command.type()==CommandType.UPSERT_PERSON) decisions.mapNativeUser(device.deviceId(),result.nativeRef(),command.subjectRef());
  switch(result.state()) {
   case CONFIRMED -> finish(command,Status.DONE,OperationState.CONFIRMED,result.reasonCode());
   case ACK,APPLIED_UNVERIFIED -> {
    var verdict=verify(adapter,command);
    if(verdict==OperationState.CONFIRMED) finish(command,Status.DONE,OperationState.CONFIRMED,"CONFIRMED_BY_READBACK");
    else finish(command,Status.DONE,OperationState.APPLIED_UNVERIFIED,result.reasonCode());
   }
   case PARTIAL -> finish(command,Status.FAILED,OperationState.PARTIAL,result.reasonCode());
   case UNKNOWN -> queue.mark(command.commandId(),Status.NEEDS_READBACK,OperationState.UNKNOWN,result.reasonCode());
   case FAILED -> {
    if("DEVICE_UNREACHABLE".equals(result.reasonCode())) {
     queue.mark(command.commandId(),Status.PENDING,OperationState.FAILED,"DEVICE_UNREACHABLE");
     markReachable(device,false,"DEVICE_UNREACHABLE");
     return false;
    }
    finish(command,Status.FAILED,OperationState.FAILED,result.reasonCode());
   }
   case NAO_SUPORTADO_SEM_DOCUMENTACAO -> finish(command,Status.FAILED,OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,result.reasonCode());
  }
  return true;
 }

 /** Regras antes do envio. null = pode enviar. */
 String gate(Device device,AccessProviderAdapter adapter,SyncCommand command) {
  var manifest=adapter.manifest();
  var type=command.type();
  if(type.revocation()) {
   // Restrição sempre pode ser tentada, inclusive sem perfil aprovado: ela só retira.
   return switch(type) {
    case REMOVE_FACE -> manifest.supports(Operation.REMOVE_FACE) || manifest.supports(Operation.REMOVE_PERSON)?null:"NAO_SUPORTADO_SEM_DOCUMENTACAO:REMOVE_FACE";
    case REMOVE_CREDENTIAL -> manifest.supports(BaseAdapter.removeOperation(kind(command)))?null:"NAO_SUPORTADO_SEM_DOCUMENTACAO:"+BaseAdapter.removeOperation(kind(command));
    case REVOKE_RIGHTS -> manifest.supports(Operation.REVOKE_RIGHTS)?null:"NAO_SUPORTADO_SEM_DOCUMENTACAO:REVOKE_RIGHTS";
    default -> null;
   };
  }
  if(registry.profile(device).isEmpty()) return "PROFILE_NOT_REGISTERED";
  boolean removePerson=manifest.supports(Operation.REMOVE_PERSON) && adapter.removePersonCoversCredentials();
  switch(type) {
   case UPSERT_PERSON -> { if(!manifest.supports(Operation.UPSERT_PERSON)) return "NAO_SUPORTADO_SEM_DOCUMENTACAO:UPSERT_PERSON"; }
   case ENROLL_FACE -> {
    if(!manifest.supports(Operation.ENROLL_FACE)) return "NAO_SUPORTADO_SEM_DOCUMENTACAO:ENROLL_FACE";
    if(!manifest.supports(Operation.REMOVE_FACE) && !removePerson) return "REVOGACAO_SEM_DOCUMENTACAO:REMOVE_FACE";
    var cap=registry.allows(device,manifest,CapabilityName.faceEnrollment);
    if(!cap.allowed()) return cap.reasonCode();
   }
   case SET_CREDENTIAL -> {
    var k=kind(command);
    if(!manifest.supports(BaseAdapter.setOperation(k))) return "NAO_SUPORTADO_SEM_DOCUMENTACAO:"+BaseAdapter.setOperation(k);
    if(!manifest.supports(BaseAdapter.removeOperation(k)) && !removePerson) return "REVOGACAO_SEM_DOCUMENTACAO:"+BaseAdapter.removeOperation(k);
   }
   case APPLY_RIGHTS -> {
    if(!manifest.supports(Operation.APPLY_RIGHTS)) return "NAO_SUPORTADO_SEM_DOCUMENTACAO:APPLY_RIGHTS";
    if(!manifest.supports(Operation.REVOKE_RIGHTS)) return "REVOGACAO_SEM_DOCUMENTACAO:REVOKE_RIGHTS";
    var cap=registry.allows(device,manifest,CapabilityName.perDoorRights);
    if(!cap.allowed()) return cap.reasonCode();
    var spec=rights(command);
    if(spec.validUntil()!=null && device.decisionMode()==DecisionMode.DEVICE_LOCAL_ACL && !registry.allows(device,manifest,CapabilityName.localValidity).allowed())
     return "LOCAL_VALIDITY_NOT_SUPPORTED";
    for(Right r:spec.rights()) if(device.channel(r.channelId()).isEmpty() || !device.nativeSchedules().containsKey(r.scheduleRef())) return "RIGHT_NOT_REPRESENTABLE";
   }
   default -> {}
  }
  return null;
 }

 private OperationResult execute(Device device,AccessProviderAdapter adapter,SyncCommand c) {
  var p=c.payload();
  return switch(c.type()) {
   case UPSERT_PERSON -> adapter.upsertPerson(new PersonSpec(c.subjectRef(),Json.string(p,"displayName"),instant(p,"validFrom"),instant(p,"validUntil")));
   case ENROLL_FACE -> {
    byte[] photo=material(c);
    try { yield adapter.enrollFace(new FaceSpec(c.subjectRef(),photo)); } finally { Arrays.fill(photo,(byte)0); }
   }
   case SET_CREDENTIAL -> {
    byte[] value=material(c);
    try { yield adapter.setCredential(new CredentialSpec(c.subjectRef(),kind(c),new String(value,StandardCharsets.UTF_8))); }
    finally { Arrays.fill(value,(byte)0); }
   }
   case APPLY_RIGHTS -> adapter.applyRights(rights(c));
   case REMOVE_FACE -> adapter.manifest().supports(Operation.REMOVE_FACE)?adapter.removeFace(c.subjectRef()):adapter.removePerson(c.subjectRef());
   case REMOVE_CREDENTIAL -> adapter.removeCredential(c.subjectRef(),kind(c));
   case REVOKE_RIGHTS -> adapter.revokeRights(c.subjectRef());
   case REVOKE_ALL -> revokeAll(adapter,c.subjectRef());
  };
 }

 /**
  * Revogação completa: direitos, cartão, PIN, QR e face (manual 17.3). Um método sem retirada documentada que nunca
  * pôde ser gravado é pulado; um que pode ter sido gravado e não tem retirada torna o resultado PARTIAL.
  */
 OperationResult revokeAll(AccessProviderAdapter adapter,String subjectRef) {
  var manifest=adapter.manifest();
  if(manifest.supports(Operation.REMOVE_PERSON) && adapter.removePersonCoversCredentials()) return adapter.removePerson(subjectRef);
  var states=new ArrayList<OperationResult>();
  if(manifest.supports(Operation.APPLY_RIGHTS) || manifest.supports(Operation.REVOKE_RIGHTS)) states.add(adapter.revokeRights(subjectRef));
  for(CredentialKind k:CredentialKind.values())
   if(manifest.supports(BaseAdapter.setOperation(k)) || manifest.supports(BaseAdapter.removeOperation(k))) states.add(adapter.removeCredential(subjectRef,k));
  if(manifest.supports(Operation.ENROLL_FACE) || manifest.supports(Operation.REMOVE_FACE)) states.add(adapter.removeFace(subjectRef));
  if(manifest.supports(Operation.REMOVE_PERSON)) states.add(adapter.removePerson(subjectRef));
  if(states.isEmpty()) return OperationResult.of(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,"NAO_SUPORTADO_SEM_DOCUMENTACAO:REVOKE_ALL");
  if(states.stream().anyMatch(s->"DEVICE_UNREACHABLE".equals(s.reasonCode()))) return OperationResult.of(OperationState.FAILED,"DEVICE_UNREACHABLE");
  if(states.stream().anyMatch(s->s.state()==OperationState.UNKNOWN)) return OperationResult.of(OperationState.UNKNOWN,"REVOKE_STEP_AMBIGUOUS");
  if(states.stream().anyMatch(s->s.state()==OperationState.FAILED || s.state()==OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO || s.state()==OperationState.PARTIAL))
   return OperationResult.of(OperationState.PARTIAL,"REVOKE_INCOMPLETE");
  if(states.stream().allMatch(s->s.state()==OperationState.CONFIRMED)) return OperationResult.of(OperationState.CONFIRMED,null);
  return OperationResult.of(OperationState.APPLIED_UNVERIFIED,null);
 }

 /** Lê de volta e compara com o desejado. CONFIRMED só com observação positiva de todos os campos necessários. */
 OperationState verify(AccessProviderAdapter adapter,SyncCommand c) {
  if(!adapter.manifest().supports(Operation.READ_STATE)) return OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO;
  AppliedState s;
  try { s=adapter.readState(c.subjectRef()); }
  catch(RuntimeException e) { return OperationState.UNKNOWN; }
  // Leitura impossível agora (fora do ar, resposta ambígua): não é prova de nada; não reenviar nesta passada.
  if(s.state()!=OperationState.CONFIRMED) return OperationState.FAILED;
  boolean ok=switch(c.type()) {
   case UPSERT_PERSON -> Boolean.TRUE.equals(s.personPresent());
   case REVOKE_ALL -> Boolean.FALSE.equals(s.personPresent())
    || (s.rightKeys()!=null && s.rightKeys().isEmpty() && s.credentialCounts()!=null
        && s.credentialCounts().values().stream().allMatch(n->n==0) && Boolean.FALSE.equals(s.facePresent()));
   case REVOKE_RIGHTS -> Boolean.FALSE.equals(s.personPresent()) || (s.rightKeys()!=null && s.rightKeys().isEmpty());
   case APPLY_RIGHTS -> {
    var expected=adapter.rightKeys(rights(c));
    yield expected.isPresent() && s.rightKeys()!=null && s.rightKeys().equals(expected.get());
   }
   case SET_CREDENTIAL -> s.credentialCounts()!=null && s.credentialCounts().getOrDefault(kind(c),0)>=1;
   case REMOVE_CREDENTIAL -> Boolean.FALSE.equals(s.personPresent()) || (s.credentialCounts()!=null && s.credentialCounts().containsKey(kind(c)) && s.credentialCounts().get(kind(c))==0);
   case ENROLL_FACE -> Boolean.TRUE.equals(s.facePresent());
   case REMOVE_FACE -> Boolean.FALSE.equals(s.personPresent()) || Boolean.FALSE.equals(s.facePresent());
  };
  return ok?OperationState.CONFIRMED:OperationState.UNKNOWN;
 }

 private void finish(SyncCommand c,Status status,OperationState state,String reason) {
  queue.mark(c.commandId(),status,state,reason);
  try { central.reportSyncResult(new CentralApiClient.SyncResult(c.commandId(),c.deviceId(),state,reason,clock.instant())); }
  catch(CentralUnavailable e) { LOG.log(System.Logger.Level.INFO,"Resultado guardado na fila local; plataforma indisponível."); }
 }

 // ----- eventos -----

 /** Busca uma página de eventos de cada equipamento que suporta backfill; o cursor só avança após gravação local. */
 public void pollEvents() {
  for(Device d:config.devices()) {
   var adapter=adapters.get(d.deviceId());
   if(!d.enabled() || adapter==null || !adapter.manifest().supports(Operation.FETCH_EVENTS)) continue;
   EventPage page;
   try { page=adapter.fetchEvents(outbox.cursor(d.deviceId()).orElse(null),100); }
   catch(RuntimeException e) { LOG.log(System.Logger.Level.WARNING,"Eventos adiados: {0}",e.getClass().getSimpleName()); continue; }
   if(page.state()!=OperationState.CONFIRMED && page.state()!=OperationState.ACK) continue;
   outbox.add(d.deviceId(),page.events(),page.nextCursor());
  }
 }

 /** Envia um lote; só tira da fila o que a plataforma aceitou ou reconheceu como duplicado. */
 public int forwardEvents() {
  var batch=outbox.nextBatch(100);
  if(batch.isEmpty()) return 0;
  String key=UUID.nameUUIDFromBytes(String.join(",",batch.stream().map(EdgeEvent::eventId).toList()).getBytes(StandardCharsets.UTF_8)).toString();
  try {
   int done=0;
   for(var r:central.postEvents(batch,key)) {
    switch(r.status()) {
     case ACCEPTED,DUPLICATE -> { outbox.result(r.eventId(),EventOutbox.State.SENT,null); done++; }
     case REJECTED -> outbox.result(r.eventId(),EventOutbox.State.QUARANTINED,r.reasonCode());
    }
   }
   return done;
  } catch(CentralUnavailable e) { return 0; }
 }

 // ----- plataforma -----

 /** Busca comandos e aberturas. Falha mantém tudo como está; nada é inventado localmente. */
 public void pullFromCentral() {
  try {
   for(var c:central.pullSyncCommands(200)) {
    try { submit(c); } catch(IllegalArgumentException e) { LOG.log(System.Logger.Level.WARNING,"Comando recusado: equipamento desconhecido."); }
   }
   decisions.markSynced(clock.instant());
   for(var o:central.pullOpenCommands(20)) {
    var receipt=opens.dispatch(o);
    try { central.reportOpenReceipt(receipt); } catch(CentralUnavailable e) { LOG.log(System.Logger.Level.INFO,"Recibo de abertura guardado localmente."); }
   }
  } catch(CentralUnavailable e) {
   LOG.log(System.Logger.Level.INFO,"Plataforma indisponível: {0}",e.getMessage());
  }
 }

 // ----- saúde -----

 /**
  * Sonda cada equipamento. Na primeira resposta e em cada retorno após falha, confronta a identificação real com o
  * perfil: divergência põe o equipamento em quarentena (só revogação continua sendo enviada).
  */
 public void checkHealth() {
  for(Device d:config.devices()) {
   var adapter=adapters.get(d.deviceId());
   if(!d.enabled() || adapter==null) continue;
   HealthReport report;
   try { report=adapter.health(); } catch(RuntimeException e) { report=new HealthReport(false,"ADAPTER_ERROR",null,null,null); }
   var previous=status.get(d.deviceId());
   boolean reconnect=report.reachable() && (previous==null || !previous.reachable());
   IdentityCheck identity=previous==null?IdentityCheck.UNVERIFIED:previous.identity();
   boolean quarantined=previous!=null && previous.quarantined();
   if(reconnect) {
    try { identity=registry.checkIdentity(d,adapter.identify()); } catch(RuntimeException e) { identity=IdentityCheck.UNVERIFIED; }
    quarantined=identity==IdentityCheck.MISMATCH;
   }
   status.put(d.deviceId(),new DeviceStatus(report.reachable(),report.reasonCode(),clock.instant(),previous==null?null:previous.lastHeartbeatAt(),identity,quarantined));
  }
 }

 private void markReachable(Device d,boolean reachable,String reason) {
  status.compute(d.deviceId(),(k,v)->new DeviceStatus(reachable,reason,clock.instant(),v==null?null:v.lastHeartbeatAt(),
   v==null?IdentityCheck.UNVERIFIED:v.identity(),v!=null && v.quarantined()));
 }

 /** Resumo para a plataforma e para diagnóstico local: sem nomes, fotos ou credenciais. */
 public Map<String,Object> healthSummary() {
  var out=new LinkedHashMap<String,Object>();
  out.put("gatewayId",config.gatewayId());
  out.put("observedAt",clock.instant().toString());
  out.put("queueActive",queue.activeCount());
  out.put("eventsPending",outbox.pendingCount());
  out.put("eventsQuarantined",outbox.quarantinedCount());
  var devices=new ArrayList<Object>();
  for(Device d:config.devices()) {
   var m=new LinkedHashMap<String,Object>();
   var s=status.get(d.deviceId());
   m.put("deviceId",d.deviceId());
   m.put("enabled",d.enabled());
   m.put("connectionMode",d.connectionMode().name());
   m.put("reachable",s==null?null:s.reachable());
   m.put("reasonCode",s==null?"NEVER_CHECKED":s.reasonCode());
   m.put("identity",s==null?IdentityCheck.UNVERIFIED.name():s.identity().name());
   m.put("quarantined",s!=null && s.quarantined());
   m.put("lastHeartbeatAt",s==null || s.lastHeartbeatAt()==null?null:s.lastHeartbeatAt().toString());
   var pending=queue.pending(d.deviceId());
   m.put("pending",pending.size());
   m.put("pendingRevocations",pending.stream().filter(e->e.command().type().revocation()).count());
   m.put("oldestPendingRevocation",queue.oldestPendingRevocation(d.deviceId()).map(Instant::toString).orElse(null));
   devices.add(m);
  }
  out.put("devices",devices);
  return out;
 }

 // ----- entrada dos equipamentos (online/push/nuvem) -----

 public InboundEndpoint.Response handleInbound(String deviceId,InboundEndpoint.Request request) {
  var device=config.device(deviceId).orElse(null);
  var adapter=adapters.get(deviceId);
  if(device==null || !device.enabled() || adapter==null || adapter.inbound().isEmpty()) return InboundEndpoint.Response.notFound();
  return adapter.inbound().get().handle(request,new InboundEndpoint.Context() {
   @Override public Instant now() { return clock.instant(); }
   @Override public InboundEndpoint.LocalDecision decide(String nativeUserRef,String nativeChannel) {
    long started=System.nanoTime();
    InboundEndpoint.LocalDecision decision;
    if(device.decisionMode()!=DecisionMode.EDGE_ONLINE || !registry.allows(device,adapter.manifest(),CapabilityName.onlineDecisionBeforeRelay).allowed())
     decision=new InboundEndpoint.LocalDecision(false,List.of("CAPABILITY_MISSING"),null,null);
    else decision=decisions.decide(deviceId,nativeUserRef,nativeChannel);
    telemetry(device,decision,(System.nanoTime()-started)/1_000_000);
    return decision;
   }
   @Override public void events(List<EdgeEvent> events) { if(!events.isEmpty()) outbox.add(deviceId,events,null); }
   @Override public void heartbeat() {
    status.compute(deviceId,(k,v)->new DeviceStatus(true,null,clock.instant(),clock.instant(),v==null?IdentityCheck.UNVERIFIED:v.identity(),v!=null && v.quarantined()));
   }
  });
 }

 private void telemetry(Device device,InboundEndpoint.LocalDecision decision,long millis) {
  Instant now=clock.instant();
  var channel=decision.channelId()==null?null:device.channel(decision.channelId()).orElse(null);
  if(channel==null) { LOG.log(System.Logger.Level.INFO,"Decisão sem canal resolvido: telemetria só local."); return; }
  String id=UUID.randomUUID().toString();
  var d=new CentralApiClient.EdgeDecision(id,id,config.connectorId(),device.deviceProvisioningId(),channel.channelId(),
   channel.pointId(),channel.bindingRevision(),device.installationRevision(),decision.subjectRef(),
   decision.allow()?CentralApiClient.DecisionResult.ALLOW:CentralApiClient.DecisionResult.DENY,decision.reasonCodes(),now,now.plusSeconds(10),
   decision.allow(),decision.reasonCodes().contains("CLOCK_UNTRUSTED")?"UNTRUSTED":"TRUSTED",millis);
  try { central.postDecision(d,id); }
  catch(CentralUnavailable|RuntimeException e) { LOG.log(System.Logger.Level.INFO,"Telemetria de decisão não entregue agora."); }
 }

 // ----- utilitários -----

 static final class MaterialUnavailable extends RuntimeException { MaterialUnavailable() { super(null,null,false,false); } }
 private byte[] material(SyncCommand c) {
  String ref=Json.string(c.payload(),"materialRef");
  if(ref==null) throw new IllegalArgumentException("materialRef obrigatório.");
  try { return central.fetchMaterial(ref,c.deviceId()); } catch(CentralUnavailable e) { throw new MaterialUnavailable(); }
 }
 private static CredentialKind kind(SyncCommand c) { return CredentialKind.valueOf(Json.string(c.payload(),"kind")); }
 private static Instant instant(Map<String,Object> p,String key) { String v=Json.string(p,key); return v==null?null:Instant.parse(v); }
 static RightsSpec rights(SyncCommand c) {
  var list=new ArrayList<Right>();
  var raw=Json.list(c.payload().get("rights"));
  if(raw!=null) for(Object o:raw) { var m=Json.map(o); list.add(new Right(Json.string(m,"channelId"),Json.string(m,"scheduleRef"))); }
  return new RightsSpec(c.subjectRef(),list,instant(c.payload(),"validFrom"),instant(c.payload(),"validUntil"));
 }
}
