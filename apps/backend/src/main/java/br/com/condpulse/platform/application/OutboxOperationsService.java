package br.com.condpulse.platform.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.infrastructure.OutboxOperationsStore;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Operação da fila de tarefas (CP-034) no painel master: alerta de fila antiga e devolução seletiva de tarefa FAILED ou
 * SKIPPED, com motivo auditado. Não lê o conteúdo das tarefas nem dados dos condomínios; os efeitos do worker são
 * idempotentes (o aviso já entregue não se repete).
 */
@Service @Profile("api")
public class OutboxOperationsService {
 private static final Set<String> STATES=Set.of("FAILED","SKIPPED");
 private final PlatformTransactionExecutor transactions;
 private final OutboxOperationsStore store;
 private final PlatformStore platform;
 public OutboxOperationsService(PlatformTransactionExecutor transactions,OutboxOperationsStore store,PlatformStore platform) {
  this.transactions=transactions; this.store=store; this.platform=platform;
 }
 public OutboxOperationsModels.Summary summary(UUID actor,AssuranceLevel assurance) {
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.OPERATE_OUTBOX); context.require(PlatformCapability.READ_TENANTS);
   return new OutboxOperationsModels.Summary(store.summary(),OutboxOperationsModels.STALE_SECONDS,Instant.now());
  });
 }
 public OutboxOperationsModels.TaskPage tasks(UUID actor,AssuranceLevel assurance,UUID tenant,String state) {
  if(state==null || !STATES.contains(state)) throw invalid("OUTBOX_STATE_INVALID","Escolha tarefas com falha (FAILED) ou ignoradas (SKIPPED).");
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.OPERATE_OUTBOX);
   return store.tasks(tenant,state);
  });
 }
 public OutboxOperationsModels.Task replay(UUID actor,AssuranceLevel assurance,UUID taskId,OutboxOperationsModels.Replay request) {
  if(taskId==null) throw notFound();
  String reason=PlatformService.text(request==null?null:request.reason(),500,"AUDIT_REASON_REQUIRED",
   "Informe o motivo da devolução, com até 500 caracteres.");
  return execute(actor,assurance,context->{
   context.require(PlatformCapability.OPERATE_OUTBOX);
   var current=store.task(taskId);
   if(current==null) throw notFound();
   if(!STATES.contains(current.state()) || !store.replay(taskId))
    throw new BusinessProblem("OUTBOX_TASK_NOT_REPLAYABLE",409,"Só tarefa com falha ou ignorada volta para a fila. Atualize a lista.");
   var details=new LinkedHashMap<String,String>();
   details.put("taskId",taskId.toString()); details.put("eventType",current.eventType()); details.put("previousState",current.state());
   details.put("previousAttempts",Integer.toString(current.attempts()));
   platform.audit(actor,"OUTBOX_TASK_REPLAYED",current.tenantId(),reason,details);
   return store.task(taskId);
  });
 }
 private <T> T execute(UUID actor,AssuranceLevel assurance,Function<PlatformContext,T> action) {
  try {
   return transactions.execute(actor,assurance,action);
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23514".equals(state)) throw new BusinessProblem("OUTBOX_TASK_NOT_REPLAYABLE",409,"Só tarefa com falha ou ignorada volta para a fila. Atualize a lista.");
   if("42501".equals(state)) throw new BusinessProblem("PLATFORM_ACCESS_DENIED",403,"Esta área é exclusiva da administração da plataforma.");
   throw new BusinessProblem("PLATFORM_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
  }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("OUTBOX_TASK_NOT_FOUND",404,"Tarefa não encontrada."); }
}
