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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Painel master (OPS-01): catálogo, uso agregado, criação e estado dos condomínios e auditoria da plataforma. A criação
 * tem o rigor do onboarding operacional (papel de plataforma com AAL2, chave de idempotência, trilha exigida no banco),
 * mas a API não recebe a credencial Auth Admin: o primeiro gestor entra por convite de uso único, aceito pela própria
 * conta Auth com o e-mail convidado (ManagerInvitationService). Não lê conteúdo residencial e não age por outra conta.
 */
@Service @Profile("api")
public class PlatformService {
 private final PlatformTransactionExecutor transactions;
 private final PlatformStore store;
 private final boolean billingEnabled;
 private final boolean facialAccessEnabled;
 private final boolean partnersEnabled;
 /**
  * Financeiro desligado (padrão): billing.* não aparece na sessão, para a tela não oferecer a área sem API. O mesmo vale
  * para technical_grants.* com o acesso facial desligado (CP_FACIAL_ACCESS_ENABLED) e partners.* com a hierarquia de
  * parceiro desligada (CP_PARTNERS_ENABLED).
  */
 @Autowired
 public PlatformService(PlatformTransactionExecutor transactions,PlatformStore store,
   @Value("${condpulse.platform.billing-enabled:false}") boolean billingEnabled,
   @Value("${condpulse.facial-access.enabled:false}") boolean facialAccessEnabled,
   @Value("${condpulse.partners.enabled:false}") boolean partnersEnabled) {
  this.transactions=transactions; this.store=store; this.billingEnabled=billingEnabled; this.facialAccessEnabled=facialAccessEnabled;
  this.partnersEnabled=partnersEnabled;
 }
 public PlatformService(PlatformTransactionExecutor transactions,PlatformStore store,boolean billingEnabled,boolean facialAccessEnabled) {
  this(transactions,store,billingEnabled,facialAccessEnabled,false);
 }
 public PlatformService(PlatformTransactionExecutor transactions,PlatformStore store,boolean billingEnabled) {
  this(transactions,store,billingEnabled,false);
 }
 public PlatformService(PlatformTransactionExecutor transactions,PlatformStore store) { this(transactions,store,true); }

