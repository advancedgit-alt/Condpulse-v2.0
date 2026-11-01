package br.com.condpulse.visits.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Hash da intenção normalizada: repetir com a mesma chave e os mesmos dados devolve o mesmo registro. */
final class VisitRequestHash {
 private VisitRequestHash() {}
 static String permit(UUID unit,String visitor,String document,String purpose,Instant from,Instant until) {
  return digest("PERMIT|"+unit+"|"+field(visitor)+"|"+field(document)+"|"+field(purpose)+"|"+from+"|"+until);
 }
 static String guest(UUID reservation,String visitor,String document) {
  return digest("RESERVATION_GUEST|"+reservation+"|"+field(visitor)+"|"+field(document));
 }
 static String rule(UUID unit,String visitor,String document,String purpose,java.util.List<Integer> days,String start,String end,
   java.time.LocalDate from,java.time.LocalDate until) {
  return digest("VISIT_RULE|"+unit+"|"+field(visitor)+"|"+field(document)+"|"+field(purpose)+"|"+days+"|"+start+"|"+end+"|"+from+"|"+until);
 }
 static String arrival(UUID unit,String visitor,String document,UUID permit) {
  return digest("ARRIVAL|"+unit+"|"+field(visitor)+"|"+field(document)+"|"+permit);
 }
 static String entry(UUID arrival,UUID permit,String evidence,String note) {
  return digest("ACCESS_ENTRY|"+arrival+"|"+permit+"|"+evidence+"|"+field(note));
 }
 /** Com o tipo de permanência (V055); sem tipo, o mesmo hash de antes (repetições antigas continuam valendo). */
 static String entry(UUID arrival,UUID permit,String evidence,String note,String stayKind) {
  return stayKind==null?entry(arrival,permit,evidence,note):digest("ACCESS_ENTRY|"+arrival+"|"+permit+"|"+evidence+"|"+field(note)+"|"+stayKind);
 }
 static String exit(UUID entry,String note) { return digest("ACCESS_EXIT|"+entry+"|"+field(note)); }
 static String correction(UUID event,String reason) { return digest("ACCESS_CORRECTION|"+event+"|"+field(reason)); }
 private static String field(String value) { return value==null?"-":value.length()+":"+value; }
 private static String digest(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
}
