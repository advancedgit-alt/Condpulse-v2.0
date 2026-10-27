package br.com.condpulse.reservations.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/** Política inicial explícita: uma janela diurna por dia e limites configurados. */
public record ReservationPolicy(int schemaVersion,Set<DayOfWeek> weekDays,LocalTime opensAt,
 LocalTime closesAt,int minimumLeadMinutes,int maximumAdvanceDays,int maximumDurationMinutes,
 int maximumActivePerUnit,int cancellationLeadMinutes) {
 public ReservationPolicy {
  if(schemaVersion!=1 || weekDays==null || weekDays.isEmpty() || opensAt==null || closesAt==null
      || !opensAt.isBefore(closesAt))
   throw new IllegalArgumentException("Política de reserva inválida ou não suportada.");
  for(var day:weekDays) if(day==null) throw new IllegalArgumentException("Dia da semana inválido.");
  if(minimumLeadMinutes<0 || minimumLeadMinutes>525600 || maximumAdvanceDays<1 || maximumAdvanceDays>366
      || maximumDurationMinutes<1 || maximumDurationMinutes>1440 || maximumActivePerUnit<1
      || maximumActivePerUnit>100 || cancellationLeadMinutes<0 || cancellationLeadMinutes>525600)
   throw new IllegalArgumentException("Limites da política de reserva inválidos.");
  weekDays=Set.copyOf(weekDays);
 }

 public ReservationWindow book(Instant start,Instant end,Instant now,ZoneId zone,int bufferMinutes) {
  if(start==null || end==null || now==null || zone==null || bufferMinutes<0 || bufferMinutes>1440)
   throw new IllegalArgumentException("Dados do período de reserva inválidos.");
  if(start.getEpochSecond()%60!=0 || end.getEpochSecond()%60!=0 || start.getNano()!=0 || end.getNano()!=0)
   throw new IllegalArgumentException("O período deve ter precisão de minutos, sem arredondamento.");
  if(!start.isAfter(now) || !start.isBefore(end))
   throw new IllegalArgumentException("A reserva deve começar no futuro e terminar depois do início.");
  try {
   if(start.isBefore(now.plusSeconds(minimumLeadMinutes*60L)))
    throw new IllegalArgumentException("Antecedência mínima da reserva não atendida.");
   if(Duration.between(start,end).compareTo(Duration.ofMinutes(maximumDurationMinutes))>0)
    throw new IllegalArgumentException("A reserva excede a duração máxima permitida.");
   var window=ReservationWindow.withCleaning(start,end,Duration.ZERO,Duration.ofMinutes(bufferMinutes));
   var localStart=start.atZone(zone);
   var localEnd=end.atZone(zone);
   var localOccupiedEnd=window.occupiedUntil().atZone(zone);
   var latestDate=now.atZone(zone).toLocalDate().plusDays(maximumAdvanceDays);
   if(localStart.toLocalDate().isAfter(latestDate) || localEnd.toLocalDate().isAfter(latestDate))
    throw new IllegalArgumentException("A reserva excede a antecedência máxima permitida.");
   if(!weekDays.contains(localStart.getDayOfWeek()) || !localStart.toLocalDate().equals(localEnd.toLocalDate())
       || !localStart.toLocalDate().equals(localOccupiedEnd.toLocalDate())
       || localStart.toLocalTime().isBefore(opensAt) || localEnd.toLocalTime().isAfter(closesAt)
       || localOccupiedEnd.toLocalTime().isAfter(closesAt))
    throw new IllegalArgumentException("Reserva e limpeza devem caber no horário local permitido.");
   var transition=zone.getRules().nextTransition(start);
   if(transition!=null && !transition.getInstant().isAfter(window.occupiedUntil()))
    throw new IllegalArgumentException("Intervalos com mudança de fuso exigem outra janela.");
   return window;
  } catch(DateTimeException | ArithmeticException ex) {
   throw new IllegalArgumentException("Período fora dos limites de data e horário.",ex);
  }
 }

 public void requireCancellation(Instant startsAt,Instant now) {
  if(startsAt==null || now==null || !now.isBefore(startsAt))
   throw new IllegalArgumentException("A reserva já começou ou o período é inválido.");
  try {
   if(startsAt.isBefore(now.plusSeconds(cancellationLeadMinutes*60L)))
    throw new IllegalArgumentException("O prazo para cancelamento desta reserva terminou.");
  } catch(DateTimeException | ArithmeticException ex) {
   throw new IllegalArgumentException("Período fora dos limites de data e horário.",ex);
  }
 }
}
