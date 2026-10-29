package br.com.condpulse.parcels.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Encomendas (CP-029/CP-030/CP-031): recebimento e entrega pela portaria, consulta pela unidade. Sem identificadores de
 * contas da equipe nas respostas; quem recebeu e quem entregou ficam na auditoria.
 */
public final class ParcelModels {
 private ParcelModels() {}
 /** Entrega registrada: pessoa recebedora informada pela portaria, horário e forma de conferência. */
 public record Handoff(String recipientName,Instant deliveredAt,String evidenceKind) {}
 public record Parcel(UUID parcelId,UUID unitId,String unitLabel,String blockName,int quantity,String description,
   String storageLocation,String status,Instant receivedAt,long version,Handoff handoff) {}
 public record Page(List<Parcel> parcels,boolean truncated) {
  public Page { parcels=List.copyOf(parcels); }
 }
 public record Receive(UUID unitId,Integer quantity,String description,String storageLocation) {}
 /** Entrega assistida: a portaria confere o documento da pessoa e registra o nome; código e delegação virão depois. */
 public record Deliver(String recipientName,Boolean identityChecked,Long expectedVersion) {}
 public record Mutation(Parcel parcel,boolean replayed,int status) {}
 public record UnitOption(UUID unitId,String label,String blockName) {}
}
