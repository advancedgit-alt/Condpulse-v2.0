package br.com.condpulse.identity.bff;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import br.com.condpulse.shared.application.BusinessProblem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthRateLimiterTest {
 private static String key(int marker) {
  byte[] bytes=new byte[32]; java.util.Arrays.fill(bytes,(byte)marker);
  return Base64.getEncoder().encodeToString(bytes);
 }
 @SuppressWarnings({"rawtypes","unchecked"})
 @Test void normalizedLoginUsesPurposeSeparatedHmacKeysAndGlobalGateFirst() {
  var store=mock(AuthRateStore.class); when(store.admit(anyList())).thenReturn(AuthRateLimiter.Admission.permitted());
  var limiter=new AuthRateLimiter(key(17),key(39),store);
  limiter.login(" Resident@Example.Invalid "); limiter.login("resident@example.invalid"); limiter.refresh("a".repeat(43));
  ArgumentCaptor<List<AuthRateLimiter.Bucket>> captured=ArgumentCaptor.forClass((Class)List.class);
  verify(store,times(3)).admit(captured.capture());
  var calls=captured.getAllValues(); assertEquals(calls.get(0),calls.get(1));
  assertEquals(120,calls.getFirst().getFirst().limit());
  assertEquals(60,calls.getFirst().getFirst().seconds());
  assertEquals(5,calls.getFirst().getLast().limit()); assertEquals(900,calls.getFirst().getLast().seconds());
  assertNotEquals(calls.getFirst().getFirst().key(),calls.getFirst().getLast().key());
  assertNotEquals(calls.getFirst().getLast().key(),calls.getLast().getLast().key());
  for(var call:calls) for(var bucket:call) {
   assertTrue(bucket.key().matches("[0-9a-f]{64}"));
   assertFalse(bucket.toString().contains(bucket.key()));
   assertFalse(bucket.toString().contains("resident"));
  }
  assertFalse(limiter.toString().contains(key(17)));
 }
 @Test void differentDeploymentsUseSameBucketsOnlyWhenTheHmacKeyIsTheSame() {
  var store=mock(AuthRateStore.class); when(store.admit(anyList())).thenReturn(AuthRateLimiter.Admission.permitted());
  new AuthRateLimiter(key(1),key(2),store).login("synthetic@example.invalid");
  new AuthRateLimiter(key(3),key(2),store).login("synthetic@example.invalid");
  @SuppressWarnings({"rawtypes","unchecked"}) ArgumentCaptor<List<AuthRateLimiter.Bucket>> captured=ArgumentCaptor.forClass((Class)List.class);
  verify(store,times(2)).admit(captured.capture());
  assertNotEquals(captured.getAllValues().getFirst(),captured.getAllValues().getLast());
 }
 @Test void blankInvalidOrReusedEncryptionKeyIsRejectedWithoutEchoingKey() {
  for(String value:List.of("", "SET_IN_VAULT", Base64.getEncoder().encodeToString(new byte[24]),key(2))) {
   var error=assertThrows(IllegalArgumentException.class,()->new AuthRateLimiter(value,key(2),mock(AuthRateStore.class)));
   assertFalse(error.getMessage().contains("SET_IN_VAULT"));
   assertFalse(error.getMessage().contains(key(2)));
  }
 }
 @Test void dependencyAndTransactionFailuresAreClosed503BeforeProviderCalls() {
  for(RuntimeException failure:List.of(new DataAccessResourceFailureException("SECRET_SENTINEL"),
      new CannotCreateTransactionException("SECRET_SENTINEL"))) {
   var store=mock(AuthRateStore.class); when(store.admit(anyList())).thenThrow(failure);
   var limiter=new AuthRateLimiter(key(1),key(2),store);
   var error=assertThrows(BusinessProblem.class,()->limiter.refresh("a".repeat(43)));
   assertEquals(503,error.status()); assertEquals("AUTH_RATE_LIMIT_UNAVAILABLE",error.code());
   assertFalse(error.getMessage().contains("SECRET_SENTINEL")); assertNull(error.getCause());
  }
 }
 @SuppressWarnings({"rawtypes","unchecked"})
 @Test void recoveryBucketsAreSeparatedFromLoginAndBoundedPerSubject() {
  var store=mock(AuthRateStore.class); when(store.admit(anyList())).thenReturn(AuthRateLimiter.Admission.permitted());
  var limiter=new AuthRateLimiter(key(5),key(6),store);
  limiter.login("pessoa@example.invalid"); limiter.recoveryRequest(" Pessoa@Example.invalid ");
  limiter.recoveryVerify("synthetic-link-hash-0123"); limiter.recoveryComplete("a".repeat(43)); limiter.recoveryComplete("forjado");
  ArgumentCaptor<List<AuthRateLimiter.Bucket>> captured=ArgumentCaptor.forClass((Class)List.class);
  verify(store,times(5)).admit(captured.capture());
  var calls=captured.getAllValues();
  assertNotEquals(calls.get(0).getLast().key(),calls.get(1).getLast().key(),"Pedido de recuperação não consome o limite do login");
  assertEquals(3,calls.get(1).getLast().limit()); assertEquals(900,calls.get(1).getLast().seconds());
  assertEquals(5,calls.get(2).getLast().limit()); assertEquals(10,calls.get(3).getLast().limit());
  assertNotEquals(calls.get(3).getLast().key(),calls.get(4).getLast().key());
  for(var call:calls) assertEquals(2,call.size());
 }
 @Test void denialsAreReturnedWithBoundedServerRetryAfter() {
  var store=mock(AuthRateStore.class); when(store.admit(anyList())).thenReturn(new AuthRateLimiter.Admission(false,899));
  var limiter=new AuthRateLimiter(key(1),key(2),store);
  assertEquals(new AuthRateLimiter.Admission(false,899),limiter.login("synthetic@example.invalid"));
  assertThrows(IllegalArgumentException.class,()->new AuthRateLimiter.Admission(false,0));
  assertThrows(IllegalArgumentException.class,()->new AuthRateLimiter.Admission(true,60));
 }
}
