package br.com.condpulse.accesspolicy.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accesspolicy.infrastructure.PolicyStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Políticas de ingresso (AC-007/AC-008, V043). Gestão com policy.edit (AAL2): cria a política da área, grava revisões
 * imutáveis, valida contra os destinos comissionados e simula cenários sintéticos sem comando físico. policy.publish
 * aprova a revisão validada (a publicação por destino é a V046). O banco repete área, hash, revisão, transições,
 * vigência da validação e aprovador.
 */
public class PolicyService {
 private final TenantTransactionExecutor tenants;
 private final PolicyStore store;
 private final AuditRecorder audit;
 private final SubjectFactsPort subjects;
 public PolicyService(TenantTransactionExecutor tenants,PolicyStore store,AuditRecorder audit) { this(tenants,store,audit,null); }
 /** subjects: leitura dos fatos reais para a simulação com sujeito (V044); null mantém só a simulação sintética. */
 public PolicyService(TenantTransactionExecutor tenants,PolicyStore store,AuditRecorder audit,SubjectFactsPort subjects) {
  this.tenants=tenants; this.store=store; this.audit=audit; this.subjects=subjects;
 }

 // ---- políticas
 public PolicyModels.PolicyPage policies(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   return new PolicyModels.PolicyPage(store.policies(tenant));
  }));
 }
 public PolicyModels.Policy createPolicy(UUID actor,UUID tenant,AssuranceLevel assurance,PolicyModels.CreatePolicy request) {
  if(request==null || request.policyId()==null || request.areaId()==null) throw invalid("POLICY_ID_REQUIRED","Informe política e área.");
  String name=name(request.name());
  var p=new PolicyModels.CreatePolicy(request.policyId(),request.areaId(),name);
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   var existing=store.policy(tenant,p.policyId());
   if(existing!=null) {
    if(existing.areaId().equals(p.areaId()) && existing.name().equals(p.name())) return existing;
    throw new BusinessProblem("POLICY_ID_CONFLICT",409,"Este identificador já foi usado para outra política.");
   }
   store.insertPolicy(tenant,p,actor);
   audit.record(tenant,actor,"POLICY_CREATED","ACCESS_POLICY",p.policyId(),null);
   return store.policy(tenant,p.policyId());
  }));
 }
 public PolicyModels.Policy updatePolicy(UUID actor,UUID tenant,AssuranceLevel assurance,UUID policyId,PolicyModels.UpdatePolicy request) {
  if(policyId==null) throw notFound("POLICY_NOT_FOUND");
  if(request==null || request.status()==null || !Set.of("DRAFT","RETIRED").contains(request.status()))
   throw invalid("POLICY_STATUS_INVALID","Estado: DRAFT ou RETIRED (ativação é pela publicação).");
  long expected=version(request.expectedVersion());
  var p=new PolicyModels.UpdatePolicy(name(request.name()),request.status(),expected);
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   if(store.policy(tenant,policyId)==null) throw notFound("POLICY_NOT_FOUND");
   if(!store.updatePolicy(tenant,policyId,p,actor)) throw stale();
   audit.record(tenant,actor,"POLICY_UPDATED","ACCESS_POLICY",policyId,null);
   return store.policy(tenant,policyId);
  }));
 }

 // ---- revisões
 public PolicyModels.PolicyVersionPage versions(UUID actor,UUID tenant,AssuranceLevel assurance,UUID policyId) {
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   if(policyId==null || store.policy(tenant,policyId)==null) throw notFound("POLICY_NOT_FOUND");
   return new PolicyModels.PolicyVersionPage(store.versions(tenant,policyId));
  }));
 }
 public PolicyModels.PolicyVersion createVersion(UUID actor,UUID tenant,AssuranceLevel assurance,UUID policyId,PolicyModels.CreateVersion request) {
  if(policyId==null) throw notFound("POLICY_NOT_FOUND");
  if(request==null || request.versionId()==null) throw invalid("POLICY_VERSION_ID_REQUIRED","Informe o identificador da revisão.");
  String reason=request.changeReason()==null?null:DisplayText.normalize(request.changeReason(),1000);
  if(reason==null || reason.length()<8) throw invalid("POLICY_REASON_INVALID","Motivo da mudança de 8 a 1000 caracteres.");
  var v=new PolicyModels.CreateVersion(request.versionId(),normalize(request.document()),reason);
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   var policy=store.policy(tenant,policyId);
   if(policy==null) throw notFound("POLICY_NOT_FOUND");
   var problem=PolicyEngine.structure(v.document(),policy.areaId());
   if(problem!=null) throw new BusinessProblem(problem.code(),400,"Documento de regra inválido em "+problem.pointer()+".");
   var existing=store.version(tenant,policyId,v.versionId());
   if(existing!=null) {
    if(existing.document().equals(v.document()) && existing.changeReason().equals(v.changeReason())) return existing;
    throw new BusinessProblem("POLICY_VERSION_ID_CONFLICT",409,"Este identificador já foi usado para outra revisão.");
   }
   store.insertVersion(tenant,policyId,v,actor);
   audit.record(tenant,actor,"POLICY_VERSION_CREATED","ACCESS_POLICY_VERSION",v.versionId(),null);
   return store.version(tenant,policyId,v.versionId());
  }));
 }
 public PolicyModels.ValidationResult validate(UUID actor,UUID tenant,AssuranceLevel assurance,UUID policyId,PolicyModels.ValidateRequest request) {
  if(policyId==null) throw notFound("POLICY_NOT_FOUND");
  if(request==null || request.policyVersionId()==null) throw invalid("POLICY_VERSION_ID_REQUIRED","Informe a revisão.");
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   var policy=store.policy(tenant,policyId);
   if(policy==null) throw notFound("POLICY_NOT_FOUND");
   var v=store.version(tenant,policyId,request.policyVersionId());
   if(v==null) throw notFound("POLICY_VERSION_NOT_FOUND");
   if(!Set.of("DRAFT","VALIDATED").contains(v.state())) throw new BusinessProblem("POLICY_VERSION_NOT_EDITABLE",409,"Só revisão em preparação é validada.");
   // Lock da instalação antes de ler os fatos: a impressão gravada pelo banco é a dos fatos validados.
   store.lockInstallation(tenant);
   var area=store.area(tenant,policy.areaId());
   if(area==null) throw notFound("POLICY_NOT_FOUND");
   int revision=store.installationRevision(tenant);
   int targets=store.countDestinations(tenant,policy.areaId());
   var destinations=store.destinations(tenant,policy.areaId(),null,PolicyEngine.MAX_TARGETS);
   var issues=PolicyEngine.validate(v.document(),area,destinations,targets,Instant.now());
   String status=PolicyEngine.blocked(issues)?"BLOCKED":"VALIDATED";
   UUID id=UUID.randomUUID();
   store.insertValidation(tenant,id,v,revision,status,issues,destinations.stream().map(PolicyModels.Destination::pointId).toList(),
    PolicyEngine.requiredCapabilities(v.document()),actor);
   // A revisão fica DRAFT quando a validação é BLOCKED; o resultado BLOCKED segue no ValidationResult.
   if(!store.recordValidation(tenant,v.versionId(),id,"VALIDATED".equals(status)?"VALIDATED":"DRAFT",v.version())) throw stale();
   audit.record(tenant,actor,"POLICY_VERSION_VALIDATED","ACCESS_POLICY_VERSION",v.versionId(),status);
   return store.validation(tenant,id);
  }));
 }
 public PolicyModels.SimulationResult simulate(UUID actor,UUID tenant,AssuranceLevel assurance,UUID policyId,PolicyModels.SimulateRequest request) {
  if(policyId==null) throw notFound("POLICY_NOT_FOUND");
  if(request==null || request.policyVersionId()==null || request.pointId()==null || request.at()==null)
   throw invalid("POLICY_SIMULATION_INVALID","Informe revisão, passagem e horário.");
  boolean real="AUTHORIZED_EXISTING_SUBJECT".equals(request.mode());
  if(!real && !"SYNTHETIC".equals(request.mode()))
   throw invalid("POLICY_SIMULATION_MODE_UNSUPPORTED","Modo de simulação: SYNTHETIC ou AUTHORIZED_EXISTING_SUBJECT.");
  if(real && subjects==null) throw invalid("POLICY_SIMULATION_MODE_UNSUPPORTED","Simulação com sujeito real indisponível.");
  if(real && (request.subjectId()==null || request.contextId()==null || request.subject()!=null))
   throw invalid("POLICY_SIMULATION_SUBJECT_INVALID","Informe sujeito e contexto reais, sem cenário sintético.");
  if(!real && !PolicyEngine.validSubject(request.subject())) throw invalid("POLICY_SIMULATION_SUBJECT_INVALID","Cenário sintético incompleto ou inválido.");
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   // Sujeito real: fatos pessoais só para quem já gere vínculos (mesma leitura da conferência de idade).
   if(real) context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   var policy=store.policy(tenant,policyId);
   if(policy==null) throw notFound("POLICY_NOT_FOUND");
   var v=store.version(tenant,policyId,request.policyVersionId());
   if(v==null) throw notFound("POLICY_VERSION_NOT_FOUND");
   if("REJECTED".equals(v.state())) throw new BusinessProblem("POLICY_VERSION_NOT_EDITABLE",409,"Revisão rejeitada não é simulada.");
   var area=store.area(tenant,policy.areaId());
   if(area==null) throw notFound("POLICY_NOT_FOUND");
   var point=store.destinations(tenant,policy.areaId(),request.pointId(),1).stream().findFirst()
    .orElseThrow(()->new BusinessProblem("POLICY_POINT_NOT_IN_AREA",422,"A passagem não dá ingresso nesta área."));
   // Mesmos impeditivos da validação (regra, área, total de passagens e esta passagem): nenhum impeditivo vira ALLOW.
   int targets=store.countDestinations(tenant,policy.areaId());
   var outcome=real
    ?PolicyEngine.evaluateSubject(v.document(),area,point,targets,subjects.facts(tenant,request.subjectId(),request.contextId(),policy.areaId(),v.versionId(),
      v.document(),context.timeZone(),request.at()),request.at())
    :PolicyEngine.evaluate(v.document(),area,point,targets,request.subject(),request.at());
   return new PolicyModels.SimulationResult(outcome.result(),outcome.reasonCodes(),PolicyEngine.explain(outcome.reasonCodes()),v.versionId(),
    point.bindingRevision(),outcome.missingCapabilities(),true,false);
  }));
 }
 public PolicyModels.PolicyVersion approve(UUID actor,UUID tenant,AssuranceLevel assurance,UUID policyId,UUID versionId,PolicyModels.VersionAction request) {
  if(policyId==null || versionId==null) throw notFound("POLICY_VERSION_NOT_FOUND");
  long expected=version(request==null?null:request.expectedVersion());
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.PUBLISH_POLICIES);
   var policy=store.policy(tenant,policyId);
   var v=store.version(tenant,policyId,versionId);
   if(policy==null || v==null) throw notFound("POLICY_VERSION_NOT_FOUND");
   // Sob o lock da instalação, os mesmos impeditivos da validação com os fatos e o horário atuais; o banco confere também
   // a impressão dos fatos gravada na validação.
   store.lockInstallation(tenant);
   var area=store.area(tenant,policy.areaId());
   if(area==null) throw notFound("POLICY_NOT_FOUND");
   int targets=store.countDestinations(tenant,policy.areaId());
   var current=PolicyEngine.validate(v.document(),area,store.destinations(tenant,policy.areaId(),null,PolicyEngine.MAX_TARGETS),targets,Instant.now());
   if(PolicyEngine.blocked(current) || v.validationId()==null || !store.factsUnchanged(tenant,v.validationId(),policy.areaId()))
    throw new BusinessProblem("POLICY_VALIDATION_STALE",409,"A instalação ou a área mudou desde a validação. Valide de novo.");
   if(!store.approve(tenant,versionId,expected)) throw stale();
   audit.record(tenant,actor,"POLICY_VERSION_APPROVED","ACCESS_POLICY_VERSION",versionId,null);
   return store.version(tenant,policyId,versionId);
  }));
 }
 public PolicyModels.PolicyVersion reject(UUID actor,UUID tenant,AssuranceLevel assurance,UUID policyId,UUID versionId,PolicyModels.RejectVersion request) {
  if(policyId==null || versionId==null) throw notFound("POLICY_VERSION_NOT_FOUND");
  String reason=request==null || request.reason()==null?null:DisplayText.normalize(request.reason(),300);
  if(reason==null || reason.length()<3) throw invalid("POLICY_REASON_INVALID","Informe o motivo (3 a 300 caracteres).");
  long expected=version(request.expectedVersion());
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   if(store.version(tenant,policyId,versionId)==null) throw notFound("POLICY_VERSION_NOT_FOUND");
   if(!store.reject(tenant,versionId,reason,expected)) throw stale();
   audit.record(tenant,actor,"POLICY_VERSION_REJECTED","ACCESS_POLICY_VERSION",versionId,reason);
   return store.version(tenant,policyId,versionId);
  }));
 }

 // ---- normalização
 private static String name(String raw) {
  String n=DisplayText.normalize(raw,160);
  if(n==null) throw invalid("POLICY_NAME_INVALID","Nome de 1 a 160 caracteres.");
  return n;
 }
 /** Nome do documento normalizado; o resto é conferido pelo motor (estrutura fechada). */
 static PolicyModels.PolicyDocument normalize(PolicyModels.PolicyDocument d) {
  if(d==null) throw invalid("POLICY_DOCUMENT_REQUIRED","Informe o documento da regra.");
  String n=d.name()==null?null:DisplayText.normalize(d.name(),120);
  return new PolicyModels.PolicyDocument(d.schemaVersion(),n,d.areaId(),d.timezone(),d.ruleCombination(),
   d.eligibleContexts()==null?null:List.copyOf(d.eligibleContexts().stream().map(String::valueOf).toList()),d.visitorPolicy(),d.minimumAgeYears(),
   d.requireVerifiedAgeEvidence(),d.weeklyWindows(),d.holidayCalendarIds(),d.calendarPrecedence(),d.reservation(),d.companion(),d.offline(),d.failure(),
   d.antiPassbackMode(),d.maximumOccupancy(),d.emergencyEgressIndependent(),d.maximumAgeYears(),d.defaultEffect());
 }
 private static long version(Long expected) {
  if(expected==null || expected<0) throw invalid("POLICY_VERSION_REQUIRED","Informe a versão lida.");
  return expected;
 }
 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("POLICY_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23505".equals(state)) throw new BusinessProblem("POLICY_CONFLICT",409,"Esta área já tem política ou a revisão já existe.");
   if("23503".equals(state)) throw new BusinessProblem("POLICY_REFERENCE_INVALID",422,"Área ou revisão inexistente neste condomínio.");
   if("23514".equals(state)) throw new BusinessProblem("POLICY_INVALID",422,"Alteração recusada pelas regras da política (área, estado, validação ou instalação).");
   throw new BusinessProblem("POLICY_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem stale() { return new BusinessProblem("POLICY_STALE",409,"A política mudou. Atualize e tente de novo."); }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound(String code) { return new BusinessProblem(code,404,"Política não encontrada neste condomínio."); }
}
