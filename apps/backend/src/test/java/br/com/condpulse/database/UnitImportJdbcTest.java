package br.com.condpulse.database;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.tenancy.application.UnitImportModels;
import br.com.condpulse.tenancy.application.UnitImportService;
import br.com.condpulse.tenancy.infrastructure.UnitImportStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Importação de unidades em staging (CP-016) com PostgreSQL real: prévia sem gravação final, mesmo arquivo no mesmo
 * lote, aprovação que não duplica unidades nem blocos (inclusive com caixa diferente e aprovações simultâneas), parcial
 * só com aceite explícito, isolamento entre condomínios e gestão com AAL2.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class UnitImportJdbcTest {
 private static JdbcDisposableDatabase db;
 private static UnitImportService imports;
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2,AAL1=AssuranceLevel.AAL1;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(4);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  imports=new UnitImportService(transactions,new UnitImportStore(db.jdbc),new AuditRecorder(db.jdbc));
  // Conta C gestora de A; conta B gestora de B. Gestão nunca vem da importação.
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTOR FICTICIO IMPORTACAO A','MANAGER','ACTIVE'),"
   +"(?,?,'GESTOR FICTICIO IMPORTACAO B','MANAGER','ACTIVE')",db.tenantA,db.accountC,db.tenantB,db.accountB);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String tag() { return UUID.randomUUID().toString().substring(0,8).toUpperCase(); }
 private static long units(UUID tenant) throws Exception {
  return Long.parseLong(db.adminString("select count(*) from cp.units where tenant_id=?",tenant));
 }
 private static long blocks(UUID tenant) throws Exception {
  return Long.parseLong(db.adminString("select count(*) from cp.blocks where tenant_id=?",tenant));
 }

 @Test void previewWritesNothingFinalAndTheSameFileReturnsTheSameBatch() throws Exception {
  String t=tag();
  String csv="bloco;unidade;codigo_externo\nTorre "+t+";101;EXT-1\nTorre "+t+";102;\n;;\n";
  long unitsBefore=units(db.tenantA),blocksBefore=blocks(db.tenantA);
  var first=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("C:\\planilhas\\unidades "+t+".csv",csv));
  assertTrue(first.created());
  var batch=first.detail().batch();
  assertEquals("unidades "+t+".csv",batch.source(),"só o nome do arquivo, sem o caminho local");
  assertEquals("PREVIEW",batch.state()); assertEquals(3,batch.totalRows()); assertEquals(2,batch.validRows());
  assertEquals(1,batch.invalidRows()); assertEquals(64,batch.fileHash().length());
  assertEquals("UNIT_LABEL_REQUIRED",first.detail().rows().get(2).errorCode());
  assertEquals(unitsBefore,units(db.tenantA)); assertEquals(blocksBefore,blocks(db.tenantA));
  var again=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("outro-nome.csv",csv));
  assertFalse(again.created(),"mesmo conteúdo reencontra o lote vivo");
  assertEquals(batch.batchId(),again.detail().batch().batchId());
  assertEquals("1",db.adminString("select count(*) from cp.import_batches where tenant_id=? and file_hash=?",db.tenantA,batch.fileHash()));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where tenant_id=? and action='IMPORT_BATCH_OPENED' and resource_id=?",
   db.tenantA,batch.batchId()));
  db.assertEmptyContext();
 }

 @Test void approvalCreatesOnlyWhatIsMissingAndRepeatingCreatesNothing() throws Exception {
  String t=tag();
  // Unidade existente com outra caixa: não é recriada.
  db.adminUpdate("insert into cp.blocks(tenant_id,name) values(?,?)",db.tenantA,"Bloco "+t);
  db.adminUpdate("insert into cp.units(tenant_id,block_id,label) select tenant_id,id,? from cp.blocks where tenant_id=? and name=?",
   "1"+t,db.tenantA,"Bloco "+t);
  String csv="unidade,bloco\n1"+t.toLowerCase()+",bloco "+t.toLowerCase()+"\n2"+t+",Bloco "+t+"\n3"+t+",Novo "+t+"\n4"+t+",\n";
  var opened=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("lote.csv",csv)).detail();
  assertTrue(opened.rows().getFirst().alreadyExists(),"prévia mostra que a primeira unidade já existe");
  assertFalse(opened.rows().get(1).alreadyExists());
  long unitsBefore=units(db.tenantA),blocksBefore=blocks(db.tenantA);
  var approved=imports.approve(db.accountC,db.tenantA,AAL2,opened.batch().batchId(),new UnitImportModels.Approve(0L,false));
  assertTrue(approved.applied());
  var batch=approved.detail().batch();
  assertEquals("APPROVED",batch.state()); assertFalse(batch.partial());
  assertEquals(3,batch.createdUnits()); assertEquals(1,batch.existingUnits()); assertEquals(1,batch.createdBlocks());
  assertEquals(unitsBefore+3,units(db.tenantA)); assertEquals(blocksBefore+1,blocks(db.tenantA));
  assertEquals(List.of("EXISTING","CREATED","CREATED","CREATED"),approved.detail().rows().stream().map(UnitImportModels.Row::applied).toList());
  assertTrue(approved.detail().rows().stream().allMatch(row->row.unitId()!=null));
  assertEquals("3",db.adminString("select count(*) from cp.audit_log where tenant_id=? and action='UNIT_CREATED' and reason=?",
   db.tenantA,"Importação de unidades "+batch.batchId()));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where tenant_id=? and action='IMPORT_BATCH_APPROVED' and resource_id=?",
   db.tenantA,batch.batchId()));
  // Repetir a aprovação, reenviar o arquivo e aprovar um arquivo novo com as mesmas unidades não criam nada.
  var repeated=imports.approve(db.accountC,db.tenantA,AAL2,batch.batchId(),new UnitImportModels.Approve(0L,false));
  assertFalse(repeated.applied());
  var reopened=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("lote.csv",csv));
  assertFalse(reopened.created()); assertEquals("APPROVED",reopened.detail().batch().state());
  var copy=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("copia.csv",csv+"\n")).detail();
  assertTrue(copy.rows().stream().allMatch(UnitImportModels.Row::alreadyExists));
  var copyApproved=imports.approve(db.accountC,db.tenantA,AAL2,copy.batch().batchId(),new UnitImportModels.Approve(0L,false));
  assertEquals(0,copyApproved.detail().batch().createdUnits()); assertEquals(4,copyApproved.detail().batch().existingUnits());
  assertEquals(unitsBefore+3,units(db.tenantA)); assertEquals(blocksBefore+1,blocks(db.tenantA));
  db.assertEmptyContext();
 }

 @Test void partialBatchNeedsExplicitAcceptanceAndDiscardIsFinal() throws Exception {
  String t=tag();
  String csv="bloco;unidade\nP"+t+";10\nP"+t+";10\nP"+t+";\n";
  var opened=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("parcial.csv",csv)).detail();
  assertEquals(1,opened.batch().duplicateRows()); assertEquals(1,opened.batch().invalidRows());
  long before=units(db.tenantA);
  var refused=assertThrows(BusinessProblem.class,()->imports.approve(db.accountC,db.tenantA,AAL2,opened.batch().batchId(),
   new UnitImportModels.Approve(0L,null)));
  assertEquals("IMPORT_PARTIAL_NOT_ACCEPTED",refused.code()); assertEquals(409,refused.status());
  assertEquals(before,units(db.tenantA));
  var stale=assertThrows(BusinessProblem.class,()->imports.approve(db.accountC,db.tenantA,AAL2,opened.batch().batchId(),
   new UnitImportModels.Approve(3L,true)));
  assertEquals("IMPORT_BATCH_CHANGED",stale.code());
  var approved=imports.approve(db.accountC,db.tenantA,AAL2,opened.batch().batchId(),new UnitImportModels.Approve(0L,true));
  assertTrue(approved.detail().batch().partial()); assertEquals(before+1,units(db.tenantA));
  assertEquals("IMPORT_BATCH_APPROVED",assertThrows(BusinessProblem.class,()->imports.discard(db.accountC,db.tenantA,AAL2,
   opened.batch().batchId(),new UnitImportModels.Discard(1L))).code());
  var other=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("descarte.csv","unidade\nD"+t+"\n")).detail();
  var discarded=imports.discard(db.accountC,db.tenantA,AAL2,other.batch().batchId(),new UnitImportModels.Discard(0L));
  assertTrue(discarded.applied()); assertEquals("DISCARDED",discarded.detail().batch().state());
  assertFalse(imports.discard(db.accountC,db.tenantA,AAL2,other.batch().batchId(),new UnitImportModels.Discard(0L)).applied());
  assertEquals("IMPORT_BATCH_DISCARDED",assertThrows(BusinessProblem.class,()->imports.approve(db.accountC,db.tenantA,AAL2,
   other.batch().batchId(),new UnitImportModels.Approve(1L,false))).code());
  // Depois do descarte o mesmo arquivo abre um lote novo.
  assertTrue(imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("descarte.csv","unidade\nD"+t+"\n")).created());
  db.assertEmptyContext();
 }

 @Test void simultaneousApprovalsCreateEachUnitOnce() throws Exception {
  String t=tag();
  var opened=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("corrida.csv",
   "bloco;unidade\nC"+t+";1\nC"+t+";2\nC"+t+";3\n")).detail();
  long before=units(db.tenantA);
  var pool=Executors.newFixedThreadPool(4);
  try {
   List<Callable<Boolean>> calls=new ArrayList<>();
   for(int i=0;i<4;i++) calls.add(()->{
    try { return imports.approve(db.accountC,db.tenantA,AAL2,opened.batch().batchId(),new UnitImportModels.Approve(0L,false)).applied(); }
    catch(BusinessProblem problem) { return false; }
   });
   long applied=0;
   for(var result:pool.invokeAll(calls)) if(result.get()) applied++;
   assertEquals(1,applied,"uma aprovação aplica; as outras encontram o lote aprovado");
  } finally { pool.shutdownNow(); }
  assertEquals(before+3,units(db.tenantA));
  assertEquals("1",db.adminString("select count(*) from cp.blocks where tenant_id=? and lower(name)=lower(?)",db.tenantA,"C"+t));
  db.assertEmptyContext();
 }

 @Test void simultaneousBatchesWithTheSameUnitInDifferentCaseCreateItOnce() throws Exception {
  String t=tag();
  long before=units(db.tenantA);
  var upper=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("maiusculas.csv","unidade\nCasa "+t+"\n")).detail();
  var lower=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("minusculas.csv","unidade\ncasa "+t.toLowerCase()+"\n")).detail();
  var pool=Executors.newFixedThreadPool(2);
  try {
   List<Callable<UnitImportModels.Decided>> calls=List.of(
    ()->imports.approve(db.accountC,db.tenantA,AAL2,upper.batch().batchId(),new UnitImportModels.Approve(0L,false)),
    ()->imports.approve(db.accountC,db.tenantA,AAL2,lower.batch().batchId(),new UnitImportModels.Approve(0L,false)));
   int created=0;
   for(var result:pool.invokeAll(calls)) created+=result.get().detail().batch().createdUnits();
   assertEquals(1,created,"a unidade equivalente é criada por um só lote; o outro a encontra");
  } finally { pool.shutdownNow(); }
  assertEquals(before+1,units(db.tenantA));
  db.assertEmptyContext();
 }

 @Test void onlyAal2ManagersOfTheSameTenantSeeOrDecide() throws Exception {
  String t=tag();
  var opened=imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("isolamento.csv","unidade\nI"+t+"\n")).detail();
  UUID batch=opened.batch().batchId();
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->imports.recent(db.accountC,db.tenantA,AAL1)).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->imports.detail(db.accountA,db.tenantA,AAL2,batch)).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->imports.open(db.accountA,db.tenantA,AAL2,
   new UnitImportModels.Open("morador.csv","unidade\nM"+t+"\n"))).code());
  // Gestor de outro condomínio não vê nem decide o lote, mesmo pelo ID.
  assertEquals("IMPORT_BATCH_NOT_FOUND",assertThrows(BusinessProblem.class,()->imports.detail(db.accountB,db.tenantB,AAL2,batch)).code());
  assertEquals("IMPORT_BATCH_NOT_FOUND",assertThrows(BusinessProblem.class,()->imports.approve(db.accountB,db.tenantB,AAL2,batch,
   new UnitImportModels.Approve(0L,false))).code());
  assertTrue(imports.recent(db.accountB,db.tenantB,AAL2).batches().stream().noneMatch(item->item.batchId().equals(batch)));
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->imports.recent(db.accountC,db.tenantB,AAL2)).code());
  assertEquals("PREVIEW",imports.detail(db.accountC,db.tenantA,AAL2,batch).batch().state());
  db.assertEmptyContext();
 }

 @Test void fileProblemsAreRefusedBeforeTheDatabase() throws Exception {
  for(String content:new String[]{"","casa;numero\n1\n","unidade\n"}) {
   var problem=assertThrows(BusinessProblem.class,()->imports.open(db.accountC,db.tenantA,AAL2,new UnitImportModels.Open("x.csv",content)));
   assertEquals(400,problem.status());
  }
  assertEquals("IMPORT_FILE_TOO_LARGE",assertThrows(BusinessProblem.class,()->imports.open(db.accountC,db.tenantA,AAL2,
   new UnitImportModels.Open("x.csv","unidade\n"+"1\n".repeat(140_000)))).code());
  assertEquals("IMPORT_FILE_NAME_INVALID",assertThrows(BusinessProblem.class,()->imports.open(db.accountC,db.tenantA,AAL2,
   new UnitImportModels.Open(" ","unidade\n1\n"))).code());
  db.assertEmptyContext();
 }
}
