package br.com.condpulse.accessgovernance.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessgovernance.infrastructure.TechnicalGrantStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.PlatformCapability;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Autorização administrativa em três níveis (AC-003, V037).
 * <ul>
 * <li>MASTER plataforma (technical_grants.manage, AAL2): emite e revoga concessões técnicas por condomínio, sempre para
 * outra conta, com validade curta e trilha da plataforma exigida pelo banco no COMMIT.</li>
 * <li>MASTER de implantação: lê a própria sessão técnica (escopos vigentes) no condomínio concedido.</li>
 * <li>Gestor (memberships.manage, AAL2): só consulta quem tem concessão técnica no próprio condomínio.</li>
 * </ul>
 */
public class TechnicalGrantService {
 public static final int HARD_MAX_DAYS=90;
 private final PlatformTransactionExecutor platform;
 private final TenantTransactionExecutor tenants;
 private final TechnicalTransactionExecutor technical;
 private final TechnicalGrantStore store;
 private final PlatformStore platformStore;
 private final int maxDays;

 public TechnicalGrantService(PlatformTransactionExecutor platform,TenantTransactionExecutor tenants,TechnicalTransactionExecutor technical,
   TechnicalGrantStore store,PlatformStore platformStore,int maxDays) {
  if(maxDays<1 || maxDays>HARD_MAX_DAYS) throw new IllegalArgumentException("Validade máxima da concessão técnica entre 1 e 90 dias.");
  this.platform=platform; this.tenants=tenants; this.technical=technical; this.store=store; this.platformStore=platformStore;
  this.maxDays=maxDays;
 }

 /** Pedido validado antes de abrir transação. */
 record Issue(UUID grantId,UUID accountId,String roleCode,List<String> scopes,int validDays,String reason) {}

 Issue validate(UUID actor,TechnicalGrantModels.IssueTechnicalGrant request) {
  if(request==null || request.grantId()==null)
   throw invalid("TECHNICAL_GRANT_ID_REQUIRED","Informe o identificador da concessão (grantId).");
  if(request.accountId()==null) throw invalid("TECHNICAL_GRANT_ACCOUNT_REQUIRED","Informe a conta que recebe a concessão.");
  if(request.accountId().equals(actor))
   throw new BusinessProblem("TECHNICAL_GRANT_SELF",403,"Ninguém concede papel técnico a si próprio pelo painel.");
  String role=request.roleCode();
  var allowed=TechnicalScope.allowedFor(role);
  if(allowed.isEmpty()) throw invalid("TECHNICAL_GRANT_ROLE_INVALID","Papel técnico inválido.");
  List<String> keys=request.scopes()==null || request.scopes().isEmpty()
   ?("MASTER_IMPLANTACAO".equals(role)?TechnicalScope.implantationDefaults().stream().map(TechnicalScope::key).sorted().toList():List.of())
   :request.scopes();
  if(keys.isEmpty() || keys.size()>16) throw invalid("TECHNICAL_GRANT_SCOPES_INVALID","Informe de 1 a 16 escopos técnicos.");
  var seen=new HashSet<String>();
  var normalized=new ArrayList<String>();
  for(String key:keys) {
   var scope=key==null?null:TechnicalScope.fromKey(key).orElse(null);
   if(scope==null || !allowed.contains(scope) || !seen.add(key))
    throw invalid("TECHNICAL_GRANT_SCOPES_INVALID","Escopo técnico inválido, repetido ou não permitido para o papel.");
   normalized.add(key);
  }
  normalized.sort(null);
  Integer days=request.validDays();
  if(days==null || days<1 || days>maxDays)
   throw invalid("TECHNICAL_GRANT_VALIDITY_INVALID","A validade deve ser de 1 a "+maxDays+" dias.");
  String reason=DisplayText.normalize(request.reason(),500);
  if(reason==null || reason.length()<10) throw invalid("AUDIT_REASON_REQUIRED","Informe o motivo e a referência da aprovação (10 a 500 caracteres).");
  return new Issue(request.grantId(),request.accountId(),role,List.copyOf(normalized),days,reason);
 }

