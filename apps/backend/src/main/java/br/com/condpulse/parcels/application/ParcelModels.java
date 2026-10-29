package br.com.condpulse.parcels.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Encomendas (CP-029/CP-030/CP-031): recebimento e entrega pela portaria, consulta pela unidade. Sem identificadores de
 * contas da equipe nas respostas; quem recebeu, quem entregou e quem anulou ficam na auditoria.
 */
public final class ParcelModels {
 private ParcelModels() {}
 /** Entrega registrada: pessoa com vínculo escolhida pela portaria, horário e forma de conferência. */
 public record Handoff(String recipientName,Instant deliveredAt,String evidenceKind) {}
 /** Registro indevido (unidade errada, duplicidade) anulado pela portaria: só a portaria vê; motivo e horário. */
 public record Parcel(UUID parcelId,UUID unitId,String unitLabel,String blockName,int quantity,String description,
   String storageLocation,String status,Instant receivedAt,long version,Handoff handoff,String voidReason,Instant voidedAt) {
  public Parcel(UUID parcelId,UUID unitId,String unitLabel,String blockName,int quantity,String description,
    String storageLocation,String status,Instant receivedAt,long version,Handoff handoff) {
   this(parcelId,unitId,unitLabel,blockName,quantity,description,storageLocation,status,receivedAt,version,handoff,null,null);
  }
 }
 public record Page(List<Parcel> parcels,boolean truncated) {
  public Page { parcels=List.copyOf(parcels); }
 }
 /** confirmDuplicate: a portaria conferiu o aviso de registro parecido recente e confirma que é outra encomenda. */
 public record Receive(UUID unitId,Integer quantity,String description,String storageLocation,Boolean confirmDuplicate) {}
 /** Retirada assistida: a portaria escolhe a pessoa entre os vínculos da unidade e confere o documento. */
 public record Deliver(UUID recipientMembershipId,Boolean identityChecked,Long expectedVersion) {}
 public record VoidRequest(String reason,Long expectedVersion) {}
 /** Pessoa que pode retirar: vínculo residencial vigente da unidade (dependente só com permissão explícita). */
 public record Recipient(UUID membershipId,String displayName,String role) {}
 public record Mutation(Parcel parcel,boolean replayed,int status) {}
 public record UnitOption(UUID unitId,String label,String blockName) {}
}
