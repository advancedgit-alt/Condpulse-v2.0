package br.com.condpulse.database;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.identity.application.CurrentIdentityQuery;
import br.com.condpulse.partners.application.ContextSwitchService;
import br.com.condpulse.partners.application.PartnerCapability;
import br.com.condpulse.partners.application.PartnerModels;
import br.com.condpulse.partners.application.PartnerService;
import br.com.condpulse.partners.application.PartnerTransactionExecutor;
import br.com.condpulse.partners.infrastructure.PartnerStore;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantModule;
import br.com.condpulse.tenancy.application.TenantModuleFlags;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V049 (CPX-001..CPX-003, ADR-0011) com PostgreSQL real e o login da API: a central cria parceiro, equipe, contrato e
 * escala com trilha; a equipe só entra no condomínio por contrato ativo + papel + (operador) escala vigente, conferidos
 * pelo banco; encerrar o contrato corta na hora; /v1/me devolve os contextos de parceiro para a /entrada.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class PartnerHierarchyJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static PartnerService partners;
 private static TenantTransactionExecutor tenants;
 private static UUID admin,lead,operator,stranger;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  partners=new PartnerService(new PlatformTransactionExecutor(db.jdbc,db.manager),new PartnerTransactionExecutor(db.jdbc,db.manager),
   new PartnerStore(db.jdbc),new PlatformStore(db.jdbc));
  tenants=new TenantTransactionExecutor(db.jdbc,db.manager);
  admin=UUID.randomUUID(); lead=UUID.randomUUID(); operator=UUID.randomUUID(); stranger=UUID.randomUUID();
  // Contas sintéticas únicas deste ensaio: ficam no banco descartável, porque a trilha da plataforma (append-only)
  // referencia o ator; nunca são apagadas.
  for(UUID account:List.of(admin,lead,operator,stranger))
   db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",account);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearPlatformFixtures(); db.grantPlatformAdmin(db.accountA); db.assertEmptyContext();
 }
 @AfterEach void clear() throws Exception { db.clearPlatformFixtures(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static PartnerModels.Reason reason() { return new PartnerModels.Reason("Motivo ficticio do ensaio JDBC"); }
 private static UUID partner(String name) {
  return partners.create(db.accountA,AAL2,new PartnerModels.CreatePartner(name,"Cadastro ficticio")).partner().id();
 }
 private static UUID member(UUID partner,UUID account,String role) {
  return partners.addMember(db.accountA,AAL2,partner,new PartnerModels.AddMember(account,role,"PESSOA FICTICIA "+role,"Papel ficticio")).id();
 }
 private static UUID contract(UUID partner,UUID tenant) {
  return partners.startContract(db.accountA,AAL2,partner,new PartnerModels.StartContract(tenant,null,null,"Contrato ficticio")).id();
 }
 private static long units(UUID account,UUID tenant) {
  return tenants.execute(account,tenant,AAL2,context->db.jdbc.queryForObject("select count(*) from cp.units",Long.class));
 }
 private static boolean capability(UUID account,UUID tenant,String key) {
  return tenants.execute(account,tenant,AAL2,context->db.jdbc.queryForObject("select platform.has_capability(?)",Boolean.class,key));
 }

 @Test void matrixInJavaIsTheMatrixInTheDatabase() {
  for(String role:PartnerCapability.ROLES) {
   var database=Set.of(db.tx.execute(status->db.jdbc.queryForObject(
    "select array_to_string(platform.partner_role_capabilities(?),',')",String.class,role)).split(","));
   assertEquals(PartnerCapability.forRole(role).stream().map(PartnerCapability::key).collect(Collectors.toSet()),database,role);
  }
  var all=Set.of(db.tx.execute(status->db.jdbc.queryForObject("select array_to_string(platform.security_capabilities(),',')",String.class)).split(","));
  assertTrue(all.containsAll(EnumSet.allOf(PartnerCapability.class).stream().map(PartnerCapability::key).toList()));
  assertTrue(all.containsAll(Set.of("security.log.read.tenant","security.blocklist.approve")));
 }

 @Test void contractRoleAndScheduleGateTheCondominiumAndEndingTheContractRevokesAtOnce() throws Exception {
  UUID p=partner("PARCEIRO FICTICIO JDBC");
  UUID adminMember=member(p,admin,"PARTNER_ADMIN"); member(p,lead,"SECURITY_LEAD"); UUID operatorMember=member(p,operator,"REMOTE_OPERATOR");
  // CPX-001: sem contrato nenhuma linha do condomínio; o executor responde 403 sem revelar o condomínio.
  assertEquals("PARTNER_ACCESS_DENIED",code(()->units(lead,db.tenantA)));
  assertEquals("TENANT_ACCESS_DENIED",code(()->units(stranger,db.tenantA)),"Quem não é equipe segue com 404");
  UUID c=contract(p,db.tenantA);
  assertEquals(2,units(lead,db.tenantA),"Líder alcança o condomínio com contrato ativo, sem escala (R3.2)");
  assertEquals(2,units(admin,db.tenantA));
  assertTrue(capability(lead,db.tenantA,"security.command"));
  assertFalse(capability(lead,db.tenantA,"ops.remote.open"),"Matriz conferida no banco");
  assertEquals("PARTNER_ACCESS_DENIED",code(()->units(lead,db.tenantB)),"Sem contrato com o outro condomínio");
  // CPX-002: operador sem escala recebe 403 e não vê o condomínio no /v1/me.
  assertEquals("PARTNER_ACCESS_DENIED",code(()->units(operator,db.tenantA)));
  var query=new CurrentIdentityQuery(db.jdbc,new TenantModuleFlags(EnumSet.allOf(TenantModule.class)),true);
  var me=db.tx.execute(status->query.execute(operator));
  assertEquals(1,me.partnerContexts().size());
  assertEquals("console",me.partnerContexts().getFirst().panel());
  assertTrue(me.partnerContexts().getFirst().tenants().isEmpty());
  assertTrue(me.memberships().isEmpty());
  // Escala pelo gestor do parceiro (contexto do próprio parceiro).
  var schedule=partners.teamCreateSchedule(admin,AAL2,p,new PartnerModels.CreateSchedule(operatorMember,db.tenantA,null,
   Instant.now().plusSeconds(4*3600),"Turno ficticio"));
  assertEquals(2,units(operator,db.tenantA),"Operador com escala vigente entra");
  assertTrue(capability(operator,db.tenantA,"ops.remote.open"));
  me=db.tx.execute(status->query.execute(operator));
  assertEquals(List.of(db.tenantA),me.partnerContexts().getFirst().tenants().stream().map(t->t.tenantId()).toList());
  assertTrue(me.tenantModules().stream().anyMatch(t->t.tenantId().equals(db.tenantA)),"Módulos do condomínio alcançado no menu");
  var leadMe=db.tx.execute(status->query.execute(lead));
  assertEquals("comando",leadMe.partnerContexts().getFirst().panel());
  assertTrue(leadMe.partnerContexts().getFirst().capabilities().contains("security.command"));
  assertTrue(db.tx.execute(status->new CurrentIdentityQuery(db.jdbc,new TenantModuleFlags(EnumSet.allOf(TenantModule.class))).execute(lead))
   .partnerContexts().isEmpty(),"Flag desligada: nenhum contexto de parceiro");
  partners.teamCancelSchedule(admin,AAL2,p,schedule.id(),reason());
  assertEquals("PARTNER_ACCESS_DENIED",code(()->units(operator,db.tenantA)),"Escala cancelada corta na hora");
  // R3.4: outro parceiro não opera o mesmo condomínio ao mesmo tempo.
  UUID other=partner("OUTRO PARCEIRO FICTICIO JDBC");
  assertEquals("PARTNER_PERIOD_CONFLICT",code(()->contract(other,db.tenantA)));
  // R3.3: encerrar o contrato tira o acesso de toda a equipe na próxima requisição.
  var ended=partners.endContract(db.accountA,AAL2,p,c,reason());
  assertEquals("ENDED",ended.status()); assertNotNull(ended.endedAt());
  assertEquals("PARTNER_ACCESS_DENIED",code(()->units(lead,db.tenantA)));
  assertEquals("PARTNER_ACCESS_DENIED",code(()->units(admin,db.tenantA)));
  assertTrue(db.tx.execute(status->query.execute(lead)).partnerContexts().getFirst().tenants().isEmpty());
  assertEquals("PARTNER_CONTRACT_ENDED",code(()->partners.endContract(db.accountA,AAL2,p,c,reason())));
  contract(other,db.tenantA);
  assertEquals("8",db.adminString("select count(*) from platform.platform_audit_log where action like 'PARTNER\\_%' and details->>'partnerId'=?",
   p.toString()),"Parceiro, três papéis, contrato, escala criada/cancelada e encerramento na trilha (sem o contrato recusado)");
  assertEquals(adminMember,partners.team(admin,AAL2,p).members().stream().filter(m->m.accountId().equals(admin)).findFirst().orElseThrow().id());
  db.assertEmptyContext();
 }

 @Test void partnerAdministrationNeedsTheRightRoleAal2AndNeverTouchesItself() throws Exception {
  UUID p=partner("PARCEIRO EQUIPE FICTICIO");
  UUID adminMember=member(p,admin,"PARTNER_ADMIN"); member(p,operator,"REMOTE_OPERATOR");
  assertEquals("MFA_REQUIRED",code(()->partners.list(db.accountA,AAL1)));
  assertEquals("PLATFORM_ACCESS_DENIED",code(()->partners.list(admin,AAL2)),"Gestor do parceiro não usa a central");
  assertEquals("MFA_REQUIRED",code(()->partners.team(admin,AAL1,p)));
  assertEquals("PARTNER_ACCESS_DENIED",code(()->partners.team(operator,AAL2,p)));
  assertEquals("PARTNER_NOT_FOUND",code(()->partners.team(stranger,AAL2,p)));
  UUID other=partner("PARCEIRO ALHEIO FICTICIO");
  assertEquals("PARTNER_NOT_FOUND",code(()->partners.team(admin,AAL2,other)),"Gestor não entra em outro parceiro");
  assertEquals("PARTNER_SELF_CHANGE_DENIED",code(()->partners.teamAdd(admin,AAL2,p,
   new PartnerModels.AddMember(admin,"SECURITY_LEAD","PROPRIO","Autoconcessao ficticia"))));
  assertEquals("PARTNER_SELF_CHANGE_DENIED",code(()->partners.teamRevoke(admin,AAL2,p,adminMember,reason())));
  var added=partners.teamAdd(admin,AAL2,p,new PartnerModels.AddMember(lead,"SECURITY_LEAD","LIDER FICTICIO","Contratacao ficticia"));
  assertEquals("SECURITY_LEAD",added.role());
  assertEquals("PARTNER_DUPLICATE",code(()->partners.teamAdd(admin,AAL2,p,new PartnerModels.AddMember(lead,"SECURITY_LEAD","LIDER","Repetido ficticio"))));
  assertEquals("PARTNER_REFERENCE_INVALID",code(()->partners.teamAdd(admin,AAL2,p,
   new PartnerModels.AddMember(UUID.randomUUID(),"TECHNICIAN","SEM CONTA","Conta inexistente ficticia"))));
  assertEquals(3,partners.team(admin,AAL2,p).members().size());
  var revoked=partners.teamRevoke(admin,AAL2,p,added.id(),reason());
  assertEquals("REVOKED",revoked.status());
  assertEquals("PARTNER_MEMBER_REVOKED",code(()->partners.teamRevoke(admin,AAL2,p,added.id(),reason())));
  // Escala sem contrato do parceiro com o condomínio é recusada pelo banco.
  UUID operatorMember=partners.team(admin,AAL2,p).members().stream().filter(m->m.accountId().equals(operator)).findFirst().orElseThrow().id();
  assertEquals("PARTNER_RULE_VIOLATED",code(()->partners.teamCreateSchedule(admin,AAL2,p,new PartnerModels.CreateSchedule(operatorMember,
   db.tenantB,null,Instant.now().plusSeconds(3600),"Turno ficticio"))));
  // Parceiro suspenso: gestor perde o painel; reativado volta.
  var detail=partners.detail(db.accountA,AAL2,p);
  var suspended=partners.changeStatus(db.accountA,AAL2,p,new PartnerModels.ChangePartnerStatus("SUSPENDED",detail.partner().version(),"Suspensao ficticia"));
  assertTrue(suspended.applied());
  assertEquals("PARTNER_NOT_FOUND",code(()->partners.team(admin,AAL2,p)));
  assertFalse(partners.changeStatus(db.accountA,AAL2,p,new PartnerModels.ChangePartnerStatus("SUSPENDED",0L,"Repeticao ficticia")).applied());
  assertEquals("PARTNER_CHANGED",code(()->partners.changeStatus(db.accountA,AAL2,p,new PartnerModels.ChangePartnerStatus("ACTIVE",0L,"Versao antiga"))));
  partners.changeStatus(db.accountA,AAL2,p,new PartnerModels.ChangePartnerStatus("ACTIVE",1L,"Reativacao ficticia"));
  assertEquals(2,partners.team(admin,AAL2,p).members().stream().filter(m->m.status().equals("ACTIVE")).count());
  assertEquals("PARTNER_DUPLICATE",code(()->partner("parceiro equipe ficticio")));
  db.assertEmptyContext();
 }

 private static long switches(UUID tenant,UUID actor) throws Exception {
  return Long.parseLong(db.adminString("select count(*) from cp.security_log where tenant_id=? and actor_account_id=? and event_type='CONTEXT_SWITCHED'",
   tenant,actor));
 }

 /** R4.4 (V052): troca de contexto grava CONTEXT_SWITCHED no condomínio alcançado, uma vez por troca, conferida pelo banco. */
 @Test void contextSwitchRecordsOncePerReachedTenantOnlyForSecurityRoles() throws Exception {
  var store=new PartnerStore(db.jdbc);
  var switchesOn=new ContextSwitchService(new PartnerTransactionExecutor(db.jdbc,db.manager),store,true);
  var switchesOff=new ContextSwitchService(new PartnerTransactionExecutor(db.jdbc,db.manager),store,false);
  // Nome único: o harness SQL roda antes no mesmo banco do CI e deixa o próprio parceiro de troca (o log só acrescenta).
  UUID p=partner("PARCEIRO TROCA JDBC "+UUID.randomUUID().toString().substring(0,8).toUpperCase());
  member(p,lead,"SECURITY_LEAD"); member(p,operator,"REMOTE_OPERATOR"); member(p,admin,"TECHNICIAN");
  contract(p,db.tenantA);
  var entry=new PartnerModels.ContextRef("ENTRY",null,null,null);
  UUID key=UUID.randomUUID();
  var request=new PartnerModels.ContextSwitchRequest(key,"SECURITY_LEAD",null,entry);
  assertEquals("MFA_REQUIRED",code(()->switchesOn.record(lead,AAL1,null,p,request)));
  assertEquals("LOG_DISABLED",switchesOff.record(lead,AAL2,null,p,request).status());
  assertEquals(0,switches(db.tenantA,lead),"Flag desligada: nada gravado");
  assertEquals(new PartnerModels.ContextSwitchResult("RECORDED",1),switchesOn.record(lead,AAL2,"sessao-ficticia-jdbc",p,request));
  assertEquals(1,switches(db.tenantA,lead));
  assertEquals("SECURITY_LEAD|"+p+"|REMOTE_CENTRAL|PARTNER|"+p+"|TO|SECURITY_LEAD|ENTRY|sessao-ficticia-jdbc|INFO",
   db.adminString("select actor_role||'|'||partner_id||'|'||source||'|'||subject_type||'|'||subject_id||'|'||(details->>'side')||'|'"
    +"||(details->'to'->>'role')||'|'||(details->'from'->>'kind')||'|'||session_id||'|'||severity from cp.security_log "
    +"where tenant_id=? and actor_account_id=? and event_type='CONTEXT_SWITCHED'",db.tenantA,lead));
  assertEquals("from,side,to",db.adminString("select string_agg(k,',' order by k) from cp.security_log l,jsonb_object_keys(l.details) k "
   +"where l.tenant_id=? and l.actor_account_id=? and l.event_type='CONTEXT_SWITCHED'",db.tenantA,lead),"details mínimo");
  // Duplo clique / nova tentativa: mesma chave não grava de novo e a cadeia continua íntegra.
  assertEquals(new PartnerModels.ContextSwitchResult("ALREADY_RECORDED",0),switchesOn.record(lead,AAL2,null,p,request));
  assertEquals(1,switches(db.tenantA,lead));
  assertEquals("t",db.adminString("select intact from cp.security_log_verify(?)",db.tenantA));
  // Mesmo contexto de origem e destino não é troca; outra chave é outra troca.
  var self=new PartnerModels.ContextRef("PARTNER",p,"SECURITY_LEAD",null);
  assertEquals("NOT_REQUIRED",switchesOn.record(lead,AAL2,null,p,new PartnerModels.ContextSwitchRequest(UUID.randomUUID(),"SECURITY_LEAD",null,self)).status());
  assertEquals("RECORDED",switchesOn.record(lead,AAL2,null,p,new PartnerModels.ContextSwitchRequest(UUID.randomUUID(),"SECURITY_LEAD",null,entry)).status());
  assertEquals(2,switches(db.tenantA,lead));
  // Operador sem escala não alcança nada; foco no condomínio é recusado. Técnico não é papel de segurança.
  assertEquals("NO_TENANT_REACHED",switchesOn.record(operator,AAL2,null,p,new PartnerModels.ContextSwitchRequest(UUID.randomUUID(),"REMOTE_OPERATOR",null,entry)).status());
  assertEquals("PARTNER_ACCESS_DENIED",code(()->switchesOn.record(operator,AAL2,null,p,
   new PartnerModels.ContextSwitchRequest(UUID.randomUUID(),"REMOTE_OPERATOR",db.tenantA,entry))));
  assertEquals("PARTNER_ACCESS_DENIED",code(()->switchesOn.record(operator,AAL2,null,p,
   new PartnerModels.ContextSwitchRequest(UUID.randomUUID(),"SECURITY_LEAD",null,entry))),"Papel que a conta não tem");
  assertEquals("NOT_REQUIRED",switchesOn.record(admin,AAL2,null,p,new PartnerModels.ContextSwitchRequest(UUID.randomUUID(),"TECHNICIAN",null,entry)).status());
  assertEquals("PARTNER_NOT_FOUND",code(()->switchesOn.record(stranger,AAL2,null,p,request)));
  assertEquals(0,switches(db.tenantA,operator)+switches(db.tenantA,admin));
  // Saída do líder para o painel técnico (mesma conta com os dois papéis) grava o fim da visão de segurança (lado FROM).
  member(p,lead,"TECHNICIAN");
  assertEquals("RECORDED",switchesOn.record(lead,AAL2,null,p,new PartnerModels.ContextSwitchRequest(UUID.randomUUID(),"TECHNICIAN",null,self)).status());
  assertEquals("FROM",db.adminString("select details->>'side' from cp.security_log where tenant_id=? and actor_account_id=? "
   +"and event_type='CONTEXT_SWITCHED' order by seq desc limit 1",db.tenantA,lead));
  // O banco recusa CONTEXT_SWITCHED para condomínio fora do alcance, mesmo gravado direto pela role da API.
  String details="{\"from\":{\"kind\":\"ENTRY\"},\"to\":{\"kind\":\"PARTNER\",\"partnerId\":\""+p+"\",\"role\":\"SECURITY_LEAD\"},\"side\":\"TO\"}";
  assertThrows(DataAccessException.class,()->db.context(lead,db.tenantB,null,()->db.jdbc.update("""
   insert into cp.security_log(tenant_id,event_type,severity,source,subject_type,subject_id,details)
   values(?,'CONTEXT_SWITCHED','INFO','REMOTE_CENTRAL','PARTNER',?,?::jsonb)""",db.tenantB,p,details)));
  assertEquals(0,switches(db.tenantB,lead));
  db.assertEmptyContext();
 }
}
