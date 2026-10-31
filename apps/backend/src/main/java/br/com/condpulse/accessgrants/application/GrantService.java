package br.com.condpulse.accessgrants.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessage.application.AgeService;
import br.com.condpulse.accessage.application.BirthDateCipher;
import br.com.condpulse.accessage.infrastructure.AgeStore;
import br.com.condpulse.accessgrants.infrastructure.GrantStore;
import br.com.condpulse.accesspolicy.application.PolicyEngine;
import br.com.condpulse.accesspolicy.application.PolicyModels;
import br.com.condpulse.accesspolicy.application.SubjectFactsPort;
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
 * Direitos derivados e revogações (AC-009/AC-015, V044). A gestão com vínculos e publicação (AAL2) deriva os direitos de
 * uma revisão aprovada da política da área; antes, a mesma transação varre os direitos ativos da área (expira o que
 * venceu, marca o que deixou de valer na origem e substitui os de outra revisão). Tudo sob o lock por condomínio.
 * Validar, avaliar e derivar compartilham os impeditivos da regra (PolicyEngine.ruleIssues). Nada aqui aciona porta.
 */
public class GrantService implements SubjectFactsPort {
 private final TenantTransactionExecutor tenants;
 private final GrantStore store;
 private final PolicyStore policies;
 private final AgeStore ages;
 private final BirthDateCipher cipher;
 private final AuditRecorder audit;
 public GrantService(TenantTransactionExecutor tenants,GrantStore store,PolicyStore policies,AgeStore ages,BirthDateCipher cipher,AuditRecorder audit) {
  this.tenants=tenants; this.store=store; this.policies=policies; this.ages=ages; this.cipher=cipher; this.audit=audit;
 }
 private static final int SAMPLE=50,PAGE=500;