 /** Papel vigente da própria conta. Sem AAL2 devolve capacidades vazias e mfaRequired, para a tela pedir o fator. */
 public PlatformModels.PlatformSession session(UUID actor,AssuranceLevel assurance) {
  return execute(actor,assurance,context->new PlatformModels.PlatformSession(actor,context.role(),
   context.effectiveCapabilities().stream().filter(capability->billingEnabled || !capability.key().startsWith("billing."))
    .filter(capability->facialAccessEnabled || !capability.facialAccess())
    .filter(capability->partnersEnabled || !capability.partners())
    .map(PlatformCapability::key).sorted().toList(),
   assurance!=AssuranceLevel.AAL2));
 }
 public PlatformModels.TenantPage tenants(UUID actor,AssuranceLevel assurance) { return tenants(actor,assurance,null); }
 /** Busca opcional por trecho do nome (sem curingas) ou id exato, para alcançar condomínios além da primeira página. */
 public PlatformModels.TenantPage tenants(UUID actor,AssuranceLevel assurance,String search) {
  String filter=search(search);
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.READ_TENANTS);
   var page=store.tenants(filter);
   return new PlatformModels.TenantPage(withBilling(context,page.tenants()),page.truncated());
  });
 }
 /** Ficha do condomínio: uso agregado, gestores, convites de gestor e os eventos recentes da trilha da plataforma. */
 public PlatformModels.TenantDetail tenant(UUID actor,AssuranceLevel assurance,UUID tenantId) {
  if(tenantId==null) throw notFound();
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.READ_TENANTS);
   var tenant=store.tenant(tenantId);
   if(tenant==null) throw notFound();
   var events=context.effectiveCapabilities().contains(PlatformCapability.READ_AUDIT)
    ?store.recentEvents(tenantId,10):java.util.List.<PlatformModels.AuditEvent>of();
   return new PlatformModels.TenantDetail(withBilling(context,java.util.List.of(tenant)).getFirst(),store.managers(tenantId),
    store.managerInvitations(tenantId),events);
  });
 }

 /**
  * Cria condomínio, unidades, área inicial opcional e o convite do primeiro gestor numa única transação, com a trilha
  * TENANT_CREATED exigida pelo banco no COMMIT. A mesma chave com o mesmo pedido devolve o condomínio já criado
  * (applied=false, sem token); a mesma chave com outro pedido é recusada.
  */
 public PlatformModels.TenantCreated createTenant(UUID actor,AssuranceLevel assurance,String idempotencyKey,
     PlatformModels.CreateTenant request) {
  var input=TenantCreationInput.validate(idempotencyKey,request);
  try {
   return transactions.execute(actor,assurance,context->{
    context.require(PlatformCapability.MANAGE_TENANTS);
    context.require(PlatformCapability.READ_TENANTS);
    var replay=replay(context,actor,input);
    if(replay!=null) return replay;
    if(!store.timeZoneKnown(input.timeZone()))
     throw invalid("TENANT_TIME_ZONE_INVALID","Informe um fuso horário IANA válido, por exemplo America/Sao_Paulo.");
    UUID tenantId=UUID.randomUUID();
    store.insertTenant(actor,input.key(),input.requestHash(),tenantId,input.name(),input.timeZone(),input.units(),
     input.amenity()==null?0:1);
    if(input.amenity()!=null) store.insertAmenity(tenantId,input.amenity());
    var details=new LinkedHashMap<String,String>();
    details.put("name",input.name()); details.put("timeZone",input.timeZone());
    details.put("units",Integer.toString(input.units().size()));
    details.put("amenities",input.amenity()==null?"0":"1");
    store.audit(actor,"TENANT_CREATED",tenantId,input.reason(),details);
    var issued=invite(actor,tenantId,input.managerName(),input.managerEmail(),input.validDays(),input.reason(),false);
    var created=store.tenant(tenantId);
    if(created==null) throw new IllegalStateException("Condomínio criado não foi encontrado na mesma transação.");
    return new PlatformModels.TenantCreated(withBilling(context,java.util.List.of(created)).getFirst(),true,
     issued.invitationId(),issued.token(),issued.expiresAt());
   });
  } catch(DataAccessException error) {
   // Outra requisição com a mesma chave venceu a corrida: relê como repetição depois do rollback desta.
   if(!"23505".equals(SqlStates.of(error))) throw translate(error);
   return execute(actor,assurance,context->{
    context.require(PlatformCapability.MANAGE_TENANTS);
    var replay=replay(context,actor,input);
    if(replay==null) throw new BusinessProblem("TENANT_CREATION_CONFLICT",409,"Os dados conflitam com um cadastro existente. Atualize e confira.");
    return replay;
   });
  }
 }
 private PlatformModels.TenantCreated replay(PlatformContext context,UUID actor,TenantCreationInput input) {
  var existing=store.creation(actor,input.key());
  if(existing==null) return null;
  if(!existing.requestHash().equals(input.requestHash()))
   throw new BusinessProblem("TENANT_CREATION_KEY_REUSED",409,"Esta criação já foi enviada com outros dados. Atualize a lista e confira antes de iniciar outra.");
  var tenant=store.tenant(existing.tenantId());
  if(tenant==null) throw new IllegalStateException("Criação registrada sem condomínio visível.");
  return new PlatformModels.TenantCreated(withBilling(context,java.util.List.of(tenant)).getFirst(),false,null,null,null);
 }

 /**
  * Novo convite do primeiro gestor: só para condomínio ativo e ainda sem gestor vigente. O convite pendente anterior
  * é revogado na mesma transação (um pendente por condomínio). O token aparece somente nesta resposta.
  */
 public PlatformModels.ManagerInvitationIssued issueManagerInvitation(UUID actor,AssuranceLevel assurance,UUID tenantId,
     PlatformModels.IssueManagerInvitation request) {
  if(tenantId==null) throw notFound();
  if(request==null) throw invalid("MANAGER_INVITATION_INPUT_INVALID","Informe os dados do convite.");
  var manager=TenantCreationInput.manager(new PlatformModels.ManagerInput(request.displayName(),request.email(),request.validDays()));
  String reason=text(request.reason(),500,"AUDIT_REASON_REQUIRED","Informe o motivo, com até 500 caracteres.");
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_TENANTS);
   var tenant=store.lockTenant(tenantId);
   if(tenant==null) throw notFound();
   if(!tenant.active()) throw new BusinessProblem("TENANT_SUSPENDED",409,"Reative o condomínio antes de convidar o gestor.");
   if(store.tenantHasActiveManager(tenantId))
    throw new BusinessProblem("TENANT_MANAGER_EXISTS",409,"O condomínio já tem gestor vigente; novos vínculos seguem pela gestão local.");
   return invite(actor,tenantId,manager.name(),manager.email(),manager.days(),reason,true);
  });
 }
 public PlatformModels.TenantDetail revokeManagerInvitation(UUID actor,AssuranceLevel assurance,UUID tenantId,UUID invitationId,
     PlatformModels.RevokeManagerInvitation request) {
  if(tenantId==null || invitationId==null) throw notFound();
  String reason=text(request==null?null:request.reason(),500,"AUDIT_REASON_REQUIRED","Informe o motivo, com até 500 caracteres.");
  execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_TENANTS);
   if(store.lockTenant(tenantId)==null) throw notFound();
   if(!store.revokeInvitation(tenantId,invitationId,actor,reason))
    throw new BusinessProblem("MANAGER_INVITATION_NOT_PENDING",409,"O convite não está mais pendente.");
   store.audit(actor,"TENANT_MANAGER_INVITATION_REVOKED",tenantId,reason,java.util.Map.of("invitationId",invitationId.toString()));
   return null;
  });
  return tenant(actor,assurance,tenantId);
 }
 private PlatformModels.ManagerInvitationIssued invite(UUID actor,UUID tenantId,String name,String email,int days,String reason,
   boolean requireNoManager) {
  // A revogação trava o convite pendente e espera um aceite em andamento (que também trava o convite). Só depois a
  // ausência de gestor é conferida de novo: um aceite confirmado nesse intervalo aparece aqui (READ COMMITTED) e a
  // emissão é recusada; um aceite que chegar depois encontra o convite revogado.
  UUID previous=store.revokePendingInvitation(tenantId,actor,reason);
  if(requireNoManager && store.tenantHasActiveManager(tenantId))
   throw new BusinessProblem("TENANT_MANAGER_EXISTS",409,"O condomínio já tem gestor vigente; novos vínculos seguem pela gestão local.");
  if(previous!=null)
   store.audit(actor,"TENANT_MANAGER_INVITATION_REVOKED",tenantId,reason,java.util.Map.of("invitationId",previous.toString(),
    "replaced","true"));
  UUID id=UUID.randomUUID();
  String token=ManagerInvitationTokens.newToken();
  var expires=store.insertInvitation(id,tenantId,name,ManagerInvitationTokens.emailHash(id,email),ManagerInvitationTokens.sha256(token),
   days,actor);
  store.audit(actor,"TENANT_MANAGER_INVITED",tenantId,reason,java.util.Map.of("invitationId",id.toString(),"displayName",name,
   "validDays",Integer.toString(days)));
  return new PlatformModels.ManagerInvitationIssued(id,token,expires);
 }
 /** Financeiro ligado e billing.read vigente: acrescenta a situação da assinatura; caso contrário fica null. */
 private java.util.List<PlatformModels.Tenant> withBilling(PlatformContext context,java.util.List<PlatformModels.Tenant> tenants) {
  if(!billingEnabled || !context.effectiveCapabilities().contains(PlatformCapability.READ_BILLING)) return tenants;
  var billing=store.billing(tenants.stream().map(PlatformModels.Tenant::tenantId).toList());
  return tenants.stream().map(t->t.withBilling(billing.get(t.tenantId()))).toList();
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
   throw translate(error);
  }
 }
 /** Constraints abortam a transação; traduzir somente depois do rollback do executor. */
 private static BusinessProblem translate(DataAccessException error) {
  String state=SqlStates.of(error);
  if("23514".equals(state)) return invalid("PLATFORM_INPUT_INVALID","Os dados informados não atendem às regras do cadastro.");
  if("42501".equals(state)) return new BusinessProblem("PLATFORM_ACCESS_DENIED",403,"Esta área é exclusiva da administração da plataforma.");
  if("23505".equals(state)) return new BusinessProblem("PLATFORM_CONFLICT",409,"Os dados conflitam com um cadastro existente. Atualize e confira.");
  return new BusinessProblem("PLATFORM_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
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
 static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() {
  return new BusinessProblem("TENANT_NOT_FOUND",404,"Condomínio não encontrado.");
 }
}
