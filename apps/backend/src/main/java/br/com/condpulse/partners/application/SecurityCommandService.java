package br.com.condpulse.partners.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.partners.infrastructure.SecurityCommandStore;
import br.com.condpulse.securitylog.application.SecurityLogModels;
import br.com.condpulse.securitylog.infrastructure.SecurityLogStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Comando de Segurança (CPX-012 a CPX-014, PRD complementar §4.3.4; V053), atrás de CP_PARTNERS_ENABLED e
 * CP_SECURITY_LOG_ENABLED. Tudo na transação do parceiro (PartnerTransactionExecutor: conta ativa, papel ativo, AAL2) com
 * security.command; cada condomínio entra no contexto só depois de conferido no alcance de líder agora, e o banco confere
 * o alcance de novo em cada política (platform.partner_reach).
 * <ul>
 * <li>Carteira: semáforo por condomínio (SecurityCommandRules, critério cpx012-v1); só contagens, sem SENSITIVE_VIEWED.</li>
 * <li>Incidentes: abrir (com eventos do log anexados), designar, anexar, encerrar com relatório; INCIDENT_* no log pelo
 * banco. Linha do tempo a partir do log, com SENSITIVE_VIEWED. Dossiê só com security.log.export e motivo, eventos
 * conferidos na cadeia e EVIDENCE_EXPORTED na mesma transação (regras da exportação da V050, R5.7).</li>
 * <li>Aberturas remotas: lista com SENSITIVE_VIEWED; revisão (security.remote_open.review) grava REMOTE_OPEN_REVIEWED.</li>
 * </ul>
 */
public class SecurityCommandService {
 /** Mesmos limites de /access-health (CredentialService). */
 static final Duration HEALTH_STALE=Duration.ofMinutes(10),HEARTBEAT_STALE=Duration.ofMinutes(5);
 private final PartnerTransactionExecutor partners;
 private final SecurityCommandStore store;
 private final SecurityLogStore log;
 public SecurityCommandService(PartnerTransactionExecutor partners,SecurityCommandStore store,SecurityLogStore log) {
  this.partners=Objects.requireNonNull(partners); this.store=Objects.requireNonNull(store); this.log=Objects.requireNonNull(log);
 }

 // ---- CPX-012: visão da carteira ----
 public SecurityCommandModels.Portfolio portfolio(UUID actor,AssuranceLevel assurance,UUID partnerId) {
  return run(actor,assurance,partnerId,context->{
   var reached=store.commandTenants(partnerId,SecurityCommandRules.MAX_TENANTS+1);
   boolean truncated=reached.size()>SecurityCommandRules.MAX_TENANTS;
   // Escala lida no nível do parceiro, antes de qualquer condomínio no contexto.
   var onDuty=store.operatorsOnDuty(partnerId);
   var rows=new ArrayList<SecurityCommandModels.PortfolioTenant>();
   for(var tenant:reached.stream().limit(SecurityCommandRules.MAX_TENANTS).toList()) {
    store.enterTenant(tenant.tenantId());
    var indicators=store.summary(HEALTH_STALE.toSeconds(),HEARTBEAT_STALE.toSeconds(),onDuty.getOrDefault(tenant.tenantId(),0));
    var light=SecurityCommandRules.light(indicators);
    rows.add(new SecurityCommandModels.PortfolioTenant(tenant.tenantId(),tenant.name(),tenant.timeZone(),light.light(),light.reasons(),indicators));
   }
   store.leaveTenant();
   rows.sort(Comparator.comparingInt((SecurityCommandModels.PortfolioTenant t)->SecurityCommandRules.rank(t.light()))
    .thenComparing(SecurityCommandModels.PortfolioTenant::tenantName,Comparator.nullsLast(Comparator.naturalOrder()))
    .thenComparing(t->t.tenantId().toString()));
   return new SecurityCommandModels.Portfolio(partnerId,Instant.now(),SecurityCommandRules.CRITERIA,SecurityCommandRules.UNAVAILABLE,truncated,rows);
  });
 }

 public SecurityCommandModels.Team team(UUID actor,AssuranceLevel assurance,UUID partnerId) {
  return run(actor,assurance,partnerId,context->new SecurityCommandModels.Team(partnerId,store.team(partnerId)));
 }