 public GrantModels.Derivation derive(UUID actor,UUID tenant,AssuranceLevel assurance,UUID policyId,GrantModels.DeriveRequest request) {
  if(policyId==null) throw notFound("POLICY_NOT_FOUND");
  if(request==null || request.policyVersionId()==null) throw invalid("GRANT_VERSION_REQUIRED","Informe a revisão aprovada.");
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   context.requireCapability(StaffCapability.PUBLISH_POLICIES);
   store.lock(tenant);
   var policy=policies.policy(tenant,policyId);
   if(policy==null) throw notFound("POLICY_NOT_FOUND");
   var version=policies.version(tenant,policyId,request.policyVersionId());
   if(version==null) throw notFound("POLICY_VERSION_NOT_FOUND");
   if(!Set.of("APPROVED","PUBLISHED").contains(version.state()) || "RETIRED".equals(policy.status()))
    throw new BusinessProblem("GRANT_VERSION_NOT_APPROVED",409,"Só revisão aprovada de política ativa gera direitos.");
   var area=policies.area(tenant,policy.areaId());
   if(area==null) throw notFound("POLICY_NOT_FOUND");
   var doc=version.document();
   // Mesmos impeditivos da regra usados por validar e avaliar: regra bloqueada não gera direito.
   var blockers=PolicyEngine.ruleIssues(doc,area,policies.countDestinations(tenant,policy.areaId())).stream()
    .filter(i->"BLOCKER".equals(i.severity())).map(PolicyModels.ValidationIssue::code).distinct().toList();
   if(!blockers.isEmpty()) throw new BusinessProblem("GRANT_POLICY_BLOCKED",409,"A regra tem impeditivo: "+String.join(", ",blockers)+".");
   if(!"ACTIVE".equals(area.status())) throw new BusinessProblem("GRANT_AREA_NOT_ACTIVE",409,"Área interditada ou inativa não recebe direitos.");

   // 1. Varredura dos ativos da área: vencido expira; o que deixou de valer na origem recebe marca; outra revisão é substituída.
   int expired=0,revoked=0,superseded=0;
   Instant now=Instant.now();
   for(var g:store.activeInArea(tenant,policy.areaId())) {
    if(!g.validUntil().isAfter(now)) { if(store.close(tenant,g.grantId(),g.version(),"EXPIRED","WINDOW_ENDED")) expired++; }
    else if(!g.current()) { store.markGrant(tenant,g.subjectId(),g.grantId(),"ORIGIN_CHANGED"); revoked++; }
    else if(!g.policyVersionId().equals(version.versionId())) { if(store.close(tenant,g.grantId(),g.version(),"SUPERSEDED","POLICY_REDERIVED")) superseded++; }
   }
   // 2. Candidatos: contextos ativos dos tipos elegíveis; acima do limite a derivação é recusada (nunca truncada).
   var kinds=List.copyOf(doc.eligibleContexts());
   if(store.countCandidates(tenant,kinds)>GrantPlanner.MAX_CANDIDATES)
    throw new BusinessProblem("GRANT_TOO_MANY_CONTEXTS",409,"Mais de "+GrantPlanner.MAX_CANDIDATES+" contextos elegíveis: divida a regra antes de derivar.");
   var candidates=store.candidates(tenant,kinds,GrantPlanner.MAX_CANDIDATES);
   var ageFacts=new HashMap<UUID,GrantPlanner.AgeFact>();
   if(doc.minimumAgeYears()!=null) for(var c:candidates) ageFacts.computeIfAbsent(c.subjectId(),s->{
    var check=AgeService.assess(ages,cipher,tenant,s,doc.minimumAgeYears(),context.timeZone(),false);
    return new GrantPlanner.AgeFact(check.outcome(),check.evidenceId(),check.validUntil());
   });
   var plan=GrantPlanner.plan(doc,candidates,store.reservations(tenant,area.amenityId()),ageFacts,now,store.versionUntil(tenant,version.versionId()));
   // 3. Gravação: mesma chave ativa não muda; chave revogada não volta; origem ativa de outra derivação é substituída.
   int created=0,unchanged=0;
   var skipped=new ArrayList<GrantModels.Skip>(plan.skipped());
   for(var p:plan.grants()) {
    var c=p.candidate();
    String key=store.key(c.subjectId(),c.contextId(),policy.areaId(),version.versionId(),p.originKind(),p.originId(),p.originVersion(),c.restrictionEpoch(),
     p.ageEvidenceId());
    String state=store.keyState(tenant,key);
    if("ACTIVE".equals(state)) { unchanged++; continue; }
    if("REVOKED".equals(state)) { skipped.add(new GrantModels.Skip(c.contextId(),"REVOKED_KEEPS")); continue; }
    var same=store.activeSameOrigin(tenant,c.contextId(),policy.areaId(),p.originKind()+":"+p.originId());
    if(same!=null) { if(!store.close(tenant,same.grantId(),same.version(),"SUPERSEDED","POLICY_REDERIVED")) throw stale(); superseded++; }
    store.insert(tenant,UUID.randomUUID(),policyId,version.versionId(),policy.areaId(),p,actor);
    created++;
   }
   var byReason=new LinkedHashMap<String,Integer>();
   for(var s:skipped) byReason.merge(s.reason(),1,Integer::sum);
   var result=new GrantModels.Derivation(version.versionId(),created,unchanged,superseded,expired,revoked,Map.copyOf(byReason),
    skipped.stream().limit(SAMPLE).toList());
   audit.record(tenant,actor,"RIGHTS_DERIVED","ACCESS_POLICY",policyId,"criados="+created+" mantidos="+unchanged+" substituidos="+superseded
    +" expirados="+expired+" revogados="+revoked+" nao_atendidos="+skipped.size());
   return result;
  }));
 }

 public GrantModels.GrantPage grants(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject,UUID area) {
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   var rows=store.grants(tenant,subject,area,PAGE+1);
   return new GrantModels.GrantPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
  }));
 }

 /** Revogação explícita de um direito pela gestão: marca durável que vale para todos os métodos do direito. */
 public GrantModels.Grant revoke(UUID actor,UUID tenant,AssuranceLevel assurance,UUID grantId,GrantModels.RevokeGrant request) {
  if(grantId==null) throw notFound("GRANT_NOT_FOUND");
  String reason=request==null || request.reason()==null?null:DisplayText.normalize(request.reason(),300);
  if(reason==null || reason.length()<3) throw invalid("GRANT_REASON_REQUIRED","Informe o motivo (3 a 300 caracteres).");
  if(request.expectedVersion()==null || request.expectedVersion()<0) throw invalid("GRANT_VERSION_REQUIRED","Informe a versão lida.");
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   context.requireCapability(StaffCapability.PUBLISH_POLICIES);
   store.lock(tenant);
   var g=store.grant(tenant,grantId);
   if(g==null) throw notFound("GRANT_NOT_FOUND");
   if("REVOKED".equals(g.status()) && g.version()==request.expectedVersion()+1) return g;
   if(!"ACTIVE".equals(g.status()) || g.version()!=request.expectedVersion()) throw stale();
   store.markGrant(tenant,g.subjectId(),grantId,"MANUAL_REVOCATION");
   audit.record(tenant,actor,"RIGHTS_REVOKED","ACCESS_GRANT",grantId,reason);
   return store.grant(tenant,grantId);
  }));
 }

 public GrantModels.MarkerPage markers(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject) {
  if(subject==null) throw notFound("ACCESS_SUBJECT_NOT_FOUND");
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(!store.subjectExists(tenant,subject)) throw notFound("ACCESS_SUBJECT_NOT_FOUND");
   var rows=store.markers(tenant,subject,PAGE+1);
   return new GrantModels.MarkerPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
  }));
 }

 /** Fatos reais do sujeito para a simulação (chamado dentro da transação da gestão, com memberships.manage conferido). */
 @Override public PolicyModels.SubjectFacts facts(UUID tenant,UUID subject,UUID context,UUID area,UUID policyVersion,PolicyModels.PolicyDocument document,
   ZoneId zone,Instant at) {
  var row=store.subject(tenant,subject,context);
  if(row==null) return new PolicyModels.SubjectFacts(false,false,false,null,null,"UNKNOWN",false,false,false,false);
  String age="UNKNOWN";
  if(document.minimumAgeYears()!=null) {
   var check=AgeService.assess(ages,cipher,tenant,subject,document.minimumAgeYears(),zone,false);
   age=switch(check.outcome()) { case "MEETS" -> "VERIFIED_MEETS_MINIMUM"; case "DOES_NOT_MEET" -> "VERIFIED_BELOW_MINIMUM"; default -> "UNKNOWN"; };
  }
  var grant=store.grantFacts(tenant,subject,context,area,policyVersion,at);
  boolean permit="VISIT_PERMIT".equals(row.sourceKind()) && row.contextCurrent();
  return new PolicyModels.SubjectFacts(true,row.subjectActive(),row.contextCurrent(),row.contextKind(),row.sourceKind(),age,permit,grant.reservationBacked(),
   grant.current(),grant.revoked());
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("GRANT_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23505".equals(state)) throw new BusinessProblem("GRANT_CONFLICT",409,"Direitos alterados ao mesmo tempo. Tente de novo.");
   if("23503".equals(state) || "23514".equals(state))
    throw new BusinessProblem("GRANT_INVALID",422,"Alteração recusada pelas regras de direitos (origem, contexto, vigência ou revogação).");
   throw new BusinessProblem("GRANT_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem stale() { return new BusinessProblem("GRANT_STALE",409,"O direito mudou. Atualize e tente de novo."); }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound(String code) { return new BusinessProblem(code,404,"Não encontrado neste condomínio."); }
}
