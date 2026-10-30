package br.com.condpulse.visits.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Visitas (CP-025) e chegada sem convite (CP-026). Convite é autorização temporal da unidade, não prova de identidade nem
 * de entrada. Morador e responsável convidam, revogam e decidem; dependente só com permissão explícita (o banco aplica
 * a mesma regra). A portaria (capacidade com AAL2) registra a chegada, que espera a primeira decisão válida até o prazo
 * de 5 minutos do banco; sem resposta não existe autorização automática — a portaria segue o interfone ou o
 * procedimento presencial e registra a origem da confirmação.
 */
@Service @Profile("api")
public class VisitService {
 static final Set<String> QUEUE_STATES=Set.of("WAITING","APPROVED","DENIED","EXPIRED","CANCELLED");
 static final Set<String> ASSISTED_SOURCES=Set.of("INTERCOM","IN_PERSON");
 static final Duration MAX_PERMIT=Duration.ofDays(7);
 static final Duration MAX_LEAD=Duration.ofDays(90);
 static final Duration START_TOLERANCE=Duration.ofMinutes(10);
 static final int RULE_HORIZON_DAYS=7;
 static final int RULE_MAX_DAYS=180;
 private static final java.util.regex.Pattern LOCAL_TIME=java.util.regex.Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
 private final TenantTransactionExecutor transactions;
 private final VisitStore store;
 private final AuditRecorder audit;
 public VisitService(TenantTransactionExecutor transactions,VisitStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 // Unidade: convites e chegadas.
 public VisitModels.PermitPage unitPermits(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   requireMember(tenant,unit,"create");
   // Janelas de prestador recorrente dos próximos dias aparecem junto com os convites.
   store.expandRules(tenant,unit,RULE_HORIZON_DAYS);
   return store.unitPermits(tenant,unit);
  });
 }
 public VisitModels.PermitMutation createPermit(UUID actor,UUID tenant,UUID unit,String key,VisitModels.CreatePermit request) {
  requireKey(key);
  if(unit==null) throw unitDenied();
  if(request==null) throw invalid("VISIT_INPUT_INVALID","Informe os dados da visita.");
  String visitor=visitorName(request.visitorName());
  String document=document(request.documentLast4());
  String purpose=request.purpose()==null || DisplayText.collapse(request.purpose()).isEmpty()?null
   :text(request.purpose(),200,"VISIT_PURPOSE_INVALID","Use até 200 caracteres na finalidade.");
  // Segundos inteiros: o banco guarda microssegundos e um período menor que isso viraria violação de CHECK.
  Instant from=request.validFrom()==null?null:request.validFrom().truncatedTo(ChronoUnit.SECONDS);
  Instant until=request.validUntil()==null?null:request.validUntil().truncatedTo(ChronoUnit.SECONDS);
  if(from==null || until==null || !until.isAfter(from) || Duration.between(from,until).compareTo(MAX_PERMIT)>0)
   throw invalid("VISIT_PERIOD_INVALID","Informe o período da visita, com fim depois do início e até 7 dias.");
  String hash=VisitRequestHash.permit(unit,visitor,document,purpose,from,until);
  String route="/v1/tenants/"+tenant+"/units/"+unit+"/permits";
  // O gatilho confere o início pelo relógio do banco, um instante depois do Java: a recusa dele é período inválido.
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::periodInvalid),context->{
   if(!store.member(tenant,unit,"create")) throw unitDenied();
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.permit(tenant,previous.resource(),false);
    if(replay==null) throw permitNotFound();
    return new VisitModels.PermitMutation(replay,true,previous.responseStatus());
   }
   Instant now=store.now();
   if(from.isBefore(now.minus(START_TOLERANCE)) || from.isAfter(now.plus(MAX_LEAD)) || !until.isAfter(now)) throw periodInvalid();
   UUID visitorId=UUID.randomUUID(), id=UUID.randomUUID();
   store.insertVisitor(visitorId,tenant,visitor,document,actor);
   store.insertPermit(id,tenant,visitorId,unit,purpose,actor,from,until);
   var permit=store.permit(tenant,id,false);
   if(permit==null) throw new IllegalStateException("Convite criado não foi encontrado na mesma transação.");
   audit.record(tenant,actor,"VISIT_PERMIT_CREATED","VISIT_PERMIT",id,null);
   store.event(tenant,"VISIT_PERMIT_CREATED",id,permit.version(),unit,permit.state());
   store.finish(tenant,actor,route,key,id,201);
   return new VisitModels.PermitMutation(permit,false,201);
  });
 }
 /** Revogação pela versão lida, com motivo: quem chegar depois não tem mais o convite. Expirado não é revogado. */
 public VisitModels.Permit revokePermit(UUID actor,UUID tenant,UUID permitId,VisitModels.RevokePermit request) {
  if(permitId==null) throw permitNotFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("VISIT_VERSION_REQUIRED","Atualize os convites antes de revogar.");
  String reason=text(request.reason(),500,"VISIT_REVOKE_REASON_REQUIRED","Informe o motivo da revogação, com até 500 caracteres.");
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::permitChanged),context->{
   var current=store.permit(tenant,permitId,true);
   if(current==null) throw permitNotFound();
   boolean adult=store.member(tenant,current.unitId(),null);
   if(!adult && !(current.createdByMe() && store.member(tenant,current.unitId(),"create"))) throw permitNotFound();
   if("REVOKED".equals(current.state())) throw new BusinessProblem("VISIT_PERMIT_REVOKED",409,"Este convite já foi revogado.");
   if("EXPIRED".equals(current.state())) throw new BusinessProblem("VISIT_PERMIT_EXPIRED",409,"A vigência deste convite já terminou.");
   if(current.version()!=request.expectedVersion() || !store.revoke(tenant,permitId,request.expectedVersion(),actor,reason))
    throw permitChanged();
   var revoked=store.permit(tenant,permitId,false);
   if(revoked==null) throw new IllegalStateException("Convite revogado não foi encontrado na mesma transação.");
   audit.record(tenant,actor,"VISIT_PERMIT_REVOKED","VISIT_PERMIT",permitId,reason);
   store.event(tenant,"VISIT_PERMIT_REVOKED",permitId,revoked.version(),revoked.unitId(),revoked.state());
   return revoked;
  });
 }
 /** Regras de prestador recorrente da unidade (CP-039), com a próxima janela. */
 public VisitModels.RulePage unitRules(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   requireMember(tenant,unit,"create");
   store.expandRules(tenant,unit,RULE_HORIZON_DAYS);
   return store.unitRules(tenant,unit);
  });
 }
 /**
  * Regra de prestador recorrente: dias da semana, horário local e período de até 180 dias, começando hoje ou depois (no
  * fuso do condomínio). As janelas dos próximos dias são geradas na mesma transação; fora delas não há convite.
  */
 public VisitModels.RuleMutation createRule(UUID actor,UUID tenant,UUID unit,String key,VisitModels.CreateRule request) {
  requireKey(key);
  if(unit==null) throw unitDenied();
  if(request==null) throw invalid("VISIT_INPUT_INVALID","Informe os dados do prestador.");
  String visitor=visitorName(request.visitorName());
  String document=document(request.documentLast4());
  String purpose=request.purpose()==null || DisplayText.collapse(request.purpose()).isEmpty()?null
   :text(request.purpose(),200,"VISIT_PURPOSE_INVALID","Use até 200 caracteres na finalidade.");
  var days=request.weekDays()==null?java.util.List.<Integer>of():request.weekDays().stream().distinct().sorted().toList();
  if(days.isEmpty() || days.size()!=request.weekDays().size() || days.stream().anyMatch(d->d==null || d<1 || d>7))
   throw invalid("VISIT_RULE_DAYS_INVALID","Escolha de 1 a 7 dias da semana, sem repetir.");
  if(request.startTime()==null || request.endTime()==null || !LOCAL_TIME.matcher(request.startTime()).matches()
     || !LOCAL_TIME.matcher(request.endTime()).matches() || request.startTime().compareTo(request.endTime())>=0)
   throw invalid("VISIT_RULE_TIME_INVALID","Informe o horário no formato HH:MM, com fim depois do início no mesmo dia.");
  var from=request.validFrom(); var until=request.validUntil();
  if(from==null || until==null || until.isBefore(from) || until.isAfter(from.plusDays(RULE_MAX_DAYS)))
   throw invalid("VISIT_RULE_PERIOD_INVALID","Informe o período, com fim no mesmo dia ou depois do início e até 180 dias.");
  String hash=VisitRequestHash.rule(unit,visitor,document,purpose,days,request.startTime(),request.endTime(),from,until);
  String route="/v1/tenants/"+tenant+"/units/"+unit+"/visit-rules";
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::rulePeriodInvalid),context->{
   if(!store.member(tenant,unit,"create")) throw unitDenied();
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.rule(tenant,previous.resource(),false);
    if(replay==null) throw ruleNotFound();
    return new VisitModels.RuleMutation(replay,true,previous.responseStatus());
   }
   var today=store.today(tenant);
   if(today==null || from.isBefore(today) || from.isAfter(today.plusDays(90))) throw rulePeriodInvalid();
   UUID visitorId=UUID.randomUUID(), id=UUID.randomUUID();
   store.insertVisitor(visitorId,tenant,visitor,document,actor);
   store.insertRule(id,tenant,unit,visitorId,purpose,days,request.startTime(),request.endTime(),from,until,actor);
   store.expandRules(tenant,unit,RULE_HORIZON_DAYS);
   var rule=store.rule(tenant,id,false);
   if(rule==null) throw new IllegalStateException("Regra criada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"VISIT_RULE_CREATED","VISIT_RULE",id,null);
   store.event(tenant,"VISIT_RULE_CREATED",id,rule.version(),unit,rule.state());
   store.finish(tenant,actor,route,key,id,201);
   return new VisitModels.RuleMutation(rule,false,201);
  });
 }
 /** Revogação pela versão lida, com motivo; as janelas ainda válidas caem na mesma transação (gatilho do banco). */
 public VisitModels.VisitRule revokeRule(UUID actor,UUID tenant,UUID ruleId,VisitModels.RevokeRule request) {
  if(ruleId==null) throw ruleNotFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("VISIT_VERSION_REQUIRED","Atualize as regras antes de revogar.");
  String reason=text(request.reason(),500,"VISIT_REVOKE_REASON_REQUIRED","Informe o motivo da revogação, com até 500 caracteres.");
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::ruleChanged),context->{
   var current=store.rule(tenant,ruleId,true);
   if(current==null) throw ruleNotFound();
   boolean adult=store.member(tenant,current.unitId(),null);
   if(!adult && !(current.createdByMe() && store.member(tenant,current.unitId(),"create"))) throw ruleNotFound();
   if("REVOKED".equals(current.state())) throw new BusinessProblem("VISIT_RULE_REVOKED",409,"Esta regra já foi revogada.");
   if(current.version()!=request.expectedVersion() || !store.revokeRule(tenant,ruleId,request.expectedVersion(),actor,reason)) throw ruleChanged();
   var revoked=store.rule(tenant,ruleId,false);
   if(revoked==null) throw new IllegalStateException("Regra revogada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"VISIT_RULE_REVOKED","VISIT_RULE",ruleId,reason);
   store.event(tenant,"VISIT_RULE_REVOKED",ruleId,revoked.version(),revoked.unitId(),revoked.state());
   return revoked;
  });
 }
 private static BusinessProblem ruleNotFound() { return new BusinessProblem("VISIT_RULE_NOT_FOUND",404,"Regra não encontrada."); }
 private static BusinessProblem ruleChanged() {
  return new BusinessProblem("VISIT_RULE_CHANGED",409,"A regra foi alterada. Atualize antes de continuar.");
 }
 private static BusinessProblem rulePeriodInvalid() {
  return invalid("VISIT_RULE_PERIOD_INVALID","A regra começa hoje ou depois (até 90 dias à frente) e dura até 180 dias.");
 }

 /** Convidados da reserva (CP-038), para quem pode convidar pela unidade da reserva. */
 public VisitModels.PermitPage reservationGuests(UUID actor,UUID tenant,UUID reservation) {
  if(reservation==null) throw reservationUnavailable();
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   var target=store.guestTarget(tenant,reservation);
   if(target==null || !"BOOKING".equals(target.kind()) || !store.member(tenant,target.unitId(),"create")) throw reservationUnavailable();
   return store.reservationGuests(tenant,reservation);
  });
 }
 /**
  * Convidado de reserva confirmada (CP-038): convite da unidade com a janela do evento — de até 1 hora antes do início
  * (ou de agora, durante o evento) até o fim do uso —, até a capacidade da área. O banco confere tudo de novo e, ao
  * encerrar a reserva, revoga os convidados na mesma transação.
  */
 public VisitModels.PermitMutation addGuest(UUID actor,UUID tenant,UUID reservation,String key,VisitModels.AddGuest request) {
  requireKey(key);
  if(reservation==null) throw reservationUnavailable();
  if(request==null) throw invalid("VISIT_INPUT_INVALID","Informe os dados do convidado.");
  String visitor=visitorName(request.visitorName());
  String document=document(request.documentLast4());
  String hash=VisitRequestHash.guest(reservation,visitor,document);
  String route="/v1/tenants/"+tenant+"/reservations/"+reservation+"/guests";
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::guestUnavailable),context->{
   var target=store.guestTarget(tenant,reservation);
   if(target==null || !"BOOKING".equals(target.kind()) || !store.member(tenant,target.unitId(),"create")) throw reservationUnavailable();
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.permit(tenant,previous.resource(),false);
    if(replay==null) throw permitNotFound();
    return new VisitModels.PermitMutation(replay,true,previous.responseStatus());
   }
   Instant now=store.now();
   if(!"CONFIRMED".equals(target.status()) || !target.endsAt().isAfter(now)) throw guestUnavailable();
   if(target.activeGuests()>=target.capacity())
    throw new BusinessProblem("RESERVATION_GUEST_LIMIT",409,"A reserva já tem o número máximo de convidados da área.");
   Instant earliest=target.startsAt().minus(Duration.ofHours(1));
   // Segundos inteiros (o banco guarda microssegundos); nunca antes do horário do banco.
   Instant from=(now.isAfter(earliest)?now:earliest).truncatedTo(ChronoUnit.SECONDS);
   if(from.isAfter(now.plus(MAX_LEAD)))
    throw new BusinessProblem("RESERVATION_GUEST_TOO_EARLY",409,"Convidados podem ser incluídos a partir de 90 dias antes do evento.");
   UUID visitorId=UUID.randomUUID(), id=UUID.randomUUID();
   store.insertVisitor(visitorId,tenant,visitor,document,actor);
   store.insertGuestPermit(id,tenant,visitorId,target.unitId(),text("Convidado: "+target.amenityName(),200,"VISIT_PURPOSE_INVALID",
    "Finalidade inválida."),actor,from,target.endsAt(),reservation);
   var permit=store.permit(tenant,id,false);
   if(permit==null) throw new IllegalStateException("Convidado criado não foi encontrado na mesma transação.");
   audit.record(tenant,actor,"RESERVATION_GUEST_ADDED","VISIT_PERMIT",id,null);
   store.event(tenant,"VISIT_PERMIT_CREATED",id,permit.version(),target.unitId(),permit.state());
   store.finish(tenant,actor,route,key,id,201);
   return new VisitModels.PermitMutation(permit,false,201);
  });
 }
 private static BusinessProblem reservationUnavailable() {
  return new BusinessProblem("RESERVATION_GUEST_UNAVAILABLE",404,"Reserva não disponível para convidados.");
 }
 private static BusinessProblem guestUnavailable() {
  return new BusinessProblem("RESERVATION_NOT_CONFIRMED",409,"Só reservas confirmadas e ainda não encerradas recebem convidados.");
 }

 /**
  * Link compartilhável do convite (CP-037). Cada pedido gera um token novo e revoga o anterior na mesma transação: o
  * token bruto só existe nesta resposta (o banco guarda o SHA-256). Sem chave de operação de propósito: guardar a
  * resposta para repetir exigiria guardar o token; resposta perdida se resolve gerando outro link.
  */
 public VisitModels.PermitLink shareLink(UUID actor,UUID tenant,UUID permitId) {
  if(permitId==null) throw permitNotFound();
  String token=PermitLinkTokens.newToken();
  String hash=PermitLinkTokens.hash(token);
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::linkUnavailable),context->{
   var current=linkable(actor,tenant,permitId);
   store.revokeLinks(tenant,permitId,actor);
   store.insertLink(UUID.randomUUID(),tenant,permitId,current.unitId(),hash,actor);
   audit.record(tenant,actor,"VISIT_PERMIT_LINK_CREATED","VISIT_PERMIT",permitId,null);
   return new VisitModels.PermitLink(token,current.validUntil());
  });
 }
 /** Revoga o link em vigor; o convite continua valendo para a portaria. Sem link em vigor, nada muda. */
 public VisitModels.Permit revokeLink(UUID actor,UUID tenant,UUID permitId) {
  if(permitId==null) throw permitNotFound();
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   var current=store.permit(tenant,permitId,false);
   if(current==null || !mayManage(tenant,current)) throw permitNotFound();
   if(store.revokeLinks(tenant,permitId,actor)>0) audit.record(tenant,actor,"VISIT_PERMIT_LINK_REVOKED","VISIT_PERMIT",permitId,null);
   var after=store.permit(tenant,permitId,false);
   if(after==null) throw new IllegalStateException("Convite não foi encontrado na mesma transação.");
   return after;
  });
 }
 private VisitModels.Permit linkable(UUID actor,UUID tenant,UUID permitId) {
  var current=store.permit(tenant,permitId,true);
  if(current==null || !mayManage(tenant,current)) throw permitNotFound();
  if(!"ACTIVE".equals(current.state()) && !"SCHEDULED".equals(current.state())) throw linkUnavailable();
  return current;
 }
 /** Mesma regra da revogação: adulto da unidade ou quem criou, com a permissão de convidar. */
 private boolean mayManage(UUID tenant,VisitModels.Permit permit) {
  return store.member(tenant,permit.unitId(),null) || (permit.createdByMe() && store.member(tenant,permit.unitId(),"create"));
 }
 private static BusinessProblem linkUnavailable() {
  return new BusinessProblem("VISIT_PERMIT_LINK_UNAVAILABLE",409,"Só convites em vigor ou agendados podem ser compartilhados.");
 }

 public VisitModels.ArrivalPage unitArrivals(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   requireMember(tenant,unit,"decide");
   return store.unitArrivals(tenant,unit);
  });
 }
 /** Resposta da unidade pelo aplicativo: vale só a primeira, dentro do prazo; depois disso, o estado final. */
 public VisitModels.Arrival decide(UUID actor,UUID tenant,UUID arrivalId,VisitModels.Decision request) {
  if(arrivalId==null) throw arrivalNotFound();
  String state=decision(request==null?null:request.decision());
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::arrivalClosed),context->{
   var current=store.arrival(tenant,arrivalId);
   if(current==null || !store.member(tenant,current.unitId(),"decide")) throw arrivalNotFound();
   requireWaiting(current);
   if(!store.decide(tenant,arrivalId,state,actor,"APP",null)) throw closed(store.arrival(tenant,arrivalId));
   return decided(tenant,actor,arrivalId,null);
  });
 }

 // Portaria: visitas previstas, chegadas e confirmação assistida.
 public VisitModels.PermitPage expected(UUID actor,UUID tenant,AssuranceLevel assurance,UUID unit,String visitor) {
  String filter=visitor==null || DisplayText.collapse(visitor).isEmpty()?null
   :text(visitor,80,"VISIT_FILTER_INVALID","Use até 80 caracteres visíveis na busca por nome.");
  return execute(actor,tenant,assurance,Map.of(),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   // A portaria materializa as janelas de hoje e de amanhã (determinísticas a partir das regras) antes de listar.
   store.expandRules(tenant,unit,1);
   return store.expected(tenant,unit,filter);
  });
 }
 public VisitModels.ArrivalPage queue(UUID actor,UUID tenant,AssuranceLevel assurance,String state) {
  if(state!=null && !QUEUE_STATES.contains(state)) throw invalid("ARRIVAL_FILTER_INVALID","Situação de chegada inválida.");
  return execute(actor,tenant,assurance,Map.of(),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   return store.queue(tenant,state);
  });
 }
 /** Pessoa aguardando na portaria: a unidade decide em até 5 minutos; sem resposta, nada é liberado automaticamente. */
 public VisitModels.ArrivalMutation register(UUID actor,UUID tenant,AssuranceLevel assurance,String key,VisitModels.RegisterArrival request) {
  requireKey(key);
  if(request==null || request.unitId()==null) throw invalid("ARRIVAL_UNIT_REQUIRED","Informe a unidade de destino.");
  String visitor=visitorName(request.visitorName());
  String document=document(request.documentLast4());
  String hash=VisitRequestHash.arrival(request.unitId(),visitor,document,request.permitId());
  String route="/v1/tenants/"+tenant+"/arrivals";
  return execute(actor,tenant,assurance,Map.of("23503",VisitService::unitUnavailable,"23514",VisitService::permitInvalid),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.arrival(tenant,previous.resource());
    if(replay==null) throw arrivalNotFound();
    return new VisitModels.ArrivalMutation(replay,true,previous.responseStatus());
   }
   if(!store.activeUnit(tenant,request.unitId())) throw unitUnavailable();
   if(request.permitId()!=null) {
    // Convite vinculado: o da mesma unidade e da mesma pessoa, ainda válido; revogado, vencido ou de vínculo encerrado
    // é recusado com o estado atual (AC-019/AC-020) e a portaria segue a chegada sem convite.
    var permit=store.permit(tenant,request.permitId(),false);
    if(permit==null || !permit.unitId().equals(request.unitId()) || !permit.visitorName().equals(visitor)
      || !java.util.Objects.equals(permit.documentLast4(),document)) throw permitInvalid();
    if("REVOKED".equals(permit.state()))
     throw new BusinessProblem("VISIT_PERMIT_REVOKED",409,"Este convite foi revogado. Siga o procedimento de chegada sem convite.");
    if("EXPIRED".equals(permit.state()))
     throw new BusinessProblem("VISIT_PERMIT_EXPIRED",409,"A vigência deste convite terminou. Siga o procedimento de chegada sem convite.");
    if(!store.issuerActive(tenant,permit.permitId()))
     throw new BusinessProblem("VISIT_PERMIT_INACTIVE",409,"Quem convidou não tem mais vínculo para convidar pela unidade: o convite não vale.");
   }
   UUID visitorId=UUID.randomUUID(), id=UUID.randomUUID();
   store.insertVisitor(visitorId,tenant,visitor,document,actor);
   store.insertArrival(id,tenant,request.unitId(),visitorId,request.permitId(),actor);
   var arrival=store.arrival(tenant,id);
   if(arrival==null) throw new IllegalStateException("Chegada registrada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"ARRIVAL_REQUESTED","ARRIVAL",id,null);
   store.event(tenant,"ARRIVAL_REQUESTED",id,arrival.version(),arrival.unitId(),arrival.state());
   store.finish(tenant,actor,route,key,id,201);
   return new VisitModels.ArrivalMutation(arrival,false,201);
  });
 }
 /**
  * Confirmação assistida (interfone ou presencial) registrada pela portaria, com a origem anotada. Vale também depois
  * do prazo: sem resposta pelo aplicativo, a portaria segue o interfone ou o procedimento presencial e registra a origem.
  */
 public VisitModels.Arrival assistedDecision(UUID actor,UUID tenant,AssuranceLevel assurance,UUID arrivalId,VisitModels.AssistedDecision request) {
  if(arrivalId==null) throw arrivalNotFound();
  String state=decision(request==null?null:request.decision());
  String source=request.source();
  if(source==null || !ASSISTED_SOURCES.contains(source))
   throw invalid("ARRIVAL_SOURCE_INVALID","Informe se a confirmação foi pelo interfone ou presencial.");
  String note=text(request.note(),300,"ARRIVAL_NOTE_REQUIRED","Anote quem confirmou e como, com até 300 caracteres.");
  return execute(actor,tenant,assurance,Map.of("23514",VisitService::arrivalClosed),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var current=store.arrival(tenant,arrivalId);
   if(current==null) throw arrivalNotFound();
   if(!"WAITING".equals(current.state()) && !"EXPIRED".equals(current.state())) throw closed(current);
   if(!store.decide(tenant,arrivalId,state,actor,source,note)) throw closed(store.arrival(tenant,arrivalId));
   return decided(tenant,actor,arrivalId,note);
  });
 }
 /** Encerramento pela portaria da chegada ainda aguardando, inclusive vencida (pessoa foi embora, registro errado). */
 public VisitModels.Arrival cancel(UUID actor,UUID tenant,AssuranceLevel assurance,UUID arrivalId,VisitModels.CancelArrival request) {
  if(arrivalId==null) throw arrivalNotFound();
  String reason=text(request==null?null:request.reason(),300,"ARRIVAL_CANCEL_REASON_REQUIRED","Informe o motivo do encerramento, com até 300 caracteres.");
  return execute(actor,tenant,assurance,Map.of("23514",VisitService::arrivalClosed),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var current=store.arrival(tenant,arrivalId);
   if(current==null) throw arrivalNotFound();
   if(!"WAITING".equals(current.state()) && !"EXPIRED".equals(current.state())) throw closed(current);
   if(!store.cancel(tenant,arrivalId,actor,reason)) throw closed(store.arrival(tenant,arrivalId));
   var cancelled=store.arrival(tenant,arrivalId);
   if(cancelled==null) throw new IllegalStateException("Chegada encerrada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"ARRIVAL_CANCELLED","ARRIVAL",arrivalId,reason);
   store.event(tenant,"ARRIVAL_CANCELLED",arrivalId,cancelled.version(),cancelled.unitId(),cancelled.state());
   return cancelled;
  });
 }

 private VisitModels.Arrival decided(UUID tenant,UUID actor,UUID arrivalId,String note) {
  var result=store.arrival(tenant,arrivalId);
  if(result==null) throw new IllegalStateException("Chegada decidida não foi encontrada na mesma transação.");
  audit.record(tenant,actor,"ARRIVAL_DECIDED","ARRIVAL",arrivalId,note);
  store.event(tenant,"ARRIVAL_DECIDED",arrivalId,result.version(),result.unitId(),result.state());
  return result;
 }
 private void requireMember(UUID tenant,UUID unit,String dependentPermission) {
  if(unit==null || !store.member(tenant,unit,dependentPermission)) throw unitDenied();
 }
 private static void requireWaiting(VisitModels.Arrival arrival) {
  if(!"WAITING".equals(arrival.state())) throw closed(arrival);
 }
 /** Estado final da chegada: "expirada" depois do prazo, "já decidida" ou "encerrada" pela portaria. */
 private static BusinessProblem closed(VisitModels.Arrival arrival) {
  if(arrival==null) return arrivalNotFound();
  return switch(arrival.state()) {
   case "EXPIRED" -> new BusinessProblem("ARRIVAL_EXPIRED",409,"O prazo de resposta desta chegada terminou. Siga o procedimento da portaria.");
   case "CANCELLED" -> new BusinessProblem("ARRIVAL_CANCELLED",409,"Esta chegada foi encerrada pela portaria.");
   default -> new BusinessProblem("ARRIVAL_ALREADY_DECIDED",409,"Esta chegada já foi respondida.");
  };
 }
 private static String decision(String value) {
  if("APPROVE".equals(value)) return "APPROVED";
  if("DENY".equals(value)) return "DENIED";
  throw invalid("ARRIVAL_DECISION_INVALID","Responda autorizando ou recusando a entrada.");
 }
 private static String visitorName(String value) {
  String name=text(value,160,"VISITOR_NAME_INVALID","Informe o nome do visitante, de 2 a 160 caracteres.");
  if(name.codePointCount(0,name.length())<2) throw invalid("VISITOR_NAME_INVALID","Informe o nome do visitante, de 2 a 160 caracteres.");
  return name;
 }
 /** Só o final do documento, quando a portaria precisar conferir: 2 a 4 letras ou números. */
 private static String document(String value) {
  if(value==null || value.isBlank()) return null;
  String compact=value.strip().toUpperCase(java.util.Locale.ROOT);
  if(!compact.matches("[0-9A-Z]{2,4}")) throw invalid("VISITOR_DOCUMENT_INVALID","Informe só o final do documento, de 2 a 4 letras ou números.");
  return compact;
 }
 private static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 private static String text(String value,int maximum,String code,String message) {
  String normalized=DisplayText.normalize(value,maximum);
  if(normalized==null) throw invalid(code,message);
  return normalized;
 }
 /**
  * Constraints, gatilhos e políticas abortam a transação; traduzir somente depois do rollback do executor e pelo
  * significado de cada operação. Prazo da transação esgotado é resultado incerto (503).
  */
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Map<String,Supplier<BusinessProblem>> conflicts,
   Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,action);
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   var mapped=state==null?null:conflicts.get(state);
   if(mapped!=null) throw mapped.get();
   if("42501".equals(state)) throw new BusinessProblem("VISIT_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("VISIT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
 }
 private static BusinessProblem unitDenied() { return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação."); }
 private static BusinessProblem unitUnavailable() { return new BusinessProblem("ARRIVAL_UNIT_UNAVAILABLE",404,"Unidade não disponível para a chegada."); }
 private static BusinessProblem permitNotFound() { return new BusinessProblem("VISIT_PERMIT_NOT_FOUND",404,"Convite não encontrado."); }
 private static BusinessProblem permitChanged() {
  return new BusinessProblem("VISIT_PERMIT_CHANGED",409,"O convite foi alterado. Atualize os convites antes de continuar.");
 }
 private static BusinessProblem permitInvalid() {
  return new BusinessProblem("ARRIVAL_PERMIT_INVALID",409,"O convite informado não confere com a unidade ou a pessoa. Confira a visita prevista.");
 }
 private static BusinessProblem periodInvalid() {
  return invalid("VISIT_PERIOD_INVALID","O convite começa a partir de agora (até 90 dias à frente), termina no futuro e dura até 7 dias.");
 }
 private static BusinessProblem arrivalNotFound() { return new BusinessProblem("ARRIVAL_NOT_FOUND",404,"Chegada não encontrada."); }
 private static BusinessProblem arrivalClosed() {
  return new BusinessProblem("ARRIVAL_ALREADY_DECIDED",409,"Esta chegada já foi respondida ou o prazo terminou. Atualize a lista.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
