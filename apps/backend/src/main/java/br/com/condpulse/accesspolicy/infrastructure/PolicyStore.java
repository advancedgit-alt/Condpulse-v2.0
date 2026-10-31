package br.com.condpulse.accesspolicy.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.accesspolicy.application.PolicyModels;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

/** SQL das políticas (V043); o banco confere área, hash, revisão, transições, validade da validação e aprovador. */
public class PolicyStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;
 public PolicyStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 // ---- políticas
 public List<PolicyModels.Policy> policies(UUID tenant) {
  return jdbc.query("select * from cp.access_policies where tenant_id=? order by name,id limit 1000",PolicyStore::policy,tenant);
 }
 public PolicyModels.Policy policy(UUID tenant,UUID id) {
  var rows=jdbc.query("select * from cp.access_policies where tenant_id=? and id=?",PolicyStore::policy,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static PolicyModels.Policy policy(ResultSet r,int n) throws SQLException {
  return new PolicyModels.Policy(r.getObject("id",UUID.class),r.getObject("area_id",UUID.class),r.getString("name"),r.getString("status"),
   r.getObject("current_published_version_id",UUID.class),r.getTimestamp("updated_at").toInstant(),r.getLong("version"));
 }
 public void insertPolicy(UUID tenant,PolicyModels.CreatePolicy p,UUID actor) {
  jdbc.update("insert into cp.access_policies(id,tenant_id,area_id,name,created_by) values(?,?,?,?,?)",p.policyId(),tenant,p.areaId(),p.name(),actor);
 }
 public boolean updatePolicy(UUID tenant,UUID id,PolicyModels.UpdatePolicy p,UUID actor) {
  return jdbc.update("update cp.access_policies set name=?,status=?,updated_by=?,version=version+1 where tenant_id=? and id=? and version=?",
   p.name(),p.status(),actor,tenant,id,p.expectedVersion())==1;
 }

 // ---- revisões
 public List<PolicyModels.PolicyVersion> versions(UUID tenant,UUID policy) {
  return jdbc.query("select * from cp.access_policy_versions where tenant_id=? and policy_id=? order by revision_number desc limit 500",
   PolicyStore::version,tenant,policy);
 }
 public PolicyModels.PolicyVersion version(UUID tenant,UUID policy,UUID id) {
  var rows=jdbc.query("select * from cp.access_policy_versions where tenant_id=? and policy_id=? and id=?",PolicyStore::version,tenant,policy,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static PolicyModels.PolicyVersion version(ResultSet r,int n) throws SQLException {
  return new PolicyModels.PolicyVersion(r.getObject("id",UUID.class),r.getObject("policy_id",UUID.class),r.getInt("revision_number"),
   r.getString("state"),document(r.getString("document")),r.getString("canonical_hash"),r.getString("change_reason"),
   r.getObject("validation_id",UUID.class),r.getObject("authored_by",UUID.class),r.getObject("approved_by",UUID.class),
   instant(r.getTimestamp("approved_at")),r.getString("rejected_reason"),r.getTimestamp("created_at").toInstant(),r.getLong("version"));
 }
 public void insertVersion(UUID tenant,UUID policy,PolicyModels.CreateVersion v,UUID actor) {
  jdbc.update("""
   insert into cp.access_policy_versions(id,tenant_id,policy_id,document_schema_version,document,change_reason,authored_by) values(?,?,?,'1.0',?::jsonb,?,?)
   """,v.versionId(),tenant,policy,JSON.writeValueAsString(v.document()),v.changeReason(),actor);
 }
 public boolean recordValidation(UUID tenant,UUID version,UUID validation,String state,long expected) {
  return jdbc.update("update cp.access_policy_versions set validation_id=?,state=?,version=version+1 where tenant_id=? and id=? and version=?",
   validation,state,tenant,version,expected)==1;
 }
 public boolean approve(UUID tenant,UUID version,long expected) {
  return jdbc.update("update cp.access_policy_versions set state='APPROVED',version=version+1 where tenant_id=? and id=? and version=? and state='VALIDATED'",
   tenant,version,expected)==1;
 }
 public boolean reject(UUID tenant,UUID version,String reason,long expected) {
  return jdbc.update("""
   update cp.access_policy_versions set state='REJECTED',rejected_reason=?,version=version+1 where tenant_id=? and id=? and version=?
    and state in ('DRAFT','VALIDATED','APPROVED')
   """,reason,tenant,version,expected)==1;
 }

 // ---- validações
 public PolicyModels.ValidationResult validation(UUID tenant,UUID id) {
  var rows=jdbc.query("select * from cp.access_policy_validations where tenant_id=? and id=?",(r,n)->new PolicyModels.ValidationResult(
   r.getObject("id",UUID.class),r.getObject("policy_version_id",UUID.class),r.getInt("installation_revision"),r.getTimestamp("validated_at").toInstant(),
   r.getTimestamp("expires_at").toInstant(),r.getString("status"),JSON.readValue(r.getString("issues"),new TypeReference<List<PolicyModels.ValidationIssue>>(){}),
   uuids(r.getArray("affected_point_ids")),strings(r.getArray("required_capabilities"))),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertValidation(UUID tenant,UUID id,PolicyModels.PolicyVersion v,int installationRevision,String status,
   List<PolicyModels.ValidationIssue> issues,List<UUID> points,List<String> capabilities,UUID actor) {
  jdbc.update("""
   insert into cp.access_policy_validations(id,tenant_id,policy_id,policy_version_id,canonical_hash,installation_revision,status,issues,
    affected_point_ids,required_capabilities,validated_by) values(?,?,?,?,?,?,?,?::jsonb,?::uuid[],?::text[],?)
   """,id,tenant,v.policyId(),v.versionId(),v.canonicalHash(),installationRevision,status,JSON.writeValueAsString(issues),
   points.stream().map(UUID::toString).toArray(String[]::new),capabilities.toArray(String[]::new),actor);
 }
 public int installationRevision(UUID tenant) {
  Integer value=jdbc.queryForObject("select cp.installation_current_revision(?)",Integer.class,tenant);
  return value==null?0:value;
 }

 // ---- fatos de área e destinos (varredura mínima, só durante a consulta)
 public PolicyModels.AreaFacts area(UUID tenant,UUID id) {
  var rows=jdbc.query("select id,status,area_kind,amenity_id,timezone,essential_route from cp.access_areas where tenant_id=? and id=?",
   (r,n)->new PolicyModels.AreaFacts(r.getObject("id",UUID.class),r.getString("status"),r.getString("area_kind"),r.getObject("amenity_id",UUID.class),
    r.getString("timezone"),r.getBoolean("essential_route")),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static final String ENTERING="""
    p.tenant_id=? and p.status<>'RETIRED'
     and ((p.to_area_id=? and p.flow_direction in ('FROM_TO','BOTH','UNKNOWN')) or (p.from_area_id=? and p.flow_direction in ('TO_FROM','BOTH','UNKNOWN')))
   """;
 /** Total real de passagens de ingresso da área (conferido antes de listar: nunca lista truncada em silêncio). */
 public int countDestinations(UUID tenant,UUID area) {
  Integer n=jdbc.queryForObject("select count(*) from cp.access_points p where"+ENTERING,Integer.class,tenant,area,area);
  return n==null?0:n;
 }
 /** Até MAX_TARGETS passagens de ingresso, com o vínculo ativo e a combinação; pointId filtra uma só. */
 public List<PolicyModels.Destination> destinations(UUID tenant,UUID area,UUID point,int limit) {
  jdbc.queryForObject("select set_config('app.policy_destination_scan','on',true)",String.class);
  try {
   return jdbc.query("""
    select p.id,p.status,p.flow_direction,b.binding_revision,h.status homologation_status,h.review_due_at,h.approved_capabilities
     from cp.access_points p
     left join cp.point_bindings b on b.tenant_id=p.tenant_id and b.point_id=p.id and b.status='ACTIVE'
     left join platform.access_homologations h on h.id=b.homologation_id
     where"""+ENTERING+" and (?::uuid is null or p.id=?::uuid) order by p.id limit ?",
    (r,n)->new PolicyModels.Destination(r.getObject("id",UUID.class),r.getString("status"),r.getString("flow_direction"),
     (Integer)r.getObject("binding_revision"),r.getString("homologation_status"),instant(r.getTimestamp("review_due_at")),
     r.getArray("approved_capabilities")==null?List.of():strings(r.getArray("approved_capabilities"))),tenant,area,area,point,point,limit);
  } finally { jdbc.queryForObject("select set_config('app.policy_destination_scan','',true)",String.class); }
 }

 private static PolicyModels.PolicyDocument document(String json) { return JSON.readValue(json,PolicyModels.PolicyDocument.class); }
 private static Instant instant(Timestamp t) { return t==null?null:t.toInstant(); }
 private static List<UUID> uuids(Array a) throws SQLException { return Arrays.stream((Object[])a.getArray()).map(o->(UUID)o).toList(); }
 private static List<String> strings(Array a) throws SQLException { return Arrays.stream((Object[])a.getArray()).map(String::valueOf).toList(); }
}
