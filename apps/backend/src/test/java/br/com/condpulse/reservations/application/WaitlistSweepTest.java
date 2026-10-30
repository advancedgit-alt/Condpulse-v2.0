package br.com.condpulse.reservations.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.reservations.infrastructure.WaitlistSweepStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A passada percorre todas as áreas em páginas e uma área com falha não impede as demais. */
class WaitlistSweepTest {
 @SuppressWarnings("unchecked")
 @Test void everyAmenityIsVisitedAcrossPagesAndAFailureDoesNotStopThePass() {
  var transactions=mock(WorkerTransactions.class);
  when(transactions.catalog(any())).thenAnswer(call->((Supplier<Object>)call.getArgument(0)).get());
  when(transactions.inTenant(any(),any())).thenAnswer(call->((Supplier<Object>)call.getArgument(1)).get());
  var store=mock(WaitlistSweepStore.class);
  UUID tenant=UUID.randomUUID();
  List<UUID> all=IntStream.range(0,WaitlistSweepStore.AMENITY_PAGE+5).mapToObj(i->new UUID(1,i)).toList();
  when(store.activeTenants()).thenReturn(List.of(tenant));
  when(store.amenities(eq(tenant),isNull())).thenReturn(all.subList(0,WaitlistSweepStore.AMENITY_PAGE));
  when(store.amenities(tenant,all.get(WaitlistSweepStore.AMENITY_PAGE-1))).thenReturn(all.subList(WaitlistSweepStore.AMENITY_PAGE,all.size()));
  List<UUID> visited=new ArrayList<>();
  when(store.offer(eq(tenant),any())).thenAnswer(call->{
   UUID amenity=call.getArgument(1); visited.add(amenity);
   if(amenity.equals(all.get(3))) throw new CannotAcquireLockException("trava");
   return 1;
  });
  assertEquals(all.size()-1,new WaitlistSweep(transactions,store).runOnce());
  assertEquals(all,visited,"Todas as áreas, inclusive depois da primeira página");
 }
}
