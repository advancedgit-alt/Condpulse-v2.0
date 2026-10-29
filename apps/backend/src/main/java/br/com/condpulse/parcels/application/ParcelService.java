package br.com.condpulse.parcels.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

/**
 * Encomendas (CP-029/CP-030/CP-031). Recebimento e entrega exigem a capacidade da portaria (vínculo vigente e AAL2) e
 * chave de idempotência; o recebimento grava a encomenda, a auditoria e a intenção de aviso na mesma transação, e o
 * aviso nunca condiciona o registro. A entrega é única: a segunda tentativa recebe "já entregue" com dados mínimos.
 * Morador, responsável e dependente consultam somente as encomendas das próprias unidades.
 */
@Service @Profile("api")
public class ParcelService {
 static final Set<String> STATUSES=Set.of("RECEIVED","DELIVERED");
 private final TenantTransactionExecutor transactions;
 private final ParcelStore store;
 private final AuditRecorder audit;
 public ParcelService(TenantTransactionExecutor transactions,ParcelStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 public ParcelModels.Page list(UUID actor,UUID tenant,AssuranceLevel assurance,String status,UUID unit,String location) {
  String filter=status(status);
  String place=location==null || DisplayText.collapse(location).isEmpty()?null
   :text(location,160,"PARCEL_FILTER_INVALID","Use até 160 caracteres visíveis no filtro de local.");
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   return store.list(tenant,filter,unit,place);
  });
 }
 public List<ParcelModels.UnitOption> units(UUID actor,UUID tenant,AssuranceLevel assurance,String search) {
  String filter=search==null || DisplayText.collapse(search).isEmpty()?null
   :text(search,80,"PARCEL_FILTER_INVALID","Use até 80 caracteres visíveis na busca de unidade.");
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   return store.units(tenant,filter);
  });
 }
 /** Detalhe para a portaria ou para quem tem vínculo residencial com a unidade; fora disso, "não encontrada". */
 public ParcelModels.Parcel parcel(UUID actor,UUID tenant,AssuranceLevel assurance,UUID parcelId) {
  if(parcelId==null) throw notFound();
  return execute(actor,tenant,assurance,context->{
   boolean concierge=context.effectiveCapabilities().contains(StaffCapability.OPERATE_CONCIERGE);
   var parcel=store.parcel(tenant,parcelId,false);
   if(parcel==null || !concierge && !residentOf(context,parcel.unitId())) throw notFound();
   return parcel;
  });
 }
 public ParcelModels.Page unitParcels(UUID actor,UUID tenant,UUID unit,String status) {
  String filter=status(status);
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   if(!residentOf(context,unit)) throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
   return store.unitParcels(tenant,unit,filter);
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
  String hash=ParcelRequestHash.receive(request.unitId(),quantity,description,location);
  String route="/v1/tenants/"+tenant+"/parcels";
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return new ParcelModels.Mutation(previous.parcel(),true,previous.responseStatus());
   if(!store.activeUnit(tenant,request.unitId()))
    throw new BusinessProblem("PARCEL_UNIT_UNAVAILABLE",404,"Unidade não disponível para recebimento.");
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
  String recipient=text(request.recipientName(),160,"PARCEL_RECIPIENT_REQUIRED","Informe o nome de quem retira, com até 160 caracteres.");
  if(!Boolean.TRUE.equals(request.identityChecked()))
   throw invalid("PARCEL_IDENTITY_CHECK_REQUIRED","Confirme que conferiu o documento da pessoa que retira.");
  String hash=ParcelRequestHash.deliver(parcelId,recipient,request.expectedVersion());
  String route="/v1/tenants/"+tenant+"/parcels/"+parcelId+"/handoff";
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return new ParcelModels.Mutation(previous.parcel(),true,previous.responseStatus());
   var current=store.parcel(tenant,parcelId,true);
   if(current==null) throw notFound();
   if("DELIVERED".equals(current.status()))
    throw new BusinessProblem("PARCEL_ALREADY_DELIVERED",409,"Esta encomenda já foi retirada.");
   if(current.version()!=request.expectedVersion() || !store.deliver(tenant,parcelId,request.expectedVersion(),actor,recipient))
    throw new BusinessProblem("PARCEL_CHANGED",409,"A encomenda foi alterada. Atualize a lista.");
   var delivered=store.parcel(tenant,parcelId,false);
   if(delivered==null) throw new IllegalStateException("Encomenda entregue não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"PARCEL_DELIVERED","PARCEL",parcelId,null);
   store.event(tenant,"PARCEL_DELIVERED",delivered);
   store.finish(tenant,actor,route,key,delivered,200);
   return new ParcelModels.Mutation(delivered,false,200);
  });
 }

 /** Vínculo residencial vigente com a unidade (o banco repete a regra na política de leitura). */
 static boolean residentOf(TenantContext context,UUID unit) {
  return unit!=null && context.memberships().stream().anyMatch(m->unit.equals(m.unitId())
   && ("RESIDENT".equals(m.role()) || "RESPONSIBLE".equals(m.role()) || "DEPENDENT".equals(m.role())));
 }
 private static String status(String value) {
  if(value==null) return null;
  if(!STATUSES.contains(value)) throw invalid("PARCEL_STATUS_INVALID","Situação de encomenda inválida.");
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
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,action);
  } catch(DataAccessException error) {
   // Constraints e o gatilho diferido abortam a transação; traduzir somente depois do rollback do executor.
   String state=SqlStates.of(error);
   if("23505".equals(state)) throw new BusinessProblem("PARCEL_ALREADY_DELIVERED",409,"Esta encomenda já foi retirada.");
   if("23514".equals(state)) throw new BusinessProblem("PARCEL_CHANGED",409,"A encomenda foi alterada. Atualize a lista.");
   if("23503".equals(state)) throw new BusinessProblem("PARCEL_UNIT_UNAVAILABLE",404,"Unidade não disponível para recebimento.");
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw new BusinessProblem("PARCEL_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("PARCEL_NOT_FOUND",404,"Encomenda não encontrada."); }
}
