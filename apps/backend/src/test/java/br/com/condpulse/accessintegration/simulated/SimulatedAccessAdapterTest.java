package br.com.condpulse.accessintegration.simulated;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import br.com.condpulse.accessintegration.application.AccessModels;
import br.com.condpulse.accessintegration.application.AccessModels.Action;
import br.com.condpulse.accessintegration.application.AccessModels.Conduct;
import br.com.condpulse.accessintegration.application.AccessModels.Outcome;
import br.com.condpulse.accessintegration.simulated.SimulatedAccessAdapter.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** CP-043: cada estado do contrato interno, de forma determinística e sem nenhum equipamento real. */
class SimulatedAccessAdapterTest {
 private static final String CONNECTOR="conector-simulado-a";
 private static final String CREDENTIAL="credencial-ficticia-1";
 private static final Instant START=Instant.parse("2030-01-01T12:00:00Z");

 /** Relógio controlado pelo teste. */
 private static final class TestClock extends Clock {
  private Instant now=START;
  void advance(Duration duration) { now=now.plus(duration); }
  @Override public ZoneId getZone() { return ZoneOffset.UTC; }
  @Override public Clock withZone(ZoneId zone) { return this; }
  @Override public Instant instant() { return now; }
 }

 private TestClock clock;
 private SimulatedAccessAdapter adapter;

 @BeforeEach void setup() {
  clock=new TestClock();
  adapter=new SimulatedAccessAdapter(CONNECTOR,clock,false,List.of("leitor-1","leitor-2","leitor-3"));
 }
 private AccessModels.Application apply(long version,String... devices) {
  return adapter.applyCredential(new AccessModels.ApplyCredential(CONNECTOR,"corr-"+version,"pessoa-ficticia",CREDENTIAL,version,List.of(devices),
   START,START.plus(Duration.ofDays(30)),"foto-temporaria"));
 }
 private AccessModels.Application revoke(long version,String... devices) {
  return adapter.revokeCredential(new AccessModels.RevokeCredential(CONNECTOR,"corr-r"+version,CREDENTIAL,version,List.of(devices),"Vínculo revogado"));
 }
 private AccessModels.Application query(long version,String... devices) {
  return adapter.getApplication(new AccessModels.ApplicationQuery(CONNECTOR,CREDENTIAL,version,List.of(devices)));
 }

 @Test void eachOutcomeCarriesItsConductAndOnlyApplicationActivates() {
  assertEquals(Conduct.AWAIT_CONFIRMATION,Outcome.ACCEPTED.conduct());
  assertEquals(Conduct.CONFIRM_DESTINATION,Outcome.APPLIED.conduct());
  assertEquals(Conduct.FIX_BEFORE_RETRY,Outcome.REJECTED.conduct());
  assertEquals(Conduct.BACKOFF,Outcome.RATE_LIMITED.conduct());
  assertEquals(Conduct.QUERY_BEFORE_RETRY,Outcome.UNKNOWN.conduct());
  assertEquals(Conduct.KEEP_PENDING,Outcome.UNAVAILABLE.conduct());
  for(Outcome outcome:Outcome.values()) assertEquals(outcome==Outcome.APPLIED,outcome.activates(),outcome.name());
 }

 @Test void confirmedApplicationAndDuplicateCommandApplyOnce() {
  var first=apply(1,"leitor-1");
  assertEquals(Outcome.APPLIED,first.destination("leitor-1").outcome());
  assertEquals(Long.valueOf(1),first.destination("leitor-1").appliedVersion());
  assertEquals(START,first.destination("leitor-1").confirmedAt());
  var again=apply(1,"leitor-1");
  assertEquals(first.jobRef(),again.jobRef(),"Mesmo comando, mesmo trabalho");
  assertEquals(first.destination("leitor-1"),again.destination("leitor-1"));
  assertEquals(1,adapter.applications("leitor-1"),"Repetição não aplica de novo");
 }

