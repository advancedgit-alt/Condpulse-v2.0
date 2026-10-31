package br.com.condpulse.accessedge.application;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessedge.infrastructure.EdgeStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Rotas do gateway (V048): identidade, entrega dos trabalhos da V047 e relatório por trabalho. Entrega não é sucesso; só
 * o relatório muda o trabalho, e CONFIRMED só com a leitura de volta do mesmo hash. O relatório é idempotente por
 * Idempotency-Key (mesmo corpo devolve o mesmo resultado; corpo diferente é 409) e só vale de quem detém a entrega mais
 * recente do trabalho. Nenhuma chamada externa dentro da transação.
 */
public class EdgeGatewayService {
 public static final int MAX_BATCH=50,DEFAULT_LEASE_SECONDS=120,MIN_LEASE_SECONDS=30,MAX_LEASE_SECONDS=600;
 private final EdgeTransactionExecutor edge;
 private final EdgeStore store;
 public EdgeGatewayService(EdgeTransactionExecutor edge,EdgeStore store) { this.edge=edge; this.store=store; }

 public EdgeModels.Identity identity(String credential) {
  return mapped(()->edge.execute(credential,c->new EdgeModels.Identity(c.agentId(),c.connectorId(),c.instanceId(),c.status(),
   c.credentialExpiresAt(),store.serverTime())));
 }

 public EdgeModels.LeasePage lease(String credential,EdgeModels.LeaseRequest request) {
  if(EdgeCredentials.hashOf(credential).isEmpty()) throw EdgeTransactionExecutor.unauthenticated();
  int max=request==null || request.max()==null?10:request.max();
  int seconds=request==null || request.leaseSeconds()==null?DEFAULT_LEASE_SECONDS:request.leaseSeconds();
  if(max<1 || max>MAX_BATCH) throw new BusinessProblem("EDGE_LEASE_MAX_INVALID",400,"Peça de 1 a "+MAX_BATCH+" trabalhos.");
  if(seconds<MIN_LEASE_SECONDS || seconds>MAX_LEASE_SECONDS)
   throw new BusinessProblem("EDGE_LEASE_SECONDS_INVALID",400,"Prazo da entrega entre "+MIN_LEASE_SECONDS+" e "+MAX_LEASE_SECONDS+" segundos.");
  return mapped(()->edge.execute(credential,c->{
   // Um arrendamento por vez no condomínio (mesmo lock da publicação): duas chamadas não entregam o mesmo trabalho.
   store.lockTenant(c.tenantId());
   var commands=new ArrayList<EdgeModels.SyncCommand>();
   for(UUID publication:store.leasable(c.tenantId(),max)) {
    store.lease(c.tenantId(),publication,c.agentId(),seconds);
    var command=store.command(c.tenantId(),publication,c.agentId());
    if(command!=null) commands.add(command);
   }
   return new EdgeModels.LeasePage(commands,store.serverTime());
  }));
 }

 public EdgeModels.ReportResult report(String credential,UUID commandId,String idempotencyKey,EdgeModels.ReportRequest request) {
  if(EdgeCredentials.hashOf(credential).isEmpty()) throw EdgeTransactionExecutor.unauthenticated();
  if(commandId==null) throw notFound();
  String key=EdgeReportRules.idempotencyKey(idempotencyKey);
  var report=EdgeReportRules.validate(request);
  String requestHash=EdgeReportRules.requestHash(commandId,report);
  return mapped(()->edge.execute(credential,c->{
   store.lockTenant(c.tenantId());
   var previous=store.report(c.tenantId(),c.agentId(),key);
   if(previous!=null) {
    if(!previous.requestHash().equals(requestHash) || !previous.publicationId().equals(commandId))
     throw new BusinessProblem("EDGE_IDEMPOTENCY_CONFLICT",409,"Esta Idempotency-Key já foi usada com outro relatório.");
    var row=store.job(c.tenantId(),commandId);
    return new EdgeModels.ReportResult(commandId,previous.outcome(),previous.outcomeCode(),row==null?null:row.job().state(),true);
   }
   var row=store.job(c.tenantId(),commandId);
   if(row==null) throw notFound();
   var lease=store.latestLease(c.tenantId(),commandId);
   if(lease==null || lease.released() || !lease.agentId().equals(c.agentId()))
    throw new BusinessProblem("EDGE_LEASE_NOT_HELD",409,"Este gateway não detém a entrega mais recente do trabalho.");
   var decision=EdgeReportRules.decide(report,row.job());
   if("APPLIED".equals(decision.outcome())) {
    int attempt=store.leaseAttempt(c.tenantId(),lease.leaseId());
    if(!store.applyResult(c.tenantId(),commandId,row.version(),report.state(),decision.confirmationLevel(),decision.observedHash(),
      report.resultCode(),Math.max(attempt,row.attempts())))
     throw new BusinessProblem("EDGE_JOB_CHANGED",409,"O trabalho mudou durante o relatório. Repita.");
   }
   store.progressLease(c.tenantId(),lease.leaseId(),decision.acknowledge(),decision.finish());
   store.insertReport(c.tenantId(),c.agentId(),lease.leaseId(),commandId,key,requestHash,report,decision.outcome(),decision.outcomeCode());
   var after=store.job(c.tenantId(),commandId);
   return new EdgeModels.ReportResult(commandId,decision.outcome(),decision.outcomeCode(),after.job().state(),false);
  }));
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException | TransactionException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("EDGE_FORBIDDEN",403,"Operação não permitida para este gateway.");
   if("23505".equals(state)) throw new BusinessProblem("EDGE_CONFLICT",409,"Relatório ou entrega concorrente. Repita.");
   if("23514".equals(state) || "23503".equals(state))
    throw new BusinessProblem("EDGE_REPORT_REFUSED",422,"Relatório recusado pelas regras do trabalho.");
   throw new BusinessProblem("EDGE_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem notFound() { return new BusinessProblem("EDGE_COMMAND_NOT_FOUND",404,"Trabalho não encontrado para este gateway."); }
}
