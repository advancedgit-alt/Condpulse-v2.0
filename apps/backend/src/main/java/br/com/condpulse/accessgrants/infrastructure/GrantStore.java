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
  // Só desliga no sucesso: com erro a transação está abortada (nova instrução daria 25P02 e mascararia a recusa por item
  // da derivação); o rollback ao savepoint ou da transação devolve a configuração local.
  T result=action.get();
  jdbc.queryForObject("select set_config('app.subject_context_scan','',true)",String.class);
  return result;
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
  // Vigência dos contextos em conjunto (cp.subject_contexts_current_now, V051, mesmo predicado de cp.subject_context_current):
  // uma chamada por contexto replanejava a função e reavaliava o RLS a cada linha.
  return scan(()->jdbc.query("""
   with cur as materialized (select x.id from cp.subject_contexts_current_now(?,null) x(id))
   select c.id,c.subject_id,c.context_kind,c.source_kind,c.membership_id,c.permit_id,c.unit_id,c.source_version,c.valid_until,s.restriction_epoch,
    s.status='ACTIVE' subject_active,exists(select 1 from cur where cur.id=c.id) is_current,p.valid_until permit_until,p.reservation_id,
    exists(select 1 from cp.reservations r where r.tenant_id=p.tenant_id and r.id=p.reservation_id and r.status='CONFIRMED') reservation_confirmed
   from cp.subject_contexts c join cp.access_subjects s on s.tenant_id=c.tenant_id and s.id=c.subject_id
   left join cp.visit_permits p on p.tenant_id=c.tenant_id and p.id=c.permit_id
   where c.tenant_id=? and c.status='ACTIVE' and c.context_kind=any(?::text[]) order by c.id limit ?
   """,(r,n)->new GrantPlanner.Candidate(r.getObject("id",UUID.class),r.getObject("subject_id",UUID.class),r.getString("context_kind"),
    r.getString("source_kind"),r.getObject("membership_id",UUID.class),r.getObject("permit_id",UUID.class),r.getObject("unit_id",UUID.class),
    r.getLong("source_version"),instant(r.getTimestamp("valid_until")),r.getLong("restriction_epoch"),r.getBoolean("subject_active"),
    r.getBoolean("is_current"),instant(r.getTimestamp("permit_until")),r.getObject("reservation_id",UUID.class),r.getBoolean("reservation_confirmed")),tenant,tenant,kinds.toArray(String[]::new),limit));
 }
 /** Reservas confirmadas do espaço cuja janela de ingresso (fim + tolerância da regra) ainda não acabou, até o horizonte. */
 public List<GrantPlanner.Reservation> reservations(UUID tenant,UUID amenity,int graceBeforeSeconds,int graceAfterSeconds) {
  if(amenity==null) return List.of();
  return jdbc.query("""
   select id,unit_id,version,starts_at,ends_at from cp.reservations where tenant_id=? and amenity_id=? and kind='BOOKING' and status='CONFIRMED'
    and ends_at+make_interval(secs=>?)>now() and starts_at-make_interval(secs=>?)<now()+interval '30 days' order by starts_at,id limit 5000
   """,(r,n)->new GrantPlanner.Reservation(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getLong("version"),
    r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant()),tenant,amenity,graceAfterSeconds,graceBeforeSeconds);
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
  cp.access_grant_current(g.tenant_id,g.id,now()) is_current,g.version,
  cp.access_grant_current(g.tenant_id,g.id,greatest(now(),g.valid_from)) origin_valid
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
 /**
  * Direito ativo para a varredura: originValid separa a validade da origem (contexto, convite, reserva, sujeito, revisão e
  * revogações, avaliados no início da janela quando ela é futura) da vigência no tempo.
  */
 public record Sweep(GrantModels.Grant grant,boolean originValid) {}
 public List<Sweep> activeInArea(UUID tenant,UUID area) {
  // Validade na origem avaliada de uma vez para a área (cp.access_grants_current_now, V047, mesmo predicado de
  // cp.access_grant_current): uma chamada por direito replanejava a função e estourava o prazo da transação com centenas.
  return scan(()->jdbc.query("""
   with cur as materialized (select c.id from cp.access_grants_current_now(?,null,?) c(id))
   select g.id,g.subject_id,g.context_id,g.area_id,g.policy_id,g.policy_version_id,g.origin_kind,
    coalesce(g.origin_reservation_id,g.origin_permit_id,g.origin_functional_context_id,g.origin_membership_id) origin_id,g.origin_version,g.valid_from,
    g.valid_until,g.generation,g.subject_restriction_epoch,g.status,g.status_reason_code,g.revocation_marker_id,g.version,
    exists(select 1 from cur where cur.id=g.id) origin_valid
   from cp.access_grants g where g.tenant_id=? and g.area_id=? and g.status='ACTIVE' order by g.generation
   """,(r,n)->{
    boolean origin=r.getBoolean("origin_valid");
    Instant from=r.getTimestamp("valid_from").toInstant(),until=r.getTimestamp("valid_until").toInstant(),now=Instant.now();
    var g=new GrantModels.Grant(r.getObject("id",UUID.class),r.getObject("subject_id",UUID.class),r.getObject("context_id",UUID.class),
     r.getObject("area_id",UUID.class),r.getObject("policy_id",UUID.class),r.getObject("policy_version_id",UUID.class),r.getString("origin_kind"),
     r.getObject("origin_id",UUID.class),r.getLong("origin_version"),from,until,r.getLong("generation"),r.getLong("subject_restriction_epoch"),
     r.getString("status"),r.getString("status_reason_code"),r.getObject("revocation_marker_id",UUID.class),
     origin && !from.isAfter(now) && until.isAfter(now),r.getLong("version"));
    return new Sweep(g,origin);
   },tenant,area,tenant,area));
 }
 public String key(UUID subject,UUID context,UUID area,UUID version,String kind,UUID origin,long originVersion,long epoch,UUID evidence) {
  return jdbc.queryForObject("select cp.access_grant_key(?,?,?,?,?,?,?,?,?)",String.class,subject,context,area,version,kind,origin,originVersion,epoch,evidence);
 }
 /** Chaves de várias derivações numa só consulta (mesma função do banco, na mesma ordem). */
 public List<String> keys(UUID area,UUID version,List<GrantPlanner.Planned> planned) {
  if(planned.isEmpty()) return List.of();
  int n=planned.size();
  String[] subjects=new String[n],contexts=new String[n],kinds=new String[n],origins=new String[n],evidence=new String[n];
  Long[] originVersions=new Long[n],epochs=new Long[n];
  for(int i=0;i<n;i++) {
   var p=planned.get(i); var c=p.candidate();
   subjects[i]=c.subjectId().toString(); contexts[i]=c.contextId().toString(); kinds[i]=p.originKind(); origins[i]=p.originId().toString();
   originVersions[i]=p.originVersion(); epochs[i]=c.restrictionEpoch(); evidence[i]=p.ageEvidenceId()==null?null:p.ageEvidenceId().toString();
  }
  return jdbc.query("""
   select cp.access_grant_key(t.s::uuid,t.c::uuid,?::uuid,?::uuid,t.k,t.o::uuid,t.ov,t.e,t.ev::uuid)
   from unnest(?::text[],?::text[],?::text[],?::text[],?::bigint[],?::bigint[],?::text[]) with ordinality t(s,c,k,o,ov,e,ev,n) order by t.n
   """,(r,i)->r.getString(1),area,version,subjects,contexts,kinds,origins,originVersions,epochs,evidence);
 }
 /** Estado de cada chave: ACTIVE (já derivado), REVOKED (revogação vence; prevalece) ou ausente. */
 public java.util.Map<String,String> keyStates(UUID tenant,List<String> keys) {
  var states=new java.util.HashMap<String,String>();
  if(keys.isEmpty()) return states;
  jdbc.query("select derivation_key,status from cp.access_grants where tenant_id=? and derivation_key=any(?::text[]) and status in ('ACTIVE','REVOKED')",
   (org.springframework.jdbc.core.RowCallbackHandler)r->states.merge(r.getString(1),r.getString(2),(a,b)->"REVOKED".equals(a)||"REVOKED".equals(b)?"REVOKED":a),
   tenant,keys.toArray(String[]::new));
  return states;
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
 /** O gatilho confere de novo o contexto e o convite: a conferência de vigência de convites fica ligada durante o INSERT. */
 public void insert(UUID tenant,UUID id,UUID policy,UUID version,UUID area,GrantPlanner.Planned p,UUID actor) {
  var c=p.candidate();
  scan(()->jdbc.update("""
   insert into cp.access_grants(id,tenant_id,subject_id,context_id,area_id,policy_id,policy_version_id,origin_kind,origin_membership_id,origin_permit_id,
    origin_reservation_id,origin_functional_context_id,origin_version,age_evidence_id,valid_from,valid_until,subject_restriction_epoch,reason_codes,derived_by)
   values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::text[],?)
   """,id,tenant,c.subjectId(),c.contextId(),area,policy,version,p.originKind(),
   "MEMBERSHIP".equals(p.originKind()) || "RESERVATION".equals(p.originKind())?c.membershipId():null,
   "VISIT_PERMIT".equals(p.originKind())?p.originId():null,"RESERVATION".equals(p.originKind())?p.originId():null,
   "FUNCTIONAL_AUTHORIZATION".equals(p.originKind())?c.contextId():null,p.originVersion(),p.ageEvidenceId(),Timestamp.from(p.validFrom()),
   Timestamp.from(p.validUntil()),c.restrictionEpoch(),p.reasonCodes().toArray(String[]::new),actor));
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
