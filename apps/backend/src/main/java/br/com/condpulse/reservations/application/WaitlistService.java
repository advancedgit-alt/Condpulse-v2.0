package br.com.condpulse.reservations.application;

import java.time.DateTimeException;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.reservations.infrastructure.WaitlistStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Fila de espera (CP-040, PRD §10): entrar num período ocupado com as regras vigentes, sair, aceitar ou recusar a
 * oferta. A oferta em si (retenção HELD de até 15 minutos ao primeiro da fila) roda em transação própria pela
 * {@link WaitlistOffers}, depois do comando que liberou o período e antes das consultas da própria fila.
 */
@Service @Profile("api")
public class WaitlistService {
 private final TenantTransactionExecutor transactions;
 private final ReservationStore reservations;
 private final WaitlistStore store;
 private final AuditRecorder audit;
 private final WaitlistOffers offers;
 public WaitlistService(TenantTransactionExecutor transactions,ReservationStore reservations,WaitlistStore store,
     AuditRecorder audit,WaitlistOffers offers) {
  this.transactions=transactions; this.reservations=reservations; this.store=store; this.audit=audit; this.offers=offers;
 }

 public WaitlistModels.Page unitEntries(UUID actor,UUID tenant,UUID unit) {
  // Antes de listar: ofertas vencidas avançam a fila e períodos liberados são oferecidos (nas áreas desta unidade).
  var amenities=execute(actor,tenant,context->{
   requireReadable(context,unit);
   return store.unitEntries(tenant,unit).entries().stream().filter(e->"WAITING".equals(e.state()) || "OFFERED".equals(e.state())
     || "EXPIRED".equals(e.state())).map(WaitlistModels.Entry::amenityId).distinct().toList();
  });
  for(UUID amenity:amenities) offers.offerForAmenity(actor,tenant,amenity);
  return execute(actor,tenant,context->{
   requireReadable(context,unit);
   return store.unitEntries(tenant,unit);
  });
 }

