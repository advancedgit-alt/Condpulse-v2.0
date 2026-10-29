package br.com.condpulse.parcels.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.parcels.infrastructure.ParcelStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Encomendas (CP-029/CP-030/CP-031). Recebimento, retirada e anulação exigem a capacidade da portaria (vínculo vigente
 * e AAL2); recebimento e retirada usam chave de idempotência. O recebimento grava a encomenda, a auditoria e a intenção
 * de aviso na mesma transação, e o aviso nunca condiciona o registro. A retirada é identificada (pessoa escolhida entre
 * os vínculos residenciais vigentes da unidade, documento conferido) e única: a segunda tentativa recebe "já retirada".
 * Morador e responsável consultam as encomendas das próprias unidades; dependente só com permissão explícita.
 */
@Service @Profile("api")
public class ParcelService {
 static final Set<String> STATUSES=Set.of("RECEIVED","DELIVERED","VOIDED");
 static final Set<String> UNIT_STATUSES=Set.of("RECEIVED","DELIVERED");
 private final TenantTransactionExecutor transactions;
 private final ParcelStore store;
 private final AuditRecorder audit;
 public ParcelService(TenantTransactionExecutor transactions,ParcelStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 public ParcelModels.Page list(UUID actor,UUID tenant,AssuranceLevel assurance,String status,UUID unit,String location) {
  String filter=status(status,STATUSES);
  String place=location==null || DisplayText.collapse(location).isEmpty()?null
   :text(location,160,"PARCEL_FILTER_INVALID","Use até 160 caracteres visíveis no filtro de local.");
  return execute(actor,tenant,assurance,Map.of(),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   return store.list(tenant,filter,unit,place);
  });
 }
 public List<ParcelModels.UnitOption> units(UUID actor,UUID tenant,AssuranceLevel assurance,String search) {
  String filter=search==null || DisplayText.collapse(search).isEmpty()?null
   :text(search,80,"PARCEL_FILTER_INVALID","Use até 80 caracteres visíveis na busca de unidade.");
  return execute(actor,tenant,assurance,Map.of(),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   return store.units(tenant,filter);
  });
 }
 /** Detalhe para a portaria ou para quem vê as encomendas da unidade; fora disso (e anulada), "não encontrada". */
 public ParcelModels.Parcel parcel(UUID actor,UUID tenant,AssuranceLevel assurance,UUID parcelId) {
  if(parcelId==null) throw notFound();
  return execute(actor,tenant,assurance,Map.of(),context->{
   boolean concierge=context.effectiveCapabilities().contains(StaffCapability.OPERATE_CONCIERGE);
   var parcel=store.parcel(tenant,parcelId,false);
   if(parcel==null || !concierge && (!residentOf(context,parcel.unitId()) || "VOIDED".equals(parcel.status()))) throw notFound();
   return parcel;
  });
 }
 public ParcelModels.Page unitParcels(UUID actor,UUID tenant,UUID unit,String status) {
  String filter=status(status,UNIT_STATUSES);
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   if(!residentOf(context,unit)) throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
   return store.unitParcels(tenant,unit,filter);
  });
 }
 /** Pessoas que podem retirar: a portaria escolhe uma delas e confere o documento. */
 public List<ParcelModels.Recipient> recipients(UUID actor,UUID tenant,AssuranceLevel assurance,UUID parcelId) {
  if(parcelId==null) throw notFound();
  return execute(actor,tenant,assurance,Map.of(),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   if(store.parcel(tenant,parcelId,false)==null) throw notFound();
   return store.recipients(tenant,parcelId);
  });
 }

 public ParcelModels.Mutation receive(UUID actor,UUID tenant,AssuranceLevel assurance,String key,ParcelModels.Receive request) {
  requireKey(key);
  if(request==null || request.unitId()==null) throw invalid("PARCEL_UNIT_REQUIRED","Informe a unidade da encomenda.");
  int quantity=request.quantity()==null?0:request.quantity();
  if(quantity<1 || quantity>1000) throw invalid("PARCEL_QUANTITY_INVALID","Informe a quantidade de volumes, de 1 a 1000.");
  String description=request.description()==null || DisplayText.collapse(request.description()).isEmpty()?""
   :text(request.description(),500,"PARCEL_DESCRIPTION_INVALID","Use até 500 caracteres visíveis na descrição.");
  String location=text(request.storageLocation(),160,"PARCEL_LOCATION_REQUIRED","Informe o local de guarda, com até 160 caracteres.");
  boolean confirmed=Boolean.TRUE.equals(request.confirmDuplicate());
  String hash=ParcelRequestHash.receive(request.unitId(),quantity,description,location,confirmed);
  String route="/v1/tenants/"+tenant+"/parcels";
  return execute(actor,tenant,assurance,Map.of("23503",ParcelService::unavailableUnit),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return new ParcelModels.Mutation(previous.parcel(),true,previous.responseStatus());
   if(!store.activeUnit(tenant,request.unitId())) throw unavailableUnit();
   // Duplicidade suspeita (POR-02): mesmo pacote registrado há pouco; a portaria confere e confirma se for outro.
   if(!confirmed && store.similarRecent(tenant,request.unitId(),quantity,description,location))
    throw new BusinessProblem("PARCEL_DUPLICATE_SUSPECTED",409,
     "Há uma encomenda igual registrada para esta unidade nos últimos 10 minutos. Confira antes de registrar outra.");
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,request.unitId(),quantity,description,location,actor);
   var parcel=store.parcel(tenant,id,false);
   if(parcel==null) throw new IllegalStateException("Encomenda recebida não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"PARCEL_RECEIVED","PARCEL",id,null);
   store.event(tenant,"PARCEL_RECEIVED",parcel);
   store.finish(tenant,actor,route,key,parcel,201);
   return new ParcelModels.Mutation(parcel,false,201);
  });
 }
 public ParcelModels.Mutation deliver(UUID actor,UUID tenant,AssuranceLevel assurance,UUID parcelId,String key,ParcelModels.Deliver request) {
  requireKey(key);
  if(parcelId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("PARCEL_VERSION_REQUIRED","Atualize a lista antes de registrar a entrega.");
  if(request.recipientMembershipId()==null) throw invalid("PARCEL_RECIPIENT_REQUIRED","Escolha a pessoa que retira a encomenda.");
  if(!Boolean.TRUE.equals(request.identityChecked()))
   throw invalid("PARCEL_IDENTITY_CHECK_REQUIRED","Confirme que conferiu o documento da pessoa que retira.");
  String hash=ParcelRequestHash.deliver(parcelId,request.recipientMembershipId(),request.expectedVersion());
  String route="/v1/tenants/"+tenant+"/parcels/"+parcelId+"/handoff";
  return execute(actor,tenant,assurance,Map.of("23505",ParcelService::delivered,"23514",ParcelService::changed),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return new ParcelModels.Mutation(previous.parcel(),true,previous.responseStatus());
   var current=store.parcel(tenant,parcelId,true);
   if(current==null) throw notFound();
   if("DELIVERED".equals(current.status())) throw delivered();
   if("VOIDED".equals(current.status())) throw voided();
   if(current.version()!=request.expectedVersion()) throw changed();
   var recipient=store.recipient(tenant,parcelId,request.recipientMembershipId());
   if(recipient==null)
    throw new BusinessProblem("PARCEL_RECIPIENT_NOT_ALLOWED",409,
     "A pessoa escolhida não pode retirar encomendas desta unidade. Atualize a lista de pessoas.");
   if(!store.deliver(tenant,parcelId,request.expectedVersion(),actor,recipient)) throw changed();
   var delivered=store.parcel(tenant,parcelId,false);
   if(delivered==null) throw new IllegalStateException("Encomenda entregue não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"PARCEL_DELIVERED","PARCEL",parcelId,null);
   store.event(tenant,"PARCEL_DELIVERED",delivered);
   store.finish(tenant,actor,route,key,delivered,200);
   return new ParcelModels.Mutation(delivered,false,200);
  });
 }
 /**
  * Registro indevido (unidade errada, duplicidade): a encomenda ainda a retirar fica anulada pela versão lida, com
  * motivo na auditoria e chave de idempotência; sai da lista da unidade e não pode mais ser retirada. Nada é apagado.
  */
 public ParcelModels.Mutation voidParcel(UUID actor,UUID tenant,AssuranceLevel assurance,UUID parcelId,String key,
   ParcelModels.VoidRequest request) {
  requireKey(key);
  if(parcelId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("PARCEL_VERSION_REQUIRED","Atualize a lista antes de anular o registro.");
  String reason=text(request.reason(),500,"PARCEL_VOID_REASON_REQUIRED","Informe o motivo da anulação, com até 500 caracteres.");
  String hash=ParcelRequestHash.voidParcel(parcelId,reason,request.expectedVersion());
  String route="/v1/tenants/"+tenant+"/parcels/"+parcelId+"/void";
  return execute(actor,tenant,assurance,Map.of("23514",ParcelService::changed),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return new ParcelModels.Mutation(previous.parcel(),true,previous.responseStatus());
   var current=store.parcel(tenant,parcelId,true);
   if(current==null) throw notFound();
   if("DELIVERED".equals(current.status())) throw delivered();
   if("VOIDED".equals(current.status())) throw voided();
   if(current.version()!=request.expectedVersion() || !store.voidParcel(tenant,parcelId,request.expectedVersion(),actor,reason))
    throw changed();
   var voided=store.parcel(tenant,parcelId,false);
   if(voided==null) throw new IllegalStateException("Encomenda anulada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"PARCEL_VOIDED","PARCEL",parcelId,reason);
   store.event(tenant,"PARCEL_VOIDED",voided);
   store.finish(tenant,actor,route,key,voided,200);
   return new ParcelModels.Mutation(voided,false,200);
  });
 }

 /** Vínculo vigente que vê as encomendas da unidade (o banco repete a regra na política de leitura). */
 static boolean residentOf(TenantContext context,UUID unit) {
  return unit!=null && context.memberships().stream().anyMatch(m->unit.equals(m.unitId()) && m.canViewParcels());
 }
 private static String status(String value,Set<String> allowed) {
  if(value==null) return null;
  if(!allowed.contains(value)) throw invalid("PARCEL_STATUS_INVALID","Situação de encomenda inválida.");
  return value;
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
  * Constraints e o gatilho diferido abortam a transação; traduzir somente depois do rollback do executor e pelo
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
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }
 private static BusinessProblem delivered() { return new BusinessProblem("PARCEL_ALREADY_DELIVERED",409,"Esta encomenda já foi retirada."); }
 private static BusinessProblem changed() { return new BusinessProblem("PARCEL_CHANGED",409,"A encomenda foi alterada. Atualize a lista."); }
 private static BusinessProblem voided() {
  return new BusinessProblem("PARCEL_VOIDED",409,"Este registro de encomenda foi anulado e não está mais para retirada.");
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("PARCEL_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
 }
 private static BusinessProblem unavailableUnit() {
  return new BusinessProblem("PARCEL_UNIT_UNAVAILABLE",404,"Unidade não disponível para recebimento.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("PARCEL_NOT_FOUND",404,"Encomenda não encontrada."); }
}
