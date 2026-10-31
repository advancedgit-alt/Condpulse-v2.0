package br.com.condpulse.database;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.accessage.application.BirthDateCipher;
import br.com.condpulse.accessage.infrastructure.AgeStore;
import br.com.condpulse.accessgrants.application.GrantModels;
import br.com.condpulse.accessgrants.application.GrantService;
import br.com.condpulse.accessgrants.application.PublicationModels;
import br.com.condpulse.accessgrants.application.PublicationService;
import br.com.condpulse.accessgrants.infrastructure.GrantStore;
import br.com.condpulse.accessgrants.infrastructure.PublicationStore;
import br.com.condpulse.accesspolicy.application.PolicyModels;
import br.com.condpulse.accesspolicy.application.PolicyService;
import br.com.condpulse.accesspolicy.infrastructure.PolicyStore;
import br.com.condpulse.accesssubjects.application.SubjectModels;
import br.com.condpulse.accesssubjects.application.SubjectService;
import br.com.condpulse.accesssubjects.infrastructure.SubjectStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V044 com PostgreSQL real e o login da API, tudo pelos fluxos reais (instalação, política aprovada, sujeitos materializados
 * pela gestão): derivação, repetição sem duplicar, simulação com sujeito real, bloqueio que revoga direito e cartão,
 * reativação em geração nova e revogação manual que vence a derivação seguinte.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessGrantJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID(),MASTER=UUID.randomUUID(),CONCIERGE=UUID.randomUUID();
 private static final List<String> APPROVED=List.of("faceEnrollment","faceDeletion","perDoorRights","secureTransport","offlineRestriction","localValidity");
 private static JdbcDisposableDatabase db;
 private static PolicyService policies;
 private static GrantService grants;
 private static PublicationService publications;
 private static PublicationStore workerPublications;
 private static WorkerTransactions worker;
 private static JdbcWorkerPool workerDb;
 private static SubjectService subjects;
 private static FacialInstallationFixture fixture;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  var tenants=new TenantTransactionExecutor(db.jdbc,db.manager);
  var audit=new AuditRecorder(db.jdbc);
  var policyStore=new PolicyStore(db.jdbc);
  var grantStore=new GrantStore(db.jdbc);
  publications=new PublicationService(tenants,new PublicationStore(db.jdbc),grantStore,audit);
  grants=new GrantService(tenants,grantStore,policyStore,new AgeStore(db.jdbc),BirthDateCipher.fromBase64("","v1"),audit,publications);
  policies=new PolicyService(tenants,policyStore,audit,grants);
  subjects=new SubjectService(tenants,new SubjectStore(db.jdbc),audit);
  fixture=new FacialInstallationFixture(db);
  workerDb=new JdbcWorkerPool();
  worker=new WorkerTransactions(workerDb.jdbc,workerDb.manager);
  workerPublications=new PublicationStore(workerDb.jdbc);
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(MASTER); db.extraAccounts.add(CONCIERGE);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,MASTER,CONCIERGE);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'PORTARIA DIREITOS JDBC','CONCIERGE','ACTIVE')",db.tenantA,CONCIERGE);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO DIREITOS JDBC','MANAGER','ACTIVE')",db.tenantA,MANAGER);
 }
 @BeforeEach void reset() throws Exception {
  fixture.clear(); db.restoreActiveFixtures(); db.assertEmptyContext();
  fixture.grantMaster(MASTER);
 }
 @AfterEach void clear() throws Exception { fixture.clear(); }
 @AfterAll static void cleanup() throws Exception { try { if(workerDb!=null) workerDb.close(); } finally { if(db!=null) db.close(); } }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static PolicyModels.PolicyDocument rule(String zone,UUID area) { return rule(zone,area,List.of("RESIDENT"),"DENY","NOT_REQUIRED"); }
 /** Tempo integral: o documento por destino (V046) ainda não representa janelas semanais. */
 private static PolicyModels.PolicyDocument rule(String zone,UUID area,List<String> contexts,String visitors,String reservation) {
  return rule(zone,area,contexts,visitors,reservation,"00:00","24:00");
 }
 private static PolicyModels.PolicyDocument rule(String zone,UUID area,List<String> contexts,String visitors,String reservation,String start,String end) {
  return new PolicyModels.PolicyDocument("1.0","Academia moradores",area,zone,"ALL",contexts,visitors,null,true,
   List.of(new PolicyModels.WeeklyWindow(List.of("MON","TUE","WED","THU","FRI","SAT","SUN"),start,end,false)),List.of(),"HOLIDAY_OVERRIDES_WEEKLY",
   new PolicyModels.ReservationRule(reservation,600,900),new PolicyModels.CompanionRule("NONE",0),
   new PolicyModels.OfflineRule("SAFE_LOCAL_SUBSET",300,true),new PolicyModels.FailureRule("DENY","DENY"),"OFF",null,true,null,"DENY");
 }
 private static SubjectModels.Subject subjectOf(UUID account) {
  return subjects.list(MANAGER,db.tenantA,AAL2).subjects().stream().filter(s->account.equals(s.accountId())).findFirst().orElseThrow();
 }
 private static List<GrantModels.Grant> grantsOf(UUID subject) {
  return grants.grants(MANAGER,db.tenantA,AAL2,subject,null).grants();
 }

 @Test void derivedRightsFollowOriginsAndRevocationAlwaysWins() throws Exception {
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,"4.4.1");
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID policyId=UUID.randomUUID(),versionId=UUID.randomUUID();
  policies.createPolicy(MANAGER,db.tenantA,AAL2,new PolicyModels.CreatePolicy(policyId,inst.area(),"Academia"));
  policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(versionId,rule(zone,inst.area()),"Regra de direitos ficticia"));
  var derive=new GrantModels.DeriveRequest(versionId);
  assertEquals("GRANT_VERSION_NOT_APPROVED",code(()->grants.derive(MANAGER,db.tenantA,AAL2,policyId,derive)),"Rascunho não gera direito");
  assertEquals("VALIDATED",policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(versionId)).status());
  long v=policies.versions(MANAGER,db.tenantA,AAL2,policyId).versions().getFirst().version();
  policies.approve(MANAGER,db.tenantA,AAL2,policyId,versionId,new PolicyModels.VersionAction(v));
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);

  assertEquals("MFA_REQUIRED",code(()->grants.derive(MANAGER,db.tenantA,AAL1,policyId,derive)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->grants.derive(db.accountA,db.tenantA,AAL2,policyId,derive)),"Morador não deriva");
  var first=grants.derive(MANAGER,db.tenantA,AAL2,policyId,derive);
  assertEquals(2,first.created(),first.toString());
  var again=grants.derive(MANAGER,db.tenantA,AAL2,policyId,derive);
  assertEquals(0,again.created()); assertEquals(2,again.unchanged(),"Mesmos fatos: nada duplicado");

  var a=subjectOf(db.accountA);
  UUID contextA=a.eligibleContextIds().getFirst();
  var grantA=grantsOf(a.subjectId()).getFirst();
  assertTrue(grantA.current()); assertEquals("MEMBERSHIP",grantA.originKind());
  assertTrue(grantA.validUntil().isBefore(Instant.now().plus(java.time.Duration.ofDays(31))),"Direito derivado nunca é infinito");

  // Simulação com o sujeito real: mesma regra, mais direito e revogações; nunca cria comando.
  // Próximas 10:00 no fuso do condomínio (dentro da janela 06:00-22:00 e da vigência do direito).
  ZonedDateTime ten=ZonedDateTime.now(ZoneId.of(zone)).withHour(10).withMinute(0).withSecond(0).withNano(0);
  Instant probe=(ten.toInstant().isAfter(Instant.now())?ten:ten.plusDays(1)).toInstant();
  var real=new PolicyModels.SimulateRequest("AUTHORIZED_EXISTING_SUBJECT",versionId,inst.point(),null,probe,a.subjectId(),contextA);
  var allowed=policies.simulate(MANAGER,db.tenantA,AAL2,policyId,real);
  assertEquals("ALLOW",allowed.result(),String.join(",",allowed.reasonCodes()));
  assertFalse(allowed.hardwareCommandCreated());
  assertEquals("POLICY_SIMULATION_SUBJECT_INVALID",code(()->policies.simulate(MANAGER,db.tenantA,AAL2,policyId,
   new PolicyModels.SimulateRequest("AUTHORIZED_EXISTING_SUBJECT",versionId,inst.point(),null,probe,null,null))));

  // Cartão do morador A; bloqueio: marca do sujeito, direito e cartão revogados na mesma transação.
  UUID card=UUID.randomUUID();
  UUID membershipA=UUID.fromString(db.adminString("select id::text from cp.memberships where tenant_id=? and account_id=? and role='RESIDENT'",db.tenantA,db.accountA));
  db.adminUpdate("insert into cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) values(?,?,?,?,?,'CARD','Cartao JDBC',?)",
   card,db.tenantA,inst.connector(),membershipA,db.unitA,MANAGER);
  subjects.block(MANAGER,db.tenantA,AAL2,a.subjectId(),new SubjectModels.Block("Ordem ficticia da gestao",a.version()));
  var revoked=grantsOf(a.subjectId()).getFirst();
  assertEquals("REVOKED",revoked.status()); assertEquals("SUBJECT_BLOCKED",revoked.statusReasonCode()); assertFalse(revoked.current());
  assertEquals("REVOKED",db.adminString("select desired_state from cp.access_credentials where id=?",card),"Revogação alcança o cartão");
  var markers=grants.markers(MANAGER,db.tenantA,AAL2,a.subjectId()).markers();
  assertEquals("SUBJECT",markers.getFirst().targetKind());
  assertEquals(List.of("FACE","CARD","PIN","QR","INTERMEDIARY_GRANT"),markers.getFirst().coveredMethods());
  assertEquals("PENDING",markers.getFirst().externalDurabilityStatus(),"Confirmação externa só na barreira de recuperação");
  var denied=policies.simulate(MANAGER,db.tenantA,AAL2,policyId,real);
  assertEquals("DENY",denied.result()); assertTrue(denied.reasonCodes().contains("SUBJECT_BLOCKED"),denied.reasonCodes().toString());
  assertEquals(1,grants.derive(MANAGER,db.tenantA,AAL2,policyId,derive).skippedByReason().getOrDefault("SUBJECT_BLOCKED",0),"Sujeito bloqueado não recebe direito");

  // Desbloqueio: nova época, nova geração acima da marca.
  var blocked=subjectOf(db.accountA);
  subjects.unblock(MANAGER,db.tenantA,AAL2,a.subjectId(),new SubjectModels.Unblock(blocked.version()));
  assertEquals(1,grants.derive(MANAGER,db.tenantA,AAL2,policyId,derive).created());
  var renewed=grantsOf(a.subjectId()).stream().filter(GrantModels.Grant::current).findFirst().orElseThrow();
  assertTrue(renewed.generation()>markers.getFirst().revokedThroughGeneration());
  assertEquals(2,renewed.subjectRestrictionEpoch());

  // Revogação manual do direito de C: vence a derivação seguinte (mesmos fatos).
  var c=subjectOf(db.accountC);
  var grantC=grantsOf(c.subjectId()).getFirst();
  assertEquals("GRANT_REASON_REQUIRED",code(()->grants.revoke(MANAGER,db.tenantA,AAL2,grantC.grantId(),new GrantModels.RevokeGrant("",grantC.version()))));
  var manual=grants.revoke(MANAGER,db.tenantA,AAL2,grantC.grantId(),new GrantModels.RevokeGrant("Pedido ficticio da gestao",grantC.version()));
  assertEquals("REVOKED",manual.status()); assertEquals("MANUAL_REVOCATION",manual.statusReasonCode());
  assertEquals(manual,grants.revoke(MANAGER,db.tenantA,AAL2,grantC.grantId(),new GrantModels.RevokeGrant("Pedido ficticio da gestao",grantC.version())),"Repetição");
  var after=grants.derive(MANAGER,db.tenantA,AAL2,policyId,derive);
  assertEquals(0,after.created()); assertEquals(1,after.skippedByReason().getOrDefault("REVOKED_KEEPS",0),"Revogação vence a derivação dos mesmos fatos");

  // Vínculo revogado na origem: o direito deixa de valer na hora e a varredura grava a marca.
  db.adminUpdate("update cp.memberships set status='REVOKED',version=version+1 where id=?",membershipA);
  assertFalse(grantsOf(a.subjectId()).stream().anyMatch(GrantModels.Grant::current),"Origem revogada derruba o direito sem esperar job");
  var swept=grants.derive(MANAGER,db.tenantA,AAL2,policyId,derive);
  assertEquals(1,swept.revoked());
  assertEquals("ORIGIN_CHANGED",grantsOf(a.subjectId()).stream().filter(g->g.grantId().equals(renewed.grantId())).findFirst().orElseThrow().statusReasonCode());
  assertEquals("TENANT_ACCESS_DENIED",code(()->grants.grants(MANAGER,db.tenantB,AAL2,null,null)),"HAC-007");
  db.assertEmptyContext();
 }
 /** Política aprovada pelos fluxos reais (rascunho, validação, aprovação). */
 private static UUID approvedPolicy(UUID area,PolicyModels.PolicyDocument doc) {
  UUID policyId=UUID.randomUUID(),versionId=UUID.randomUUID();
  policies.createPolicy(MANAGER,db.tenantA,AAL2,new PolicyModels.CreatePolicy(policyId,area,"Area"));
  policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(versionId,doc,"Regra de direitos ficticia"));
  var validation=policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(versionId));
  assertEquals("VALIDATED",validation.status(),validation.issues().toString());
  long v=policies.versions(MANAGER,db.tenantA,AAL2,policyId).versions().getFirst().version();
  policies.approve(MANAGER,db.tenantA,AAL2,policyId,versionId,new PolicyModels.VersionAction(v));
  return versionId;
 }
 private static UUID policyOf(UUID version) throws Exception {
  return UUID.fromString(db.adminString("select policy_id::text from cp.access_policy_versions where id=?",version));
 }

 /**
  * V046: a derivação publica por destino na mesma transação (trabalho pendente, nunca sucesso físico); mesmos fatos não
  * geram trabalho novo; bloqueio publica REVOKE_ALL com prioridade e supera a aplicação pendente.
  */
 @Test void publicationIsAJobPerDestinationAndRevocationGoesFirst() throws Exception {
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,"4.5.1");
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID version=approvedPolicy(inst.area(),rule(zone,inst.area()));
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  var derived=grants.derive(MANAGER,db.tenantA,AAL2,policyOf(version),new GrantModels.DeriveRequest(version));
  assertEquals(2,derived.created(),derived.toString());
  assertEquals(2,derived.publication().created(),derived.publication().toString());
  UUID operation=derived.publication().operationId();
  var page=publications.publications(MANAGER,db.tenantA,AAL2,operation,null,null);
  assertEquals(2,page.summary().byState().get("PENDING")); assertFalse(page.summary().allConfirmed(),"Pendente não é sucesso");
  for(var p:page.publications()) {
   assertEquals("APPLY_RIGHTS",p.action()); assertEquals(1,p.priority()); assertEquals(inst.device(),p.deviceId()); assertEquals(1,p.entries());
   assertEquals("NONE",p.confirmationLevel());
  }
  assertEquals(0,publications.plan(MANAGER,db.tenantA,AAL2,new PublicationModels.PlanRequest(null)).created(),"Mesmo documento não gera trabalho");
  assertEquals("STAFF_ACCESS_DENIED",code(()->publications.plan(db.accountA,db.tenantA,AAL2,new PublicationModels.PlanRequest(null))),"Morador não publica");
  assertEquals("MFA_REQUIRED",code(()->publications.plan(MANAGER,db.tenantA,AAL1,new PublicationModels.PlanRequest(null))));
  assertEquals("ACCESS_SUBJECT_NOT_FOUND",code(()->publications.plan(MANAGER,db.tenantA,AAL2,new PublicationModels.PlanRequest(List.of(UUID.randomUUID())))));
  assertEquals("PUBLICATION_SUBJECTS_INVALID",code(()->publications.plan(MANAGER,db.tenantA,AAL2,new PublicationModels.PlanRequest(List.of()))));

  // Executor confirma um destino (leitura de volta igual ao hash); o outro destino é superado pelo bloqueio.
  var a=subjectOf(db.accountA);
  var other=page.publications().stream().filter(p->!p.subjectId().equals(a.subjectId())).findFirst().orElseThrow();
  assertThrows(org.springframework.dao.DataAccessException.class,()->worker.inTenant(db.tenantA,()->workerPublications.report(db.tenantA,
   other.publicationId(),other.version(),"CONFIRMED","STATE_READ","0".repeat(64),null,1,0)),"Leitura diferente nunca confirma");
  assertTrue(worker.inTenant(db.tenantA,()->workerPublications.report(db.tenantA,other.publicationId(),other.version(),"CONFIRMED","STATE_READ",
   other.documentHash(),null,1,0)));
  subjects.block(MANAGER,db.tenantA,AAL2,a.subjectId(),new SubjectModels.Block("Ordem ficticia da gestao",a.version()));
  // Lote com um destino confirmado e outro superado: o sucessor (REVOKE_ALL, outra operação) ainda pendente mantém o lote aberto.
  var open=publications.publications(MANAGER,db.tenantA,AAL2,operation,null,null).summary();
  assertEquals(1,open.byState().get("CONFIRMED")); assertEquals(1,open.byState().get("SUPERSEDED"));
  assertEquals(2,open.destinations()); assertEquals(1,open.confirmedDestinations());
  assertFalse(open.allConfirmed(),"Superado não encerra o destino: vale o trabalho mais novo");
  var rows=publications.publications(MANAGER,db.tenantA,AAL2,null,a.subjectId(),null).publications();
  assertEquals(2,rows.size());
  assertEquals("REVOKE_ALL",rows.getFirst().action()); assertEquals("REVOCATION",rows.getFirst().origin()); assertEquals(0,rows.getFirst().priority());
  assertTrue(rows.getFirst().desiredVersion()>rows.get(1).desiredVersion());
  assertEquals("SUPERSEDED",rows.get(1).state(),"Aplicação pendente superada pela revogação");
  assertEquals(rows.getFirst().publicationId(),rows.get(1).supersededBy());
  var successor=rows.getFirst();
  assertTrue(worker.inTenant(db.tenantA,()->workerPublications.report(db.tenantA,successor.publicationId(),successor.version(),"CONFIRMED","STATE_READ",
   successor.documentHash(),null,1,0)));
  assertTrue(publications.publications(MANAGER,db.tenantA,AAL2,operation,null,null).summary().allConfirmed(),"Sucessor confirmado fecha o lote");
  assertThrows(org.springframework.dao.DataAccessException.class,()->worker.inTenant(db.tenantA,()->workerPublications.report(db.tenantA,
   rows.get(1).publicationId(),rows.get(1).version(),"CONFIRMED","STATE_READ",rows.get(1).documentHash(),null,1,0)),"Trabalho superado não recebe resultado");
  assertEquals("TENANT_ACCESS_DENIED",code(()->publications.publications(MANAGER,db.tenantB,AAL2,null,null,null)),"HAC-007");
  assertTrue(Integer.parseInt(db.adminString("select count(*)::text from cp.audit_log where tenant_id=? and action='RIGHTS_PUBLICATION_PLANNED'",db.tenantA))>=1);
  db.assertEmptyContext();
 }

 /**
  * Documento acima de 512 entradas: falha fechada por destino. O compilado retira o que estava publicado (nunca mantém
  * direito removido), a impossibilidade fica registrada (DOCUMENT_TOO_LARGE) e os demais sujeitos seguem; a limpeza da
  * varredura não roda em transação abortada.
  */
 @Test void oversizedDocumentRetractsAndIsRecordedWhileOthersArePublished() throws Exception {
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,"4.5.2");
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID version=approvedPolicy(inst.area(),rule(zone,inst.area(),List.of("RESIDENT","EMPLOYEE"),"DENY","NOT_REQUIRED"));
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  var a=subjectOf(db.accountA);
  var c=subjectOf(db.accountC);
  var first=grants.derive(MANAGER,db.tenantA,AAL2,policyOf(version),new GrantModels.DeriveRequest(version));
  // A e C (RESIDENT) e a portaria da base (EMPLOYEE, elegível nesta regra): um trabalho por sujeito, nada fora do documento.
  assertEquals(3,first.created(),first.toString());
  assertEquals(3,first.publication().created(),first.publication().toString());
  assertEquals(Map.of(),first.publication().failedByReason());
  Instant until=Instant.now().plus(java.time.Duration.ofDays(10));
  for(int i=0;i<512;i++)
   subjects.addFunctional(MANAGER,db.tenantA,AAL2,a.subjectId(),new SubjectModels.FunctionalAuthorization(UUID.randomUUID(),"EMPLOYEE","func-jdbc-"+i,until));
  var derived=grants.derive(MANAGER,db.tenantA,AAL2,policyOf(version),new GrantModels.DeriveRequest(version));
  assertEquals(512,derived.created(),"Direitos gravados apesar do limite do documento: "+derived.skippedByReason());
  var published=derived.publication();
  assertEquals(Map.of("DOCUMENT_TOO_LARGE",1),published.failedByReason());
  assertEquals(a.subjectId(),published.failedSample().getFirst().subjectId());
  assertEquals(inst.device(),published.failedSample().getFirst().deviceId());
  // Contrato: a retirada segura conta como trabalho criado (created) e a impossibilidade de publicar o documento completo
  // fica em failedByReason; os dois são conferidos.
  assertEquals(1,published.created(),"Só a retirada segura de A; C e a portaria sem mudança");
  var retract=publications.publications(MANAGER,db.tenantA,AAL2,published.operationId(),a.subjectId(),null).publications().getFirst();
  assertEquals("REVOKE_RIGHTS",retract.action()); assertEquals(0,retract.priority()); assertEquals(0,retract.entries());
  assertEquals(1,publications.publications(MANAGER,db.tenantA,AAL2,null,c.subjectId(),null).publications().size(),"Os demais seguem publicados");
  var again=publications.plan(MANAGER,db.tenantA,AAL2,new PublicationModels.PlanRequest(List.of(a.subjectId(),c.subjectId())));
  assertEquals(0,again.created()); assertEquals(Map.of("DOCUMENT_TOO_LARGE",1),again.failedByReason(),"Plano explícito também registra por destino");
  db.assertEmptyContext();
 }

 /**
  * Regra com janela semanal: o documento ainda não representa horário, então a aplicação é recusada por destino
  * (SCHEDULE_NOT_REPRESENTABLE) e nada vira acesso contínuo; os direitos lógicos continuam gravados.
  */
 @Test void restrictedScheduleIsNeverPublishedAsContinuousAccess() throws Exception {
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,"4.5.3");
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID fullTime=approvedPolicy(inst.area(),rule(zone,inst.area()));
  UUID policyId=policyOf(fullTime);
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  var first=grants.derive(MANAGER,db.tenantA,AAL2,policyId,new GrantModels.DeriveRequest(fullTime));
  assertEquals(2,first.publication().created(),"Tempo integral publica");
  // Nova revisão com janela semanal: os direitos antigos são substituídos e o documento publicado é retirado; os novos
  // ficam fora do documento (nunca acesso contínuo) e a exclusão é relatada por destino.
  UUID restricted=UUID.randomUUID();
  policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(restricted,
   rule(zone,inst.area(),List.of("RESIDENT"),"DENY","NOT_REQUIRED","06:00","22:00"),"Regra com horario ficticia"));
  var validation=policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(restricted));
  assertEquals("VALIDATED",validation.status(),validation.issues().toString());
  long v=policies.versions(MANAGER,db.tenantA,AAL2,policyId).versions().stream().filter(x->restricted.equals(x.versionId())).findFirst().orElseThrow().version();
  policies.approve(MANAGER,db.tenantA,AAL2,policyId,restricted,new PolicyModels.VersionAction(v));
  var derived=grants.derive(MANAGER,db.tenantA,AAL2,policyId,new GrantModels.DeriveRequest(restricted));
  assertEquals(2,derived.created(),derived.toString()); assertEquals(2,derived.superseded());
  assertEquals(Map.of("SCHEDULE_NOT_REPRESENTABLE",2),derived.publication().failedByReason());
  assertEquals(inst.device(),derived.publication().failedSample().getFirst().deviceId());
  assertEquals(2,derived.publication().created(),"Retirada do documento antigo publicada");
  for(var p:publications.publications(MANAGER,db.tenantA,AAL2,derived.publication().operationId(),null,null).publications()) {
   assertEquals("REVOKE_RIGHTS",p.action()); assertEquals(0,p.priority()); assertEquals(0,p.entries());
  }
  assertFalse(publications.publications(MANAGER,db.tenantA,AAL2,first.publication().operationId(),null,null).summary().allConfirmed());
  db.assertEmptyContext();
 }

 /** Visitante de convite vigente: o gatilho confere o convite com a conferência ligada; derivação não aborta. */
 @Test void visitorOfCurrentInvitationReceivesARight() throws Exception {
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,"4.4.2");
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID version=approvedPolicy(inst.area(),rule(zone,inst.area(),List.of("RESIDENT","VISITOR"),"ALLOW_WITH_VALID_PERMIT","NOT_REQUIRED"));
  UUID visitor=UUID.randomUUID(),permit=UUID.randomUUID();
  db.adminUpdate("insert into cp.visitors(id,tenant_id,full_name,created_by) values(?,?,'VISITANTE DIREITO JDBC',?)",visitor,db.tenantA,db.accountA);
  db.adminUpdate("insert into cp.visit_permits(id,tenant_id,visitor_id,unit_id,created_by,valid_from,valid_until) values(?,?,?,?,?,now(),now()+interval '1 day')",
   permit,db.tenantA,visitor,db.unitA,db.accountA);
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  subjects.materializePermits(CONCIERGE,db.tenantA,AAL2);
  var result=grants.derive(MANAGER,db.tenantA,AAL2,policyOf(version),new GrantModels.DeriveRequest(version));
  assertEquals(3,result.created(),result.toString());
  var visitorGrant=grants.grants(MANAGER,db.tenantA,AAL2,null,inst.area()).grants().stream().filter(g->"VISIT_PERMIT".equals(g.originKind())).findFirst().orElseThrow();
  assertTrue(visitorGrant.current());
  assertFalse(visitorGrant.validUntil().isAfter(Instant.now().plus(java.time.Duration.ofDays(1))),"Nunca além do convite");
  db.adminUpdate("update cp.visit_permits set state='REVOKED',revoked_by=?,revoked_at=now(),revoke_reason='Cancelado JDBC',version=version+1 where id=?",db.accountA,permit);
  assertEquals(1,grants.derive(MANAGER,db.tenantA,AAL2,policyOf(version),new GrantModels.DeriveRequest(version)).revoked(),"Convite cancelado: marca na varredura");
  db.assertEmptyContext();
 }

 /** Reserva ainda por começar: o direito futuro não é tratado como origem inválida; cancelar a reserva revoga. */
 @Test void futureReservationRightSurvivesRederivationUntilCancelled() throws Exception {
  UUID amenity=UUID.randomUUID(),booking=UUID.randomUUID();
  db.adminUpdate("insert into cp.amenities(id,tenant_id,name,capacity,approval_required) values(?,?,'SALAO DIREITO JDBC',10,false)",amenity,db.tenantA);
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,"4.4.3",amenity);
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID version=approvedPolicy(inst.area(),rule(zone,inst.area(),List.of("RESIDENT"),"DENY","VALID_RESERVATION"));
  db.adminUpdate("insert into cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) "
   +"values(?,?,?,?,?,'BOOKING','CONFIRMED',now()+interval '2 hours',now()+interval '4 hours',now()+interval '2 hours',now()+interval '4 hours','{}'::jsonb)",
   booking,db.tenantA,amenity,db.unitA,db.accountA);
  // Reserva que terminou há 5 min: ainda dentro da tolerância de saída da regra (900 s), recebe direito até o fim da tolerância.
  UUID ended=UUID.randomUUID();
  db.adminUpdate("insert into cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) "
   +"values(?,?,?,?,?,'BOOKING','CONFIRMED',now()-interval '1 hour',now()-interval '5 minutes',now()-interval '1 hour',now()-interval '5 minutes','{}'::jsonb)",
   ended,db.tenantA,amenity,db.unitA,db.accountA);
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  UUID policy=policyOf(version);
  var first=grants.derive(MANAGER,db.tenantA,AAL2,policy,new GrantModels.DeriveRequest(version));
  assertEquals(2,first.created(),first.toString());
  assertEquals(1,first.skippedByReason().getOrDefault("RESERVATION_REQUIRED",0),"Unidade sem reserva não recebe direito");
  var inGrace=grants.grants(MANAGER,db.tenantA,AAL2,null,inst.area()).grants().stream().filter(g->ended.equals(g.originId())).findFirst().orElseThrow();
  assertTrue(inGrace.current(),"Tolerância de saída da regra conta na consulta de reservas");
  assertTrue(inGrace.validUntil().isBefore(Instant.now().plusSeconds(900)),"Nunca além do fim + tolerância");
  var host=grants.grants(MANAGER,db.tenantA,AAL2,null,inst.area()).grants().stream().filter(g->booking.equals(g.originId())).findFirst().orElseThrow();
  assertEquals("RESERVATION",host.originKind()); assertEquals(booking,host.originId());
  assertFalse(host.current(),"Ainda não começou"); assertTrue(host.validFrom().isAfter(Instant.now()));
  var again=grants.derive(MANAGER,db.tenantA,AAL2,policy,new GrantModels.DeriveRequest(version));
  assertEquals(0,again.revoked(),"Direito futuro não é origem inválida"); assertEquals(2,again.unchanged());
  db.adminUpdate("update cp.reservations set status='CANCELLED',version=version+1 where id=?",booking);
  assertEquals(1,grants.derive(MANAGER,db.tenantA,AAL2,policy,new GrantModels.DeriveRequest(version)).revoked(),"Reserva cancelada: marca na varredura");
  db.assertEmptyContext();
 }
}
