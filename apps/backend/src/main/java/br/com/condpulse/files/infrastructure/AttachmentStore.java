package br.com.condpulse.files.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.files.application.AttachmentModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Somente dentro da transação do TenantTransactionExecutor: as políticas RLS de cp.attachments decidem o que é visível. */
@Repository @Profile("api")
public class AttachmentStore {
 private static final String COLUMNS="""
  id,entity_id,owner_id,file_name,mime_type,size_bytes,bucket,object_key,state,rejection,created_at,upload_expires_at,completed_at
  """;
 private final JdbcTemplate jdbc;
 public AttachmentStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public record Row(UUID id,UUID entityId,UUID ownerId,String fileName,String mimeType,long sizeBytes,String bucket,String objectKey,
   String state,String rejection,Instant createdAt,Instant uploadExpiresAt,Instant completedAt) {
  public AttachmentModels.Attachment view(UUID actor) {
   return new AttachmentModels.Attachment(id,entityId,fileName,mimeType,sizeBytes,state,rejection,ownerId.equals(actor),createdAt,completedAt);
  }
 }
 /** Pedido visível para quem pede (as políticas do pedido valem aqui também). */
 public String requestState(UUID tenant,UUID request) {
  var rows=jdbc.query("select state from cp.service_requests where tenant_id=? and id=?",(r,n)->r.getString(1),tenant,request);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public Row insert(UUID id,UUID tenant,UUID owner,UUID request,String fileName,String mimeType,long size,String bucket) {
  jdbc.update("""
   insert into cp.attachments(id,tenant_id,owner_id,purpose,entity_type,entity_id,file_name,mime_type,size_bytes,bucket,object_key)
   values(?,?,?,'REQUEST_ATTACHMENT','SERVICE_REQUEST',?,?,?,?,?,?)
   """,id,tenant,owner,request,fileName,mimeType,size,bucket,tenant+"/"+id);
  return find(tenant,id);
 }
 public Row find(UUID tenant,UUID id) {
  var rows=jdbc.query("select "+COLUMNS+" from cp.attachments where tenant_id=? and id=?",(r,n)->row(r),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Disponíveis para quem vê o pedido; os pendentes e recusados só para quem enviou. */
 public List<Row> forRequest(UUID tenant,UUID request,UUID actor) {
  return jdbc.query("select "+COLUMNS+" from cp.attachments where tenant_id=? and entity_type='SERVICE_REQUEST' and entity_id=?"
   +" and (state='AVAILABLE' or owner_id=?) order by created_at,id limit 50",(r,n)->row(r),tenant,request,actor);
 }
 public boolean complete(UUID tenant,UUID id,String sha256) {
  return jdbc.update("update cp.attachments set state='AVAILABLE',sha256=? where tenant_id=? and id=? and state='PENDING'",
   sha256,tenant,id)==1;
 }
 public boolean reject(UUID tenant,UUID id,String reason) {
  return jdbc.update("update cp.attachments set state='REJECTED',rejection=? where tenant_id=? and id=? and state='PENDING'",
   reason,tenant,id)==1;
 }
 public Instant now() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }
 private static Row row(ResultSet r) throws SQLException {
  return new Row(r.getObject("id",UUID.class),r.getObject("entity_id",UUID.class),r.getObject("owner_id",UUID.class),
   r.getString("file_name"),r.getString("mime_type"),r.getLong("size_bytes"),r.getString("bucket"),r.getString("object_key"),
   r.getString("state"),r.getString("rejection"),instant(r.getTimestamp("created_at")),instant(r.getTimestamp("upload_expires_at")),
   instant(r.getTimestamp("completed_at")));
 }
 private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
