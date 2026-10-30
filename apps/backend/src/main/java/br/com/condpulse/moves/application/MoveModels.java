package br.com.condpulse.moves.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mudança e entrega de móveis (CP-042). A aprovação libera o período combinado; não reserva elevador nem área. */
public final class MoveModels {
 private MoveModels() {}
 /** Regras do condomínio exibidas à unidade; dias ISO (1 = segunda) e horário local. */
 public record Rules(String rulesText,List<Integer> weekDays,String startTime,String endTime,int minNoticeHours,int maxDurationMinutes,
     int version,Instant updatedAt) {}
 public record CurrentRules(Rules rules) {}
 public record RulesUpdate(String rulesText,List<Integer> weekDays,String startTime,String endTime,Integer minNoticeHours,
     Integer maxDurationMinutes,Integer expectedVersion) {}
 /** state: REQUESTED, APPROVED, REJECTED ou CANCELLED. */
 public record Move(UUID moveId,UUID unitId,String unitLabel,String blockName,String kind,Instant startsAt,Instant endsAt,String providerName,
     String vehiclePlate,String items,int rulesVersion,String state,String decisionNote,Instant decidedAt,boolean createdByMe,
     Instant createdAt,long version) {}
 public record Page(List<Move> moves,boolean truncated) {
  public Page { moves=List.copyOf(moves); }
 }
 public record Create(String kind,Instant startsAt,Instant endsAt,String providerName,String vehiclePlate,String items,Integer acceptedRulesVersion) {}
 public record Mutation(Move move,boolean replayed,int status) {}
 public record Decision(String decision,String note,Long expectedVersion) {}
 public record Cancel(String note,Long expectedVersion) {}
}