 // ---- CPX-013: incidentes ----
 public SecurityCommandModels.IncidentPage incidents(UUID actor,AssuranceLevel assurance,UUID partnerId,String state) {
  String wanted=SecurityCommandRules.state(state);
  return run(actor,assurance,partnerId,context->{
   var all=new ArrayList<SecurityCommandModels.Incident>();
   for(var tenant:store.commandTenants(partnerId,SecurityCommandRules.MAX_TENANTS)) {
    store.enterTenant(tenant.tenantId());
    all.addAll(store.incidents(tenant.tenantId(),wanted,SecurityCommandRules.MAX_INCIDENTS+1));
   }
   store.leaveTenant();
   all.sort(Comparator.comparing(SecurityCommandModels.Incident::openedAt).reversed().thenComparing(i->i.id().toString()));
   boolean truncated=all.size()>SecurityCommandRules.MAX_INCIDENTS;
   return new SecurityCommandModels.IncidentPage(partnerId,wanted,truncated,all.stream().limit(SecurityCommandRules.MAX_INCIDENTS).toList());
  });
 }

 public SecurityCommandModels.IncidentDetail open(UUID actor,AssuranceLevel assurance,UUID partnerId,SecurityCommandModels.OpenIncident request) {
  if(request==null || request.tenantId()==null) throw new BusinessProblem("INCIDENT_TENANT_REQUIRED",400,"Escolha o condomínio do incidente.");
  String title=SecurityCommandRules.title(request.title());
  String severity=SecurityCommandRules.severity(request.severity());
  var events=SecurityCommandRules.events(request.eventIds(),false);
  return run(actor,assurance,partnerId,context->{
   enter(partnerId,request.tenantId());
   UUID id=UUID.randomUUID();
   store.insertIncident(id,request.tenantId(),title,severity);
   linkAll(request.tenantId(),id,events);
   return detail(request.tenantId(),id,false);
  });
 }

 public SecurityCommandModels.IncidentDetail detail(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId,UUID incidentId) {
  return run(actor,assurance,partnerId,context->{
   enter(partnerId,tenantId);
   var detail=detail(tenantId,incidentId,true);
   // Leitura de eventos do log pela equipe (R5.6).
   var details=new TreeMap<String,Object>();
   details.put("incidentId",incidentId.toString()); details.put("count",detail.timeline().size());
   log.recordView(tenantId,"INCIDENT_TIMELINE",details);
   return detail;
  });
 }

 public SecurityCommandModels.IncidentDetail assign(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId,UUID incidentId,
   SecurityCommandModels.AssignIncident request) {
  if(request==null || request.memberId()==null) throw new BusinessProblem("INCIDENT_ASSIGNEE_REQUIRED",400,"Escolha o responsável.");
  long version=SecurityCommandRules.version(request.expectedVersion());
  return run(actor,assurance,partnerId,context->{
   enter(partnerId,tenantId);
   var current=require(tenantId,incidentId);
   if(request.memberId().equals(current.assigneeMemberId())) return detail(tenantId,incidentId,false);
   requireOpen(current,version);
   try {
    if(!store.assign(tenantId,incidentId,request.memberId(),version)) throw changed();
   } catch(DataAccessException error) {
    if("23514".equals(SqlStates.of(error)) || "23503".equals(SqlStates.of(error)))
     throw new BusinessProblem("INCIDENT_ASSIGNEE_INVALID",400,"O responsável precisa ser líder, supervisor ou operador ativo do parceiro.");
    throw error;
   }
   return detail(tenantId,incidentId,false);
  });
 }

 public SecurityCommandModels.IncidentDetail link(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId,UUID incidentId,
   SecurityCommandModels.LinkEvents request) {
  var events=SecurityCommandRules.events(request==null?null:request.eventIds(),true);
  return run(actor,assurance,partnerId,context->{
   enter(partnerId,tenantId);
   var current=require(tenantId,incidentId);
   if(!"OPEN".equals(current.state())) throw closed();
   linkAll(tenantId,incidentId,events);
   return detail(tenantId,incidentId,false);
  });
 }

 public SecurityCommandModels.IncidentDetail close(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId,UUID incidentId,
   SecurityCommandModels.CloseIncident request) {
  if(request==null) throw SecurityCommandRules.invalid("INCIDENT_OUTCOME_INVALID","Use RESOLVED, FALSE_ALARM ou REFERRED.");
  String outcome=SecurityCommandRules.outcome(request.outcome());
  String report=SecurityCommandRules.report(request.report());
  long version=SecurityCommandRules.version(request.expectedVersion());
  return run(actor,assurance,partnerId,context->{
   enter(partnerId,tenantId);
   requireOpen(require(tenantId,incidentId),version);
   if(!store.close(tenantId,incidentId,outcome,report,version)) throw changed();
   return detail(tenantId,incidentId,false);
  });
 }

