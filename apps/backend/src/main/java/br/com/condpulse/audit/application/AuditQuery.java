package br.com.condpulse.audit.application;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Auditoria do próprio condomínio, por capacidade e com paginação por cursor opaco. */
@Service @Profile("api")
public class AuditQuery {
 private final TenantTransactionExecutor transactions;
 private final JdbcTemplate jdbc;
 public AuditQuery(TenantTransactionExecutor transactions,JdbcTemplate jdbc) { this.transactions=transactions; this.jdbc=jdbc; }

 public Page page(UUID actor,UUID tenant,AssuranceLevel assurance,String cursor,Integer requestedLimit) {
  int limit=requestedLimit==null?30:requestedLimit;
  if(limit<1 || limit>100) throw new BusinessProblem("AUDIT_LIMIT_INVALID",400,"Use um limite entre 1 e 100.");
  Cursor after=Cursor.parse(cursor);
  try {
   return transactions.execute(actor,tenant,assurance,context->{
    context.requireCapability(StaffCapability.READ_AUDIT);
    var rows=jdbc.query("""
     select id,actor_id,action,resource_type,resource_id,reason,created_at from cp.audit_log
     where tenant_id=? and (?::timestamptz is null or (created_at,id)<(?::timestamptz,?::uuid))
     order by created_at desc,id desc limit ?
     """,(r,n)->new Event(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("action"),
      r.getString("resource_type"),r.getObject("resource_id",UUID.class),r.getString("reason"),
      r.getTimestamp("created_at").toInstant()),
     tenant,after==null?null:after.timestamp(),after==null?null:after.timestamp(),after==null?null:after.id(),limit+1);
    boolean more=rows.size()>limit;
    var events=rows.stream().limit(limit).toList();
    String next=more?new Cursor(events.getLast().createdAt(),events.getLast().eventId()).encode():null;
    return new Page(events,next);
   });
  } catch(DataAccessException error) {
   throw new BusinessProblem("AUDIT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível consultar a auditoria. Tente novamente.");
  }
 }
 public record Event(UUID eventId,UUID actorId,String action,String resourceType,UUID resourceId,String reason,Instant createdAt) {}
 public record Page(List<Event> events,String nextCursor) {
  public Page { events=List.copyOf(events); }
 }
 record Cursor(Instant createdAt,UUID id) {
  Timestamp timestamp() { return Timestamp.from(createdAt); }
  String encode() {
   long micros=Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(),1_000_000L),createdAt.getNano()/1000);
   return Base64.getUrlEncoder().withoutPadding().encodeToString((micros+":"+id).getBytes(StandardCharsets.US_ASCII));
  }
  static Cursor parse(String value) {
   if(value==null || value.isBlank()) return null;
   try {
    if(value.length()>120) throw new IllegalArgumentException();
    String decoded=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.US_ASCII);
    int split=decoded.indexOf(':');
    long micros=Long.parseLong(decoded.substring(0,split));
    UUID id=UUID.fromString(decoded.substring(split+1));
    return new Cursor(Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000L),Math.floorMod(micros,1_000_000L)*1000L),id);
   } catch(RuntimeException invalid) {
    throw new BusinessProblem("AUDIT_CURSOR_INVALID",400,"Cursor de paginação inválido.");
   }
  }
 }
}
