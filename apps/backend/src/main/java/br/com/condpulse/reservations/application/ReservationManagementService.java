package br.com.condpulse.reservations.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.reservations.infrastructure.ReservationManagementStore;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Decisão de pedidos, cancelamento pela gestão e bloqueio de manutenção (CP-023). Toda mutação exige AAL2,
 * capacidade do papel vigente, versão lida e grava auditoria e evento na mesma transação. Decisão repetida
 * devolve o estado atual sem nova versão, auditoria ou evento.
 */
@Service @Profile("api")
public class ReservationManagementService {
 private static final Set<String> STATUSES=Set.of("HELD","PENDING_APPROVAL","CONFIRMED","REJECTED","CANCELLED","EXPIRED");
 private static final Instant MINIMUM_TIME=Instant.parse("2000-01-01T00:00:00Z");
 private static final Instant MAXIMUM_TIME=Instant.parse("9999-12-31T23:59:59Z");
 private static final Duration MAXIMUM_QUERY=Duration.ofDays(366);
 private static final Duration MAXIMUM_BLOCK=Duration.ofDays(366);
 /** Tolerância para o relógio do cliente ao bloquear "a partir de agora". */
 private static final Duration BLOCK_START_TOLERANCE=Duration.ofMinutes(5);
 private static final Duration BLOCK_HORIZON=Duration.ofDays(730);
 private final TenantTransactionExecutor transactions;
 private final ReservationStore reservations;
 private final ReservationManagementStore store;
 private final AuditRecorder audit;
 public ReservationManagementService(TenantTransactionExecutor transactions,ReservationStore reservations,
     ReservationManagementStore store,AuditRecorder audit) {
  this.transactions=transactions; this.reservations=reservations; this.store=store; this.audit=audit;
 }

