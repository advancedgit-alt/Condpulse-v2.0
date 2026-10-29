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
 * Consumidor do outbox (CP-034): por condomínio ativo, numa transação curta encerra arrendamentos que esgotaram as
 * tentativas e arrenda um lote; depois processa cada tarefa em transação própria — trava a linha ainda arrendada nesta
 * tentativa, aplica o efeito e grava a conclusão juntos. O efeito é só interno ao banco (sem chamada externa dentro da
 * transação): se a conclusão não puder ser gravada, ele volta junto. Falha de um condomínio não interrompe os outros.
 * Logs só com identificadores técnicos, sem dados de moradores ou visitantes.
 *
 * <p>Lote e arrendamento: até 10 tarefas por condomínio e passada, arrendamento de 60 s. Cada transação tem limite de
 * 10 s; se um lote lento passar do arrendamento, a tarefa seguinte só é processada se ninguém a arrendou de novo (a
 * trava cercada pela tentativa recusa a tarefa perdida, sem efeito).
 */
@Component @Profile("worker")
public class OutboxWorker {
 static final int BATCH=10;
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
  for(UUID tenant:transactions.catalog(store::activeTenants)) {
   try {
    processed+=runTenant(tenant);
   } catch(RuntimeException error) {
    // Um condomínio com falha (banco, dado inesperado) não impede a passada dos outros.
    LOG.warn("outbox tenant={} passada do condominio interrompida: {}",tenant,sanitize(error));
   }
  }
  return processed;
 }
 public int runTenant(UUID tenant) {
  var pass=transactions.inTenant(tenant,()->{
   int exhausted=store.failExhausted(tenant,RetryPolicy.MAX_ATTEMPTS);
   return new Pass(exhausted,store.lease(tenant,BATCH,LEASE_SECONDS,RetryPolicy.MAX_ATTEMPTS));
  });
  if(pass.exhausted()>0) LOG.warn("outbox tenant={} tarefas com tentativas esgotadas={}",tenant,pass.exhausted());
  for(var event:pass.leased()) process(tenant,event);
  return pass.leased().size();
 }
 private record Pass(int exhausted,List<OutboxModels.LeasedEvent> leased) {}
 /**
  * Uma tarefa já arrendada: trava a linha ainda arrendada nesta tentativa, aplica o efeito e grava a conclusão na mesma
  * transação; falha registra nova tentativa ou FAILED. Arrendamento perdido antes do efeito: nada é aplicado.
  */
 public void process(UUID tenant,OutboxModels.LeasedEvent event) {
  var effect=effects.stream().filter(candidate->candidate.handles(event.type())).findFirst().orElse(null);
  try {
   boolean completed=transactions.inTenant(tenant,()->{
    if(!store.hold(tenant,event)) throw new LeaseLost();
    var outcome=effect==null?OutboxModels.Outcome.SKIPPED:effect.apply(tenant,event);
    if(!store.complete(tenant,event,outcome)) throw new LeaseLost();
    return true;
   });
   if(completed) LOG.debug("outbox tenant={} evento={} tipo={} concluido",tenant,event.id(),event.type());
  } catch(LeaseLost lost) {
   LOG.warn("outbox tenant={} evento={} arrendamento perdido; nada aplicado nesta tentativa",tenant,event.id());
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
