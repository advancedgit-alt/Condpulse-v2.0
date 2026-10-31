package br.com.condpulse.accesscatalog.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accesscatalog.infrastructure.CatalogStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.PlatformCapability;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Catálogo técnico e homologação por combinação (AC-019, V040). MASTER da plataforma: access_catalog.manage cria o
 * dossiê candidato; homologation.approve decide (ensaio, bancada, piloto, produção, reprovação, suspensão). Catálogo não é
 * homologação; aprovação só de capacidade observada e com evidência; conteúdo aprovado nunca muda (nova revisão). Gestão só
 * consulta o estado.
 */
public class CatalogService {
 private static final Set<String> MANUFACTURERS=Set.of("CONTROL_ID","HIKVISION","DAHUA","INTELBRAS");
 private static final Set<String> MODES=Set.of("DEVICE_LOCAL_ACL","EDGE_ONLINE","PROVIDER_LOCAL","ASSISTED");
 private static final Set<String> TOPOLOGIES=Set.of("LOCAL_GATEWAY","CLOUD_SERVICE");
 private static final Set<String> DECISIONS=Set.of("START_TRIAL","APPROVE_LAB","APPROVE_PILOT","APPROVE_PRODUCTION","REJECT","SUSPEND");
 private final PlatformTransactionExecutor platform;
 private final TenantTransactionExecutor tenants;
 private final CatalogStore store;
 private final PlatformStore platformStore;
 public CatalogService(PlatformTransactionExecutor platform,TenantTransactionExecutor tenants,CatalogStore store,PlatformStore platformStore) {
  this.platform=platform; this.tenants=tenants; this.store=store; this.platformStore=platformStore;
 }

