package br.com.condpulse.accessgrants.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessgrants.application.PublicationModels;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL da publicação por destino (V047). O banco compila documento, ação, prioridade e versão; aqui só se escolhem os
 * destinos e se grava a intenção. A varredura de destinos (vínculos) e a de convites ficam ligadas só durante a compilação.
 */
public class PublicationStore {
 private final JdbcTemplate jdbc;
 public PublicationStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private <T> T scan(Supplier<T> action) {
  setting("app.policy_destination_scan","on"); setting("app.subject_context_scan","on");
  // Só desliga no sucesso: com erro a transação está abortada (nova instrução daria 25P02 e mascararia a recusa); o
  // rollback ao savepoint do item (ou da transação) já devolve as configurações locais ao valor anterior.
  T result=action.get();
  setting("app.policy_destination_scan",""); setting("app.subject_context_scan","");
  return result;
 }
 private void setting(String name,String value) {
  jdbc.queryForObject("select set_config(?,?,true)",String.class,name,value);
 }

 /** Executa um item dentro de um savepoint: a recusa do banco desfaz só o item, nunca o lote. */
 public <T> T item(Supplier<T> action) {
  jdbc.execute("savepoint cp_rights_item");
  try {
   T result=action.get();
   jdbc.execute("release savepoint cp_rights_item");
   return result;
  } catch(RuntimeException error) {
   jdbc.execute("rollback to savepoint cp_rights_item");
   jdbc.execute("release savepoint cp_rights_item");
   throw error;
  }
 }

 /** Sujeitos com direito ativo ou com algo já publicado (destinos de onde pode ser preciso retirar). */
 public int countSubjects(UUID tenant) {
  Integer n=jdbc.queryForObject("""
   select count(*) from (select subject_id from cp.access_grants where tenant_id=? and status='ACTIVE'
    union select subject_id from cp.rights_publications where tenant_id=?) x
   """,Integer.class,tenant,tenant);
  return n==null?0:n;
 }
 public List<UUID> subjects(UUID tenant,int limit) {
  return jdbc.query("""
   select subject_id from (select subject_id from cp.access_grants where tenant_id=? and status='ACTIVE'
    union select subject_id from cp.rights_publications where tenant_id=?) x order by subject_id limit ?
   """,(r,n)->r.getObject(1,UUID.class),tenant,tenant,limit);
 }
 public List<UUID> existingSubjects(UUID tenant,List<UUID> ids) {
  return jdbc.query("select id from cp.access_subjects where tenant_id=? and id=any(?::text[]::uuid[]) order by id",(r,n)->r.getObject(1,UUID.class),
   tenant,ids.stream().map(UUID::toString).toArray(String[]::new));
 }

 /**
  * Destinos do sujeito cujo documento compilado mudou desde o último trabalho do destino; destino que nunca recebeu nada
  * só entra se houver direito a aplicar. cp.rights_compiled_set (V051) compila todos os destinos com uma só passagem
  * dos alvos, a mesma fonte de cp.rights_compiled usada pelo gatilho (antes eram uma passagem por destino, mais uma
  * para listar os destinos).
  */
 public List<UUID> changedDevices(UUID tenant,UUID subject) {
  return scan(()->jdbc.query("""
   select c.device_id from cp.rights_compiled_set(?,?,null) c
    left join lateral (select r.document_hash from cp.rights_publications r where r.tenant_id=? and r.subject_id=? and r.device_id=c.device_id
     and r.state<>'SUPERSEDED' order by r.desired_version desc limit 1) l on true
   where c.document is not null and l.document_hash is distinct from cp.rights_document_hash(c.document)
    and (l.document_hash is not null or c.document->>'action'='APPLY_RIGHTS')
   order by c.device_id
   """,(r,n)->r.getObject(1,UUID.class),tenant,subject,tenant,subject));
 }
 /** Direito vigente que ficou fora do documento de um destino, com o motivo do predicado único (cp.rights_targets). */
 public record Excluded(UUID deviceId,String issue) {}
 /** Visão do sujeito numa só avaliação dos alvos: motivos de exclusão e destinos acima do limite do documento. */
 public record Review(List<Excluded> excluded,List<UUID> oversized) {}
 /**
  * Uma chamada a cp.rights_targets por sujeito (o mesmo filtro de cp.rights_entries: alvo vigente sem motivo entra no
  * documento): direitos vigentes fora do documento, com motivo por destino, e destinos que passariam de 512 entradas
  * (o compilado retira tudo deles: falha fechada).
  */
 public Review review(UUID tenant,UUID subject) {
  var excluded=new java.util.ArrayList<Excluded>();
  var oversized=new java.util.ArrayList<UUID>();
  scan(()->{
   jdbc.query("""
    with x as materialized (select * from cp.rights_targets(?,?))
    select device_id,issue from (select distinct device_id,issue from x where issue is not null and is_current) e
    union all
    select device_id,null from x where issue is null and is_current group by device_id having count(*)>512
    order by 1,2
    """,(org.springframework.jdbc.core.RowCallbackHandler)r->{
     UUID device=r.getObject(1,UUID.class);
     if(r.getString(2)!=null) excluded.add(new Excluded(device,r.getString(2))); else oversized.add(device);
    },tenant,subject);
   return null;
  });
  return new Review(List.copyOf(excluded),List.copyOf(oversized));
 }

