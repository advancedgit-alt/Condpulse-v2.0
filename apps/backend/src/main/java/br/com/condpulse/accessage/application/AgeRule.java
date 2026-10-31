package br.com.condpulse.accessage.application;

import java.time.LocalDate;
import java.time.Month;
import java.util.Objects;

/**
 * Anos completos numa data civil do condomínio (AC-006). Sem biblioteca implícita para 29/02: o critério é parâmetro do
 * condomínio (cp.facial_access_settings.leap_day_rule).
 * <ul>
 * <li>MARCH_1 (padrão provisório): quem nasceu em 29/02 completa anos em 01/03 nos anos não bissextos.</li>
 * <li>FEBRUARY_28: completa em 28/02 nos anos não bissextos.</li>
 * </ul>
 * Na véspera do aniversário ainda não completou; no próprio dia (00:00 local) já completou. Data futura ou ausente não
 * produz idade.
 */
public final class AgeRule {
 private AgeRule() {}
 public enum LeapDayRule { MARCH_1, FEBRUARY_28 }
 /** Resultado para uma idade mínima; idade desconhecida nunca presume maioridade (HAC-033). */
 public enum Outcome { MEETS, DOES_NOT_MEET, UNKNOWN }

 public static int completedYears(LocalDate birth,LocalDate on,LeapDayRule rule) {
  Objects.requireNonNull(birth); Objects.requireNonNull(on); Objects.requireNonNull(rule);
  if(birth.isAfter(on)) throw new IllegalArgumentException("Nascimento posterior à data avaliada.");
  int years=on.getYear()-birth.getYear();
  return on.isBefore(birthdayIn(birth,on.getYear(),rule))?years-1:years;
 }
 /** Data em que o aniversário acontece no ano informado, conforme o critério do condomínio. */
 public static LocalDate birthdayIn(LocalDate birth,int year,LeapDayRule rule) {
  if(birth.getMonth()==Month.FEBRUARY && birth.getDayOfMonth()==29 && !java.time.Year.isLeap(year))
   return rule==LeapDayRule.MARCH_1?LocalDate.of(year,3,1):LocalDate.of(year,2,28);
  return birth.withYear(year);
 }
 public static Outcome fromBirthDate(LocalDate birth,LocalDate on,LeapDayRule rule,int minimumAge) {
  if(birth==null || on==null || birth.isAfter(on)) return Outcome.UNKNOWN;
  return completedYears(birth,on,rule)>=minimumAge?Outcome.MEETS:Outcome.DOES_NOT_MEET;
 }
 /**
  * Atestação verificada em attestedOn, avaliada em on. Limite inferior ("tinha pelo menos N") continua valendo depois,
  * porque a idade só cresce. Limite superior ("tinha no máximo M", ou "não tinha N", isto é, no máximo N-1) envelhece:
  * em on a pessoa pode ter até M + anos completos desde attestedOn + 1 (no mesmo dia, M). Só conclui "não atende"
  * enquanto esse limite ainda ficar abaixo da idade pedida; depois disso é UNKNOWN (pede nova evidência).
  */
 public static Outcome fromAttestation(Integer attestedMinimum,Integer attestedMaximum,Boolean predicate,int minimumAge,
   LocalDate attestedOn,LocalDate on) {
  if(predicate==null || attestedOn==null || on==null || on.isBefore(attestedOn)) return Outcome.UNKNOWN;
  Integer lower=null,upper=null;
  if(predicate) { lower=attestedMinimum; upper=attestedMaximum; }
  else if(attestedMinimum!=null && attestedMaximum==null) upper=attestedMinimum-1;
  if(lower!=null && lower>=minimumAge) return Outcome.MEETS;
  if(upper!=null) {
   int grown=on.equals(attestedOn)?0:completedYears(attestedOn,on,LeapDayRule.MARCH_1)+1;
   if(upper+grown<minimumAge) return Outcome.DOES_NOT_MEET;
  }
  return Outcome.UNKNOWN;
 }
}
