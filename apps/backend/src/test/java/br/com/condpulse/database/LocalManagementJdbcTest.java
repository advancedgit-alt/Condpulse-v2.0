package br.com.condpulse.database;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.audit.application.AuditQuery;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.reservations.application.AmenityManagementModels;
import br.com.condpulse.reservations.application.AmenityManagementService;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
import br.com.condpulse.reservations.infrastructure.AmenityManagementStore;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.tenancy.application.UnitManagementModels;
import br.com.condpulse.tenancy.application.UnitManagementService;
import br.com.condpulse.tenancy.infrastructure.UnitManagementStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/** Gestão local com PostgreSQL real: papel + AAL2 no serviço e capacidade como segunda barreira RLS. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class LocalManagementJdbcTest {
 private static JdbcDisposableDatabase db;
 private static TenantTransactionExecutor transactions;
 private static UnitManagementService units;
 private static AmenityManagementService amenities;
 private static AuditQuery audit;
 private static ReservationService reservations;
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2,AAL1=AssuranceLevel.AAL1;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var recorder=new AuditRecorder(db.jdbc);
  units=new UnitManagementService(transactions,new UnitManagementStore(db.jdbc),recorder);
  amenities=new AmenityManagementService(transactions,new AmenityManagementStore(db.jdbc),recorder);
  audit=new AuditQuery(transactions,db.jdbc);
  reservations=new ReservationService(transactions,new ReservationStore(db.jdbc));
  // Conta C: moradora em A e gestora de A (sem unidade). Gestão nunca é concedida por conveniência.
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTOR FICTICIO JDBC','MANAGER','ACTIVE')",
   db.tenantA,db.accountC);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String label() { return "JDBC "+UUID.randomUUID().toString().substring(0,8); }
 private static AmenityManagementModels.PolicyInput policy(int maximumActive) {
  return new AmenityManagementModels.PolicyInput(1,List.of(1,2,3,4,5,6,7),"06:00","23:59",0,366,600,maximumActive,0);
 }
 private static AmenityManagementModels.AmenityInput area(String name,int maximumActive,Long expectedVersion) {
  return new AmenityManagementModels.AmenityInput(name,10,false,2500L,15,true,policy(maximumActive),expectedVersion,null);
 }

 @Test void aal1ManagerAndAal2ResidentChangeNothing() throws Exception {
  long before=db.businessCount("audit_log");
  var weak=assertThrows(BusinessProblem.class,()->units.createUnit(db.accountC,db.tenantA,AAL1,
   new UnitManagementModels.CreateUnit(label(),null)));
  assertEquals("MFA_REQUIRED",weak.code()); assertEquals(403,weak.status());
  var resident=assertThrows(BusinessProblem.class,()->units.createUnit(db.accountA,db.tenantA,AAL2,
   new UnitManagementModels.CreateUnit(label(),null)));
  assertEquals("STAFF_ACCESS_DENIED",resident.code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->amenities.amenities(db.accountA,db.tenantA,AAL2)).code());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->units.units(db.accountC,db.tenantB,AAL2)).code());
  assertEquals(before,db.businessCount("audit_log")); db.assertEmptyContext();
 }

 @Test void databaseRejectsManagementWriteWithoutPublishedCapability() {
  var store=new UnitManagementStore(db.jdbc);
  var error=assertThrows(DataAccessException.class,()->transactions.execute(db.accountC,db.tenantA,AAL1,
   context->store.insertUnit(db.tenantA,label(),null)));
  assertEquals("42501",SqlStates.of(error)); db.assertEmptyContext();
 }

 @Test void managerCreatesBlocksAndUnitsOnlyInsideOwnTenantWithAudit() throws Exception {
  var block=units.createBlock(db.accountC,db.tenantA,AAL2,new UnitManagementModels.CreateBlock("  Torre   "+label()+" "));
  assertFalse(block.name().contains("  "));
  String unitLabel=label();
  var unit=units.createUnit(db.accountC,db.tenantA,AAL2,new UnitManagementModels.CreateUnit(unitLabel,block.blockId()));
  assertEquals("ACTIVE",unit.status()); assertEquals(block.blockId(),unit.blockId()); assertEquals(0,unit.version());
  assertEquals("UNIT_LABEL_TAKEN",assertThrows(BusinessProblem.class,()->units.createUnit(db.accountC,db.tenantA,AAL2,
   new UnitManagementModels.CreateUnit(unitLabel,block.blockId()))).code());
  assertEquals("BLOCK_NAME_TAKEN",assertThrows(BusinessProblem.class,()->units.createBlock(db.accountC,db.tenantA,AAL2,
   new UnitManagementModels.CreateBlock(block.name()))).code());
  var page=units.units(db.accountC,db.tenantA,AAL2);
  assertTrue(page.units().stream().anyMatch(u->u.unitId().equals(unit.unitId())));
  assertTrue(page.units().stream().anyMatch(u->u.unitId().equals(db.unitA)));
  assertTrue(page.units().stream().noneMatch(u->u.unitId().equals(db.unitB)),"Unidade de outro tenant nunca aparece");
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where tenant_id=? and resource_id=? and action='UNIT_CREATED' and actor_id=?",
   db.tenantA,unit.unitId(),db.accountC));
  db.assertEmptyContext();
 }

 @Test void foreignUuidsAreNotFoundAndStaleVersionsConflict() throws Exception {
  var foreignBlock=units.createBlock(db.accountC,db.tenantA,AAL2,new UnitManagementModels.CreateBlock(label()));
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTOR B','MANAGER','ACTIVE') on conflict do nothing",
   db.tenantB,db.accountB);
  try {
   assertEquals("BLOCK_NOT_FOUND",assertThrows(BusinessProblem.class,()->units.createUnit(db.accountB,db.tenantB,AAL2,
    new UnitManagementModels.CreateUnit(label(),foreignBlock.blockId()))).code());
   assertEquals("UNIT_NOT_FOUND",assertThrows(BusinessProblem.class,()->units.updateUnit(db.accountB,db.tenantB,AAL2,db.unitA,
    new UnitManagementModels.UpdateUnit("INVASAO",null,"INACTIVE",0L,null))).code());
  } finally {
   db.adminUpdate("delete from cp.memberships where tenant_id=? and account_id=? and role='MANAGER'",db.tenantB,db.accountB);
  }
  var created=units.createUnit(db.accountC,db.tenantA,AAL2,new UnitManagementModels.CreateUnit(label(),null));
  assertEquals("UNIT_CHANGED",assertThrows(BusinessProblem.class,()->units.updateUnit(db.accountC,db.tenantA,AAL2,created.unitId(),
   new UnitManagementModels.UpdateUnit(created.label(),null,"ACTIVE",created.version()+1,null))).code());
  var renamed=units.updateUnit(db.accountC,db.tenantA,AAL2,created.unitId(),
   new UnitManagementModels.UpdateUnit(created.label()+" B",null,"ACTIVE",created.version(),"Correção de identificação"));
  assertEquals(created.version()+1,renamed.version());
  assertEquals("UNIT_CHANGED",assertThrows(BusinessProblem.class,()->units.updateUnit(db.accountC,db.tenantA,AAL2,created.unitId(),
   new UnitManagementModels.UpdateUnit(created.label(),null,"ACTIVE",created.version(),null))).code());
  db.assertEmptyContext();
 }

 @Test void deactivatingUnitImmediatelyBlocksItsResidents() throws Exception {
  var current=units.units(db.accountC,db.tenantA,AAL2).units().stream().filter(u->u.unitId().equals(db.unitA)).findFirst().orElseThrow();
  assertEquals(db.tenantA,transactions.execute(db.accountA,db.tenantA,context->context.tenantId()));
  units.updateUnit(db.accountC,db.tenantA,AAL2,db.unitA,
   new UnitManagementModels.UpdateUnit(current.label(),current.blockId(),"INACTIVE",current.version(),"Unidade em reforma"));
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->transactions.execute(db.accountA,db.tenantA,context->context.tenantId())).code());
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='UNIT_DEACTIVATED' and reason='Unidade em reforma'",db.unitA));
  db.assertEmptyContext();
 }

 @Test void amenityRuleChangesCreateNewPolicyVersionWithoutRewritingReservations() throws Exception {
  db.clearReservationFixtures();
  var created=amenities.create(db.accountC,db.tenantA,AAL2,area("Salao "+label(),1,null));
  assertEquals(1,created.policyVersion()); assertEquals("CONFIGURED",created.policyState());
  assertEquals("23:59",created.policy().closesAt()); assertEquals(List.of(1,2,3,4,5,6,7),created.policy().weekDays());
  ZoneId zone=ZoneId.of("America/Sao_Paulo");
  Instant start=LocalDate.now(zone).plusDays(2).atTime(12,0).atZone(zone).toInstant();
  var booked=reservations.create(db.accountA,db.tenantA,"jdbc-management:"+UUID.randomUUID(),
   new ReservationModels.Create(db.unitA,created.amenityId(),start,start.plusSeconds(3600),1));
  assertEquals("CONFIRMED",booked.reservation().status());
  var renamed=amenities.update(db.accountC,db.tenantA,AAL2,created.amenityId(),area(created.name()+" Norte",1,created.version()));
  assertEquals(1,renamed.policyVersion(),"Nome não muda a versão da regra");
  var stricter=amenities.update(db.accountC,db.tenantA,AAL2,created.amenityId(),area(renamed.name(),2,renamed.version()));
  assertEquals(2,stricter.policyVersion());
  assertEquals("1",db.adminString("select rule_snapshot->>'policyVersion' from cp.reservations where id=?",booked.reservation().reservationId()));
  assertEquals("AMENITY_CHANGED",assertThrows(BusinessProblem.class,()->amenities.update(db.accountC,db.tenantA,AAL2,
   created.amenityId(),area(renamed.name(),3,renamed.version()))).code());
  assertEquals("AMENITY_POLICY_CHANGED",assertThrows(BusinessProblem.class,()->reservations.create(db.accountA,db.tenantA,
   "jdbc-management:"+UUID.randomUUID(),new ReservationModels.Create(db.unitA,created.amenityId(),start.plusSeconds(7200),
   start.plusSeconds(10800),1))).code(),"Aceite antigo da regra não cria reserva");
  assertEquals("AMENITY_NAME_TAKEN",assertThrows(BusinessProblem.class,()->amenities.create(db.accountC,db.tenantA,AAL2,
   area(" "+stricter.name().toUpperCase()+" ",1,null))).code());
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='AMENITY_POLICY_VERSIONED'",created.amenityId()));
  db.assertEmptyContext();
 }

 @Test void invalidRulesAreRejectedBeforeTheDatabase() {
  var invalid=new AmenityManagementModels.AmenityInput("Quadra",10,false,0L,0,true,
   new AmenityManagementModels.PolicyInput(1,List.of(1,1),"06:00","22:00",0,30,60,1,0),null,null);
  assertEquals("AMENITY_POLICY_INVALID",assertThrows(BusinessProblem.class,
   ()->amenities.create(db.accountC,db.tenantA,AAL2,invalid)).code());
  var closed=new AmenityManagementModels.AmenityInput("Quadra",10,false,0L,0,true,
   new AmenityManagementModels.PolicyInput(1,List.of(1),"22:00","06:00",0,30,60,1,0),null,null);
  assertEquals("AMENITY_POLICY_INVALID",assertThrows(BusinessProblem.class,
   ()->amenities.create(db.accountC,db.tenantA,AAL2,closed)).code());
  db.assertEmptyContext();
 }

 @Test void auditIsReadableOnlyWithCapabilityAndPaginatesWithinTenant() throws Exception {
  for(int i=0;i<3;i++) units.createUnit(db.accountC,db.tenantA,AAL2,new UnitManagementModels.CreateUnit(label(),null));
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->audit.page(db.accountA,db.tenantA,AAL2,null,10)).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->audit.page(db.accountC,db.tenantA,AAL1,null,10)).code());
  var first=audit.page(db.accountC,db.tenantA,AAL2,null,2);
  assertEquals(2,first.events().size()); assertNotNull(first.nextCursor());
  var second=audit.page(db.accountC,db.tenantA,AAL2,first.nextCursor(),2);
  assertFalse(second.events().isEmpty());
  assertTrue(second.events().stream().noneMatch(e->first.events().stream().anyMatch(f->f.eventId().equals(e.eventId()))));
  assertTrue(first.events().getLast().createdAt().compareTo(second.events().getFirst().createdAt())>=0);
  assertEquals("AUDIT_CURSOR_INVALID",assertThrows(BusinessProblem.class,()->audit.page(db.accountC,db.tenantA,AAL2,"invalid*cursor",2)).code());
  db.assertEmptyContext();
 }
}
