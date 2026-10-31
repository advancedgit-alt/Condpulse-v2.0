package br.com.condpulse.accessgrants.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessgrants.application.GrantModels;
import br.com.condpulse.accessgrants.application.GrantPlanner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL dos direitos e marcas (V044). O banco confere origem, contexto, época, vigência, revisão e revogações; a conferência
 * de vigência de convites (app.subject_context_scan) fica ligada só durante a consulta.
 */
public class GrantStore {
 private final JdbcTemplate jdbc;
 public GrantStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private <T> T scan(Supplier<T> action) {
  jdbc.queryForObject("select set_config('app.subject_context_scan','on',true)",String.class);
  try { return action.get(); }
  finally { jdbc.queryForObject("select set_config('app.subject_context_scan','',true)",String.class); }
 }
 /** Mesmo lock por condomínio das V041-V043 (instalação, políticas, direitos e marcas). */
 public void lock(UUID tenant) {
  jdbc.query("select pg_advisory_xact_lock(hashtextextended('cp.installation:'||?::text,0))",(r,n)->null,tenant);
 }

 // ---- leitura de fatos para a derivação
 public int countCandidates(UUID tenant,List<String> kinds) {
  Integer n=jdbc.queryForObject("select count(*) from cp.subject_contexts where tenant_id=? and status='ACTIVE' and context_kind=any(?::text[])",Integer.class,
   tenant,kinds.toArray(String[]::new));
  return n==null?0:n;
 }
 public List<GrantPlanner.Candidate> candidates(UUID tenant,List<String> kinds,int limit) {
  return scan(()->jdbc.query("""
   select c.id,c.subject_id,c.context_kind,c.source_kind,c.membership_id,c.permit_id,c.unit_id,c.source_version,c.valid_until,s.restriction_epoch,
    s.status='ACTIVE' subject_active,cp.subject_context_current(c.tenant_id,c.id) is_current,p.valid_until permit_until,p.reservation_id,
    exists(select 1 from cp.reservations r where r.tenant_id=p.tenant_id and r.id=p.reservation_id and r.status='CONFIRMED') reservation_confirmed
   from cp.subject_contexts c join cp.access_subjects s on s.tenant_id=c.tenant_id and s.id=c.subject_id
   left join cp.visit_permits p on p.tenant_id=c.tenant_id and p.id=c.permit_id
   where c.tenant_id=? and c.status='ACTIVE' and c.context_kind=any(?::text[]) order by c.id limit ?
   """,(r,n)->new GrantPlanner.Candidate(r.getObject("id",UUID.class),r.getObject("subject_id",UUID.class),r.getString("context_kind"),
    r.getString("source_kind"),r.getObject("membership_id",UUID.class),r.getObject("permit_id",UUID.class),r.getObject("unit_id",UUID.class),
    r.getLong("source_version"),instant(r.getTimestamp("valid_until")),r.getLong("restriction_epoch"),r.getBoolean("subject_active"),
    r.getBoolean("is_current"),instant(r.getTimestamp("permit_until")),r.getObject("reservation_id",UUID.class),r.getBoolean("reservation_confirmed")),tenant,kinds.toArray(String[]::new),limit));
 }
 public List<GrantPlanner.Reservation> reservations(UUID tenant,UUID amenity) {
  if(amenity==null) return List.of();
  return jdbc.query("""
   select id,unit_id,version,starts_at,ends_at from cp.reservations where tenant_id=? and amenity_id=? and kind='BOOKING' and status='CONFIRMED'
    and ends_at>now() and starts_at<now()+interval '30 days' order by starts_at,id limit 5000
   """,(r,n)->new GrantPlanner.Reservation(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getLong("version"),
    r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant()),tenant,amenity);
 }
 public Instant versionUntil(UUID tenant,UUID version) {
  var rows=jdbc.query("select valid_until from cp.access_policy_versions where tenant_id=? and id=?",(r,n)->instant(r.getTimestamp(1)),tenant,version);
  return rows.isEmpty()?null:rows.getFirst();
 }