 public SecurityCommandModels.Dossier dossier(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId,UUID incidentId,
   SecurityCommandModels.DossierRequest request) {
  String reason=SecurityCommandRules.exportReason(request==null?null:request.reason());
  return run(actor,assurance,partnerId,context->{
   // Exportação só pelo líder (security.log.export, R5.7), como a do log.
   context.require(PartnerCapability.EXPORT_SECURITY_LOG);
   enter(partnerId,tenantId);
   var detail=detail(tenantId,incidentId,true);
   var events=detail.timeline();
   if(events.isEmpty()) throw new BusinessProblem("INCIDENT_DOSSIER_EMPTY",409,"O incidente ainda não tem eventos no log.");
   if(!store.eventsIntact(tenantId,events.stream().map(SecurityLogModels.Event::eventId).toList()))
    throw new BusinessProblem("SECURITY_LOG_CHAIN_BROKEN",409,"A cadeia do log está quebrada em eventos deste incidente. Verifique a integridade.");
   String digest=SecurityCommandRules.eventsDigest(tenantId,incidentId,events);
   UUID eventId=UUID.randomUUID();
   var details=new TreeMap<String,Object>();
   details.put("count",events.size()); details.put("firstSeq",events.getFirst().seq()); details.put("lastSeq",events.getLast().seq());
   details.put("eventsDigest",digest); details.put("canonicalization",SecurityCommandRules.DOSSIER_CANONICALIZATION);
   Instant exportedAt=store.recordDossier(tenantId,eventId,incidentId,reason,details);
   var manifest=new SecurityCommandModels.DossierManifest(tenantId,incidentId,events.size(),events.getFirst().seq(),events.getLast().seq(),digest,
    SecurityCommandRules.DOSSIER_CANONICALIZATION,actor,exportedAt,reason,eventId);
   return new SecurityCommandModels.Dossier(manifest,detail.incident(),detail.links(),events);
  });
 }

 // ---- CPX-014: revisão de aberturas remotas ----
 public SecurityCommandModels.RemoteOpenPage remoteOpens(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId,String status,
   UUID operatorId,Long beforeSeq,Integer limit) {
  String filter=SecurityCommandRules.reviewFilter(status);
  int size=SecurityCommandRules.limit(limit);
  if(beforeSeq!=null && beforeSeq<2) throw SecurityCommandRules.invalid("REMOTE_OPEN_CURSOR_INVALID","Cursor de paginação inválido.");
  return run(actor,assurance,partnerId,context->{
   context.require(PartnerCapability.REVIEW_REMOTE_OPEN);
   enter(partnerId,tenantId);
   var rows=store.remoteOpens(tenantId,filter,operatorId,beforeSeq,size);
   boolean more=rows.size()>size;
   var items=rows.stream().limit(size).toList();
   var details=new TreeMap<String,Object>();
   details.put("status",filter); details.put("limit",size); details.put("count",items.size());
   if(operatorId!=null) details.put("operatorId",operatorId.toString());
   if(beforeSeq!=null) details.put("beforeSeq",beforeSeq);
   log.recordView(tenantId,"REMOTE_OPEN_REVIEW",details);
   return new SecurityCommandModels.RemoteOpenPage(tenantId,items,more?items.getLast().seq():null);
  });
 }

 public SecurityCommandModels.RemoteOpen review(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId,UUID eventId,
   SecurityCommandModels.ReviewRequest request) {
  var input=SecurityCommandRules.review(request);
  if(eventId==null) throw notFoundOpen();
  return run(actor,assurance,partnerId,context->{
   context.require(PartnerCapability.REVIEW_REMOTE_OPEN);
   enter(partnerId,tenantId);
   var open=store.remoteOpen(tenantId,eventId);
   if(!open.exists()) throw notFoundOpen();
   if(actor.equals(open.actor()))
    throw new BusinessProblem("REMOTE_OPEN_SELF_REVIEW",403,"Ninguém revisa a própria abertura remota.");
   try {
    store.insertReview(tenantId,UUID.randomUUID(),eventId,input.verdict(),input.note(),input.correctsReviewId());
   } catch(DataAccessException error) {
    String state=SqlStates.of(error);
    if("23505".equals(state)) throw new BusinessProblem("REMOTE_OPEN_ALREADY_REVIEWED",409,
     "Esta abertura já foi revisada (ou a revisão já foi corrigida). Atualize a lista e corrija a revisão mais recente.");
    if("23514".equals(state)) throw new BusinessProblem("REMOTE_OPEN_REVIEW_INVALID",400,"Revisão inválida para esta abertura.");
    throw error;
   }
   var item=store.remoteOpenItem(tenantId,eventId);
   if(item==null) throw notFoundOpen();
   return item;
  });
 }