 @Test void partialConfirmationShowsEachDestination() {
  adapter.scenario("leitor-2",Scenario.REJECT);
  adapter.scenario("leitor-3",Scenario.OFFLINE);
  var result=apply(1,"leitor-1","leitor-2","leitor-3");
  assertFalse(result.fullyApplied());
  assertEquals(Outcome.APPLIED,result.destination("leitor-1").outcome());
  assertEquals(Outcome.REJECTED,result.destination("leitor-2").outcome());
  assertEquals("DEVICE_REJECTED",result.destination("leitor-2").reason());
  assertEquals(Outcome.UNAVAILABLE,result.destination("leitor-3").outcome());
  assertEquals(Long.valueOf(1),result.summary().get(Outcome.APPLIED));
  // Repetir não muda o recusado nem reaplica o confirmado; o que estava fora do ar é tentado de novo.
  adapter.scenario("leitor-3",Scenario.CONFIRM);
  var retried=apply(1,"leitor-1","leitor-2","leitor-3");
  assertEquals(Outcome.APPLIED,retried.destination("leitor-3").outcome());
  assertEquals(Outcome.REJECTED,retried.destination("leitor-2").outcome());
  assertEquals(1,adapter.applications("leitor-1"));
  assertEquals(0,adapter.applications("leitor-2"));
 }

 @Test void timeoutIsUnknownAndTheQueryTellsWhetherItArrived() {
  adapter.scenario("leitor-1",Scenario.TIMEOUT_APPLIED);
  adapter.scenario("leitor-2",Scenario.TIMEOUT_LOST);
  var sent=apply(1,"leitor-1","leitor-2");
  assertEquals(Outcome.UNKNOWN,sent.destination("leitor-1").outcome());
  assertEquals(Outcome.UNKNOWN,sent.destination("leitor-2").outcome());
  var state=query(1,"leitor-1","leitor-2");
  assertEquals(Outcome.APPLIED,state.destination("leitor-1").outcome(),"Chegou apesar da resposta perdida");
  assertEquals(Outcome.UNKNOWN,state.destination("leitor-2").outcome());
  assertEquals("VERSION_NOT_PRESENT",state.destination("leitor-2").reason());
  assertNull(state.destination("leitor-2").appliedVersion());
  // Depois da consulta, repetir é seguro: a aplicação é idempotente pela versão.
  adapter.scenario("leitor-1",Scenario.CONFIRM);
  adapter.scenario("leitor-2",Scenario.CONFIRM);
  var retried=apply(1,"leitor-1","leitor-2");
  assertTrue(retried.fullyApplied());
  assertEquals(1,adapter.applications("leitor-1"),"Não aplica duas vezes o que já tinha chegado");
  assertEquals(1,adapter.applications("leitor-2"));
 }

 @Test void acknowledgementIsNotApplicationUntilConfirmed() {
  adapter.scenario("leitor-1",Scenario.ACK_ONLY);
  var sent=apply(1,"leitor-1");
  assertEquals(Outcome.ACCEPTED,sent.destination("leitor-1").outcome());
  assertFalse(sent.destination("leitor-1").outcome().activates());
  assertEquals(Outcome.ACCEPTED,query(1,"leitor-1").destination("leitor-1").outcome());
  assertEquals(1,adapter.health(CONNECTOR).pendingCommands());
  assertEquals(1,adapter.deliverPending("leitor-1"));
  assertEquals(Outcome.APPLIED,query(1,"leitor-1").destination("leitor-1").outcome());
  assertEquals(Outcome.APPLIED,apply(1,"leitor-1").destination("leitor-1").outcome(),"Repetição mostra a confirmação que chegou");
  assertEquals(0,adapter.health(CONNECTOR).pendingCommands());
 }