 public TechnicalGrantModels.TechnicalGrantIssued issue(UUID actor,AssuranceLevel assurance,UUID tenantId,
   TechnicalGrantModels.IssueTechnicalGrant request) {
  if(tenantId==null) throw notFound();
  var input=validate(actor,request);
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_TECHNICAL_GRANTS);
   context.require(PlatformCapability.READ_TENANTS);
   var existing=store.find(input.grantId());
   if(existing!=null) {
    if(existing.tenantId().equals(tenantId) && existing.accountId().equals(input.accountId()) && existing.roleCode().equals(input.roleCode())
      && new HashSet<>(existing.scopes()).equals(new HashSet<>(input.scopes())))
     return new TechnicalGrantModels.TechnicalGrantIssued(existing,false);
    throw new BusinessProblem("TECHNICAL_GRANT_ID_CONFLICT",409,"Este identificador já foi usado para outra concessão.");
   }
   if(!store.tenantActive(tenantId)) throw notFound();
   platformStore.audit(actor,"TECHNICAL_GRANT_ISSUED",tenantId,input.reason(),Map.of("grantId",input.grantId().toString(),
    "accountId",input.accountId().toString(),"roleCode",input.roleCode(),"scopes",String.join(",",input.scopes()),
    "validDays",Integer.toString(input.validDays())));
   store.insert(input.grantId(),tenantId,input.accountId(),input.roleCode(),input.scopes(),input.validDays(),actor,input.reason());
   return new TechnicalGrantModels.TechnicalGrantIssued(store.find(input.grantId()),true);
  }));
 }

 public TechnicalGrantModels.TechnicalGrant revoke(UUID actor,AssuranceLevel assurance,UUID tenantId,UUID grantId,
   TechnicalGrantModels.RevokeTechnicalGrant request) {
  if(tenantId==null || grantId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("TECHNICAL_GRANT_VERSION_REQUIRED","Informe a versão lida da concessão.");
  String reason=DisplayText.normalize(request.reason(),500);
  if(reason==null || reason.length()<3) throw invalid("AUDIT_REASON_REQUIRED","Informe o motivo da revogação (3 a 500 caracteres).");
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_TECHNICAL_GRANTS);
   var current=store.find(grantId);
   if(current==null || !current.tenantId().equals(tenantId)) throw notFound();
   platformStore.audit(actor,"TECHNICAL_GRANT_REVOKED",tenantId,reason,Map.of("grantId",grantId.toString(),
    "accountId",current.accountId().toString()));
   if(!store.revoke(tenantId,grantId,request.expectedVersion(),actor,reason))
    throw new BusinessProblem("TECHNICAL_GRANT_STALE",409,"A concessão mudou ou já foi revogada. Atualize e tente de novo.");
   return store.find(grantId);
  }));
 }

 public TechnicalGrantModels.TechnicalGrantPage platformList(UUID actor,AssuranceLevel assurance,UUID tenantId) {
  if(tenantId==null) throw notFound();
  return mapped(()->platform.execute(actor,assurance,context->{
   context.require(PlatformCapability.MANAGE_TECHNICAL_GRANTS);
   return new TechnicalGrantModels.TechnicalGrantPage(store.byTenant(tenantId));
  }));
 }

 /** Consulta do gestor no próprio condomínio; nenhuma escrita. */
 public TechnicalGrantModels.TechnicalGrantPage managerList(UUID actor,UUID tenantId,AssuranceLevel assurance) {
  return mapped(()->tenants.execute(actor,tenantId,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   return new TechnicalGrantModels.TechnicalGrantPage(store.byTenant(tenantId));
  }));
 }

 /** Sessão técnica da própria conta: escopos vigentes no condomínio (exige AAL2 e concessão vigente). */
 public TechnicalGrantModels.TechnicalSession session(UUID actor,UUID tenantId,AssuranceLevel assurance) {
  return mapped(()->technical.execute(actor,tenantId,assurance,context->new TechnicalGrantModels.TechnicalSession(tenantId,
   context.scopes().stream().map(TechnicalScope::key).sorted().toList(),context.validUntil())));
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("TECHNICAL_GRANT_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23503".equals(state)) throw invalid("TECHNICAL_GRANT_ACCOUNT_UNKNOWN","A conta informada não existe.");
   if("23514".equals(state)) throw new BusinessProblem("TECHNICAL_GRANT_INVALID",422,"A concessão não atende às regras de validade e escopo.");
   if("23505".equals(state)) throw new BusinessProblem("TECHNICAL_GRANT_ID_CONFLICT",409,"Este identificador já foi usado para outra concessão.");
   throw new BusinessProblem("TECHNICAL_GRANT_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,Objects.requireNonNull(message)); }
 private static BusinessProblem notFound() {
  return new BusinessProblem("TENANT_NOT_FOUND",404,"Condomínio não encontrado.");
 }
}
