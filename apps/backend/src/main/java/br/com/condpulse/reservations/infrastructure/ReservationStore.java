package br.com.condpulse.reservations.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.domain.ReservationPolicy;
import br.com.condpulse.reservations.domain.ReservationWindow;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Somente dentro da transação/contexto fornecidos pelo TenantTransactionExecutor. */
@Repository @Profile("api")
public class ReservationStore {
 /** Textos de sistema gravados em decision_reason; o motivo da gestão é sempre informado por pessoa. */
 public static final String EXPIRED_REASON="Prazo de decisão encerrado sem confirmação.";
 public static final String AUTHOR_CANCELLED_REASON="Cancelamento solicitado pelo autor.";
 private final JdbcTemplate jdbc;
 public ReservationStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Colunas e validação de regra compartilhadas com a gestão de áreas do mesmo módulo. */
 public static final String AMENITY_FIELDS="""
  select id,name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy::text,version,active,
   policy->>'schemaVersion' as rule_schema_version,policy->>'weekDays' as rule_week_days,
   policy->>'opensAt' as rule_opens_at,policy->>'closesAt' as rule_closes_at,
   policy->>'minimumLeadMinutes' as rule_minimum_lead,policy->>'maximumAdvanceDays' as rule_maximum_advance,
   policy->>'maximumDurationMinutes' as rule_maximum_duration,policy->>'maximumActivePerUnit' as rule_maximum_active,
   policy->>'cancellationLeadMinutes' as rule_cancellation_lead,
   (policy - array['schemaVersion','weekDays','opensAt','closesAt','minimumLeadMinutes','maximumAdvanceDays',
     'maximumDurationMinutes','maximumActivePerUnit','cancellationLeadMinutes'] = '{}'::jsonb
    and jsonb_typeof(policy->'schemaVersion')='number' and jsonb_typeof(policy->'weekDays')='array'
    and jsonb_typeof(policy->'opensAt')='string' and jsonb_typeof(policy->'closesAt')='string'
    and jsonb_typeof(policy->'minimumLeadMinutes')='number' and jsonb_typeof(policy->'maximumAdvanceDays')='number'
    and jsonb_typeof(policy->'maximumDurationMinutes')='number' and jsonb_typeof(policy->'maximumActivePerUnit')='number'
    and jsonb_typeof(policy->'cancellationLeadMinutes')='number') as rule_types_valid
  from cp.amenities
  """;
 private static final String AMENITY_COLUMNS=AMENITY_FIELDS+" where tenant_id=? and active";
 private static final String RESERVATION_COLUMNS="""
  select id as reservation_id,unit_id,amenity_id,
   case when status='PENDING_APPROVAL' and expires_at<=clock_timestamp() then 'EXPIRED' else status end as status,
   starts_at,ends_at,occupied_from,occupied_until,
   coalesce((rule_snapshot->>'policyVersion')::integer,0) as accepted_policy_version,fee_cents,version,
   case when status='PENDING_APPROVAL' and expires_at<=clock_timestamp() then 'Prazo de decisão encerrado sem confirmação.'
    else decision_reason end as decision_reason,
   rule_snapshot->'policy'->>'schemaVersion' as snapshot_schema,
   rule_snapshot->'policy'->>'cancellationLeadMinutes' as snapshot_cancel_lead,created_by
  from cp.reservations
  """;

 public record AmenityData(UUID id,String name,int capacity,boolean approvalRequired,long feeCents,
     int bufferMinutes,int policyVersion,String rawPolicy,long version,ReservationPolicy policy) {
  public ReservationModels.Amenity view() {
   String state=policy==null?"POLICY_NOT_CONFIGURED":approvalRequired?"AVAILABLE_FOR_APPROVAL_REQUEST":"AVAILABLE_FOR_REQUEST";
   return new ReservationModels.Amenity(id,name,capacity,policyVersion,feeCents,bufferMinutes,approvalRequired,
     state,policy==null?null:ReservationModels.Policy.from(policy));
  }
 }
 public record StoredReservation(ReservationModels.View view,Integer cancellationLeadMinutes,UUID createdBy) {}
 public record IdempotentReply(boolean fresh,ReservationModels.View reservation,int responseStatus) {}

