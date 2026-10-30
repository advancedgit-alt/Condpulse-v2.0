package br.com.condpulse.parcels.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.parcels.infrastructure.DelegationStore;
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
 * Retirada delegada com código (CP-041). Quem pode retirar pela unidade autoriza um terceiro para uma encomenda; o
 * código de 8 dígitos aparece só na resposta da criação e o banco guarda apenas o HMAC com segredo do servidor
 * ({@link DelegationCodeKey}). A portaria confere o
 * código da própria encomenda (até 5 tentativas; a tentativa errada fica gravada mesmo quando a resposta é recusa) e
 * registra a retirada em nome do terceiro, conferindo o documento.
 */
@Service @Profile("api")
public class DelegationService {
 static final Duration DEFAULT_VALIDITY=Duration.ofHours(48);
 static final Duration MAXIMUM_VALIDITY=Duration.ofDays(7);
 static final Duration MINIMUM_VALIDITY=Duration.ofMinutes(15);
 private static final SecureRandom RANDOM=new SecureRandom();
 private final TenantTransactionExecutor transactions;
 private final DelegationStore store;
 private final ParcelStore parcels;
 private final AuditRecorder audit;
 private final DelegationCodeKey codeKey;
 public DelegationService(TenantTransactionExecutor transactions,DelegationStore store,ParcelStore parcels,AuditRecorder audit,
   DelegationCodeKey codeKey) {
  this.transactions=transactions; this.store=store; this.parcels=parcels; this.audit=audit; this.codeKey=codeKey;
 }

 /** Resumo da delegação mais recente da encomenda (unidade que vê as encomendas ou portaria); nunca o código. */
 public DelegationModels.Current current(UUID actor,UUID tenant,AssuranceLevel assurance,UUID parcelId) {
  if(parcelId==null) throw parcelNotFound();
  return execute(actor,tenant,assurance,context->{
   boolean concierge=context.effectiveCapabilities().contains(StaffCapability.OPERATE_CONCIERGE);
   var parcel=parcels.parcel(tenant,parcelId,false);
   if(parcel==null || !concierge && (!ParcelService.residentOf(context,parcel.unitId()) || "VOIDED".equals(parcel.status()))) throw parcelNotFound();
   return new DelegationModels.Current(store.latest(tenant,parcelId));
  });
 }