 @Test void revocationHasPriorityAndOldConfirmationNeverReactivates() {
  apply(1,"leitor-1");
  adapter.scenario("leitor-1",Scenario.ACK_ONLY);
  apply(2,"leitor-1");
  adapter.scenario("leitor-1",Scenario.CONFIRM);
  var revoked=revoke(3,"leitor-1");
  assertEquals(Outcome.APPLIED,revoked.destination("leitor-1").outcome());
  assertEquals(Action.REVOKE,revoked.destination("leitor-1").appliedAction());
  // A confirmação tardia da versão 2 e o reenvio atrasado da versão 2 não reativam.
  assertEquals(0,adapter.deliverPending("leitor-1"));
  var late=apply(2,"leitor-1");
  assertEquals(Outcome.REJECTED,late.destination("leitor-1").outcome());
  assertEquals("STALE_VERSION",late.destination("leitor-1").reason());
  assertEquals(Long.valueOf(3),late.destination("leitor-1").appliedVersion());
  assertEquals(Action.REVOKE,query(3,"leitor-1").destination("leitor-1").appliedAction());
  assertEquals(Outcome.REJECTED,query(2,"leitor-1").destination("leitor-1").outcome());
  // Mesma versão com outra ação é conflito, não substituição.
  assertEquals("VERSION_CONFLICT",apply(3,"leitor-1").destination("leitor-1").reason());
  assertEquals(2,adapter.applications("leitor-1"));
 }

 @Test void rateLimitAndOfflineKeepThePendingWithoutApplying() {
  adapter.scenario("leitor-1",Scenario.RATE_LIMITED);
  adapter.scenario("leitor-2",Scenario.OFFLINE);
  var result=apply(1,"leitor-1","leitor-2");
  assertEquals(Outcome.RATE_LIMITED,result.destination("leitor-1").outcome());
  assertEquals(SimulatedAccessAdapter.RETRY_AFTER,result.destination("leitor-1").retryAfter());
  assertEquals(Outcome.UNAVAILABLE,result.destination("leitor-2").outcome());
  assertEquals(Outcome.UNAVAILABLE,query(1,"leitor-2").destination("leitor-2").outcome());
  assertEquals(0,adapter.applications("leitor-1")+adapter.applications("leitor-2"));
 }

 @Test void healthShowsOfflineDevicesAndClockSkew() {
  adapter.clockSkew("leitor-1",Duration.ofSeconds(-4));
  adapter.scenario("leitor-2",Scenario.OFFLINE);
  clock.advance(Duration.ofMinutes(5));
  var health=adapter.health(CONNECTOR);
  var first=health.devices().get(0);
  var second=health.devices().get(1);
  assertTrue(first.online()); assertEquals(clock.instant(),first.lastHeartbeat()); assertEquals(Duration.ofSeconds(-4),first.clockSkew());
  assertFalse(second.online()); assertEquals(START,second.lastHeartbeat(),"Equipamento fora do ar não tem batimento novo");
 }

 @Test void eventsArePagedWithinTheWindowAndDuplicatesKeepTheirId() {
  adapter.emitPassage("leitor-1",CREDENTIAL,AccessModels.Direction.ENTRY,true);
  clock.advance(Duration.ofMinutes(1));
  adapter.emitPassage("leitor-2",CREDENTIAL,null,false);
  adapter.scenario("leitor-3",Scenario.OFFLINE);
  var page=adapter.getEvents(new AccessModels.EventQuery(CONNECTOR,null,START,START.plus(Duration.ofHours(1)),2));
  assertEquals(2,page.events().size());
  assertEquals(page.events().get(0).eventId(),page.events().get(1).eventId(),"Parceiro reenviou o mesmo evento");
  assertEquals(AccessModels.Direction.ENTRY,page.events().get(0).direction());
  assertNotNull(page.nextCursor());
  var next=adapter.getEvents(new AccessModels.EventQuery(CONNECTOR,page.nextCursor(),START,START.plus(Duration.ofHours(1)),10));
  assertEquals(List.of(AccessModels.EventKind.PASSAGE_DETECTED,AccessModels.EventKind.DEVICE_OFFLINE),next.events().stream().map(AccessModels.Event::kind).toList());
  assertNull(next.events().get(0).direction(),"Direção ausente continua ausente");
  assertNull(next.nextCursor());
  assertEquals(0,adapter.getEvents(new AccessModels.EventQuery(CONNECTOR,null,START.plus(Duration.ofHours(2)),START.plus(Duration.ofHours(3)),10)).events().size());
  assertThrows(IllegalArgumentException.class,()->new AccessModels.EventQuery(CONNECTOR,null,START,START.plus(Duration.ofHours(25)),10));
  assertThrows(IllegalArgumentException.class,()->new AccessModels.EventQuery(CONNECTOR,null,START,START.plus(Duration.ofHours(1)),101));
  assertThrows(IllegalArgumentException.class,()->adapter.getEvents(new AccessModels.EventQuery(CONNECTOR,"outro-cursor",START,START.plus(Duration.ofHours(1)),10)));
 }

