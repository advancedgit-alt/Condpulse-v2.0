package br.com.condpulse.accesscredentials.application;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Lotes de destinos dentro do limite do contrato do adaptador (até 50 por comando ou consulta). */
class CredentialReconcilerTest {
 @Test void destinationsAreSplitIntoBatchesOfAtMostFifty() {
  var destinations=IntStream.range(0,121).mapToObj(i->"leitor-"+i).toList();
  var chunks=CredentialReconciler.chunks(destinations);
  assertEquals(List.of(50,50,21),chunks.stream().map(List::size).toList());
  assertEquals(destinations,chunks.stream().flatMap(List::stream).toList());
  assertTrue(CredentialReconciler.chunks(List.of()).isEmpty());
  assertEquals(List.of(List.of("a")),CredentialReconciler.chunks(List.of("a")));
 }
}
