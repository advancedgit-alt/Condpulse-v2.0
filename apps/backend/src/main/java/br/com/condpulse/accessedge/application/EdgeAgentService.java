package br.com.condpulse.accessedge.application;

import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessedge.infrastructure.EdgeStore;
import br.com.condpulse.accessgovernance.application.TechnicalScope;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.dao.DataAccessException;

/**
 * Identidade do gateway pelo MASTER de implantação (V048, AAL2): lê com gateway.manage ou installation.read; cadastra,
 * gira e revoga com gateway.manage. Recebe só o hash SHA-256 da credencial gerada no gateway; nunca gera, mostra ou
 * devolve credencial. Um gateway não revogado por conector; revogação é definitiva.
 */
public class EdgeAgentService {
 public static final int DEFAULT_VALID_DAYS=90,MIN_VALID_DAYS=2,MAX_VALID_DAYS=180;
 private final TechnicalTransactionExecutor technical;
 private final EdgeStore store;
 private final AuditRecorder audit;
 public EdgeAgentService(TechnicalTransactionExecutor technical,EdgeStore store,AuditRecorder audit) {
  this.technical=technical; this.store=store; this.audit=audit;
 }

 public EdgeModels.AgentPage agents(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   if(!context.scopes().contains(TechnicalScope.GATEWAY_MANAGE)) context.require(TechnicalScope.INSTALLATION_READ);
   return new EdgeModels.AgentPage(store.agents(tenant));
  }));
 }

 public EdgeModels.Agent register(UUID actor,UUID tenant,AssuranceLevel assurance,EdgeModels.RegisterAgent request) {
  if(request==null || request.agentId()==null || request.connectorId()==null)
   throw invalid("EDGE_AGENT_INVALID","Informe o identificador do gateway e o conector.");
  String label=DisplayText.normalize(request.label(),80);
  if(label==null) throw invalid("EDGE_AGENT_LABEL_INVALID","Nome do gateway com 1 a 80 caracteres.");
  String hash=hash(request.credentialHash());
  int days=days(request.credentialValidDays());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.GATEWAY_MANAGE);
   var existing=store.agent(tenant,request.agentId());
   if(existing!=null) {
    // Repetição do mesmo cadastro devolve o existente; outro conteúdo no mesmo id é conflito.
    if(existing.connectorId().equals(request.connectorId()) && existing.label().equals(label) && hash.equals(store.credentialHash(tenant,request.agentId())))
     return existing;
    throw new BusinessProblem("EDGE_AGENT_ID_CONFLICT",409,"Este identificador já foi usado para outro gateway.");
   }
   store.insertAgent(tenant,request.agentId(),request.connectorId(),label,hash,days,actor);
   audit.record(tenant,actor,"EDGE_AGENT_REGISTERED","EDGE_AGENT",request.agentId(),null);
   return store.agent(tenant,request.agentId());
  }));
 }

 public EdgeModels.Agent rotate(UUID actor,UUID tenant,AssuranceLevel assurance,UUID agentId,EdgeModels.RotateAgent request) {
  if(agentId==null || request==null) throw notFound();
  String hash=hash(request.credentialHash());
  int days=days(request.credentialValidDays());
  long expected=version(request.expectedVersion());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.GATEWAY_MANAGE);
   var current=store.agent(tenant,agentId);
   if(current==null) throw notFound();
   if("REVOKED".equals(current.status())) throw new BusinessProblem("EDGE_AGENT_REVOKED",409,"Gateway revogado não recebe credencial nova.");
   if(!store.rotate(tenant,agentId,hash,days,expected)) throw stale();
   audit.record(tenant,actor,"EDGE_AGENT_ROTATED","EDGE_AGENT",agentId,null);
   return store.agent(tenant,agentId);
  }));
 }

 public EdgeModels.Agent revoke(UUID actor,UUID tenant,AssuranceLevel assurance,UUID agentId,EdgeModels.RevokeAgent request) {
  if(agentId==null || request==null) throw notFound();
  String reason=DisplayText.normalize(request.reason(),500);
  if(reason==null || reason.codePointCount(0,reason.length())<3) throw invalid("EDGE_AGENT_REASON_REQUIRED","Informe o motivo (3 a 500 caracteres).");
  long expected=version(request.expectedVersion());
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.GATEWAY_MANAGE);
   var current=store.agent(tenant,agentId);
   if(current==null) throw notFound();
   if("REVOKED".equals(current.status())) throw new BusinessProblem("EDGE_AGENT_REVOKED",409,"Gateway já revogado.");
   if(!store.revoke(tenant,agentId,reason,expected)) throw stale();
   audit.record(tenant,actor,"EDGE_AGENT_REVOKED","EDGE_AGENT",agentId,reason);
   return store.agent(tenant,agentId);
  }));
 }

 private static String hash(String value) {
  if(!EdgeCredentials.validHash(value))
   throw invalid("EDGE_CREDENTIAL_HASH_INVALID","Informe o SHA-256 (64 dígitos hexadecimais minúsculos) da credencial gerada no gateway.");
  return value;
 }
 private static int days(Integer value) {
  int days=value==null?DEFAULT_VALID_DAYS:value;
  if(days<MIN_VALID_DAYS || days>MAX_VALID_DAYS)
   throw invalid("EDGE_CREDENTIAL_VALIDITY_INVALID","Validade da credencial entre "+MIN_VALID_DAYS+" e "+MAX_VALID_DAYS+" dias.");
  return days;
 }
 private static long version(Long expected) {
  if(expected==null || expected<0) throw invalid("EDGE_VERSION_REQUIRED","Informe a versão lida.");
  return expected;
 }
 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("EDGE_AGENT_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23505".equals(state)) throw new BusinessProblem("EDGE_AGENT_CONFLICT",409,"Já existe gateway ativo neste conector ou a credencial já foi usada.");
   if("23503".equals(state)) throw new BusinessProblem("EDGE_AGENT_REFERENCE_INVALID",422,"Conector inexistente ou inativo neste condomínio.");
   if("23514".equals(state)) throw new BusinessProblem("EDGE_AGENT_INVALID",422,"Alteração recusada pelas regras do gateway.");
   throw new BusinessProblem("EDGE_AGENT_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem stale() { return new BusinessProblem("EDGE_AGENT_STALE",409,"O cadastro mudou. Atualize e tente de novo."); }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("EDGE_AGENT_NOT_FOUND",404,"Gateway não encontrado neste condomínio."); }
}
