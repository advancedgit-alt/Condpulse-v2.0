package br.com.condpulse.gateway;

import java.util.List;
import java.util.Map;
import br.com.condpulse.gateway.queue.LocalCommandQueue;
import br.com.condpulse.gateway.queue.LocalCommandQueue.CommandType;
import br.com.condpulse.gateway.queue.LocalCommandQueue.EnqueueOutcome;
import br.com.condpulse.gateway.queue.LocalCommandQueue.Status;
import br.com.condpulse.gateway.spi.Spi.OperationState;
import br.com.condpulse.gateway.support.TestSupport;
import org.junit.jupiter.api.Test;
import static br.com.condpulse.gateway.support.TestSupport.command;
import static org.junit.jupiter.api.Assertions.*;

/** Fila local: idempotência, revogação vencendo, capacidade e reinício sem repetição às cegas. */
class LocalCommandQueueTest {
 private static final Map<String,Object> RIGHTS=Map.of("rights",List.of(Map.of("channelId","c1","scheduleRef","sempre")));

 @Test void mesmaChaveDeIdempotenciaNaoDuplicaNemDepoisDeReiniciar() {
  var dir=TestSupport.tempDir();
  var clock=new TestSupport.TestClock();
  try(var q=new LocalCommandQueue(dir,clock,10,20)) {
   assertEquals(EnqueueOutcome.ACCEPTED,q.enqueue(command("a","d1","p1",CommandType.APPLY_RIGHTS,1,RIGHTS)).outcome());
   assertEquals(EnqueueOutcome.DUPLICATE,q.enqueue(command("a","d1","p1",CommandType.APPLY_RIGHTS,1,RIGHTS)).outcome());
  }
  try(var q=new LocalCommandQueue(dir,clock,10,20)) {
   assertEquals(EnqueueOutcome.DUPLICATE,q.enqueue(command("a","d1","p1",CommandType.APPLY_RIGHTS,1,RIGHTS)).outcome());
   assertEquals(1,q.pending("d1").size());
  }
 }

 @Test void revogacaoSaiPrimeiroESuperaAplicacaoAntiga() {
  try(var q=new LocalCommandQueue(TestSupport.tempDir(),new TestSupport.TestClock(),10,20)) {
   q.enqueue(command("ap","d1","p1",CommandType.APPLY_RIGHTS,3,RIGHTS));
   q.enqueue(command("outro","d1","p2",CommandType.APPLY_RIGHTS,1,RIGHTS));
   var r=q.enqueue(command("rv","d1","p1",CommandType.REVOKE_ALL,4,Map.of()));
   assertEquals(List.of("ap"),r.superseded());
   assertEquals(Status.SUPERSEDED,q.get("ap").orElseThrow().status());
   assertEquals("rv",q.next("d1").orElseThrow().command().commandId(),"revogação antes de mensagem antiga");
  }
 }

 @Test void aplicacaoComVersaoAnteriorARevogacaoERecusada() {
  try(var q=new LocalCommandQueue(TestSupport.tempDir(),new TestSupport.TestClock(),10,20)) {
   q.enqueue(command("rv","d1","p1",CommandType.REVOKE_ALL,5,Map.of()));
   var late=q.enqueue(command("velha","d1","p1",CommandType.APPLY_RIGHTS,5,RIGHTS));
   assertEquals(EnqueueOutcome.REJECTED_STALE,late.outcome());
   var newer=q.enqueue(command("nova","d1","p1",CommandType.APPLY_RIGHTS,6,RIGHTS));
   assertEquals(EnqueueOutcome.ACCEPTED,newer.outcome());
  }
 }

 @Test void filaCheiaAceitaRevogacaoAteOLimiteRigidoESemDescartarEmSilencio() {
  try(var q=new LocalCommandQueue(TestSupport.tempDir(),new TestSupport.TestClock(),2,3)) {
   q.enqueue(command("1","d1","p1",CommandType.APPLY_RIGHTS,1,RIGHTS));
   q.enqueue(command("2","d1","p2",CommandType.APPLY_RIGHTS,1,RIGHTS));
   assertEquals(EnqueueOutcome.REJECTED_FULL,q.enqueue(command("3","d1","p3",CommandType.APPLY_RIGHTS,1,RIGHTS)).outcome());
   assertEquals(EnqueueOutcome.ACCEPTED,q.enqueue(command("4","d1","p4",CommandType.REVOKE_ALL,1,Map.of())).outcome());
   assertEquals(EnqueueOutcome.REJECTED_FULL,q.enqueue(command("5","d1","p5",CommandType.REVOKE_ALL,1,Map.of())).outcome(),
    "limite rígido: o chamador recebe a recusa e alarma");
  }
 }

 @Test void reinicioComComandoEmExecucaoExigeLeituraAntesDeReenviar() {
  var dir=TestSupport.tempDir();
  var clock=new TestSupport.TestClock();
  try(var q=new LocalCommandQueue(dir,clock,10,20)) {
   q.enqueue(command("a","d1","p1",CommandType.APPLY_RIGHTS,1,RIGHTS));
   q.mark("a",Status.IN_FLIGHT,null,null);
  }
  try(var q=new LocalCommandQueue(dir,clock,10,20)) {
   var e=q.get("a").orElseThrow();
   assertEquals(Status.NEEDS_READBACK,e.status());
   assertEquals(OperationState.UNKNOWN,e.lastState());
   assertEquals("GATEWAY_RESTARTED_IN_FLIGHT",e.lastReason());
  }
 }

 @Test void supersessaoDecorreDaRevogacaoTambemAoRelerODiario() {
  var dir=TestSupport.tempDir();
  var clock=new TestSupport.TestClock();
  try(var q=new LocalCommandQueue(dir,clock,10,20)) { q.enqueue(command("ap","d1","p1",CommandType.APPLY_RIGHTS,1,RIGHTS)); }
  // Queda logo depois de gravar a revogação: o diário não tem nenhum registro de supersessão.
  try(var j=new br.com.condpulse.gateway.journal.Journal(dir.resolve("sync-queue.jsonl"))) {
   var rec=new java.util.LinkedHashMap<String,Object>();
   var cmd=new java.util.LinkedHashMap<String,Object>();
   cmd.put("commandId","rv"); cmd.put("idempotencyKey","idem-rv"); cmd.put("deviceId","d1"); cmd.put("subjectRef","p1");
   cmd.put("type","REVOKE_ALL"); cmd.put("desiredVersion",2L); cmd.put("payload",Map.of()); cmd.put("issuedAt",TestSupport.START.toString());
   rec.put("t","enq"); rec.put("cmd",cmd); rec.put("at",clock.instant().toString());
   j.append(rec);
  }
  try(var q=new LocalCommandQueue(dir,clock,10,20)) {
   assertEquals(Status.SUPERSEDED,q.get("ap").orElseThrow().status());
   assertEquals("rv",q.next("d1").orElseThrow().command().commandId());
   q.mark("rv",Status.DONE,OperationState.CONFIRMED,null);
   assertTrue(q.next("d1").isEmpty(),"aplicação antiga não volta depois da revogação");
  }
 }
}
