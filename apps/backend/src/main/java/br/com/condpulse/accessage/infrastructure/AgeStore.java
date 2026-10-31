package br.com.condpulse.accessage.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.accessage.application.AgeModels;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL da evidência de idade e dos parâmetros (V039), sempre dentro do TenantTransactionExecutor. */
public class AgeStore {
 private final JdbcTemplate jdbc;
 public AgeStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public AgeModels.Settings settings(UUID tenant) {
  var rows=jdbc.query("select leap_day_rule,minor_face_enrollment_enabled,age_evidence_validity_days,version from cp.facial_access_settings where tenant_id=?",
   (r,n)->new AgeModels.Settings(r.getString(1),r.getBoolean(2),r.getInt(3),r.getLong(4)),tenant);
  return rows.isEmpty()?new AgeModels.Settings("MARCH_1",false,365,0):rows.getFirst();
 }
 /** Primeira gravação (versão lida 0) insere; as seguintes conferem a versão. */
 public boolean saveSettings(UUID tenant,long expected,String leap,boolean minors,int days,UUID actor) {
  if(expected==0)
   return jdbc.update("""
    insert into cp.facial_access_settings(tenant_id,leap_day_rule,minor_face_enrollment_enabled,age_evidence_validity_days,updated_by,version)
    values(?,?,?,?,?,1) on conflict (tenant_id) do nothing
    """,tenant,leap,minors,days,actor)==1;
  return jdbc.update("""
   update cp.facial_access_settings set leap_day_rule=?,minor_face_enrollment_enabled=?,age_evidence_validity_days=?,updated_by=?,version=version+1
   where tenant_id=? and version=?
   """,leap,minors,days,actor,tenant,expected)==1;
 }
 public boolean subjectExists(UUID tenant,UUID subject) {
  Integer count=jdbc.queryForObject("select count(*) from cp.access_subjects where tenant_id=? and id=?",Integer.class,tenant,subject);
  return count!=null && count==1;
 }

 public record Row(UUID id,UUID subjectId,String method,Integer minimumAge,Integer maximumAge,Boolean predicate,byte[] ciphertext,
   String keyVersion,String verificationMethod,String sourceRef,String status,Timestamp verifiedAt,Timestamp validUntil,UUID supersedes,long version) {
  public AgeModels.Evidence projection() {
   return new AgeModels.Evidence(id,subjectId,method,verificationMethod,status,verifiedAt.toInstant(),validUntil.toInstant(),supersedes,version);
  }
 }
 private static final String COLUMNS="""
  id,subject_id,method,minimum_age,maximum_age,predicate_result,birth_date_ciphertext,encryption_key_version,verification_method,
  source_evidence_ref,status,verified_at,valid_until,supersedes_evidence_id,version
  """;
 private static Row row(ResultSet r,int n) throws SQLException {
  return new Row(r.getObject("id",UUID.class),r.getObject("subject_id",UUID.class),r.getString("method"),
   (Integer)r.getObject("minimum_age",Integer.class),(Integer)r.getObject("maximum_age",Integer.class),(Boolean)r.getObject("predicate_result",Boolean.class),
   r.getBytes("birth_date_ciphertext"),r.getString("encryption_key_version"),r.getString("verification_method"),r.getString("source_evidence_ref"),
   r.getString("status"),r.getTimestamp("verified_at"),r.getTimestamp("valid_until"),r.getObject("supersedes_evidence_id",UUID.class),r.getLong("version"));
 }
 public Row find(UUID tenant,UUID id) {
  var rows=jdbc.query("select "+COLUMNS+" from cp.age_evidence where tenant_id=? and id=?",AgeStore::row,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Evidência válida do sujeito, travada para substituição. */
 public Row currentForUpdate(UUID tenant,UUID subject) {
  var rows=jdbc.query("select "+COLUMNS+" from cp.age_evidence where tenant_id=? and subject_id=? and status='VALID' for update",AgeStore::row,tenant,subject);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Evidência válida e ainda no prazo (now() da transação). */
 public Row current(UUID tenant,UUID subject) {
  var rows=jdbc.query("select "+COLUMNS+" from cp.age_evidence where tenant_id=? and subject_id=? and status='VALID' and valid_until>now()",
   AgeStore::row,tenant,subject);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public List<Row> history(UUID tenant,UUID subject) {
  return jdbc.query("select "+COLUMNS+" from cp.age_evidence where tenant_id=? and subject_id=? order by created_at desc,id limit 50",AgeStore::row,tenant,subject);
 }
 public boolean close(UUID tenant,UUID id,long expected,String status,String reason,UUID actor) {
  return jdbc.update("""
   update cp.age_evidence set status=?,status_reason=?,status_changed_by=?,version=version+1 where tenant_id=? and id=? and status='VALID' and version=?
   """,status,reason,actor,tenant,id,expected)==1;
 }
 public void insert(UUID tenant,UUID id,UUID subject,String method,Integer min,Integer max,Boolean predicate,byte[] ciphertext,String keyRef,
   String keyVersion,String verificationMethod,String sourceRef,UUID actor,int validityDays,UUID supersedes) {
  jdbc.update("""
   insert into cp.age_evidence(id,tenant_id,subject_id,method,minimum_age,maximum_age,predicate_result,birth_date_ciphertext,encryption_key_ref,
    encryption_key_version,verification_method,source_evidence_ref,verified_by_account_id,valid_until,supersedes_evidence_id)
   values(?,?,?,?,?,?,?,?,?,?,?,?,?,now()+make_interval(days=>?),?)
   """,id,tenant,subject,method,min,max,predicate,ciphertext,keyRef,keyVersion,verificationMethod,sourceRef,actor,validityDays,supersedes);
 }
 /** Repetição só com o pedido inteiro igual; a data cifrada é comparada pelo serviço depois de decifrar. */
 public static boolean sameShape(Row row,UUID subject,String method,Integer min,Integer max,Boolean predicate,String verificationMethod,String sourceRef) {
  return row.subjectId().equals(subject) && row.method().equals(method) && java.util.Objects.equals(row.minimumAge(),min)
   && java.util.Objects.equals(row.maximumAge(),max) && java.util.Objects.equals(row.predicate(),predicate)
   && row.verificationMethod().equals(verificationMethod) && row.sourceRef().equals(sourceRef);
 }
}
