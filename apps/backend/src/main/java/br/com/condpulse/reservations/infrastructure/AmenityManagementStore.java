package br.com.condpulse.reservations.infrastructure;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.reservations.application.AmenityManagementModels;
import br.com.condpulse.reservations.application.ReservationModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Somente dentro da transação do TenantTransactionExecutor, com capacidade amenities.manage publicada. */
@Repository @Profile("api")
public class AmenityManagementStore {
 private final JdbcTemplate jdbc;
 public AmenityManagementStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public List<AmenityManagementModels.ManagedAmenity> amenities(UUID tenant) {
  return jdbc.query(ReservationStore.AMENITY_FIELDS+" where tenant_id=? order by active desc,name,id limit 500",
   (r,n)->view(ReservationStore.amenity(r),r.getBoolean("active")),tenant);
 }
 public AmenityManagementModels.ManagedAmenity find(UUID tenant,UUID amenity) {
  var rows=jdbc.query(ReservationStore.AMENITY_FIELDS+" where tenant_id=? and id=?",
   (r,n)->view(ReservationStore.amenity(r),r.getBoolean("active")),tenant,amenity);
  return rows.size()==1?rows.getFirst():null;
 }
 public UUID insert(UUID tenant,AmenityManagementModels.ValidAmenity value) {
  UUID id=UUID.randomUUID();
  jdbc.update("""
   insert into cp.amenities(id,tenant_id,name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy,active)
   values(?,?,?,?,?,?,?,1,?::jsonb,?)
   """,id,tenant,value.name(),value.capacity(),value.approvalRequired(),value.feeCents(),value.bufferMinutes(),
   value.policyJson(),value.active());
  return id;
 }
 /**
  * Uma regra nova recebe nova versão quando muda algo que a reserva retrata. As reservas já gravadas
  * mantêm o próprio snapshot; nome e situação ativa não alteram a versão da regra.
  */
 public boolean update(UUID tenant,UUID amenity,long expectedVersion,AmenityManagementModels.ValidAmenity value) {
  String policy=value.policyJson();
  return jdbc.update("""
   update cp.amenities set name=?,capacity=?,approval_required=?,fee_cents=?,buffer_minutes=?,policy=?::jsonb,active=?,
    policy_version=policy_version+case when capacity<>? or approval_required<>? or fee_cents<>? or buffer_minutes<>?
     or policy<>?::jsonb then 1 else 0 end,
    updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and version=?
   """,value.name(),value.capacity(),value.approvalRequired(),value.feeCents(),value.bufferMinutes(),policy,value.active(),
   value.capacity(),value.approvalRequired(),value.feeCents(),value.bufferMinutes(),policy,
   tenant,amenity,expectedVersion)==1;
 }
 private static AmenityManagementModels.ManagedAmenity view(ReservationStore.AmenityData data,boolean active) {
  return new AmenityManagementModels.ManagedAmenity(data.id(),data.name(),data.capacity(),data.approvalRequired(),
   data.feeCents(),data.bufferMinutes(),active,data.policyVersion(),data.version(),
   data.policy()==null?"POLICY_NOT_CONFIGURED":"CONFIGURED",
   data.policy()==null?null:ReservationModels.Policy.from(data.policy()));
 }
}
