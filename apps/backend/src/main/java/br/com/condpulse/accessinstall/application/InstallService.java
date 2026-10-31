package br.com.condpulse.accessinstall.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessgovernance.application.TechnicalScope;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessinstall.infrastructure.InstallStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Instalação física (AC-004 parte 2, V042). MASTER de implantação (AAL2): equipamentos e canais (installation.manage), vínculo
 * versionado do ponto (installation.bind_channel), aceite, assinatura técnica e aprovação (installation.commission). Gestão
 * (area.manage, AAL2): resumo do aceite e assinatura das regras; nunca recebe equipamento, canal, relé ou vínculo. O banco
 * repete todas as fronteiras e faz a ativação atômica (vínculos ACTIVE, anterior SUPERSEDED, ponto COMMISSIONED).
 */
public class InstallService {
 static final Set<String> DIRECTIONS=Set.of("ENTRY","EXIT","BOTH","UNKNOWN");
 static final Set<String> DEVICE_STATES=Set.of("ACTIVE","RETIRED");
 static final Set<String> DEVICE_ROLES=Set.of("FACE_TERMINAL","CONTROLLER","READER","EXPANSION_MODULE");
 static final Set<String> CONNECTION_MODES=Set.of("LOCAL_GATEWAY","CLOUD_SERVICE");
 static final Set<String> CHANNEL_KINDS=Set.of("ACCESS_ACTUATOR","DOOR_CONTACT","EXIT_REQUEST","PASSAGE_SENSOR","ALARM_OUTPUT","OTHER");
 static final Set<String> CHANNEL_STATES=Set.of("DISCOVERED","VERIFIED","DISABLED","RETIRED");
 static final Set<String> READER_SENSE=Set.of("FROM_TO","TO_FROM");
 static final Set<String> TRANSITIONS=Set.of("TESTING","APPROVED","REJECTED","SUSPENDED","SUPERSEDED");
 static final Set<String> NEEDS_REASON=Set.of("REJECTED","SUSPENDED","SUPERSEDED");
 static final String EXTERNAL="[A-Za-z0-9._:-]{1,120}",CHANNEL="[A-Za-z0-9._:-]{1,64}",NAMESPACE="[a-z][a-z0-9_.-]{0,63}",
  REF="[A-Za-z0-9._:/-]{3,120}",HASH="[0-9a-f]{64}",SUITE="[A-Za-z0-9._-]{1,64}",REVISION="\\S(.{0,62}\\S)?";
 private final TenantTransactionExecutor tenants;
 private final TechnicalTransactionExecutor technical;
 private final InstallStore store;
 private final AuditRecorder audit;
 public InstallService(TenantTransactionExecutor tenants,TechnicalTransactionExecutor technical,InstallStore store,AuditRecorder audit) {
  this.tenants=tenants; this.technical=technical; this.store=store; this.audit=audit;
 }

