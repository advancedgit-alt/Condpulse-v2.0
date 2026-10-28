package br.com.condpulse.reservations.application;

import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service @Profile("api")
public class ReservationService {
 private static final Instant MINIMUM_QUERY_TIME=Instant.parse("0001-01-01T00:00:00Z");
 private static final Instant MAXIMUM_QUERY_TIME=Instant.parse("9999-12-31T23:59:59.999999Z");
 private final TenantTransactionExecutor transactions;
 private final ReservationStore store;
 public ReservationService(TenantTransactionExecutor transactions,ReservationStore store) {
  this.transactions=transactions; this.store=store;
 }

 public List<ReservationModels.Amenity> amenities(UUID actor,UUID tenant) {
  return execute(actor,tenant,context->{
   if(context.memberships().stream().noneMatch(m->m.canCreateReservation() || m.canCancelReservation()))
    throw new BusinessProblem("RESERVATION_ACCESS_DENIED",403,"Acesso residencial às áreas não disponível para este vínculo.");
   return store.amenities(tenant).stream().map(a->a.view()).toList();
  });
 }
 public ReservationModels.Page reservations(UUID actor,UUID tenant,UUID unit,Instant from,Instant until) {
  requireRange(from,until);
  return execute(actor,tenant,context->{
   requireReadable(context,unit);
   return store.ownReservations(tenant,unit,from,until);
  });
 }
 public ReservationModels.Availability availability(UUID actor,UUID tenant,UUID unit,UUID amenityId,
     Instant from,Instant until) {
  requireRange(from,until);
  // PostgreSQL representa microssegundos. Rejeitar precisao maior evita arredondar a janela silenciosamente.
  if(from.getNano()%1000!=0 || until.getNano()%1000!=0)
   throw new BusinessProblem("RESERVATION_RANGE_INVALID",400,"Use instantes com precisao maxima de microssegundos.");
  return execute(actor,tenant,context->{
   context.requireBookableUnit(unit);
   var amenity=store.amenity(tenant,amenityId);
   requireAvailable(amenity);
   Instant observedAt=store.currentTime();
   return new ReservationModels.Availability(tenant,unit,amenityId,context.timeZone().getId(),from,until,
    observedAt,store.blockedIntervals(tenant,amenityId,from,until));
  });
 }
 public ReservationModels.Mutation create(UUID actor,UUID tenant,String key,ReservationModels.Create request) {
  ReservationRequestHash.requireKey(key);
  String hash=ReservationRequestHash.create(request);
  String route="/v1/tenants/"+tenant+"/reservations";
  return execute(actor,tenant,context->{
   // Reautorizar antes de consultar qualquer resposta idempotente.
   context.requireBookableUnit(request.unitId());
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return new ReservationModels.Mutation(previous.reservation(),true,previous.responseStatus());
   store.lockUnit(tenant,request.unitId());
   var amenity=store.amenity(tenant,request.amenityId());
   requireAvailable(amenity);
   if(request.acceptedPolicyVersion()!=amenity.policyVersion())
    throw new BusinessProblem("AMENITY_POLICY_CHANGED",409,"As regras da área mudaram. Revise-as antes de tentar novamente.");
   Instant now=store.currentTime();
   var window=book(amenity,request,now,context);
   if(store.activeCount(tenant,request.unitId(),now)>=amenity.policy().maximumActivePerUnit())
    throw new BusinessProblem("UNIT_RESERVATION_QUOTA_REACHED",409,"A unidade atingiu o limite de reservas ativas para esta regra.");
   var reservation=store.create(tenant,actor,request,amenity,window,context.timeZone().getId());
   store.record(tenant,actor,reservation,"RESERVATION_CREATED",UUID.randomUUID());
   store.finish(tenant,actor,route,key,reservation,201);
   return new ReservationModels.Mutation(reservation,false,201);
  });
 }
 public ReservationModels.Mutation cancel(UUID actor,UUID tenant,UUID reservationId,String key,ReservationModels.Cancel request) {
  ReservationRequestHash.requireKey(key);
  String hash=ReservationRequestHash.cancel(reservationId,request);
  String route="/v1/tenants/"+tenant+"/reservations/"+reservationId+"/cancel";
  return execute(actor,tenant,context->{
   context.requireCancellableUnit(request.unitId());
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return new ReservationModels.Mutation(previous.reservation(),true,previous.responseStatus());
   // Mesma ordem global da criação: idempotência -> unidade -> linha da reserva.
   store.lockUnit(tenant,request.unitId());
   var stored=store.ownForUpdate(tenant,request.unitId(),actor,reservationId);
   var current=stored.view();
   if("CANCELLED".equals(current.status())) {
    store.finish(tenant,actor,route,key,current,200);
    return new ReservationModels.Mutation(current,false,200);
   }
   if(!"CONFIRMED".equals(current.status()) || current.version()!=request.expectedVersion())
    throw new BusinessProblem("RESERVATION_CHANGED",409,"A reserva foi alterada. Atualize os dados.");
   if(stored.cancellationLeadMinutes()==null)
    throw new BusinessProblem("RESERVATION_RULE_SNAPSHOT_INVALID",503,"As regras aceitas desta reserva precisam ser revisadas.");
   Instant now=store.currentTime();
   if(!now.isBefore(current.startsAt()) || now.isAfter(current.startsAt().minus(Duration.ofMinutes(stored.cancellationLeadMinutes()))))
    throw new BusinessProblem("RESERVATION_CANCELLATION_CLOSED",409,"O prazo de cancelamento desta reserva terminou.");
   var cancelled=store.cancel(tenant,actor,current);
   store.record(tenant,actor,cancelled,"RESERVATION_CANCELLED",UUID.randomUUID());
   store.finish(tenant,actor,route,key,cancelled,200);
   return new ReservationModels.Mutation(cancelled,false,200);
  });
 }
 private br.com.condpulse.reservations.domain.ReservationWindow book(ReservationStore.AmenityData amenity,
     ReservationModels.Create request,Instant now,TenantContext context) {
  try { return amenity.policy().book(request.startsAt(),request.endsAt(),now,context.timeZone(),amenity.bufferMinutes()); }
  catch(IllegalArgumentException | DateTimeException invalid) {
   throw new BusinessProblem("RESERVATION_WINDOW_INVALID",400,"O período não atende às regras da área.");
  }
 }
 private static void requireAvailable(ReservationStore.AmenityData amenity) {
  if(amenity.policy()==null)
   throw new BusinessProblem("AMENITY_POLICY_NOT_CONFIGURED",503,"As regras desta área precisam ser configuradas antes da reserva.");
  if(amenity.approvalRequired())
   throw new BusinessProblem("AMENITY_APPROVAL_UNAVAILABLE",503,"Esta área exige aprovação e ainda não está disponível neste fluxo.");
 }
 private static void requireReadable(TenantContext context,UUID unit) {
  if(unit==null || context.memberships().stream().noneMatch(m->unit.equals(m.unitId()) && (m.canCreateReservation() || m.canCancelReservation())))
   throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
 }
 private static void requireRange(Instant from,Instant until) {
  if(from==null || until==null || from.isBefore(MINIMUM_QUERY_TIME) || until.isAfter(MAXIMUM_QUERY_TIME)
      || !from.isBefore(until) || Duration.between(from,until).compareTo(Duration.ofDays(31))>0)
   throw new BusinessProblem("RESERVATION_RANGE_INVALID",400,"Informe um período de consulta de até 31 dias.");
 }
 private <T> T execute(UUID actor,UUID tenant,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,action); }
  catch(DataAccessException error) {
   // A exclusão GiST aborta a transação. Traduzir somente depois do rollback do executor.
   for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
    if(cause instanceof SQLException sql && "23P01".equals(sql.getSQLState()))
     throw new BusinessProblem("RESERVATION_CONFLICT",409,"O período já está ocupado. Escolha outro horário.");
   }
   throw new BusinessProblem("RESERVATION_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a consulta de reservas. Tente novamente.");
  }
 }
}