 public List<AmenityData> amenities(UUID tenant) {
  return jdbc.query(AMENITY_COLUMNS+" order by name,id",(r,n)->amenity(r),tenant);
 }
 public AmenityData amenity(UUID tenant,UUID amenity) {
  var result=jdbc.query(AMENITY_COLUMNS+" and id=?",(r,n)->amenity(r),tenant,amenity);
  if(result.size()!=1) throw unavailable();
  return result.getFirst();
 }
 public static AmenityData amenity(ResultSet r) throws SQLException {
  return new AmenityData(r.getObject("id",UUID.class),r.getString("name"),r.getInt("capacity"),
   r.getBoolean("approval_required"),r.getLong("fee_cents"),r.getInt("buffer_minutes"),r.getInt("policy_version"),
   r.getString("policy"),r.getLong("version"),policy(r));
 }
 private static ReservationPolicy policy(ResultSet r) throws SQLException {
  if(!r.getBoolean("rule_types_valid")) return null;
  try {
   String days=r.getString("rule_week_days");
   if(days==null || !days.matches("\\[\\s*[1-7](\\s*,\\s*[1-7])*\\s*]")) return null;
   var weekDays=EnumSet.noneOf(DayOfWeek.class);
   for(String day:days.substring(1,days.length()-1).split(","))
    if(!weekDays.add(DayOfWeek.of(Integer.parseInt(day.trim())))) return null;
   return new ReservationPolicy(Integer.parseInt(r.getString("rule_schema_version")),weekDays,
    LocalTime.parse(r.getString("rule_opens_at")),LocalTime.parse(r.getString("rule_closes_at")),
    Integer.parseInt(r.getString("rule_minimum_lead")),Integer.parseInt(r.getString("rule_maximum_advance")),
    Integer.parseInt(r.getString("rule_maximum_duration")),Integer.parseInt(r.getString("rule_maximum_active")),
    Integer.parseInt(r.getString("rule_cancellation_lead")));
  } catch(IllegalArgumentException | java.time.DateTimeException | NullPointerException invalid) { return null; }
 }

