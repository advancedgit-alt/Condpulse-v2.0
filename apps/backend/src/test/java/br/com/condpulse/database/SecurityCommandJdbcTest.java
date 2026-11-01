package br.com.condpulse.database;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.partners.application.PartnerModels;
import br.com.condpulse.partners.application.PartnerService;
import br.com.condpulse.partners.application.PartnerTransactionExecutor;
import br.com.condpulse.partners.application.SecurityCommandModels;
import br.com.condpulse.partners.application.SecurityCommandRules;
import br.com.condpulse.partners.application.SecurityCommandService;
import br.com.condpulse.partners.infrastructure.PartnerStore;
import br.com.condpulse.partners.infrastructure.SecurityCommandStore;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.securitylog.application.SecurityLogModels;
import br.com.condpulse.securitylog.infrastructure.SecurityLogStore;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CPX-012 a CPX-014 (V053) com PostgreSQL real e o login da API: o líder vê o semáforo dos condomínios com contrato,
 * abre, designa, anexa, encerra e exporta incidentes com INCIDENT_* e EVIDENCE_EXPORTED no log, e revisa aberturas
 * remotas (REMOTE_OPEN_REVIEWED); gestor do parceiro e operador são recusados; a cadeia segue íntegra.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class SecurityCommandJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static PartnerService partners;
 private static SecurityCommandService command;
 private static UUID lead,operator,manager;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  partners=new PartnerService(new PlatformTransactionExecutor(db.jdbc,db.manager),new PartnerTransactionExecutor(db.jdbc,db.manager),
   new PartnerStore(db.jdbc),new PlatformStore(db.jdbc));
  command=new SecurityCommandService(new PartnerTransactionExecutor(db.jdbc,db.manager),new SecurityCommandStore(db.jdbc),new SecurityLogStore(db.jdbc));
  lead=UUID.randomUUID(); operator=UUID.randomUUID(); manager=UUID.randomUUID();
  // Contas sintéticas únicas deste ensaio (a trilha append-only as referencia): ficam no banco descartável.
  for(UUID account:List.of(lead,operator,manager)) db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",account);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearPlatformFixtures(); db.grantPlatformAdmin(db.accountA); db.assertEmptyContext();
 }
 @AfterEach void clear() throws Exception { db.clearPlatformFixtures(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static UUID member(UUID partner,UUID account,String role) {
  return partners.addMember(db.accountA,AAL2,partner,new PartnerModels.AddMember(account,role,"PESSOA FICTICIA "+role,"Papel ficticio")).id();
 }
 private static long count(String type,UUID correlation) throws Exception {
  return Long.parseLong(db.adminString("select count(*) from cp.security_log where tenant_id=? and event_type=? and correlation_id=?",
   db.tenantA,type,correlation));
 }

 @Test void leadRunsTheCommandWithRealDataAndEveryStepLandsInTheLog() throws Exception {
  UUID p=partners.create(db.accountA,AAL2,new PartnerModels.CreatePartner("PARCEIRO COMANDO JDBC "+UUID.randomUUID().toString().substring(0,8).toUpperCase(),
   "Cadastro ficticio")).partner().id();
  UUID leadMember=member(p,lead,"SECURITY_LEAD"); UUID operatorMember=member(p,operator,"REMOTE_OPERATOR"); UUID managerMember=member(p,manager,"PARTNER_ADMIN");
  partners.startContract(db.accountA,AAL2,p,new PartnerModels.StartContract(db.tenantA,null,null,"Contrato ficticio"));

  // Recusas antes de qualquer dado: papel sem security.command, segundo fator, condomínio fora do contrato.
  assertEquals("PARTNER_ACCESS_DENIED",code(()->command.portfolio(manager,AAL2,p)));
  assertEquals("MFA_REQUIRED",code(()->command.portfolio(lead,AAL1,p)));
  assertEquals("PARTNER_ACCESS_DENIED",code(()->command.portfolio(operator,AAL2,p)));
  assertEquals("PARTNER_ACCESS_DENIED",code(()->command.open(lead,AAL2,p,new SecurityCommandModels.OpenIncident(db.tenantB,"Fora do contrato","ATTENTION",null))));

  var first=command.portfolio(lead,AAL2,p);
  assertEquals(List.of(db.tenantA),first.tenants().stream().map(SecurityCommandModels.PortfolioTenant::tenantId).toList());
  assertEquals(SecurityCommandRules.CRITERIA,first.criteria());
  assertEquals(List.of("DOORS_HELD_OPEN","STAYS_OVERDUE"),first.unavailableIndicators());
  assertTrue(first.tenants().getFirst().reasons().contains("NO_OPERATOR_ON_DUTY"),"Contrato sem escala vigente: central sem operador");
  db.assertEmptyContext();

  // Alerta crítico sem incidente deixa o condomínio vermelho.
  UUID critical=UUID.randomUUID();
  db.adminUpdate("insert into cp.security_log(id,tenant_id,event_type,severity,source,details) values(?,?,'FORCED_OPEN','CRITICAL','DEVICE','{}'::jsonb)",
   critical,db.tenantA);
  var red=command.portfolio(lead,AAL2,p).tenants().getFirst();
  assertEquals("RED",red.light()); assertTrue(red.reasons().contains("CRITICAL_ALERTS_UNLINKED"));

  // Incidente com o alerta anexado: INCIDENT_OPENED correlacionado; a linha do tempo vem do log e grava SENSITIVE_VIEWED.
  var opened=command.open(lead,AAL2,p,new SecurityCommandModels.OpenIncident(db.tenantA,"Porta forcada ficticia","CRITICAL",List.of(critical)));
  UUID incident=opened.incident().id();
  assertEquals("OPEN|"+p+"|"+lead+"|1",opened.incident().state()+"|"+opened.incident().partnerId()+"|"+opened.incident().openedBy()+"|"+opened.incident().linkCount());
  assertEquals(1,count("INCIDENT_OPENED",incident));
  var detail=command.detail(lead,AAL2,p,db.tenantA,incident);
  assertEquals(List.of("FORCED_OPEN","INCIDENT_OPENED"),detail.timeline().stream().map(SecurityLogModels.Event::eventType).toList());
  assertEquals("1",db.adminString("select count(*) from cp.security_log where tenant_id=? and event_type='SENSITIVE_VIEWED' and actor_account_id=? "
   +"and details->>'view'='INCIDENT_TIMELINE' and details->>'incidentId'=?",db.tenantA,lead,incident.toString()));
  var after=command.portfolio(lead,AAL2,p).tenants().getFirst();
  assertFalse(after.reasons().contains("CRITICAL_ALERTS_UNLINKED")); assertTrue(after.reasons().contains("CRITICAL_INCIDENTS_OPEN"));

  // Designação: equipe do próprio parceiro; gestor do parceiro não é responsável; versão protege a corrida.
  assertTrue(command.team(lead,AAL2,p).members().stream().anyMatch(m->m.memberId().equals(operatorMember)));
  assertTrue(command.team(lead,AAL2,p).members().stream().noneMatch(m->m.memberId().equals(managerMember)));
  assertEquals("INCIDENT_ASSIGNEE_INVALID",code(()->command.assign(lead,AAL2,p,db.tenantA,incident,new SecurityCommandModels.AssignIncident(managerMember,0L))));
  assertEquals(operatorMember,command.assign(lead,AAL2,p,db.tenantA,incident,new SecurityCommandModels.AssignIncident(operatorMember,0L)).incident().assigneeMemberId());
  assertEquals("INCIDENT_CHANGED",code(()->command.assign(lead,AAL2,p,db.tenantA,incident,new SecurityCommandModels.AssignIncident(leadMember,0L))));
  assertEquals(1,count("INCIDENT_ASSIGNED",incident));

  // Dossiê: só com security.log.export e motivo; resumo dos eventos conferidos na cadeia; EVIDENCE_EXPORTED na mesma transação.
  assertEquals("INCIDENT_DOSSIER_REASON_REQUIRED",code(()->command.dossier(lead,AAL2,p,db.tenantA,incident,new SecurityCommandModels.DossierRequest("curto"))));
  var dossier=command.dossier(lead,AAL2,p,db.tenantA,incident,new SecurityCommandModels.DossierRequest("Pedido formal ficticio do condominio"));
  assertEquals(dossier.events().size(),dossier.manifest().count());
  assertEquals(SecurityCommandRules.eventsDigest(db.tenantA,incident,dossier.events()),dossier.manifest().eventsDigest());
  assertEquals(1,count("EVIDENCE_EXPORTED",incident));

  // Encerramento final com relatório; depois nada muda.
  var closed=command.close(lead,AAL2,p,db.tenantA,incident,new SecurityCommandModels.CloseIncident("RESOLVED","Relatorio ficticio do encerramento",1L));
  assertEquals("CLOSED",closed.incident().state());
  assertEquals(1,count("INCIDENT_CLOSED",incident));
  assertEquals("INCIDENT_CLOSED",code(()->command.link(lead,AAL2,p,db.tenantA,incident,new SecurityCommandModels.LinkEvents(List.of(critical)))));
  assertEquals(1,command.incidents(lead,AAL2,p,"CLOSED").incidents().size());

  // Revisão de aberturas remotas: uma por abertura, correção encadeada, nunca a própria.
  UUID remote=UUID.randomUUID(),own=UUID.randomUUID();
  db.context(operator,db.tenantA,null,()->db.jdbc.update("insert into cp.security_log(id,tenant_id,event_type,severity,source,reason) "
   +"values(?,?,'REMOTE_OPEN','INFO','REMOTE_CENTRAL','Autorizado por ligacao ficticia')",remote,db.tenantA));
  db.context(lead,db.tenantA,null,()->db.jdbc.update("insert into cp.security_log(id,tenant_id,event_type,severity,source,reason) "
   +"values(?,?,'REMOTE_OPEN','INFO','REMOTE_CENTRAL','Abertura ficticia do proprio lider')",own,db.tenantA));
  var pending=command.remoteOpens(lead,AAL2,p,db.tenantA,"PENDING",null,null,null);
  assertEquals(List.of(own,remote),pending.items().stream().map(SecurityCommandModels.RemoteOpen::eventId).toList());
  assertEquals(operator,pending.items().get(1).operatorAccountId());
  var conferred=command.review(lead,AAL2,p,db.tenantA,remote,new SecurityCommandModels.ReviewRequest("CONFERRED",null,null));
  assertEquals("CONFERRED",conferred.review().verdict());
  assertEquals("REMOTE_OPEN_ALREADY_REVIEWED",code(()->command.review(lead,AAL2,p,db.tenantA,remote,new SecurityCommandModels.ReviewRequest("CONFERRED",null,null))));
  var corrected=command.review(lead,AAL2,p,db.tenantA,remote,new SecurityCommandModels.ReviewRequest("IRREGULAR","Sem autorizacao registrada",
   conferred.review().reviewEventId()));
  assertEquals("IRREGULAR",corrected.review().verdict());
  assertEquals("REMOTE_OPEN_SELF_REVIEW",code(()->command.review(lead,AAL2,p,db.tenantA,own,new SecurityCommandModels.ReviewRequest("CONFERRED",null,null))));
  assertEquals(List.of(own),command.remoteOpens(lead,AAL2,p,db.tenantA,"PENDING",null,null,null).items().stream().map(SecurityCommandModels.RemoteOpen::eventId).toList());
  assertEquals("PARTNER_ACCESS_DENIED",code(()->command.remoteOpens(manager,AAL2,p,db.tenantA,"ALL",null,null,null)));
  assertEquals("t",db.adminString("select intact from cp.security_log_verify(?)",db.tenantA));
  db.assertEmptyContext();
 }
}
