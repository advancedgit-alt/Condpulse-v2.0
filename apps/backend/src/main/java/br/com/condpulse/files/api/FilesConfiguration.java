package br.com.condpulse.files.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import br.com.condpulse.files.application.AttachmentService;
import br.com.condpulse.files.application.AttachmentStorage;
import br.com.condpulse.files.infrastructure.AttachmentStore;
import br.com.condpulse.files.infrastructure.SupabaseAttachmentStorage;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Arquivos privados atrás de CP_FILES_ENABLED=false. O endereço do Storage vem do emissor do Auth do mesmo projeto
 * (https://{ref}.supabase.co/auth/v1); a chave de serviço (CP_STORAGE_SERVICE_KEY) existe só no servidor.
 */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.files.enabled",havingValue="true")
public class FilesConfiguration {
 static URI storageBase(String issuer) {
  URI uri=URI.create(issuer==null?"":issuer);
  if(!"https".equals(uri.getScheme()) || uri.getHost()==null || !uri.getHost().matches("[a-z0-9]{20}\\.supabase\\.co")
     || !"/auth/v1".equals(uri.getPath()) || uri.getQuery()!=null || uri.getUserInfo()!=null)
   throw new IllegalArgumentException("Emissor do Auth inválido para o Storage.");
  return URI.create("https://"+uri.getHost());
 }
 @Bean AttachmentStorage attachmentStorage(@Value("${condpulse.auth.issuer}") String issuer,
     @Value("${condpulse.files.service-key:}") String serviceKey) {
  var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  return new SupabaseAttachmentStorage(http,storageBase(issuer),serviceKey);
 }
 @Bean AttachmentService attachmentService(TenantTransactionExecutor transactions,AttachmentStore store,AttachmentStorage storage,
     AuditRecorder audit,@Value("${condpulse.files.bucket:cp-upload-quarantine}") String bucket) {
  if(bucket==null || !bucket.matches("[a-z0-9][a-z0-9-]{1,62}")) throw new IllegalArgumentException("Bucket inválido.");
  return new AttachmentService(transactions,store,storage,audit,bucket);
 }
}
