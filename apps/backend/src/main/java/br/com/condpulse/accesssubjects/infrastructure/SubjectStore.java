package br.com.condpulse.accesssubjects.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.accesssubjects.application.ContextPrecedence;
import br.com.condpulse.accesssubjects.application.SubjectModels;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL de sujeitos e contextos (V038), sempre dentro do TenantTransactionExecutor; o banco deriva tipo, unidade e vigência. */
public class SubjectStore {
 public static final int PAGE=200;
 private final JdbcTemplate jdbc;
 public SubjectStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public record SubjectRow(UUID id,String originKind,UUID accountId,UUID visitorId,String status,long epoch,String externalRef,long version) {}

 public List<SubjectRow> subjects(UUID tenant,int limit) {
  return jdbc.query("""
   select id,origin_kind,account_id,visitor_id,status,restriction_epoch,external_ref,version from cp.access_subjects
   where tenant_id=? order by created_at,id limit ?
   """,(r,n)->new SubjectRow(r.getObject("id",UUID.class),r.getString("origin_kind"),r.getObject("account_id",UUID.class),
    r.getObject("visitor_id",UUID.class),r.getString("status"),r.getLong("restriction_epoch"),r.getString("external_ref"),r.getLong("version")),
   tenant,limit);
 }
 public SubjectRow subject(UUID tenant,UUID id) {
  var rows=jdbc.query("""
   select id,origin_kind,account_id,visitor_id,status,restriction_epoch,external_ref,version from cp.access_subjects
   where tenant_id=? and id=?
   """,(r,n)->new SubjectRow(r.getObject("id",UUID.class),r.getString("origin_kind"),r.getObject("account_id",UUID.class),
    r.getObject("visitor_id",UUID.class),r.getString("status"),r.getLong("restriction_epoch"),r.getString("external_ref"),r.getLong("version")),
   tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Contextos dos sujeitos informados, com a vigência conferida pelo banco agora. */
 public Map<UUID,List<SubjectModels.SubjectContext>> contexts(UUID tenant,List<UUID> subjects) {
  var result=new LinkedHashMap<UUID,List<SubjectModels.SubjectContext>>();
  if(subjects.isEmpty()) return result;
  // Só durante esta consulta a conferência de convites fica liberada (devolve booleanos, nunca dados do convite).
  jdbc.queryForObject("select set_config('app.subject_context_scan','on',true)",String.class);
  try { scanContexts(tenant,subjects,result); }
  finally { jdbc.queryForObject("select set_config('app.subject_context_scan','',true)",String.class); }
  return result;
 }
 private void scanContexts(UUID tenant,List<UUID> subjects,Map<UUID,List<SubjectModels.SubjectContext>> result) {
  jdbc.query("""
   select c.subject_id,c.id,c.context_kind,c.source_kind,c.membership_id,c.permit_id,c.unit_id,c.guardian_subject_id,c.valid_from,
    c.valid_until,c.status,cp.subject_context_current(c.tenant_id,c.id) as current,c.version
   from cp.subject_contexts c where c.tenant_id=? and c.subject_id=any(?) order by c.created_at,c.id
   """,(ResultSet r)->{
    result.computeIfAbsent(r.getObject("subject_id",UUID.class),k->new ArrayList<>()).add(context(r));
   },tenant,subjects.toArray(UUID[]::new));
 }
 private static SubjectModels.SubjectContext context(ResultSet r) throws SQLException {
  return new SubjectModels.SubjectContext(r.getObject("id",UUID.class),r.getString("context_kind"),r.getString("source_kind"),
   r.getObject("membership_id",UUID.class),r.getObject("permit_id",UUID.class),r.getObject("unit_id",UUID.class),
   r.getObject("guardian_subject_id",UUID.class),r.getTimestamp("valid_from").toInstant(),instant(r.getTimestamp("valid_until")),
   r.getString("status"),r.getBoolean("current"),r.getLong("version"));
 }
 public static ContextPrecedence.Candidate candidate(SubjectModels.SubjectContext c) {
  return new ContextPrecedence.Candidate(c.contextId(),c.contextKind(),c.status(),c.validFrom(),c.validUntil(),c.current());
 }
 private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }

 /** Vínculos ativos de papel com acesso físico, sem sujeito/contexto ativo equivalente. */
 public record PendingMembership(UUID membershipId,UUID accountId) {}
 public List<PendingMembership> physicalMemberships(UUID tenant) {
  return jdbc.query("""
   select m.id,m.account_id from cp.memberships m
   where m.tenant_id=? and m.status='ACTIVE' and m.role in ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER','CONCIERGE')
    and (m.valid_until is null or m.valid_until>now())
   order by m.created_at,m.id
   """,(r,n)->new PendingMembership(r.getObject("id",UUID.class),r.getObject("account_id",UUID.class)),tenant);
 }
 public record PendingPermit(UUID permitId,UUID visitorId) {}
 public List<PendingPermit> currentPermits(UUID tenant) {
  return jdbc.query("""
   select p.id,p.visitor_id from cp.visit_permits p
   where p.tenant_id=? and p.state='ACTIVE' and p.valid_until>now() order by p.valid_from,p.id
   """,(r,n)->new PendingPermit(r.getObject("id",UUID.class),r.getObject("visitor_id",UUID.class)),tenant);
 }
 /** Sujeito da conta/visitante, criado se faltar (idempotente pelo índice único). Devolve [id, criado?]. */
 public Object[] ensureSubject(UUID tenant,String origin,UUID person) {
  String column="ACCOUNT".equals(origin)?"account_id":"visitor_id";
  var created=jdbc.query("insert into cp.access_subjects(tenant_id,origin_kind,"+column+") values(?,?,?) "
   +"on conflict (tenant_id,"+column+") where "+column+" is not null do nothing returning id",
   (r,n)->r.getObject("id",UUID.class),tenant,origin,person);
  if(!created.isEmpty()) return new Object[]{created.getFirst(),true};
  return new Object[]{jdbc.queryForObject("select id from cp.access_subjects where tenant_id=? and "+column+"=?",UUID.class,tenant,person),false};
 }
 /**
  * Ciclo de vida por sujeito e origem, olhando a linha mais recente:
  * <ul>
  * <li>nenhuma, SUPERSEDED ou EXPIRED: cria (1);</li>
  * <li>REVOKED: revogação pendente, não recria (-2) até a reautorização explícita;</li>
  * <li>ACTIVE com os mesmos dados da origem (versão, tipo, unidade e vigência): mantém (0), mesmo que agora esteja
  * inelegível por bloqueio do sujeito, início futuro ou origem vencida — vigência não é mudança;</li>
  * <li>ACTIVE com dados diferentes da origem ainda ativa: SUPERSEDED e cria o sucessor (2), preservando a cadeia
  * reautorizada.</li>
  * </ul>
  */
 public int ensureContext(UUID tenant,UUID subject,String sourceKind,UUID source,UUID actor) {
  String column="MEMBERSHIP".equals(sourceKind)?"membership_id":"permit_id";
  var latest=jdbc.query("select c.id,c.status,"+sameSourceSql(sourceKind)+" as same from cp.subject_contexts c "
   +"where c.tenant_id=? and c.subject_id=? and c.source_kind=? and c."+column+"=? order by c.created_at desc,c.id desc limit 1 for update",
   (r,n)->new Object[]{r.getObject("id",UUID.class),r.getString("status"),r.getBoolean("same")},tenant,subject,sourceKind,source);
  int outcome=1;
  if(!latest.isEmpty()) {
   String status=(String)latest.getFirst()[1];
   if("REVOKED".equals(status)) return -2;
   if("ACTIVE".equals(status)) {
    if((Boolean)latest.getFirst()[2]) return 0;
    jdbc.update("update cp.subject_contexts set status='SUPERSEDED',status_reason='Origem alterada',version=version+1 where tenant_id=? and id=?",
     tenant,latest.getFirst()[0]);
    outcome=2;
   }
  }
  jdbc.update("insert into cp.subject_contexts(tenant_id,subject_id,context_kind,source_kind,"+column+",valid_until,created_by) "
   +"values(?,?,'RESIDENT',?,?,now()+interval '1 day',?)",tenant,subject,sourceKind,source,actor);
  return outcome;
 }
 /** Os dados materializados ainda são os da origem (sem olhar vigência nem estado do sujeito). */
 private static String sameSourceSql(String sourceKind) {
  if("MEMBERSHIP".equals(sourceKind))
   return "coalesce((select m.version=c.source_version and m.unit_id is not distinct from c.unit_id and m.valid_from=c.valid_from "
    +"and m.valid_until is not distinct from c.valid_until and cp.membership_context_kind(m.tenant_id,m.id)=c.context_kind "
    +"from cp.memberships m where m.tenant_id=c.tenant_id and m.id=c.membership_id),false)";
  return "coalesce((select p.version=c.source_version and p.unit_id is not distinct from c.unit_id and p.valid_from=c.valid_from "
   +"and p.valid_until is not distinct from c.valid_until and (case when p.rule_id is not null then 'SERVICE_PROVIDER' else 'VISITOR' end)=c.context_kind "
   +"from cp.visit_permits p where p.tenant_id=c.tenant_id and p.id=c.permit_id),false)";
 }
 /** [sujeito, vínculo] quando o contexto é a revogação pendente (linha mais recente) de um vínculo; null se não for o caso. */
 public UUID[] revokedMembershipContext(UUID tenant,UUID context) {
  var rows=jdbc.query("""
   select r.subject_id,r.membership_id from cp.subject_contexts r where r.tenant_id=? and r.id=? and r.status='REVOKED' and r.source_kind='MEMBERSHIP'
    and not exists(select 1 from cp.subject_contexts later where later.tenant_id=r.tenant_id and later.subject_id=r.subject_id
     and later.source_kind='MEMBERSHIP' and later.membership_id=r.membership_id and (later.created_at,later.id)>(r.created_at,r.id))
   """,(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},tenant,context);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean reauthorizedBy(UUID tenant,UUID context) {
  Integer count=jdbc.queryForObject("select count(*) from cp.subject_contexts where tenant_id=? and reauthorizes_context_id=?",
   Integer.class,tenant,context);
  return count!=null && count>0;
 }
 public UUID insertReauthorized(UUID tenant,UUID subject,UUID membership,UUID revoked,UUID actor) {
  return jdbc.queryForObject("""
   insert into cp.subject_contexts(tenant_id,subject_id,context_kind,source_kind,membership_id,reauthorizes_context_id,created_by)
   values(?,?,'RESIDENT','MEMBERSHIP',?,?,?) returning id
   """,UUID.class,tenant,subject,membership,revoked,actor);
 }
 public boolean changeSubjectState(UUID tenant,UUID subject,long expected,String status,String reason,UUID actor) {
  return jdbc.update("""
   update cp.access_subjects set status=?,blocked_reason=?,blocked_by=?,restriction_epoch=restriction_epoch+1,version=version+1
   where tenant_id=? and id=? and version=? and status in ('ACTIVE','BLOCKED') and status<>?
   """,status,reason,"BLOCKED".equals(status)?actor:null,tenant,subject,expected,status)==1;
 }
 public boolean closeContext(UUID tenant,UUID context,long expected,String reason) {
  return jdbc.update("""
   update cp.subject_contexts set status='REVOKED',status_reason=?,version=version+1 where tenant_id=? and id=? and version=? and status='ACTIVE'
   """,reason,tenant,context,expected)==1;
 }
 public UUID contextSubject(UUID tenant,UUID context) {
  var rows=jdbc.queryForList("select subject_id from cp.subject_contexts where tenant_id=? and id=?",UUID.class,tenant,context);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Repetição só com o pedido inteiro igual: mesmo sujeito, tipo, referência e fim. */
 public boolean sameFunctional(UUID tenant,UUID context,UUID subject,String kind,String reference,Instant validUntil) {
  Integer same=jdbc.queryForObject("""
   select count(*) from cp.subject_contexts where tenant_id=? and id=? and subject_id=? and source_kind='FUNCTIONAL_AUTHORIZATION'
    and context_kind=? and functional_authorization_ref=? and valid_until=?
   """,Integer.class,tenant,context,subject,kind,reference,Timestamp.from(validUntil));
  return same!=null && same==1;
 }
 public void insertFunctional(UUID tenant,UUID id,UUID subject,String kind,String reference,Instant validUntil,UUID actor) {
  jdbc.update("""
   insert into cp.subject_contexts(id,tenant_id,subject_id,context_kind,source_kind,functional_authorization_ref,approved_by_account_id,
    valid_until,created_by) values(?,?,?,?,'FUNCTIONAL_AUTHORIZATION',?,?,?,?)
   """,id,tenant,subject,kind,reference,actor,Timestamp.from(validUntil),actor);
 }
}
