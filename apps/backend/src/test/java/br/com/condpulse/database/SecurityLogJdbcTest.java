package br.com.condpulse.database;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.accesssubjects.application.SubjectService;
import br.com.condpulse.accesssubjects.infrastructure.SubjectStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.occurrences.application.OccurrenceModels;
import br.com.condpulse.occurrences.application.OccurrenceService;
import br.com.condpulse.occurrences.infrastructure.OccurrenceStore;
import br.com.condpulse.securitylog.application.SecurityLogModels;
import br.com.condpulse.securitylog.application.SecurityLogService;
import br.com.condpulse.securitylog.infrastructure.SecurityLogStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.application.AccessService;
import br.com.condpulse.visits.infrastructure.AccessStore;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V050 (CPX-006 a CPX-011) com PostgreSQL real e o login da API: busca com filtros e paginação só com
 * security.log.read.tenant e AAL2, SENSITIVE_VIEWED em toda leitura sensível, verificação que detecta adulteração,
 * exportação só com security.log.export e motivo, e falha do log desfazendo a operação de negócio.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class SecurityLogJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static TenantTransactionExecutor transactions;
 private static SecurityLogService log;
 private static SecurityLogStore store;
 private static UUID manager,concierge,managerB,conciergeB;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  store=new SecurityLogStore(db.jdbc);
  log=new SecurityLogService(transactions,store);
  manager=UUID.randomUUID(); concierge=UUID.randomUUID(); managerB=UUID.randomUUID(); conciergeB=UUID.randomUUID();
  for(UUID account:List.of(manager,concierge,managerB,conciergeB)) db.extraAccounts.add(account);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE'),(?,'ACTIVE'),(?,'ACTIVE')",manager,concierge,managerB,conciergeB);
  db.adminUpdate("""
   insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
   values(?,?,null,'GESTAO LOG JDBC','MANAGER','ACTIVE'),(?,?,null,'PORTARIA LOG JDBC','CONCIERGE','ACTIVE'),
    (?,?,null,'GESTAO LOG JDBC B','MANAGER','ACTIVE'),(?,?,null,'PORTARIA LOG JDBC B','CONCIERGE','ACTIVE')
   """,db.tenantA,manager,db.tenantA,concierge,db.tenantB,managerB,db.tenantB,conciergeB);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception {
  if(db!=null) {
   db.adminUpdateWithoutTriggers("delete from cp.concierge_occurrences where tenant_id in (?,?)",db.tenantA,db.tenantB);
   db.close();
  }
 }
 private static long count(UUID tenant,String type) throws Exception {
  return Long.parseLong(db.adminString("select count(*) from cp.security_log where tenant_id=? and event_type=?",tenant,type));
 }
 private static SecurityLogModels.Filter all() { return SecurityLogModels.Filter.of(null,null,null,null,null,null,null); }

 @Test void managerReadsOwnLogWithFiltersAndEveryReadIsRecorded() throws Exception {
  long before=count(db.tenantA,"SENSITIVE_VIEWED");
  var page=log.page(manager,db.tenantA,AAL2,all());
  assertFalse(page.events().isEmpty(),"Vínculos ativos da fixture já geraram ROLE_GRANTED");
  assertTrue(page.events().stream().anyMatch(e->"ROLE_GRANTED".equals(e.eventType())));
  for(int i=1;i<page.events().size();i++) assertTrue(page.events().get(i-1).seq()>page.events().get(i).seq(),"Mais recente primeiro");
  assertEquals(before+1,count(db.tenantA,"SENSITIVE_VIEWED"),"R5.6: toda leitura do log grava SENSITIVE_VIEWED");
  assertEquals(manager.toString(),db.adminString("select actor_account_id::text from cp.security_log where tenant_id=? and event_type='SENSITIVE_VIEWED' order by seq desc limit 1",db.tenantA));
  assertEquals("MANAGER",db.adminString("select actor_role from cp.security_log where tenant_id=? and event_type='SENSITIVE_VIEWED' order by seq desc limit 1",db.tenantA));
  assertEquals("SECURITY_LOG",db.adminString("select details->>'view' from cp.security_log where tenant_id=? and event_type='SENSITIVE_VIEWED' order by seq desc limit 1",db.tenantA));
  log.page(manager,db.tenantA,AAL2,all());
  // Filtros: tipo, severidade, autor e período; paginação por seq (há pelo menos duas leituras anteriores do mesmo autor).
  var viewed=log.page(manager,db.tenantA,AAL2,SecurityLogModels.Filter.of("SENSITIVE_VIEWED","INFO",manager,
   Instant.now().minus(1,ChronoUnit.HOURS),Instant.now().plus(1,ChronoUnit.HOURS),null,1));
  assertEquals(1,viewed.events().size()); assertEquals("SENSITIVE_VIEWED",viewed.events().getFirst().eventType());
  assertEquals(manager,viewed.events().getFirst().actorAccountId());
  assertNotNull(viewed.nextBeforeSeq(),"Há outra leitura anterior do mesmo autor");
  var older=log.page(manager,db.tenantA,AAL2,SecurityLogModels.Filter.of("SENSITIVE_VIEWED",null,manager,null,null,viewed.nextBeforeSeq(),1));
  assertTrue(older.events().getFirst().seq()<viewed.events().getFirst().seq());
  assertTrue(log.page(manager,db.tenantA,AAL2,SecurityLogModels.Filter.of(null,"CRITICAL",null,null,null,null,10)).events().isEmpty());
  assertTrue(log.page(manager,db.tenantA,AAL2,SecurityLogModels.Filter.of(null,null,concierge,null,null,null,10)).events().isEmpty());
  assertTrue(log.page(manager,db.tenantA,AAL2,SecurityLogModels.Filter.of(null,null,null,Instant.parse("2001-01-01T00:00:00Z"),
   Instant.parse("2001-01-02T00:00:00Z"),null,10)).events().isEmpty());
  // Isolamento: os eventos do condomínio B não aparecem para a gestão do A.
  String firstOfB=db.adminString("select hash from cp.security_log where tenant_id=? order by seq limit 1",db.tenantB);
  assertNotNull(firstOfB);
  assertTrue(page.events().stream().noneMatch(e->e.hash().equals(firstOfB)));
 }

 @Test void onlyStaffWithCapabilityAndSecondFactorReads() throws Exception {
  long before=count(db.tenantA,"SENSITIVE_VIEWED");
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->log.page(manager,db.tenantA,AssuranceLevel.AAL1,all())).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->log.page(concierge,db.tenantA,AAL2,all())).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->log.page(db.accountA,db.tenantA,AAL2,all())).code(),"Morador não lê o log");
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->log.page(manager,db.tenantB,AAL2,all())).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->log.integrity(concierge,db.tenantA,AAL2)).code());
  assertEquals(before,count(db.tenantA,"SENSITIVE_VIEWED"),"Leitura recusada não grava nada");
  // Exportação é do líder de segurança do parceiro (security.log.export): nenhum papel de condomínio a tem hoje.
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->log.export(manager,db.tenantA,AAL2,new SecurityLogModels.ExportRequest("Pedido da autoridade policial ficticio",null,null))).code());
  assertEquals("SECURITY_LOG_EXPORT_REASON_REQUIRED",assertThrows(BusinessProblem.class,
   ()->log.export(manager,db.tenantA,AAL2,new SecurityLogModels.ExportRequest("curto",null,null))).code());
  assertEquals(0,count(db.tenantA,"LOG_EXPORTED"));
 }

 @Test void exportWithCapabilityRecordsManifestEventAndRefusesMissingReason() throws Exception {
  // A capacidade de exportação ainda não é publicada pela API; aqui é colocada direto no contexto da transação (só no teste).
  var range=db.context(manager,db.tenantA,null,()->{
   db.jdbc.queryForObject("select set_config('app.capabilities','security.log.export',true)",String.class);
   long last=store.lastSeq(db.tenantA);
   var events=store.range(db.tenantA,1,last);
   assertTrue(SecurityLogModels.linked(events,1,last),"Intervalo contíguo e encadeado");
   store.recordExport(db.tenantA,UUID.randomUUID(),"Auditoria interna ficticia do condominio",
    Map.of("fromSeq",1,"toSeq",last,"count",events.size(),"firstHash",events.getFirst().hash(),"lastHash",events.getLast().hash()));
   return events;
  });
  assertFalse(range.isEmpty());
  assertEquals(1,count(db.tenantA,"LOG_EXPORTED"));
  assertEquals("Auditoria interna ficticia do condominio",db.adminString("select reason from cp.security_log where tenant_id=? and event_type='LOG_EXPORTED'",db.tenantA));
  assertEquals(range.getLast().hash(),db.adminString("select details->>'lastHash' from cp.security_log where tenant_id=? and event_type='LOG_EXPORTED'",db.tenantA));
  // Sem motivo o banco recusa (R5.7) e nada é gravado.
  assertThrows(DataAccessException.class,()->db.context(manager,db.tenantA,null,()->{
   db.jdbc.queryForObject("select set_config('app.capabilities','security.log.export',true)",String.class);
   db.jdbc.update("insert into cp.security_log(tenant_id,event_type,severity,source) values(?,'LOG_EXPORTED','INFO','MANAGEMENT')",db.tenantA);
   return null;
  }));
  assertEquals(1,count(db.tenantA,"LOG_EXPORTED"));
 }

 @Test void staffSensitiveReadsAreRecordedOnlyWithTheFlag() throws Exception {
  var enabled=new SensitiveViewRecorder(db.jdbc,true);
  var audit=new AuditRecorder(db.jdbc);
  long before=count(db.tenantA,"SENSITIVE_VIEWED");
  new AccessService(transactions,new VisitStore(db.jdbc),new AccessStore(db.jdbc),audit,SensitiveViewRecorder.disabled()).records(concierge,db.tenantA,AAL2);
  assertEquals(before,count(db.tenantA,"SENSITIVE_VIEWED"),"Flag desligada: nada gravado");
  new AccessService(transactions,new VisitStore(db.jdbc),new AccessStore(db.jdbc),audit,enabled).records(concierge,db.tenantA,AAL2);
  assertEquals(before+1,count(db.tenantA,"SENSITIVE_VIEWED"),"Histórico de acesso lido pela portaria grava SENSITIVE_VIEWED");
  assertEquals("ACCESS_HISTORY|CONCIERGE|CONCIERGE",db.adminString("select (details->>'view')||'|'||source||'|'||actor_role from cp.security_log "
   +"where tenant_id=? and event_type='SENSITIVE_VIEWED' order by seq desc limit 1",db.tenantA));
  new SubjectService(transactions,new SubjectStore(db.jdbc),audit,enabled).list(manager,db.tenantA,AAL2);
  assertEquals("PERSON_REGISTRY",db.adminString("select details->>'view' from cp.security_log where tenant_id=? and event_type='SENSITIVE_VIEWED' order by seq desc limit 1",db.tenantA));
  // Leitura recusada não grava.
  long after=count(db.tenantA,"SENSITIVE_VIEWED");
  assertThrows(BusinessProblem.class,()->new SubjectService(transactions,new SubjectStore(db.jdbc),audit,enabled).list(concierge,db.tenantA,AAL2));
  assertEquals(after,count(db.tenantA,"SENSITIVE_VIEWED"));
 }

 @Test void integrityDetectsTamperingAndLogFailureUndoesTheOperation() throws Exception {
  assertTrue(log.integrity(managerB,db.tenantB,AAL2).intact(),"Cadeia íntegra antes da adulteração");
  var occurrences=new OccurrenceService(transactions,new OccurrenceStore(db.jdbc),new AuditRecorder(db.jdbc));
  var created=occurrences.create(conciergeB,db.tenantB,AAL2,new OccurrenceModels.Create(UUID.randomUUID(),"SECURITY","Portão aberto","Encontrado aberto.",null));
  assertEquals(1,Long.parseLong(db.adminString("select count(*) from cp.security_log where tenant_id=? and event_type='OCCURRENCE_RECORDED' and subject_id=?",
   db.tenantB,created.occurrence().occurrenceId())),"R5.4: ocorrência gravada no log na mesma transação");
  assertEquals("ATTENTION",db.adminString("select severity from cp.security_log where tenant_id=? and subject_id=?",db.tenantB,created.occurrence().occurrenceId()));
  // Cabeça recuada fora da regra: o log falha no COMMIT e a ocorrência é desfeita junto.
  db.adminUpdateWithoutTriggers("update cp.security_log_heads set last_seq=last_seq-1 where tenant_id=?",db.tenantB);
  UUID failed=UUID.randomUUID();
  assertThrows(BusinessProblem.class,()->occurrences.create(conciergeB,db.tenantB,AAL2,new OccurrenceModels.Create(failed,"SECURITY","Portão aberto","Outra vez.",null)));
  assertEquals("0",db.adminString("select count(*) from cp.concierge_occurrences where tenant_id=? and id=?",db.tenantB,failed),"Falha do log desfaz a operação");
  var head=log.integrity(managerB,db.tenantB,AAL2);
  assertFalse(head.intact()); assertEquals("HEAD_MISMATCH",head.problem());
  // Alteração manual de um campo (gatilhos desligados) é detectada no evento exato.
  db.adminUpdateWithoutTriggers("update cp.security_log_heads set last_seq=last_seq+1 where tenant_id=?",db.tenantB);
  assertTrue(log.integrity(managerB,db.tenantB,AAL2).intact(),"Cabeça restaurada");
  db.adminUpdateWithoutTriggers("update cp.security_log set reason='adulterado' where tenant_id=? and seq=1",db.tenantB);
  var broken=log.integrity(managerB,db.tenantB,AAL2);
  assertFalse(broken.intact()); assertEquals("HASH_MISMATCH",broken.problem()); assertEquals(1L,broken.brokenSeq());
 }
}
