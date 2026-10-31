package br.com.condpulse.database;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.accessage.application.BirthDateCipher;
import br.com.condpulse.accessage.infrastructure.AgeStore;
import br.com.condpulse.accessedge.application.EdgeAgentService;
import br.com.condpulse.accessedge.application.EdgeCredentials;
import br.com.condpulse.accessedge.application.EdgeGatewayService;
import br.com.condpulse.accessedge.application.EdgeModels;
import br.com.condpulse.accessedge.application.EdgeTransactionExecutor;
import br.com.condpulse.accessedge.infrastructure.EdgeStore;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessgrants.application.GrantModels;
import br.com.condpulse.accessgrants.application.GrantService;
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
 * V048 com PostgreSQL real e o login da API, pelos fluxos reais: o MASTER cadastra o gateway só com o hash da credencial,
 * o gateway recebe os trabalhos da V047 do próprio conector (retirada primeiro), relata ACK e confirma só com a leitura
 * de volta do mesmo hash; relatório repetido não tem segundo efeito; credencial girada ou revogada deixa de valer na hora.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class EdgeGatewayJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID(),MASTER=UUID.randomUUID();
 private static final List<String> APPROVED=List.of("faceEnrollment","faceDeletion","perDoorRights","secureTransport","offlineRestriction","localValidity");
 // Credenciais sintéticas de teste (base64url, 256+ bits); nunca usadas fora do banco descartável.
 private static final String TOKEN_A="c2ludGV0aWNhLWdhdGV3YXktQS0wMTIzNDU2Nzg5LWFiY2RlZg",TOKEN_B="c2ludGV0aWNhLWdhdGV3YXktQi0wMTIzNDU2Nzg5LWFiY2RlZg";
 private static JdbcDisposableDatabase db;
 private static PolicyService policies;
 private static GrantService grants;
 private static SubjectService subjects;
 private static EdgeAgentService agents;
 private static EdgeGatewayService gateway;
 private static FacialInstallationFixture fixture;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  var tenants=new TenantTransactionExecutor(db.jdbc,db.manager);
  var audit=new AuditRecorder(db.jdbc);
  var policyStore=new PolicyStore(db.jdbc);
  var grantStore=new GrantStore(db.jdbc);
  var publications=new PublicationService(tenants,new PublicationStore(db.jdbc),grantStore,audit);
  grants=new GrantService(tenants,grantStore,policyStore,new AgeStore(db.jdbc),BirthDateCipher.fromBase64("","v1"),audit,publications);
  policies=new PolicyService(tenants,policyStore,audit,grants);
  subjects=new SubjectService(tenants,new SubjectStore(db.jdbc),audit);
  var store=new EdgeStore(db.jdbc);
  agents=new EdgeAgentService(new TechnicalTransactionExecutor(db.jdbc,db.manager),store,audit);
  gateway=new EdgeGatewayService(new EdgeTransactionExecutor(db.jdbc,db.manager,store),store);
  fixture=new FacialInstallationFixture(db);
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(MASTER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,MASTER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO GATEWAY JDBC','MANAGER','ACTIVE')",db.tenantA,MANAGER);
 }
 @BeforeEach void reset() throws Exception {
  fixture.clear(); db.restoreActiveFixtures(); db.assertEmptyContext();
  fixture.grantMaster(MASTER);
  db.adminUpdate("insert into cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) "
   +"values(gen_random_uuid(),?,?,'MASTER_IMPLANTACAO',ARRAY['gateway.manage'],now(),now()+interval '10 days','OPERATIONS','Gateway ficticio JDBC')",db.tenantA,MASTER);
 }
 @AfterEach void clear() throws Exception { fixture.clear(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static String hash(String token) { return EdgeCredentials.hashOf(token).orElseThrow(); }

 /** Instalação comissionada, regra de tempo integral aprovada e derivação (que publica um trabalho por sujeito × equipamento). */
 private static FacialInstallationFixture.Installed published(String firmware) throws Exception {
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,firmware);
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  var doc=new PolicyModels.PolicyDocument("1.0","Academia moradores",inst.area(),zone,"ALL",List.of("RESIDENT"),"DENY",null,true,
   List.of(new PolicyModels.WeeklyWindow(List.of("MON","TUE","WED","THU","FRI","SAT","SUN"),"00:00","24:00",false)),List.of(),"HOLIDAY_OVERRIDES_WEEKLY",
   new PolicyModels.ReservationRule("NOT_REQUIRED",600,900),new PolicyModels.CompanionRule("NONE",0),
   new PolicyModels.OfflineRule("SAFE_LOCAL_SUBSET",300,true),new PolicyModels.FailureRule("DENY","DENY"),"OFF",null,true,null,"DENY");
  UUID policyId=UUID.randomUUID(),versionId=UUID.randomUUID();
  policies.createPolicy(MANAGER,db.tenantA,AAL2,new PolicyModels.CreatePolicy(policyId,inst.area(),"Area"));
  policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(versionId,doc,"Regra do gateway ficticia"));
  assertEquals("VALIDATED",policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(versionId)).status());
  long v=policies.versions(MANAGER,db.tenantA,AAL2,policyId).versions().getFirst().version();
  policies.approve(MANAGER,db.tenantA,AAL2,policyId,versionId,new PolicyModels.VersionAction(v));
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  var derived=grants.derive(MANAGER,db.tenantA,AAL2,policyId,new GrantModels.DeriveRequest(versionId));
  assertEquals(2,derived.publication().created(),derived.toString());
  return inst;
 }

 @Test void gatewayLeasesItsJobsAndConfirmsOnlyWithTheSameHash() throws Exception {
  var inst=published("4.8.1");
  UUID agentId=UUID.randomUUID();
  var register=new EdgeModels.RegisterAgent(agentId,inst.connector(),"Gateway portaria",hash(TOKEN_A),30);
  assertEquals("MFA_REQUIRED",code(()->agents.register(MASTER,db.tenantA,AAL1,register)));
  assertEquals("TENANT_ACCESS_DENIED",code(()->agents.register(MANAGER,db.tenantA,AAL2,register)),"Gestão não cadastra gateway");
  var agent=agents.register(MASTER,db.tenantA,AAL2,register);
  assertEquals("PROVISIONED",agent.status()); assertEquals(hash(TOKEN_A).substring(0,12),agent.credentialFingerprint());
  assertEquals(agent,agents.register(MASTER,db.tenantA,AAL2,register),"Repetição do mesmo cadastro devolve o existente");
  assertEquals("EDGE_AGENT_CONFLICT",code(()->agents.register(MASTER,db.tenantA,AAL2,new EdgeModels.RegisterAgent(UUID.randomUUID(),inst.connector(),
   "Outro",hash(TOKEN_B),30))),"Um gateway não revogado por conector");

  assertEquals("EDGE_UNAUTHENTICATED",code(()->gateway.identity(TOKEN_B)),"Credencial desconhecida");
  var identity=gateway.identity(TOKEN_A);
  assertEquals(agentId,identity.agentId()); assertEquals("ONLINE",identity.status()); assertNotNull(identity.serverTime());

  var page=gateway.lease(TOKEN_A,new EdgeModels.LeaseRequest(10,60));
  assertEquals(2,page.commands().size());
  for(var c:page.commands()) {
   assertEquals(inst.device(),c.deviceId()); assertEquals("APPLY_RIGHTS",c.type()); assertEquals(1,c.attempt());
   assertEquals(c.commandId().toString(),c.idempotencyKey());
   assertEquals(c.documentHash(),EdgeCredentials.sha256Hex(c.document().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
    "Documento entregue é o texto exato do hash");
  }
  assertEquals(0,gateway.lease(TOKEN_A,new EdgeModels.LeaseRequest(10,60)).commands().size(),"Trabalho em entrega não sai duas vezes");

  var job=page.commands().getFirst();
  var ack=gateway.report(TOKEN_A,job.commandId(),"ack-"+job.commandId(),new EdgeModels.ReportRequest("ACK",null,job.documentHash(),null));
  assertEquals("APPLIED",ack.outcome()); assertEquals("ACK",ack.jobState()); assertFalse(ack.duplicate());
  var again=gateway.report(TOKEN_A,job.commandId(),"ack-"+job.commandId(),new EdgeModels.ReportRequest("ACK",null,job.documentHash(),null));
  assertTrue(again.duplicate()); assertEquals("APPLIED",again.outcome());
  assertEquals("EDGE_IDEMPOTENCY_CONFLICT",code(()->gateway.report(TOKEN_A,job.commandId(),"ack-"+job.commandId(),
   new EdgeModels.ReportRequest("FAILED",null,job.documentHash(),null))),"Mesma chave, outro corpo");
  var wrong=gateway.report(TOKEN_A,job.commandId(),"rb-errado-"+job.commandId(),new EdgeModels.ReportRequest("CONFIRMED",null,job.documentHash(),"0".repeat(64)));
  assertEquals("REJECTED",wrong.outcome()); assertEquals("READBACK_HASH_MISMATCH",wrong.outcomeCode()); assertEquals("ACK",wrong.jobState());
  var confirmed=gateway.report(TOKEN_A,job.commandId(),"ok-"+job.commandId(),new EdgeModels.ReportRequest("CONFIRMED","CONFIRMED_BY_READBACK",
   job.documentHash(),job.documentHash()));
  assertEquals("APPLIED",confirmed.outcome()); assertEquals("CONFIRMED",confirmed.jobState());
  assertEquals("STATE_READ:true",db.adminString("select confirmation_level||':'||(observed_hash=document_hash) from cp.rights_publications where id=?",job.commandId()));
  var late=gateway.report(TOKEN_A,job.commandId(),"tarde-"+job.commandId(),new EdgeModels.ReportRequest("FAILED",null,job.documentHash(),null));
  assertEquals("CLOSED",late.outcome()); assertEquals("JOB_ALREADY_CONFIRMED",late.outcomeCode());

  // Rotação: a credencial antiga deixa de valer na hora; a nova herda a entrega (mesmo gateway).
  var current=agents.agents(MASTER,db.tenantA,AAL2).agents().getFirst();
  agents.rotate(MASTER,db.tenantA,AAL2,agentId,new EdgeModels.RotateAgent(hash(TOKEN_B),30,current.version()));
  assertEquals("EDGE_UNAUTHENTICATED",code(()->gateway.lease(TOKEN_A,null)));
  var other=page.commands().get(1);
  assertEquals("APPLIED",gateway.report(TOKEN_B,other.commandId(),"f-"+other.commandId(),new EdgeModels.ReportRequest("FAILED","DEVICE_REJECTED",
   other.documentHash(),null)).outcome());
  // Revogação: definitiva e imediata.
  current=agents.agents(MASTER,db.tenantA,AAL2).agents().getFirst();
  var revoked=agents.revoke(MASTER,db.tenantA,AAL2,agentId,new EdgeModels.RevokeAgent("Troca do computador do gateway",current.version()));
  assertEquals("REVOKED",revoked.status()); assertNotNull(revoked.revokedAt());
  assertEquals("EDGE_UNAUTHENTICATED",code(()->gateway.identity(TOKEN_B)));
  assertEquals("EDGE_AGENT_REVOKED",code(()->agents.revoke(MASTER,db.tenantA,AAL2,agentId,new EdgeModels.RevokeAgent("Repeticao",revoked.version()))));
  assertEquals("TENANT_ACCESS_DENIED",code(()->agents.agents(MASTER,db.tenantB,AAL2)),"HAC-007");
  assertEquals("3",db.adminString("select count(*)::text from cp.audit_log where tenant_id=? and action like 'EDGE\\_AGENT\\_%'",db.tenantA));
  assertEquals("0",db.adminString("select count(*)::text from cp.audit_log where tenant_id=? and reason like ?",db.tenantA,"%"+TOKEN_A+"%"));
  db.assertEmptyContext();
 }

 /** Bloqueio publica REVOKE_ALL (prioridade 0), que supera a aplicação pendente; relatório do superado só encerra. */
 @Test void revocationIsDeliveredFirstAndSupersededJobsOnlyClose() throws Exception {
  var inst=published("4.8.2");
  UUID agentId=UUID.randomUUID();
  agents.register(MASTER,db.tenantA,AAL2,new EdgeModels.RegisterAgent(agentId,inst.connector(),"Gateway",hash(TOKEN_A),30));
  var first=gateway.lease(TOKEN_A,new EdgeModels.LeaseRequest(1,60)).commands().getFirst();
  String subjectId=db.adminString("select subject_id::text from cp.rights_publications where id=?",first.commandId());
  var subject=subjects.list(MANAGER,db.tenantA,AAL2).subjects().stream().filter(s->s.subjectId().toString().equals(subjectId)).findFirst().orElseThrow();
  subjects.block(MANAGER,db.tenantA,AAL2,subject.subjectId(),new SubjectModels.Block("Ordem ficticia da gestao",subject.version()));
  var next=gateway.lease(TOKEN_A,new EdgeModels.LeaseRequest(10,60)).commands();
  assertEquals("REVOKE_ALL",next.getFirst().type(),"Retirada sai antes de qualquer ampliação"); assertEquals(0,next.getFirst().priority());
  var closed=gateway.report(TOKEN_A,first.commandId(),"sup-"+first.commandId(),new EdgeModels.ReportRequest("CONFIRMED",null,first.documentHash(),
   first.documentHash()));
  assertEquals("CLOSED",closed.outcome()); assertEquals("JOB_SUPERSEDED",closed.outcomeCode()); assertEquals("SUPERSEDED",closed.jobState());
  db.assertEmptyContext();
 }
}
