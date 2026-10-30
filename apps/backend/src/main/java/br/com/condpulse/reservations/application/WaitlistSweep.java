package br.com.condpulse.reservations.application;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.reservations.infrastructure.WaitlistSweepStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Faz a fila andar sem requisição da API: oferta vencida, pedido sem decisão no prazo e período liberado. Cada área numa
 * transação própria do worker; falha numa área (trava, banco) não impede as demais e volta na próxima passada.
 */
@Service @Profile("worker")
public class WaitlistSweep {
 private static final System.Logger LOG=System.getLogger(WaitlistSweep.class.getName());
 private final WorkerTransactions transactions;
 private final WaitlistSweepStore store;
 public WaitlistSweep(WorkerTransactions transactions,WaitlistSweepStore store) { this.transactions=transactions; this.store=store; }
 /** Devolve quantas ofertas foram criadas na passada. */
 public int runOnce() {
  int offered=0;
  for(UUID tenant:transactions.catalog(store::activeTenants)) {
   UUID after=null;
   while(true) {
    UUID cursor=after;
    List<UUID> amenities=transactions.inTenant(tenant,()->store.amenities(tenant,cursor));
    for(UUID amenity:amenities) {
     try { offered+=transactions.inTenant(tenant,()->store.offer(tenant,amenity)); }
     catch(DataAccessException failure) {
      LOG.log(System.Logger.Level.WARNING,"Passada da fila adiada para uma área: {0}",failure.getClass().getSimpleName());
     }
    }
    if(amenities.size()<WaitlistSweepStore.AMENITY_PAGE) break;
    after=amenities.getLast();
   }
  }
  return offered;
 }
}
