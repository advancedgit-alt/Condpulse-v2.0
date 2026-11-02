package br.com.condpulse.stays.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/** Identidade conferida pela portaria; nunca associa pessoas por nome ou fragmento de documento. */
@Service @Profile("api")
public class VisitorIdentityService {
 public record Check(UUID checkId,UUID visitorId,UUID canonicalVisitorId,String state,long version,Instant checkedAt) {}
 public record Source(Check check) {}
 public record Candidate(UUID checkId,UUID visitorId,String name,String documentLast4,long version) {}
 public record Candidates(List<Candidate> candidates) { public Candidates { candidates=List.copyOf(candidates); } }
 public record Verify(UUID requestId,UUID arrivalId,UUID permitId,UUID canonicalVisitorId,String reason) {}
 public record VoidCheck(Long version,String reason) {}
 private final TenantTransactionExecutor transactions;
 private final JdbcTemplate jdbc;
 private final SensitiveViewRecorder views;
 private static final RowMapper<Check> CHECK=(r,n)->new Check(r.getObject("id",UUID.class),r.getObject("visitor_id",UUID.class),
  r.getObject("canonical_visitor_id",UUID.class),r.getString("state"),r.getLong("version"),r.getTimestamp("checked_at").toInstant());
 public VisitorIdentityService(TenantTransactionExecutor transactions,JdbcTemplate jdbc,SensitiveViewRecorder views) {
  this.transactions=transactions;this.jdbc=jdbc;this.views=views;
 }
 public Source source(UUID actor,UUID tenant,AssuranceLevel aal,UUID arrival,UUID permit) {
  basis(arrival,permit);
  return execute(()->transactions.execute(actor,tenant,aal,c->{
   c.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   UUID visitor=visitor(tenant,arrival,permit);
   var rows=jdbc.query("select * from cp.visitor_identity_checks where tenant_id=? and visitor_id=? and state='ACTIVE'",CHECK,tenant,visitor);
   return new Source(rows.isEmpty()?null:rows.getFirst());
  }));
 }
 public Candidates search(UUID actor,UUID tenant,AssuranceLevel aal,String prefix) {
  String query=DisplayText.normalize(prefix,80);
  if(query==null || query.length()<3) throw problem("IDENTITY_SEARCH_INVALID",400,"Informe ao menos três caracteres do nome.");
  String literal=query.toLowerCase(java.util.Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
  return execute(()->transactions.execute(actor,tenant,aal,c->{
   c.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var rows=jdbc.query("""
    select c.id,c.visitor_id,v.full_name,v.document_last4,c.version from cp.visitor_identity_checks c
    join cp.visitors v on v.tenant_id=c.tenant_id and v.id=c.visitor_id
    where c.tenant_id=? and c.state='ACTIVE' and c.visitor_id=c.canonical_visitor_id and lower(v.full_name) like ? escape '\\'
    order by lower(v.full_name),c.visitor_id limit 20
    """,(r,n)->new Candidate(r.getObject("id",UUID.class),r.getObject("visitor_id",UUID.class),r.getString("full_name"),r.getString("document_last4"),r.getLong("version")),tenant,literal);
   views.record(tenant,"CONCIERGE","VISITOR_IDENTITY_SEARCH",null,null,null,Map.of("count",rows.size()));
   return new Candidates(rows);
  }));
 }
 public Check verify(UUID actor,UUID tenant,AssuranceLevel aal,Verify request) {
  if(request==null || request.requestId()==null) throw problem("IDENTITY_REQUEST_INVALID",400,"Identificador de conferência obrigatório.");
  basis(request.arrivalId(),request.permitId()); String reason=reason(request.reason());
  return execute(()->transactions.execute(actor,tenant,aal,c->{
   c.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   UUID visitor=visitor(tenant,request.arrivalId(),request.permitId());
   UUID canonical=request.canonicalVisitorId()==null?visitor:request.canonicalVisitorId();
   // Serializa também a consulta de repetição. O gatilho protege chamadas SQL concorrentes sem este serviço.
   jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text,59001))",Object.class,tenant.toString());
   var old=jdbc.query("select * from cp.visitor_identity_checks where tenant_id=? and id=?",CHECK,tenant,request.requestId());
   if(!old.isEmpty()) {
    var value=old.getFirst();
    Boolean same=jdbc.queryForObject("select checked_by=? and reason=? from cp.visitor_identity_checks where tenant_id=? and id=?",Boolean.class,actor,reason,tenant,request.requestId());
    if(value.visitorId().equals(visitor) && value.canonicalVisitorId().equals(canonical) && Boolean.TRUE.equals(same)) return value;
    throw problem("IDENTITY_REQUEST_CONFLICT",409,"Esta conferência já foi usada para outros dados.");
   }
   jdbc.update("insert into cp.visitor_identity_checks(id,tenant_id,visitor_id,canonical_visitor_id,reason,checked_by) values(?,?,?,?,?,?)",
    request.requestId(),tenant,visitor,canonical,reason,actor);
   return jdbc.query("select * from cp.visitor_identity_checks where tenant_id=? and id=?",CHECK,tenant,request.requestId()).getFirst();
  }));
 }
 public Check voidCheck(UUID actor,UUID tenant,AssuranceLevel aal,UUID id,VoidCheck request) {
  if(request==null || request.version()==null || request.version()<0) throw problem("IDENTITY_REQUEST_INVALID",400,"Versão obrigatória.");
  String reason=reason(request.reason());
  return execute(()->transactions.execute(actor,tenant,aal,c->{
   c.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   int count=jdbc.update("update cp.visitor_identity_checks set state='VOIDED',void_reason=?,version=version+1 where tenant_id=? and id=? and version=? and state='ACTIVE'",reason,tenant,id,request.version());
   if(count!=1) throw problem("IDENTITY_VERSION_CONFLICT",409,"Conferência alterada. Atualize os dados.");
   return jdbc.query("select * from cp.visitor_identity_checks where tenant_id=? and id=?",CHECK,tenant,id).getFirst();
  }));
 }
 private UUID visitor(UUID tenant,UUID arrival,UUID permit) {
  var rows=arrival!=null?jdbc.query("select visitor_id from cp.arrival_requests where tenant_id=? and id=?",(r,n)->r.getObject(1,UUID.class),tenant,arrival)
   :jdbc.query("select visitor_id from cp.visit_permits where tenant_id=? and id=?",(r,n)->r.getObject(1,UUID.class),tenant,permit);
  if(rows.isEmpty()) throw problem("IDENTITY_SOURCE_NOT_FOUND",404,"Chegada ou convite não encontrado.");
  return rows.getFirst();
 }
 private static void basis(UUID arrival,UUID permit) { if((arrival==null)==(permit==null)) throw problem("IDENTITY_SOURCE_INVALID",400,"Informe uma chegada ou um convite."); }
 private static String reason(String raw) { String value=DisplayText.normalize(raw,500); if(value==null || value.length()<3) throw problem("IDENTITY_REASON_REQUIRED",400,"Informe motivo de 3 a 500 caracteres, sem documento completo."); return value; }
 private <T> T execute(Supplier<T> task) {
  try { return task.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw problem("IDENTITY_FORBIDDEN",403,"Conferência não permitida neste vínculo.");
   if("23505".equals(state)||"23514".equals(state)||"23503".equals(state)) throw problem("IDENTITY_STATE_CONFLICT",409,"Já existe conferência ou a associação não é válida. Atualize e confira a identidade.");
   throw problem("IDENTITY_UNAVAILABLE",503,"Não foi possível confirmar a conferência. Consulte antes de repetir.");
  } catch(TransactionException error) { throw problem("IDENTITY_UNAVAILABLE",503,"Não foi possível confirmar a conferência. Consulte antes de repetir."); }
 }
 private static BusinessProblem problem(String code,int status,String text) { return new BusinessProblem(code,status,text); }
}
