package br.com.condpulse.database;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;
import br.com.condpulse.accessage.application.AgeModels;
import br.com.condpulse.accessage.application.AgeService;
import br.com.condpulse.accessage.application.BirthDateCipher;
import br.com.condpulse.accessage.infrastructure.AgeStore;
import br.com.condpulse.identity.application.AssuranceLevel;
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
 * V039 com PostgreSQL real e o login da API: evidência de idade e parâmetros por condomínio (AC-006). Chave sintética
 * gerada no próprio teste. Casos HAC-031/032 (véspera e aniversário no fuso do condomínio), HAC-033 (sem presunção),
 * HAC-034 (correção cadastral) e HAC-085 (data não sai do serviço).
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessAgeJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID();
 private static JdbcDisposableDatabase db;
 private static AgeService ages,withoutKey;
 private static UUID subjectA,subjectB;
 private static LocalDate today;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  byte[] key=new byte[32]; new SecureRandom().nextBytes(key);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  ages=new AgeService(transactions,new AgeStore(db.jdbc),BirthDateCipher.fromBase64(Base64.getEncoder().encodeToString(key),"v1"),new AuditRecorder(db.jdbc));
  withoutKey=new AgeService(transactions,new AgeStore(db.jdbc),BirthDateCipher.fromBase64("","v1"),new AuditRecorder(db.jdbc));
  db.extraAccounts.add(MANAGER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",MANAGER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO IDADE JDBC','MANAGER','ACTIVE')",db.tenantA,MANAGER);
  today=LocalDate.now(ZoneId.of(db.adminString("select time_zone from platform.tenants where id=?",db.tenantA)));
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.assertEmptyContext();
  subjectA=UUID.randomUUID(); subjectB=UUID.randomUUID();
  db.adminUpdate("insert into cp.access_subjects(id,tenant_id,origin_kind,account_id) values(?,?,'ACCOUNT',?),(?,?,'ACCOUNT',?)",
   subjectA,db.tenantA,db.accountA,subjectB,db.tenantB,db.accountB);
 }
 @AfterEach void clear() throws Exception {
  db.adminUpdate("delete from cp.audit_log where tenant_id in (?,?) and (action like 'AGE\\_EVIDENCE\\_%' or action='FACIAL_SETTINGS_UPDATED')",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.age_evidence where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.facial_access_settings where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_subjects where tenant_id in (?,?)",db.tenantA,db.tenantB);
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static AgeModels.RecordEvidence birth(UUID id,LocalDate date) {
  return new AgeModels.RecordEvidence(id,"ENCRYPTED_BIRTH_DATE",date,null,null,null,"DOCUMENT_REVIEW","ref:conferencia-jdbc-01");
 }

 @Test void settingsAreVersionedPerTenant() throws Exception {
  var defaults=ages.settings(MANAGER,db.tenantA,AAL2);
  assertEquals(new AgeModels.Settings("MARCH_1",false,365,0),defaults,"Padrões provisórios: 01/03, menores desligado, 365 dias");
  assertEquals("MFA_REQUIRED",code(()->ages.settings(MANAGER,db.tenantA,AAL1)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->ages.settings(db.accountA,db.tenantA,AAL2)));
  var saved=ages.updateSettings(MANAGER,db.tenantA,AAL2,new AgeModels.UpdateSettings("FEBRUARY_28",false,730,0L,"Regra aprovada em assembleia"));
  assertEquals(new AgeModels.Settings("FEBRUARY_28",false,730,1),saved);
  assertEquals("FACIAL_SETTINGS_STALE",code(()->ages.updateSettings(MANAGER,db.tenantA,AAL2,new AgeModels.UpdateSettings("MARCH_1",true,365,0L,"Outra"))));
  assertEquals("FACIAL_SETTINGS_LEAP_RULE_INVALID",code(()->ages.updateSettings(MANAGER,db.tenantA,AAL2,new AgeModels.UpdateSettings("JAN_1",true,365,1L,"Outra"))));
  assertEquals("0",db.adminString("select count(*) from cp.facial_access_settings where tenant_id=?",db.tenantB),"Nada atravessa para B");
  db.assertEmptyContext();
 }

 @Test void protectedBirthDateAnswersOnlyThePredicate() throws Exception {
  UUID first=UUID.randomUUID();
  var recorded=ages.record(MANAGER,db.tenantA,AAL2,subjectA,birth(first,today.minusYears(18).plusDays(1)));
  assertEquals("VALID",recorded.status());
  assertEquals("DOES_NOT_MEET",ages.check(MANAGER,db.tenantA,AAL2,subjectA,18).outcome(),"HAC-031: véspera dos 18 nega");
  assertEquals(recorded,ages.record(MANAGER,db.tenantA,AAL2,subjectA,birth(first,today.minusYears(18).plusDays(1))),"Mesmo pedido é repetição");
  assertEquals("AGE_EVIDENCE_ID_CONFLICT",code(()->ages.record(MANAGER,db.tenantA,AAL2,subjectA,birth(first,today.minusYears(30)))),
   "Mesmo id com outra data é conflito");
  String stored=db.adminString("select encode(birth_date_ciphertext,'escape') from cp.age_evidence where id=?",first);
  assertFalse(stored.contains(Integer.toString(today.minusYears(18).getYear())),"HAC-085: sem data em claro no banco");
  // HAC-034: correção cadastral substitui; HAC-032: no aniversário (data local) atende.
  UUID corrected=UUID.randomUUID();
  ages.record(MANAGER,db.tenantA,AAL2,subjectA,birth(corrected,today.minusYears(18)));
  var check=ages.check(MANAGER,db.tenantA,AAL2,subjectA,18);
  assertEquals("MEETS",check.outcome()); assertEquals(corrected,check.evidenceId()); assertEquals(today,check.assessedOn());
  var history=ages.history(MANAGER,db.tenantA,AAL2,subjectA);
  assertEquals(2,history.size()); assertEquals("VALID",history.getFirst().status()); assertEquals("SUPERSEDED",history.get(1).status());
  assertEquals(first,history.getFirst().supersedesEvidenceId());
  assertEquals("ACCESS_SUBJECT_NOT_FOUND",code(()->ages.check(MANAGER,db.tenantA,AAL2,subjectB,18)),"Sujeito de B não existe em A");
  assertEquals("STAFF_ACCESS_DENIED",code(()->ages.check(db.accountA,db.tenantA,AAL2,subjectA,18)),"Morador não consulta");
  db.assertEmptyContext();
 }

 @Test void attestationAndRevocationNeverPresumeAdulthood() throws Exception {
  assertEquals("UNKNOWN",ages.check(MANAGER,db.tenantA,AAL2,subjectA,18).outcome(),"HAC-033: sem evidência é desconhecido");
  UUID id=UUID.randomUUID();
  var att=new AgeModels.RecordEvidence(id,"AGE_PREDICATE_ATTESTATION",null,18,null,true,"TRUSTED_EXISTING_RECORD","ref:atestado-jdbc-01");
  var recorded=withoutKey.record(MANAGER,db.tenantA,AAL2,subjectA,att);
  assertEquals("MEETS",withoutKey.check(MANAGER,db.tenantA,AAL2,subjectA,18).outcome(),"Atestação funciona sem a chave");
  assertEquals("UNKNOWN",withoutKey.check(MANAGER,db.tenantA,AAL2,subjectA,21).outcome(),"18+ não prova 21+");
  assertEquals("AGE_EVIDENCE_KEY_UNAVAILABLE",code(()->withoutKey.record(MANAGER,db.tenantA,AAL2,subjectA,birth(UUID.randomUUID(),today.minusYears(40)))),
   "Sem chave a data protegida falha fechada");
  assertEquals("AGE_EVIDENCE_SHAPE_INVALID",code(()->ages.record(MANAGER,db.tenantA,AAL2,subjectA,
   new AgeModels.RecordEvidence(UUID.randomUUID(),"AGE_PREDICATE_ATTESTATION",today.minusYears(40),18,null,true,"DOCUMENT_REVIEW","ref:atestado-jdbc-02"))));
  assertEquals("AGE_EVIDENCE_METHOD_INVALID",code(()->ages.record(MANAGER,db.tenantA,AAL2,subjectA,
   new AgeModels.RecordEvidence(UUID.randomUUID(),null,null,18,null,true,"DOCUMENT_REVIEW","ref:atestado-jdbc-03"))),"Campo ausente é 400, não 500");
  assertEquals("AGE_EVIDENCE_VERIFICATION_INVALID",code(()->ages.record(MANAGER,db.tenantA,AAL2,subjectA,
   new AgeModels.RecordEvidence(UUID.randomUUID(),"AGE_PREDICATE_ATTESTATION",null,18,null,true,null,"ref:atestado-jdbc-04"))));
  assertEquals("AGE_EVIDENCE_STALE",code(()->ages.revoke(MANAGER,db.tenantA,AAL2,id,new AgeModels.RevokeEvidence("Documento contestado",3L))));
  ages.revoke(MANAGER,db.tenantA,AAL2,id,new AgeModels.RevokeEvidence("Documento contestado",recorded.version()));
  assertEquals("UNKNOWN",ages.check(MANAGER,db.tenantA,AAL2,subjectA,18).outcome(),"Revogada não vale");
  db.assertEmptyContext();
 }
}
