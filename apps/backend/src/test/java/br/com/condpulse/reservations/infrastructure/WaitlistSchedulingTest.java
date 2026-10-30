package br.com.condpulse.reservations.infrastructure;

import br.com.condpulse.reservations.application.WaitlistSweep;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Passada da fila no worker: só no perfil worker com a flag ligada e só depois da aplicação pronta. */
class WaitlistSchedulingTest {
 private static ApplicationContextRunner runner(String profile) {
  return new ApplicationContextRunner().withPropertyValues("spring.profiles.active="+profile)
   .withUserConfiguration(WaitlistScheduling.class).withBean(WaitlistSweep.class,()->mock(WaitlistSweep.class));
 }

 @Test void loopExistsOnlyInTheWorkerProfileWithTheFlagOn() {
  runner("worker").run(context->assertTrue(context.getBeansOfType(WaitlistScheduling.class).isEmpty(),"Sem a flag, nada roda"));
  runner("api").withPropertyValues("condpulse.worker.waitlist-enabled=true")
   .run(context->assertTrue(context.getBeansOfType(WaitlistScheduling.class).isEmpty(),"A API não roda a passada"));
  runner("worker").withPropertyValues("condpulse.worker.waitlist-enabled=true")
   .run(context->assertEquals(1,context.getBeansOfType(WaitlistScheduling.class).size()));
 }

 @Test void noPassBeforeReadyAndAFailedPassDoesNotStopTheNext() {
  var sweep=mock(WaitlistSweep.class);
  when(sweep.runOnce()).thenThrow(new IllegalStateException("PESSOA FICTICIA")).thenReturn(0);
  var scheduling=new WaitlistScheduling(sweep);
  scheduling.poll();
  verifyNoInteractions(sweep);
  scheduling.ready();
  assertDoesNotThrow(scheduling::poll);
  assertDoesNotThrow(scheduling::poll);
  verify(sweep,times(2)).runOnce();
 }
}
