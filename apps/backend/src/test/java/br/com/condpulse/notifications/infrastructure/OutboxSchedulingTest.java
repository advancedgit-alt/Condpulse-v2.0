package br.com.condpulse.notifications.infrastructure;

import br.com.condpulse.notifications.application.OutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Laço do worker: só existe no perfil worker com a flag ligada e só faz passadas depois da aplicação pronta. */
class OutboxSchedulingTest {
 // Perfil pela propriedade: ela vale antes do registro dos beans (OutboxWorker também é @Profile("worker")).
 private static ApplicationContextRunner runner(String profile) {
  return new ApplicationContextRunner().withPropertyValues("spring.profiles.active="+profile)
   .withUserConfiguration(OutboxScheduling.class).withBean(OutboxWorker.class,()->mock(OutboxWorker.class));
 }

 @Test void loopExistsOnlyInTheWorkerProfileWithTheFlagOn() {
  runner("worker").run(context->assertTrue(context.getBeansOfType(OutboxScheduling.class).isEmpty(),"Sem a flag, nada processa"));
  runner("worker").withPropertyValues("condpulse.worker.outbox-enabled=false")
   .run(context->assertTrue(context.getBeansOfType(OutboxScheduling.class).isEmpty(),"Flag desligada"));
  runner("api").withPropertyValues("condpulse.worker.outbox-enabled=true")
   .run(context->assertTrue(context.getBeansOfType(OutboxScheduling.class).isEmpty(),"Perfil da API nunca processa o outbox"));
  runner("worker").withPropertyValues("condpulse.worker.outbox-enabled=true")
   .run(context->assertEquals(1,context.getBeansOfType(OutboxScheduling.class).size()));
 }

 @Test void noPassBeforeTheApplicationIsReady() {
  var worker=mock(OutboxWorker.class);
  var scheduling=new OutboxScheduling(worker);
  scheduling.poll();
  verifyNoInteractions(worker);
  scheduling.ready();
  scheduling.poll();
  verify(worker).runOnce();
 }

 @Test void aFailedPassIsLoggedAndTheNextOneRuns() {
  var worker=mock(OutboxWorker.class);
  when(worker.runOnce()).thenThrow(new IllegalStateException("PESSOA FICTICIA")).thenReturn(0);
  var scheduling=new OutboxScheduling(worker);
  scheduling.ready();
  assertDoesNotThrow(scheduling::poll);
  assertDoesNotThrow(scheduling::poll);
  verify(worker,times(2)).runOnce();
 }
}
