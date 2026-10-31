package br.com.condpulse.accesscatalog.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.accesscatalog.application.CatalogModels;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** SQL do catálogo e das homologações (V040); o banco calcula revisão, hashes, transições e autoria da aprovação. */
public class CatalogStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;
 public CatalogStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public List<CatalogModels.Profile> profiles(String manufacturer) {
  return manufacturer==null
   ?jdbc.query("select * from platform.access_model_profiles order by manufacturer,catalog_code,profile_revision",CatalogStore::profile)
   :jdbc.query("select * from platform.access_model_profiles where manufacturer=? order by catalog_code,profile_revision",CatalogStore::profile,manufacturer);
 }
 public CatalogModels.Profile profile(UUID id) {
  var rows=jdbc.query("select * from platform.access_model_profiles where id=?",CatalogStore::profile,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static CatalogModels.Profile profile(ResultSet r,int n) throws SQLException {
  return new CatalogModels.Profile(r.getObject("id",UUID.class),r.getString("catalog_code"),r.getString("manufacturer"),r.getString("model_sku"),
   r.getString("hardware_revision"),r.getString("region_code"),r.getString("category"),r.getString("source_category"),r.getString("market_lifecycle"),
   r.getString("status"),r.getInt("profile_revision"),list(r.getString("sources")),map(r.getString("declared_capabilities")),
   map(r.getString("declared_limits")),r.getString("notes"),r.getObject("consulted_on",java.time.LocalDate.class),r.getString("document_hash"));
 }

 private static final String HOMOLOGATION="""
  select *,(status in ('APROVADO_BANCADA','APROVADO_PILOTO','HOMOLOGADO') and (review_due_at is null or review_due_at>now())) as current
  from platform.access_homologations
  """;
 public List<CatalogModels.Homologation> homologations(UUID profile) {
  return profile==null
   ?jdbc.query(HOMOLOGATION+" order by created_at desc,id limit 500",CatalogStore::homologation)
   :jdbc.query(HOMOLOGATION+" where model_profile_id=? order by created_at desc,id limit 500",CatalogStore::homologation,profile);
 }
 public CatalogModels.Homologation homologation(UUID id) {
  var rows=jdbc.query(HOMOLOGATION+" where id=?",CatalogStore::homologation,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static CatalogModels.Homologation homologation(ResultSet r,int n) throws SQLException {
  Timestamp approved=r.getTimestamp("approved_at"),review=r.getTimestamp("review_due_at");
  return new CatalogModels.Homologation(r.getObject("id",UUID.class),r.getInt("homologation_revision"),r.getObject("model_profile_id",UUID.class),
   r.getString("firmware_exact"),r.getString("adapter_name"),r.getString("adapter_version"),r.getString("contract_schema_version"),
   r.getString("decision_mode"),r.getString("topology"),map(r.getString("capabilities")),map(r.getString("limits")),list(r.getString("limitations")),
   uuids(r.getArray("evidence_refs")),r.getString("evidence_hash"),r.getString("status"),strings(r.getArray("approved_capabilities")),
   list(r.getString("restrictions")),approved==null?null:approved.toInstant(),review==null?null:review.toInstant(),r.getBoolean("current"),
   r.getLong("version"));
 }
 public List<CatalogModels.Decision> decisions(UUID homologation) {
  return jdbc.query("select * from platform.access_homologation_decisions where homologation_id=? order by created_at,id",(r,n)->
   new CatalogModels.Decision(r.getObject("id",UUID.class),r.getString("decision"),r.getString("from_status"),r.getString("to_status"),
    r.getString("reason"),uuids(r.getArray("evidence_refs")),strings(r.getArray("approved_capabilities")),r.getObject("decided_by",UUID.class),
    r.getTimestamp("created_at").toInstant()),homologation);
 }
 public boolean decisionExists(UUID decision) {
  Integer count=jdbc.queryForObject("select count(*) from platform.access_homologation_decisions where id=?",Integer.class,decision);
  return count!=null && count>0;
 }
 /** Repetição só com a decisão inteira igual. */
 public boolean sameDecision(UUID decision,UUID homologation,String kind,String reason,List<UUID> evidence,List<String> approved,
   Instant reviewDue,String restrictions) {
  Boolean same=jdbc.query(connection->{
   var statement=connection.prepareStatement("""
    select count(*)=1 from platform.access_homologation_decisions where id=? and homologation_id=? and decision=? and reason=?
     and evidence_refs=? and approved_capabilities=? and review_due_at is not distinct from ? and restrictions=?::jsonb
    """);
   statement.setObject(1,decision); statement.setObject(2,homologation); statement.setString(3,kind); statement.setString(4,reason);
   statement.setArray(5,connection.createArrayOf("uuid",evidence.toArray())); statement.setArray(6,connection.createArrayOf("text",approved.toArray()));
   statement.setTimestamp(7,reviewDue==null?null:Timestamp.from(reviewDue)); statement.setString(8,restrictions);
   return statement;
  },(ResultSet r)->r.next() && r.getBoolean(1));
  return Boolean.TRUE.equals(same);
 }
 /** Repetição só com o dossiê inteiro igual (conteúdo técnico, evidências e motivo). */
 public boolean sameHomologation(UUID id,UUID profile,String firmware,String adapter,String adapterVersion,String contract,String mode,
   String topology,String capabilities,String limits,List<UUID> evidence,String limitations,String reason) {
  Boolean same=jdbc.query(connection->{
   var statement=connection.prepareStatement("""
    select count(*)=1 from platform.access_homologations where id=? and model_profile_id=? and firmware_exact=? and adapter_name=?
     and adapter_version=? and contract_schema_version=? and decision_mode=? and topology=? and capabilities=?::jsonb and limits=?::jsonb
     and evidence_refs=? and limitations=?::jsonb and reason=?
    """);
   statement.setObject(1,id); statement.setObject(2,profile); statement.setString(3,firmware); statement.setString(4,adapter);
   statement.setString(5,adapterVersion); statement.setString(6,contract); statement.setString(7,mode); statement.setString(8,topology);
   statement.setString(9,capabilities); statement.setString(10,limits); statement.setArray(11,connection.createArrayOf("uuid",evidence.toArray()));
   statement.setString(12,limitations); statement.setString(13,reason);
   return statement;
  },(ResultSet r)->r.next() && r.getBoolean(1));
  return Boolean.TRUE.equals(same);
 }
 public void insertHomologation(UUID id,UUID profile,String firmware,String adapter,String adapterVersion,String contract,String mode,
   String topology,String capabilities,String limits,List<UUID> evidence,String limitations,String reason,UUID actor) {
  jdbc.update(connection->{
   var statement=connection.prepareStatement("""
    insert into platform.access_homologations(id,model_profile_id,firmware_exact,adapter_name,adapter_version,contract_schema_version,
     decision_mode,topology,capabilities,limits,evidence_refs,limitations,reason,requested_by)
    values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,?,?)
    """);
   statement.setObject(1,id); statement.setObject(2,profile); statement.setString(3,firmware); statement.setString(4,adapter);
   statement.setString(5,adapterVersion); statement.setString(6,contract); statement.setString(7,mode); statement.setString(8,topology);
   statement.setString(9,capabilities); statement.setString(10,limits); statement.setArray(11,connection.createArrayOf("uuid",evidence.toArray()));
   statement.setString(12,limitations); statement.setString(13,reason); statement.setObject(14,actor);
   return statement;
  });
 }
 public void insertDecision(UUID id,UUID homologation,String kind,String reason,List<UUID> evidence,List<String> approved,Instant reviewDue,
   String restrictions,UUID actor) {
  jdbc.update(connection->{
   var statement=connection.prepareStatement("""
    insert into platform.access_homologation_decisions(id,homologation_id,decision,reason,evidence_refs,approved_capabilities,restrictions,
     review_due_at,decided_by) values(?,?,?,?,?,?,?::jsonb,?,?)
    """);
   statement.setObject(1,id); statement.setObject(2,homologation); statement.setString(3,kind); statement.setString(4,reason);
   statement.setArray(5,connection.createArrayOf("uuid",evidence.toArray())); statement.setArray(6,connection.createArrayOf("text",approved.toArray()));
   statement.setString(7,restrictions); statement.setTimestamp(8,reviewDue==null?null:Timestamp.from(reviewDue)); statement.setObject(9,actor);
   return statement;
  });
 }
 /** Segue a decisão gravada nesta transação, pela versão lida. */
 public boolean applyDecision(UUID homologation,String kind,long expected) {
  return jdbc.update("update platform.access_homologations set status=platform.homologation_next(status,?),version=version+1 where id=? and version=?",
   kind,homologation,expected)==1;
 }
 public static String json(Object value) { return JSON.writeValueAsString(value); }
 @SuppressWarnings("unchecked")
 private static Map<String,Object> map(String json) { return json==null?Map.of():JSON.readValue(json,Map.class); }
 @SuppressWarnings("unchecked")
 private static List<Object> list(String json) { return json==null?List.of():JSON.readValue(json,List.class); }
 private static List<UUID> uuids(Array array) throws SQLException {
  return array==null?List.of():Arrays.asList((UUID[])array.getArray());
 }
 private static List<String> strings(Array array) throws SQLException {
  return array==null?List.of():Arrays.asList((String[])array.getArray());
 }
}
