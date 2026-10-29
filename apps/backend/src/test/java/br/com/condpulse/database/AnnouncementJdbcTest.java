package br.com.condpulse.database;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.announcements.application.AnnouncementModels;
import br.com.condpulse.announcements.application.AnnouncementService;
import br.com.condpulse.announcements.infrastructure.AnnouncementStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V016 com PostgreSQL real: rascunho invisível à unidade, público por condomínio/unidade, abertura única e arquivamento. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AnnouncementJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static AnnouncementService announcements;
 private static UUID manager;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  announcements=new AnnouncementService(new TenantTransactionExecutor(db.jdbc,db.manager),new AnnouncementStore(db.jdbc),new AuditRecorder(db.jdbc));
  manager=UUID.randomUUID(); db.extraAccounts.add(manager);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",manager);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO AVISOS JDBC','MANAGER','ACTIVE')",
   db.tenantA,manager);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "announce-jdbc-"+UUID.randomUUID(); }
 private static AnnouncementModels.ManagedAnnouncement draft(String title,String audience,List<UUID> ids) {
  return announcements.createDraft(manager,db.tenantA,AAL2,key(),new AnnouncementModels.Draft(title,"Texto fictício do aviso.",audience,ids)).announcement();
 }
 private static List<UUID> unitList(UUID account,UUID unit) {
  return announcements.unitAnnouncements(account,db.tenantA,unit).announcements().stream().map(AnnouncementModels.UnitAnnouncement::announcementId).toList();
 }

 @Test void draftIsInvisibleToTheUnitUntilPublishedAndOpeningIsRecordedOnce() throws Exception {
  String key=key();
  var request=new AnnouncementModels.Draft("  Assembleia   fictícia ","Pauta:\r\n\r\n\r\n- obras\n",  "TENANT",List.of());
  var first=announcements.createDraft(manager,db.tenantA,AAL2,key,request);
  var replay=announcements.createDraft(manager,db.tenantA,AAL2,key,request);
  assertFalse(first.replayed()); assertTrue(replay.replayed()); assertEquals(first.announcement(),replay.announcement());
  var created=first.announcement();
  assertEquals("Assembleia fictícia",created.title()); assertEquals("Pauta:\n\n- obras",created.body());
  assertEquals("DRAFT",created.state()); assertEquals(0,created.version()); assertTrue(created.authoredByMe());
  assertEquals("1",db.adminString("select count(*) from cp.announcements where tenant_id=?",db.tenantA),"Repetição não duplica o rascunho");
  // AC-029: rascunho não aparece nem abre pelo ID.
  assertTrue(unitList(db.accountA,db.unitA).isEmpty());
  assertEquals("ANNOUNCEMENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->announcements.open(db.accountA,db.tenantA,created.announcementId())).code());
  assertEquals("0",db.adminString("select count(*) from cp.announcement_reads where announcement_id=?",created.announcementId()));
  var edited=announcements.editDraft(manager,db.tenantA,AAL2,created.announcementId(),
   new AnnouncementModels.EditDraft("Assembleia fictícia geral","Pauta: obras e orçamento.","TENANT",List.of(),0L));
  assertEquals(1,edited.version());
  assertEquals("ANNOUNCEMENT_CHANGED",assertThrows(BusinessProblem.class,()->announcements.publish(manager,db.tenantA,AAL2,created.announcementId(),
   new AnnouncementModels.Publish(0L))).code(),"Publicação pela versão lida");
  var published=announcements.publish(manager,db.tenantA,AAL2,created.announcementId(),new AnnouncementModels.Publish(1L));
  assertEquals("PUBLISHED",published.state()); assertNotNull(published.publishedAt()); assertEquals(2,published.version());
  assertEquals("ANNOUNCEMENT_NOT_DRAFT",assertThrows(BusinessProblem.class,()->announcements.editDraft(manager,db.tenantA,AAL2,created.announcementId(),
   new AnnouncementModels.EditDraft("Outro título","Outro texto","TENANT",List.of(),2L))).code(),"Publicado não muda de texto");
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='ANNOUNCEMENT_PUBLISHED'",created.announcementId()));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='ANNOUNCEMENT_PUBLISHED' and actor_id=?",
   created.announcementId(),manager));
  // A lista da unidade não traz o texto; a abertura traz e registra uma vez.
  var listed=announcements.unitAnnouncements(db.accountA,db.tenantA,db.unitA).announcements();
  assertEquals(List.of(created.announcementId()),listed.stream().map(AnnouncementModels.UnitAnnouncement::announcementId).toList());
  assertNull(listed.getFirst().openedAt());
  var opened=announcements.open(db.accountA,db.tenantA,created.announcementId());
  assertEquals("Pauta: obras e orçamento.",opened.body()); assertNotNull(opened.openedAt());
  assertEquals(opened.openedAt(),announcements.open(db.accountA,db.tenantA,created.announcementId()).openedAt(),"Repetir a abertura mantém o horário");
  assertEquals(opened.openedAt(),announcements.unitAnnouncements(db.accountA,db.tenantA,db.unitA).announcements().getFirst().openedAt());
  assertEquals(1,announcements.managed(manager,db.tenantA,AAL2).announcements().getFirst().opens());
  // Outro condomínio e a gestão sem segundo fator não alcançam.
  assertEquals("ANNOUNCEMENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->announcements.open(db.accountB,db.tenantB,created.announcementId())).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->announcements.managed(manager,db.tenantA,AssuranceLevel.AAL1)).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->announcements.managed(db.accountA,db.tenantA,AAL2)).code());
  // Arquivado sai da unidade; a abertura continua registrada.
  var archived=announcements.archive(manager,db.tenantA,AAL2,created.announcementId(),new AnnouncementModels.Archive(2L,"Assembleia realizada"));
  assertEquals("ARCHIVED",archived.state()); assertNotNull(archived.archivedAt());
  assertTrue(unitList(db.accountA,db.unitA).isEmpty());
  assertEquals("ANNOUNCEMENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->announcements.open(db.accountA,db.tenantA,created.announcementId())).code());
  assertEquals("1",db.adminString("select count(*) from cp.announcement_reads where announcement_id=?",created.announcementId()));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='ANNOUNCEMENT_ARCHIVED' and reason='Assembleia realizada'",
   created.announcementId()));
  db.assertEmptyContext();
 }
 @Test void audienceByUnitReachesOnlyThatUnitAndTargetsMustBelongToTheTenant() throws Exception {
  var directed=draft("Aviso da unidade","UNITS",List.of(db.unitA));
  announcements.publish(manager,db.tenantA,AAL2,directed.announcementId(),new AnnouncementModels.Publish(0L));
  assertEquals(List.of(directed.announcementId()),unitList(db.accountA,db.unitA));
  assertTrue(unitList(db.accountC,db.otherUnitA).isEmpty(),"Outra unidade do condomínio não vê aviso dirigido");
  assertEquals("ANNOUNCEMENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->announcements.open(db.accountC,db.tenantA,directed.announcementId())).code());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->announcements.unitAnnouncements(db.accountC,db.tenantA,db.unitA)).code());
  var everyone=draft("Aviso geral","TENANT",List.of());
  announcements.publish(manager,db.tenantA,AAL2,everyone.announcementId(),new AnnouncementModels.Publish(0L));
  assertEquals(List.of(everyone.announcementId()),unitList(db.accountC,db.otherUnitA));
  assertEquals("ANNOUNCEMENT_TARGETS_INVALID",assertThrows(BusinessProblem.class,()->draft("Alvo de outro condomínio","UNITS",List.of(db.unitB))).code());
  assertEquals("ANNOUNCEMENT_AUDIENCE_INVALID",assertThrows(BusinessProblem.class,()->draft("Lista no condomínio inteiro","TENANT",List.of(db.unitA))).code());
  assertEquals("ANNOUNCEMENT_AUDIENCE_INVALID",assertThrows(BusinessProblem.class,()->draft("Unidade repetida","UNITS",List.of(db.unitA,db.unitA))).code());
  assertEquals("ANNOUNCEMENT_TITLE_INVALID",assertThrows(BusinessProblem.class,()->draft("Oi","TENANT",List.of())).code());
  assertEquals("ANNOUNCEMENT_BODY_INVALID",assertThrows(BusinessProblem.class,()->announcements.createDraft(manager,db.tenantA,AAL2,key(),
   new AnnouncementModels.Draft("Aviso invisível","Texto​escondido","TENANT",List.of()))).code());
  // Vínculo encerrado deixa de ver o aviso.
  db.adminUpdate("update cp.memberships set status='REVOKED' where tenant_id=? and account_id=?",db.tenantA,db.accountC);
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->announcements.unitAnnouncements(db.accountC,db.tenantA,db.otherUnitA)).code());
  assertEquals("ANNOUNCEMENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->announcements.open(db.accountC,db.tenantA,everyone.announcementId())).code());
  db.assertEmptyContext();
 }
 @Test void discardedDraftIsArchivedWithoutPublication() throws Exception {
  var discarded=draft("Rascunho descartado","TENANT",List.of());
  var archived=announcements.archive(manager,db.tenantA,AAL2,discarded.announcementId(),new AnnouncementModels.Archive(0L,null));
  assertEquals("ARCHIVED",archived.state()); assertNull(archived.publishedAt());
  assertEquals("ANNOUNCEMENT_ARCHIVED",assertThrows(BusinessProblem.class,()->announcements.archive(manager,db.tenantA,AAL2,discarded.announcementId(),
   new AnnouncementModels.Archive(1L,null))).code());
  assertEquals("ANNOUNCEMENT_NOT_DRAFT",assertThrows(BusinessProblem.class,()->announcements.publish(manager,db.tenantA,AAL2,discarded.announcementId(),
   new AnnouncementModels.Publish(1L))).code());
  assertEquals("0",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='ANNOUNCEMENT_PUBLISHED'",discarded.announcementId()));
  db.assertEmptyContext();
 }
}
