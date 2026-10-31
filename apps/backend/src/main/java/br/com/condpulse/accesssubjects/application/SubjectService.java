package br.com.condpulse.accesssubjects.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accesssubjects.infrastructure.SubjectStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Sujeitos e contextos de acesso (AC-005, V038). A gestão (memberships.manage, AAL2) materializa sujeitos das contas com
 * vínculo de acesso físico, bloqueia/desbloqueia o sujeito (bloqueio transversal) e encerra um contexto (só aquele); a
 * portaria (concierge.operate, AAL2) materializa visitantes de convites vigentes. Tipo, unidade e vigência vêm da origem.
 */
public class SubjectService {
 private static final Set<String> FUNCTIONAL_KINDS=Set.of("EMPLOYEE","SERVICE_PROVIDER","EMERGENCY_RESPONDER");
 private final TenantTransactionExecutor transactions;
 private final SubjectStore store;
 private final AuditRecorder audit;
 public SubjectService(TenantTransactionExecutor transactions,SubjectStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 public SubjectModels.SubjectPage list(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   var rows=store.subjects(tenant,SubjectStore.PAGE+1);
   boolean truncated=rows.size()>SubjectStore.PAGE;
   var page=truncated?rows.subList(0,SubjectStore.PAGE):rows;
   var contexts=store.contexts(tenant,page.stream().map(SubjectStore.SubjectRow::id).toList());
   Instant now=Instant.now();
   return new SubjectModels.SubjectPage(page.stream().map(row->{
    var own=contexts.getOrDefault(row.id(),List.of());
    return new SubjectModels.Subject(row.id(),row.originKind(),row.accountId(),row.visitorId(),row.status(),row.epoch(),row.externalRef(),own,
     ContextPrecedence.eligible(row.status(),own.stream().map(SubjectStore::candidate).toList(),now),row.version());
   }).toList(),truncated);
  }));
 }

 /** Idempotente: sujeito por conta e um contexto ativo por vínculo; origem alterada substitui o contexto antigo. */
 public SubjectModels.Materialized materializeMemberships(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   int subjects=0,created=0,superseded=0,kept=0;
   for(var membership:store.physicalMemberships(tenant)) {
    var subject=store.ensureSubject(tenant,"ACCOUNT",membership.accountId());
    if((Boolean)subject[1]) subjects++;
    int outcome=store.ensureContext(tenant,(UUID)subject[0],"MEMBERSHIP",membership.membershipId(),actor);
    if(outcome==1) created++; else if(outcome==2) { created++; superseded++; } else if(outcome==-2) kept++;
   }
   var result=new SubjectModels.Materialized(subjects,created,superseded,kept);
   if(subjects+created>0) audit.record(tenant,actor,"SUBJECT_MATERIALIZED","ACCESS_SUBJECT",tenant,summary(result));
   return result;
  }));
 }

 /** Portaria: visitantes de convites vigentes (o banco confere o convite e a pessoa). */
 public SubjectModels.Materialized materializePermits(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   int subjects=0,created=0,superseded=0,kept=0;
   for(var permit:store.currentPermits(tenant)) {
    var subject=store.ensureSubject(tenant,"VISITOR",permit.visitorId());
    if((Boolean)subject[1]) subjects++;
    int outcome=store.ensureContext(tenant,(UUID)subject[0],"VISIT_PERMIT",permit.permitId(),actor);
    if(outcome==1) created++; else if(outcome==2) { created++; superseded++; } else if(outcome==-2) kept++;
   }
   var result=new SubjectModels.Materialized(subjects,created,superseded,kept);
   if(subjects+created>0) audit.record(tenant,actor,"SUBJECT_MATERIALIZED","ACCESS_SUBJECT",tenant,summary(result));
   return result;
  }));
 }

 public void block(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject,SubjectModels.Block request) {
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw versionRequired();
  String reason=DisplayText.normalize(request.reason(),300);
  if(reason==null || reason.length()<3) throw new BusinessProblem("ACCESS_SUBJECT_REASON_REQUIRED",400,"Informe o motivo do bloqueio (3 a 300 caracteres).");
  change(actor,tenant,assurance,subject,request.expectedVersion(),"BLOCKED",reason,"SUBJECT_BLOCKED");
 }
 public void unblock(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject,SubjectModels.Unblock request) {
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw versionRequired();
  change(actor,tenant,assurance,subject,request.expectedVersion(),"ACTIVE",null,"SUBJECT_UNBLOCKED");
 }
 private void change(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject,long expected,String status,String reason,String action) {
  if(subject==null) throw notFound();
  mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(store.subject(tenant,subject)==null) throw notFound();
   if(!store.changeSubjectState(tenant,subject,expected,status,reason,actor))
    throw new BusinessProblem("ACCESS_SUBJECT_STALE",409,"O cadastro mudou ou já está nesse estado. Atualize e tente de novo.");
   audit.record(tenant,actor,action,"ACCESS_SUBJECT",subject,reason);
   return null;
  }));
 }

 public void closeContext(UUID actor,UUID tenant,AssuranceLevel assurance,UUID contextId,SubjectModels.CloseContext request) {
  if(contextId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw versionRequired();
  String reason=DisplayText.normalize(request.reason(),300);
  if(reason==null || reason.length()<3) throw new BusinessProblem("ACCESS_SUBJECT_REASON_REQUIRED",400,"Informe o motivo (3 a 300 caracteres).");
  mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(store.contextSubject(tenant,contextId)==null) throw notFound();
   if(!store.closeContext(tenant,contextId,request.expectedVersion(),reason))
    throw new BusinessProblem("ACCESS_SUBJECT_STALE",409,"O contexto mudou ou já foi encerrado. Atualize e tente de novo.");
   audit.record(tenant,actor,"SUBJECT_CONTEXT_REVOKED","SUBJECT_CONTEXT",contextId,reason);
   return null;
  }));
 }

 /**
  * Reautorização explícita de um contexto de vínculo revogado: novo contexto da mesma origem, se ela ainda vale. Convite
  * revogado não volta (novo convite é nova origem). Repetir não duplica.
  */
 public void reauthorize(UUID actor,UUID tenant,AssuranceLevel assurance,UUID contextId,SubjectModels.Reauthorize request) {
  if(contextId==null) throw notFound();
  String reason=request==null?null:DisplayText.normalize(request.reason(),300);
  if(reason==null || reason.length()<3) throw new BusinessProblem("ACCESS_SUBJECT_REASON_REQUIRED",400,"Informe o motivo (3 a 300 caracteres).");
  mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(store.reauthorizedBy(tenant,contextId)) return null;
   var revoked=store.revokedMembershipContext(tenant,contextId);
   if(revoked==null) throw new BusinessProblem("ACCESS_SUBJECT_NOT_REAUTHORIZABLE",409,"Só a revogação pendente de um vínculo pode ser reautorizada.");
   UUID created=store.insertReauthorized(tenant,revoked[0],revoked[1],contextId,actor);
   audit.record(tenant,actor,"SUBJECT_CONTEXT_REAUTHORIZED","SUBJECT_CONTEXT",created,reason);
   return null;
  }));
 }

 /** Autorização funcional aprovada pela própria gestão, com fim de até 366 dias; contextId do cliente para repetição. */
 public void addFunctional(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject,SubjectModels.FunctionalAuthorization request) {
  if(subject==null) throw notFound();
  if(request==null || request.contextId()==null) throw new BusinessProblem("ACCESS_SUBJECT_CONTEXT_ID_REQUIRED",400,"Informe o identificador do contexto.");
  if(request.contextKind()==null || !FUNCTIONAL_KINDS.contains(request.contextKind()))
   throw new BusinessProblem("ACCESS_SUBJECT_CONTEXT_KIND_INVALID",400,"Tipo inválido: EMPLOYEE, SERVICE_PROVIDER ou EMERGENCY_RESPONDER.");
  if(request.reference()==null || !request.reference().matches("[A-Za-z0-9._:-]{8,120}"))
   throw new BusinessProblem("ACCESS_SUBJECT_REFERENCE_INVALID",400,"Referência da autorização: 8 a 120 caracteres sem espaço.");
  Instant now=Instant.now();
  Instant until=request.validUntil()==null?null:request.validUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  if(request.validUntil()==null || !request.validUntil().isAfter(now) || request.validUntil().isAfter(now.plus(Duration.ofDays(366))))
   throw new BusinessProblem("ACCESS_SUBJECT_VALIDITY_INVALID",400,"Informe o fim da autorização, em até 366 dias.");
  mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(store.subject(tenant,subject)==null) throw notFound();
   if(store.contextSubject(tenant,request.contextId())!=null) {
    if(store.sameFunctional(tenant,request.contextId(),subject,request.contextKind(),request.reference(),until)) return null;
    throw new BusinessProblem("ACCESS_SUBJECT_CONTEXT_ID_CONFLICT",409,"Este identificador já foi usado para outro contexto.");
   }
   store.insertFunctional(tenant,request.contextId(),subject,request.contextKind(),request.reference(),until,actor);
   audit.record(tenant,actor,"SUBJECT_CONTEXT_ADDED","SUBJECT_CONTEXT",request.contextId(),null);
   return null;
  }));
 }

 private static String summary(SubjectModels.Materialized result) {
  return "sujeitos="+result.subjectsCreated()+" contextos="+result.contextsCreated()+" substituidos="+result.contextsSuperseded()
   +" revogados_mantidos="+result.revokedKept();
 }
 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("ACCESS_SUBJECT_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23514".equals(state) || "23503".equals(state))
    throw new BusinessProblem("ACCESS_SUBJECT_INVALID",422,"O cadastro não atende às regras de origem e vigência.");
   if("23505".equals(state)) throw new BusinessProblem("ACCESS_SUBJECT_CONFLICT",409,"Cadastro alterado ao mesmo tempo. Tente de novo.");
   throw new BusinessProblem("ACCESS_SUBJECT_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem versionRequired() {
  return new BusinessProblem("ACCESS_SUBJECT_VERSION_REQUIRED",400,"Informe a versão lida.");
 }
 private static BusinessProblem notFound() {
  return new BusinessProblem("ACCESS_SUBJECT_NOT_FOUND",404,"Cadastro de acesso não encontrado neste condomínio.");
 }
}
