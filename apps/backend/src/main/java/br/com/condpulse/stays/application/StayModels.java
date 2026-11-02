package br.com.condpulse.stays.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Permanências (CPX-015 a CPX-019, V055). Toda entrada registrada abre uma permanência; a saída a fecha. "Aberta" não
 * significa que a pessoa continua no condomínio, só que não há registro de saída (R5.10): a tela mostra a origem do dado.
 */
public final class StayModels {
 private StayModels() {}
 public static final List<String> KINDS=List.of("VISITOR","SERVICE_PROVIDER","DELIVERY","CONSTRUCTION","EVENT_GUEST");
 public static final Set<String> KIND_SET=Set.copyOf(KINDS);
 public static final int PAGE=200;
 public static final int DAY_CLOSE_LIMIT=500;
 /** Dias para trás aceitos no relatório de fechamento. */
 public static final int DAY_CLOSE_DAYS=31;

 /**
  * Permanência. origin: como a entrada foi registrada (MANUAL = portaria, V014); basis: ARRIVAL, PERMIT ou RULE;
  * expectedSource: PERMIT, RULE, TENANT_RULE ou PLATFORM_DEFAULT. level: nível pelo horário agora (NONE, DUE, L1, L2);
  * recordedLevel: último nível gravado pelo worker no Log de Segurança. justification só para a equipe (null na unidade).
  */
 public record Stay(UUID stayId,UUID unitId,String unitLabel,String blockName,String visitorName,String documentLast4,String kind,
   String origin,String basis,Instant enteredAt,Instant expectedExitAt,String expectedSource,String state,String closure,Instant closedAt,
   String level,String recordedLevel,boolean overdue,boolean justified,String justification,Instant justifiedAt,UUID entryEventId,
   UUID exitEventId,UUID reservationId,String amenityName,boolean controlEnabled) {
  public Stay(UUID stayId,UUID unitId,String unitLabel,String blockName,String visitorName,String documentLast4,String kind,
   String origin,String basis,Instant enteredAt,Instant expectedExitAt,String expectedSource,String state,String closure,Instant closedAt,
   String level,String recordedLevel,boolean overdue,boolean justified,String justification,Instant justifiedAt,UUID entryEventId,
   UUID exitEventId) {
   this(stayId,unitId,unitLabel,blockName,visitorName,documentLast4,kind,origin,basis,enteredAt,expectedExitAt,expectedSource,state,closure,closedAt,level,recordedLevel,overdue,justified,justification,justifiedAt,entryEventId,exitEventId,null,null,true);
  }
  public Stay forUnit() {
   return new Stay(stayId,unitId,unitLabel,blockName,visitorName,documentLast4,kind,origin,basis,enteredAt,expectedExitAt,expectedSource,
    state,closure,closedAt,level,recordedLevel,overdue,justified,null,justifiedAt,entryEventId,exitEventId,reservationId,amenityName,controlEnabled);
  }
 }
 public record StayPage(List<Stay> stays,boolean truncated,Instant generatedAt) {
  public StayPage { stays=List.copyOf(stays); }
 }
 /** Fechamento do dia (CPX-019): entradas do dia (fuso do condomínio) sem saída registrada até o fim do dia. */
 public record DayClose(LocalDate date,String timeZone,Instant from,Instant until,Instant generatedAt,List<Stay> entries,boolean truncated,
   int stillOpen,String listHash) {
  public DayClose { entries=List.copyOf(entries); }
 }
 public record Justify(String reason) {}
 public record Mutation(Stay stay,boolean replayed) {}
 public record Rule(String kind,int maxMinutes,int l1AfterMinutes,int l2AfterMinutes,String source,Long version,
   boolean enabled,String startsLocal,String endsLocal) {
  public Rule(String kind,int maxMinutes,int l1AfterMinutes,int l2AfterMinutes,String source,Long version) {
   this(kind,maxMinutes,l1AfterMinutes,l2AfterMinutes,source,version,true,null,null);
  }
 }
 public record Rules(List<Rule> rules) {
  public Rules { rules=List.copyOf(rules); }
 }
 /** version null cria a regra do tipo; com versão, atualiza só se ainda for a mesma. */
 public record RuleChange(Integer maxMinutes,Integer l1AfterMinutes,Integer l2AfterMinutes,Long version,
   Boolean enabled,Boolean scheduleEnabled,String startsLocal,String endsLocal) {
  public RuleChange(Integer maxMinutes,Integer l1AfterMinutes,Integer l2AfterMinutes,Long version) {
   this(maxMinutes,l1AfterMinutes,l2AfterMinutes,version,null,null,null,null);
  }
 }
}
