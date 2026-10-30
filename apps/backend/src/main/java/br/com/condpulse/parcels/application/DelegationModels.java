package br.com.condpulse.parcels.application;

import java.time.Instant;
import java.util.UUID;

/** Retirada delegada (CP-041). O código nunca sai do servidor depois da criação; a delegação mostra só o resumo. */
public final class DelegationModels {
 private DelegationModels() {}
 /** state: ACTIVE, USED, REVOKED, LOCKED, CLOSED ou EXPIRED (ACTIVE com validade vencida). */
 public record Delegation(UUID delegationId,UUID parcelId,UUID unitId,String delegateName,String documentLast4,Instant validUntil,
     String state,int attempts,int attemptsLeft,boolean createdByMe,Instant createdAt,Instant usedAt,Instant closedAt,long version) {}
 /** Delegação atual da encomenda (a mais recente) ou null quando nunca houve. */
 public record Current(Delegation delegation) {}
 public record Create(String delegateName,String documentLast4,Instant validUntil) {}
 /** code: 8 dígitos, só na resposta da criação; repetição da mesma chave devolve null. */
 public record Issued(Delegation delegation,String code,boolean replayed,int status) {}
 /** Corpo da resposta da criação. */
 public record Created(Delegation delegation,String code) {}
 public record Revoke(Long expectedVersion) {}
 public record CodeHandoff(String code,Boolean identityChecked,Long expectedVersion) {}
}
