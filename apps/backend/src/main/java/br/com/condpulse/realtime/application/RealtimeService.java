package br.com.condpulse.realtime.application;

import java.util.Map;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.realtime.infrastructure.RealtimeStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Estado resumido da unidade para o canal em tempo real (CP-036). Cada consulta repete toda a validação do condomínio
 * (conta ativa, vínculo vigente, unidade ativa) numa transação curta: é assim que a revogação fecha o canal.
 */
@Service @Profile("api")
@ConditionalOnProperty(name="condpulse.realtime.enabled",havingValue="true")
public class RealtimeService {
 private final TenantTransactionExecutor transactions;
 private final RealtimeStore store;
 public RealtimeService(TenantTransactionExecutor transactions,RealtimeStore store) {
  this.transactions=transactions; this.store=store;
 }
 public Map<String,String> snapshot(UUID actor,UUID tenant,UUID unit) {
  try {
   return transactions.execute(actor,tenant,AssuranceLevel.AAL1,context->{
    if(unit==null || !store.resident(tenant,unit))
     throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
    return store.fingerprints(tenant,unit);
   });
  } catch(DataAccessException | TransactionException error) {
   throw new BusinessProblem("REALTIME_DEPENDENCY_UNAVAILABLE",503,"Atualização automática indisponível agora.");
  }
 }
}
