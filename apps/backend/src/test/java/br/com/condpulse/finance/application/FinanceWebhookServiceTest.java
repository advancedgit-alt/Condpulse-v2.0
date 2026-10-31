package br.com.condpulse.finance.application;

import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinanceWebhookServiceTest {
 private static final String TOKEN="SYNTHETIC_webhook_token_0123456789abcdef";
 @Test void weakOrMissingTokenConfigurationFailsClosed() {
  for(String bad:new String[]{null,"","curto","x".repeat(256),"com espaço 0123456789abcdef0123456789"})
   assertThrows(IllegalArgumentException.class,()->new FinanceWebhookService(bad,null,null));
 }
 @Test void wrongTokenIsRefusedBeforeAnyReadAndNothingLeaks() {
  var service=new FinanceWebhookService(TOKEN,null,null);
  for(String bad:new String[]{null,"",TOKEN+"x",TOKEN.substring(1),TOKEN.toUpperCase()}) {
   var error=assertThrows(BusinessProblem.class,()->service.handle(bad,"{}"));
   assertEquals("FINANCE_WEBHOOK_UNAUTHORIZED",error.code()); assertEquals(401,error.status());
   assertFalse(error.getMessage().contains(TOKEN));
  }
  assertEquals("FINANCE_WEBHOOK_INVALID",assertThrows(BusinessProblem.class,()->service.handle(TOKEN,"[]")).code());
  assertEquals(FinanceWebhookService.Outcome.IGNORED,service.handle(TOKEN,"{\"id\":\"evt_1\",\"event\":\"TRANSFER_CREATED\"}"));
  assertFalse(service.toString().contains(TOKEN));
 }
}
