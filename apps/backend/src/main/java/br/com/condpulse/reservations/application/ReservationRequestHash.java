package br.com.condpulse.reservations.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;

/** Hash semântico do DTO já tipado; ordem/whitespace JSON não mudam a intenção. */
public final class ReservationRequestHash {
 private ReservationRequestHash() {}
 public static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw new BusinessProblem("IDEMPOTENCY_KEY_INVALID",400,"Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 public static String create(ReservationModels.Create request) {
  if(request==null || request.unitId()==null || request.amenityId()==null || request.startsAt()==null
      || request.endsAt()==null || request.acceptedPolicyVersion()<1)
   throw invalid();
  return digest("CREATE|"+request.unitId()+"|"+request.amenityId()+"|"+request.startsAt()+"|"
    +request.endsAt()+"|"+request.acceptedPolicyVersion());
 }
 public static String cancel(UUID reservationId,ReservationModels.Cancel request) {
  if(reservationId==null || request==null || request.unitId()==null || request.expectedVersion()<0) throw invalid();
  return digest("CANCEL|"+reservationId+"|"+request.unitId()+"|"+request.expectedVersion());
 }
 private static BusinessProblem invalid() {
  return new BusinessProblem("RESERVATION_REQUEST_INVALID",400,"Revise os dados da reserva.");
 }
 private static String digest(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
}
