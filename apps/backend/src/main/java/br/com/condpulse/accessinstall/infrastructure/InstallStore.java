package br.com.condpulse.accessinstall.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import br.com.condpulse.accessinstall.application.InstallModels;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL da instalação física (V042); o banco confere função do canal, revisão, hash, assinaturas, transições e comissionamento. */
public class InstallStore {
 private final JdbcTemplate jdbc;
 public InstallStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 // ---- equipamentos
 public List<InstallModels.Device> devices(UUID tenant) {
  return jdbc.query("select * from cp.access_devices where tenant_id=? order by label,id limit 1000",InstallStore::device,tenant);
 }
 public InstallModels.Device device(UUID tenant,UUID id) {
  var rows=jdbc.query("select * from cp.access_devices where tenant_id=? and id=?",InstallStore::device,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static InstallModels.Device device(ResultSet r,int n) throws SQLException {
  return new InstallModels.Device(r.getObject("id",UUID.class),r.getObject("connector_id",UUID.class),r.getString("external_id"),
   r.getObject("provisioned_instance_id",UUID.class),r.getString("label"),r.getString("direction"),r.getString("status"),r.getString("hardware_revision"),
   r.getObject("homologation_id",UUID.class),r.getString("device_role"),r.getString("connection_mode"),r.getLong("version"));
 }
 public void insertDevice(UUID tenant,InstallModels.CreateDevice d,UUID actor) {
  jdbc.update("""
   insert into cp.access_devices(id,tenant_id,connector_id,external_id,label,direction,hardware_revision,homologation_id,device_role,connection_mode,
    registered_by) values(?,?,?,?,?,?,?,?,?,?,?)
   """,d.deviceId(),tenant,d.connectorId(),d.externalId(),d.label(),d.direction(),d.hardwareRevision(),d.homologationId(),d.deviceRole(),
   d.connectionMode(),actor);
 }
 public boolean updateDevice(UUID tenant,UUID id,InstallModels.UpdateDevice d) {
  return jdbc.update("""
   update cp.access_devices set label=?,direction=?,status=?,hardware_revision=?,homologation_id=?,device_role=?,connection_mode=?,version=version+1
    where tenant_id=? and id=? and version=?
   """,d.label(),d.direction(),d.status(),d.hardwareRevision(),d.homologationId(),d.deviceRole(),d.connectionMode(),tenant,id,d.expectedVersion())==1;
 }
 public static boolean sameDevice(InstallModels.Device a,InstallModels.CreateDevice d) {
  return a.connectorId().equals(d.connectorId()) && a.externalId().equals(d.externalId()) && a.label().equals(d.label())
   && a.direction().equals(d.direction()) && Objects.equals(a.hardwareRevision(),d.hardwareRevision())
   && Objects.equals(a.homologationId(),d.homologationId()) && Objects.equals(a.deviceRole(),d.deviceRole())
   && Objects.equals(a.connectionMode(),d.connectionMode());
 }

 // ---- canais
 public List<InstallModels.Channel> channels(UUID tenant) {
  return jdbc.query("select * from cp.device_channels where tenant_id=? order by device_id,native_namespace,external_channel limit 2000",
   InstallStore::channel,tenant);
 }
 public InstallModels.Channel channel(UUID tenant,UUID id) {
  var rows=jdbc.query("select * from cp.device_channels where tenant_id=? and id=?",InstallStore::channel,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static InstallModels.Channel channel(ResultSet r,int n) throws SQLException {
  return new InstallModels.Channel(r.getObject("id",UUID.class),r.getObject("device_id",UUID.class),r.getString("native_namespace"),
   r.getString("external_channel"),r.getString("channel_kind"),r.getString("status"),r.getLong("version"));
 }
 public void insertChannel(UUID tenant,InstallModels.CreateChannel c,UUID actor) {
  jdbc.update("""
   insert into cp.device_channels(id,tenant_id,device_id,native_namespace,external_channel,channel_kind,status,created_by) values(?,?,?,?,?,?,?,?)
   """,c.channelId(),tenant,c.deviceId(),c.nativeNamespace(),c.externalChannel(),c.channelKind(),c.status(),actor);
 }
 public boolean updateChannel(UUID tenant,UUID id,InstallModels.UpdateChannel c) {
  return jdbc.update("update cp.device_channels set channel_kind=?,status=?,version=version+1 where tenant_id=? and id=? and version=?",
   c.channelKind(),c.status(),tenant,id,c.expectedVersion())==1;
 }
 public static boolean sameChannel(InstallModels.Channel a,InstallModels.CreateChannel c) {
  return a.deviceId().equals(c.deviceId()) && a.nativeNamespace().equals(c.nativeNamespace()) && a.externalChannel().equals(c.externalChannel())
   && a.channelKind().equals(c.channelKind());
 }

 // ---- vínculos
 public List<InstallModels.Binding> bindings(UUID tenant) {
  return jdbc.query("select b.*,b.direction_map->>'entryReader' entry_reader,b.direction_map->>'exitReader' exit_reader from cp.point_bindings b where b.tenant_id=? order by b.point_id,b.binding_revision limit 2000",InstallStore::binding,tenant);
 }
 public InstallModels.Binding binding(UUID tenant,UUID id) {
  var rows=jdbc.query("select b.*,b.direction_map->>'entryReader' entry_reader,b.direction_map->>'exitReader' exit_reader from cp.point_bindings b where b.tenant_id=? and b.id=?",InstallStore::binding,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static InstallModels.Binding binding(ResultSet r,int n) throws SQLException {
  return new InstallModels.Binding(r.getObject("id",UUID.class),r.getObject("point_id",UUID.class),r.getInt("binding_revision"),
   r.getObject("actuator_channel_id",UUID.class),r.getObject("controller_device_id",UUID.class),r.getObject("entry_reader_device_id",UUID.class),
   r.getObject("exit_reader_device_id",UUID.class),r.getObject("door_contact_channel_id",UUID.class),r.getObject("exit_request_channel_id",UUID.class),
   r.getObject("passage_sensor_channel_id",UUID.class),r.getObject("homologation_id",UUID.class),r.getObject("installation_acceptance_id",UUID.class),
   new InstallModels.Directions(r.getString("entry_reader"),r.getString("exit_reader")),r.getString("status"),instant(r.getTimestamp("valid_from")),
   instant(r.getTimestamp("valid_until")),r.getString("wiring_evidence_ref"),r.getString("configuration_hash"),r.getString("commissioned_fingerprint"),r.getLong("version"));
 }
 public void insertBinding(UUID tenant,InstallModels.CreateBinding b,UUID actor) {
  jdbc.update("""
   insert into cp.point_bindings(id,tenant_id,point_id,actuator_channel_id,controller_device_id,entry_reader_device_id,exit_reader_device_id,
    door_contact_channel_id,exit_request_channel_id,passage_sensor_channel_id,homologation_id,direction_map,wiring_evidence_ref,created_by)
   values(?,?,?,?,?,?,?,?,?,?,?,jsonb_strip_nulls(jsonb_build_object('entryReader',?::text,'exitReader',?::text)),?,?)
   """,b.bindingId(),tenant,b.pointId(),b.actuatorChannelId(),b.controllerDeviceId(),b.entryReaderDeviceId(),b.exitReaderDeviceId(),
   b.doorContactChannelId(),b.exitRequestChannelId(),b.passageSensorChannelId(),b.homologationId(),b.directions().entryReader(),
   b.directions().exitReader(),b.wiringEvidenceRef(),actor);
 }
 public boolean submitBinding(UUID tenant,UUID id,UUID acceptance,long expected) {
  return jdbc.update("""
   update cp.point_bindings set installation_acceptance_id=?,status='PENDING_COMMISSION',version=version+1
    where tenant_id=? and id=? and version=? and status='DRAFT'
   """,acceptance,tenant,id,expected)==1;
 }
 public boolean discardBinding(UUID tenant,UUID id,long expected) {
  return jdbc.update("""
   update cp.point_bindings set status='SUPERSEDED',version=version+1 where tenant_id=? and id=? and version=? and status in ('DRAFT','PENDING_COMMISSION')
   """,tenant,id,expected)==1;
 }
 public static boolean sameBinding(InstallModels.Binding a,InstallModels.CreateBinding b) {
  return a.pointId().equals(b.pointId()) && a.actuatorChannelId().equals(b.actuatorChannelId()) && a.controllerDeviceId().equals(b.controllerDeviceId())
   && Objects.equals(a.entryReaderDeviceId(),b.entryReaderDeviceId()) && Objects.equals(a.exitReaderDeviceId(),b.exitReaderDeviceId())
   && Objects.equals(a.doorContactChannelId(),b.doorContactChannelId()) && Objects.equals(a.exitRequestChannelId(),b.exitRequestChannelId())
   && Objects.equals(a.passageSensorChannelId(),b.passageSensorChannelId()) && a.homologationId().equals(b.homologationId())
   && a.directions().equals(b.directions()) && a.wiringEvidenceRef().equals(b.wiringEvidenceRef());
 }

 // ---- aceites
 public List<InstallModels.Acceptance> acceptances(UUID tenant) {
  return jdbc.query("select * from cp.installation_acceptances where tenant_id=? order by installation_revision desc limit 500",
   InstallStore::acceptance,tenant);
 }
 public InstallModels.Acceptance acceptance(UUID tenant,UUID id) {
  var rows=jdbc.query("select * from cp.installation_acceptances where tenant_id=? and id=?",InstallStore::acceptance,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static InstallModels.Acceptance acceptance(ResultSet r,int n) throws SQLException {
  return new InstallModels.Acceptance(r.getObject("id",UUID.class),r.getInt("installation_revision"),r.getString("status"),r.getString("plant_ref"),
   r.getString("plant_hash"),r.getString("topology_manifest_ref"),r.getString("topology_hash"),r.getString("capability_envelope_hash"),
   r.getString("test_suite_version"),r.getString("test_report_ref"),r.getInt("critical_tests_pending"),r.getInt("critical_tests_failed"),
   r.getString("emergency_test_ref"),r.getString("rollback_plan_ref"),r.getString("revocation_checkpoint_ref"),
   r.getObject("technical_approver_account_id",UUID.class),instant(r.getTimestamp("technical_signed_at")),
   r.getObject("rules_approver_account_id",UUID.class),instant(r.getTimestamp("rules_signed_at")),instant(r.getTimestamp("effective_at")),
   instant(r.getTimestamp("review_due_at")),r.getString("status_reason"),r.getLong("version"));
 }
 public static InstallModels.AcceptanceSummary summary(InstallModels.Acceptance a) {
  return new InstallModels.AcceptanceSummary(a.acceptanceId(),a.installationRevision(),a.status(),a.criticalTestsPending(),a.criticalTestsFailed(),
   a.technicalSignedAt(),a.rulesSignedAt(),a.rulesApproverAccountId(),a.effectiveAt(),a.reviewDueAt(),a.version());
 }
 public void insertAcceptance(UUID tenant,InstallModels.CreateAcceptance a,UUID actor) {
  jdbc.update("""
   insert into cp.installation_acceptances(id,tenant_id,plant_ref,plant_hash,topology_manifest_ref,topology_hash,capability_envelope_hash,
    test_suite_version,test_report_ref,critical_tests_pending,critical_tests_failed,emergency_test_ref,rollback_plan_ref,revocation_checkpoint_ref,
    review_due_at,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
   """,a.acceptanceId(),tenant,a.plantRef(),a.plantHash(),a.topologyManifestRef(),a.topologyHash(),a.capabilityEnvelopeHash(),a.testSuiteVersion(),
   a.testReportRef(),a.criticalTestsPending(),a.criticalTestsFailed(),a.emergencyTestRef(),a.rollbackPlanRef(),a.revocationCheckpointRef(),
   a.reviewDueAt()==null?null:Timestamp.from(a.reviewDueAt()),actor);
 }
 public boolean updateResults(UUID tenant,UUID id,InstallModels.AcceptanceResults r) {
  return jdbc.update("""
   update cp.installation_acceptances set critical_tests_pending=?,critical_tests_failed=?,test_report_ref=?,revocation_checkpoint_ref=?,version=version+1
    where tenant_id=? and id=? and version=? and technical_signed_at is null
   """,r.criticalTestsPending(),r.criticalTestsFailed(),r.testReportRef(),r.revocationCheckpointRef(),tenant,id,r.expectedVersion())==1;
 }
 /** Concessão técnica vigente da própria conta com comissionamento (o banco confere de novo). */
 public UUID commissionGrant(UUID tenant,UUID account) {
  var rows=jdbc.queryForList("""
   select id from cp.technical_grants where tenant_id=? and account_id=? and status='ACTIVE' and valid_from<=now() and valid_until>now()
    and 'installation.commission'=any(scopes) order by valid_until desc limit 1
   """,UUID.class,tenant,account);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean signTechnical(UUID tenant,UUID id,UUID grant,UUID actor,long expected) {
  return jdbc.update("""
   update cp.installation_acceptances set technical_grant_id=?,technical_approver_account_id=?,technical_signed_at=clock_timestamp(),version=version+1
    where tenant_id=? and id=? and version=? and technical_signed_at is null
   """,grant,actor,tenant,id,expected)==1;
 }
 public boolean signRules(UUID tenant,UUID id,UUID membership,UUID actor,long expected) {
  return jdbc.update("""
   update cp.installation_acceptances set rules_approver_membership_id=?,rules_approver_account_id=?,rules_signed_at=clock_timestamp(),version=version+1
    where tenant_id=? and id=? and version=? and rules_signed_at is null
   """,membership,actor,tenant,id,expected)==1;
 }
 public boolean transition(UUID tenant,UUID id,String status,String reason,long expected) {
  return jdbc.update("""
   update cp.installation_acceptances set status=?,status_reason=coalesce(?,status_reason),version=version+1 where tenant_id=? and id=? and version=?
   """,status,reason,tenant,id,expected)==1;
 }
 public static boolean sameAcceptance(InstallModels.Acceptance a,InstallModels.CreateAcceptance c) {
  return a.plantRef().equals(c.plantRef()) && a.plantHash().equals(c.plantHash()) && a.topologyManifestRef().equals(c.topologyManifestRef())
   && a.topologyHash().equals(c.topologyHash()) && a.capabilityEnvelopeHash().equals(c.capabilityEnvelopeHash())
   && a.testSuiteVersion().equals(c.testSuiteVersion()) && a.testReportRef().equals(c.testReportRef())
   && a.criticalTestsPending()==c.criticalTestsPending() && a.criticalTestsFailed()==c.criticalTestsFailed()
   && a.emergencyTestRef().equals(c.emergencyTestRef()) && a.rollbackPlanRef().equals(c.rollbackPlanRef())
   && Objects.equals(a.revocationCheckpointRef(),c.revocationCheckpointRef()) && Objects.equals(a.reviewDueAt(),c.reviewDueAt());
 }
 private static Instant instant(Timestamp t) { return t==null?null:t.toInstant(); }
}
