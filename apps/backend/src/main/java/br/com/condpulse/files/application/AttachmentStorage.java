package br.com.condpulse.files.application;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Porta do Storage privado (CP-017). A API nunca recebe os bytes pelo próprio servidor: emite URL assinada curta para
 * o envio direto e depois lê o objeto para conferir tamanho, assinatura do tipo e hash. Falha de rede ou resposta
 * inesperada vira {@link StorageUnavailable}, nunca sucesso.
 */
public interface AttachmentStorage {
 /** URL absoluta para PUT do objeto, válida por pouco tempo. */
 URI signUpload(String bucket,String key);
 /** Objeto lido até {@code limit}+1 bytes; vazio quando não existe. */
 Optional<StoredObject> inspect(String bucket,String key,long limit);
 /** URL absoluta de leitura, válida por {@code lifetime}, com o nome de download indicado. */
 URI signDownload(String bucket,String key,Duration lifetime,String downloadName);

 /** Tamanho lido (pode passar do limite), primeiros bytes e SHA-256 hexadecimal do conteúdo lido. */
 record StoredObject(long size,byte[] head,String sha256) {
  public StoredObject { head=head.clone(); }
  @Override public byte[] head() { return head.clone(); }
 }
 final class StorageUnavailable extends RuntimeException {
  public StorageUnavailable() { super("Storage indisponível."); }
 }
}