 /** Grava o trabalho de um destino; o banco compila de novo e recusa o que não pode publicar. */
 public void planDevice(UUID tenant,UUID operation,UUID subject,UUID device,UUID actor) {
  scan(()->jdbc.update("insert into cp.rights_publications(tenant_id,operation_id,subject_id,device_id,origin,requested_by) values(?,?,?,?,'PLAN',?)",
   tenant,operation,subject,device,actor));
 }

 private static final String COLUMNS="""
  id,operation_id,subject_id,device_id,origin,revocation_marker_id,action,priority,desired_version,rights_generation,subject_restriction_epoch,
  installation_revision,jsonb_array_length(document->'entries') entries,document_hash,state,confirmation_level,result_code,attempts,reported_at,
  confirmed_at,superseded_by,created_at,version
  """;
 private static PublicationModels.Publication map(ResultSet r,int n) throws SQLException {
  return new PublicationModels.Publication(r.getObject("id",UUID.class),r.getObject("operation_id",UUID.class),r.getObject("subject_id",UUID.class),
   r.getObject("device_id",UUID.class),r.getString("origin"),r.getObject("revocation_marker_id",UUID.class),r.getString("action"),r.getInt("priority"),
   r.getLong("desired_version"),r.getLong("rights_generation"),r.getLong("subject_restriction_epoch"),r.getInt("installation_revision"),
   r.getInt("entries"),r.getString("document_hash"),r.getString("state"),r.getString("confirmation_level"),r.getString("result_code"),
   r.getInt("attempts"),instant(r.getTimestamp("reported_at")),instant(r.getTimestamp("confirmed_at")),r.getObject("superseded_by",UUID.class),
   r.getTimestamp("created_at").toInstant(),r.getLong("version"));
 }
 private static final String FILTER="""
  tenant_id=? and (?::uuid is null or operation_id=?::uuid) and (?::uuid is null or subject_id=?::uuid) and (?::uuid is null or device_id=?::uuid)
  """;
 public List<PublicationModels.Publication> publications(UUID tenant,UUID operation,UUID subject,UUID device,int limit) {
  return jdbc.query("select "+COLUMNS+" from cp.rights_publications where "+FILTER+" order by desired_version desc limit ?",PublicationStore::map,
   tenant,operation,operation,subject,subject,device,device,limit);
 }
 /** Contagem por estado sobre todas as linhas do filtro. */
 public Map<String,Integer> countByState(UUID tenant,UUID operation,UUID subject,UUID device) {
  var counts=new LinkedHashMap<String,Integer>();
  for(String s:PublicationModels.STATES) counts.put(s,0);
  jdbc.query("select state,count(*) from cp.rights_publications where "+FILTER+" group by state",(org.springframework.jdbc.core.RowCallbackHandler)r->
   counts.put(r.getString(1),r.getInt(2)),tenant,operation,operation,subject,subject,device,device);
  return counts;
 }
 /**
  * Situação atual dos destinos do filtro: para cada sujeito × equipamento tocado, o trabalho mais novo do destino em
  * qualquer operação (o sucessor de um superado). Retorna {destinos, destinos com o trabalho atual CONFIRMED}.
  */
 public int[] currentDestinations(UUID tenant,UUID operation,UUID subject,UUID device) {
  return jdbc.queryForObject("select count(*),count(*) filter (where l.state='CONFIRMED') from (select distinct subject_id,device_id "
   +"from cp.rights_publications where "+FILTER+") d cross join lateral (select r.state from cp.rights_publications r where r.tenant_id=? "
   +"and r.subject_id=d.subject_id and r.device_id=d.device_id order by r.desired_version desc limit 1) l",(r,n)->new int[]{r.getInt(1),r.getInt(2)},tenant,operation,operation,subject,subject,device,device,tenant);
 }

 /**
  * Relatório do executor (cp_worker; API do gateway na fatia seguinte): grava só o resultado, com a versão lida. O banco
  * recusa trabalho superado ou confirmado, confirmação sem leitura igual ao hash e volta a pendente. false = versão mudou.
  */
 public boolean report(UUID tenant,UUID publication,long expectedVersion,String state,String confirmationLevel,String observedHash,String resultCode,
   int attempts,long retryDelaySeconds) {
  return jdbc.update("""
   update cp.rights_publications set state=?,confirmation_level=?,observed_hash=?,result_code=?,attempts=?,
    next_attempt_at=clock_timestamp()+make_interval(secs=>?),version=version+1 where tenant_id=? and id=? and version=?
   """,state,confirmationLevel,observedHash,resultCode,attempts,(double)retryDelaySeconds,tenant,publication,expectedVersion)==1;
 }

 private static Instant instant(Timestamp t) { return t==null?null:t.toInstant(); }
}
