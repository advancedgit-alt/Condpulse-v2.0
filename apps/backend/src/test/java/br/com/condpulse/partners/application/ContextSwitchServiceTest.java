package br.com.condpulse.partners.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.partners.infrastructure.PartnerStore;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R4.4 (V052): regras da troca de contexto sem banco. O alcance vem de um armazenamento falso; a conferência real do
 * alcance, da forma de details e da idempotência pela chave primária está em PartnerHierarchyJdbcTest e no harness.
 */
class ContextSwitchServiceTest {
 private static final UUID ACTOR=UUID.fromString("10000000-0000-4000-8000-0000000000c1");
 private static final UUID PARTNER=UUID.fromString("49000000-0000-4000-8000-0000000000c1");
 private static final UUID OTHER=UUID.fromString("49000000-0000-4000-8000-0000000000c2");
 private static final UUID T1=UUID.fromString("00000000-0000-4000-8000-0000000000c1");
 private static final UUID T2=UUID.fromString("00000000-0000-4000-8000-0000000000c2");
 private static final UUID T3=UUID.fromString("f0000000-0000-4000-8000-0000000000c3");
 private static final UUID SWITCH=UUID.fromString("5a000000-0000-4000-8000-0000000000c1");

 /** Alcance fixo por parceiro e papel; grava em memória. */
 static final class FakeStore extends PartnerStore {
  final Map<String,List<UUID>> reach;
  final List<ContextSwitchRow> written=new ArrayList<>();
  int reads;
  FakeStore(Map<String,List<UUID>> reach) { super(null); this.reach=reach; }
  @Override public List<UUID> reach(UUID partner,String role) { reads++; return reach.getOrDefault(partner+"|"+role,List.of()); }
  @Override public void recordContextSwitch(List<ContextSwitchRow> rows,String sessionId) { written.addAll(rows); }
 }
 private static FakeStore store() {
  return new FakeStore(Map.of(PARTNER+"|SECURITY_LEAD",List.of(T1,T2),PARTNER+"|REMOTE_OPERATOR",List.of(T2),
   OTHER+"|SECURITY_SUPERVISOR",List.of(T2,T3),PARTNER+"|TECHNICIAN",List.of(T1)));
 }
 private static ContextSwitchService service(FakeStore store,boolean enabled) {
  return new ContextSwitchService(new PartnerTransactionExecutor(null,null),store,enabled);
 }
 private static PartnerContext context(AssuranceLevel assurance,String... roles) {
  return new PartnerContext(ACTOR,PARTNER,Set.of(roles),assurance);
 }
 private static ContextSwitchService.Input input(String role,UUID tenant,PartnerModels.ContextRef from) {
  return ContextSwitchService.input(PARTNER,new PartnerModels.ContextSwitchRequest(SWITCH,role,tenant,from));
 }
 private static final PartnerModels.ContextRef ENTRY=new PartnerModels.ContextRef("ENTRY",null,null,null);

 @Test void securityRolesAreThePartnerRolesWithSecurityOrOperationCapabilities() {
  for(String role:PartnerCapability.ROLES) {
   boolean security=PartnerCapability.forRole(role).stream().anyMatch(c->c.key().startsWith("security.") || c.key().startsWith("ops."));
   assertEquals(security,ContextSwitchService.SECURITY_ROLES.contains(role),role);
  }
  assertFalse(ContextSwitchService.SECURITY_ROLES.contains("TECHNICIAN"));
  assertTrue(PartnerCapability.ROLES.containsAll(ContextSwitchService.SECURITY_ROLES));
  assertFalse(EnumSet.allOf(PartnerCapability.class).isEmpty());
 }

