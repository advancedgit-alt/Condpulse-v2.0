package br.com.condpulse.partners.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.partners.application.PartnerModels;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tabelas de parceiro (V049). Toda leitura passa pelo RLS da transação (plataforma, gestor do parceiro do contexto ou
 * próprio papel); gravação só pelas colunas concedidas e com a trilha exigida no COMMIT.
 */
public class PartnerStore {
 private final JdbcTemplate jdbc;
 public PartnerStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column); return value==null?null:value.toInstant();
 }
 private static PartnerModels.Partner partner(ResultSet r,int n) throws SQLException {
  return new PartnerModels.Partner(r.getObject("id",UUID.class),r.getString("name"),r.getString("status"),r.getLong("version"),
   instant(r,"created_at"),instant(r,"updated_at"));
 }
 private static PartnerModels.Member member(ResultSet r,int n) throws SQLException {
  return new PartnerModels.Member(r.getObject("id",UUID.class),r.getObject("partner_id",UUID.class),r.getObject("account_id",UUID.class),
   r.getString("role"),r.getString("display_name"),r.getString("status"),r.getLong("version"),instant(r,"created_at"),
   instant(r,"revoked_at"),r.getString("revoke_reason"));
 }
 private static PartnerModels.Contract contract(ResultSet r,int n) throws SQLException {
  return new PartnerModels.Contract(r.getObject("id",UUID.class),r.getObject("partner_id",UUID.class),r.getObject("tenant_id",UUID.class),
   r.getString("tenant_name"),r.getString("service"),r.getString("status"),r.getLong("version"),instant(r,"starts_at"),
   instant(r,"ends_at"),instant(r,"ended_at"),r.getString("end_reason"));
 }
 private static PartnerModels.Schedule schedule(ResultSet r,int n) throws SQLException {
  return new PartnerModels.Schedule(r.getObject("id",UUID.class),r.getObject("partner_id",UUID.class),r.getObject("tenant_id",UUID.class),
   r.getObject("member_id",UUID.class),r.getObject("account_id",UUID.class),r.getString("status"),r.getLong("version"),
   instant(r,"starts_at"),instant(r,"ends_at"),instant(r,"cancelled_at"));
 }
 private static final String PARTNER="select id,name,status,version,created_at,updated_at from platform.partners";
 private static final String MEMBER="select id,partner_id,account_id,role,display_name,status,version,created_at,revoked_at,revoke_reason "
  +"from platform.partner_members";
 private static final String CONTRACT="select c.id,c.partner_id,c.tenant_id,t.name as tenant_name,c.service,c.status,c.version,"
  +"c.starts_at,c.ends_at,c.ended_at,c.end_reason from platform.partner_tenant_contracts c left join platform.tenants t on t.id=c.tenant_id";
 private static final String SCHEDULE="select id,partner_id,tenant_id,member_id,account_id,status,version,starts_at,ends_at,cancelled_at "
  +"from cp.operator_schedules";

 public List<PartnerModels.Partner> partners() { return jdbc.query(PARTNER+" order by lower(name),id limit 500",PartnerStore::partner); }
 public PartnerModels.Partner partner(UUID id) {
  var rows=jdbc.query(PARTNER+" where id=?",PartnerStore::partner,id); return rows.isEmpty()?null:rows.getFirst();
 }
 public PartnerModels.Partner lockPartner(UUID id) {
  var rows=jdbc.query(PARTNER+" where id=? for update",PartnerStore::partner,id); return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertPartner(UUID id,String name,UUID actor) {
  jdbc.update("insert into platform.partners(id,name,created_by,updated_by) values(?,?,?,?)",id,name,actor,actor);
 }
 public boolean updatePartnerStatus(UUID id,String status,long expectedVersion,UUID actor) {
  return jdbc.update("update platform.partners set status=?,version=version+1,updated_by=? where id=? and version=?",
   status,actor,id,expectedVersion)==1;
 }
 public List<PartnerModels.Member> members(UUID partner) {
  return jdbc.query(MEMBER+" where partner_id=? order by status,role,display_name,id",PartnerStore::member,partner);
 }
 public PartnerModels.Member lockMember(UUID partner,UUID id) {
  var rows=jdbc.query(MEMBER+" where partner_id=? and id=? for update",PartnerStore::member,partner,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public PartnerModels.Member member(UUID partner,UUID id) {
  var rows=jdbc.query(MEMBER+" where partner_id=? and id=?",PartnerStore::member,partner,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertMember(UUID id,UUID partner,UUID account,String role,String displayName,UUID actor) {
  jdbc.update("insert into platform.partner_members(id,partner_id,account_id,role,display_name,created_by) values(?,?,?,?,?,?)",
   id,partner,account,role,displayName,actor);
 }
 public boolean revokeMember(UUID partner,UUID id,long expectedVersion,String reason,UUID actor) {
  return jdbc.update("update platform.partner_members set status='REVOKED',revoked_by=?,revoke_reason=?,version=version+1 "
   +"where partner_id=? and id=? and status='ACTIVE' and version=?",actor,reason,partner,id,expectedVersion)==1;
 }
 public List<PartnerModels.Contract> contracts(UUID partner) {
  return jdbc.query(CONTRACT+" where c.partner_id=? order by c.status,c.starts_at desc,c.id",PartnerStore::contract,partner);
 }
 public PartnerModels.Contract lockContract(UUID partner,UUID id) {
  var rows=jdbc.query(CONTRACT+" where c.partner_id=? and c.id=? for update of c",PartnerStore::contract,partner,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public PartnerModels.Contract contract(UUID partner,UUID id) {
  var rows=jdbc.query(CONTRACT+" where c.partner_id=? and c.id=?",PartnerStore::contract,partner,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertContract(UUID id,UUID partner,UUID tenant,Instant startsAt,Instant endsAt,String reason,UUID actor) {
  jdbc.update("insert into platform.partner_tenant_contracts(id,partner_id,tenant_id,service,starts_at,ends_at,reason,created_by) "
   +"values(?,?,?,'SECURITY_OPERATOR',coalesce(?,clock_timestamp()),?,?,?)",id,partner,tenant,
   startsAt==null?null:Timestamp.from(startsAt),endsAt==null?null:Timestamp.from(endsAt),reason,actor);
 }
 public boolean endContract(UUID partner,UUID id,long expectedVersion,String reason,UUID actor) {
  return jdbc.update("update platform.partner_tenant_contracts set status='ENDED',ended_by=?,end_reason=?,version=version+1 "
   +"where partner_id=? and id=? and status='ACTIVE' and version=?",actor,reason,partner,id,expectedVersion)==1;
 }
 public List<PartnerModels.Schedule> schedules(UUID partner,UUID tenant) {
  return tenant==null
   ?jdbc.query(SCHEDULE+" where partner_id=? order by starts_at desc,id limit 500",PartnerStore::schedule,partner)
   :jdbc.query(SCHEDULE+" where partner_id=? and tenant_id=? order by starts_at desc,id limit 500",PartnerStore::schedule,partner,tenant);
 }
 public PartnerModels.Schedule lockSchedule(UUID partner,UUID id) {
  var rows=jdbc.query(SCHEDULE+" where partner_id=? and id=? for update",PartnerStore::schedule,partner,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public PartnerModels.Schedule schedule(UUID partner,UUID id) {
  var rows=jdbc.query(SCHEDULE+" where partner_id=? and id=?",PartnerStore::schedule,partner,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertSchedule(UUID id,UUID tenant,UUID partner,UUID member,UUID account,Instant startsAt,Instant endsAt,String reason,UUID actor) {
  jdbc.update("insert into cp.operator_schedules(id,tenant_id,partner_id,member_id,account_id,starts_at,ends_at,reason,created_by) "
   +"values(?,?,?,?,?,coalesce(?,clock_timestamp()),?,?,?)",id,tenant,partner,member,account,
   startsAt==null?null:Timestamp.from(startsAt),Timestamp.from(endsAt),reason,actor);
 }
 public boolean cancelSchedule(UUID partner,UUID id,long expectedVersion,String reason,UUID actor) {
  return jdbc.update("update cp.operator_schedules set status='CANCELLED',cancelled_by=?,cancel_reason=?,version=version+1 "
   +"where partner_id=? and id=? and status='ACTIVE' and version=?",actor,reason,partner,id,expectedVersion)==1;
 }

 // ---- Troca de contexto (R4.4, V052) ----
 /**
  * Condomínios ativos que o papel da conta no parceiro alcança agora (platform.partner_reach, V049), em ordem estável de
  * id: a gravação no log trava as cabeças nessa ordem, sem ciclo de impasse entre trocas concorrentes.
  */
 public List<UUID> reach(UUID partner,String role) {
  return jdbc.queryForList("""
   select distinct r.tenant_id from platform.partner_reach() r join platform.tenants t on t.id=r.tenant_id and t.active
   where r.partner_id=? and r.role=? order by r.tenant_id
   """,UUID.class,partner,role);
 }
 /**
  * CONTEXT_SWITCHED no log de cada condomínio, um por vez com o condomínio no contexto da transação (política de
  * gravação da V050); o banco confere de novo o alcance do lado declarado (V052). O contexto volta vazio no fim.
  */
 public void recordContextSwitch(List<ContextSwitchRow> rows,String sessionId) {
  // Só gravação no log: nenhuma capacidade do parceiro fica publicada com condomínio no contexto.
  jdbc.queryForObject("select set_config('app.capabilities','',true)",String.class);
  for(var row:rows) {
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,row.tenantId().toString());
   jdbc.update("""
    insert into cp.security_log(id,tenant_id,event_type,severity,source,session_id,subject_type,subject_id,details)
    values(?,?,'CONTEXT_SWITCHED','INFO',?,?,'PARTNER',?,?::jsonb)
    """,row.id(),row.tenantId(),row.source(),sessionId,row.subjectId(),row.details());
  }
  // Falha acima desfaz a transação inteira (e o contexto local com ela); no sucesso o contexto volta vazio.
  jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
 }
 /** Evento de troca de contexto pronto para gravar: details já em JSON canônico e sem nulos. */
 public record ContextSwitchRow(UUID id,UUID tenantId,String source,UUID subjectId,String details) {}
}
