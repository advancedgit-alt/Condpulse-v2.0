package br.com.condpulse.parcels.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.parcels.application.ParcelModels;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL das encomendas (V012), sempre dentro do TenantTransactionExecutor: a visibilidade (portaria por capacidade,
 * unidade por vínculo residencial) e a entrega única também são impostas pelo banco.
 */
@Repository @Profile("api")
public class ParcelStore {
 public static final int PAGE=100;
 private final JdbcTemplate jdbc;
 public ParcelStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private static final String SELECT="""
  select p.id,p.unit_id,u.label as unit_label,b.name as block_name,p.quantity,p.description,p.storage_location,p.status,
    p.received_at,p.version,h.recipient_name,h.created_at as delivered_at,h.evidence_kind,p.void_reason,p.voided_at
  from cp.parcels p
  join cp.units u on u.tenant_id=p.tenant_id and u.id=p.unit_id
  left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
  left join cp.parcel_handoffs h on h.tenant_id=p.tenant_id and h.parcel_id=p.id
  """;
 private static final RowMapper<ParcelModels.Parcel> PARCEL=(r,n)->parcel(r);
 private static ParcelModels.Parcel parcel(ResultSet r) throws SQLException {
  Timestamp delivered=r.getTimestamp("delivered_at");
  var handoff=delivered==null?null:new ParcelModels.Handoff(r.getString("recipient_name"),delivered.toInstant(),r.getString("evidence_kind"));
  Timestamp voided=r.getTimestamp("voided_at");
  return new ParcelModels.Parcel(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("unit_label"),
   r.getString("block_name"),r.getInt("quantity"),r.getString("description"),r.getString("storage_location"),r.getString("status"),
   r.getTimestamp("received_at").toInstant(),r.getLong("version"),handoff,r.getString("void_reason"),voided==null?null:voided.toInstant());
 }

