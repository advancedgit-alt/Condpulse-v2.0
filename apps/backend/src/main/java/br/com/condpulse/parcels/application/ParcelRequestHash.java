package br.com.condpulse.parcels.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Hash da intenção normalizada: repetir com a mesma chave e os mesmos dados devolve o mesmo resultado. */
final class ParcelRequestHash {
 private ParcelRequestHash() {}
 static String receive(UUID unit,int quantity,String description,String location,boolean confirmDuplicate) {
  return digest("RECEIVE|"+unit+"|"+quantity+"|"+description.length()+":"+description+"|"+location.length()+":"+location
   +"|"+confirmDuplicate);
 }
 static String deliver(UUID parcel,UUID recipientMembership,long expectedVersion) {
  return digest("DELIVER|"+parcel+"|"+recipientMembership+"|"+expectedVersion);
 }
 static String voidParcel(UUID parcel,String reason,long expectedVersion) {
  return digest("VOID|"+parcel+"|"+reason.length()+":"+reason+"|"+expectedVersion);
 }
 static String delegate(UUID parcel,String name,String document,java.time.Instant validUntil) {
  return digest("DELEGATE|"+parcel+"|"+name.length()+":"+name+"|"+document+"|"+validUntil);
 }
 /** O código não entra no hash da intenção (8 dígitos seriam recuperáveis do hash guardado). */
 static String codeHandoff(UUID parcel,long expectedVersion) {
  return digest("CODE_HANDOFF|"+parcel+"|"+expectedVersion);
 }
 static String digest(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
}