 public CatalogModels.ProfilePage profiles(UUID actor,AssuranceLevel assurance,String manufacturer) {
  if(manufacturer!=null && !MANUFACTURERS.contains(manufacturer)) throw invalid("ACCESS_MODEL_FILTER_INVALID","Fabricante inválido.");
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_ACCESS_CATALOG);
   return new CatalogModels.ProfilePage(store.profiles(manufacturer));
  }));
 }
 public CatalogModels.Profile profile(UUID actor,AssuranceLevel assurance,UUID id) {
  if(id==null) throw notFound("ACCESS_MODEL_NOT_FOUND");
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_ACCESS_CATALOG);
   var profile=store.profile(id);
   if(profile==null) throw notFound("ACCESS_MODEL_NOT_FOUND");
   return profile;
  }));
 }
 public CatalogModels.HomologationPage homologations(UUID actor,AssuranceLevel assurance,UUID modelId) {
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_ACCESS_CATALOG);
   return new CatalogModels.HomologationPage(store.homologations(modelId));
  }));
 }
 public CatalogModels.HomologationDetail homologation(UUID actor,AssuranceLevel assurance,UUID id) {
  if(id==null) throw notFound("HOMOLOGATION_NOT_FOUND");
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_ACCESS_CATALOG);
   var found=store.homologation(id);
   if(found==null) throw notFound("HOMOLOGATION_NOT_FOUND");
   return new CatalogModels.HomologationDetail(found,store.decisions(id));
  }));
 }
 /** Gestão: só o estado das combinações (sem a trilha de decisões). */
 public CatalogModels.HomologationPage managementHomologations(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   return new CatalogModels.HomologationPage(store.homologations(null));
  }));
 }

 record Dossier(UUID id,UUID profile,String firmware,String adapter,String adapterVersion,String contract,String mode,String topology,
   String capabilities,String limits,List<UUID> evidence,String limitations,String reason) {}
 Dossier validate(CatalogModels.CreateHomologation request) {
  if(request==null || request.homologationId()==null) throw invalid("HOMOLOGATION_ID_REQUIRED","Informe o identificador do dossiê.");
  if(request.modelProfileId()==null) throw invalid("ACCESS_MODEL_REQUIRED","Informe o perfil do catálogo.");
  if(request.firmwareVersion()==null || !request.firmwareVersion().matches("[A-Za-z0-9._+()-]{1,128}"))
   throw invalid("HOMOLOGATION_FIRMWARE_INVALID","Firmware exato, sem curinga.");
  if(request.adapterName()==null || !request.adapterName().matches("[a-z0-9][a-z0-9._-]{1,63}")) throw invalid("HOMOLOGATION_ADAPTER_INVALID","Adaptador inválido.");
  if(request.adapterVersion()==null || !request.adapterVersion().matches("[A-Za-z0-9._+-]{1,64}")) throw invalid("HOMOLOGATION_ADAPTER_INVALID","Versão do adaptador inválida.");
  if(request.apiContractVersion()==null || !request.apiContractVersion().matches("[A-Za-z0-9._-]{1,32}"))
   throw invalid("HOMOLOGATION_CONTRACT_INVALID","Versão do contrato inválida.");
  if(request.operatingMode()==null || !MODES.contains(request.operatingMode())) throw invalid("HOMOLOGATION_MODE_INVALID","Modo de decisão inválido.");
  if(request.topology()==null || !TOPOLOGIES.contains(request.topology())) throw invalid("HOMOLOGATION_TOPOLOGY_INVALID","Topologia: LOCAL_GATEWAY ou CLOUD_SERVICE.");
  var caps=request.capabilities();
  if(caps==null || !caps.keySet().equals(new HashSet<>(CatalogModels.CAPABILITIES)) || caps.values().stream().anyMatch(v->v==null))
   throw invalid("HOMOLOGATION_CAPABILITIES_INVALID","Informe as 12 capacidades do contrato (documentada e observada; null = desconhecido).");
  var capJson=new TreeMap<String,Object>();
  for(var entry:caps.entrySet()) {
   var value=new TreeMap<String,Object>();
   value.put("documented",entry.getValue().documented()); value.put("observed",entry.getValue().observed()); value.put("approved",null);
   capJson.put(entry.getKey(),value);
  }
  var limits=request.limits();
  if(limits==null || !limits.keySet().equals(new HashSet<>(CatalogModels.LIMITS))
    || limits.values().stream().anyMatch(v->v!=null && (v<0 || v>Integer.MAX_VALUE)))
   throw invalid("HOMOLOGATION_LIMITS_INVALID","Informe os 10 limites (null = desconhecido, nunca ilimitado).");
  var evidence=request.evidenceRefs()==null?List.<UUID>of():request.evidenceRefs();
  if(evidence.size()>300 || evidence.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(evidence).size()!=evidence.size())
   throw invalid("HOMOLOGATION_EVIDENCE_INVALID","Até 300 referências de evidência, sem repetição.");
  var sorted=new ArrayList<>(evidence); sorted.sort(null);
  var limitations=request.limitations()==null?List.<String>of():request.limitations();
  var normalized=new ArrayList<String>();
  for(String text:limitations) {
   String value=DisplayText.normalize(text,500);
   if(value==null) throw invalid("HOMOLOGATION_LIMITATIONS_INVALID","Limitações de 1 a 500 caracteres.");
   normalized.add(value);
  }
  if(normalized.size()>100) throw invalid("HOMOLOGATION_LIMITATIONS_INVALID","Até 100 limitações.");
  String reason=DisplayText.normalize(request.reason(),1000);
  if(reason==null || reason.length()<8) throw invalid("AUDIT_REASON_REQUIRED","Informe o motivo (8 a 1000 caracteres).");
  return new Dossier(request.homologationId(),request.modelProfileId(),request.firmwareVersion(),request.adapterName(),request.adapterVersion(),
   request.apiContractVersion(),request.operatingMode(),request.topology(),CatalogStore.json(capJson),CatalogStore.json(new TreeMap<>(limits)),
   List.copyOf(sorted),CatalogStore.json(normalized),reason);
 }

 public CatalogModels.Created create(UUID actor,AssuranceLevel assurance,CatalogModels.CreateHomologation request) {
  var d=validate(request);
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_ACCESS_CATALOG);
   var existing=store.homologation(d.id());
   if(existing!=null) {
    if(store.sameHomologation(d.id(),d.profile(),d.firmware(),d.adapter(),d.adapterVersion(),d.contract(),d.mode(),d.topology(),d.capabilities(),
      d.limits(),d.evidence(),d.limitations(),d.reason())) return new CatalogModels.Created(existing,false);
    throw new BusinessProblem("HOMOLOGATION_ID_CONFLICT",409,"Este identificador já foi usado para outro dossiê.");
   }
   if(store.profile(d.profile())==null) throw notFound("ACCESS_MODEL_NOT_FOUND");
   platformStore.audit(actor,"HOMOLOGATION_CREATED",null,d.reason(),Map.of("homologationId",d.id().toString(),"modelProfileId",d.profile().toString(),
    "firmware",d.firmware(),"adapter",d.adapter()+"@"+d.adapterVersion(),"mode",d.mode(),"topology",d.topology()));
   store.insertHomologation(d.id(),d.profile(),d.firmware(),d.adapter(),d.adapterVersion(),d.contract(),d.mode(),d.topology(),d.capabilities(),
    d.limits(),d.evidence(),d.limitations(),d.reason(),actor);
   return new CatalogModels.Created(store.homologation(d.id()),true);
  }));
 }

 public CatalogModels.HomologationDetail decide(UUID actor,AssuranceLevel assurance,UUID homologation,CatalogModels.Decide request) {
  if(homologation==null) throw notFound("HOMOLOGATION_NOT_FOUND");
  if(request==null || request.decisionId()==null) throw invalid("HOMOLOGATION_DECISION_ID_REQUIRED","Informe o identificador da decisão.");
  if(request.decision()==null || !DECISIONS.contains(request.decision())) throw invalid("HOMOLOGATION_DECISION_INVALID","Decisão inválida.");
  if(request.expectedVersion()==null || request.expectedVersion()<0) throw invalid("HOMOLOGATION_VERSION_REQUIRED","Informe a versão lida.");
  String reason=DisplayText.normalize(request.reason(),1000);
  if(reason==null || reason.length()<8) throw invalid("AUDIT_REASON_REQUIRED","Informe o motivo (8 a 1000 caracteres).");
  var evidence=request.evidenceRefs()==null?List.<UUID>of():request.evidenceRefs();
  if(evidence.size()>300 || evidence.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(evidence).size()!=evidence.size())
   throw invalid("HOMOLOGATION_EVIDENCE_INVALID","Até 300 referências de evidência, sem repetição.");
  boolean approval=request.decision().startsWith("APPROVE_");
  if(approval && evidence.isEmpty()) throw invalid("HOMOLOGATION_EVIDENCE_REQUIRED","Aprovação exige evidência de execução própria.");
  var approved=request.approvedCapabilityNames()==null?List.<String>of():request.approvedCapabilityNames();
  if(approved.stream().anyMatch(name->name==null || !CatalogModels.CAPABILITIES.contains(name)) || new HashSet<>(approved).size()!=approved.size() || (!approval && !approved.isEmpty()))
   throw invalid("HOMOLOGATION_CAPABILITIES_INVALID","Capacidades aprovadas só em aprovação, sem repetição e da lista do contrato.");
  if(request.reviewDueAt()!=null && (!approval || !request.reviewDueAt().isAfter(Instant.now())))
   throw invalid("HOMOLOGATION_REVIEW_INVALID","Revisão futura, só em aprovação.");
  var restrictions=new ArrayList<String>();
  for(String text:request.restrictions()==null?List.<String>of():request.restrictions()) {
   String value=DisplayText.normalize(text,500);
   if(value==null) throw invalid("HOMOLOGATION_RESTRICTIONS_INVALID","Restrições de 1 a 500 caracteres.");
   restrictions.add(value);
  }
  if(restrictions.size()>100 || (!approval && !restrictions.isEmpty())) throw invalid("HOMOLOGATION_RESTRICTIONS_INVALID","Até 100 restrições, só em aprovação.");
  var sortedEvidence=new ArrayList<>(evidence); sortedEvidence.sort(null);
  var sortedApproved=new ArrayList<>(approved); sortedApproved.sort(null);
  String restrictionsJson=CatalogStore.json(restrictions);
  Instant reviewDue=request.reviewDueAt()==null?null:request.reviewDueAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.APPROVE_HOMOLOGATION);
   if(store.homologation(homologation)==null) throw notFound("HOMOLOGATION_NOT_FOUND");
   if(store.decisionExists(request.decisionId())) {
    if(store.sameDecision(request.decisionId(),homologation,request.decision(),reason,sortedEvidence,sortedApproved,reviewDue,restrictionsJson))
     return new CatalogModels.HomologationDetail(store.homologation(homologation),store.decisions(homologation));
    throw new BusinessProblem("HOMOLOGATION_DECISION_ID_CONFLICT",409,"Este identificador já foi usado para outra decisão.");
   }
   platformStore.audit(actor,"HOMOLOGATION_"+request.decision(),null,reason,Map.of("homologationId",homologation.toString(),
    "decisionId",request.decisionId().toString(),"approvedCapabilities",String.join(",",sortedApproved)));
   store.insertDecision(request.decisionId(),homologation,request.decision(),reason,sortedEvidence,sortedApproved,reviewDue,
    restrictionsJson,actor);
   if(!store.applyDecision(homologation,request.decision(),request.expectedVersion()))
    throw new BusinessProblem("HOMOLOGATION_STALE",409,"A homologação mudou. Atualize e tente de novo.");
   return new CatalogModels.HomologationDetail(store.homologation(homologation),store.decisions(homologation));
  }));
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("HOMOLOGATION_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23514".equals(state)) throw new BusinessProblem("HOMOLOGATION_TRANSITION_INVALID",422,
    "A decisão não segue as regras (transição, evidência ou capacidade observada).");
   if("23503".equals(state)) throw new BusinessProblem("ACCESS_MODEL_NOT_FOUND",404,"Perfil do catálogo não encontrado.");
   if("23505".equals(state)) throw new BusinessProblem("HOMOLOGATION_CONFLICT",409,"Alterado ao mesmo tempo. Tente de novo.");
   throw new BusinessProblem("HOMOLOGATION_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound(String code) { return new BusinessProblem(code,404,"Registro não encontrado no catálogo."); }
}