 @Test void requestNeedsKeyKnownRoleAndAClosedOrigin() {
  java.util.function.Function<org.junit.jupiter.api.function.Executable,String> code=action->{
   var error=assertThrows(BusinessProblem.class,action); assertEquals(400,error.status()); return error.code(); };
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->ContextSwitchService.input(PARTNER,null)));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->ContextSwitchService.input(PARTNER,new PartnerModels.ContextSwitchRequest(null,"SECURITY_LEAD",null,null))));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->input("MANAGER",null,null)));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->input("SECURITY_LEAD",null,new PartnerModels.ContextRef("CENTRAL",null,null,null))));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->input("SECURITY_LEAD",null,new PartnerModels.ContextRef("ENTRY",null,null,T1))));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->input("SECURITY_LEAD",null,new PartnerModels.ContextRef("TENANT",PARTNER,null,T1))));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->input("SECURITY_LEAD",null,new PartnerModels.ContextRef("PARTNER",OTHER,null,null))));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->input("SECURITY_LEAD",null,new PartnerModels.ContextRef("PARTNER",OTHER,"MANAGER",null))));
  var parsed=input("SECURITY_LEAD",null,null);
  assertEquals("ENTRY",parsed.from().kind(),"Sem origem: primeira entrada da janela");
  assertEquals(new PartnerModels.ContextRef("PARTNER",PARTNER,"SECURITY_LEAD",null),parsed.to(),"Destino sempre é o parceiro da rota");
 }

 @Test void enteringASecurityPanelRecordsOneEventPerReachedTenant() {
  var store=store();
  var result=service(store,true).apply(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),input("SECURITY_LEAD",null,ENTRY),"sessao-ficticia-1");
  assertEquals(new PartnerModels.ContextSwitchResult("RECORDED",2),result);
  assertEquals(List.of(T1,T2),store.written.stream().map(PartnerStore.ContextSwitchRow::tenantId).toList());
  assertTrue(store.written.stream().allMatch(row->row.subjectId().equals(PARTNER) && row.source().equals("REMOTE_CENTRAL")));
  assertEquals(2,store.written.stream().map(PartnerStore.ContextSwitchRow::id).distinct().count(),"Um id por condomínio");
  // Mesma troca, mesma chave: mesmos ids (a repetição bate na chave primária do log).
  var again=store();
  service(again,true).apply(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),input("SECURITY_LEAD",null,ENTRY),null);
  assertEquals(store.written.stream().map(PartnerStore.ContextSwitchRow::id).toList(),again.written.stream().map(PartnerStore.ContextSwitchRow::id).toList());
 }

 @Test void originSecurityContextAlsoRecordsTheTenantsItStillReaches() {
  var store=store();
  var from=new PartnerModels.ContextRef("PARTNER",OTHER,"SECURITY_SUPERVISOR",null);
  var result=service(store,true).apply(ACTOR,context(AssuranceLevel.AAL2,"REMOTE_OPERATOR"),input("REMOTE_OPERATOR",null,from),null);
  assertEquals(new PartnerModels.ContextSwitchResult("RECORDED",2),result);
  // T2: destino e origem (BOTH, sujeito é o destino); T3: só a origem (FROM, sujeito é o parceiro de origem).
  assertEquals(List.of(T2,T3),store.written.stream().map(PartnerStore.ContextSwitchRow::tenantId).toList());
  assertEquals(PARTNER,store.written.get(0).subjectId());
  assertEquals(OTHER,store.written.get(1).subjectId());
  assertEquals(Map.of(T2,"BOTH",T3,"FROM"),ContextSwitchService.sides(List.of(T2),List.of(T2,T3)));
  // Saída de um papel de segurança para o técnico grava só o lado da origem.
  var technician=store();
  var leaving=new PartnerModels.ContextRef("PARTNER",PARTNER,"SECURITY_LEAD",null);
  assertEquals(new PartnerModels.ContextSwitchResult("RECORDED",2),
   service(technician,true).apply(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD","TECHNICIAN"),input("TECHNICIAN",null,leaving),null));
  assertTrue(technician.written.stream().allMatch(row->row.subjectId().equals(PARTNER)));
 }

 @Test void secondFactorRoleAndReachAreCheckedBeforeAnyWrite() {
  var store=store();
  var aal1=assertThrows(BusinessProblem.class,()->service(store,true).apply(ACTOR,context(AssuranceLevel.AAL1,"SECURITY_LEAD"),
   input("SECURITY_LEAD",null,ENTRY),null));
  assertEquals("MFA_REQUIRED",aal1.code()); assertEquals(0,store.reads,"Sem AAL2 nenhuma consulta de alcance");
  assertEquals("PARTNER_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->service(store,true).apply(ACTOR,
   context(AssuranceLevel.AAL2,"REMOTE_OPERATOR"),input("SECURITY_LEAD",null,ENTRY),null)).code(),"Papel inativo neste parceiro");
  assertEquals("PARTNER_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->service(store,true).apply(ACTOR,
   context(AssuranceLevel.AAL2,"REMOTE_OPERATOR"),input("REMOTE_OPERATOR",T1,ENTRY),null)).code(),"Condomínio fora da escala");
  assertTrue(store.written.isEmpty());
  var focused=service(store,true).apply(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),input("SECURITY_LEAD",T2,ENTRY),null);
  assertEquals(new PartnerModels.ContextSwitchResult("RECORDED",1),focused);
  assertEquals(T2,store.written.getFirst().tenantId(),"Foco num condomínio grava só nele");
 }

 @Test void nonSecurityRolesSameContextDisabledLogAndNoReachWriteNothing() {
  var store=store();
  assertEquals("NOT_REQUIRED",service(store,true).apply(ACTOR,context(AssuranceLevel.AAL1,"TECHNICIAN"),input("TECHNICIAN",null,ENTRY),null).status(),
   "Técnico não é papel de segurança (V052)");
  var same=new PartnerModels.ContextRef("PARTNER",PARTNER,"SECURITY_LEAD",null);
  assertEquals("NOT_REQUIRED",service(store,true).apply(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),input("SECURITY_LEAD",null,same),null).status(),
   "Contexto igual ao anterior não é troca");
  assertEquals("LOG_DISABLED",service(store,false).apply(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),input("SECURITY_LEAD",null,ENTRY),null).status());
  assertEquals("PARTNER_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->service(store,false).apply(ACTOR,
   context(AssuranceLevel.AAL2,"REMOTE_OPERATOR"),input("REMOTE_OPERATOR",T1,ENTRY),null)).code(),"Log desligado ainda confere o alcance");
  var empty=new FakeStore(Map.of());
  assertEquals("NO_TENANT_REACHED",service(empty,true).apply(ACTOR,context(AssuranceLevel.AAL2,"REMOTE_OPERATOR"),input("REMOTE_OPERATOR",null,ENTRY),null).status(),
   "Operador sem escala vigente: nada a gravar");
  assertTrue(store.written.isEmpty() && empty.written.isEmpty());
  assertEquals("MANAGEMENT",ContextSwitchService.source("PARTNER_ADMIN"));
  assertEquals("REMOTE_CENTRAL",ContextSwitchService.source("REMOTE_OPERATOR"));
 }

 @Test void detailsCarryOnlyKindsAndIdentifiers() {
  var described=ContextSwitchService.describe(new PartnerModels.ContextRef("PARTNER",PARTNER,"SECURITY_LEAD",null));
  assertEquals(List.of("kind","partnerId","role"),List.copyOf(described.keySet()),"Sem campo nulo, ordem estável");
  assertEquals(List.of("kind"),List.copyOf(ContextSwitchService.describe(ENTRY).keySet()));
  assertNotEquals(ContextSwitchService.eventId(ACTOR,SWITCH,"a",T1),ContextSwitchService.eventId(ACTOR,SWITCH,"b",T1),"Conteúdo diferente, id diferente");
  assertNotEquals(ContextSwitchService.eventId(ACTOR,SWITCH,"a",T1),ContextSwitchService.eventId(UUID.randomUUID(),SWITCH,"a",T1),"Outra conta, outro id");
  assertEquals(ContextSwitchService.eventId(ACTOR,SWITCH,"a",T1),ContextSwitchService.eventId(ACTOR,SWITCH,"a",T1));
 }
 
 private static ContextSwitchService.Input exit(String role,PartnerModels.ContextRef to) {
  return ContextSwitchService.exitInput(PARTNER,new PartnerModels.ContextExitRequest(SWITCH,role,to));
 }
 private static final PartnerModels.ContextRef CENTRAL=new PartnerModels.ContextRef("PLATFORM",null,null,null);

 @Test void leavingASecurityPanelForATenantOrTheCentralRecordsTheOriginSide() {
  var store=store();
  var to=new PartnerModels.ContextRef("TENANT",null,null,T3);
  var result=service(store,true).applyExit(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),exit("SECURITY_LEAD",to),"sessao-ficticia-2");
  assertEquals(new PartnerModels.ContextSwitchResult("RECORDED",2),result);
  assertEquals(List.of(T1,T2),store.written.stream().map(PartnerStore.ContextSwitchRow::tenantId).toList(),"Condomínios que a origem ainda alcança");
  assertTrue(store.written.stream().allMatch(row->row.subjectId().equals(PARTNER) && row.details().contains("\"side\":\"FROM\"")
   && row.details().contains("\"to\":{\"kind\":\"TENANT\",\"tenantId\":\""+T3+"\"}")));
  // A saída para a central tem outro conteúdo: outros ids, mesmo com a mesma chave.
  var central=store();
  service(central,true).applyExit(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),exit("SECURITY_LEAD",CENTRAL),null);
  assertNotEquals(store.written.getFirst().id(),central.written.getFirst().id());
  assertTrue(central.written.getFirst().details().contains("\"to\":{\"kind\":\"PLATFORM\"}"));
  // Entrada e saída com a mesma chave nunca colidem.
  var entry=store();
  service(entry,true).apply(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),input("SECURITY_LEAD",null,ENTRY),null);
  assertTrue(entry.written.stream().noneMatch(row->store.written.stream().anyMatch(other->other.id().equals(row.id()))));
 }

 @Test void exitNeedsAClosedDestinationActiveRoleAndSecondFactor() {
  java.util.function.Function<org.junit.jupiter.api.function.Executable,String> code=action->assertThrows(BusinessProblem.class,action).code();
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->exit("SECURITY_LEAD",null)));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->exit("SECURITY_LEAD",ENTRY)));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->exit("SECURITY_LEAD",new PartnerModels.ContextRef("TENANT",null,null,null))));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->exit("SECURITY_LEAD",new PartnerModels.ContextRef("PARTNER",OTHER,"SECURITY_LEAD",null))));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->exit("SECURITY_LEAD",new PartnerModels.ContextRef("PLATFORM",null,null,T1))));
  assertEquals("CONTEXT_SWITCH_INVALID",code.apply(()->exit("MANAGER",CENTRAL)));
  var store=store();
  assertEquals("PARTNER_ACCESS_DENIED",code.apply(()->service(store,true).applyExit(ACTOR,context(AssuranceLevel.AAL2,"REMOTE_OPERATOR"),
   exit("SECURITY_LEAD",CENTRAL),null)));
  assertEquals("MFA_REQUIRED",code.apply(()->service(store,true).applyExit(ACTOR,context(AssuranceLevel.AAL1,"SECURITY_LEAD"),
   exit("SECURITY_LEAD",CENTRAL),null)));
  assertEquals(0,store.reads,"Sem papel ou sem AAL2 nenhuma consulta de alcance");
  assertEquals("NOT_REQUIRED",service(store,true).applyExit(ACTOR,context(AssuranceLevel.AAL1,"TECHNICIAN"),exit("TECHNICIAN",CENTRAL),null).status());
  assertEquals("LOG_DISABLED",service(store,false).applyExit(ACTOR,context(AssuranceLevel.AAL2,"SECURITY_LEAD"),exit("SECURITY_LEAD",CENTRAL),null).status());
  assertEquals("NO_TENANT_REACHED",service(new FakeStore(Map.of()),true).applyExit(ACTOR,context(AssuranceLevel.AAL2,"REMOTE_OPERATOR"),
   exit("REMOTE_OPERATOR",CENTRAL),null).status());
  assertTrue(store.written.isEmpty());
 }
}
