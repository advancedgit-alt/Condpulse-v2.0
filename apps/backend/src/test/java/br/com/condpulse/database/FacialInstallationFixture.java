package br.com.condpulse.database;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.accesscatalog.application.CatalogModels;
import br.com.condpulse.accesscatalog.application.CatalogService;
import br.com.condpulse.accesscatalog.infrastructure.CatalogStore;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessinstall.application.InstallModels;
import br.com.condpulse.accessinstall.application.InstallService;
import br.com.condpulse.accessinstall.infrastructure.InstallStore;
import br.com.condpulse.accessmap.application.MapModels;
import br.com.condpulse.accessmap.application.MapService;
import br.com.condpulse.accessmap.infrastructure.MapStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;

/**
 * Instalação fictícia montada pelos fluxos reais (serviços, gatilhos, RLS e trilhas ligados; nunca replica): combinação
 * homologada pela plataforma (accountA como PLATFORM_ADMIN), áreas pela gestão, ponto, equipamento, canal, vínculo e aceite
 * pelo MASTER de implantação e assinatura das regras pela gestão.
 */
final class FacialInstallationFixture {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 static final List<String> ALL_CAPABILITIES=List.copyOf(CatalogModels.CAPABILITIES);
 record Installed(UUID hall,UUID area,UUID point,UUID homologation,UUID connector,UUID device,UUID channel,UUID binding,UUID acceptance) {}

 private final JdbcDisposableDatabase db;
 final CatalogService catalog;
 final MapService map;
 final InstallService install;
 FacialInstallationFixture(JdbcDisposableDatabase db) {
  this.db=db;
  var tenants=new TenantTransactionExecutor(db.jdbc,db.manager);
  var technical=new TechnicalTransactionExecutor(db.jdbc,db.manager);
  var audit=new AuditRecorder(db.jdbc);
  catalog=new CatalogService(new PlatformTransactionExecutor(db.jdbc,db.manager),tenants,new CatalogStore(db.jdbc),new PlatformStore(db.jdbc));
  map=new MapService(tenants,technical,new MapStore(db.jdbc),audit);
  install=new InstallService(tenants,technical,new InstallStore(db.jdbc),audit);
 }

 /** Concessão técnica com os quatro escopos de instalação (como a operação protegida grava). */
 void grantMaster(UUID master) throws Exception {
  db.adminUpdate("insert into cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) "
   +"values(gen_random_uuid(),?,?,'MASTER_IMPLANTACAO',ARRAY['installation.read','installation.manage','installation.bind_channel',"
   +"'installation.commission'],now(),now()+interval '10 days','OPERATIONS','Implantacao ficticia JDBC')",db.tenantA,master);
 }

 /** Dossiê, ensaio, bancada e piloto pela plataforma; pilot=false deixa a combinação só documentada. */
 UUID homologation(String firmware,List<String> approved,boolean pilot) throws Exception {
  db.grantPlatformAdmin(db.accountA);
  UUID id=UUID.randomUUID();
  UUID profile=UUID.fromString(db.adminString("select id from platform.access_model_profiles order by catalog_code limit 1"));
  var caps=new HashMap<String,CatalogModels.CapabilityObservation>();
  for(String name:CatalogModels.CAPABILITIES) caps.put(name,new CatalogModels.CapabilityObservation(true,approved.contains(name)?true:null));
  var limits=new HashMap<String,Long>(); for(String name:CatalogModels.LIMITS) limits.put(name,null); limits.put("faces",3000L);
  catalog.create(db.accountA,AAL2,new CatalogModels.CreateHomologation(id,profile,firmware,"control-id-rest","0.1.0","v1","EDGE_ONLINE",
   "LOCAL_GATEWAY",caps,limits,List.of(UUID.fromString("41000000-0000-4000-8000-000000000001")),List.of("Ensaio ficticio"),"Dossie ficticio JDBC"));
  if(pilot) {
   long version=0;
   for(String decision:List.of("START_TRIAL","APPROVE_LAB","APPROVE_PILOT")) {
    boolean approve=decision.startsWith("APPROVE_");
    catalog.decide(db.accountA,AAL2,id,new CatalogModels.Decide(UUID.randomUUID(),decision,"Decisao ficticia JDBC",
     approve?List.of(UUID.fromString("41000000-0000-4000-8000-000000000002")):List.of(),approve?approved:List.of(),approve?java.time.Instant.now().plus(180,java.time.temporal.ChronoUnit.DAYS):null,List.of(),version++));
   }
  }
  return id;
 }