 public DelegationModels.Issued create(UUID actor,UUID tenant,UUID parcelId,String key,DelegationModels.Create request) {
  requireKey(key);
  if(parcelId==null) throw parcelNotFound();
  if(request==null) throw invalid("DELEGATION_INPUT_INVALID","Informe quem vai retirar a encomenda.");
  String name=DisplayText.normalize(request.delegateName(),160);
  if(name==null || name.codePointCount(0,name.length())<2) throw invalid("DELEGATION_NAME_INVALID","Informe o nome de quem vai retirar, com 2 a 160 caracteres.");
  String document=request.documentLast4()==null || DisplayText.collapse(request.documentLast4()).isEmpty()?null
   :DisplayText.collapse(request.documentLast4()).toUpperCase(java.util.Locale.ROOT);
  if(document!=null && !document.matches("[0-9A-Z]{2,4}"))
   throw invalid("DELEGATION_DOCUMENT_INVALID","Informe só os 2 a 4 últimos caracteres do documento.");
  requireCodeKey();
  String route="/v1/tenants/"+tenant+"/parcels/"+parcelId+"/delegation";
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   var parcel=parcels.parcel(tenant,parcelId,false);
   if(parcel==null || !ParcelService.residentOf(context,parcel.unitId()) || "VOIDED".equals(parcel.status())) throw parcelNotFound();
   String hash=ParcelRequestHash.delegate(parcelId,name,String.valueOf(document),request.validUntil()==null?null:request.validUntil());
   // A repetição devolve a autorização já criada antes de conferir o prazo contra o relógio atual.
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.delegation(tenant,previous.resource());
    if(replay==null) throw parcelNotFound();
    return new DelegationModels.Issued(replay,null,true,previous.responseStatus());
   }
   Instant now=store.now();
   Instant until=request.validUntil()==null?now.plus(DEFAULT_VALIDITY):request.validUntil();
   if(until.isBefore(now.plus(MINIMUM_VALIDITY)) || until.isAfter(now.plus(MAXIMUM_VALIDITY)))
    throw invalid("DELEGATION_VALIDITY_INVALID","A autorização vale de 15 minutos a 7 dias.");
   if("DELIVERED".equals(parcel.status())) throw new BusinessProblem("PARCEL_ALREADY_DELIVERED",409,"Esta encomenda já foi retirada.");
   store.lockParcel(tenant,parcelId);
   // Nova autorização substitui a anterior: o código antigo deixa de valer na mesma transação.
   store.closeActive(tenant,parcelId);
   String code=String.format("%08d",RANDOM.nextInt(100_000_000));
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,parcelId,parcel.unitId(),name,document,codeKey.mac(id,code),until,actor);
   var created=store.delegation(tenant,id);
   if(created==null) throw new IllegalStateException("Delegação criada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"DELEGATION_CREATED","PARCEL_DELEGATION",id,null);
   store.event(tenant,"DELEGATION_CREATED",created);
   store.finish(tenant,actor,route,key,id,201);
   return new DelegationModels.Issued(created,code,false,201);
  });
 }

 public DelegationModels.Delegation revoke(UUID actor,UUID tenant,UUID parcelId,DelegationModels.Revoke request) {
  if(parcelId==null) throw parcelNotFound();
  if(request==null || request.delegationId()==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("DELEGATION_VERSION_REQUIRED","Atualize a encomenda antes de cancelar a autorização.");
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   var parcel=parcels.parcel(tenant,parcelId,false);
   if(parcel==null || !ParcelService.residentOf(context,parcel.unitId())) throw parcelNotFound();
   store.lockParcel(tenant,parcelId);
   var current=store.latest(tenant,parcelId);
   if(current==null || !"ACTIVE".equals(current.state()) && !"EXPIRED".equals(current.state()))
    throw new BusinessProblem("DELEGATION_NOT_ACTIVE",409,"Não há autorização em vigor para esta encomenda.");
   // Só a autorização exibida é cancelada: confirmação de uma autorização já substituída é conflito.
   if(!current.delegationId().equals(request.delegationId()) || current.version()!=request.expectedVersion() || !store.revoke(tenant,current.delegationId(),request.expectedVersion(),actor))
    throw new BusinessProblem("DELEGATION_CHANGED",409,"A autorização foi alterada. Atualize a encomenda.");
   var revoked=store.delegation(tenant,current.delegationId());
   audit.record(tenant,actor,"DELEGATION_REVOKED","PARCEL_DELEGATION",current.delegationId(),null);
   store.event(tenant,"DELEGATION_REVOKED",revoked);
   return revoked;
  });
 }

 /**
  * Retirada por código. A conferência grava a tentativa (e o bloqueio) mesmo quando o código está errado; por isso a
  * chave de operação só é gravada depois do código aceito, e uma repetição com a mesma chave devolve a retirada feita.
  */
 public ParcelModels.Mutation handoffByCode(UUID actor,UUID tenant,AssuranceLevel assurance,UUID parcelId,String key,
     DelegationModels.CodeHandoff request) {
  requireKey(key);
  if(parcelId==null) throw parcelNotFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("PARCEL_VERSION_REQUIRED","Atualize a lista antes de registrar a entrega.");
  if(!Boolean.TRUE.equals(request.identityChecked()))
   throw invalid("PARCEL_IDENTITY_CHECK_REQUIRED","Confirme que conferiu o documento da pessoa que retira.");
  if(request.code()==null || request.code().isBlank()) throw invalid("DELEGATION_CODE_REQUIRED","Informe o código de retirada.");
  requireCodeKey();
  String hash=ParcelRequestHash.codeHandoff(parcelId,request.expectedVersion());
  String route="/v1/tenants/"+tenant+"/parcels/"+parcelId+"/handoff-code";
  var outcome=execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   if(store.keyExists(tenant,actor,route,key)) {
    var previous=parcels.begin(tenant,actor,route,key,hash);
    return new Outcome(new ParcelModels.Mutation(previous.parcel(),true,previous.responseStatus()),null);
   }
   var current=parcels.parcel(tenant,parcelId,true);
   if(current==null) throw parcelNotFound();
   // Repetição simultânea com a mesma chave: a primeira pode ter concluído enquanto esta esperava a trava da encomenda.
   if(store.keyExists(tenant,actor,route,key)) {
    var previous=parcels.begin(tenant,actor,route,key,hash);
    return new Outcome(new ParcelModels.Mutation(previous.parcel(),true,previous.responseStatus()),null);
   }
   if("DELIVERED".equals(current.status())) throw new BusinessProblem("PARCEL_ALREADY_DELIVERED",409,"Esta encomenda já foi retirada.");
   if("VOIDED".equals(current.status()))
    throw new BusinessProblem("PARCEL_VOIDED",409,"Este registro de encomenda foi anulado e não está mais para retirada.");
   if(current.version()!=request.expectedVersion()) throw new BusinessProblem("PARCEL_CHANGED",409,"A encomenda foi alterada. Atualize a lista.");
   // O autenticador é calculado aqui, com o segredo do servidor, para a delegação em vigor lida; o banco só compara.
   var active=store.latest(tenant,parcelId);
   UUID delegationId=active!=null && "ACTIVE".equals(active.state())?active.delegationId():null;
   String result=store.check(tenant,parcelId,delegationId,delegationId==null?null:codeKey.mac(delegationId,request.code().strip()));
   if(!"OK".equals(result)) {
    var latest=store.latest(tenant,parcelId);
    return new Outcome(null,refusal(result,latest));
   }
   var used=store.usedByMe(tenant,parcelId);
   if(used==null) throw new IllegalStateException("Delegação usada não encontrada na mesma transação.");
   var fresh=parcels.begin(tenant,actor,route,key,hash);
   if(!fresh.fresh()) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
   if(!store.deliver(tenant,parcelId,request.expectedVersion(),actor,used.delegateName()))
    throw new BusinessProblem("PARCEL_CHANGED",409,"A encomenda foi alterada. Atualize a lista.");
   var delivered=parcels.parcel(tenant,parcelId,false);
   if(delivered==null) throw new IllegalStateException("Encomenda entregue não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"PARCEL_DELIVERED","PARCEL",parcelId,null);
   parcels.event(tenant,"PARCEL_DELIVERED",delivered);
   parcels.finish(tenant,actor,route,key,delivered,200);
   return new Outcome(new ParcelModels.Mutation(delivered,false,200),null);
  });
  // Recusa depois do commit: a tentativa errada (ou o bloqueio) fica gravada.
  if(outcome.refusal()!=null) throw outcome.refusal();
  return outcome.mutation();
 }
 private record Outcome(ParcelModels.Mutation mutation,BusinessProblem refusal) {}
 private static BusinessProblem refusal(String result,DelegationModels.Delegation latest) {
  return switch(result) {
   case "WRONG" -> new BusinessProblem("DELEGATION_CODE_INVALID",409,"Código incorreto. Restam "
     +(latest==null?0:latest.attemptsLeft())+" tentativas antes do bloqueio da autorização.");
   case "LOCKED" -> new BusinessProblem("DELEGATION_LOCKED",409,"Código incorreto pela quinta vez: a autorização foi bloqueada. A unidade precisa gerar outra.");
   case "EXPIRED" -> new BusinessProblem("DELEGATION_EXPIRED",409,"A autorização desta encomenda venceu. A unidade precisa gerar outra.");
   case "CHANGED" -> new BusinessProblem("DELEGATION_CHANGED",409,"A autorização foi alterada. Atualize a encomenda.");
   default -> new BusinessProblem("DELEGATION_NOT_FOUND",409,"Não há autorização de retirada em vigor para esta encomenda.");
  };
 }
 private void requireCodeKey() {
  if(!codeKey.available())
   throw new BusinessProblem("DELEGATION_UNAVAILABLE",503,"A retirada por código ainda não está disponível neste condomínio.");
 }
 private static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem parcelNotFound() { return new BusinessProblem("PARCEL_NOT_FOUND",404,"Encomenda não encontrada."); }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,assurance,action); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23505".equals(state)) throw new BusinessProblem("DELEGATION_CHANGED",409,"A autorização foi alterada. Atualize a encomenda.");
   if("23514".equals(state)) throw new BusinessProblem("DELEGATION_CHANGED",409,"A autorização foi alterada. Atualize a encomenda.");
   if("42501".equals(state))
    throw new BusinessProblem("DELEGATION_NOT_ALLOWED",403,"Seu vínculo não permite autorizar ou registrar retirada desta encomenda.");
   throw new BusinessProblem("PARCEL_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  } catch(TransactionException error) {
   throw new BusinessProblem("PARCEL_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  }
 }
}
