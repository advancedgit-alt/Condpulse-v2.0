package br.com.condpulse.tenancy.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.tenancy.application.MembershipModels;
import br.com.condpulse.tenancy.application.RentalModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL de locação (V034). A gestão lê e grava com memberships.manage; o proprietário só alcança, pelas políticas do banco,
 * a própria locação, os ocupantes e os convites de inquilino das unidades que alugou.
 */
@Repository @Profile("api")
public class RentalStore {
 private final JdbcTemplate jdbc;
 public RentalStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Serializa início e fim de locação da mesma unidade. */
 public void lockUnit(UUID tenant,UUID unit) {
  jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"cp.unit_rentals:"+tenant+":"+unit);
 }
 public List<RentalModels.ManagedRental> active(UUID tenant) {
  record Row(UUID unit,String label,Instant since,String owner) {}
  var rows=jdbc.query("""
   select r.unit_id,u.label,min(r.started_at) over(partition by r.unit_id) as since,m.display_name
   from cp.unit_rentals r join cp.units u on u.tenant_id=r.tenant_id and u.id=r.unit_id
   join cp.memberships m on m.tenant_id=r.tenant_id and m.id=r.owner_membership_id
   where r.tenant_id=? and r.ended_at is null order by u.label,r.unit_id,m.display_name,m.id
   """,(r,n)->new Row(r.getObject("unit_id",UUID.class),r.getString("label"),instant(r,"since"),r.getString("display_name")),tenant);
  var byUnit=new LinkedHashMap<UUID,RentalModels.ManagedRental>();
  for(Row row:rows) {
   var current=byUnit.get(row.unit());
   var owners=new ArrayList<String>(current==null?List.of():current.owners());
   owners.add(row.owner());
   byUnit.put(row.unit(),new RentalModels.ManagedRental(row.unit(),row.label(),row.since(),owners));
  }
  return List.copyOf(byUnit.values());
 }
 public RentalModels.ManagedRental activeFor(UUID tenant,UUID unit) {
  return active(tenant).stream().filter(r->r.unitId().equals(unit)).findFirst().orElse(null);
 }
 /** Vínculos OWNER não revogados da unidade (gestão). */
 public List<UUID> ownerMemberships(UUID tenant,UUID unit) {
  return jdbc.query("select id from cp.memberships where tenant_id=? and unit_id=? and role='OWNER' and status<>'REVOKED' order by id",
   (r,n)->r.getObject("id",UUID.class),tenant,unit);
 }
 /** Proprietário da unidade que também tem vínculo de ocupante não revogado nela (gestão). */
 public boolean ownerIsOccupant(UUID tenant,UUID unit) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.memberships o join cp.memberships m on m.tenant_id=o.tenant_id and m.unit_id=o.unit_id
    and m.account_id=o.account_id and m.role in ('RESIDENT','RESPONSIBLE','DEPENDENT') and m.status<>'REVOKED'
    where o.tenant_id=? and o.unit_id=? and o.role='OWNER' and o.status<>'REVOKED')
   """,Boolean.class,tenant,unit));
 }
 public void start(UUID tenant,UUID unit,UUID ownerMembership,UUID actor) {
  // owner_account_id é sobrescrito pelo gatilho com a conta do vínculo.
  jdbc.update("""
   insert into cp.unit_rentals(tenant_id,unit_id,owner_membership_id,owner_account_id,started_by)
   select tenant_id,unit_id,id,account_id,? from cp.memberships where tenant_id=? and id=?
   """,actor,tenant,ownerMembership);
 }
 public int end(UUID tenant,UUID unit,UUID actor) {
  return jdbc.update("update cp.unit_rentals set ended_by=? where tenant_id=? and unit_id=? and ended_at is null",actor,tenant,unit);
 }

 /** Início da locação em vigor do ator nesta unidade, ou null. */
 public Instant ownerRentedSince(UUID tenant,UUID unit) {
  var rows=jdbc.query("""
   select min(started_at) as since from cp.unit_rentals
   where tenant_id=? and unit_id=? and owner_account_id=platform.current_actor_id() and ended_at is null having count(*)>0
   """,(r,n)->instant(r,"since"),tenant,unit);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public String unitLabel(UUID tenant,UUID unit) {
  var rows=jdbc.query("select label from cp.units where tenant_id=? and id=?",(r,n)->r.getString("label"),tenant,unit);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public List<RentalModels.Occupant> occupants(UUID tenant,UUID unit) {
  return jdbc.query("""
   select id,display_name,role,status,valid_from,version from cp.memberships
   where tenant_id=? and unit_id=? and role in ('RESIDENT','RESPONSIBLE','DEPENDENT') and status<>'REVOKED'
    and account_id<>platform.current_actor_id()
   order by status,display_name,id limit 200
   """,(r,n)->new RentalModels.Occupant(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("role"),
    r.getString("status"),instant(r,"valid_from"),r.getLong("version")),tenant,unit);
 }
 public RentalModels.Occupant occupant(UUID tenant,UUID unit,UUID membership) {
  var rows=jdbc.query("""
   select id,display_name,role,status,valid_from,version from cp.memberships
   where tenant_id=? and unit_id=? and id=? and role in ('RESIDENT','RESPONSIBLE','DEPENDENT') and account_id<>platform.current_actor_id()
   """,(r,n)->new RentalModels.Occupant(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("role"),
    r.getString("status"),instant(r,"valid_from"),r.getLong("version")),tenant,unit,membership);
  return rows.size()==1?rows.getFirst():null;
 }
 public List<MembershipModels.Invitation> invitations(UUID tenant,UUID unit) {
  return jdbc.query("""
   select i.id,i.unit_id,u.label as unit_label,i.role,i.display_name,
    case when i.status='PENDING' and i.expires_at<=now() then 'EXPIRED' else i.status end as status,
    i.expires_at,i.created_at,i.accepted_at
   from cp.membership_invitations i join cp.units u on u.tenant_id=i.tenant_id and u.id=i.unit_id
   where i.tenant_id=? and i.unit_id=? and i.role in ('RESIDENT','RESPONSIBLE')
   order by i.created_at desc,i.id limit 50
   """,(r,n)->new MembershipModels.Invitation(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),
    r.getString("unit_label"),r.getString("role"),r.getString("display_name"),r.getString("status"),
    instant(r,"expires_at"),instant(r,"created_at"),instant(r,"accepted_at")),tenant,unit);
 }
 public boolean revokeInvitation(UUID tenant,UUID unit,UUID invitation) {
  return jdbc.update("""
   update cp.membership_invitations set status='REVOKED',updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and unit_id=? and id=? and status='PENDING' and role in ('RESIDENT','RESPONSIBLE')
   """,tenant,unit,invitation)==1;
 }
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column); return value==null?null:value.toInstant();
 }
}
