package br.com.condpulse.audit.application;

import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuditCursorTest {
 @Test void cursorRoundTripsMicrosecondsAndIdentifier() {
  var cursor=new AuditQuery.Cursor(Instant.parse("2026-10-28T23:59:59.123456Z"),UUID.randomUUID());
  var parsed=AuditQuery.Cursor.parse(cursor.encode());
  assertEquals(cursor,parsed); assertFalse(cursor.encode().contains(cursor.id().toString()));
  assertNull(AuditQuery.Cursor.parse(null)); assertNull(AuditQuery.Cursor.parse(" "));
 }
 @Test void malformedCursorIsAStableClientError() {
  var encoder=java.util.Base64.getUrlEncoder().withoutPadding();
  String id=UUID.randomUUID().toString();
  String extreme=encoder.encodeToString((Long.MIN_VALUE+":"+id).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  String beforeRange=encoder.encodeToString(("-1:"+id).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  String nonCanonical=encoder.encodeToString("1700000000000000:1-1-1-1-1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  for(String value:new String[]{"***","bm90LWEtY3Vyc29y","MTIzOm5vdC1hLXV1aWQ","x".repeat(121),extreme,beforeRange,nonCanonical}) {
   var error=assertThrows(BusinessProblem.class,()->AuditQuery.Cursor.parse(value));
   assertEquals("AUDIT_CURSOR_INVALID",error.code()); assertEquals(400,error.status());
  }
 }
}
