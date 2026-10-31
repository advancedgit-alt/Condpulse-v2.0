package br.com.condpulse.database;

import java.util.UUID;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessinstall.application.InstallModels;
import br.com.condpulse.accessinstall.application.InstallService;
import br.com.condpulse.accessinstall.infrastructure.InstallStore;
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
 * V042 com PostgreSQL real e o login da API: equipamento, canais, vínculo versionado e aceite de instalação. HAC-007, HAC-009,
 * HAC-021, HAC-022, HAC-027, HAC-098 e HAC-100.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessInstallJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID(),MASTER=UUID.randomUUID();
 private static UUID PILOT;
 private static final UUID CONNECTOR=UUID.randomUUID(),FROM=UUID.randomUUID(),TO=UUID.randomUUID(),POINT=UUID.randomUUID();
 private static final String A64="a".repeat(64),B64="b".repeat(64),C64="c".repeat(64);
 private static JdbcDisposableDatabase db;
 private static InstallService install;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  install=new InstallService(new TenantTransactionExecutor(db.jdbc,db.manager),new TechnicalTransactionExecutor(db.jdbc,db.manager),
   new InstallStore(db.jdbc),new AuditRecorder(db.jdbc));
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(MASTER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,MASTER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO INSTALACAO JDBC','MANAGER','ACTIVE')",
   db.tenantA,MANAGER);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.assertEmptyContext();
  db.adminUpdate("insert into cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) "
   +"values(gen_random_uuid(),?,?,'MASTER_IMPLANTACAO',ARRAY['installation.read','installation.manage','installation.bind_channel','installation.commission'],"
   +"now(),now()+interval '10 days','OPERATIONS','Implantacao ficticia JDBC')",db.tenantA,MASTER);
  db.adminUpdate("insert into cp.access_connectors(id,tenant_id,provider,label) values(?,?,'SIMULATED','Conector JDBC')",CONNECTOR,db.tenantA);
  db.adminUpdate("insert into cp.access_areas(id,tenant_id,code,name,area_kind,timezone,created_by) values(?,?,'EXT','Exterior','EXTERIOR','America/Sao_Paulo',?),"
   +"(?,?,'HALL','Hall','RESIDENTIAL_ROUTE','America/Sao_Paulo',?)",FROM,db.tenantA,MANAGER,TO,db.tenantA,MANAGER);
  db.adminUpdate("insert into cp.access_points(id,tenant_id,code,name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,status,created_by) "
   +"values(?,?,'P-SOCIAL','Portao social',?,?,'DOOR','BOTH','FREE_EGRESS','MAPPED',?)",POINT,db.tenantA,FROM,TO,MASTER);
  // Combinação aprovada para piloto pelo fluxo real da plataforma (dossiê, ensaio, bancada e piloto), sem desligar gatilhos.
  db.clearPlatformFixtures();
  PILOT=new FacialInstallationFixture(db).homologation("4.2.0",FacialInstallationFixture.ALL_CAPABILITIES,true);
 }
 @AfterEach void clear() throws Exception {
  db.adminUpdate("delete from cp.audit_log where tenant_id in (?,?) and (action like 'DEVICE\\_%' or action like 'CHANNEL\\_%' or action like 'BINDING\\_%' "
   +"or action like 'INSTALLATION\\_%')",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.point_bindings where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.installation_acceptances where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.device_channels where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_devices where connector_id=?",CONNECTOR);
  db.adminUpdate("delete from cp.access_connectors where id=?",CONNECTOR);
  db.adminUpdate("delete from cp.access_points where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_areas where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.clearPlatformFixtures();
  db.adminUpdate("delete from cp.technical_grants where tenant_id in (?,?)",db.tenantA,db.tenantB);
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static InstallModels.CreateAcceptance acceptance(UUID id,int failed) {
  return new InstallModels.CreateAcceptance(id,"planta/social",A64,"topologia/social",B64,C64,"hac-1.1","relatorio/social",0,failed,
   "emergencia/social","rollback/social",null,null);
 }
 private static InstallModels.CreateBinding binding(UUID id,UUID actuator,UUID controller,UUID reader,UUID contact) {
  return new InstallModels.CreateBinding(id,POINT,actuator,controller,reader,null,contact,null,null,PILOT,
   new InstallModels.Directions("FROM_TO",null),"ligacao/social-01");
 }

 @Test void masterCommissionsWithBothSignaturesAndManagerNeverSeesChannels() throws Exception {
  UUID controller=UUID.randomUUID(),reader=UUID.randomUUID();
  var create=new InstallModels.CreateDevice(controller,CONNECTOR,"ctrl-1","Controlador social",null,"r2",PILOT,"CONTROLLER","LOCAL_GATEWAY");
  assertEquals("TENANT_ACCESS_DENIED",code(()->install.createDevice(MANAGER,db.tenantA,AAL2,create)),"HAC-009: gestor não cadastra equipamento");
  assertEquals("MFA_REQUIRED",code(()->install.createDevice(MASTER,db.tenantA,AAL1,create)));
  var device=install.createDevice(MASTER,db.tenantA,AAL2,create);
  assertNotNull(device.provisionedInstanceId());
  assertEquals(device,install.createDevice(MASTER,db.tenantA,AAL2,create),"Mesmo pedido é repetição");
  assertEquals("INSTALLATION_DEVICE_ID_CONFLICT",code(()->install.createDevice(MASTER,db.tenantA,AAL2,
   new InstallModels.CreateDevice(controller,CONNECTOR,"ctrl-2","Outro",null,null,null,null,null))));
  var cloud=install.updateDevice(MASTER,db.tenantA,AAL2,controller,new InstallModels.UpdateDevice("Controlador social","BOTH","ACTIVE","r2",PILOT,
   "CONTROLLER","CLOUD_SERVICE",0L));
  assertEquals("CLOUD_SERVICE",cloud.connectionMode(),"D5: modo de conexão por equipamento");
  install.createDevice(MASTER,db.tenantA,AAL2,new InstallModels.CreateDevice(reader,CONNECTOR,"leitor-1","Leitor social","ENTRY",null,PILOT,"READER",null));

  UUID relay=UUID.randomUUID(),alarm=UUID.randomUUID(),contact=UUID.randomUUID();
  install.createChannel(MASTER,db.tenantA,AAL2,new InstallModels.CreateChannel(relay,controller,"relay","1","ACCESS_ACTUATOR","VERIFIED"));
  install.createChannel(MASTER,db.tenantA,AAL2,new InstallModels.CreateChannel(alarm,controller,"relay","2","ALARM_OUTPUT","VERIFIED"));
  install.createChannel(MASTER,db.tenantA,AAL2,new InstallModels.CreateChannel(contact,controller,"input","1","DOOR_CONTACT","VERIFIED"));
  assertEquals("INSTALLATION_CONFLICT",code(()->install.createChannel(MASTER,db.tenantA,AAL2,
   new InstallModels.CreateChannel(UUID.randomUUID(),controller,"relay","1","ACCESS_ACTUATOR","VERIFIED"))),"HAC-021: canal nativo único por controlador");
  assertEquals(3,install.channels(MASTER,db.tenantA,AAL2).channels().size());
  assertEquals("TENANT_ACCESS_DENIED",code(()->install.channels(MANAGER,db.tenantA,AAL2)),"HAC-009: gestor não lê canal");

  assertEquals("INSTALLATION_INVALID",code(()->install.createBinding(MASTER,db.tenantA,AAL2,binding(UUID.randomUUID(),alarm,controller,reader,contact))),
   "HAC-022: saída de alarme nunca vira atuador");
  assertEquals("INSTALLATION_BINDING_DIRECTION_INVALID",code(()->install.createBinding(MASTER,db.tenantA,AAL2,new InstallModels.CreateBinding(UUID.randomUUID(),
   POINT,relay,controller,reader,null,contact,null,null,PILOT,null,"ligacao/social-01"))));
  UUID first=UUID.randomUUID();
  var draft=install.createBinding(MASTER,db.tenantA,AAL2,binding(first,relay,controller,reader,contact));
  assertEquals(1,draft.bindingRevision()); assertEquals(64,draft.configurationHash().length());
  assertEquals(draft,install.createBinding(MASTER,db.tenantA,AAL2,binding(first,relay,controller,reader,contact)));

  // Revisão 1: falha crítica bloqueia o aceite (HAC-100) mesmo com as duas assinaturas.
  UUID rejected=UUID.randomUUID();
  var a1=install.createAcceptance(MASTER,db.tenantA,AAL2,acceptance(rejected,1));
  assertEquals(1,a1.installationRevision());
  var pending=install.submitBinding(MASTER,db.tenantA,AAL2,first,new InstallModels.SubmitBinding(rejected,0L));
  assertEquals("PENDING_COMMISSION",pending.status());
  a1=install.transition(MASTER,db.tenantA,AAL2,rejected,new InstallModels.AcceptanceTransition("TESTING",null,a1.version()));
  a1=install.signTechnical(MASTER,db.tenantA,AAL2,rejected,new InstallModels.VersionOnly(a1.version()));
  assertNotNull(a1.technicalSignedAt());
  long signedVersion=a1.version();
  assertEquals("INSTALLATION_STALE",code(()->install.recordResults(MASTER,db.tenantA,AAL2,rejected,new InstallModels.AcceptanceResults(0,0,
   "relatorio/social",null,signedVersion))),"Conteúdo assinado não muda");
  assertEquals("TENANT_ACCESS_DENIED",code(()->install.transition(MANAGER,db.tenantA,AAL2,rejected,
   new InstallModels.AcceptanceTransition("APPROVED",null,signedVersion))),"Gestão não aprova o aceite técnico");
  var rules=install.signRules(MANAGER,db.tenantA,AAL2,rejected,new InstallModels.VersionOnly(signedVersion));
  assertNotNull(rules.rulesSignedAt());
  assertEquals("INSTALLATION_INVALID",code(()->install.transition(MASTER,db.tenantA,AAL2,rejected,
   new InstallModels.AcceptanceTransition("APPROVED",null,rules.version()))),"HAC-100: falha crítica bloqueia");
  assertEquals("INSTALLATION_REASON_REQUIRED",code(()->install.transition(MASTER,db.tenantA,AAL2,rejected,
   new InstallModels.AcceptanceTransition("REJECTED",null,rules.version()))));
  install.transition(MASTER,db.tenantA,AAL2,rejected,new InstallModels.AcceptanceTransition("REJECTED","Falha critica no ensaio",rules.version()));
  assertEquals("SUPERSEDED",db.adminString("select status from cp.point_bindings where id=?",first),"Rejeição descarta o vínculo pendente");

  // Revisão 2: nova revisão do vínculo, aceite limpo, duas assinaturas e ativação atômica (HAC-098).
  UUID second=UUID.randomUUID(),approved=UUID.randomUUID();
  var b2=install.createBinding(MASTER,db.tenantA,AAL2,binding(second,relay,controller,reader,contact));
  assertEquals(2,b2.bindingRevision(),"HAC-027: mudança gera nova revisão");
  var a2=install.createAcceptance(MASTER,db.tenantA,AAL2,acceptance(approved,0));
  install.submitBinding(MASTER,db.tenantA,AAL2,second,new InstallModels.SubmitBinding(approved,b2.version()));
  a2=install.transition(MASTER,db.tenantA,AAL2,approved,new InstallModels.AcceptanceTransition("TESTING",null,a2.version()));
  a2=install.signTechnical(MASTER,db.tenantA,AAL2,approved,new InstallModels.VersionOnly(a2.version()));
  long beforeRules=a2.version();
  assertEquals("INSTALLATION_INVALID",code(()->install.transition(MASTER,db.tenantA,AAL2,approved,
   new InstallModels.AcceptanceTransition("APPROVED",null,beforeRules))),"Sem assinatura das regras não aprova");
  var summaries=install.managementAcceptances(MANAGER,db.tenantA,AAL2).acceptances();
  assertEquals(2,summaries.size());
  var signed=install.signRules(MANAGER,db.tenantA,AAL2,approved,new InstallModels.VersionOnly(beforeRules));
  var done=install.transition(MASTER,db.tenantA,AAL2,approved,new InstallModels.AcceptanceTransition("APPROVED",null,signed.version()));
  assertEquals("APPROVED",done.status()); assertNotNull(done.effectiveAt());
  assertEquals("ACTIVE:COMMISSIONED",db.adminString("select b.status||':'||p.status from cp.point_bindings b join cp.access_points p on p.id=b.point_id where b.id=?",
   second));
  assertEquals("INSTALLATION_INVALID",code(()->install.updateChannel(MASTER,db.tenantA,AAL2,relay,new InstallModels.UpdateChannel("OTHER","VERIFIED",0L))),
   "Canal em vínculo ativo não muda de função");
  long deviceVersion=install.devices(MASTER,db.tenantA,AAL2).devices().stream().filter(d->d.deviceId().equals(controller)).findFirst().orElseThrow().version();
  assertEquals("INSTALLATION_INVALID",code(()->install.updateDevice(MASTER,db.tenantA,AAL2,controller,new InstallModels.UpdateDevice("Controlador social",
   "BOTH","ACTIVE","r3",PILOT,"CONTROLLER","CLOUD_SERVICE",deviceVersion))),"Equipamento de vínculo ativo não muda hardware sem novo comissionamento");
  assertEquals("TENANT_ACCESS_DENIED",code(()->install.bindings(MANAGER,db.tenantA,AAL2)),"HAC-009: gestor não lê vínculo físico");
  assertEquals("TENANT_ACCESS_DENIED",code(()->install.bindings(MASTER,db.tenantB,AAL2)),"HAC-007: concessão não atravessa condomínio");
  db.assertEmptyContext();
 }
}
