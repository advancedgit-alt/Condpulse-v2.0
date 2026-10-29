package br.com.condpulse.notifications.application;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import br.com.condpulse.notifications.infrastructure.OutboxStore;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Consumidor do outbox (CP-034): por condomínio ativo, encerra arrendamentos que esgotaram as tentativas, arrenda um
 * lote e processa cada tarefa em transação própria — efeito e conclusão juntos, cercados pela tentativa arrendada. A
 * entrega é ao menos uma vez; os efeitos internos são idempotentes pela chave tarefa/destinatário/canal. Logs só com
 * identificadores técnicos, sem dados de moradores ou visitantes.
 */
@Component @Profile("worker")
public class OutboxWorker {
 static final int BATCH=20;
 static final int LEASE_SECONDS=60;
 private static final Logger LOG=LoggerFactory.getLogger(OutboxWorker.class);
 private final WorkerTransactions transactions;
 private final OutboxStore store;
 private final List<OutboxEffect> effects;
 private final DoubleSupplier random;
 @Autowired
 public OutboxWorker(WorkerTransactions transactions,OutboxStore store,List<OutboxEffect> effects) {
  this(transactions,store,effects,()->ThreadLocalRandom.current().nextDouble());
 }
 OutboxWorker(WorkerTransactions transactions,OutboxStore store,List<OutboxEffect> effects,DoubleSupplier random) {
  this.transactions=transactions; this.store=store; this.effects=List.copyOf(effects); this.random=random;
 }

 /** Uma passada por todos os condomínios ativos; devolve quantas tarefas foram processadas. */
 public int runOnce() {
  int processed=0;
  for(UUID tenant:transactions.catalog(store::activeTenants)) processed+=runTenant(tenant);
  return processed;
 }
 public int runTenant(UUID tenant) {
  int exhausted=transactions.inTenant(tenant,()->store.failExhausted(tenant,RetryPolicy.MAX_ATTEMPTS));
  if(exhausted>0) LOG.warn("outbox tenant={} tarefas com tentativas esgotadas={}",tenant,exhausted);
  var leased=transactions.inTenant(tenant,()->store.lease(tenant,BATCH,LEASE_SECONDS,RetryPolicy.MAX_ATTEMPTS));
  for(var event:leased) process(tenant,event);
  return leased.size();
 }
 /** Uma tarefa já arrendada: efeito e conclusão na mesma transação; falha registra nova tentativa ou FAILED. */
 public void process(UUID tenant,OutboxModels.LeasedEvent event) {
  var effect=effects.stream().filter(candidate->candidate.handles(event.type())).findFirst().orElse(null);
  try {
   boolean completed=transactions.inTenant(tenant,()->{
    var outcome=effect==null?OutboxModels.Outcome.SKIPPED:effect.apply(tenant,event);
    if(!store.complete(tenant,event,outcome)) throw new LeaseLost();
    return true;
   });
   if(completed) LOG.debug("outbox tenant={} evento={} tipo={} concluido",tenant,event.id(),event.type());
  } catch(LeaseLost lost) {
   LOG.warn("outbox tenant={} evento={} arrendamento perdido; efeito desfeito",tenant,event.id());
  } catch(RuntimeException error) {
   var failure=error instanceof OutboxModels.PermanentFailure permanent
    ?new OutboxModels.Failure(false,java.time.Duration.ZERO,RetryPolicy.limit(permanent.getMessage()))
    :RetryPolicy.after(event.attempts(),effect==null || effect.idempotent(),sanitize(error),random);
   try {
    boolean recorded=transactions.inTenant(tenant,()->failure.retry()?store.retry(tenant,event,failure.delay(),failure.error())
     :store.fail(tenant,event,failure.error()));
    LOG.warn("outbox tenant={} evento={} tipo={} tentativa={} {} registrado={}",tenant,event.id(),event.type(),event.attempts(),
     failure.retry()?"nova tentativa":"falha definitiva",recorded);
   } catch(RuntimeException again) {
    // Sem registrar a falha, o arrendamento vence e a tarefa volta a ser arrendada (ao menos uma vez).
    LOG.error("outbox tenant={} evento={} falha ao registrar o resultado: {}",tenant,event.id(),sanitize(again));
   }
  }
 }
 /** Erro saneado: categoria e SQLSTATE, sem mensagem do banco, SQL ou dados. */
 public static String sanitize(Throwable error) {
  if(error instanceof DataAccessException) {
   String state=SqlStates.of(error);
   return "Falha de banco"+(state==null?"":" ("+state+")");
  }
  return "Erro inesperado ("+error.getClass().getSimpleName()+")";
 }
 private static final class LeaseLost extends RuntimeException {
  LeaseLost() { super(null,null,false,false); }
 }
}