 @Test void remoteOpeningIsDisabledAndNeverQueued() {
  var request=new AccessModels.OpenRequest(CONNECTOR,"abrir-1","leitor-1","autorizacao-1",START.plus(Duration.ofSeconds(10)));
  assertEquals(AccessModels.OpenOutcome.REJECTED,adapter.requestOpen(request).outcome());
  assertEquals("OPEN_NOT_ENABLED",adapter.requestOpen(request).reason());
  assertFalse(adapter.capabilities(CONNECTOR).openEnabled());

  var enabled=new SimulatedAccessAdapter(CONNECTOR,clock,true,List.of("leitor-1"));
  assertEquals(AccessModels.OpenOutcome.EXECUTED,enabled.requestOpen(request).outcome());
  assertEquals(AccessModels.OpenOutcome.EXECUTED,enabled.requestOpen(request).outcome(),"Repetir devolve a resposta, sem reabrir");
  var relays=enabled.getEvents(new AccessModels.EventQuery(CONNECTOR,null,START,START.plus(Duration.ofHours(1)),10)).events();
  assertEquals(1,relays.stream().filter(e->e.kind()==AccessModels.EventKind.RELAY_TRIGGERED).count());
  enabled.scenario("leitor-1",Scenario.OFFLINE);
  var offline=new AccessModels.OpenRequest(CONNECTOR,"abrir-2","leitor-1","autorizacao-1",START.plus(Duration.ofSeconds(10)));
  assertEquals("DEVICE_OFFLINE",enabled.requestOpen(offline).reason());
  enabled.scenario("leitor-1",Scenario.CONFIRM);
  assertEquals("DEVICE_OFFLINE",enabled.requestOpen(offline).reason(),"Depois de reconectar, o pedido antigo não é enviado");
  clock.advance(Duration.ofSeconds(11));
  var expired=new AccessModels.OpenRequest(CONNECTOR,"abrir-3","leitor-1","autorizacao-1",START.plus(Duration.ofSeconds(10)));
  assertEquals("EXPIRED",enabled.requestOpen(expired).reason());
  assertEquals(1,enabled.getEvents(new AccessModels.EventQuery(CONNECTOR,null,START,START.plus(Duration.ofHours(1)),10)).events().stream()
   .filter(e->e.kind()==AccessModels.EventKind.RELAY_TRIGGERED).count());
 }

 @Test void peopleAreStablePerExternalReferenceAndConnectorIsChecked() {
  var first=adapter.upsertPerson(new AccessModels.UpsertPerson(CONNECTOR,"c1","morador-ficticio-1","PESSOA FICTÍCIA"));
  var again=adapter.upsertPerson(new AccessModels.UpsertPerson(CONNECTOR,"c2","morador-ficticio-1","PESSOA FICTÍCIA"));
  var other=adapter.upsertPerson(new AccessModels.UpsertPerson(CONNECTOR,"c3","morador-ficticio-2",null));
  assertEquals(first.providerPersonId(),again.providerPersonId());
  assertNotEquals(first.providerPersonId(),other.providerPersonId());
  assertThrows(IllegalArgumentException.class,()->adapter.health("conector-de-outro-condominio"));
  assertThrows(IllegalArgumentException.class,()->apply(1,"leitor-desconhecido"));
  assertThrows(IllegalArgumentException.class,()->apply(1,"leitor-1","leitor-1"));
  assertThrows(IllegalArgumentException.class,()->apply(0,"leitor-1"));
 }
}
