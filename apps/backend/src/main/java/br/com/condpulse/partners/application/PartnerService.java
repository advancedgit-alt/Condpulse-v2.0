package br.com.condpulse.partners.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.partners.infrastructure.PartnerStore;
import br.com.condpulse.platform.application.PlatformCapability;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.dao.DataAccessException;

/**
 * Hierarquia de parceiro (V049, ADR-0011). Plataforma (partners.read/partners.manage, AAL2): cria e suspende parceiros,
 * vincula e desvincula condomínios (contrato), gerencia equipe e escala. Gestor do parceiro (partner.team.manage, AAL2):
 * gerencia a própria equipe e a escala, nunca o próprio papel. Toda mudança grava o evento PARTNER_* na trilha da
 * plataforma na mesma transação; o banco exige o evento no COMMIT e confere papel, contrato e escala de novo.
 */
public class PartnerService {
 private final PlatformTransactionExecutor platform;
 private final PartnerTransactionExecutor partners;
 private final PartnerStore store;
 private final PlatformStore audit;
 public PartnerService(PlatformTransactionExecutor platform,PartnerTransactionExecutor partners,PartnerStore store,PlatformStore audit) {
  this.platform=platform; this.partners=partners; this.store=store; this.audit=audit;
 }

 // ---- Plataforma ----
 public PartnerModels.PartnerPage list(UUID actor,AssuranceLevel assurance) {
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.READ_PARTNERS);
   return new PartnerModels.PartnerPage(store.partners());
  }));
 }
 public PartnerModels.PartnerDetail detail(UUID actor,AssuranceLevel assurance,UUID partnerId) {
  if(partnerId==null) throw notFound();
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.READ_PARTNERS);
   return detail(partnerId);
  }));
 }
 public PartnerModels.PartnerDetail create(UUID actor,AssuranceLevel assurance,PartnerModels.CreatePartner request) {
  if(request==null) throw invalid("PARTNER_NAME_INVALID","Informe o nome do parceiro.");
  String name=DisplayText.normalize(request.name(),160);
  if(name==null || name.codePointCount(0,name.length())<2) throw invalid("PARTNER_NAME_INVALID","Informe o nome do parceiro (2 a 160 caracteres).");
  String reason=reason(request.reason());
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_PARTNERS); context.require(PlatformCapability.READ_PARTNERS);
   UUID id=UUID.randomUUID();
   audit.audit(actor,"PARTNER_CREATED",null,reason,Map.of("partnerId",id.toString(),"version","0"));
   store.insertPartner(id,name,actor);
   return detail(id);
  }));
 }
 public PartnerModels.PartnerChange changeStatus(UUID actor,AssuranceLevel assurance,UUID partnerId,PartnerModels.ChangePartnerStatus request) {
  if(partnerId==null) throw notFound();
  if(request==null || !("ACTIVE".equals(request.status()) || "SUSPENDED".equals(request.status())))
   throw invalid("PARTNER_STATUS_INVALID","Informe o estado ACTIVE ou SUSPENDED.");
  if(request.expectedVersion()==null || request.expectedVersion()<0) throw invalid("PARTNER_VERSION_REQUIRED","Informe a versão lida do parceiro.");
  String reason=reason(request.reason());
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_PARTNERS); context.require(PlatformCapability.READ_PARTNERS);
   var current=store.lockPartner(partnerId);
   if(current==null) throw notFound();
   if(current.status().equals(request.status())) return new PartnerModels.PartnerChange(false,current);
   if(current.version()!=request.expectedVersion()) throw changed();
   long next=current.version()+1;
   audit.audit(actor,"SUSPENDED".equals(request.status())?"PARTNER_SUSPENDED":"PARTNER_REACTIVATED",null,reason,
    Map.of("partnerId",partnerId.toString(),"version",Long.toString(next)));
   if(!store.updatePartnerStatus(partnerId,request.status(),current.version(),actor)) throw changed();
   return new PartnerModels.PartnerChange(true,store.partner(partnerId));
  }));
 }
 public PartnerModels.Contract startContract(UUID actor,AssuranceLevel assurance,UUID partnerId,PartnerModels.StartContract request) {
  if(partnerId==null) throw notFound();
  if(request==null || request.tenantId()==null) throw invalid("PARTNER_CONTRACT_INVALID","Informe o condomínio.");
  if(request.endsAt()!=null && request.startsAt()!=null && !request.endsAt().isAfter(request.startsAt()))
   throw invalid("PARTNER_CONTRACT_INVALID","O fim previsto precisa ser depois do início.");
  String reason=reason(request.reason());
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_PARTNERS); context.require(PlatformCapability.READ_PARTNERS);
   if(store.lockPartner(partnerId)==null) throw notFound();
   UUID id=UUID.randomUUID();
   audit.audit(actor,"PARTNER_CONTRACT_STARTED",request.tenantId(),reason,
    Map.of("partnerId",partnerId.toString(),"contractId",id.toString(),"version","0"));
   store.insertContract(id,partnerId,request.tenantId(),request.startsAt(),request.endsAt(),reason,actor);
   return store.contract(partnerId,id);
  }));
 }
 /** Desvincula o condomínio: o acesso da equipe do parceiro termina na hora (R3.3). */
 public PartnerModels.Contract endContract(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID contractId,PartnerModels.Reason request) {
  if(partnerId==null || contractId==null) throw notFound();
  String reason=reason(request==null?null:request.reason());
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_PARTNERS); context.require(PlatformCapability.READ_PARTNERS);
   var current=store.lockContract(partnerId,contractId);
   if(current==null) throw notFound();
   if(!"ACTIVE".equals(current.status())) throw new BusinessProblem("PARTNER_CONTRACT_ENDED",409,"O contrato já foi encerrado.");
   audit.audit(actor,"PARTNER_CONTRACT_ENDED",current.tenantId(),reason,Map.of("partnerId",partnerId.toString(),
    "contractId",contractId.toString(),"version",Long.toString(current.version()+1)));
   if(!store.endContract(partnerId,contractId,current.version(),reason,actor)) throw changed();
   return store.contract(partnerId,contractId);
  }));
 }
 public PartnerModels.Member addMember(UUID actor,AssuranceLevel assurance,UUID partnerId,PartnerModels.AddMember request) {
  var input=member(request);
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_PARTNERS); context.require(PlatformCapability.READ_PARTNERS);
   if(store.lockPartner(partnerId)==null) throw notFound();
   return insertMember(actor,partnerId,input);
  }));
 }
 public PartnerModels.Member revokeMember(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID memberId,PartnerModels.Reason request) {
  String reason=reason(request==null?null:request.reason());
  if(partnerId==null || memberId==null) throw notFound();
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_PARTNERS); context.require(PlatformCapability.READ_PARTNERS);
   return revoke(actor,partnerId,memberId,reason);
  }));
 }
 public PartnerModels.SchedulePage schedules(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId) {
  if(partnerId==null) throw notFound();
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.READ_PARTNERS);
   if(store.partner(partnerId)==null) throw notFound();
   return new PartnerModels.SchedulePage(partnerId,store.schedules(partnerId,tenantId));
  }));
 }
 public PartnerModels.Schedule createSchedule(UUID actor,AssuranceLevel assurance,UUID partnerId,PartnerModels.CreateSchedule request) {
  var input=schedule(request);
  if(partnerId==null) throw notFound();
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_PARTNERS); context.require(PlatformCapability.READ_PARTNERS);
   return insertSchedule(actor,partnerId,input);
  }));
 }
 public PartnerModels.Schedule cancelSchedule(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID scheduleId,PartnerModels.Reason request) {
  String reason=reason(request==null?null:request.reason());
  if(partnerId==null || scheduleId==null) throw notFound();
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_PARTNERS); context.require(PlatformCapability.READ_PARTNERS);
   return cancel(actor,partnerId,scheduleId,reason);
  }));
 }

 // ---- Gestor do parceiro (contexto do próprio parceiro) ----
 public PartnerModels.MemberPage team(UUID actor,AssuranceLevel assurance,UUID partnerId) {
  return mapped(()->partners.execute(actor,partnerId,assurance,context->{
   context.require(PartnerCapability.MANAGE_TEAM);
   return new PartnerModels.MemberPage(partnerId,store.members(partnerId));
  }));
 }
 public PartnerModels.Member teamAdd(UUID actor,AssuranceLevel assurance,UUID partnerId,PartnerModels.AddMember request) {
  var input=member(request);
  if(actor!=null && actor.equals(input.accountId()))
   throw new BusinessProblem("PARTNER_SELF_CHANGE_DENIED",403,"O gestor não altera o próprio papel; peça à plataforma.");
  return mapped(()->partners.execute(actor,partnerId,assurance,context->{
   context.require(PartnerCapability.MANAGE_TEAM);
   return insertMember(actor,partnerId,input);
  }));
 }
 public PartnerModels.Member teamRevoke(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID memberId,PartnerModels.Reason request) {
  String reason=reason(request==null?null:request.reason());
  if(memberId==null) throw notFound();
  return mapped(()->partners.execute(actor,partnerId,assurance,context->{
   context.require(PartnerCapability.MANAGE_TEAM);
   var current=store.member(partnerId,memberId);
   if(current!=null && actor.equals(current.accountId()))
    throw new BusinessProblem("PARTNER_SELF_CHANGE_DENIED",403,"O gestor não altera o próprio papel; peça à plataforma.");
   return revoke(actor,partnerId,memberId,reason);
  }));
 }
 public PartnerModels.SchedulePage teamSchedules(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID tenantId) {
  return mapped(()->partners.execute(actor,partnerId,assurance,context->{
   context.require(PartnerCapability.MANAGE_TEAM);
   return new PartnerModels.SchedulePage(partnerId,store.schedules(partnerId,tenantId));
  }));
 }
 public PartnerModels.Schedule teamCreateSchedule(UUID actor,AssuranceLevel assurance,UUID partnerId,PartnerModels.CreateSchedule request) {
  var input=schedule(request);
  return mapped(()->partners.execute(actor,partnerId,assurance,context->{
   context.require(PartnerCapability.MANAGE_TEAM);
   return insertSchedule(actor,partnerId,input);
  }));
 }
 public PartnerModels.Schedule teamCancelSchedule(UUID actor,AssuranceLevel assurance,UUID partnerId,UUID scheduleId,PartnerModels.Reason request) {
  String reason=reason(request==null?null:request.reason());
  if(scheduleId==null) throw notFound();
  return mapped(()->partners.execute(actor,partnerId,assurance,context->{
   context.require(PartnerCapability.MANAGE_TEAM);
   return cancel(actor,partnerId,scheduleId,reason);
  }));
 }
 /** Carteira do parceiro (partner.manage): contratos com o nome do condomínio quando ainda alcançado. */
 public PartnerModels.ContractPage portfolio(UUID actor,AssuranceLevel assurance,UUID partnerId) {
  return mapped(()->partners.execute(actor,partnerId,assurance,context->{
   context.require(PartnerCapability.MANAGE_PARTNER);
   return new PartnerModels.ContractPage(partnerId,store.contracts(partnerId));
  }));
 }

 // ---- Regras comuns ----
 record MemberInput(UUID accountId,String role,String displayName,String reason) {}
 record ScheduleInput(UUID memberId,UUID tenantId,Instant startsAt,Instant endsAt,String reason) {}

 static MemberInput member(PartnerModels.AddMember request) {
  if(request==null || request.accountId()==null) throw invalid("PARTNER_MEMBER_INVALID","Informe a conta.");
  if(request.role()==null || !PartnerCapability.ROLES.contains(request.role()))
   throw invalid("PARTNER_ROLE_INVALID","Papel de parceiro desconhecido.");
  String name=DisplayText.normalize(request.displayName(),160);
  if(name==null) throw invalid("PARTNER_MEMBER_INVALID","Informe o nome de exibição (1 a 160 caracteres).");
  return new MemberInput(request.accountId(),request.role(),name,reason(request.reason()));
 }
 static ScheduleInput schedule(PartnerModels.CreateSchedule request) {
  if(request==null || request.memberId()==null || request.tenantId()==null || request.endsAt()==null)
   throw invalid("PARTNER_SCHEDULE_INVALID","Informe o operador, o condomínio e o fim da escala.");
  Instant start=request.startsAt();
  if(start!=null && !request.endsAt().isAfter(start)) throw invalid("PARTNER_SCHEDULE_INVALID","O fim precisa ser depois do início.");
  if(start!=null && Duration.between(start,request.endsAt()).compareTo(Duration.ofDays(31))>0)
   throw invalid("PARTNER_SCHEDULE_INVALID","Cada escala cobre no máximo 31 dias.");
  return new ScheduleInput(request.memberId(),request.tenantId(),start,request.endsAt(),reason(request.reason()));
 }
 static String reason(String value) {
  String reason=DisplayText.normalize(value,500);
  if(reason==null || reason.codePointCount(0,reason.length())<3) throw invalid("AUDIT_REASON_REQUIRED","Informe o motivo (3 a 500 caracteres).");
  return reason;
 }

 private PartnerModels.PartnerDetail detail(UUID partnerId) {
  var partner=store.partner(partnerId);
  if(partner==null) throw notFound();
  return new PartnerModels.PartnerDetail(partner,store.members(partnerId),store.contracts(partnerId));
 }
 private PartnerModels.Member insertMember(UUID actor,UUID partnerId,MemberInput input) {
  UUID id=UUID.randomUUID();
  audit.audit(actor,"PARTNER_MEMBER_ADDED",null,input.reason(),Map.of("partnerId",partnerId.toString(),"memberId",id.toString(),
   "role",input.role(),"version","0"));
  store.insertMember(id,partnerId,input.accountId(),input.role(),input.displayName(),actor);
  return store.member(partnerId,id);
 }
 private PartnerModels.Member revoke(UUID actor,UUID partnerId,UUID memberId,String reason) {
  var current=store.lockMember(partnerId,memberId);
  if(current==null) throw new BusinessProblem("PARTNER_MEMBER_NOT_FOUND",404,"Papel não encontrado neste parceiro.");
  if(!"ACTIVE".equals(current.status())) throw new BusinessProblem("PARTNER_MEMBER_REVOKED",409,"O papel já foi revogado.");
  audit.audit(actor,"PARTNER_MEMBER_REVOKED",null,reason,Map.of("partnerId",partnerId.toString(),"memberId",memberId.toString(),
   "version",Long.toString(current.version()+1)));
  if(!store.revokeMember(partnerId,memberId,current.version(),reason,actor)) throw changed();
  return store.member(partnerId,memberId);
 }
 private PartnerModels.Schedule insertSchedule(UUID actor,UUID partnerId,ScheduleInput input) {
  var member=store.member(partnerId,input.memberId());
  if(member==null) throw new BusinessProblem("PARTNER_MEMBER_NOT_FOUND",404,"Papel não encontrado neste parceiro.");
  UUID id=UUID.randomUUID();
  audit.audit(actor,"PARTNER_SCHEDULE_CREATED",input.tenantId(),input.reason(),Map.of("partnerId",partnerId.toString(),
   "scheduleId",id.toString(),"memberId",member.id().toString(),"version","0"));
  store.insertSchedule(id,input.tenantId(),partnerId,member.id(),member.accountId(),input.startsAt(),input.endsAt(),input.reason(),actor);
  return store.schedule(partnerId,id);
 }
 private PartnerModels.Schedule cancel(UUID actor,UUID partnerId,UUID scheduleId,String reason) {
  var current=store.lockSchedule(partnerId,scheduleId);
  if(current==null) throw new BusinessProblem("PARTNER_SCHEDULE_NOT_FOUND",404,"Escala não encontrada neste parceiro.");
  if(!"ACTIVE".equals(current.status())) throw new BusinessProblem("PARTNER_SCHEDULE_CANCELLED",409,"A escala já foi cancelada.");
  audit.audit(actor,"PARTNER_SCHEDULE_CANCELLED",current.tenantId(),reason,Map.of("partnerId",partnerId.toString(),
   "scheduleId",scheduleId.toString(),"version",Long.toString(current.version()+1)));
  if(!store.cancelSchedule(partnerId,scheduleId,current.version(),reason,actor)) throw changed();
  return store.schedule(partnerId,scheduleId);
 }

 static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("PARTNER_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23P01".equals(state)) throw new BusinessProblem("PARTNER_PERIOD_CONFLICT",409,
    "Já existe contrato de operador de segurança ou escala no mesmo período.");
   if("23505".equals(state)) throw new BusinessProblem("PARTNER_DUPLICATE",409,"Já existe um registro igual ativo.");
   if("40001".equals(state)) throw changed();
   if("23503".equals(state)) throw new BusinessProblem("PARTNER_REFERENCE_INVALID",422,"Conta, condomínio ou papel inexistente.");
   if("23514".equals(state)) throw new BusinessProblem("PARTNER_RULE_VIOLATED",422,"A mudança não atende às regras do parceiro.");
   throw new BusinessProblem("PARTNER_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem changed() {
  return new BusinessProblem("PARTNER_CHANGED",409,"O registro mudou desde a leitura. Atualize e tente de novo.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("PARTNER_NOT_FOUND",404,"Parceiro não encontrado."); }
}