 public WaitlistModels.Mutation join(UUID actor,UUID tenant,String key,WaitlistModels.Join request) {
  ReservationRequestHash.requireKey(key);
  if(request==null || request.unitId()==null || request.amenityId()==null || request.startsAt()==null || request.endsAt()==null
     || request.acceptedPolicyVersion()<1) throw new BusinessProblem("WAITLIST_REQUEST_INVALID",400,"Revise os dados da fila de espera.");
  String hash=ReservationRequestHash.create(new ReservationModels.Create(request.unitId(),request.amenityId(),request.startsAt(),
   request.endsAt(),request.acceptedPolicyVersion()));
  String route="/v1/tenants/"+tenant+"/waitlist";
  // Antes de avaliar o período: oferta vencida ou período liberado vai primeiro para quem já estava na fila.
  offers.offerForAmenity(actor,tenant,request.amenityId());
  return execute(actor,tenant,context->{
   context.requireBookableUnit(request.unitId());
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.entry(tenant,previous.resource(),false);
    if(replay==null) throw notFound();
    return new WaitlistModels.Mutation(replay,true,previous.responseStatus());
   }
   reservations.lockUnit(tenant,request.unitId());
   var amenity=reservations.amenity(tenant,request.amenityId());
   if(amenity.policy()==null)
    throw new BusinessProblem("AMENITY_POLICY_NOT_CONFIGURED",503,"As regras desta área precisam ser configuradas antes da reserva.");
   if(request.acceptedPolicyVersion()!=amenity.policyVersion())
    throw new BusinessProblem("AMENITY_POLICY_CHANGED",409,"As regras da área mudaram. Revise-as antes de tentar novamente.");
   br.com.condpulse.reservations.domain.ReservationWindow window;
   try { window=amenity.policy().book(request.startsAt(),request.endsAt(),reservations.currentTime(),context.timeZone(),amenity.bufferMinutes()); }
   catch(IllegalArgumentException | DateTimeException invalid) {
    throw new BusinessProblem("RESERVATION_WINDOW_INVALID",400,"O período não atende às regras da área.");
   }
   if(store.ownOccupation(tenant,request.unitId(),request.amenityId(),window))
    throw new BusinessProblem("WAITLIST_OWN_RESERVATION",409,"A unidade já tem reserva ou pedido neste período.");
   if(!store.occupied(tenant,request.amenityId(),window))
    throw new BusinessProblem("WAITLIST_SLOT_AVAILABLE",409,"O período está livre. Faça a reserva diretamente.");
   if(store.openEntries(tenant,request.unitId())>=WaitlistStore.OPEN_LIMIT)
    throw new BusinessProblem("WAITLIST_LIMIT_REACHED",409,"A unidade já está em "+WaitlistStore.OPEN_LIMIT+" filas de espera abertas.");
   UUID id=UUID.randomUUID();
   if(!store.insert(id,tenant,actor,request.unitId(),amenity,window,context.timeZone().getId()))
    throw new BusinessProblem("AMENITY_POLICY_CHANGED",409,"As regras da área mudaram. Revise-as antes de tentar novamente.");
   var created=required(store.entry(tenant,id,false));
   audit.record(tenant,actor,"WAITLIST_JOINED","RESERVATION_WAITLIST",id,null);
   store.event(tenant,"WAITLIST_JOINED",created);
   store.finish(tenant,actor,route,key,id,201);
   return new WaitlistModels.Mutation(created,false,201);
  });
 }

 /** Sair da fila (entrada aguardando) ou recusar a oferta (retenção em vigor): só quem entrou na fila. */
 public WaitlistModels.Entry leave(UUID actor,UUID tenant,UUID id,WaitlistModels.Change request) {
  requireChange(id,request);
  var result=execute(actor,tenant,context->{
   context.requireCancellableUnit(request.unitId());
   reservations.lockUnit(tenant,request.unitId());
   var current=owned(tenant,id,request.unitId(),actor);
   if("LEFT".equals(current.state())) return current;
   if("EXPIRED".equals(current.state())) throw offerExpired();
   if(current.version()!=request.expectedVersion()) throw changed();
   if("WAITING".equals(current.state())) {
    if(!store.leave(tenant,id,request.expectedVersion())) throw changed();
   } else if("OFFERED".equals(current.state())) {
    if(!store.declineOffer(tenant,current.offerReservationId())) throw offerExpired();
   } else throw closed(current);
   var left=required(store.entry(tenant,id,false));
   audit.record(tenant,actor,"OFFERED".equals(current.state())?"WAITLIST_OFFER_DECLINED":"WAITLIST_LEFT","RESERVATION_WAITLIST",id,null);
   store.event(tenant,"WAITLIST_LEFT",left);
   return left;
  });
  // A retenção recusada libera o período: o próximo da fila recebe a oferta depois do commit.
  if(result.offerReservationId()!=null) offers.offerForAmenity(actor,tenant,result.amenityId());
  return result;
 }

 public WaitlistModels.Acceptance accept(UUID actor,UUID tenant,UUID id,String key,WaitlistModels.Change request) {
  ReservationRequestHash.requireKey(key);
  requireChange(id,request);
  String route="/v1/tenants/"+tenant+"/waitlist/"+id+"/accept";
  String hash=ReservationRequestHash.cancel(id,new ReservationModels.Cancel(request.unitId(),request.expectedVersion()));
  return execute(actor,tenant,context->{
   context.requireBookableUnit(request.unitId());
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=required(store.entry(tenant,id,false));
    return new WaitlistModels.Acceptance(replay,store.reservation(tenant,previous.resource()));
   }
   // Mesma ordem dos comandos de reserva: unidade, depois as linhas (entrada e retenção).
   reservations.lockUnit(tenant,request.unitId());
   var current=owned(tenant,id,request.unitId(),actor);
   if("EXPIRED".equals(current.state())) throw offerExpired();
   if(!"OFFERED".equals(current.state())) throw "WAITING".equals(current.state())?changed():closed(current);
   if(current.version()!=request.expectedVersion()) throw changed();
   if(!store.heldOfferActive(tenant,current.offerReservationId())) throw offerExpired();
   Integer quota=store.acceptedQuota(tenant,id);
   if(quota==null) throw new BusinessProblem("RESERVATION_RULE_SNAPSHOT_INVALID",503,"As regras aceitas desta entrada precisam ser revisadas.");
   // A retenção da própria oferta já conta como ativa: as demais reservas ativas precisam caber na cota aceita.
   if(reservations.activeCount(tenant,request.unitId(),reservations.currentTime())-1>=quota)
    throw new BusinessProblem("UNIT_RESERVATION_QUOTA_REACHED",409,"A unidade atingiu o limite de reservas ativas para esta regra.");
   String status=store.accept(tenant,id,request.expectedVersion());
   var reservation=store.reservation(tenant,current.offerReservationId());
   if(!status.equals(reservation.status())) throw new IllegalStateException("Estado da reserva aceita divergente.");
   var accepted=required(store.entry(tenant,id,false));
   audit.record(tenant,actor,"WAITLIST_OFFER_ACCEPTED","RESERVATION_WAITLIST",id,null);
   reservations.record(tenant,actor,reservation,"PENDING_APPROVAL".equals(status)?"RESERVATION_REQUESTED":"RESERVATION_CREATED",UUID.randomUUID());
   store.event(tenant,"WAITLIST_ACCEPTED",accepted);
   store.finish(tenant,actor,route,key,reservation.reservationId(),200);
   return new WaitlistModels.Acceptance(accepted,reservation);
  });
 }

 private WaitlistModels.Entry owned(UUID tenant,UUID id,UUID unit,UUID actor) {
  var current=store.entry(tenant,id,true);
  if(current==null || !unit.equals(current.unitId())) throw notFound();
  if(!current.createdByMe())
   throw new BusinessProblem("WAITLIST_AUTHOR_REQUIRED",403,"Somente quem entrou na fila pode alterar esta entrada.");
  return current;
 }
 private static void requireChange(UUID id,WaitlistModels.Change request) {
  if(id==null || request==null || request.unitId()==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw new BusinessProblem("WAITLIST_VERSION_REQUIRED",400,"Atualize a fila de espera antes de continuar.");
 }
 private static void requireReadable(TenantContext context,UUID unit) {
  if(unit==null || context.memberships().stream().noneMatch(m->unit.equals(m.unitId()) && (m.canCreateReservation() || m.canCancelReservation())))
   throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
 }
 private static WaitlistModels.Entry required(WaitlistModels.Entry entry) {
  if(entry==null) throw new IllegalStateException("Entrada da fila não encontrada na mesma transação.");
  return entry;
 }
 private static BusinessProblem notFound() { return new BusinessProblem("WAITLIST_NOT_FOUND",404,"Entrada da fila não encontrada."); }
 private static BusinessProblem changed() {
  return new BusinessProblem("WAITLIST_CHANGED",409,"A fila de espera mudou. Atualize antes de continuar.");
 }
 private static BusinessProblem offerExpired() {
  return new BusinessProblem("WAITLIST_OFFER_EXPIRED",409,"O prazo da oferta terminou e a vaga seguiu para o próximo da fila.");
 }
 private static BusinessProblem closed(WaitlistModels.Entry entry) {
  return new BusinessProblem("WAITLIST_CLOSED",409,"Esta entrada da fila já foi encerrada.");
 }
 private <T> T execute(UUID actor,UUID tenant,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,action); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23505".equals(state)) throw new BusinessProblem("WAITLIST_ALREADY_JOINED",409,"A unidade já está na fila deste período.");
   if("23P01".equals(state)) throw new BusinessProblem("RESERVATION_CONFLICT",409,"O período já está ocupado. Escolha outro horário.");
   if("23514".equals(state)) throw new BusinessProblem("WAITLIST_CHANGED",409,"A fila de espera mudou. Atualize antes de continuar.");
   if("42501".equals(state)) throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
   throw new BusinessProblem("RESERVATION_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a consulta de reservas. Tente novamente.");
  }
 }
}
