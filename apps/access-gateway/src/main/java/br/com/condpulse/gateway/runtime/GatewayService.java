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
import br.com.condpulse.gateway.central.DecisionOutbox;
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

 /**
  * Situação de cada equipamento vista pelo gateway.
  * identityPending: a identidade ainda não foi conferida desde a última (re)conexão observada pela sonda de saúde.
  * Batimento recebido do equipamento não torna o equipamento "alcançável" nem dispensa essa conferência.
  */
 public record DeviceStatus(boolean reachable,String reasonCode,Instant lastCheckedAt,Instant lastHeartbeatAt,IdentityCheck identity,boolean quarantined,
     boolean identityPending) {}

 private final GatewayConfig config;
 private final CapabilityRegistry registry;
 private final Map<String,AccessProviderAdapter> adapters;
 private final LocalCommandQueue queue;
 private final OpenDispatcher opens;
 private final EventOutbox outbox;
 private final LocalDecisionEngine decisions;
 private final CentralApiClient central;
 private final DecisionOutbox decisionOutbox;
 private final Clock clock;
 private final Map<String,DeviceStatus> status=new ConcurrentHashMap<>();
 private final java.util.concurrent.atomic.AtomicLong rejectedRevocations=new java.util.concurrent.atomic.AtomicLong();
 private final Map<String,String> eventGaps=new ConcurrentHashMap<>();

 public GatewayService(GatewayConfig config,CapabilityRegistry registry,Map<String,AccessProviderAdapter> adapters,LocalCommandQueue queue,
     OpenDispatcher opens,EventOutbox outbox,LocalDecisionEngine decisions,DecisionOutbox decisionOutbox,CentralApiClient central,Clock clock) {
  this.config=config; this.registry=registry; this.adapters=Map.copyOf(adapters); this.queue=queue; this.opens=opens;
  this.outbox=outbox; this.decisions=decisions; this.decisionOutbox=decisionOutbox; this.central=central; this.clock=clock;
  opens.quarantineCheck(this::actuationBlock);
  reconcileDecisions();
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
  if(command.type().revocation()) {
   // Revogação mais antiga que a concessão aceita é mensagem velha: não vale nem na fila nem na decisão.
   if(result.outcome()==LocalCommandQueue.EnqueueOutcome.REJECTED_STALE) return result;
   // Revogação vale na decisão local mesmo se a fila recusar por estar cheia (negativa durável, fail-closed).
   applyToDecisions(command,false);
   if(result.outcome()==LocalCommandQueue.EnqueueOutcome.REJECTED_FULL) {
    rejectedRevocations.incrementAndGet();
    LOG.log(System.Logger.Level.ERROR,"ALARME: fila cheia recusou revogação; negativa local aplicada, equipamento pendente.");
   }
   return result;
  }
  if(result.outcome()!=LocalCommandQueue.EnqueueOutcome.ACCEPTED) return result;
  applyToDecisions(command,false);
  return result;
 }

 /**
  * Recuperação: refaz a cópia de decisão local a partir dos comandos duráveis da fila, na ordem de chegada, antes de
  * atender qualquer equipamento. Uma queda entre gravar o comando e atualizar a cópia não deixa revogação sem
  * efeito; versões menores ou iguais às já registradas são ignoradas pelo motor.
  */
 void reconcileDecisions() {
  for(var e:queue.all()) {
   var c=e.command();
   if(config.device(c.deviceId()).isEmpty()) continue;
   if(e.status()==Status.REJECTED_STALE) continue;
   applyToDecisions(c,e.status()==Status.SUPERSEDED);
  }
 }

 private void applyToDecisions(SyncCommand command,boolean superseded) {
  switch(command.type()) {
   case REVOKE_ALL,REVOKE_RIGHTS -> decisions.revoke(command.deviceId(),command.subjectRef(),command.desiredVersion());
   case APPLY_RIGHTS -> {
    if(superseded) return;
    var spec=rights(command);
    decisions.grant(command.deviceId(),command.subjectRef(),command.desiredVersion(),
     spec.rights().stream().map(r->new String[]{r.channelId(),r.scheduleRef()}).toList(),spec.validFrom(),spec.validUntil());
   }
   case UPSERT_PERSON -> {
    String nativeRef=Json.string(command.payload(),"nativeUserRef");
    if(nativeRef!=null) decisions.mapNativeUser(command.deviceId(),nativeRef,command.subjectRef());
   }
   default -> {}
  }
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
  if(adapter==null) {
   queue.mark(command.commandId(),entry.status()==Status.NEEDS_READBACK?Status.NEEDS_READBACK:Status.PENDING,entry.lastState(),"ADAPTER_UNAVAILABLE");
   return false;
  }
  if(!command.type().revocation()) {
   // Gravação só depois de a sonda conferir a identidade desde a (re)conexão; revogação nunca espera.
   String identity=actuationBlock(device.deviceId());
   if(identity!=null) {
    // Adiar não apaga a incerteza: comando em NEEDS_READBACK continua exigindo leitura antes de reenviar.
    queue.mark(command.commandId(),entry.status()==Status.NEEDS_READBACK?Status.NEEDS_READBACK:Status.PENDING,entry.lastState(),identity);
    return false;
   }
  }
  if(entry.status()==Status.NEEDS_READBACK) {
   switch(verify(adapter,command)) {
    case MATCH -> { finish(command,Status.DONE,OperationState.CONFIRMED,"CONFIRMED_BY_READBACK"); return true; }
    case ABSENT -> {} // leitura bem-sucedida mostrou que não chegou: reenvio autorizado
    case UNAVAILABLE -> {
     // Leitura indisponível agora não prova nada: mantém a pendência e não reenvia.
     queue.mark(command.commandId(),Status.NEEDS_READBACK,OperationState.UNKNOWN,"READBACK_UNAVAILABLE");
     return false;
    }
    case UNPROVEN,NOT_SUPPORTED -> {
     // Retirada é idempotente e restringe: pode ser reenviada. Aplicação ambígua sem prova fica para decisão humana.
     if(!command.type().revocation()) {
      // Fora da seleção da fila (não trava os demais), visível e publicado como UNKNOWN para decisão humana.
      finish(command,Status.HELD,OperationState.UNKNOWN,"READBACK_CANNOT_PROVE");
      return true;
     }
    }
   }
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
    if(verdict==Verdict.MATCH) finish(command,Status.DONE,OperationState.CONFIRMED,"CONFIRMED_BY_READBACK");
    else finish(command,Status.DONE,OperationState.APPLIED_UNVERIFIED,verdict==Verdict.ABSENT?"READBACK_MISMATCH":result.reasonCode());
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
   case UPSERT_PERSON -> {
    if(!manifest.supports(Operation.UPSERT_PERSON)) return "NAO_SUPORTADO_SEM_DOCUMENTACAO:UPSERT_PERSON";
    // Qualquer limite temporal (início ou fim) só é aceito onde a validade local é suportada.
    var p=person(command);
    if((p.validFrom()!=null || p.validUntil()!=null) && device.decisionMode()==DecisionMode.DEVICE_LOCAL_ACL
       && !registry.allows(device,manifest,CapabilityName.localValidity).allowed())
     return "LOCAL_VALIDITY_NOT_SUPPORTED";
   }
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
    if((spec.validFrom()!=null || spec.validUntil()!=null) && device.decisionMode()==DecisionMode.DEVICE_LOCAL_ACL
       && !registry.allows(device,manifest,CapabilityName.localValidity).allowed())
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
   case UPSERT_PERSON -> adapter.upsertPerson(person(c));
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
  // Atalho só com cascata declarada de direitos E credenciais; senão cada método é retirado e agregado.
  if(manifest.supports(Operation.REMOVE_PERSON) && adapter.removePersonCoversCredentials() && adapter.removePersonCoversRights())
   return adapter.removePerson(subjectRef);
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

 /**
  * Resultado da leitura de volta. MATCH: leitura bem-sucedida comprova o desejado. ABSENT: leitura bem-sucedida
  * mostra que o desejado não está lá (autoriza reenvio). UNAVAILABLE: leitura falhou agora. UNPROVEN: leitura não
  * tem dado suficiente (ex.: só conta credenciais ou vê presença de face). NOT_SUPPORTED: sem leitura documentada.
  */
 enum Verdict { MATCH, ABSENT, UNAVAILABLE, UNPROVEN, NOT_SUPPORTED }

 Verdict verify(AccessProviderAdapter adapter,SyncCommand c) {
  if(c.type()==CommandType.SET_CREDENTIAL) return verifyCredential(adapter,c);
  if(!adapter.manifest().supports(Operation.READ_STATE)) return Verdict.NOT_SUPPORTED;
  AppliedState s;
  try { s=adapter.readState(c.subjectRef()); }
  catch(RuntimeException e) { return Verdict.UNAVAILABLE; }
  if(s==null || s.state()==OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO) return Verdict.NOT_SUPPORTED;
  if(s.state()!=OperationState.CONFIRMED) return Verdict.UNAVAILABLE;
  boolean absentPerson=Boolean.FALSE.equals(s.personPresent());
  // Pessoa ausente só prova retirada do que a fonte declara removido junto (cascata); o resto precisa ser lido.
  boolean absentCoversRights=absentPerson && adapter.removePersonCoversRights();
  boolean absentCoversCredentials=absentPerson && adapter.removePersonCoversCredentials();
  return switch(c.type()) {
   case UPSERT_PERSON -> {
    if(s.personPresent()==null) yield Verdict.UNPROVEN;
    if(!s.personPresent()) yield Verdict.ABSENT;
    // Presença não basta: os campos aplicados (nome, validade) precisam bater com o desejado.
    var expected=adapter.personAttributes(person(c));
    if(expected.isEmpty() || s.personAttributes()==null) yield Verdict.UNPROVEN;
    yield s.personAttributes().entrySet().containsAll(expected.get().entrySet())?Verdict.MATCH:Verdict.ABSENT;
   }
   case REVOKE_ALL -> {
    if(absentCoversRights && absentCoversCredentials) yield Verdict.MATCH;
    boolean anyLeft=(s.rightKeys()!=null && !s.rightKeys().isEmpty()) || Boolean.TRUE.equals(s.facePresent())
     || (s.credentialCounts()!=null && s.credentialCounts().values().stream().anyMatch(n->n>0));
    if(anyLeft) yield Verdict.ABSENT;
    boolean rightsGone=absentCoversRights || (s.rightKeys()!=null && s.rightKeys().isEmpty());
    boolean credentialsGone=absentCoversCredentials || (s.credentialCounts()!=null && s.credentialCounts().size()==CredentialKind.values().length
     && Boolean.FALSE.equals(s.facePresent()));
    yield rightsGone && credentialsGone?Verdict.MATCH:Verdict.UNPROVEN;
   }
   case REVOKE_RIGHTS -> absentCoversRights?Verdict.MATCH:s.rightKeys()==null?Verdict.UNPROVEN:s.rightKeys().isEmpty()?Verdict.MATCH:Verdict.ABSENT;
   case APPLY_RIGHTS -> {
    var spec=rights(c);
    // Prazo sem prova de prazo aplicado: não confirma (validade antiga poderia continuar liberando).
    if((spec.validFrom()!=null || spec.validUntil()!=null) && !adapter.rightKeysIncludeValidity()) yield Verdict.UNPROVEN;
    var expected=adapter.rightKeys(spec);
    if(expected.isEmpty() || s.rightKeys()==null) yield Verdict.UNPROVEN;
    yield s.rightKeys().equals(expected.get())?Verdict.MATCH:Verdict.ABSENT;
   }
   case REMOVE_CREDENTIAL -> {
    if(absentCoversCredentials) yield Verdict.MATCH;
    Integer n=s.credentialCounts()==null?null:s.credentialCounts().get(kind(c));
    yield n==null?Verdict.UNPROVEN:n==0?Verdict.MATCH:Verdict.ABSENT;
   }
   // Presença de face não prova que a foto nova foi aplicada (substituição); só a ausência é observável.
   case ENROLL_FACE -> Boolean.FALSE.equals(s.facePresent()) || absentPerson?Verdict.ABSENT:Verdict.UNPROVEN;
   case REMOVE_FACE -> absentCoversCredentials || Boolean.FALSE.equals(s.facePresent())?Verdict.MATCH:Boolean.TRUE.equals(s.facePresent())?Verdict.ABSENT:Verdict.UNPROVEN;
   case SET_CREDENTIAL -> Verdict.UNPROVEN;
  };
 }

 /**
  * Cartão/PIN/QR só são confirmados comparando o valor aplicado: o material é buscado de novo na plataforma (nunca
  * guardado) e o adaptador responde se aquele valor está vinculado à pessoa. Contagem de credenciais não prova
  * substituição.
  */
 private Verdict verifyCredential(AccessProviderAdapter adapter,SyncCommand c) {
  byte[] value;
  try { value=material(c); } catch(MaterialUnavailable e) { return Verdict.UNAVAILABLE; }
  try {
   var present=adapter.credentialPresent(new CredentialSpec(c.subjectRef(),kind(c),new String(value,StandardCharsets.UTF_8)));
   if(present.isEmpty()) return Verdict.UNPROVEN;
   return present.get()?Verdict.MATCH:Verdict.ABSENT;
  } catch(RuntimeException e) {
   return Verdict.UNAVAILABLE;
  } finally { Arrays.fill(value,(byte)0); }
 }

 /** Marca o resultado terminal e tenta publicar; se a plataforma falhar, a publicação fica pendente na fila local. */
 private void finish(SyncCommand c,Status status,OperationState state,String reason) {
  queue.mark(c.commandId(),status,state,reason);
  publish(c.commandId());
 }

 private boolean publish(String commandId) {
  var e=queue.get(commandId).orElse(null);
  if(e==null || e.reported()) return true;
  OperationState state=e.lastState()==null?OperationState.FAILED:e.lastState();
  try {
   central.reportSyncResult(new CentralApiClient.SyncResult(commandId,e.command().deviceId(),state,e.lastReason(),clock.instant()));
   queue.markReported(commandId);
   return true;
  } catch(CentralUnavailable ex) {
   LOG.log(System.Logger.Level.INFO,"Resultado guardado na fila local; publicação pendente.");
   return false;
  }
 }

 /** Reenvia só os relatórios de resultados terminais não publicados; nenhuma operação física é repetida. */
 public int publishPendingResults() {
  int done=0;
  for(var e:queue.unreported()) { if(!publish(e.command().commandId())) break; done++; }
  return done;
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
   if(page.reasonCode()!=null) {
    // Lacuna/perda sinalizada pelo adaptador (ex.: STREAM_GAP, BUFFER_OVERFLOW): visível na saúde.
    eventGaps.put(d.deviceId(),page.reasonCode()+"@"+clock.instant());
    LOG.log(System.Logger.Level.WARNING,"Eventos do equipamento {0}: {1}",d.deviceId(),page.reasonCode());
   }
   if(page.state()!=OperationState.CONFIRMED && page.state()!=OperationState.ACK) continue;
   // Fila cheia ou falha de gravação: o cursor não avança e a mesma página volta na próxima passada.
   try { outbox.add(d.deviceId(),page.events(),page.nextCursor()); }
   catch(RuntimeException e) { LOG.log(System.Logger.Level.WARNING,"Eventos não gravados localmente: {0}",e.getMessage()); }
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

 /** Envia a telemetria de decisões acumulada (ciclo de fundo). */
 public int forwardDecisions() { return decisionOutbox.flush(central); }

 // ----- plataforma -----

 /** Busca comandos e aberturas. Falha mantém tudo como está; nada é inventado localmente. */
 public void pullFromCentral() {
  try {
   boolean complete=true;
   for(var c:central.pullSyncCommands(200)) {
    try {
     if(submit(c).outcome()==LocalCommandQueue.EnqueueOutcome.REJECTED_FULL) complete=false;
    } catch(IllegalArgumentException e) { LOG.log(System.Logger.Level.WARNING,"Comando recusado: equipamento desconhecido."); }
   }
   // Política só é "atual" quando todos os comandos recebidos foram aceitos; senão envelhece até STALE_POLICY.
   if(complete) decisions.markSynced(clock.instant());
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
   IdentityCheck identity=previous==null?IdentityCheck.UNVERIFIED:previous.identity();
   boolean quarantined=previous!=null && previous.quarantined();
   boolean pending=previous==null || previous.identityPending() || !previous.reachable();
   if(!report.reachable()) pending=true; // perda de conexão: confere de novo na volta
   else if(pending) {
    try { identity=registry.checkIdentity(d,adapter.identify()); } catch(RuntimeException e) { identity=IdentityCheck.UNVERIFIED; }
    quarantined=identity==IdentityCheck.MISMATCH;
    pending=false;
   }
   status.put(d.deviceId(),new DeviceStatus(report.reachable(),report.reasonCode(),clock.instant(),previous==null?null:previous.lastHeartbeatAt(),
    identity,quarantined,pending));
  }
 }

 /**
  * Motivo para recusar atuação (abertura) pela identidade do equipamento, ou null. Fail-closed: sem conferência
  * desde a conexão, recusa; em PRODUCAO só identidade confirmada (OK) libera.
  */
 String actuationBlock(String deviceId) {
  var st=status.get(deviceId);
  if(st!=null && st.quarantined()) return "DEVICE_IDENTITY_MISMATCH";
  if(st==null || st.identityPending()) return "DEVICE_IDENTITY_NOT_VERIFIED";
  var device=config.device(deviceId).orElse(null);
  if(device!=null && device.phase()==GatewayConfig.Phase.PRODUCAO && st.identity()!=IdentityCheck.OK) return "DEVICE_IDENTITY_NOT_VERIFIED";
  return null;
 }

 private void markReachable(Device d,boolean reachable,String reason) {
  status.compute(d.deviceId(),(k,v)->new DeviceStatus(reachable,reason,clock.instant(),v==null?null:v.lastHeartbeatAt(),
   v==null?IdentityCheck.UNVERIFIED:v.identity(),v!=null && v.quarantined(),!reachable || v==null || v.identityPending()));
 }

 /** Resumo para a plataforma e para diagnóstico local: sem nomes, fotos ou credenciais. */
 public Map<String,Object> healthSummary() {
  var out=new LinkedHashMap<String,Object>();
  out.put("gatewayId",config.gatewayId());
  out.put("observedAt",clock.instant().toString());
  out.put("queueActive",queue.activeCount());
  out.put("eventsPending",outbox.pendingCount());
  out.put("eventsQuarantined",outbox.quarantinedCount());
  out.put("resultsUnpublished",queue.unreported().size());
  out.put("revocationsRejectedQueueFull",rejectedRevocations.get());
  out.put("decisionsPending",decisionOutbox.pendingCount());
  out.put("decisionsDropped",decisionOutbox.dropped());
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
   m.put("identityPending",s==null || s.identityPending());
   m.put("lastEventGap",eventGaps.get(d.deviceId()));
   m.put("pendingHuman",queue.pending(d.deviceId()).stream().filter(e->e.status()==Status.HELD).count());
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
    try {
     var st=status.get(deviceId);
     boolean production=device.phase()==GatewayConfig.Phase.PRODUCAO;
     if((st!=null && st.quarantined()) || (production && (st==null || st.identity()!=IdentityCheck.OK)))
      decision=new InboundEndpoint.LocalDecision(false,List.of("NOT_COMMISSIONED"),null,null);
     else if(device.decisionMode()!=DecisionMode.EDGE_ONLINE || !registry.allows(device,adapter.manifest(),CapabilityName.onlineDecisionBeforeRelay).allowed())
      decision=new InboundEndpoint.LocalDecision(false,List.of("CAPABILITY_MISSING"),null,null);
     else decision=decisions.decide(deviceId,nativeUserRef,nativeChannel);
    } catch(RuntimeException e) {
     decision=new InboundEndpoint.LocalDecision(false,List.of("DEPENDENCY_UNAVAILABLE"),null,null);
    }
    try { telemetry(device,decision,(System.nanoTime()-started)/1_000_000); } catch(RuntimeException ignored) {}
    return decision;
   }
   /**
    * Grava na fila durável (com reserva própria para eventos recebidos) antes de o adaptador responder. Falha de
    * gravação é propagada: o adaptador não confirma o recebimento e responde negativa segura.
    */
   @Override public void events(List<EdgeEvent> events) {
    if(events.isEmpty()) return;
    outbox.addInbound(deviceId,events);
   }
   @Override public void heartbeat() {
    // Só registra o batimento: alcance e identidade continuam sendo da sonda de saúde.
    status.compute(deviceId,(k,v)->v==null?new DeviceStatus(false,"NEVER_CHECKED",null,clock.instant(),IdentityCheck.UNVERIFIED,false,true)
     :new DeviceStatus(v.reachable(),v.reasonCode(),v.lastCheckedAt(),clock.instant(),v.identity(),v.quarantined(),v.identityPending()));
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
  // Só enfileira em memória: rede e disco ficam fora do caminho da resposta ao equipamento.
  decisionOutbox.offer(d);
 }

 // ----- utilitários -----

 static final class MaterialUnavailable extends RuntimeException { MaterialUnavailable() { super(null,null,false,false); } }
 private byte[] material(SyncCommand c) {
  String ref=Json.string(c.payload(),"materialRef");
  if(ref==null) throw new IllegalArgumentException("materialRef obrigatório.");
  try { return central.fetchMaterial(ref,c.deviceId()); } catch(CentralUnavailable e) { throw new MaterialUnavailable(); }
 }
 private static PersonSpec person(SyncCommand c) {
  var p=c.payload();
  return new PersonSpec(c.subjectRef(),Json.string(p,"displayName"),instant(p,"validFrom"),instant(p,"validUntil"));
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
