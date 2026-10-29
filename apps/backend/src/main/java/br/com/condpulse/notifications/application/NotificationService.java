package br.com.condpulse.notifications.application;

import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.notifications.infrastructure.NotificationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/** Caixa interna da unidade (parte do CP-035): leitura dos próprios avisos e abertura autenticada. */
@Service @Profile("api")
public class NotificationService {
 private final TenantTransactionExecutor transactions;
 private final NotificationStore store;
 public NotificationService(TenantTransactionExecutor transactions,NotificationStore store) {
  this.transactions=transactions; this.store=store;
 }
 public NotificationModels.Page unit(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,context->{
   if(unit==null || !store.resident(tenant,unit)) throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
   return store.page(tenant,unit);
  });
 }
 public NotificationModels.Notification open(UUID actor,UUID tenant,UUID notification) {
  return execute(actor,tenant,context->{
   var opened=notification==null?null:store.open(tenant,notification);
   if(opened==null) throw new BusinessProblem("NOTIFICATION_NOT_FOUND",404,"Aviso não encontrado.");
   return opened;
  });
 }
 private <T> T execute(UUID actor,UUID tenant,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,AssuranceLevel.AAL1,action);
  } catch(DataAccessException | TransactionException error) {
   throw new BusinessProblem("NOTIFICATION_DEPENDENCY_UNAVAILABLE",503,"Não foi possível consultar os avisos agora. Tente novamente.");
  }
 }
}
