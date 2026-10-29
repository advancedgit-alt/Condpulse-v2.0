package br.com.condpulse.tenancy.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.tenancy.application.MembershipModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Vínculos e convites. A gestão usa o contexto com memberships.manage; o aceite usa o hash do convite. */
@Repository @Profile("api")
public class MembershipStore {
 private static final int PAGE_LIMIT=1000;
 private static final String MEMBERSHIP_COLUMNS="""
  select m.id,m.unit_id,u.label as unit_label,m.display_name,m.role,m.status,m.valid_from,m.valid_until,m.version
  from cp.memberships m left join cp.units u on u.tenant_id=m.tenant_id and u.id=m.unit_id
  """;
 private final JdbcTemplate jdbc;
 public MembershipStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public MembershipModels.MembershipPage memberships(UUID tenant,String status) {
  var rows=status==null
   ?jdbc.query(MEMBERSHIP_COLUMNS+" where m.tenant_id=? order by m.status,u.label nulls first,m.display_name,m.id limit ?",
     (r,n)->membership(r),tenant,PAGE_LIMIT+1)
   :jdbc.query(MEMBERSHIP_COLUMNS+" where m.tenant_id=? and m.status=? order by u.label nulls first,m.display_name,m.id limit ?",
     (r,n)->membership(r),tenant,status,PAGE_LIMIT+1);
  return new MembershipModels.MembershipPage(rows.stream().limit(PAGE_LIMIT).toList(),rows.size()>PAGE_LIMIT);
 }
 public MembershipModels.Membership membership(UUID tenant,UUID id) {
  var rows=jdbc.query(MEMBERSHIP_COLUMNS+" where m.tenant_id=? and m.id=?",(r,n)->membership(r),tenant,id);
  return rows.size()==1?rows.getFirst():null;
 }
 public UUID accountOf(UUID tenant,UUID membership) {
  var rows=jdbc.query("select account_id from cp.memberships where tenant_id=? and id=?",
   (r,n)->r.getObject("account_id",UUID.class),tenant,membership);
  return rows.size()==1?rows.getFirst():null;
 }
 public boolean transition(UUID tenant,UUID membership,long expectedVersion,String from,String to) {
  return jdbc.update("""
   update cp.memberships set status=?,updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and version=? and status=?
   """,to,tenant,membership,expectedVersion,from)==1;
 }
 public boolean revoke(UUID tenant,UUID membership,long expectedVersion) {
  return jdbc.update("""
   update cp.memberships set status='REVOKED',updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and version=? and status in ('PENDING','ACTIVE','SUSPENDED')
   """,tenant,membership,expectedVersion)==1;
 }
 public long otherActiveManagers(UUID tenant,UUID membership) {
  Long count=jdbc.queryForObject("""
   select count(*) from cp.memberships where tenant_id=? and id<>? and role='MANAGER' and status='ACTIVE'
    and valid_from<=now() and(valid_until is null or valid_until>now())
   """,Long.class,tenant,membership);
  return count==null?0:count;
 }
 public void event(UUID tenant,String type,UUID aggregate,long version) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('membershipId',?::uuid,'version',?::bigint))
   """,tenant,type,aggregate,version,aggregate,version);
 }

 public boolean activeUnit(UUID tenant,UUID unit) {
  Integer count=jdbc.queryForObject("select count(*) from cp.units where tenant_id=? and id=? and status='ACTIVE'",
   Integer.class,tenant,unit);
  return count!=null && count==1;
 }
 public void insertInvitation(UUID id,UUID tenant,UUID unit,String role,String displayName,String emailHash,
     String tokenHash,Instant expiresAt,UUID createdBy) {
  jdbc.update("""
   insert into cp.membership_invitations(id,tenant_id,unit_id,role,display_name,email_hash,token_hash,expires_at,created_by)
   values(?,?,?,?,?,?,?,?,?)
   """,id,tenant,unit,role,displayName,emailHash,tokenHash,Timestamp.from(expiresAt),createdBy);
 }
 public List<MembershipModels.Invitation> invitations(UUID tenant) {
  return jdbc.query("""
   select i.id,i.unit_id,u.label as unit_label,i.role,i.display_name,
    case when i.status='PENDING' and i.expires_at<=now() then 'EXPIRED' else i.status end as status,
    i.expires_at,i.created_at,i.accepted_at
   from cp.membership_invitations i left join cp.units u on u.tenant_id=i.tenant_id and u.id=i.unit_id
   where i.tenant_id=? order by i.created_at desc,i.id limit 500
   """,(r,n)->new MembershipModels.Invitation(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),
    r.getString("unit_label"),r.getString("role"),r.getString("display_name"),r.getString("status"),
    instant(r,"expires_at"),instant(r,"created_at"),instant(r,"accepted_at")),tenant);
 }
 public boolean revokeInvitation(UUID tenant,UUID invitation) {
  return jdbc.update("""
   update cp.membership_invitations set status='REVOKED',updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and status='PENDING'
   """,tenant,invitation)==1;
 }

 /** Convite apresentado, travado para o aceite. Requer app.invitation_hash no contexto local. */
 public PresentedInvitation lockPresented(String tokenHash) {
  var rows=jdbc.query("""
   select id,tenant_id,unit_id,role,display_name,email_hash from cp.membership_invitations
   where token_hash=? and status='PENDING' and expires_at>now() for update
   """,(r,n)->new PresentedInvitation(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),
    r.getObject("unit_id",UUID.class),r.getString("role"),r.getString("display_name"),r.getString("email_hash")),tokenHash);
  return rows.size()==1?rows.getFirst():null;
 }
 public void insertPendingMembership(UUID id,PresentedInvitation invitation,UUID account) {
  jdbc.update("""
   insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status,valid_from)
   values(?,?,?,?,?,?,'PENDING',clock_timestamp())
   """,id,invitation.tenantId(),account,invitation.unitId(),invitation.displayName(),invitation.role());
 }
 public boolean completeInvitation(UUID invitation,UUID account,UUID membership) {
  return jdbc.update("""
   update cp.membership_invitations set status='ACCEPTED',accepted_by=?,accepted_at=clock_timestamp(),membership_id=?,
    updated_at=clock_timestamp(),version=version+1
   where id=? and status='PENDING'
   """,account,membership,invitation)==1;
 }
 public String accountStatus(UUID account) {
  var rows=jdbc.query("select status from platform.accounts where id=?",(r,n)->r.getString("status"),account);
  return rows.size()==1?rows.getFirst():null;
 }
 public void insertAccount(UUID account) {
  jdbc.update("insert into platform.accounts(id,status) values(?,'ACTIVE')",account);
 }

 public record PresentedInvitation(UUID id,UUID tenantId,UUID unitId,String role,String displayName,String emailHash) {}
 private static MembershipModels.Membership membership(ResultSet r) throws SQLException {
  return new MembershipModels.Membership(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),
   r.getString("unit_label"),r.getString("display_name"),r.getString("role"),r.getString("status"),
   instant(r,"valid_from"),instant(r,"valid_until"),r.getLong("version"));
 }
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column); return value==null?null:value.toInstant();
 }
}
