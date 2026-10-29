package br.com.condpulse.files.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos dos anexos privados de pedido (CP-017 + anexo do CP-033). Nunca expõem bucket, chave nem URL permanente. */
public final class AttachmentModels {
 private AttachmentModels() {}
 public record Intent(String purpose,UUID entityId,String fileName,String mimeType,Long sizeBytes) {}
 public record Attachment(UUID attachmentId,UUID entityId,String fileName,String mimeType,long sizeBytes,String state,
   String rejection,boolean mine,Instant createdAt,Instant completedAt) {}
 public record Upload(Attachment attachment,URI uploadUrl,String method,String contentType,Instant expiresAt) {}
 public record Download(URI url,Instant expiresAt) {}
 public record Page(List<Attachment> attachments) {
  public Page { attachments=List.copyOf(attachments); }
 }
}
