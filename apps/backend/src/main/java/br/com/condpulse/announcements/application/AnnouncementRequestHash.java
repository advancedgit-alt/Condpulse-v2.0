package br.com.condpulse.announcements.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Hash do rascunho normalizado: repetir com a mesma chave e os mesmos dados devolve o mesmo rascunho. */
final class AnnouncementRequestHash {
 private AnnouncementRequestHash() {}
 static String draft(String title,String body,String audience,List<UUID> ids) {
  return digest("ANNOUNCEMENT_DRAFT|"+title.length()+":"+title+"|"+body.length()+":"+body+"|"+audience+"|"
   +String.join(",",ids.stream().map(UUID::toString).toList()));
 }
 private static String digest(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
}