 /** Duas áreas (hall de origem e área-alvo) e uma passagem comissionada entre elas. */
 Installed commissioned(UUID manager,UUID master,List<String> approved,String firmware) throws Exception {
  return commissioned(manager,master,approved,firmware,null);
 }
 /** amenity: área-alvo ligada ao espaço reservável (tipo AMENITY), para regras com reserva. */
 Installed commissioned(UUID manager,UUID master,List<String> approved,String firmware,UUID amenity) throws Exception {
  UUID homologation=homologation(firmware,approved,true);
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID hall=UUID.randomUUID(),area=UUID.randomUUID(),point=UUID.randomUUID(),connector=UUID.randomUUID(),device=UUID.randomUUID(),
   channel=UUID.randomUUID(),binding=UUID.randomUUID(),acceptance=UUID.randomUUID();
  String suffix=firmware.replace(".","");
  map.createArea(manager,db.tenantA,AAL2,new MapModels.CreateArea(hall,"HALL"+suffix,"Hall ficticio","RESIDENTIAL_ROUTE",null,null,null,zone,null,"ACTIVE"));
  map.createArea(manager,db.tenantA,AAL2,new MapModels.CreateArea(area,"AREA"+suffix,"Area ficticia",amenity==null?"COMMON_AREA":"AMENITY",null,null,amenity,zone,null,"ACTIVE"));
  map.createPoint(master,db.tenantA,AAL2,new MapModels.CreatePoint(point,"P"+suffix,"Porta ficticia",hall,area,"DOOR","FROM_TO","FREE_EGRESS",null,null,"MAPPED"));
  db.adminUpdate("insert into cp.access_connectors(id,tenant_id,provider,label) values(?,?,'SIMULATED','Conector ficticio JDBC')",connector,db.tenantA);
  install.createDevice(master,db.tenantA,AAL2,new InstallModels.CreateDevice(device,connector,"ctrl-"+suffix,"Controlador ficticio",null,null,homologation,
   "CONTROLLER",null));
  install.createChannel(master,db.tenantA,AAL2,new InstallModels.CreateChannel(channel,device,"relay","1","ACCESS_ACTUATOR","VERIFIED"));
  install.createBinding(master,db.tenantA,AAL2,new InstallModels.CreateBinding(binding,point,channel,device,null,null,null,null,null,homologation,
   new InstallModels.Directions(null,null),"ligacao/ficticia"));
  var a=install.createAcceptance(master,db.tenantA,AAL2,new InstallModels.CreateAcceptance(acceptance,"planta/ficticia","a".repeat(64),"topologia/ficticia",
   "b".repeat(64),"c".repeat(64),"hac-1.1","relatorio/ficticio",0,0,"emergencia/ficticia","rollback/ficticio",null,null));
  install.submitBinding(master,db.tenantA,AAL2,binding,new InstallModels.SubmitBinding(acceptance,0L));
  a=install.transition(master,db.tenantA,AAL2,acceptance,new InstallModels.AcceptanceTransition("TESTING",null,a.version()));
  a=install.signTechnical(master,db.tenantA,AAL2,acceptance,new InstallModels.VersionOnly(a.version()));
  var rules=install.signRules(manager,db.tenantA,AAL2,acceptance,new InstallModels.VersionOnly(a.version()));
  install.transition(master,db.tenantA,AAL2,acceptance,new InstallModels.AcceptanceTransition("APPROVED",null,rules.version()));
  return new Installed(hall,area,point,homologation,connector,device,channel,binding,acceptance);
 }

 /** Remove a instalação do condomínio A sem desligar gatilhos (ordem das referências). */
 void clear() throws Exception {
  db.adminUpdate("delete from cp.audit_log where tenant_id in (?,?) and (action like 'POLICY\\_%' or action like 'DEVICE\\_%' or action like 'CHANNEL\\_%' "
   +"or action like 'BINDING\\_%' or action like 'INSTALLATION\\_%' or action like 'RIGHTS\\_%' or action like 'SUBJECT\\_%' or action like 'AREA\\_%' or action like 'POINT\\_%')",db.tenantA,db.tenantB);
  db.adminUpdate("with g as (delete from cp.access_grants where tenant_id in (?,?)) delete from cp.revocation_markers where tenant_id in (?,?)",
   db.tenantA,db.tenantB,db.tenantA,db.tenantB);
  db.adminUpdate("with v as (delete from cp.access_policy_validations where tenant_id in (?,?)) delete from cp.access_policy_versions where tenant_id in (?,?)",
   db.tenantA,db.tenantB,db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_policies where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.point_bindings where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.installation_acceptances where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.device_channels where tenant_id in (?,?)",db.tenantA,db.tenantB);
  // Credenciais criadas nos conectores fictícios (e o evento da revogação em cascata), antes dos conectores.
  db.adminUpdate("delete from cp.outbox where tenant_id in (?,?) and aggregate_id in (select c.id from cp.access_credentials c join cp.access_connectors k "
   +"on k.tenant_id=c.tenant_id and k.id=c.connector_id where k.label like '%ficticio JDBC')",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.credential_syncs where credential_id in (select c.id from cp.access_credentials c join cp.access_connectors k "
   +"on k.tenant_id=c.tenant_id and k.id=c.connector_id where k.label like '%ficticio JDBC')");
  db.adminUpdate("delete from cp.access_credentials where connector_id in (select id from cp.access_connectors where tenant_id in (?,?) and label like '%ficticio JDBC')",
   db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_devices where connector_id in (select id from cp.access_connectors where tenant_id in (?,?) and label like '%ficticio JDBC')",
   db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_connectors where tenant_id in (?,?) and label like '%ficticio JDBC'",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_points where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_areas where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.technical_grants where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.clearPlatformFixtures();
 }
}