 // ---- Regras comuns ----
 /** Condomínio alcançado agora como líder; senão 403 sem revelar mais nada. */
 private void enter(UUID partnerId,UUID tenantId) {
  if(tenantId==null || store.commandTenants(partnerId,SecurityCommandRules.MAX_TENANTS).stream().noneMatch(t->t.tenantId().equals(tenantId)))
   throw new BusinessProblem("PARTNER_ACCESS_DENIED",403,"Condomínio fora do contrato do parceiro.");
  store.enterTenant(tenantId);
 }
 private SecurityCommandModels.Incident require(UUID tenantId,UUID incidentId) {
  var incident=incidentId==null?null:store.incident(tenantId,incidentId);
  if(incident==null) throw new BusinessProblem("INCIDENT_NOT_FOUND",404,"Incidente não encontrado.");
  return incident;
 }
 private static void requireOpen(SecurityCommandModels.Incident incident,long version) {
  if(!"OPEN".equals(incident.state())) throw closed();
  if(incident.version()!=version) throw changed();
 }
 private void linkAll(UUID tenantId,UUID incidentId,List<UUID> events) {
  if(events.isEmpty()) return;
  try {
   store.link(tenantId,incidentId,events);
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23503".equals(state)) throw new BusinessProblem("INCIDENT_EVENT_NOT_FOUND",400,"Evento do log não encontrado neste condomínio.");
   if("23514".equals(state)) throw new BusinessProblem("INCIDENT_LINKS_LIMIT",409,"O incidente aceita no máximo 50 eventos anexados.");
   throw error;
  }
 }
 private SecurityCommandModels.IncidentDetail detail(UUID tenantId,UUID incidentId,boolean timeline) {
  var incident=require(tenantId,incidentId);
  var links=store.links(tenantId,incidentId);
  List<SecurityLogModels.Event> events=List.of();
  if(timeline) {
   events=log.incidentTimeline(tenantId,incidentId,SecurityCommandRules.MAX_TIMELINE);
   if(events.size()>SecurityCommandRules.MAX_TIMELINE)
    throw new BusinessProblem("INCIDENT_TIMELINE_TOO_LARGE",409,"A linha do tempo passa de 500 eventos; consulte o log por período.");
  }
  return new SecurityCommandModels.IncidentDetail(incident,links,events);
 }

 private <T> T run(UUID actor,AssuranceLevel assurance,UUID partnerId,Function<PartnerContext,T> action) {
  try {
   return partners.execute(actor,partnerId,assurance,context->{
    context.require(PartnerCapability.SECURITY_COMMAND);
    return action.apply(context);
   });
  } catch(BusinessProblem problem) {
   throw problem;
  } catch(DataAccessException error) {
   // Alcance perdido entre a conferência e a gravação (contrato encerrado, papel revogado): nada foi gravado.
   if("42501".equals(SqlStates.of(error)))
    throw new BusinessProblem("PARTNER_ACCESS_DENIED",403,"Condomínio fora do contrato do parceiro ou papel inativo.");
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }
 static BusinessProblem changed() {
  return new BusinessProblem("INCIDENT_CHANGED",409,"O incidente mudou desde a leitura. Atualize e tente de novo.");
 }
 static BusinessProblem closed() { return new BusinessProblem("INCIDENT_CLOSED",409,"O incidente já foi encerrado."); }
 static BusinessProblem notFoundOpen() { return new BusinessProblem("REMOTE_OPEN_NOT_FOUND",404,"Abertura remota não encontrada."); }
 static BusinessProblem unavailable() {
  return new BusinessProblem("SECURITY_COMMAND_UNAVAILABLE",503,"Não foi possível concluir no Comando de Segurança. Tente novamente.");
 }
}
