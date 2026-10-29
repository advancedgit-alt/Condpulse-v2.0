package br.com.condpulse.notifications.application;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.notifications.infrastructure.OutboxStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Passada do worker sem banco: falha de um condomínio não para os outros; tarefa perdida não roda efeito. */
class OutboxWorkerTest {
 @SuppressWarnings("unchecked")
 private static WorkerTransactions runningTransactions() {
  var transactions=mock(WorkerTransactions.class);
  when(transactions.catalog(any())).thenAnswer(call->((Supplier<Object>)call.getArgument(0)).get());
  when(transactions.inTenant(any(),any())).thenAnswer(call->((Supplier<Object>)call.getArgument(1)).get());
  return transactions;
 }

 @Test void oneTenantFailureDoesNotStopTheOthers() {
  UUID broken=UUID.randomUUID(),healthy=UUID.randomUUID();
  var transactions=runningTransactions();
  var store=mock(OutboxStore.class);
  when(store.activeTenants()).thenReturn(List.of(broken,healthy));
  when(store.failExhausted(eq(broken),anyInt())).thenThrow(new DataAccessResourceFailureException("PESSOA FICTICIA"));
  var event=new OutboxModels.LeasedEvent(UUID.randomUUID(),"SEM_CONSUMIDOR",UUID.randomUUID(),0,null,1);
  when(store.lease(eq(healthy),anyInt(),anyInt(),anyInt())).thenReturn(List.of(event));
  when(store.hold(healthy,event)).thenReturn(true);
  when(store.complete(healthy,event,OutboxModels.Outcome.SKIPPED)).thenReturn(true);
  assertEquals(1,new OutboxWorker(transactions,store,List.of()).runOnce());
  verify(store).lease(healthy,OutboxWorker.BATCH,OutboxWorker.LEASE_SECONDS,RetryPolicy.MAX_ATTEMPTS);
  verify(store).complete(healthy,event,OutboxModels.Outcome.SKIPPED);
  verify(store,never()).lease(eq(broken),anyInt(),anyInt(),anyInt());
 }

 @Test void lostLeaseRunsNoEffectAndRecordsNothing() {
  UUID tenant=UUID.randomUUID();
  var transactions=runningTransactions();
  var store=mock(OutboxStore.class);
  var effect=mock(OutboxEffect.class);
  when(effect.handles("PARCEL_RECEIVED")).thenReturn(true);
  var event=new OutboxModels.LeasedEvent(UUID.randomUUID(),"PARCEL_RECEIVED",UUID.randomUUID(),0,UUID.randomUUID(),1);
  when(store.hold(tenant,event)).thenReturn(false);
  new OutboxWorker(transactions,store,List.of(effect)).process(tenant,event);
  verify(effect,never()).apply(any(),any());
  verify(store,never()).complete(any(),any(),any());
  verify(store,never()).retry(any(),any(),any(),any());
  verify(store,never()).fail(any(),any(),any());
 }
}