 public ReservationManagementModels.ManagedReservationPage reservations(UUID actor,UUID tenant,AssuranceLevel assurance,
     String status,Instant from,Instant until) {
  if(status!=null && !STATUSES.contains(status))
   throw new BusinessProblem("RESERVATION_STATUS_INVALID",400,"Situação de reserva inválida.");
  if((from==null)!=(until==null) || from!=null && (from.isBefore(MINIMUM_TIME) || until.isAfter(MAXIMUM_TIME)
      || !from.isBefore(until) || Duration.between(from,until).compareTo(MAXIMUM_QUERY)>0))
   throw new BusinessProblem("RESERVATION_RANGE_INVALID",400,"Informe início e fim de um período de até 366 dias.");
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.DECIDE_RESERVATIONS);
   return store.reservations(tenant,status,from,until);
  });
 }

 public ReservationManagementModels.ManagedReservation decide(UUID actor,UUID tenant,AssuranceLevel assurance,
     UUID reservationId,ReservationManagementModels.Decision request) {
  if(reservationId==null || request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw versionRequired();
  boolean approve="APPROVE".equals(request.decision());
  if(!approve && !"REJECT".equals(request.decision()))
   throw new BusinessProblem("RESERVATION_DECISION_INVALID",400,"Informe APPROVE ou REJECT.");
  String reason=reason(request.reason(),!approve);
  long expected=request.expectedVersion();
  String target=approve?"CONFIRMED":"REJECTED";
  var outcome=execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.DECIDE_RESERVATIONS);
   var row=store.lock(tenant,reservationId);
   if(row==null || !"BOOKING".equals(row.kind())) throw notFound();
   if(target.equals(row.status())) return new Outcome(required(store.reservation(tenant,reservationId)),false);
   if(!"PENDING_APPROVAL".equals(row.status()) || row.version()!=expected) throw changed();
   if(row.deadlinePassed()) {
    var expired=store.expire(tenant,reservationId,expected);
    if(expired==null) throw changed();
    reservations.event(tenant,"RESERVATION_EXPIRED",expired);
    return new Outcome(null,true);
   }
   if(approve) {
    if(!row.amenityActive())
     throw new BusinessProblem("AMENITY_INACTIVE",409,"A área está inativa. Rejeite o pedido informando o motivo.");
    if(!"ACTIVE".equals(row.unitStatus()))
     throw new BusinessProblem("RESERVATION_UNIT_INACTIVE",409,"A unidade do pedido está inativa. Rejeite o pedido informando o motivo.");
    if(!store.requesterEligible(tenant,row.createdBy(),row.unitId()))
     throw new BusinessProblem("RESERVATION_REQUESTER_INACTIVE",409,"O solicitante não tem mais vínculo ativo para reservar por esta unidade.");
   }
   var decided=store.decide(tenant,reservationId,expected,target,reason);
   if(decided==null) throw changed();
   audit.record(tenant,actor,approve?"RESERVATION_APPROVED":"RESERVATION_REJECTED","RESERVATION",reservationId,reason);
   reservations.event(tenant,approve?"RESERVATION_CONFIRMED":"RESERVATION_REJECTED",decided);
   return new Outcome(required(store.reservation(tenant,reservationId)),false);
  });
  // A expiração já foi gravada pelo commit acima; somente então a decisão tardia é recusada (AC-013).
  if(outcome.expired())
   throw new BusinessProblem("RESERVATION_DECISION_EXPIRED",409,
    "O prazo de decisão terminou. O pedido expirou e a unidade precisa solicitar novamente.");
  return outcome.reservation();
 }

 public ReservationManagementModels.ManagedReservation cancel(UUID actor,UUID tenant,AssuranceLevel assurance,
     UUID reservationId,ReservationManagementModels.Cancellation request) {
  if(reservationId==null || request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw versionRequired();
  String reason=reason(request.reason(),true);
  long expected=request.expectedVersion();
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.DECIDE_RESERVATIONS);
   var row=store.lock(tenant,reservationId);
   if(row==null) throw notFound();
   boolean block="BLOCK".equals(row.kind());
   if(block) context.requireCapability(StaffCapability.MANAGE_AMENITIES);
   if("CANCELLED".equals(row.status())) return required(store.reservation(tenant,reservationId));
   if(row.version()!=expected || row.deadlinePassed()
      || !("CONFIRMED".equals(row.status()) || "PENDING_APPROVAL".equals(row.status())))
    throw changed();
   if(!reservations.currentTime().isBefore(row.endsAt()))
    throw new BusinessProblem("RESERVATION_CANCELLATION_CLOSED",409,"O período desta reserva já terminou.");
   var cancelled=store.cancel(tenant,reservationId,expected,reason);
   if(cancelled==null) throw changed();
   audit.record(tenant,actor,block?"AMENITY_BLOCK_CANCELLED":"RESERVATION_CANCELLED_BY_MANAGEMENT","RESERVATION",
    reservationId,reason);
   // Avisos e a revogação de convites vinculados (módulo de visitantes, ainda não implementado) partem deste evento.
   reservations.event(tenant,block?"AMENITY_BLOCK_CANCELLED":"RESERVATION_CANCELLED",cancelled);
   return required(store.reservation(tenant,reservationId));
  });
 }

 public ReservationManagementModels.BlockMutation createBlock(UUID actor,UUID tenant,AssuranceLevel assurance,UUID amenityId,
     String key,ReservationManagementModels.CreateBlock request) {
  ReservationRequestHash.requireKey(key);
  if(amenityId==null || request==null || request.startsAt()==null || request.endsAt()==null) throw blockInvalid();
  Instant startsAt=request.startsAt(); Instant endsAt=request.endsAt();
  if(startsAt.getNano()%1000!=0 || endsAt.getNano()%1000!=0 || !startsAt.isBefore(endsAt)
     || startsAt.isBefore(MINIMUM_TIME) || endsAt.isAfter(MAXIMUM_TIME)
     || Duration.between(startsAt,endsAt).compareTo(MAXIMUM_BLOCK)>0)
   throw blockInvalid();
  String reason=reason(request.reason(),true);
  String hash=ReservationRequestHash.block(amenityId,startsAt,endsAt,reason);
  String route="/v1/tenants/"+tenant+"/management/amenities/"+amenityId+"/maintenance-blocks";
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_AMENITIES);
   var previous=reservations.begin(tenant,actor,route,key,hash);
   if(!previous.fresh())
    return new ReservationManagementModels.BlockMutation(
     required(store.reservation(tenant,previous.reservation().reservationId())),true,previous.responseStatus());
   Boolean active=store.amenityActive(tenant,amenityId);
   if(active==null) throw new BusinessProblem("AMENITY_NOT_FOUND",404,"Área não encontrada neste condomínio.");
   if(!active) throw new BusinessProblem("AMENITY_INACTIVE",409,"A área está inativa.");
   Instant now=reservations.currentTime();
   if(startsAt.isBefore(now.minus(BLOCK_START_TOLERANCE)) || startsAt.isAfter(now.plus(BLOCK_HORIZON))) throw blockInvalid();
   // Pedidos vencidos no período deixam de ocupar; reservas e pedidos válidos exigem decisão explícita antes.
   reservations.expireStale(tenant,null,amenityId,startsAt,endsAt);
   if(store.occupied(tenant,amenityId,startsAt,endsAt)>0) throw blockConflict();
   var created=store.insertBlock(tenant,actor,amenityId,startsAt,endsAt,reason);
   audit.record(tenant,actor,"AMENITY_BLOCK_CREATED","RESERVATION",created.reservationId(),reason);
   reservations.event(tenant,"AMENITY_BLOCK_CREATED",created);
   reservations.finish(tenant,actor,route,key,created,201);
   return new ReservationManagementModels.BlockMutation(required(store.reservation(tenant,created.reservationId())),false,201);
  });
 }

 private record Outcome(ReservationManagementModels.ManagedReservation reservation,boolean expired) {}
 static String reason(String value,boolean required) {
  String reason=value==null?null:value.strip();
  if(reason!=null && reason.isEmpty()) reason=null;
  if(reason==null && required) throw new BusinessProblem("RESERVATION_REASON_REQUIRED",400,"Informe o motivo.");
  if(reason!=null && reason.length()>500)
   throw new BusinessProblem("RESERVATION_REASON_INVALID",400,"O motivo deve ter até 500 caracteres.");
  return reason;
 }
 private static ReservationManagementModels.ManagedReservation required(ReservationManagementModels.ManagedReservation value) {
  if(value==null) throw new IllegalStateException("Reserva alterada não foi encontrada na mesma transação.");
  return value;
 }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,assurance,action); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23P01".equals(state)) throw blockConflict();
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   if("23514".equals(state)) throw new BusinessProblem("RESERVATION_INPUT_INVALID",400,"Os dados informados não atendem às regras.");
   throw new BusinessProblem("MANAGEMENT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
  }
 }
 private static BusinessProblem versionRequired() {
  return new BusinessProblem("RESERVATION_VERSION_REQUIRED",400,"Informe a versão lida da reserva.");
 }
 private static BusinessProblem notFound() {
  return new BusinessProblem("RESERVATION_NOT_FOUND",404,"Reserva não encontrada neste condomínio.");
 }
 private static BusinessProblem changed() {
  return new BusinessProblem("RESERVATION_CHANGED",409,"A reserva foi alterada. Atualize os dados antes de decidir.");
 }
 private static BusinessProblem blockInvalid() {
  return new BusinessProblem("AMENITY_BLOCK_WINDOW_INVALID",400,
   "Informe início a partir de agora e fim posterior, em período de até 366 dias, com precisão máxima de microssegundos.");
 }
 private static BusinessProblem blockConflict() {
  return new BusinessProblem("AMENITY_BLOCK_CONFLICT",409,
   "Há reservas ou pedidos no período. Decida ou cancele cada um com motivo antes de bloquear.");
 }
}
