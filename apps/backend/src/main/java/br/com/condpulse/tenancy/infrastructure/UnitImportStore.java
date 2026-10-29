package br.com.condpulse.tenancy.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.tenancy.application.UnitCsvParser;
import br.com.condpulse.tenancy.application.UnitImportModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lotes de importação de unidades. Somente dentro da transação do TenantTransactionExecutor, com units.manage publicada.
 * Aprovação em poucas instruções por conjunto (blocos, unidades, linhas e trilha), dentro do limite de tempo da transação.
 */
@Repository @Profile("api")
public class UnitImportStore {
 private static final String BATCH_COLUMNS="""
  id,source,file_hash,mapping_version,state,total_rows,valid_rows,invalid_rows,duplicate_rows,partial,created_units,
  existing_units,created_blocks,created_at,decided_at,version
  """;
 // Bloco correspondente sem diferenciar caixa: o mais antigo em ordem de nome quando houver mais de um.
 private static final String BLOCK_OF_ROW="""
  left join lateral(select b.id from cp.blocks b where b.tenant_id=r.tenant_id and r.block_name is not null
   and lower(b.name)=lower(r.block_name) order by b.name,b.id limit 1) blk on true
  """;
 private final JdbcTemplate jdbc;
 public UnitImportStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public UnitImportModels.Batch liveByHash(UUID tenant,String hash) {
  var rows=jdbc.query("select "+BATCH_COLUMNS+" from cp.import_batches where tenant_id=? and entity_type='UNITS' and file_hash=?"
   +" and state<>'DISCARDED'",(r,n)->batch(r),tenant,hash);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public UnitImportModels.Batch find(UUID tenant,UUID batch,boolean lock) {
  var rows=jdbc.query("select "+BATCH_COLUMNS+" from cp.import_batches where tenant_id=? and id=? and entity_type='UNITS'"
   +(lock?" for update":""),(r,n)->batch(r),tenant,batch);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public List<UnitImportModels.Batch> recent(UUID tenant) {
  return jdbc.query("select "+BATCH_COLUMNS+" from cp.import_batches where tenant_id=? and entity_type='UNITS'"
   +" order by created_at desc,id limit 50",(r,n)->batch(r),tenant);
 }
 public UUID insert(UUID tenant,String source,UnitCsvParser.Parsed parsed) {
  UUID id=UUID.randomUUID();
  jdbc.update("""
   insert into cp.import_batches(id,tenant_id,entity_type,source,file_hash,mapping_version,total_rows,valid_rows,invalid_rows,duplicate_rows)
   values(?,?,'UNITS',?,?,?,?,?,?,?)
   """,id,tenant,source,parsed.fileHash(),UnitCsvParser.MAPPING_VERSION,parsed.rows().size(),parsed.valid(),parsed.invalid(),
   parsed.duplicate());
  jdbc.batchUpdate("""
   insert into cp.import_rows(tenant_id,batch_id,line_number,block_name,unit_label,external_id,outcome,error_code)
   values(?,?,?,?,?,?,?,?)
   """,parsed.rows(),500,(statement,row)->{
    statement.setObject(1,tenant); statement.setObject(2,id); statement.setInt(3,row.line());
    statement.setString(4,row.block()); statement.setString(5,row.unit()); statement.setString(6,row.externalId());
    statement.setString(7,row.outcome()); statement.setString(8,row.errorCode());
   });
  return id;
 }
 public List<UnitImportModels.Row> rows(UUID tenant,UUID batch) {
  return jdbc.query("""
   select r.line_number,r.block_name,r.unit_label,r.external_id,r.outcome,r.error_code,r.applied,r.unit_id,
    (r.outcome='VALID' and r.applied is null and exists(select 1 from cp.units u where u.tenant_id=r.tenant_id
     and u.block_id is not distinct from blk.id and (r.block_name is null or blk.id is not null)
     and lower(u.label)=lower(r.unit_label))) as already_exists
   from cp.import_rows r
   """+BLOCK_OF_ROW+"""
   where r.tenant_id=? and r.batch_id=? order by r.line_number
   """,(r,n)->new UnitImportModels.Row(r.getInt("line_number"),r.getString("block_name"),r.getString("unit_label"),
    r.getString("external_id"),r.getString("outcome"),r.getString("error_code"),r.getBoolean("already_exists"),
    r.getString("applied"),r.getObject("unit_id",UUID.class)),tenant,batch);
 }
 /**
  * Serializa as aprovações de importação do condomínio até o fim da transação: a comparação sem diferenciar caixa
  * ("Casa 1" e "casa 1") não é garantida pelas constraints de blocos e unidades, que comparam o texto original.
  */
 public void lockTenantImports(UUID tenant) {
  jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended('cp.import_units:'||?::text,0))::text",String.class,tenant);
 }
 /** Cria os blocos que faltam (sem diferenciar caixa) e devolve os criados. */
 public List<UUID> createMissingBlocks(UUID tenant,UUID batch) {
  return jdbc.queryForList("""
   insert into cp.blocks(tenant_id,name)
   select r.tenant_id,min(r.block_name) from cp.import_rows r
   where r.tenant_id=? and r.batch_id=? and r.outcome='VALID' and r.block_name is not null
    and not exists(select 1 from cp.blocks b where b.tenant_id=r.tenant_id and lower(b.name)=lower(r.block_name))
   group by r.tenant_id,lower(r.block_name)
   returning id
   """,UUID.class,tenant,batch);
 }
 /** Cria as unidades que faltam no bloco correspondente e devolve as criadas; as existentes ficam como estão. */
 public List<UUID> createMissingUnits(UUID tenant,UUID batch) {
  return jdbc.queryForList("""
   insert into cp.units(tenant_id,block_id,label,status)
   select distinct on(blk.id,lower(r.unit_label)) r.tenant_id,blk.id,r.unit_label,'ACTIVE'
   from cp.import_rows r
   """+BLOCK_OF_ROW+"""
   where r.tenant_id=? and r.batch_id=? and r.outcome='VALID'
    and not exists(select 1 from cp.units u where u.tenant_id=r.tenant_id and u.block_id is not distinct from blk.id
     and lower(u.label)=lower(r.unit_label))
   order by blk.id,lower(r.unit_label),r.line_number
   on conflict do nothing
   returning id
   """,UUID.class,tenant,batch);
 }
 /** Liga cada linha válida à unidade correspondente; devolve quantas linhas foram aplicadas. */
 public int applyRows(UUID tenant,UUID batch,List<UUID> created) {
  String createdIds=String.join(",",created.stream().map(UUID::toString).toList());
  return jdbc.update("""
   update cp.import_rows target set unit_id=m.unit_id,
    applied=case when m.unit_id=any(string_to_array(?,',')::uuid[]) then 'CREATED' else 'EXISTING' end
   from(select r.line_number,(select u.id from cp.units u where u.tenant_id=r.tenant_id and u.block_id is not distinct from blk.id
     and lower(u.label)=lower(r.unit_label) order by u.label,u.id limit 1) as unit_id
    from cp.import_rows r
   """+BLOCK_OF_ROW+"""
    where r.tenant_id=? and r.batch_id=? and r.outcome='VALID') m
   where target.tenant_id=? and target.batch_id=? and target.line_number=m.line_number
   """,createdIds,tenant,batch,tenant,batch);
 }
 public boolean approve(UUID tenant,UUID batch,long expectedVersion,boolean partial,int createdUnits,int existingUnits,int createdBlocks) {
  return jdbc.update("""
   update cp.import_batches set state='APPROVED',partial=?,created_units=?,existing_units=?,created_blocks=?,version=version+1
   where tenant_id=? and id=? and state='PREVIEW' and version=?
   """,partial,createdUnits,existingUnits,createdBlocks,tenant,batch,expectedVersion)==1;
 }
 public boolean discard(UUID tenant,UUID batch,long expectedVersion) {
  return jdbc.update("update cp.import_batches set state='DISCARDED',version=version+1 where tenant_id=? and id=? and state='PREVIEW'"
   +" and version=?",tenant,batch,expectedVersion)==1;
 }
 /** Trilha das unidades e blocos criados pela importação, em nome de quem aprovou. */
 public void auditCreated(UUID tenant,UUID actor,String action,String resourceType,List<UUID> resources,String reason) {
  if(resources.isEmpty()) return;
  jdbc.update("""
   insert into cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id,reason)
   select ?,?,?,?,x,gen_random_uuid(),? from unnest(string_to_array(?,',')::uuid[]) x
   """,tenant,actor,action,resourceType,reason,String.join(",",resources.stream().map(UUID::toString).toList()));
 }
 private static UnitImportModels.Batch batch(ResultSet r) throws SQLException {
  return new UnitImportModels.Batch(r.getObject("id",UUID.class),r.getString("source"),r.getString("file_hash"),
   r.getInt("mapping_version"),r.getString("state"),r.getInt("total_rows"),r.getInt("valid_rows"),r.getInt("invalid_rows"),
   r.getInt("duplicate_rows"),r.getBoolean("partial"),integer(r,"created_units"),integer(r,"existing_units"),
   integer(r,"created_blocks"),instant(r.getTimestamp("created_at")),instant(r.getTimestamp("decided_at")),r.getLong("version"));
 }
 private static Integer integer(ResultSet r,String column) throws SQLException {
  int value=r.getInt(column);
  return r.wasNull()?null:value;
 }
 private static java.time.Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
