package br.com.condpulse.platform.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.platform.infrastructure.TenantModuleStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.TenantModule;
import br.com.condpulse.tenancy.application.TenantModuleFlags;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Módulos por condomínio na central administradora (V045). Leitura com tenants.read; mudança com tenant_modules.manage,
 * AAL2, motivo, versão lida e evento TENANT_MODULE_ENABLED/DISABLED na mesma transação (o banco exige o evento no COMMIT).
 * Pedido igual ao estado atual devolve applied=false sem gravar; versão divergente com mudança real é 409.
 */
@Service @Profile("api")
public class TenantModuleService {
 private final PlatformTransactionExecutor platform;
 private final PlatformStore platformStore;
 private final TenantModuleStore store;
 private final TenantModuleFlags flags;

 public TenantModuleService(PlatformTransactionExecutor platform,PlatformStore platformStore,TenantModuleStore store,TenantModuleFlags flags) {
  this.platform=platform; this.platformStore=platformStore; this.store=store; this.flags=flags;
 }
 @org.springframework.beans.factory.annotation.Autowired
 public TenantModuleService(PlatformTransactionExecutor platform,PlatformStore platformStore,TenantModuleStore store,
   org.springframework.core.env.Environment environment) {
  this(platform,platformStore,store,TenantModuleFlags.from(environment));
 }

 public TenantModuleModels.TenantModulePage list(UUID actor,AssuranceLevel assurance,UUID tenantId) {
  if(tenantId==null) throw notFound();
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.READ_TENANTS);
   if(platformStore.tenant(tenantId)==null) throw notFound();
   return page(tenantId);
  }));
 }

 record Change(TenantModule module,boolean enabled,long expectedVersion,String reason) {}

 static Change validate(TenantModuleModels.ChangeTenantModule request) {
  if(request==null || request.module()==null) throw invalid("TENANT_MODULE_INVALID","Informe o módulo.");
  var module=TenantModule.fromKey(request.module()).orElseThrow(()->invalid("TENANT_MODULE_INVALID","Módulo desconhecido."));
  if(request.enabled()==null) throw invalid("TENANT_MODULE_STATE_REQUIRED","Informe se o módulo fica liberado (enabled).");
  if(request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("TENANT_MODULE_VERSION_REQUIRED","Informe a versão lida do módulo (0 para o padrão).");
  String reason=DisplayText.normalize(request.reason(),500);
  if(reason==null || reason.length()<3) throw invalid("AUDIT_REASON_REQUIRED","Informe o motivo (3 a 500 caracteres).");
  return new Change(module,request.enabled(),request.expectedVersion(),reason);
 }

 public TenantModuleModels.TenantModuleChange change(UUID actor,AssuranceLevel assurance,UUID tenantId,
   TenantModuleModels.ChangeTenantModule request) {
  if(tenantId==null) throw notFound();
  var input=validate(request);
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_TENANT_MODULES);
   context.require(PlatformCapability.READ_TENANTS);
   // Trava do condomínio: mudanças de módulo do mesmo condomínio ficam em fila (inclusive a primeira gravação).
   if(platformStore.lockTenant(tenantId)==null) throw notFound();
   String key=input.module().key();
   var current=store.lock(tenantId,key);
   boolean currentEnabled=current==null || current.enabled();
   long currentVersion=current==null?0:current.version();
   if(currentEnabled==input.enabled())
    return new TenantModuleModels.TenantModuleChange(false,state(input.module(),current));
   if(currentVersion!=input.expectedVersion())
    throw new BusinessProblem("TENANT_MODULE_CHANGED",409,"O módulo mudou desde a leitura. Atualize e tente de novo.");
   long next=currentVersion+1;
   platformStore.audit(actor,input.enabled()?"TENANT_MODULE_ENABLED":"TENANT_MODULE_DISABLED",tenantId,input.reason(),
    Map.of("module",key,"version",Long.toString(next),"previousVersion",Long.toString(currentVersion)));
   if(current==null) store.insert(tenantId,key,input.enabled(),input.reason(),actor);
   else if(!store.update(tenantId,key,input.enabled(),currentVersion,input.reason(),actor))
    throw new BusinessProblem("TENANT_MODULE_CHANGED",409,"O módulo mudou desde a leitura. Atualize e tente de novo.");
   return new TenantModuleModels.TenantModuleChange(true,state(input.module(),store.find(tenantId,key)));
  }));
 }

 private TenantModuleModels.TenantModulePage page(UUID tenantId) {
  var rows=new HashMap<String,TenantModuleStore.Row>();
  for(var row:store.byTenant(tenantId)) rows.put(row.module(),row);
  var modules=new ArrayList<TenantModuleModels.TenantModuleState>();
  for(TenantModule module:TenantModule.values()) modules.add(state(module,rows.get(module.key())));
  return new TenantModuleModels.TenantModulePage(tenantId,modules);
 }
 private TenantModuleModels.TenantModuleState state(TenantModule module,TenantModuleStore.Row row) {
  boolean global=flags.globallyEnabled(module);
  boolean tenant=row==null || row.enabled();
  return row==null
   ?new TenantModuleModels.TenantModuleState(module.key(),global,true,global,true,0,null,null,null)
   :new TenantModuleModels.TenantModuleState(module.key(),global,tenant,global && tenant,false,row.version(),row.reason(),
     row.updatedBy(),row.updatedAt());
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("TENANT_MODULE_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23505".equals(state) || "40001".equals(state))
    throw new BusinessProblem("TENANT_MODULE_CHANGED",409,"O módulo mudou desde a leitura. Atualize e tente de novo.");
   if("23514".equals(state)) throw new BusinessProblem("TENANT_MODULE_INVALID",422,"A mudança não atende às regras do módulo.");
   throw new BusinessProblem("TENANT_MODULE_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,Objects.requireNonNull(message)); }
 private static BusinessProblem notFound() { return new BusinessProblem("TENANT_NOT_FOUND",404,"Condomínio não encontrado."); }
}
