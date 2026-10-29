package br.com.condpulse.platform.application;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.audit.application.AuditQuery;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Painel master (OPS-01): catálogo e estado dos condomínios e auditoria da plataforma. Não cria condomínio (o cadastro
 * segue pelo onboarding operacional, que confere as contas no Auth), não lê dados de negócio e não age por outra conta.
 */
@Service @Profile("api")
public class PlatformService {
 private final PlatformTransactionExecutor transactions;
 private final PlatformStore store;
 public PlatformService(PlatformTransactionExecutor transactions,PlatformStore store) {
  this.transactions=transactions; this.store=store;
 }

 /** Papel vigente da própria conta. Sem AAL2 devolve capacidades vazias e mfaRequired, para a tela pedir o fator. */
 public PlatformModels.PlatformSession session(UUID actor,AssuranceLevel assurance) {
  return execute(actor,assurance,context->new PlatformModels.PlatformSession(actor,context.role(),
   context.effectiveCapabilities().stream().map(PlatformCapability::key).sorted().toList(),
   assurance!=AssuranceLevel.AAL2));
 }
 public PlatformModels.TenantPage tenants(UUID actor,AssuranceLevel assurance) { return tenants(actor,assurance,null); }
 /** Busca opcional por trecho do nome (sem curingas) ou id exato, para alcançar condomínios além da primeira página. */
 public PlatformModels.TenantPage tenants(UUID actor,AssuranceLevel assurance,String search) {
  String filter=search(search);
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.READ_TENANTS);
   return store.tenants(filter);
  });
 }
 /**
  * Renomear, suspender ou reativar pela versão lida. Pedido igual ao estado atual é repetição natural (applied=false,
  * sem nova versão nem auditoria); versão divergente com mudança real é conflito. Suspender remove o condomínio do
  * contexto dos moradores e da gestão local na próxima requisição; não apaga dados nem sessões.
  */
 public PlatformModels.TenantChange updateTenant(UUID actor,AssuranceLevel assurance,UUID tenantId,
     PlatformModels.UpdateTenant request) {
  if(tenantId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("TENANT_VERSION_REQUIRED","Informe a versão lida do condomínio.");
  // Nome ausente mantém o atual: suspender ou reativar nunca renomeia por normalização.
  String name=request.name()==null?null:text(request.name(),160,"TENANT_NAME_INVALID",
   "Informe o nome do condomínio com até 160 caracteres visíveis.");
  if(request.active()==null) throw invalid("TENANT_STATE_INVALID","Informe se o condomínio fica ativo ou suspenso.");
  String reason=text(request.reason(),500,"AUDIT_REASON_REQUIRED","Informe o motivo da alteração, com até 500 caracteres.");
  boolean active=request.active();
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_TENANTS);
   var current=store.lockTenant(tenantId);
   if(current==null) throw notFound();
   String target=name==null?current.name():name;
   if(current.name().equals(target) && current.active()==active) return new PlatformModels.TenantChange(current,false);
   if(current.version()!=request.expectedVersion() || !store.updateTenant(tenantId,target,active,request.expectedVersion()))
    throw new BusinessProblem("TENANT_CHANGED",409,"O condomínio foi alterado. Atualize os dados antes de salvar.");
   // Uma ação por mudança: renomear e suspender na mesma gravação deixam os dois registros.
   if(!current.name().equals(target)) {
    var details=new LinkedHashMap<String,String>();
    details.put("previousName",current.name()); details.put("name",target);
    store.audit(actor,"TENANT_RENAMED",tenantId,reason,details);
   }
   if(current.active()!=active)
    store.audit(actor,active?"TENANT_REACTIVATED":"TENANT_SUSPENDED",tenantId,reason,java.util.Map.of());
   var updated=store.tenant(tenantId);
   if(updated==null) throw new IllegalStateException("Condomínio alterado não foi encontrado na mesma transação.");
   return new PlatformModels.TenantChange(updated,true);
  });
 }
 public PlatformModels.AuditPage audit(UUID actor,AssuranceLevel assurance,String cursor,Integer requestedLimit) {
  int limit=requestedLimit==null?30:requestedLimit;
  if(limit<1 || limit>100) throw invalid("AUDIT_LIMIT_INVALID","Use um limite entre 1 e 100.");
  var after=AuditQuery.Cursor.parse(cursor);
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.READ_AUDIT);
   return store.auditPage(after,limit);
  });
 }

 private <T> T execute(UUID actor,AssuranceLevel assurance,Function<PlatformContext,T> action) {
  try {
   return transactions.execute(actor,assurance,action);
  } catch(DataAccessException error) {
   // Constraints abortam a transação; traduzir somente depois do rollback do executor.
   String state=SqlStates.of(error);
   if("23514".equals(state)) throw invalid("PLATFORM_INPUT_INVALID","Os dados informados não atendem às regras do cadastro.");
   if("42501".equals(state)) throw new BusinessProblem("PLATFORM_ACCESS_DENIED",403,"Esta área é exclusiva da administração da plataforma.");
   throw new BusinessProblem("PLATFORM_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
  }
 }
 static String text(String value,int maximum,String code,String message) {
  String normalized=DisplayText.normalize(value,maximum);
  if(normalized==null) throw invalid(code,message);
  return normalized;
 }
 static String search(String value) {
  if(value==null || DisplayText.collapse(value).isEmpty()) return null;
  return text(value,160,"TENANT_SEARCH_INVALID","Use até 160 caracteres visíveis na busca.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() {
  return new BusinessProblem("TENANT_NOT_FOUND",404,"Condomínio não encontrado.");
 }
}