 // ---- equipamentos
 public InstallModels.DevicePage devices(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_READ);
   return new InstallModels.DevicePage(store.devices(tenant));
  }));
 }
 public InstallModels.Device createDevice(UUID actor,UUID tenant,AssuranceLevel assurance,InstallModels.CreateDevice request) {
  var d=validate(request);
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_MANAGE);
   var existing=store.device(tenant,d.deviceId());
   if(existing!=null) {
    if(InstallStore.sameDevice(existing,d)) return existing;
    throw new BusinessProblem("INSTALLATION_DEVICE_ID_CONFLICT",409,"Este identificador já foi usado para outro equipamento.");
   }
   store.insertDevice(tenant,d,actor);
   audit.record(tenant,actor,"DEVICE_REGISTERED","ACCESS_DEVICE",d.deviceId(),null);
   return store.device(tenant,d.deviceId());
  }));
 }
 public InstallModels.Device updateDevice(UUID actor,UUID tenant,AssuranceLevel assurance,UUID deviceId,InstallModels.UpdateDevice request) {
  if(deviceId==null) throw notFound("INSTALLATION_DEVICE_NOT_FOUND");
  var d=validate(request);
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_MANAGE);
   if(store.device(tenant,deviceId)==null) throw notFound("INSTALLATION_DEVICE_NOT_FOUND");
   if(!store.updateDevice(tenant,deviceId,d)) throw stale();
   audit.record(tenant,actor,"DEVICE_UPDATED","ACCESS_DEVICE",deviceId,null);
   return store.device(tenant,deviceId);
  }));
 }

 // ---- canais
 public InstallModels.ChannelPage channels(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_READ);
   return new InstallModels.ChannelPage(store.channels(tenant));
  }));
 }
 public InstallModels.Channel createChannel(UUID actor,UUID tenant,AssuranceLevel assurance,InstallModels.CreateChannel request) {
  var c=validate(request);
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_MANAGE);
   var existing=store.channel(tenant,c.channelId());
   if(existing!=null) {
    if(InstallStore.sameChannel(existing,c)) return existing;
    throw new BusinessProblem("INSTALLATION_CHANNEL_ID_CONFLICT",409,"Este identificador já foi usado para outro canal.");
   }
   store.insertChannel(tenant,c,actor);
   audit.record(tenant,actor,"CHANNEL_CREATED","DEVICE_CHANNEL",c.channelId(),null);
   return store.channel(tenant,c.channelId());
  }));
 }
 public InstallModels.Channel updateChannel(UUID actor,UUID tenant,AssuranceLevel assurance,UUID channelId,InstallModels.UpdateChannel request) {
  if(channelId==null) throw notFound("INSTALLATION_CHANNEL_NOT_FOUND");
  if(request==null || request.channelKind()==null || !CHANNEL_KINDS.contains(request.channelKind()) || request.status()==null
    || !CHANNEL_STATES.contains(request.status())) throw invalid("INSTALLATION_CHANNEL_INVALID","Informe função e estado válidos do canal.");
  version(request.expectedVersion());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_MANAGE);
   if(store.channel(tenant,channelId)==null) throw notFound("INSTALLATION_CHANNEL_NOT_FOUND");
   if(!store.updateChannel(tenant,channelId,request)) throw stale();
   audit.record(tenant,actor,"CHANNEL_UPDATED","DEVICE_CHANNEL",channelId,null);
   return store.channel(tenant,channelId);
  }));
 }

 // ---- vínculos
 public InstallModels.BindingPage bindings(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_READ);
   return new InstallModels.BindingPage(store.bindings(tenant));
  }));
 }
 public InstallModels.Binding createBinding(UUID actor,UUID tenant,AssuranceLevel assurance,InstallModels.CreateBinding request) {
  var b=validate(request);
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_BIND_CHANNEL);
   var existing=store.binding(tenant,b.bindingId());
   if(existing!=null) {
    if(InstallStore.sameBinding(existing,b)) return existing;
    throw new BusinessProblem("INSTALLATION_BINDING_ID_CONFLICT",409,"Este identificador já foi usado para outro vínculo.");
   }
   store.insertBinding(tenant,b,actor);
   audit.record(tenant,actor,"BINDING_CREATED","POINT_BINDING",b.bindingId(),null);
   return store.binding(tenant,b.bindingId());
  }));
 }
 public InstallModels.Binding submitBinding(UUID actor,UUID tenant,AssuranceLevel assurance,UUID bindingId,InstallModels.SubmitBinding request) {
  if(bindingId==null) throw notFound("INSTALLATION_BINDING_NOT_FOUND");
  if(request==null || request.installationAcceptanceId()==null) throw invalid("INSTALLATION_ACCEPTANCE_REQUIRED","Informe o aceite de instalação.");
  long expected=version(request.expectedVersion());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_BIND_CHANNEL);
   if(store.binding(tenant,bindingId)==null) throw notFound("INSTALLATION_BINDING_NOT_FOUND");
   if(store.acceptance(tenant,request.installationAcceptanceId())==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
   if(!store.submitBinding(tenant,bindingId,request.installationAcceptanceId(),expected)) throw stale();
   audit.record(tenant,actor,"BINDING_SUBMITTED","POINT_BINDING",bindingId,null);
   return store.binding(tenant,bindingId);
  }));
 }
 public InstallModels.Binding discardBinding(UUID actor,UUID tenant,AssuranceLevel assurance,UUID bindingId,InstallModels.VersionOnly request) {
  if(bindingId==null) throw notFound("INSTALLATION_BINDING_NOT_FOUND");
  long expected=version(request==null?null:request.expectedVersion());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_BIND_CHANNEL);
   if(store.binding(tenant,bindingId)==null) throw notFound("INSTALLATION_BINDING_NOT_FOUND");
   if(!store.discardBinding(tenant,bindingId,expected)) throw stale();
   audit.record(tenant,actor,"BINDING_DISCARDED","POINT_BINDING",bindingId,null);
   return store.binding(tenant,bindingId);
  }));
 }

 // ---- aceite técnico
 public InstallModels.AcceptancePage acceptances(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_READ);
   return new InstallModels.AcceptancePage(store.acceptances(tenant));
  }));
 }
 public InstallModels.Acceptance createAcceptance(UUID actor,UUID tenant,AssuranceLevel assurance,InstallModels.CreateAcceptance request) {
  var a=validate(request);
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_COMMISSION);
   var existing=store.acceptance(tenant,a.acceptanceId());
   if(existing!=null) {
    if(InstallStore.sameAcceptance(existing,a)) return existing;
    throw new BusinessProblem("INSTALLATION_ACCEPTANCE_ID_CONFLICT",409,"Este identificador já foi usado para outro aceite.");
   }
   store.insertAcceptance(tenant,a,actor);
   audit.record(tenant,actor,"INSTALLATION_ACCEPTANCE_CREATED","INSTALLATION_ACCEPTANCE",a.acceptanceId(),null);
   return store.acceptance(tenant,a.acceptanceId());
  }));
 }
 public InstallModels.Acceptance recordResults(UUID actor,UUID tenant,AssuranceLevel assurance,UUID acceptanceId,InstallModels.AcceptanceResults request) {
  if(acceptanceId==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
  if(request==null || !counts(request.criticalTestsPending(),request.criticalTestsFailed()) || request.testReportRef()==null
    || !request.testReportRef().matches(REF) || (request.revocationCheckpointRef()!=null && !request.revocationCheckpointRef().matches(REF)))
   throw invalid("INSTALLATION_RESULTS_INVALID","Informe testes críticos pendentes e reprovados (≥ 0) e o relatório.");
  version(request.expectedVersion());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_COMMISSION);
   if(store.acceptance(tenant,acceptanceId)==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
   if(!store.updateResults(tenant,acceptanceId,request)) throw stale();
   audit.record(tenant,actor,"INSTALLATION_RESULTS_RECORDED","INSTALLATION_ACCEPTANCE",acceptanceId,null);
   return store.acceptance(tenant,acceptanceId);
  }));
 }
 public InstallModels.Acceptance signTechnical(UUID actor,UUID tenant,AssuranceLevel assurance,UUID acceptanceId,InstallModels.VersionOnly request) {
  if(acceptanceId==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
  long expected=version(request==null?null:request.expectedVersion());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_COMMISSION);
   if(store.acceptance(tenant,acceptanceId)==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
   UUID grant=store.commissionGrant(tenant,actor);
   if(grant==null) throw new BusinessProblem("INSTALLATION_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if(!store.signTechnical(tenant,acceptanceId,grant,actor,expected)) throw stale();
   audit.record(tenant,actor,"INSTALLATION_TECHNICAL_SIGNED","INSTALLATION_ACCEPTANCE",acceptanceId,null);
   return store.acceptance(tenant,acceptanceId);
  }));
 }
 public InstallModels.Acceptance transition(UUID actor,UUID tenant,AssuranceLevel assurance,UUID acceptanceId,InstallModels.AcceptanceTransition request) {
  if(acceptanceId==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
  if(request==null || request.status()==null || !TRANSITIONS.contains(request.status()))
   throw invalid("INSTALLATION_TRANSITION_INVALID","Estado: TESTING, APPROVED, REJECTED, SUSPENDED ou SUPERSEDED.");
  String reason=request.reason()==null?null:DisplayText.normalize(request.reason(),300);
  if(NEEDS_REASON.contains(request.status()) && (reason==null || reason.length()<3))
   throw invalid("INSTALLATION_REASON_REQUIRED","Informe o motivo (3 a 300 caracteres).");
  long expected=version(request.expectedVersion());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_COMMISSION);
   if(store.acceptance(tenant,acceptanceId)==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
   if(!store.transition(tenant,acceptanceId,request.status(),NEEDS_REASON.contains(request.status())?reason:null,expected)) throw stale();
   audit.record(tenant,actor,"INSTALLATION_"+request.status(),"INSTALLATION_ACCEPTANCE",acceptanceId,reason);
   return store.acceptance(tenant,acceptanceId);
  }));
 }

 // ---- gestão
 public InstallModels.AcceptanceSummaryPage managementAcceptances(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_AREAS);
   return new InstallModels.AcceptanceSummaryPage(store.acceptances(tenant).stream().map(InstallStore::summary).toList());
  }));
 }
 public InstallModels.AcceptanceSummary signRules(UUID actor,UUID tenant,AssuranceLevel assurance,UUID acceptanceId,InstallModels.VersionOnly request) {
  if(acceptanceId==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
  long expected=version(request==null?null:request.expectedVersion());
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_AREAS);
   var manager=context.memberships().stream().filter(m->"MANAGER".equals(m.role())).findFirst()
    .orElseThrow(()->new BusinessProblem("INSTALLATION_FORBIDDEN",403,"Operação não permitida para este acesso."));
   if(store.acceptance(tenant,acceptanceId)==null) throw notFound("INSTALLATION_ACCEPTANCE_NOT_FOUND");
   if(!store.signRules(tenant,acceptanceId,manager.membershipId(),actor,expected)) throw stale();
   audit.record(tenant,actor,"INSTALLATION_RULES_SIGNED","INSTALLATION_ACCEPTANCE",acceptanceId,null);
   return InstallStore.summary(store.acceptance(tenant,acceptanceId));
  }));
 }

 // ---- validação (antes de abrir transação)
 static InstallModels.CreateDevice validate(InstallModels.CreateDevice r) {
  if(r==null || r.deviceId()==null || r.connectorId()==null) throw invalid("INSTALLATION_DEVICE_ID_REQUIRED","Informe equipamento e conector.");
  if(r.externalId()==null || !r.externalId().matches(EXTERNAL)) throw invalid("INSTALLATION_DEVICE_EXTERNAL_INVALID","Identificador externo inválido.");
  var c=device(r.label(),r.direction(),r.hardwareRevision(),r.deviceRole(),r.connectionMode());
  return new InstallModels.CreateDevice(r.deviceId(),r.connectorId(),r.externalId(),c[0],c[1],c[2],r.homologationId(),r.deviceRole(),r.connectionMode());
 }
 static InstallModels.UpdateDevice validate(InstallModels.UpdateDevice r) {
  if(r==null) throw invalid("INSTALLATION_DEVICE_INVALID","Informe o equipamento.");
  version(r.expectedVersion());
  if(r.status()==null || !DEVICE_STATES.contains(r.status())) throw invalid("INSTALLATION_DEVICE_STATUS_INVALID","Estado: ACTIVE ou RETIRED.");
  var c=device(r.label(),r.direction(),r.hardwareRevision(),r.deviceRole(),r.connectionMode());
  return new InstallModels.UpdateDevice(c[0],c[1],r.status(),c[2],r.homologationId(),r.deviceRole(),r.connectionMode(),r.expectedVersion());
 }
 private static String[] device(String label,String direction,String hardware,String role,String mode) {
  String normalized=DisplayText.normalize(label,120);
  if(normalized==null) throw invalid("INSTALLATION_DEVICE_LABEL_INVALID","Nome de 1 a 120 caracteres.");
  String d=direction==null?"UNKNOWN":direction;
  if(!DIRECTIONS.contains(d)) throw invalid("INSTALLATION_DEVICE_DIRECTION_INVALID","Sentido inválido.");
  String h=hardware==null || hardware.isBlank()?null:hardware.strip();
  if(h!=null && !h.matches(REVISION)) throw invalid("INSTALLATION_DEVICE_HARDWARE_INVALID","Revisão de hardware até 64 caracteres.");
  if(role!=null && !DEVICE_ROLES.contains(role)) throw invalid("INSTALLATION_DEVICE_ROLE_INVALID","Papel do equipamento inválido.");
  if(mode!=null && !CONNECTION_MODES.contains(mode)) throw invalid("INSTALLATION_DEVICE_MODE_INVALID","Conexão: LOCAL_GATEWAY ou CLOUD_SERVICE.");
  return new String[]{normalized,d,h};
 }
 static InstallModels.CreateChannel validate(InstallModels.CreateChannel r) {
  if(r==null || r.channelId()==null || r.deviceId()==null) throw invalid("INSTALLATION_CHANNEL_ID_REQUIRED","Informe canal e equipamento.");
  if(r.nativeNamespace()==null || !r.nativeNamespace().matches(NAMESPACE) || r.externalChannel()==null || !r.externalChannel().matches(CHANNEL))
   throw invalid("INSTALLATION_CHANNEL_NATIVE_INVALID","Espaço e canal nativos inválidos.");
  if(r.channelKind()==null || !CHANNEL_KINDS.contains(r.channelKind())) throw invalid("INSTALLATION_CHANNEL_KIND_INVALID","Função do canal inválida.");
  String status=r.status()==null?"DISCOVERED":r.status();
  if(!Set.of("DISCOVERED","VERIFIED").contains(status)) throw invalid("INSTALLATION_CHANNEL_STATUS_INVALID","Canal nasce DISCOVERED ou VERIFIED.");
  return new InstallModels.CreateChannel(r.channelId(),r.deviceId(),r.nativeNamespace(),r.externalChannel(),r.channelKind(),status);
 }
 static InstallModels.CreateBinding validate(InstallModels.CreateBinding r) {
  if(r==null || r.bindingId()==null || r.pointId()==null || r.actuatorChannelId()==null || r.controllerDeviceId()==null || r.homologationId()==null)
   throw invalid("INSTALLATION_BINDING_REQUIRED","Informe vínculo, ponto, atuador, controlador e combinação homologada.");
  var d=r.directions()==null?new InstallModels.Directions(null,null):r.directions();
  if((d.entryReader()==null)!=(r.entryReaderDeviceId()==null) || (d.exitReader()==null)!=(r.exitReaderDeviceId()==null)
    || (d.entryReader()!=null && !READER_SENSE.contains(d.entryReader())) || (d.exitReader()!=null && !READER_SENSE.contains(d.exitReader())))
   throw invalid("INSTALLATION_BINDING_DIRECTION_INVALID","Cada leitor exige o sentido FROM_TO ou TO_FROM.");
  if(r.wiringEvidenceRef()==null || !r.wiringEvidenceRef().matches(REF)) throw invalid("INSTALLATION_BINDING_EVIDENCE_INVALID","Referência da ligação inválida.");
  return new InstallModels.CreateBinding(r.bindingId(),r.pointId(),r.actuatorChannelId(),r.controllerDeviceId(),r.entryReaderDeviceId(),
   r.exitReaderDeviceId(),r.doorContactChannelId(),r.exitRequestChannelId(),r.passageSensorChannelId(),r.homologationId(),d,r.wiringEvidenceRef());
 }
 static InstallModels.CreateAcceptance validate(InstallModels.CreateAcceptance r) {
  if(r==null || r.acceptanceId()==null) throw invalid("INSTALLATION_ACCEPTANCE_ID_REQUIRED","Informe o identificador do aceite.");
  for(String ref:new String[]{r.plantRef(),r.topologyManifestRef(),r.testReportRef(),r.emergencyTestRef(),r.rollbackPlanRef()})
   if(ref==null || !ref.matches(REF)) throw invalid("INSTALLATION_ACCEPTANCE_REF_INVALID","Planta, topologia, relatório, emergência e reversão são obrigatórios.");
  if(r.revocationCheckpointRef()!=null && !r.revocationCheckpointRef().matches(REF)) throw invalid("INSTALLATION_ACCEPTANCE_REF_INVALID","Referência inválida.");
  for(String hash:new String[]{r.plantHash(),r.topologyHash(),r.capabilityEnvelopeHash()})
   if(hash==null || !hash.matches(HASH)) throw invalid("INSTALLATION_ACCEPTANCE_HASH_INVALID","Hashes SHA-256 em hexadecimal minúsculo.");
  if(r.testSuiteVersion()==null || !r.testSuiteVersion().matches(SUITE)) throw invalid("INSTALLATION_ACCEPTANCE_SUITE_INVALID","Versão da suíte inválida.");
  if(!counts(r.criticalTestsPending(),r.criticalTestsFailed())) throw invalid("INSTALLATION_RESULTS_INVALID","Testes críticos pendentes e reprovados ≥ 0.");
  Instant review=r.reviewDueAt()==null?null:r.reviewDueAt().truncatedTo(ChronoUnit.MICROS);
  return new InstallModels.CreateAcceptance(r.acceptanceId(),r.plantRef(),r.plantHash(),r.topologyManifestRef(),r.topologyHash(),r.capabilityEnvelopeHash(),
   r.testSuiteVersion(),r.testReportRef(),r.criticalTestsPending(),r.criticalTestsFailed(),r.emergencyTestRef(),r.rollbackPlanRef(),
   r.revocationCheckpointRef(),review);
 }
 private static boolean counts(Integer pending,Integer failed) { return pending!=null && failed!=null && pending>=0 && failed>=0; }
 private static long version(Long expected) {
  if(expected==null || expected<0) throw invalid("INSTALLATION_VERSION_REQUIRED","Informe a versão lida.");
  return expected;
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("INSTALLATION_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23505".equals(state)) throw new BusinessProblem("INSTALLATION_CONFLICT",409,"Já existe cadastro ativo igual (canal nativo, atuador ou ponto).");
   if("23503".equals(state)) throw new BusinessProblem("INSTALLATION_REFERENCE_INVALID",422,"Equipamento, canal, ponto ou combinação inexistente neste condomínio.");
   if("23514".equals(state)) throw new BusinessProblem("INSTALLATION_INVALID",422,"Alteração recusada pelas regras da instalação (função do canal, assinaturas, testes ou estado).");
   throw new BusinessProblem("INSTALLATION_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem stale() { return new BusinessProblem("INSTALLATION_STALE",409,"O cadastro mudou. Atualize e tente de novo."); }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound(String code) { return new BusinessProblem(code,404,"Cadastro não encontrado neste condomínio."); }
}
