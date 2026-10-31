package br.com.condpulse.partners.application;

import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Validação dos pedidos de parceiro antes de abrir transação. */
class PartnerServiceRulesTest {
 private static String code(org.junit.jupiter.api.function.Executable action) {
  var error=assertThrows(BusinessProblem.class,action); assertEquals(400,error.status()); return error.code();
 }
 @Test void memberRequestNeedsAccountKnownRoleNameAndReason() {
  UUID account=UUID.randomUUID();
  assertEquals("PARTNER_MEMBER_INVALID",code(()->PartnerService.member(null)));
  assertEquals("PARTNER_MEMBER_INVALID",code(()->PartnerService.member(new PartnerModels.AddMember(null,"REMOTE_OPERATOR","Nome","Motivo"))));
  assertEquals("PARTNER_ROLE_INVALID",code(()->PartnerService.member(new PartnerModels.AddMember(account,"CONCIERGE","Nome","Motivo"))));
  assertEquals("PARTNER_ROLE_INVALID",code(()->PartnerService.member(new PartnerModels.AddMember(account,"PLATFORM_ADMIN","Nome","Motivo"))));
  assertEquals("PARTNER_MEMBER_INVALID",code(()->PartnerService.member(new PartnerModels.AddMember(account,"TECHNICIAN"," ","Motivo"))));
  assertEquals("PARTNER_MEMBER_INVALID",code(()->PartnerService.member(new PartnerModels.AddMember(account,"TECHNICIAN","Nome‮","Motivo"))));
  assertEquals("AUDIT_REASON_REQUIRED",code(()->PartnerService.member(new PartnerModels.AddMember(account,"TECHNICIAN","Nome","x"))));
  var input=PartnerService.member(new PartnerModels.AddMember(account,"SECURITY_LEAD","  Pessoa   Ficticia ","  Contratacao ficticia "));
  assertEquals("Pessoa Ficticia",input.displayName()); assertEquals("Contratacao ficticia",input.reason());
 }
 @Test void scheduleRequestNeedsMemberTenantEndAndAtMostThirtyOneDays() {
  UUID member=UUID.randomUUID(),tenant=UUID.randomUUID();
  Instant start=Instant.parse("2030-01-01T08:00:00Z");
  assertEquals("PARTNER_SCHEDULE_INVALID",code(()->PartnerService.schedule(null)));
  assertEquals("PARTNER_SCHEDULE_INVALID",code(()->PartnerService.schedule(new PartnerModels.CreateSchedule(member,tenant,start,null,"Turno"))));
  assertEquals("PARTNER_SCHEDULE_INVALID",code(()->PartnerService.schedule(new PartnerModels.CreateSchedule(member,null,start,start.plusSeconds(60),"Turno"))));
  assertEquals("PARTNER_SCHEDULE_INVALID",code(()->PartnerService.schedule(new PartnerModels.CreateSchedule(member,tenant,start,start,"Turno"))));
  assertEquals("PARTNER_SCHEDULE_INVALID",code(()->PartnerService.schedule(new PartnerModels.CreateSchedule(member,tenant,start,
   start.plusSeconds(31L*86400+1),"Turno"))));
  assertEquals("AUDIT_REASON_REQUIRED",code(()->PartnerService.schedule(new PartnerModels.CreateSchedule(member,tenant,start,start.plusSeconds(3600),null))));
  assertNull(PartnerService.schedule(new PartnerModels.CreateSchedule(member,tenant,null,start,"Turno ficticio")).startsAt(),
   "Sem início, o banco usa o próprio relógio");
 }
}