 /**
  * Lista da portaria: filtros fixos, valores sempre por parâmetro; trecho do local sem curingas. Pendentes da mais
  * antiga para a mais nova (nenhuma some da fila por excesso de novas); demais situações da mais recente.
  */
 public ParcelModels.Page list(UUID tenant,String status,UUID unit,String location) {
  var where=new ArrayList<String>(); var args=new ArrayList<Object>();
  where.add("p.tenant_id=?"); args.add(tenant);
  if(status!=null) { where.add("p.status=?"); args.add(status); }
  if(unit!=null) { where.add("p.unit_id=?"); args.add(unit); }
  if(location!=null) { where.add("p.storage_location ilike ? escape '\\'"); args.add("%"+literal(location)+"%"); }
  args.add(PAGE+1);
  var rows=jdbc.query(SELECT+" where "+String.join(" and ",where)+order(status)+" limit ?",PARCEL,args.toArray());
  return new ParcelModels.Page(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 /** Encomendas da unidade (o banco esconde as anuladas de quem não é da portaria). */
 public ParcelModels.Page unitParcels(UUID tenant,UUID unit,String status) {
  var rows=status==null
   ?jdbc.query(SELECT+" where p.tenant_id=? and p.unit_id=?"+order(null)+" limit ?",PARCEL,tenant,unit,PAGE+1)
   :jdbc.query(SELECT+" where p.tenant_id=? and p.unit_id=? and p.status=?"+order(status)+" limit ?",PARCEL,tenant,unit,status,PAGE+1);
  return new ParcelModels.Page(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 private static String order(String status) {
  return "RECEIVED".equals(status)?" order by p.received_at,p.id":" order by p.received_at desc,p.id desc";
 }
 public ParcelModels.Parcel parcel(UUID tenant,UUID parcel,boolean lock) {
  var rows=jdbc.query(SELECT+" where p.tenant_id=? and p.id=?"+(lock?" for update of p":""),PARCEL,tenant,parcel);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Unidade ativa do condomínio, para o recebimento pela portaria. */
 public boolean activeUnit(UUID tenant,UUID unit) {
  Integer count=jdbc.queryForObject("select count(*) from cp.units where tenant_id=? and id=? and status='ACTIVE'",Integer.class,tenant,unit);
  return count!=null && count==1;
 }
 public List<ParcelModels.UnitOption> units(UUID tenant,String search) {
  return search==null
   ?jdbc.query("""
     select u.id,u.label,b.name as block_name from cp.units u left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
     where u.tenant_id=? and u.status='ACTIVE' order by b.name nulls first,u.label,u.id limit 500
     """,(r,n)->new ParcelModels.UnitOption(r.getObject("id",UUID.class),r.getString("label"),r.getString("block_name")),tenant)
   :jdbc.query("""
     select u.id,u.label,b.name as block_name from cp.units u left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
     where u.tenant_id=? and u.status='ACTIVE' and (u.label ilike ? escape '\\' or b.name ilike ? escape '\\')
     order by b.name nulls first,u.label,u.id limit 500
     """,(r,n)->new ParcelModels.UnitOption(r.getObject("id",UUID.class),r.getString("label"),r.getString("block_name")),
     tenant,"%"+literal(search)+"%","%"+literal(search)+"%");
 }
 public void insert(UUID id,UUID tenant,UUID unit,int quantity,String description,String location,UUID actor) {
  jdbc.update("""
   insert into cp.parcels(id,tenant_id,unit_id,quantity,description,storage_location,received_by) values(?,?,?,?,?,?,?)
   """,id,tenant,unit,quantity,description,location,actor);
 }
 /** Entrega única: muda o estado pela versão lida e grava o registro de retirada na mesma transação. */
 public boolean deliver(UUID tenant,UUID parcel,long expectedVersion,UUID actor,ParcelModels.Recipient recipient) {
  int changed=jdbc.update("update cp.parcels set status='DELIVERED',version=version+1 where tenant_id=? and id=? and status='RECEIVED' and version=?",
   tenant,parcel,expectedVersion);
  if(changed!=1) return false;
  jdbc.update("""
   insert into cp.parcel_handoffs(tenant_id,parcel_id,operator_id,recipient_name,recipient_membership_id,evidence_kind)
   values(?,?,?,?,?,'ASSISTED')
   """,tenant,parcel,actor,recipient.displayName(),recipient.membershipId());
  return true;
 }
 /** Registro indevido: anula pela versão lida, com motivo e responsável; o horário vem do banco. */
 public boolean voidParcel(UUID tenant,UUID parcel,long expectedVersion,UUID actor,String reason) {
  return jdbc.update("""
   update cp.parcels set status='VOIDED',voided_by=?,void_reason=?,version=version+1
   where tenant_id=? and id=? and status='RECEIVED' and version=?
   """,actor,reason,tenant,parcel,expectedVersion)==1;
 }
 private static final String RECIPIENTS="""
  select m.id,m.display_name,m.role
  from cp.parcels p
  join cp.units u on u.tenant_id=p.tenant_id and u.id=p.unit_id and u.status='ACTIVE'
  join cp.memberships m on m.tenant_id=p.tenant_id and m.unit_id=p.unit_id
  join platform.accounts a on a.id=m.account_id and a.status='ACTIVE'
  where p.tenant_id=? and p.id=? and m.status='ACTIVE' and cp.parcel_pickup_role(m.role,m.permissions)
   and m.valid_from<=now() and (m.valid_until is null or m.valid_until>now())
  """;
 private static final RowMapper<ParcelModels.Recipient> RECIPIENT=(r,n)->new ParcelModels.Recipient(r.getObject("id",UUID.class),
  r.getString("display_name"),r.getString("role"));
 public static final int RECIPIENTS_PAGE=100;
 /** Quem pode retirar a encomenda: vínculos residenciais vigentes, de contas ativas, da unidade ativa. */
 public List<ParcelModels.Recipient> recipients(UUID tenant,UUID parcel) {
  return jdbc.query(RECIPIENTS+" order by lower(m.display_name),m.id limit ?",RECIPIENT,tenant,parcel,RECIPIENTS_PAGE);
 }
 public ParcelModels.Recipient recipient(UUID tenant,UUID parcel,UUID membership) {
  var rows=jdbc.query(RECIPIENTS+" and m.id=?",RECIPIENT,tenant,parcel,membership);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Mesma unidade, volumes, descrição e local ainda a retirar, registrada nos últimos 10 minutos (duplicidade suspeita). */
 public boolean similarRecent(UUID tenant,UUID unit,int quantity,String description,String location) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.parcels where tenant_id=? and unit_id=? and status='RECEIVED' and quantity=?
    and lower(description)=lower(?) and lower(storage_location)=lower(?) and received_at>clock_timestamp()-interval '10 minutes')
   """,Boolean.class,tenant,unit,quantity,description,location));
 }
 /** Intenção de aviso na mesma transação: falha de notificação nunca apaga a encomenda (worker entrega depois). */
 public void event(UUID tenant,String eventType,ParcelModels.Parcel parcel) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('parcelId',?::uuid,'unitId',?::uuid,'quantity',?::integer,'status',?::text,'version',?::bigint))
   """,tenant,eventType,parcel.parcelId(),parcel.version(),parcel.parcelId(),parcel.unitId(),parcel.quantity(),parcel.status(),parcel.version());
 }

 public record IdempotentReply(boolean fresh,ParcelModels.Parcel parcel,int responseStatus) {}
 public IdempotentReply begin(UUID tenant,UUID actor,String route,String key,String hash) {
  int inserted=jdbc.update("""
   insert into cp.idempotency_keys(tenant_id,actor_id,request_key,method,route,body_hash,expires_at)
   values(?,?,?,'POST',?,?,clock_timestamp()+interval '24 hours')
   on conflict(tenant_id,actor_id,method,route,request_key) do nothing
   """,tenant,actor,key,route,hash);
  if(inserted==1) return new IdempotentReply(true,null,0);
  var rows=jdbc.query("""
   select body_hash,response_status,expires_at<=clock_timestamp() as expired,
    (response_body->>'parcelId')::uuid as id,(response_body->>'unitId')::uuid as unit_id,response_body->>'unitLabel' as unit_label,
    response_body->>'blockName' as block_name,(response_body->>'quantity')::integer as quantity,response_body->>'description' as description,
    response_body->>'storageLocation' as storage_location,response_body->>'status' as status,
    (response_body->>'receivedAt')::timestamptz as received_at,(response_body->>'version')::bigint as version,
    response_body->>'recipientName' as recipient_name,(response_body->>'deliveredAt')::timestamptz as delivered_at,
    response_body->>'evidenceKind' as evidence_kind,response_body->>'voidReason' as void_reason,
    (response_body->>'voidedAt')::timestamptz as voided_at
   from cp.idempotency_keys where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? for update
   """,(r,n)->{
    if(r.getBoolean("expired")) throw new BusinessProblem("IDEMPOTENCY_KEY_EXPIRED",409,"Use uma nova chave para esta operação.");
    if(!hash.equals(r.getString("body_hash")))
     throw new BusinessProblem("IDEMPOTENCY_KEY_REUSED",409,"Esta chave já foi usada com outros dados.");
    Integer status=r.getObject("response_status",Integer.class);
    if(status==null) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
    return new IdempotentReply(false,parcel(r),status);
   },tenant,actor,route,key);
  if(rows.size()!=1) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
  return rows.getFirst();
 }
 public void finish(UUID tenant,UUID actor,String route,String key,ParcelModels.Parcel value,int status) {
  var handoff=value.handoff();
  int changed=jdbc.update("""
   update cp.idempotency_keys set response_status=?,response_body=jsonb_build_object(
    'parcelId',?::uuid,'unitId',?::uuid,'unitLabel',?::text,'blockName',?::text,'quantity',?::integer,'description',?::text,
    'storageLocation',?::text,'status',?::text,'receivedAt',?::text,'version',?::bigint,'recipientName',?::text,
    'deliveredAt',?::text,'evidenceKind',?::text,'voidReason',?::text,'voidedAt',?::text)
   where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? and response_status is null
   """,status,value.parcelId(),value.unitId(),value.unitLabel(),value.blockName(),value.quantity(),value.description(),
   value.storageLocation(),value.status(),value.receivedAt().toString(),value.version(),handoff==null?null:handoff.recipientName(),
   handoff==null?null:handoff.deliveredAt().toString(),handoff==null?null:handoff.evidenceKind(),value.voidReason(),
   value.voidedAt()==null?null:value.voidedAt().toString(),tenant,actor,route,key);
  if(changed!=1) throw new IllegalStateException("Resultado de idempotência não persistido.");
 }
 public Instant now() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }
 private static String literal(String value) {
  return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
 }
}
