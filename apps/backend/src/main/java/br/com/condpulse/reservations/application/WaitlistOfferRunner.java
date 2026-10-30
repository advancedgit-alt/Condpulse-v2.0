package br.com.condpulse.reservations.application;

import java.util.UUID;
import br.com.condpulse.reservations.infrastructure.WaitlistStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service @Profile("api")
public class WaitlistOfferRunner implements WaitlistOffers {
 private static final System.Logger LOG=System.getLogger(WaitlistOfferRunner.class.getName());
 private final TenantTransactionExecutor transactions;
 private final WaitlistStore store;
 public WaitlistOfferRunner(TenantTransactionExecutor transactions,WaitlistStore store) {
  this.transactions=transactions; this.store=store;
 }
 @Override public void offerForAmenity(UUID actor,UUID tenant,UUID amenity) {
  if(amenity==null) return;
  run(actor,tenant,()->transactions.execute(actor,tenant,context->store.offer(tenant,amenity)));
 }
 @Override public void offerForReservation(UUID actor,UUID tenant,UUID reservation) {
  if(reservation==null) return;
  run(actor,tenant,()->transactions.execute(actor,tenant,context->{
   UUID amenity=store.amenityOf(tenant,reservation);
   return amenity==null?0:store.offer(tenant,amenity);
  }));
 }
 private static void run(UUID actor,UUID tenant,java.util.function.Supplier<Integer> action) {
  try { action.get(); }
  catch(DataAccessException | BusinessProblem | IllegalStateException failure) {
   LOG.log(System.Logger.Level.WARNING,"Oferta da fila de espera adiada: {0}",failure.getClass().getSimpleName());
  }
 }
}