 public Instant currentTime() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }
 public void lockUnit(UUID tenant,UUID unit) {
  // Não precisa de UPDATE em cp.units. Todos os comandos desta fatia usam esta mesma ordem de trava.
  jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,
    "cp.reservations:"+tenant+":"+unit);
 }
 public long activeCount(UUID tenant,UUID unit,Instant now) {
  Long count=jdbc.queryForObject("""
   select count(*) from cp.reservations where tenant_id=? and unit_id=? and kind='BOOKING'
    and status in ('HELD','PENDING_APPROVAL','CONFIRMED') and ends_at>?
    and not (status in ('HELD','PENDING_APPROVAL') and expires_at<=clock_timestamp())
   """,Long.class,tenant,unit,Timestamp.from(now));
  return count==null?0:count;
 }
 public ReservationModels.Page ownReservations(UUID tenant,UUID unit,Instant from,Instant until) {
  var rows=jdbc.query(RESERVATION_COLUMNS+"""
   where tenant_id=? and unit_id=? and kind='BOOKING' and starts_at<? and ends_at>?
   order by starts_at,id limit 101
   """,(r,n)->view(r),tenant,unit,Timestamp.from(until),Timestamp.from(from));
  return new ReservationModels.Page(rows.stream().limit(100).toList(),rows.size()>100);
 }

 /**
  * Ocupacao da exclusao GiST, inclusive BLOCK. Pedido de aprovacao com prazo vencido nao bloqueia: nao pode mais
  * ser confirmado e a criacao o expira sob trava antes de gravar. Retencao HELD de fila continua ocupando ate a
  * transicao real, porque a oferta ao proximo da fila ainda nao existe.
  */
 public List<ReservationModels.BlockedInterval> blockedIntervals(UUID tenant,UUID amenity,Instant from,Instant until) {
  var rows=jdbc.query("""
   select greatest(occupied_from,?) as occupied_from,least(occupied_until,?) as occupied_until
   from cp.reservations where tenant_id=? and amenity_id=?
    and status in ('HELD','PENDING_APPROVAL','CONFIRMED')
    and not (status='PENDING_APPROVAL' and expires_at<=clock_timestamp())
    and tstzrange(occupied_from,occupied_until,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)')
   order by occupied_from,occupied_until limit 1001
   """,(r,n)->new ReservationModels.BlockedInterval(r.getTimestamp("occupied_from").toInstant(),
    r.getTimestamp("occupied_until").toInstant()),Timestamp.from(from),Timestamp.from(until),tenant,amenity,
    Timestamp.from(from),Timestamp.from(until));
  // Nunca devolver disponibilidade parcial: uma pagina incompleta poderia sugerir horario livre.
  if(rows.size()>1000)
   throw new BusinessProblem("AMENITY_AVAILABILITY_UNAVAILABLE",503,"Reduza o periodo para consultar a ocupacao desta area.");
  var merged=new ArrayList<ReservationModels.BlockedInterval>();
  for(var next:rows) {
   if(merged.isEmpty() || merged.getLast().occupiedUntil().isBefore(next.occupiedFrom())) merged.add(next);
   else {
    var previous=merged.removeLast();
    merged.add(new ReservationModels.BlockedInterval(previous.occupiedFrom(),
     previous.occupiedUntil().isAfter(next.occupiedUntil())?previous.occupiedUntil():next.occupiedUntil()));
   }
  }
  return List.copyOf(merged);
 }

 public IdempotentReply begin(UUID tenant,UUID actor,String route,String key,String hash) {
  int inserted=jdbc.update("""
   insert into cp.idempotency_keys(tenant_id,actor_id,request_key,method,route,body_hash,expires_at)
   values(?,?,?,'POST',?,?,clock_timestamp()+interval '24 hours')
   on conflict(tenant_id,actor_id,method,route,request_key) do nothing
   """,tenant,actor,key,route,hash);
  if(inserted==1) return new IdempotentReply(true,null,0);
  var rows=jdbc.query("""
   select body_hash,response_status,expires_at<=clock_timestamp() as expired,
    (response_body->>'reservationId')::uuid as reservation_id,(response_body->>'unitId')::uuid as unit_id,
    (response_body->>'amenityId')::uuid as amenity_id,response_body->>'status' as status,
    (response_body->>'startsAt')::timestamptz as starts_at,(response_body->>'endsAt')::timestamptz as ends_at,
    (response_body->>'occupiedFrom')::timestamptz as occupied_from,
    (response_body->>'occupiedUntil')::timestamptz as occupied_until,
    (response_body->>'acceptedPolicyVersion')::integer as accepted_policy_version,
    (response_body->>'feeCents')::bigint as fee_cents,(response_body->>'version')::bigint as version,
    response_body->>'decisionReason' as decision_reason
   from cp.idempotency_keys where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? for update
   """,(r,n)->{
    if(r.getBoolean("expired")) throw new BusinessProblem("IDEMPOTENCY_KEY_EXPIRED",409,"Use uma nova chave para esta operação.");
    if(!hash.equals(r.getString("body_hash")))
     throw new BusinessProblem("IDEMPOTENCY_KEY_REUSED",409,"Esta chave já foi usada com outros dados.");
    Integer status=r.getObject("response_status",Integer.class);
    if(status==null) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
    return new IdempotentReply(false,view(r),status);
   },tenant,actor,route,key);
  if(rows.size()!=1) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
  return rows.getFirst();
 }

 public ReservationModels.View create(UUID tenant,UUID actor,ReservationModels.Create request,
     AmenityData amenity,ReservationWindow window,String timeZone) {
  UUID id=UUID.randomUUID();
  // Área com aprovação gera pedido PENDING_APPROVAL com prazo de decisão de até 24 h, nunca após o início.
  var statuses=jdbc.query("""
   insert into cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,
     occupied_from,occupied_until,expires_at,rule_snapshot,fee_cents)
   select ?,a.tenant_id,a.id,?,?,'BOOKING',case when a.approval_required then 'PENDING_APPROVAL' else 'CONFIRMED' end,?,?,?,?,
    case when a.approval_required then least(now()+interval '24 hours',?::timestamptz) end,
    jsonb_build_object('policyVersion',a.policy_version,'policy',a.policy,'bufferMinutes',a.buffer_minutes,
      'capacity',a.capacity,'approvalRequired',a.approval_required,'feeCents',a.fee_cents,'timeZone',?::text),a.fee_cents
   from cp.amenities a where a.tenant_id=? and a.id=? and a.active and a.approval_required=?
    and a.policy_version=? and a.version=? and a.policy=?::jsonb and a.buffer_minutes=? and a.fee_cents=?
   returning status
   """,(r,n)->r.getString("status"),id,request.unitId(),actor,Timestamp.from(window.startsAt()),Timestamp.from(window.endsAt()),
    Timestamp.from(window.occupiedFrom()),Timestamp.from(window.occupiedUntil()),Timestamp.from(window.startsAt()),timeZone,
    tenant,request.amenityId(),amenity.approvalRequired(),
    amenity.policyVersion(),amenity.version(),amenity.rawPolicy(),amenity.bufferMinutes(),amenity.feeCents());
  if(statuses.size()!=1) throw new BusinessProblem("AMENITY_POLICY_CHANGED",409,"As regras da área mudaram. Revise-as antes de tentar novamente.");
  return new ReservationModels.View(id,request.unitId(),request.amenityId(),statuses.getFirst(),window.startsAt(),window.endsAt(),
    window.occupiedFrom(),window.occupiedUntil(),amenity.policyVersion(),amenity.feeCents(),0,null);
 }
 /**
  * Expiração verificada sob a trava da unidade antes de avaliar cota e intervalo: pedidos de aprovação vencidos
  * da própria unidade ou que ocupam o período pedido deixam de bloquear. Gera evento sem auditoria de ator.
  * Retenções HELD ficam para a fila de espera, que decidirá a oferta ao próximo elegível.
  */
 public List<ReservationModels.View> expireStale(UUID tenant,UUID unit,UUID amenity,Instant occupiedFrom,Instant occupiedUntil) {
  // Travas em ordem de id evitam deadlock entre expirações concorrentes de unidades e bloqueios diferentes.
  var expired=jdbc.query("""
   with stale as (
    select id from cp.reservations
    where tenant_id=? and status='PENDING_APPROVAL' and expires_at<=clock_timestamp()
     and (unit_id=?::uuid or (amenity_id=? and tstzrange(occupied_from,occupied_until,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)')))
    order by id for update
   )
   update cp.reservations r set status='EXPIRED',updated_at=clock_timestamp(),version=r.version+1,decision_reason=?
   from stale where r.id=stale.id and r.tenant_id=? and r.status='PENDING_APPROVAL'
   returning r.id as reservation_id,r.unit_id,r.amenity_id,r.status,r.starts_at,r.ends_at,r.occupied_from,r.occupied_until,
    coalesce((r.rule_snapshot->>'policyVersion')::integer,0) as accepted_policy_version,r.fee_cents,r.version,r.decision_reason
   """,(r,n)->view(r),tenant,unit,amenity,Timestamp.from(occupiedFrom),Timestamp.from(occupiedUntil),EXPIRED_REASON,tenant);
  for(var item:expired) event(tenant,"RESERVATION_EXPIRED",item);
  return expired;
 }
 public void event(UUID tenant,String eventType,ReservationModels.View reservation) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('reservationId',?::uuid,'unitId',?::uuid,'amenityId',?::uuid,'status',?::text,'version',?::bigint))
   """,tenant,eventType,reservation.reservationId(),reservation.version(),reservation.reservationId(),reservation.unitId(),
    reservation.amenityId(),reservation.status(),reservation.version());
 }
 /**
  * Reserva da unidade travada para cancelamento residencial. Não filtra o autor: o serviço compara o autor e
  * responde de forma explícita, porque a reserva já é visível a quem tem vínculo com a unidade.
  */
 public StoredReservation unitReservationForUpdate(UUID tenant,UUID unit,UUID reservation) {
  var rows=jdbc.query(RESERVATION_COLUMNS+"""
   where tenant_id=? and unit_id=? and id=? and kind='BOOKING' for update
   """,(r,n)->{
    Integer lead=null;
    try {
     if("1".equals(r.getString("snapshot_schema"))) {
      int value=Integer.parseInt(r.getString("snapshot_cancel_lead"));
      if(value>=0 && value<=525600) lead=value;
     }
    } catch(NumberFormatException ignored) { /* Snapshot anterior/inválido exige revisão, não default permissivo. */ }
    return new StoredReservation(view(r),lead,r.getObject("created_by",UUID.class));
   },tenant,unit,reservation);
  if(rows.size()!=1) throw unavailable();
  return rows.getFirst();
 }
 public ReservationModels.View cancel(UUID tenant,UUID actor,ReservationModels.View current) {
  int changed=jdbc.update("""
   update cp.reservations set status='CANCELLED',updated_at=clock_timestamp(),version=version+1,decision_reason=?
   where tenant_id=? and id=? and unit_id=? and created_by=? and kind='BOOKING'
    and status in ('CONFIRMED','PENDING_APPROVAL') and version=?
   """,AUTHOR_CANCELLED_REASON,tenant,current.reservationId(),current.unitId(),actor,current.version());
  if(changed!=1) throw new BusinessProblem("RESERVATION_CHANGED",409,"A reserva foi alterada. Atualize os dados.");
  return new ReservationModels.View(current.reservationId(),current.unitId(),current.amenityId(),"CANCELLED",
    current.startsAt(),current.endsAt(),current.occupiedFrom(),current.occupiedUntil(),
    current.acceptedPolicyVersion(),current.feeCents(),current.version()+1,AUTHOR_CANCELLED_REASON);
 }
 public void record(UUID tenant,UUID actor,ReservationModels.View reservation,String eventType,UUID trace) {
  jdbc.update("""
   insert into cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id)
   values(?,?,?,'RESERVATION',?,?)
   """,tenant,actor,eventType,reservation.reservationId(),trace);
  event(tenant,eventType,reservation);
 }
 public void finish(UUID tenant,UUID actor,String route,String key,ReservationModels.View value,int status) {
  int changed=jdbc.update("""
   update cp.idempotency_keys set response_status=?,response_body=jsonb_build_object(
    'reservationId',?::uuid,'unitId',?::uuid,'amenityId',?::uuid,'status',?::text,'startsAt',?::text,'endsAt',?::text,
    'occupiedFrom',?::text,'occupiedUntil',?::text,'acceptedPolicyVersion',?::integer,'feeCents',?::bigint,'version',?::bigint,
    'decisionReason',?::text)
   where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? and response_status is null
   """,status,value.reservationId(),value.unitId(),value.amenityId(),value.status(),value.startsAt().toString(),
    value.endsAt().toString(),value.occupiedFrom().toString(),value.occupiedUntil().toString(),value.acceptedPolicyVersion(),
    value.feeCents(),value.version(),value.decisionReason(),tenant,actor,route,key);
  if(changed!=1) throw new IllegalStateException("Resultado de idempotência não persistido.");
 }
 /**
  * Colunas de RETURNING compatíveis com {@link #view(ResultSet)} para alterações de uma única reserva. Começa com
  * espaço porque text blocks removem o espaço final da linha "returning" que o antecede.
  */
 public static final String VIEW_RETURNING=" "+"""
  id as reservation_id,unit_id,amenity_id,status,starts_at,ends_at,occupied_from,occupied_until,
  coalesce((rule_snapshot->>'policyVersion')::integer,0) as accepted_policy_version,fee_cents,version,decision_reason
  """;
 public static ReservationModels.View view(ResultSet r) throws SQLException {
  return new ReservationModels.View(r.getObject("reservation_id",UUID.class),r.getObject("unit_id",UUID.class),
    r.getObject("amenity_id",UUID.class),r.getString("status"),r.getTimestamp("starts_at").toInstant(),
    r.getTimestamp("ends_at").toInstant(),r.getTimestamp("occupied_from").toInstant(),r.getTimestamp("occupied_until").toInstant(),
    r.getInt("accepted_policy_version"),r.getLong("fee_cents"),r.getLong("version"),r.getString("decision_reason"));
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("RESERVATION_RESOURCE_UNAVAILABLE",404,"Recurso não disponível para esta operação.");
 }
}
