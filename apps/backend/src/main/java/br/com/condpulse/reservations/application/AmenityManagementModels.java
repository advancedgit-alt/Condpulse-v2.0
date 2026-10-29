package br.com.condpulse.reservations.application;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import br.com.condpulse.reservations.domain.ReservationPolicy;
import br.com.condpulse.shared.application.BusinessProblem;

/** Contratos e validação explícita das regras de área. Nenhum valor permissivo é presumido. */
public final class AmenityManagementModels {
 private AmenityManagementModels() {}
 public record PolicyInput(Integer schemaVersion,List<Integer> weekDays,String opensAt,String closesAt,
     Integer minimumLeadMinutes,Integer maximumAdvanceDays,Integer maximumDurationMinutes,
     Integer maximumActivePerUnit,Integer cancellationLeadMinutes) {}
 public record AmenityInput(String name,Integer capacity,Boolean approvalRequired,Long feeCents,
     Integer bufferMinutes,Boolean active,PolicyInput policy,Long expectedVersion,String reason) {}
 public record ManagedAmenity(UUID amenityId,String name,int capacity,boolean approvalRequired,long feeCents,
     int bufferMinutes,boolean active,int policyVersion,long version,String policyState,ReservationModels.Policy policy) {}

 /** Valores normalizados e validados, prontos para persistência. */
 public record ValidAmenity(String name,int capacity,boolean approvalRequired,long feeCents,int bufferMinutes,
     boolean active,ReservationPolicy policy,String reason) {
  public String policyJson() {
   String days=policy.weekDays().stream().map(DayOfWeek::getValue).sorted().map(String::valueOf)
    .collect(Collectors.joining(","));
   return "{\"schemaVersion\":1,\"weekDays\":["+days+"],\"opensAt\":\""+hhmm(policy.opensAt())
    +"\",\"closesAt\":\""+hhmm(policy.closesAt())+"\",\"minimumLeadMinutes\":"+policy.minimumLeadMinutes()
    +",\"maximumAdvanceDays\":"+policy.maximumAdvanceDays()+",\"maximumDurationMinutes\":"+policy.maximumDurationMinutes()
    +",\"maximumActivePerUnit\":"+policy.maximumActivePerUnit()+",\"cancellationLeadMinutes\":"+policy.cancellationLeadMinutes()+"}";
  }
 }

 public static ValidAmenity validate(AmenityInput input,boolean creating) {
  if(input==null) throw invalid("AMENITY_INPUT_INVALID","Informe os dados da área.");
  String name=normalized(input.name(),120,"AMENITY_NAME_INVALID","Informe o nome da área com até 120 caracteres.");
  if(input.capacity()==null || input.capacity()<1 || input.capacity()>10000)
   throw invalid("AMENITY_CAPACITY_INVALID","A capacidade deve estar entre 1 e 10000 pessoas.");
  if(input.approvalRequired()==null)
   throw invalid("AMENITY_APPROVAL_REQUIRED","Informe se a área exige aprovação da gestão.");
  if(input.feeCents()==null || input.feeCents()<0 || input.feeCents()>10_000_000L)
   throw invalid("AMENITY_FEE_INVALID","A taxa informativa deve estar entre R$ 0,00 e R$ 100.000,00.");
  if(input.bufferMinutes()==null || input.bufferMinutes()<0 || input.bufferMinutes()>1440)
   throw invalid("AMENITY_BUFFER_INVALID","O intervalo de limpeza deve ter entre 0 e 1440 minutos.");
  if(!creating && input.active()==null) throw invalid("AMENITY_ACTIVE_REQUIRED","Informe se a área está ativa.");
  boolean active=input.active()==null || input.active();
  String reason=null;
  if(input.reason()!=null && !input.reason().isBlank())
   reason=normalized(input.reason(),500,"AUDIT_REASON_INVALID","O motivo deve ter até 500 caracteres.");
  return new ValidAmenity(name,input.capacity(),input.approvalRequired(),input.feeCents(),input.bufferMinutes(),
   active,policy(input.policy()),reason);
 }
 static ReservationPolicy policy(PolicyInput input) {
  if(input==null) throw invalid("AMENITY_POLICY_REQUIRED","Configure as regras de reserva da área.");
  if(input.schemaVersion()==null || input.schemaVersion()!=1)
   throw invalid("AMENITY_POLICY_INVALID","Versão de regra não suportada.");
  if(input.weekDays()==null || input.weekDays().isEmpty() || input.weekDays().size()>7)
   throw invalid("AMENITY_POLICY_INVALID","Informe os dias da semana permitidos.");
  var days=EnumSet.noneOf(DayOfWeek.class);
  for(Integer day:input.weekDays())
   if(day==null || day<1 || day>7 || !days.add(DayOfWeek.of(day)))
    throw invalid("AMENITY_POLICY_INVALID","Dias da semana devem ser números distintos de 1 a 7.");
  LocalTime opens=time(input.opensAt()),closes=time(input.closesAt());
  if(input.minimumLeadMinutes()==null || input.maximumAdvanceDays()==null || input.maximumDurationMinutes()==null
     || input.maximumActivePerUnit()==null || input.cancellationLeadMinutes()==null)
   throw invalid("AMENITY_POLICY_INVALID","Preencha antecedência, janela, duração, limite por unidade e prazo de cancelamento.");
  try {
   return new ReservationPolicy(1,days,opens,closes,input.minimumLeadMinutes(),input.maximumAdvanceDays(),
    input.maximumDurationMinutes(),input.maximumActivePerUnit(),input.cancellationLeadMinutes());
  } catch(IllegalArgumentException invalid) {
   throw invalid("AMENITY_POLICY_INVALID","As regras informadas estão fora dos limites permitidos.");
  }
 }
 private static LocalTime time(String value) {
  if(value==null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
   throw invalid("AMENITY_POLICY_INVALID","Horários devem usar o formato HH:mm.");
  try { return LocalTime.parse(value); }
  catch(DateTimeException ex) { throw invalid("AMENITY_POLICY_INVALID","Horários devem usar o formato HH:mm."); }
 }
 private static String hhmm(LocalTime time) {
  return String.format(Locale.ROOT,"%02d:%02d",time.getHour(),time.getMinute());
 }
 static String normalized(String value,int maximum,String code,String message) {
  if(value==null) throw invalid(code,message);
  String text=value.strip().replaceAll("\\s+"," ");
  if(text.isEmpty() || text.length()>maximum || text.chars().anyMatch(Character::isISOControl)) throw invalid(code,message);
  return text;
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
