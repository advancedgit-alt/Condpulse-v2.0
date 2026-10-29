package br.com.condpulse.database;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import br.com.condpulse.files.application.AttachmentModels;
import br.com.condpulse.files.application.AttachmentService;
import br.com.condpulse.files.application.AttachmentStorage;
import br.com.condpulse.files.infrastructure.AttachmentStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.requests.application.RequestModels;
import br.com.condpulse.requests.application.RequestService;
import br.com.condpulse.requests.infrastructure.RequestStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V019 com PostgreSQL real e Storage em memória: anexo só em pedido visível e aberto, liberado apenas depois de
 * conferidos tamanho e assinatura do tipo no Storage, arquivo disfarçado recusado, download revalidado a cada pedido
 * (outra unidade e outro condomínio recebem "não encontrado", mesmo com o ID) e nunca antes da liberação.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AttachmentJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final byte[] PNG={(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,1,2,3,4};
 private static final byte[] PDF="%PDF-1.7 conteudo ficticio".getBytes();
 private static JdbcDisposableDatabase db;
 private static RequestService requests;
 private static AttachmentService attachments;
 private static final FakeStorage storage=new FakeStorage();
 private static UUID manager;

 /** Objetos em memória; a chave é bucket/objeto, como no Storage real. */
 static final class FakeStorage implements AttachmentStorage {
  final Map<String,byte[]> objects=new ConcurrentHashMap<>();
  volatile boolean down;
  @Override public URI signUpload(String bucket,String key) {
   if(down) throw new StorageUnavailable();
   return URI.create("https://fake.storage.invalid/upload/"+bucket+"/"+key+"?token=synthetic-upload-token");
  }
  @Override public Optional<StoredObject> inspect(String bucket,String key,long limit) {
   if(down) throw new StorageUnavailable();
   byte[] content=objects.get(bucket+"/"+key);
   if(content==null) return Optional.empty();
   try {
    String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    byte[] head=java.util.Arrays.copyOf(content,Math.min(16,content.length));
    return Optional.of(new StoredObject(content.length,head,sha));
   } catch(Exception impossible) { throw new IllegalStateException(impossible); }
  }
  @Override public URI signDownload(String bucket,String key,Duration lifetime,String downloadName) {
   if(down) throw new StorageUnavailable();
   return URI.create("https://fake.storage.invalid/download/"+bucket+"/"+key+"?token=synthetic-download-token");
  }
  void put(AttachmentModels.Upload upload,byte[] content) {
   String path=upload.uploadUrl().getPath().substring("/upload/".length());
   objects.put(path,content);
  }
 }

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(2);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var audit=new AuditRecorder(db.jdbc);
  requests=new RequestService(transactions,new RequestStore(db.jdbc),audit);
  attachments=new AttachmentService(transactions,new AttachmentStore(db.jdbc),storage,audit,"cp-upload-quarantine");
  manager=UUID.randomUUID(); db.extraAccounts.add(manager);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",manager);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO ANEXOS JDBC','MANAGER','ACTIVE')",
   db.tenantA,manager);
 }
 @BeforeEach void reset() throws Exception {
  storage.down=false; storage.objects.clear();
  db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static UUID openRequest() {
  return requests.open(db.accountA,db.tenantA,db.unitA,"attachment-jdbc-"+UUID.randomUUID(),
   new RequestModels.Open("MAINTENANCE","Pedido com anexo","Foto do vazamento.")).request().requestId();
 }
 private static AttachmentModels.Upload intent(UUID account,UUID request,String mime,long size) {
  return attachments.intent(account,db.tenantA,AAL1,new AttachmentModels.Intent("REQUEST_ATTACHMENT",request,"C:\\fotos\\vazamento.png",mime,size));
 }

 @Test void validFileIsReleasedOnlyAfterTheStorageCheckAndDownloadIsShortAndAudited() throws Exception {
  UUID request=openRequest();
  var upload=intent(db.accountA,request,"image/png",PNG.length);
  assertEquals("PENDING",upload.attachment().state()); assertEquals("vazamento.png",upload.attachment().fileName());
  assertEquals("PUT",upload.method()); assertEquals("image/png",upload.contentType());
  assertTrue(upload.uploadUrl().getPath().endsWith(db.tenantA+"/"+upload.attachment().attachmentId()),"chave do objeto vem do condomínio e do anexo");
  UUID id=upload.attachment().attachmentId();
  assertEquals("ATTACHMENT_NOT_AVAILABLE",assertThrows(BusinessProblem.class,()->attachments.download(db.accountA,db.tenantA,AAL1,id)).code());
  assertEquals("ATTACHMENT_NOT_UPLOADED",assertThrows(BusinessProblem.class,()->attachments.complete(db.accountA,db.tenantA,AAL1,id)).code(),
   "sem objeto no Storage e dentro do prazo: pode enviar de novo");
  storage.put(upload,PNG);
  var done=attachments.complete(db.accountA,db.tenantA,AAL1,id);
  assertEquals("AVAILABLE",done.state()); assertTrue(done.mine());
  assertEquals("AVAILABLE",attachments.complete(db.accountA,db.tenantA,AAL1,id).state(),"repetir a conclusão não muda nada");
  assertEquals(64,db.adminString("select sha256 from cp.attachments where id=?",id).length());
  var link=attachments.download(db.accountA,db.tenantA,AAL1,id);
  assertTrue(link.url().toString().contains("synthetic-download-token"));
  assertTrue(link.expiresAt().isBefore(java.time.Instant.now().plusSeconds(120)));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where tenant_id=? and action='ATTACHMENT_UPLOADED' and resource_id=?",db.tenantA,id));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where tenant_id=? and action='ATTACHMENT_DOWNLOADED' and resource_id=?",db.tenantA,id));
  // Gestão (AAL2) vê e baixa; a lista do pedido mostra o anexo liberado.
  assertEquals(List.of(id),attachments.forRequest(manager,db.tenantA,AAL2,request).attachments().stream().map(AttachmentModels.Attachment::attachmentId).toList());
  assertNotNull(attachments.download(manager,db.tenantA,AAL2,id).url());
  db.assertEmptyContext();
 }

 @Test void disguisedOrResizedFilesAreRejectedAndNeverDownloadable() throws Exception {
  UUID request=openRequest();
  var disguised=intent(db.accountA,request,"image/png",PDF.length);
  storage.put(disguised,PDF);
  var rejected=attachments.complete(db.accountA,db.tenantA,AAL1,disguised.attachment().attachmentId());
  assertEquals("REJECTED",rejected.state()); assertEquals("TYPE_MISMATCH",rejected.rejection());
  var resized=intent(db.accountA,request,"image/png",PNG.length+5);
  storage.put(resized,PNG);
  assertEquals("SIZE_MISMATCH",attachments.complete(db.accountA,db.tenantA,AAL1,resized.attachment().attachmentId()).rejection());
  assertEquals("ATTACHMENT_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->attachments.download(db.accountA,db.tenantA,AAL1,disguised.attachment().attachmentId())).code());
  // Recusados aparecem só para quem enviou; a gestão vê só o que foi liberado.
  assertEquals(2,attachments.forRequest(db.accountA,db.tenantA,AAL1,request).attachments().size());
  assertEquals(0,attachments.forRequest(manager,db.tenantA,AAL2,request).attachments().size());
  db.assertEmptyContext();
 }

 @Test void otherUnitsTenantsAndWeakManagersGetNotFound() throws Exception {
  UUID request=openRequest();
  var upload=intent(db.accountA,request,"application/pdf",PDF.length);
  storage.put(upload,PDF);
  UUID id=upload.attachment().attachmentId();
  attachments.complete(db.accountA,db.tenantA,AAL1,id);
  // Conta C mora em outra unidade de A; conta B é de outro condomínio.
  assertEquals("ATTACHMENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->attachments.download(db.accountC,db.tenantA,AAL1,id)).code());
  assertEquals("SERVICE_REQUEST_NOT_FOUND",assertThrows(BusinessProblem.class,()->attachments.forRequest(db.accountC,db.tenantA,AAL1,request)).code());
  assertEquals("SERVICE_REQUEST_NOT_FOUND",assertThrows(BusinessProblem.class,()->intent(db.accountC,request,"image/png",PNG.length)).code());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->attachments.download(db.accountB,db.tenantA,AAL1,id)).code());
  assertEquals("ATTACHMENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->attachments.download(db.accountB,db.tenantB,AAL1,id)).code());
  assertEquals("ATTACHMENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->attachments.download(manager,db.tenantA,AAL1,id)).code(),
   "gestão sem segundo fator não publica capacidade e não vê o pedido");
  // Vínculo revogado deixa de ver o próprio anexo.
  db.adminUpdate("update cp.memberships set status='REVOKED' where tenant_id=? and account_id=? and unit_id=?",db.tenantA,db.accountA,db.unitA);
  assertThrows(BusinessProblem.class,()->attachments.download(db.accountA,db.tenantA,AAL1,id));
  db.assertEmptyContext();
 }

 @Test void closedRequestsLimitsTypesAndStorageFailures() throws Exception {
  UUID request=openRequest();
  assertEquals("ATTACHMENT_TYPE_INVALID",assertThrows(BusinessProblem.class,()->intent(db.accountA,request,"image/gif",10)).code());
  assertEquals("ATTACHMENT_TOO_LARGE",assertThrows(BusinessProblem.class,()->intent(db.accountA,request,"image/png",10L*1024*1024+1)).code());
  assertDoesNotThrow(()->intent(db.accountA,request,"application/pdf",20L*1024*1024));
  for(int i=1;i<10;i++) intent(db.accountA,request,"image/png",PNG.length);
  assertEquals("ATTACHMENT_LIMIT_REACHED",assertThrows(BusinessProblem.class,()->intent(db.accountA,request,"image/png",PNG.length)).code());
  UUID other=openRequest();
  storage.down=true;
  long before=Long.parseLong(db.adminString("select count(*) from cp.attachments where tenant_id=? and entity_id=?",db.tenantA,other));
  assertEquals("ATTACHMENT_STORAGE_UNAVAILABLE",assertThrows(BusinessProblem.class,()->intent(db.accountA,other,"image/png",PNG.length)).code());
  assertEquals(before+1,Long.parseLong(db.adminString("select count(*) from cp.attachments where tenant_id=? and entity_id=?",db.tenantA,other)),
   "a linha pendente fica e expira sozinha; nada é liberado sem a conferência");
  storage.down=false;
  db.adminUpdate("update cp.service_requests set state='CLOSED',closed_at=clock_timestamp(),version=version+1 where tenant_id=? and id=?",db.tenantA,other);
  assertEquals("SERVICE_REQUEST_CLOSED",assertThrows(BusinessProblem.class,()->intent(db.accountA,other,"image/png",PNG.length)).code());
  db.assertEmptyContext();
 }

 @Test void lateCompletionOfAValidFileEndsExpiredAndAvailableFilesAreListedFirst() throws Exception {
  UUID request=openRequest();
  var valid=intent(db.accountA,request,"image/png",PNG.length);
  storage.put(valid,PNG);
  attachments.complete(db.accountA,db.tenantA,AAL1,valid.attachment().attachmentId());
  // Arquivo chegou, mas a conclusão só foi repetida depois da tolerância: encerra como vencido, não fica pendente.
  var late=intent(db.accountA,request,"image/png",PNG.length);
  storage.put(late,PNG);
  db.adminUpdateWithoutTriggers("update cp.attachments set upload_expires_at=clock_timestamp()-interval '6 minutes' where id=?",
   late.attachment().attachmentId());
  var expired=attachments.complete(db.accountA,db.tenantA,AAL1,late.attachment().attachmentId());
  assertEquals("REJECTED",expired.state()); assertEquals("UPLOAD_EXPIRED",expired.rejection());
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where tenant_id=? and action='ATTACHMENT_REJECTED' and resource_id=?",
   db.tenantA,late.attachment().attachmentId()));
  // Muitas tentativas recusadas depois do anexo liberado não o escondem da lista.
  for(int i=0;i<55;i++) {
   var attempt=intent(db.accountA,request,"image/png",PDF.length);
   storage.put(attempt,PDF);
   assertEquals("TYPE_MISMATCH",attachments.complete(db.accountA,db.tenantA,AAL1,attempt.attachment().attachmentId()).rejection());
  }
  var page=attachments.forRequest(db.accountA,db.tenantA,AAL1,request).attachments();
  assertEquals(50,page.size());
  assertEquals(valid.attachment().attachmentId(),page.getFirst().attachmentId());
  assertEquals("AVAILABLE",page.getFirst().state());
  db.assertEmptyContext();
 }
}
