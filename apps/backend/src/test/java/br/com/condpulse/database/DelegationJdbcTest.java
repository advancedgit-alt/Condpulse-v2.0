package br.com.condpulse.database;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.parcels.application.DelegationModels;
import br.com.condpulse.parcels.application.DelegationService;
import br.com.condpulse.parcels.application.ParcelModels;
import br.com.condpulse.parcels.application.ParcelService;
import br.com.condpulse.parcels.infrastructure.DelegationStore;
import br.com.condpulse.parcels.infrastructure.ParcelStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V024 com PostgreSQL real: código só na criação, conferência pela portaria com tentativas gravadas e retirada única. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class DelegationJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static ParcelService parcels;
 private static DelegationService delegations;
 private static UUID concierge;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(2);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var parcelStore=new ParcelStore(db.jdbc);
  var audit=new AuditRecorder(db.jdbc);
  parcels=new ParcelService(transactions,parcelStore,audit);
  delegations=new DelegationService(transactions,new DelegationStore(db.jdbc),parcelStore,audit);
  concierge=UUID.randomUUID(); db.extraAccounts.add(concierge);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",concierge);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'PORTARIA DELEGACAO JDBC','CONCIERGE','ACTIVE')",
   db.tenantA,concierge);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "delegation-jdbc-"+UUID.randomUUID(); }
 private ParcelModels.Parcel receive(UUID unit) {
  return parcels.receive(concierge,db.tenantA,AAL2,key(),new ParcelModels.Receive(unit,1,"Caixa fictícia","Armário D",true)).parcel();
 }
 private DelegationModels.Issued delegate(ParcelModels.Parcel parcel,String key) {
  return delegations.create(db.accountA,db.tenantA,parcel.parcelId(),key,new DelegationModels.Create("  Terceiro   Fictício ","9z",null));
 }
 private static String wrong(String code) { return code.equals("00000000")?"11111111":"00000000"; }

 @Test void codeIsShownOnceAndTheConciergeDeliversToTheAuthorizedPerson() throws Exception {
  var parcel=receive(db.unitA);
  String createKey=key();
  var issued=delegate(parcel,createKey);
  assertEquals(201,issued.status()); assertFalse(issued.replayed());
  assertTrue(issued.code().matches("[0-9]{8}"));
  assertEquals("Terceiro Fictício",issued.delegation().delegateName()); assertEquals("9Z",issued.delegation().documentLast4());
  assertEquals("ACTIVE",issued.delegation().state()); assertEquals(5,issued.delegation().attemptsLeft());
  var replay=delegate(parcel,createKey);
  assertTrue(replay.replayed()); assertNull(replay.code(),"Repetição nunca devolve o código");
  assertEquals(issued.delegation().delegationId(),replay.delegation().delegationId());
  assertFalse(db.adminString("select response_body::text from cp.idempotency_keys where request_key=?",createKey).contains(issued.code()),
   "O código não fica gravado na resposta idempotente");
  assertNotEquals(issued.code(),db.adminString("select code_hash from cp.parcel_delegations where id=?",issued.delegation().delegationId()));
  assertEquals("1",db.adminString("select count(*) from cp.outbox where event_type='DELEGATION_CREATED' and aggregate_id=?",issued.delegation().delegationId()));

  // A portaria vê o resumo, sem código; a outra unidade não vê nada.
  var seen=delegations.current(concierge,db.tenantA,AAL2,parcel.parcelId()).delegation();
  assertEquals("Terceiro Fictício",seen.delegateName());
  assertEquals("PARCEL_NOT_FOUND",assertThrows(BusinessProblem.class,()->delegations.current(db.accountC,db.tenantA,AssuranceLevel.AAL1,parcel.parcelId())).code());

  var handoff=new DelegationModels.CodeHandoff(wrong(issued.code()),true,parcel.version());
  var refused=assertThrows(BusinessProblem.class,()->delegations.handoffByCode(concierge,db.tenantA,AAL2,parcel.parcelId(),key(),handoff));
  assertEquals("DELEGATION_CODE_INVALID",refused.code());
  assertEquals("1",db.adminString("select attempts from cp.parcel_delegations where id=?",issued.delegation().delegationId()),
   "A tentativa errada fica gravada mesmo com a recusa");
  String handoffKey=key();
  var delivered=delegations.handoffByCode(concierge,db.tenantA,AAL2,parcel.parcelId(),handoffKey,
   new DelegationModels.CodeHandoff(issued.code(),true,parcel.version()));
  assertEquals("DELIVERED",delivered.parcel().status());
  assertEquals("DELEGATION",delivered.parcel().handoff().evidenceKind());
  assertEquals("Terceiro Fictício",delivered.parcel().handoff().recipientName());
  var again=delegations.handoffByCode(concierge,db.tenantA,AAL2,parcel.parcelId(),handoffKey,
   new DelegationModels.CodeHandoff(issued.code(),true,parcel.version()));
  assertTrue(again.replayed()); assertEquals(delivered.parcel(),again.parcel());
  assertEquals("PARCEL_ALREADY_DELIVERED",assertThrows(BusinessProblem.class,()->delegations.handoffByCode(concierge,db.tenantA,AAL2,parcel.parcelId(),key(),
   new DelegationModels.CodeHandoff(issued.code(),true,delivered.parcel().version()))).code(),"Código usado não volta a valer");
  assertEquals("USED",delegations.current(db.accountA,db.tenantA,AssuranceLevel.AAL1,parcel.parcelId()).delegation().state());
  db.assertEmptyContext();
 }

 @Test void fiveWrongCodesLockAndOtherParcelsCodeIsNotAccepted() throws Exception {
  var first=receive(db.unitA);
  var second=receive(db.unitA);
  var issued=delegate(first,key());
  assertEquals("DELEGATION_NOT_FOUND",assertThrows(BusinessProblem.class,()->delegations.handoffByCode(concierge,db.tenantA,AAL2,second.parcelId(),key(),
   new DelegationModels.CodeHandoff(issued.code(),true,second.version()))).code(),"Código de outra encomenda não vale");
  for(int attempt=1;attempt<=4;attempt++)
   assertEquals("DELEGATION_CODE_INVALID",assertThrows(BusinessProblem.class,()->delegations.handoffByCode(concierge,db.tenantA,AAL2,first.parcelId(),key(),
    new DelegationModels.CodeHandoff(wrong(issued.code()),true,first.version()))).code());
  assertEquals("DELEGATION_LOCKED",assertThrows(BusinessProblem.class,()->delegations.handoffByCode(concierge,db.tenantA,AAL2,first.parcelId(),key(),
   new DelegationModels.CodeHandoff(wrong(issued.code()),true,first.version()))).code());
  assertEquals("DELEGATION_NOT_FOUND",assertThrows(BusinessProblem.class,()->delegations.handoffByCode(concierge,db.tenantA,AAL2,first.parcelId(),key(),
   new DelegationModels.CodeHandoff(issued.code(),true,first.version()))).code(),"Bloqueada não aceita nem o código certo");
  assertEquals("LOCKED",delegations.current(db.accountA,db.tenantA,AssuranceLevel.AAL1,first.parcelId()).delegation().state());
  assertEquals("RECEIVED",db.adminString("select status from cp.parcels where id=?",first.parcelId()));
  // Nova autorização substitui a anterior e volta a valer.
  var renewed=delegate(first,key());
  assertEquals("ACTIVE",renewed.delegation().state());
  assertEquals("DELIVERED",delegations.handoffByCode(concierge,db.tenantA,AAL2,first.parcelId(),key(),
   new DelegationModels.CodeHandoff(renewed.code(),true,first.version())).parcel().status());
  db.assertEmptyContext();
 }

 @Test void revocationReplacementAndInvalidInputs() throws Exception {
  var parcel=receive(db.unitA);
  var first=delegate(parcel,key());
  var second=delegate(parcel,key());
  assertEquals("CLOSED",db.adminString("select state from cp.parcel_delegations where id=?",first.delegation().delegationId()),
   "Nova autorização encerra a anterior");
  // O código antigo não vale mais: só a nova autorização está em vigor (tentativa contada nela).
  org.junit.jupiter.api.Assumptions.assumeFalse(first.code().equals(second.code()));
  assertEquals("DELEGATION_CODE_INVALID",assertThrows(BusinessProblem.class,()->delegations.handoffByCode(concierge,db.tenantA,AAL2,parcel.parcelId(),key(),
   new DelegationModels.CodeHandoff(first.code(),true,parcel.version()))).code());
  assertEquals("DELEGATION_CHANGED",assertThrows(BusinessProblem.class,()->delegations.revoke(db.accountA,db.tenantA,parcel.parcelId(),
   new DelegationModels.Revoke(second.delegation().delegationId(),second.delegation().version()+3))).code());
  var current=delegations.current(db.accountA,db.tenantA,AssuranceLevel.AAL1,parcel.parcelId()).delegation();
  var revoked=delegations.revoke(db.accountA,db.tenantA,parcel.parcelId(),new DelegationModels.Revoke(current.delegationId(),current.version()));
  assertEquals("REVOKED",revoked.state());
  assertEquals("DELEGATION_NOT_ACTIVE",assertThrows(BusinessProblem.class,()->delegations.revoke(db.accountA,db.tenantA,parcel.parcelId(),
   new DelegationModels.Revoke(revoked.delegationId(),revoked.version()))).code());
  assertEquals("DELEGATION_VALIDITY_INVALID",assertThrows(BusinessProblem.class,()->delegations.create(db.accountA,db.tenantA,parcel.parcelId(),key(),
   new DelegationModels.Create("Terceiro",null,java.time.Instant.now().plusSeconds(8L*86400)))).code());
  assertEquals("DELEGATION_DOCUMENT_INVALID",assertThrows(BusinessProblem.class,()->delegations.create(db.accountA,db.tenantA,parcel.parcelId(),key(),
   new DelegationModels.Create("Terceiro","123456",null))).code());
  assertEquals("PARCEL_NOT_FOUND",assertThrows(BusinessProblem.class,()->delegations.create(db.accountC,db.tenantA,parcel.parcelId(),key(),
   new DelegationModels.Create("Terceiro",null,null))).code(),"Outra unidade não autoriza");
  assertEquals("PARCEL_IDENTITY_CHECK_REQUIRED",assertThrows(BusinessProblem.class,()->delegations.handoffByCode(concierge,db.tenantA,AAL2,parcel.parcelId(),key(),
   new DelegationModels.CodeHandoff("12345678",false,parcel.version()))).code());
  db.assertEmptyContext();
 }

 @Test void staleConfirmationDoesNotRevokeTheReplacement() throws Exception {
  var parcel=receive(db.unitA);
  var shown=delegate(parcel,key()).delegation();
  // Outra sessão gera nova autorização, também na versão 0.
  var replacement=delegate(parcel,key()).delegation();
  assertEquals(shown.version(),replacement.version());
  assertEquals("DELEGATION_CHANGED",assertThrows(BusinessProblem.class,()->delegations.revoke(db.accountA,db.tenantA,parcel.parcelId(),
   new DelegationModels.Revoke(shown.delegationId(),shown.version()))).code(),"Cancelar a autorização exibida não cancela a substituta");
  assertEquals("ACTIVE",db.adminString("select state from cp.parcel_delegations where id=?",replacement.delegationId()));
  assertEquals("DELEGATION_VERSION_REQUIRED",assertThrows(BusinessProblem.class,()->delegations.revoke(db.accountA,db.tenantA,parcel.parcelId(),
   new DelegationModels.Revoke(null,replacement.version()))).code());
  assertEquals("REVOKED",delegations.revoke(db.accountA,db.tenantA,parcel.parcelId(),
   new DelegationModels.Revoke(replacement.delegationId(),replacement.version())).state());
  db.assertEmptyContext();
 }

 @Test void simultaneousRepeatOfTheSameHandoffKeyReplaysTheDelivery() throws Exception {
  var parcel=receive(db.unitA);
  var issued=delegate(parcel,key());
  String handoffKey=key();
  var request=new DelegationModels.CodeHandoff(issued.code(),true,parcel.version());
  var pool=Executors.newFixedThreadPool(2);
  try(var holder=db.adminSession()) {
   // A encomenda fica travada enquanto as duas repetições passam pela conferência da chave (ainda inexistente).
   holder.setAutoCommit(false);
   try(var lock=holder.prepareStatement("select id from cp.parcels where id=? for update")) { lock.setObject(1,parcel.parcelId()); lock.executeQuery(); }
   var tasks=new ArrayList<Callable<String>>();
   for(int attempt=0;attempt<2;attempt++)
    tasks.add(()->{
     try {
      var result=delegations.handoffByCode(concierge,db.tenantA,AAL2,parcel.parcelId(),handoffKey,request);
      return result.replayed()?"REPLAY":"OK";
     } catch(BusinessProblem problem) { return problem.code(); }
    });
   var futures=tasks.stream().map(pool::submit).toList();
   Thread.sleep(1500);
   holder.commit();
   var results=new ArrayList<String>();
   for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
   results.sort(null);
   assertEquals(List.of("OK","REPLAY"),results,"A mesma operação repetida devolve a retirada feita, não \"já retirada\"");
  } finally { pool.shutdownNow(); }
  assertEquals("1",db.adminString("select count(*) from cp.parcel_handoffs where parcel_id=?",parcel.parcelId()));
  db.assertEmptyContext();
 }
}
