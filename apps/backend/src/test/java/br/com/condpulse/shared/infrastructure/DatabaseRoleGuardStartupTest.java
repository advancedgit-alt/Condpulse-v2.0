package br.com.condpulse.shared.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A role de execução é conferida durante a criação do contexto, antes dos componentes com ciclo de vida — entre eles o
 * servidor web, que abre a porta. Assim nenhuma requisição chega antes da conferência e a prontidão não fica um tempo
 * em OUT_OF_SERVICE com a porta já aberta (503 na primeira chamada depois de um início a frio).
 */
class DatabaseRoleGuardStartupTest {
 /** Fica no lugar do servidor web: só começa depois de todos os singletons criados. */
 static final class PortOpener implements SmartLifecycle {
  final AtomicBoolean started=new AtomicBoolean();
  @Override public void start() { started.set(true); }
  @Override public void stop() { started.set(false); }
  @Override public boolean isRunning() { return started.get(); }
 }

 private static JdbcTemplate jdbc(boolean unsafe,int unprotected) {
  var jdbc=mock(JdbcTemplate.class);
  when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenReturn(unsafe);
  when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(unprotected);
  return jdbc;
 }

 private static ApplicationContextRunner runner(String profile,JdbcTemplate jdbc,PortOpener port) {
  return new ApplicationContextRunner().withPropertyValues("spring.profiles.active="+profile)
   .withBean(JdbcTemplate.class,()->jdbc).withBean(PortOpener.class,()->port).withUserConfiguration(DatabaseRoleGuard.class);
 }

 @Test void unsafeRoleStopsTheStartupBeforeThePortOpens() {
  var port=new PortOpener();
  runner("api",jdbc(true,0),port).run(context->{
   assertNotNull(context.getStartupFailure(),"Role com privilégio a mais derruba a criação do contexto");
   assertInstanceOf(IllegalStateException.class,rootCause(context.getStartupFailure()));
  });
  assertFalse(port.started.get(),"Nada com ciclo de vida (servidor web) começa antes da conferência");
 }

 @Test void schemaWithoutCompleteRlsStopsTheStartup() {
  var port=new PortOpener();
  runner("worker",jdbc(false,1),port).run(context->assertNotNull(context.getStartupFailure()));
  assertFalse(port.started.get());
 }

 @Test void safeRoleIsCheckedOnceBeforeThePortOpens() {
  var port=new PortOpener();
  var jdbc=jdbc(false,0);
  doAnswer(call->{ assertFalse(port.started.get(),"Conferência antes da porta"); return false; })
   .when(jdbc).queryForObject(anyString(),eq(Boolean.class));
  runner("api",jdbc,port).run(context->{
   assertNull(context.getStartupFailure());
   assertTrue(port.started.get());
  });
  verify(jdbc).queryForObject(anyString(),eq(Boolean.class));
 }

 @Test void otherProfilesDoNotCheck() {
  var jdbc=jdbc(true,1);
  runner("migrate",jdbc,new PortOpener()).run(context->assertNull(context.getStartupFailure()));
  verifyNoInteractions(jdbc);
 }

 private static Throwable rootCause(Throwable error) {
  while(error.getCause()!=null) error=error.getCause();
  return error;
 }
}
