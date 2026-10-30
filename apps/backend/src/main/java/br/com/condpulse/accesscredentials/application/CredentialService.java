package br.com.condpulse.accesscredentials.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.accesscredentials.infrastructure.CredentialStore;
import br.com.condpulse.identity.application.AssuranceLevel;
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
 * Credenciais de acesso (CP-046). A gestão (memberships.manage, segundo fator) cadastra cartão ou QR para um vínculo
 * residencial e revoga; a portaria vê as revogações ainda não confirmadas para orientar a contingência local; a pessoa
 * vê as próprias. O estado desejado fica aqui; quem fala com o equipamento é o worker (CredentialReconciler). Revogada é
 * final e nenhuma confirmação antiga a reativa (guarda no banco).
 */
@Service @Profile("api")
public class CredentialService {
 static final Set<String> KINDS=Set.of("CARD","QR");
 private final TenantTransactionExecutor transactions;
 private final CredentialStore store;
 private final AuditRecorder audit;
 public CredentialService(TenantTransactionExecutor transactions,CredentialStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 public CredentialModels.Connectors connectors(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->{ requireStaff(context); return store.connectors(tenant); });
 }
 public CredentialModels.Page managed(UUID actor,UUID tenant,AssuranceLevel assurance,UUID unit,boolean pendingOnly) {
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   return store.managed(tenant,unit,pendingOnly);
  });
 }
 public CredentialModels.Page pendingRevocations(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->{ requireStaff(context); return store.pendingRevocations(tenant); });
 }
 /** Credenciais do próprio vínculo na unidade (a política de leitura do banco limita ao vínculo da conta). */
 public CredentialModels.Page unit(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   if(unit==null || context.memberships().stream().noneMatch(m->unit.equals(m.unitId())))
    throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
   return store.unit(tenant,unit);
  });
 }

 /** Histórico dos equipamentos da unidade (CP-049): eventos das próprias credenciais, sem presença inferida. */
 public CredentialModels.EventPage unitEvents(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   if(unit==null || context.memberships().stream().noneMatch(m->unit.equals(m.unitId())))
    throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
   return store.unitEvents(tenant,unit);
  });
 }
 public CredentialModels.EventPage recentEvents(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->{ requireStaff(context); return store.recentEvents(tenant); });
 }

 public CredentialModels.Mutation create(UUID actor,UUID tenant,AssuranceLevel assurance,String key,CredentialModels.Create request) {
  requireKey(key);
  if(request==null || request.connectorId()==null) throw invalid("CREDENTIAL_CONNECTOR_REQUIRED","Escolha a integração de acesso.");
  if(request.membershipId()==null) throw invalid("CREDENTIAL_HOLDER_REQUIRED","Escolha o morador que recebe a credencial.");
  if(request.kind()==null || !KINDS.contains(request.kind())) throw invalid("CREDENTIAL_KIND_INVALID","Escolha cartão ou QR.");
  String label=DisplayText.normalize(request.label(),80);
  if(label==null) throw invalid("CREDENTIAL_LABEL_INVALID","Descreva a credencial com até 80 caracteres, sem o número completo do cartão.");
  String hash=digest("CREDENTIAL|"+request.connectorId()+"|"+request.membershipId()+"|"+request.kind()+"|"+label.length()+":"+label);
  String route="/v1/tenants/"+tenant+"/management/access-credentials";
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.credential(tenant,previous.resource(),false);
    if(replay==null) throw notFound();
    return new CredentialModels.Mutation(replay,true,previous.responseStatus());
   }
   if(!store.connectorActive(tenant,request.connectorId()))
    throw new BusinessProblem("CREDENTIAL_CONNECTOR_UNAVAILABLE",409,"A integração de acesso não está ativa neste condomínio.");
   UUID unit=store.holderUnit(tenant,request.membershipId());
   if(unit==null) throw new BusinessProblem("CREDENTIAL_HOLDER_INVALID",409,"O vínculo escolhido não é de morador ativo.");
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,request.connectorId(),request.membershipId(),unit,request.kind(),label,actor);
   var created=store.credential(tenant,id,false);
   if(created==null) throw new IllegalStateException("Credencial não encontrada na mesma transação.");
   audit.record(tenant,actor,"CREDENTIAL_CREATED","ACCESS_CREDENTIAL",id,null);
   store.event(tenant,"CREDENTIAL_CREATED",created);
   store.finish(tenant,actor,route,key,id,201);
   return new CredentialModels.Mutation(created,false,201);
  });
 }

 public CredentialModels.Credential revoke(UUID actor,UUID tenant,AssuranceLevel assurance,UUID credentialId,CredentialModels.Revoke request) {
  if(credentialId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("CREDENTIAL_VERSION_REQUIRED","Atualize a credencial antes de revogar.");
  String reason=DisplayText.normalize(request.reason(),300);
  if(reason==null) throw invalid("CREDENTIAL_REASON_REQUIRED","Informe o motivo da revogação, com até 300 caracteres.");
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   var current=store.credential(tenant,credentialId,true);
   if(current==null) throw notFound();
   // Repetição da mesma revogação devolve o estado; outra versão ou credencial já revogada por outra pessoa não.
   if("REVOKED".equals(current.desiredState()) && current.version()==request.expectedVersion()+1 && reason.equals(current.revokeReason())) return current;
   if("REVOKED".equals(current.desiredState()))
    throw new BusinessProblem("CREDENTIAL_ALREADY_REVOKED",409,"Esta credencial já foi revogada.");
   if(current.version()!=request.expectedVersion() || !store.revoke(tenant,credentialId,request.expectedVersion(),actor,reason)) throw changed();
   var revoked=store.credential(tenant,credentialId,false);
   audit.record(tenant,actor,"CREDENTIAL_REVOKED","ACCESS_CREDENTIAL",credentialId,reason);
   store.event(tenant,"CREDENTIAL_REVOKED",revoked);
   return revoked;
  });
 }

 private static void requireStaff(TenantContext context) {
  var capabilities=context.effectiveCapabilities();
  if(!capabilities.contains(StaffCapability.MANAGE_MEMBERSHIPS) && !capabilities.contains(StaffCapability.OPERATE_CONCIERGE))
   throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
 }
 private static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 private static String digest(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("CREDENTIAL_NOT_FOUND",404,"Credencial não encontrada."); }
 private static BusinessProblem changed() { return new BusinessProblem("CREDENTIAL_CHANGED",409,"A credencial foi alterada. Atualize antes de continuar."); }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,assurance,action); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   if("23514".equals(state)) throw changed();
   throw new BusinessProblem("CREDENTIAL_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  } catch(TransactionException error) {
   throw new BusinessProblem("CREDENTIAL_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  }
 }
}
