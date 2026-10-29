package br.com.condpulse.files.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.files.infrastructure.AttachmentStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Anexos privados de pedido (CP-017). Quem vê o pedido (unidade com vínculo vigente ou gestão com requests.manage e
 * AAL2) envia e baixa; o banco decide a visibilidade pelas mesmas políticas do pedido. O envio vai direto ao Storage por
 * URL assinada curta; a conclusão confere no próprio Storage o tamanho, a assinatura do tipo e o hash antes de liberar.
 * Arquivo disfarçado ou com tamanho diferente fica recusado. Download sempre revalida o acesso e emite link curto.
 * Chamadas ao Storage nunca ficam dentro da transação do banco.
 */
public class AttachmentService {
 static final long IMAGE_LIMIT=10L*1024*1024, PDF_LIMIT=20L*1024*1024;
 static final Duration DOWNLOAD_LIFETIME=Duration.ofSeconds(60);
 private static final Map<String,byte[]> SIGNATURES=Map.of(
  "image/png",new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A},
  "image/jpeg",new byte[]{(byte)0xFF,(byte)0xD8,(byte)0xFF},
  "application/pdf",new byte[]{0x25,0x50,0x44,0x46,0x2D});
 private final TenantTransactionExecutor transactions;
 private final AttachmentStore store;
 private final AttachmentStorage storage;
 private final AuditRecorder audit;
 private final String bucket;
 public AttachmentService(TenantTransactionExecutor transactions,AttachmentStore store,AttachmentStorage storage,AuditRecorder audit,
     String bucket) {
  this.transactions=transactions; this.store=store; this.storage=storage; this.audit=audit; this.bucket=bucket;
 }

 public AttachmentModels.Upload intent(UUID actor,UUID tenant,AssuranceLevel assurance,AttachmentModels.Intent request) {
  if(request==null || !"REQUEST_ATTACHMENT".equals(request.purpose()) || request.entityId()==null)
   throw invalid("ATTACHMENT_PURPOSE_INVALID","Anexo só pode ser enviado para um pedido.");
  String mime=request.mimeType();
  if(mime==null || !SIGNATURES.containsKey(mime))
   throw invalid("ATTACHMENT_TYPE_INVALID","Envie foto JPEG ou PNG ou documento PDF.");
  long limit="application/pdf".equals(mime)?PDF_LIMIT:IMAGE_LIMIT;
  if(request.sizeBytes()==null || request.sizeBytes()<1 || request.sizeBytes()>limit)
   throw invalid("ATTACHMENT_TOO_LARGE","Fotos até 10 MB e PDFs até 20 MB.");
  String name=fileName(request.fileName());
  UUID id=UUID.randomUUID();
  var row=execute(actor,tenant,assurance,context->{
   String state=store.requestState(tenant,request.entityId());
   if(state==null) throw requestNotFound();
   if("CLOSED".equals(state)) throw new BusinessProblem("SERVICE_REQUEST_CLOSED",409,"Pedido fechado não recebe anexo.");
   return store.insert(id,tenant,actor,request.entityId(),name,mime,request.sizeBytes(),bucket);
  });
  try {
   var url=storage.signUpload(bucket,row.objectKey());
   return new AttachmentModels.Upload(row.view(actor),url,"PUT",mime,row.uploadExpiresAt());
  } catch(AttachmentStorage.StorageUnavailable unavailable) { throw storageUnavailable(); }
 }

 public AttachmentModels.Attachment complete(UUID actor,UUID tenant,AssuranceLevel assurance,UUID id) {
  if(id==null) throw notFound();
  var row=execute(actor,tenant,assurance,context->{
   var current=store.find(tenant,id);
   if(current==null) throw notFound();
   if(!current.ownerId().equals(actor)) throw new BusinessProblem("ATTACHMENT_NOT_OWNER",403,"Somente quem enviou o arquivo conclui o envio.");
   return current;
  });
  if(!"PENDING".equals(row.state())) return row.view(actor);
  AttachmentStorage.StoredObject object;
  try { object=storage.inspect(row.bucket(),row.objectKey(),row.sizeBytes()).orElse(null); }
  catch(AttachmentStorage.StorageUnavailable unavailable) { throw storageUnavailable(); }
  String rejection=null;
  if(object==null) {
   boolean expired=execute(actor,tenant,assurance,context->store.now().isAfter(row.uploadExpiresAt()));
   if(!expired) throw new BusinessProblem("ATTACHMENT_NOT_UPLOADED",409,"O arquivo ainda não chegou. Envie de novo e conclua em seguida.");
   rejection="UPLOAD_EXPIRED";
  } else if(object.size()!=row.sizeBytes()) rejection="SIZE_MISMATCH";
  else if(!startsWith(object.head(),SIGNATURES.get(row.mimeType()))) rejection="TYPE_MISMATCH";
  String checked=rejection;
  return execute(actor,tenant,assurance,context->{
   String reason=checked;
   boolean changed=reason==null?store.complete(tenant,id,object.sha256()):store.reject(tenant,id,reason);
   var current=store.find(tenant,id);
   if(current==null) throw notFound();
   // Arquivo válido concluído depois da tolerância do prazo: não é liberado e encerra como vencido, para não ficar
   // pendente para sempre.
   if(!changed && reason==null && "PENDING".equals(current.state())) {
    reason="UPLOAD_EXPIRED"; changed=store.reject(tenant,id,reason); current=store.find(tenant,id);
   }
   if(changed) audit.record(tenant,actor,reason==null?"ATTACHMENT_UPLOADED":"ATTACHMENT_REJECTED","ATTACHMENT",id,reason);
   return current.view(actor);
  });
 }

 public AttachmentModels.Download download(UUID actor,UUID tenant,AssuranceLevel assurance,UUID id) {
  if(id==null) throw notFound();
  var row=execute(actor,tenant,assurance,context->{
   var current=store.find(tenant,id);
   if(current==null) throw notFound();
   if(!"AVAILABLE".equals(current.state()))
    throw new BusinessProblem("ATTACHMENT_NOT_AVAILABLE",409,"Este arquivo ainda não foi liberado.");
   audit.record(tenant,actor,"ATTACHMENT_DOWNLOADED","ATTACHMENT",id,null);
   return current;
  });
  try {
   var url=storage.signDownload(row.bucket(),row.objectKey(),DOWNLOAD_LIFETIME,row.fileName());
   return new AttachmentModels.Download(url,Instant.now().plus(DOWNLOAD_LIFETIME));
  } catch(AttachmentStorage.StorageUnavailable unavailable) { throw storageUnavailable(); }
 }

 public AttachmentModels.Page forRequest(UUID actor,UUID tenant,AssuranceLevel assurance,UUID request) {
  if(request==null) throw requestNotFound();
  return execute(actor,tenant,assurance,context->{
   if(store.requestState(tenant,request)==null) throw requestNotFound();
   return new AttachmentModels.Page(store.forRequest(tenant,request,actor).stream().map(row->row.view(actor)).toList());
  });
 }

 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,assurance,action); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw requestNotFound();
   if("23514".equals(state)) throw new BusinessProblem("ATTACHMENT_LIMIT_REACHED",409,
    "Este pedido já tem o máximo de 10 anexos, ou o envio expirou. Atualize e confira.");
   throw new BusinessProblem("ATTACHMENT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 static boolean startsWith(byte[] head,byte[] signature) {
  return head.length>=signature.length && Arrays.equals(Arrays.copyOf(head,signature.length),signature);
 }
 static String fileName(String value) {
  if(value==null) throw invalid("ATTACHMENT_NAME_INVALID","Informe o nome do arquivo.");
  String name=value.replace('\\','/');
  name=name.substring(name.lastIndexOf('/')+1).strip().replaceAll("\\s+"," ");
  if(name.isEmpty() || name.length()>120 || name.chars().anyMatch(c->Character.isISOControl(c) || c=='"'))
   throw invalid("ATTACHMENT_NAME_INVALID","O nome do arquivo deve ter até 120 caracteres.");
  return name;
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("ATTACHMENT_NOT_FOUND",404,"Arquivo não encontrado."); }
 private static BusinessProblem requestNotFound() {
  return new BusinessProblem("SERVICE_REQUEST_NOT_FOUND",404,"Pedido não encontrado.");
 }
 private static BusinessProblem storageUnavailable() {
  return new BusinessProblem("ATTACHMENT_STORAGE_UNAVAILABLE",503,"O armazenamento de arquivos não respondeu. Tente novamente.");
 }
}
