package br.com.condpulse.partners.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.securitylog.application.SecurityLogModels;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** CPX-012 a CPX-014: critério do semáforo, validação dos pedidos e resumo do dossiê, sem banco. */
class SecurityCommandRulesTest {
 private static SecurityCommandModels.Indicators indicators(int offline,int unknown,int occurrences,int critical,int open,int criticalOpen,int onDuty) {
  return new SecurityCommandModels.Indicators(3,offline,unknown,occurrences,critical,open,criticalOpen,5,onDuty);
 }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }

 @Test void lightFollowsTheDocumentedCriteria() {
  assertEquals(new SecurityCommandRules.Light("GREEN",List.of()),SecurityCommandRules.light(indicators(0,0,0,0,0,0,1)),
   "Aberturas sem revisão (5) não mudam a cor");
  assertEquals(new SecurityCommandRules.Light("RED",List.of("CRITICAL_ALERTS_UNLINKED")),SecurityCommandRules.light(indicators(0,0,0,1,0,0,1)));
  assertEquals(new SecurityCommandRules.Light("RED",List.of("CRITICAL_INCIDENTS_OPEN")),SecurityCommandRules.light(indicators(0,0,0,0,1,1,1)),
   "Incidente crítico aberto não conta de novo como incidente comum");
  assertEquals(new SecurityCommandRules.Light("AMBER",List.of("DEVICES_OFFLINE","DEVICES_UNKNOWN","NO_OPERATOR_ON_DUTY",
   "SECURITY_OCCURRENCES_OPEN","INCIDENTS_OPEN")),SecurityCommandRules.light(indicators(1,1,1,0,1,0,0)));
  var both=SecurityCommandRules.light(indicators(2,0,0,3,0,0,1));
  assertEquals("RED",both.light()); assertEquals(List.of("CRITICAL_ALERTS_UNLINKED","DEVICES_OFFLINE"),both.reasons(),"Motivos vermelhos primeiro");
  assertTrue(SecurityCommandRules.rank("RED")<SecurityCommandRules.rank("AMBER") && SecurityCommandRules.rank("AMBER")<SecurityCommandRules.rank("GREEN"));
  assertEquals(List.of("DOORS_HELD_OPEN","STAYS_OVERDUE"),SecurityCommandRules.UNAVAILABLE,"Sem fonte nesta versão: nunca zero");
 }

 @Test void requestsAreValidatedBeforeAnyTransaction() {
  assertEquals("INCIDENT_TITLE_INVALID",code(()->SecurityCommandRules.title(" ab ")));
  assertEquals("Porta forçada",SecurityCommandRules.title("  Porta   forçada "));
  assertEquals("INCIDENT_SEVERITY_INVALID",code(()->SecurityCommandRules.severity("INFO")));
  assertEquals("INCIDENT_EVENTS_REQUIRED",code(()->SecurityCommandRules.events(List.of(),true)));
  assertEquals(List.of(),SecurityCommandRules.events(null,false));
  var many=new ArrayList<UUID>(); for(int i=0;i<51;i++) many.add(UUID.randomUUID());
  assertEquals("INCIDENT_EVENTS_INVALID",code(()->SecurityCommandRules.events(many,true)));
  UUID event=UUID.randomUUID();
  assertEquals(List.of(event),SecurityCommandRules.events(List.of(event,event),true),"Repetido entra uma vez");
  assertEquals("INCIDENT_VERSION_REQUIRED",code(()->SecurityCommandRules.version(null)));
  assertEquals("INCIDENT_OUTCOME_INVALID",code(()->SecurityCommandRules.outcome("DONE")));
  assertEquals("INCIDENT_REPORT_REQUIRED",code(()->SecurityCommandRules.report("curto")));
  assertEquals("INCIDENT_DOSSIER_REASON_REQUIRED",code(()->SecurityCommandRules.exportReason("curto")));
  assertEquals("OPEN",SecurityCommandRules.state(null));
  assertEquals("INCIDENT_STATE_INVALID",code(()->SecurityCommandRules.state("ALL")));
  assertEquals("REMOTE_OPEN_REVIEW_INVALID",code(()->SecurityCommandRules.review(new SecurityCommandModels.ReviewRequest("OK",null,null))));
  assertEquals("REMOTE_OPEN_REVIEW_NOTE_REQUIRED",code(()->SecurityCommandRules.review(new SecurityCommandModels.ReviewRequest("IRREGULAR",null,null))));
  assertEquals("REMOTE_OPEN_REVIEW_NOTE_REQUIRED",code(()->SecurityCommandRules.review(new SecurityCommandModels.ReviewRequest("CONFERRED"," ",UUID.randomUUID()))));
  assertEquals(new SecurityCommandRules.ReviewInput("CONFERRED",null,null),SecurityCommandRules.review(new SecurityCommandModels.ReviewRequest("CONFERRED","",null)));
  assertEquals("PENDING",SecurityCommandRules.reviewFilter(null));
  assertEquals("REMOTE_OPEN_FILTER_INVALID",code(()->SecurityCommandRules.reviewFilter("OPEN")));
  assertEquals("REMOTE_OPEN_LIMIT_INVALID",code(()->SecurityCommandRules.limit(201)));
 }

 @Test void dossierDigestIsTheDocumentedCanonicalForm() throws Exception {
  UUID tenant=UUID.fromString("00000000-0000-4000-8000-0000000000d1"),incident=UUID.fromString("53000000-0000-4000-8000-0000000000d1");
  var first=event(3,"a".repeat(64)); var second=event(7,"b".repeat(64));
  String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
   ("cpdx1|"+tenant+"|"+incident+"|3:"+"a".repeat(64)+"|7:"+"b".repeat(64)).getBytes(StandardCharsets.UTF_8)));
  assertEquals(expected,SecurityCommandRules.eventsDigest(tenant,incident,List.of(first,second)));
  assertNotEquals(expected,SecurityCommandRules.eventsDigest(tenant,incident,List.of(second,first)),"Ordem faz parte do resumo");
 }
 private static SecurityLogModels.Event event(long seq,String hash) {
  return new SecurityLogModels.Event(UUID.randomUUID(),seq,Instant.EPOCH,Instant.EPOCH,null,"INCIDENT_OPENED","CRITICAL","REMOTE_CENTRAL",null,
   null,"SECURITY_LEAD","INCIDENT",null,null,null,null,null,null,null,null,null,Map.of(),"0".repeat(64),hash);
 }
}