 // ---- direitos
 private static final String GRANT_COLUMNS="""
  g.id,g.subject_id,g.context_id,g.area_id,g.policy_id,g.policy_version_id,g.origin_kind,
  coalesce(g.origin_reservation_id,g.origin_permit_id,g.origin_functional_context_id,g.origin_membership_id) origin_id,g.origin_version,g.valid_from,
  g.valid_until,g.generation,g.subject_restriction_epoch,g.status,g.status_reason_code,g.revocation_marker_id,
  cp.access_grant_current(g.tenant_id,g.id,now()) is_current,g.version
  """;
 private static GrantModels.Grant mapGrant(ResultSet r,int n) throws SQLException {
  return new GrantModels.Grant(r.getObject("id",UUID.class),r.getObject("subject_id",UUID.class),r.getObject("context_id",UUID.class),
   r.getObject("area_id",UUID.class),r.getObject("policy_id",UUID.class),r.getObject("policy_version_id",UUID.class),r.getString("origin_kind"),
   r.getObject("origin_id",UUID.class),r.getLong("origin_version"),r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_until").toInstant(),
   r.getLong("generation"),r.getLong("subject_restriction_epoch"),r.getString("status"),r.getString("status_reason_code"),
   r.getObject("revocation_marker_id",UUID.class),r.getBoolean("is_current"),r.getLong("version"));
 }
 public List<GrantModels.Grant> grants(UUID tenant,UUID subject,UUID area,int limit) {
  return scan(()->jdbc.query("select "+GRANT_COLUMNS+" from cp.access_grants g where g.tenant_id=? and (?::uuid is null or g.subject_id=?::uuid) "
   +"and (?::uuid is null or g.area_id=?::uuid) order by g.generation desc limit ?",GrantStore::mapGrant,tenant,subject,subject,area,area,limit));
 }
 public GrantModels.Grant grant(UUID tenant,UUID id) {
  var rows=scan(()->jdbc.query("select "+GRANT_COLUMNS+" from cp.access_grants g where g.tenant_id=? and g.id=?",GrantStore::mapGrant,tenant,id));
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Direitos ativos da área (para a varredura antes da derivação). */
 public List<GrantModels.Grant> activeInArea(UUID tenant,UUID area) {
  return scan(()->jdbc.query("select "+GRANT_COLUMNS+" from cp.access_grants g where g.tenant_id=? and g.area_id=? and g.status='ACTIVE' order by g.generation",
   GrantStore::mapGrant,tenant,area));
 }
 public String key(UUID subject,UUID context,UUID area,UUID version,String kind,UUID origin,long originVersion,long epoch,UUID evidence) {
  return jdbc.queryForObject("select cp.access_grant_key(?,?,?,?,?,?,?,?,?)",String.class,subject,context,area,version,kind,origin,originVersion,epoch,evidence);
 }
 /** Estado da chave: ACTIVE (já derivado), REVOKED (revogação vence) ou null. */
 public String keyState(UUID tenant,String key) {
  var rows=jdbc.query("select status from cp.access_grants where tenant_id=? and derivation_key=? and status in ('ACTIVE','REVOKED') "
   +"order by (status='REVOKED') desc limit 1",(r,n)->r.getString(1),tenant,key);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Direito ativo da mesma origem no contexto e na área (será substituído pela nova derivação). */
 public GrantModels.Grant activeSameOrigin(UUID tenant,UUID context,UUID area,String originKey) {
  var rows=scan(()->jdbc.query("select "+GRANT_COLUMNS+" from cp.access_grants g where g.tenant_id=? and g.context_id=? and g.area_id=? and g.origin_key=? "
   +"and g.status='ACTIVE'",GrantStore::mapGrant,tenant,context,area,originKey));
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insert(UUID tenant,UUID id,UUID policy,UUID version,UUID area,GrantPlanner.Planned p,UUID actor) {
  var c=p.candidate();
  jdbc.update("""
   insert into cp.access_grants(id,tenant_id,subject_id,context_id,area_id,policy_id,policy_version_id,origin_kind,origin_membership_id,origin_permit_id,
    origin_reservation_id,origin_functional_context_id,origin_version,age_evidence_id,valid_from,valid_until,subject_restriction_epoch,reason_codes,derived_by)
   values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::text[],?)
   """,id,tenant,c.subjectId(),c.contextId(),area,policy,version,p.originKind(),
   "MEMBERSHIP".equals(p.originKind()) || "RESERVATION".equals(p.originKind())?c.membershipId():null,
   "VISIT_PERMIT".equals(p.originKind())?p.originId():null,"RESERVATION".equals(p.originKind())?p.originId():null,
   "FUNCTIONAL_AUTHORIZATION".equals(p.originKind())?c.contextId():null,p.originVersion(),p.ageEvidenceId(),Timestamp.from(p.validFrom()),
   Timestamp.from(p.validUntil()),c.restrictionEpoch(),p.reasonCodes().toArray(String[]::new),actor);
 }
 public boolean close(UUID tenant,UUID id,long expected,String status,String reason) {
  return jdbc.update("update cp.access_grants set status=?,status_reason_code=?,version=version+1 where tenant_id=? and id=? and version=? and status='ACTIVE'",
   status,reason,tenant,id,expected)==1;
 }

 // ---- marcas
 public UUID markGrant(UUID tenant,UUID subject,UUID grant,String reason) {
  return scan(()->jdbc.queryForObject("""
   insert into cp.revocation_markers(tenant_id,target_kind,subject_id,grant_id,reason_code) values(?,'GRANT',?,?,?) returning id
   """,UUID.class,tenant,subject,grant,reason));
 }
 public List<GrantModels.Marker> markers(UUID tenant,UUID subject,int limit) {
  return jdbc.query("""
   select id,target_kind,subject_id,context_id,grant_id,covered_methods,revoked_through_generation,subject_restriction_epoch,effective_at,reason_code,
    journal_sequence,external_durability_status from cp.revocation_markers where tenant_id=? and subject_id=? order by journal_sequence desc limit ?
   """,(r,n)->new GrantModels.Marker(r.getObject("id",UUID.class),r.getString("target_kind"),r.getObject("subject_id",UUID.class),
    r.getObject("context_id",UUID.class),r.getObject("grant_id",UUID.class),Arrays.stream((Object[])r.getArray("covered_methods").getArray()).map(String::valueOf).toList(),
    r.getLong("revoked_through_generation"),r.getLong("subject_restriction_epoch"),r.getTimestamp("effective_at").toInstant(),r.getString("reason_code"),
    r.getLong("journal_sequence"),r.getString("external_durability_status")),tenant,subject,limit);
 }
 public boolean subjectExists(UUID tenant,UUID subject) {
  Integer n=jdbc.queryForObject("select count(*) from cp.access_subjects where tenant_id=? and id=?",Integer.class,tenant,subject);
  return n!=null && n==1;
 }

 // ---- fatos de um sujeito real (simulação)
 public record SubjectRow(boolean subjectActive,boolean contextCurrent,String contextKind,String sourceKind,UUID permitId) {}
 public SubjectRow subject(UUID tenant,UUID subject,UUID context) {
  var rows=scan(()->jdbc.query("""
   select s.status='ACTIVE' active,cp.subject_context_current(c.tenant_id,c.id) is_current,c.context_kind,c.source_kind,c.permit_id
   from cp.subject_contexts c join cp.access_subjects s on s.tenant_id=c.tenant_id and s.id=c.subject_id
   where c.tenant_id=? and c.id=? and c.subject_id=?
   """,(r,n)->new SubjectRow(r.getBoolean("active"),r.getBoolean("is_current"),r.getString("context_kind"),r.getString("source_kind"),
    r.getObject("permit_id",UUID.class)),tenant,context,subject));
  return rows.isEmpty()?null:rows.getFirst();
 }
 public record GrantFacts(boolean current,boolean reservationBacked,boolean revoked) {}
 /**
  * Direito do contexto na área e revisão, vigente no instante; revogado quando marca alcança o sujeito (na época atual) ou o
  * contexto, ou quando não há direito vigente e um direito revogado cobriria o instante.
  */
 public GrantFacts grantFacts(UUID tenant,UUID subject,UUID context,UUID area,UUID version,Instant at) {
  var ts=Timestamp.from(at);
  return scan(()->jdbc.queryForObject("""
   select coalesce(bool_or(cp.access_grant_current(g.tenant_id,g.id,?)),false),
    coalesce(bool_or(cp.access_grant_current(g.tenant_id,g.id,?) and (g.origin_kind='RESERVATION' or g.reason_codes && array['RESERVATION_GUEST'])),false),
    exists(select 1 from cp.revocation_markers m join cp.access_subjects s on s.tenant_id=m.tenant_id and s.id=m.subject_id
      where m.tenant_id=? and m.subject_id=? and ((m.target_kind='SUBJECT' and m.subject_restriction_epoch>=s.restriction_epoch)
       or (m.target_kind='CONTEXT' and m.context_id=?))),
    coalesce(bool_or(g.status='REVOKED' and g.valid_from<=? and g.valid_until>?),false)
   from cp.access_grants g where g.tenant_id=? and g.subject_id=? and g.context_id=? and g.area_id=? and g.policy_version_id=?
   """,(r,n)->{
    boolean current=r.getBoolean(1);
    return new GrantFacts(current,r.getBoolean(2),r.getBoolean(3) || (!current && r.getBoolean(4)));
   },ts,ts,tenant,subject,context,ts,ts,tenant,subject,context,area,version));
 }

 private static Instant instant(Timestamp t) { return t==null?null:t.toInstant(); }
}
