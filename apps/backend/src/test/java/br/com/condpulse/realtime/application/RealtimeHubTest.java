package br.com.condpulse.realtime.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

/** Regras do canal sem servidor: mudança vira só nomes de assuntos; perda de acesso, prazo ou erro encerram com motivo. */
class RealtimeHubTest {
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");

 static final class MutableClock extends Clock {
  Instant now=NOW;
  @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
  @Override public Clock withZone(ZoneId zone) { return this; }
  @Override public Instant instant() { return now; }
 }
 static final class RecordingSink implements RealtimeHub.Sink {
  final List<String> events=new ArrayList<>(); boolean completed; boolean broken;
  @Override public void send(String event,String data) throws IOException {
   if(broken) throw new IOException("gone"); events.add(event+" "+data);
  }
  @Override public void complete() { completed=true; }
 }

 private final MutableClock clock=new MutableClock();
 private final RealtimeHub hub=new RealtimeHub(new RealtimeHub.Settings(Duration.ofSeconds(5),Duration.ofSeconds(60),3,2),clock);
 private final Map<String,String> state=new HashMap<>(Map.of("parcels","0:0:","notifications","0:0:"));

 private UUID open(RecordingSink sink,RealtimeHub.Credential credential,AtomicReference<RuntimeException> failure) {
  return hub.register(ACCOUNT,credential,()->{ if(failure.get()!=null) throw failure.get(); return Map.copyOf(state); },Map.copyOf(state),sink);
 }

 @Test void sendsPingWithoutChangesAndOnlyTopicNamesWhenSomethingChanged() {
  var sink=new RecordingSink();
  open(sink,()->{},new AtomicReference<>());
  hub.tick();
  state.put("parcels","1:0:2026-10-29 12:00:01+00"); state.put("notifications","1:1:2026-10-29 12:00:02+00");
  hub.tick();
  hub.tick();
  assertEquals(List.of("ping {}","changed {\"topics\":[\"notifications\",\"parcels\"]}","ping {}"),sink.events);
  assertFalse(sink.completed); assertEquals(1,hub.open());
 }

 @Test void lostAccessRevokedSessionOrExpiredTokenEndTheChannelWithAReason() {
  var revoked=new RecordingSink();
  var failure=new AtomicReference<RuntimeException>();
  open(revoked,()->{},failure);
  failure.set(new BusinessProblem("UNIT_ACCESS_DENIED",404,"x"));
  hub.tick();
  assertEquals(List.of("end {\"reason\":\"revoked\"}"),revoked.events); assertTrue(revoked.completed);

  var loggedOut=new RecordingSink();
  open(loggedOut,()->{ throw new BusinessProblem("SESSION_EXPIRED",401,"x"); },new AtomicReference<>());
  var expired=new RecordingSink();
  open(expired,()->{ throw new JwtException("expired"); },new AtomicReference<>());
  hub.tick();
  assertEquals(List.of("end {\"reason\":\"revoked\"}"),loggedOut.events);
  assertEquals(List.of("end {\"reason\":\"expired\"}"),expired.events);
  assertEquals(0,hub.open(),"Canal encerrado não volta a ser consultado");
 }

 @Test void credentialIsCheckedBeforeTheStateSoARevokedSessionNeverReceivesChanges() {
  var sink=new RecordingSink();
  var loads=new ArrayList<String>();
  hub.register(ACCOUNT,()->{ throw new BusinessProblem("SESSION_EXPIRED",401,"x"); },()->{ loads.add("load"); return Map.of("parcels","9"); },
   Map.copyOf(state),sink);
  hub.tick();
  assertTrue(loads.isEmpty()); assertEquals(List.of("end {\"reason\":\"revoked\"}"),sink.events);
 }

 @Test void dependencyFailureAndUnexpectedErrorsEndAsUnavailableWithoutTouchingOtherChannels() {
  var down=new RecordingSink();
  var failure=new AtomicReference<RuntimeException>(new BusinessProblem("REALTIME_DEPENDENCY_UNAVAILABLE",503,"x"));
  open(down,()->{},failure);
  var odd=new RecordingSink();
  open(odd,()->{ throw new IllegalStateException("boom"); },new AtomicReference<>());
  var fine=new RecordingSink();
  hub.register(UUID.randomUUID(),()->{},()->Map.copyOf(state),Map.copyOf(state),fine);
  hub.tick();
  assertEquals(List.of("end {\"reason\":\"unavailable\"}"),down.events);
  assertEquals(List.of("end {\"reason\":\"unavailable\"}"),odd.events);
  assertEquals(List.of("ping {}"),fine.events); assertEquals(1,hub.open());
 }

 @Test void channelLivesOnlyUntilItsDeadlineAndShutdownAsksEveryoneToReconnect() {
  var old=new RecordingSink();
  open(old,()->{},new AtomicReference<>());
  clock.now=NOW.plusSeconds(60);
  hub.tick();
  assertEquals(List.of("end {\"reason\":\"timeout\"}"),old.events);
  var live=new RecordingSink();
  open(live,()->{},new AtomicReference<>());
  hub.close();
  assertEquals(List.of("end {\"reason\":\"shutdown\"}"),live.events); assertTrue(live.completed); assertEquals(0,hub.open());
 }

 @Test void clientThatLeftIsDroppedWithoutError() {
  var sink=new RecordingSink(); sink.broken=true;
  open(sink,()->{},new AtomicReference<>());
  hub.tick();
  assertEquals(0,hub.open());
 }

 @Test void limitsPerAccountAndPerInstanceRefuseNewChannelsWithoutDroppingOpenOnes() {
  open(new RecordingSink(),()->{},new AtomicReference<>());
  UUID second=open(new RecordingSink(),()->{},new AtomicReference<>());
  var perAccount=assertThrows(BusinessProblem.class,()->open(new RecordingSink(),()->{},new AtomicReference<>()));
  assertEquals("REALTIME_LIMIT_REACHED",perAccount.code()); assertEquals(429,perAccount.status());
  hub.register(UUID.randomUUID(),()->{},Map::of,Map.of(),new RecordingSink());
  assertEquals(429,assertThrows(BusinessProblem.class,()->hub.register(UUID.randomUUID(),()->{},Map::of,Map.of(),new RecordingSink())).status());
  assertEquals(3,hub.open());
  hub.remove(second);
  assertDoesNotThrow(()->open(new RecordingSink(),()->{},new AtomicReference<>()));
 }

 @Test void invalidSettingsAreRefused() {
  assertThrows(IllegalArgumentException.class,()->new RealtimeHub.Settings(Duration.ZERO,Duration.ofSeconds(5),1,1));
  assertThrows(IllegalArgumentException.class,()->new RealtimeHub.Settings(Duration.ofSeconds(5),Duration.ofSeconds(1),1,1));
  assertThrows(IllegalArgumentException.class,()->new RealtimeHub.Settings(Duration.ofSeconds(5),Duration.ofSeconds(60),0,1));
 }
}
