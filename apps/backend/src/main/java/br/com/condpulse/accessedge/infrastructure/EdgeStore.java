package br.com.condpulse.accessedge.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.accessedge.application.EdgeCredentials;
import br.com.condpulse.accessedge.application.EdgeModels;
import br.com.condpulse.accessedge.application.EdgeReportRules;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL do gateway (V048). Identidade: só o hash da credencial entra e sai do banco; nunca é devolvido inteiro. Entrega e
 * relatório rodam no contexto de máquina (app.edge_credential_hash), em que o banco confere de novo a identidade, o
 * conector do equipamento e o detentor do arrendamento.
 */
public class EdgeStore {
 private final JdbcTemplate jdbc;
 public EdgeStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp t=r.getTimestamp(column); return t==null?null:t.toInstant();
 }

 // ---- identidade (MASTER)
 private static final String AGENT="""
  select id,connector_id,instance_id,label,credential_hash,credential_expires_at,credential_rotated_at,status,agent_version,protocol_version,
   last_seen_at,revoked_at,revoke_reason,created_at,version from cp.edge_agents
  """;
 private static EdgeModels.Agent agentRow(ResultSet r,int n) throws SQLException {
  return new EdgeModels.Agent(r.getObject("id",UUID.class),r.getObject("connector_id",UUID.class),r.getObject("instance_id",UUID.class),
   r.getString("label"),EdgeCredentials.fingerprint(r.getString("credential_hash")),instant(r,"credential_expires_at"),instant(r,"credential_rotated_at"),
   r.getString("status"),r.getString("agent_version"),r.getString("protocol_version"),instant(r,"last_seen_at"),instant(r,"revoked_at"),
   r.getString("revoke_reason"),instant(r,"created_at"),r.getLong("version"));
 }
 public List<EdgeModels.Agent> agents(UUID tenant) {
  return jdbc.query(AGENT+" where tenant_id=? order by created_at desc,id",EdgeStore::agentRow,tenant);
 }
 public EdgeModels.Agent agent(UUID tenant,UUID id) {
  var rows=jdbc.query(AGENT+" where tenant_id=? and id=?",EdgeStore::agentRow,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Hash cadastrado (só para conferir repetição idempotente do cadastro; nunca sai da API). */
 public String credentialHash(UUID tenant,UUID id) {
  var rows=jdbc.query("select credential_hash from cp.edge_agents where tenant_id=? and id=?",(r,n)->r.getString(1),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertAgent(UUID tenant,UUID id,UUID connector,String label,String hash,int days,UUID actor) {
  jdbc.update("""
   insert into cp.edge_agents(id,tenant_id,connector_id,label,credential_hash,credential_expires_at,created_by)
   values(?,?,?,?,?,clock_timestamp()+make_interval(days=>?),?)
   """,id,tenant,connector,label,hash,days,actor);
 }
 public boolean rotate(UUID tenant,UUID id,String hash,int days,long expected) {
  return jdbc.update("""
   update cp.edge_agents set credential_hash=?,credential_expires_at=clock_timestamp()+make_interval(days=>?),version=version+1
   where tenant_id=? and id=? and version=? and status<>'REVOKED'
   """,hash,days,tenant,id,expected)==1;
 }
 public boolean revoke(UUID tenant,UUID id,String reason,long expected) {
  return jdbc.update("""
   update cp.edge_agents set status='REVOKED',revoke_reason=?,version=version+1 where tenant_id=? and id=? and version=? and status<>'REVOKED'
   """,reason,tenant,id,expected)==1;
 }

 // ---- contexto de máquina
 /** Linha do gateway pela credencial apresentada (antes de saber o condomínio). */
 public record Presented(UUID agentId,UUID tenantId,UUID connectorId,UUID instanceId,String status,Instant credentialExpiresAt) {}
 public Presented presented(String hash) {
  var rows=jdbc.query("""
   select id,tenant_id,connector_id,instance_id,status,credential_expires_at from cp.edge_agents where credential_hash=?
   """,(r,n)->new Presented(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("connector_id",UUID.class),
    r.getObject("instance_id",UUID.class),r.getString("status"),instant(r,"credential_expires_at")),hash);
  return rows.size()==1?rows.getFirst():null;
 }
 /** O banco confere de novo: mesmo gateway, não revogado, credencial válida e condomínio ativo. */
 public boolean authenticated(UUID tenant,UUID agent) {
  Boolean ok=jdbc.queryForObject("""
   select cp.edge_agent_id() is not distinct from ?::uuid and exists(select 1 from platform.tenants t where t.id=? and t.active)
   """,Boolean.class,agent,tenant);
  return Boolean.TRUE.equals(ok);
 }
 /** Contato autenticado: PROVISIONED vira ONLINE; last_seen_at no máximo uma gravação por minuto. */
 public void touch(UUID tenant,UUID agent) {
  jdbc.update("""
   update cp.edge_agents set status='ONLINE',last_seen_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and (status='PROVISIONED' or last_seen_at is null or last_seen_at<clock_timestamp()-interval '60 seconds')
   """,tenant,agent);
 }
 public Instant serverTime() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }

 // ---- entrega
 /** Trabalhos pendentes dos equipamentos deste gateway, sem entrega em andamento: retirada (prioridade 0) primeiro. */
 public List<UUID> leasable(UUID tenant,int max) {
  return jdbc.query("""
   select p.id from cp.rights_publications p
   where p.tenant_id=? and p.state='PENDING' and p.next_attempt_at<=clock_timestamp() and cp.edge_device(p.tenant_id,p.device_id)
    and not exists(select 1 from cp.edge_leases l where l.tenant_id=p.tenant_id and l.publication_id=p.id
     and (l.acknowledged_at is not null or l.finished_at is not null or l.lease_until>clock_timestamp()))
   order by p.priority,p.desired_version,p.id limit ?
   """,(r,n)->r.getObject(1,UUID.class),tenant,max);
 }
 public void lease(UUID tenant,UUID publication,UUID agent,int seconds) {
  jdbc.update("""
   insert into cp.edge_leases(tenant_id,publication_id,agent_id,device_id,lease_seconds)
   select p.tenant_id,p.id,?,p.device_id,? from cp.rights_publications p where p.tenant_id=? and p.id=?
   """,agent,seconds,tenant,publication);
 }
 public EdgeModels.SyncCommand command(UUID tenant,UUID publication,UUID agent) {
  var rows=jdbc.query("""
   select p.id,l.id as lease_id,l.attempt,l.lease_until,p.device_id,p.document->>'subjectRef' as subject_ref,p.action,p.desired_version,p.priority,
    p.document_hash,p.document::text as document_text,p.created_at
   from cp.rights_publications p join cp.edge_leases l on l.tenant_id=p.tenant_id and l.publication_id=p.id and l.agent_id=?
   where p.tenant_id=? and p.id=? order by l.attempt desc limit 1
   """,(r,n)->{
    UUID id=r.getObject("id",UUID.class);
    return new EdgeModels.SyncCommand(id,id.toString(),r.getObject("lease_id",UUID.class),r.getInt("attempt"),instant(r,"lease_until"),
     r.getObject("device_id",UUID.class),r.getString("subject_ref"),r.getString("action"),r.getLong("desired_version"),r.getInt("priority"),
     r.getString("document_hash"),r.getString("document_text"),instant(r,"created_at"));
   },agent,tenant,publication);
  return rows.isEmpty()?null:rows.getFirst();
 }

 // ---- relatório
 public record StoredReport(String requestHash,String outcome,String outcomeCode,UUID publicationId) {}
 public StoredReport report(UUID tenant,UUID agent,String key) {
  var rows=jdbc.query("""
   select request_hash,outcome,outcome_code,publication_id from cp.edge_reports where tenant_id=? and agent_id=? and idempotency_key=?
   """,(r,n)->new StoredReport(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,UUID.class)),tenant,agent,key);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public record Lease(UUID leaseId,UUID agentId) {}
 /** Arrendamento mais recente do trabalho (visível só se o equipamento é deste gateway). */
 public Lease latestLease(UUID tenant,UUID publication) {
  var rows=jdbc.query("""
   select id,agent_id from cp.edge_leases where tenant_id=? and publication_id=? order by attempt desc limit 1
   """,(r,n)->new Lease(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),tenant,publication);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public record JobRow(EdgeReportRules.Job job,int attempts,long version) {}
 public JobRow job(UUID tenant,UUID publication) {
  var rows=jdbc.query("""
   select state,document_hash,confirmation_level,attempts,version from cp.rights_publications where tenant_id=? and id=?
   """,(r,n)->new JobRow(new EdgeReportRules.Job(r.getString(1),r.getString(2),r.getString(3)),r.getInt(4),r.getLong(5)),tenant,publication);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public int leaseAttempt(UUID tenant,UUID lease) {
  Integer n=jdbc.queryForObject("select attempt from cp.edge_leases where tenant_id=? and id=?",Integer.class,tenant,lease);
  return n==null?0:n;
 }
 public boolean applyResult(UUID tenant,UUID publication,long expected,String state,String level,String observed,String code,int attempts) {
  return jdbc.update("""
   update cp.rights_publications set state=?,confirmation_level=?,observed_hash=?,result_code=?,attempts=greatest(attempts,?),version=version+1
   where tenant_id=? and id=? and version=?
   """,state,level,observed,code,attempts,tenant,publication,expected)==1;
 }
 public void progressLease(UUID tenant,UUID lease,boolean acknowledge,boolean finish) {
  if(!acknowledge && !finish) return;
  jdbc.update("""
   update cp.edge_leases set acknowledged_at=case when ? then coalesce(acknowledged_at,clock_timestamp()) else acknowledged_at end,
    finished_at=case when ? then coalesce(finished_at,clock_timestamp()) else finished_at end,version=version+1
   where tenant_id=? and id=? and ((? and acknowledged_at is null) or (? and finished_at is null))
   """,acknowledge,finish,tenant,lease,acknowledge,finish);
 }
 public void insertReport(UUID tenant,UUID agent,UUID lease,UUID publication,String key,String requestHash,EdgeReportRules.Report r,
   String outcome,String outcomeCode) {
  jdbc.update("""
   insert into cp.edge_reports(tenant_id,agent_id,lease_id,publication_id,idempotency_key,request_hash,state,result_code,document_hash,observed_hash,
    outcome,outcome_code) values(?,?,?,?,?,?,?,?,?,?,?,?)
   """,tenant,agent,lease,publication,key,requestHash,r.state(),r.resultCode(),r.documentHash(),r.observedHash(),outcome,outcomeCode);
 }
 public void lockTenant(UUID tenant) {
  jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))::text",String.class,"cp.installation:"+tenant);
 }
}
