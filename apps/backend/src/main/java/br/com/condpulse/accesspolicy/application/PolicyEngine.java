package br.com.condpulse.accesspolicy.application;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Motor determinístico da política (AC-008): mesma entrada, mesma saída, sem relógio nem banco. Três funções puras:
 * conferência estrutural do documento (na criação da revisão), validação contra área e destinos (problemas com
 * gravidade) e avaliação de um cenário (simulação). Ingresso sem regra satisfeita é negado; a saída física nunca
 * depende deste motor.
 */
public final class PolicyEngine {
 private PolicyEngine() {}

 public static final List<String> CAPABILITIES=List.of("faceEnrollment","faceDeletion","perDoorRights","localValidity","onlineDecisionBeforeRelay",
  "offlineRestriction","doorContactEvents","passageEvents","scopedRemoteOpen","eventBackfill","secureTransport","safeRecovery");
 static final Set<String> CONTEXTS=Set.of("RESIDENT","DEPENDENT","OWNER_NONRESIDENT","VISITOR","SERVICE_PROVIDER","EMPLOYEE","EMERGENCY_RESPONDER");
 static final Set<String> VISITOR_POLICIES=Set.of("DENY","ALLOW_WITH_VALID_PERMIT","ONLY_VISITORS");
 static final Map<String,DayOfWeek> DAYS=Map.of("MON",DayOfWeek.MONDAY,"TUE",DayOfWeek.TUESDAY,"WED",DayOfWeek.WEDNESDAY,"THU",DayOfWeek.THURSDAY,
  "FRI",DayOfWeek.FRIDAY,"SAT",DayOfWeek.SATURDAY,"SUN",DayOfWeek.SUNDAY);
 static final Set<String> RESERVATION=Set.of("NOT_REQUIRED","VALID_RESERVATION","HOST_OR_LISTED_GUEST");
 static final Set<String> COMPANION=Set.of("NONE","VERIFIED_PHYSICAL_COMPANION","ASSISTED_BY_STAFF");
 static final Set<String> OFFLINE=Set.of("DENY_ENTRY","SAFE_LOCAL_SUBSET","ASSISTED_OPERATION");
 static final Set<String> MISSING=Set.of("DENY","ASSISTED_VERIFICATION");
 static final Set<String> SERVICE=Set.of("DENY","ASSISTED_OPERATION");
 static final Set<String> ANTIPASSBACK=Set.of("OFF","LOCAL","GLOBAL");
 static final Set<String> AGE_EVIDENCE=Set.of("VERIFIED_BELOW_MINIMUM","VERIFIED_MEETS_MINIMUM","UNKNOWN");
 private static final String START="([01][0-9]|2[0-3]):[0-5][0-9]",END="(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)";

 /** Erro estrutural do documento: código estável e ponteiro JSON. */
 public record DocumentProblem(String code,String pointer) {}

 // ---- 1. Estrutura (antes de gravar a revisão)
 public static DocumentProblem structure(PolicyModels.PolicyDocument d,UUID policyArea) {
  if(d==null) return new DocumentProblem("POLICY_DOCUMENT_REQUIRED","/document");
  if(!"1.0".equals(d.schemaVersion())) return new DocumentProblem("POLICY_SCHEMA_UNSUPPORTED","/schemaVersion");
  if(d.name()==null || d.name().isBlank() || d.name().strip().length()>120) return new DocumentProblem("POLICY_NAME_INVALID","/name");
  if(d.areaId()==null || !d.areaId().equals(policyArea)) return new DocumentProblem("POLICY_AREA_MISMATCH","/areaId");
  if(zone(d.timezone())==null) return new DocumentProblem("POLICY_TIMEZONE_INVALID","/timezone");
  if(!"ALL".equals(d.ruleCombination())) return new DocumentProblem("POLICY_COMBINATION_INVALID","/ruleCombination");
  var contexts=d.eligibleContexts();
  if(contexts==null || contexts.isEmpty() || contexts.size()>7 || contexts.stream().anyMatch(c->c==null || !CONTEXTS.contains(c))
    || new HashSet<>(contexts).size()!=contexts.size()) return new DocumentProblem("POLICY_CONTEXTS_INVALID","/eligibleContexts");
  if(d.visitorPolicy()==null || !VISITOR_POLICIES.contains(d.visitorPolicy())) return new DocumentProblem("POLICY_VISITOR_INVALID","/visitorPolicy");
  boolean visitor=contexts.contains("VISITOR");
  if(visitor==("DENY".equals(d.visitorPolicy())) || ("ONLY_VISITORS".equals(d.visitorPolicy()) && contexts.size()!=1))
   return new DocumentProblem("POLICY_VISITOR_INCONSISTENT","/visitorPolicy");
  if(!age(d.minimumAgeYears()) ) return new DocumentProblem("POLICY_AGE_INVALID","/minimumAgeYears");
  if(!age(d.maximumAgeYears())) return new DocumentProblem("POLICY_AGE_INVALID","/maximumAgeYears");
  if(d.minimumAgeYears()!=null && d.maximumAgeYears()!=null && d.maximumAgeYears()<d.minimumAgeYears())
   return new DocumentProblem("POLICY_AGE_RANGE_INVALID","/maximumAgeYears");
  if(d.requireVerifiedAgeEvidence()==null || ((d.minimumAgeYears()!=null || d.maximumAgeYears()!=null) && !d.requireVerifiedAgeEvidence()))
   return new DocumentProblem("POLICY_AGE_EVIDENCE_REQUIRED","/requireVerifiedAgeEvidence");
  var windows=d.weeklyWindows();
  if(windows==null || windows.isEmpty() || windows.size()>128) return new DocumentProblem("POLICY_WINDOWS_INVALID","/weeklyWindows");
  for(int i=0;i<windows.size();i++) if(!window(windows.get(i))) return new DocumentProblem("POLICY_WINDOW_INVALID","/weeklyWindows/"+i);
  var holidays=d.holidayCalendarIds();
  if(holidays==null || holidays.size()>128 || holidays.stream().anyMatch(Objects::isNull) || new HashSet<>(holidays).size()!=holidays.size())
   return new DocumentProblem("POLICY_HOLIDAYS_INVALID","/holidayCalendarIds");
  if(!"HOLIDAY_OVERRIDES_WEEKLY".equals(d.calendarPrecedence())) return new DocumentProblem("POLICY_PRECEDENCE_INVALID","/calendarPrecedence");
  var r=d.reservation();
  if(r==null || r.mode()==null || !RESERVATION.contains(r.mode()) || !between(r.entryGraceBeforeSeconds(),0,3600) || !between(r.entryGraceAfterSeconds(),0,3600))
   return new DocumentProblem("POLICY_RESERVATION_INVALID","/reservation");
  var c=d.companion();
  if(c==null || c.mode()==null || !COMPANION.contains(c.mode()) || !between(c.maximumPairingSeconds(),0,120))
   return new DocumentProblem("POLICY_COMPANION_INVALID","/companion");
  var o=d.offline();
  if(o==null || o.mode()==null || !OFFLINE.contains(o.mode()) || !between(o.maximumStalenessSeconds(),0,86400) || o.requireLocalExpiry()==null
    || ("SAFE_LOCAL_SUBSET".equals(o.mode()) && (!o.requireLocalExpiry() || o.maximumStalenessSeconds()==0)))
   return new DocumentProblem("POLICY_OFFLINE_INVALID","/offline");
  var f=d.failure();
  if(f==null || f.onMissingAttribute()==null || !MISSING.contains(f.onMissingAttribute()) || f.onServiceFailure()==null || !SERVICE.contains(f.onServiceFailure()))
   return new DocumentProblem("POLICY_FAILURE_INVALID","/failure");
  if(d.antiPassbackMode()==null || !ANTIPASSBACK.contains(d.antiPassbackMode())) return new DocumentProblem("POLICY_ANTIPASSBACK_INVALID","/antiPassbackMode");
  if(d.maximumOccupancy()!=null && !between(d.maximumOccupancy(),1,100000)) return new DocumentProblem("POLICY_OCCUPANCY_INVALID","/maximumOccupancy");
  if(!Boolean.TRUE.equals(d.emergencyEgressIndependent())) return new DocumentProblem("POLICY_EGRESS_REQUIRED","/emergencyEgressIndependent");
  if(!"DENY".equals(d.defaultEffect())) return new DocumentProblem("POLICY_DEFAULT_EFFECT_INVALID","/defaultEffect");
  return null;
 }
 private static boolean age(Integer v) { return v==null || (v>=0 && v<=120); }
 private static boolean between(Integer v,int min,int max) { return v!=null && v>=min && v<=max; }
 static ZoneId zone(String id) {
  if(id==null || id.isBlank() || id.length()>80 || !id.contains("/")) return null;
  try { return ZoneId.of(id); } catch(DateTimeException ex) { return null; }
 }
 private static boolean window(PolicyModels.WeeklyWindow w) {
  if(w==null || w.weekdays()==null || w.weekdays().isEmpty() || w.weekdays().size()>7 || w.weekdays().stream().anyMatch(x->x==null || !DAYS.containsKey(x))
    || new HashSet<>(w.weekdays()).size()!=w.weekdays().size() || w.crossesMidnight()==null) return false;
  if(w.startLocalTime()==null || !w.startLocalTime().matches(START) || w.endLocalTime()==null || !w.endLocalTime().matches(END)) return false;
  int start=minutes(w.startLocalTime()),end=minutes(w.endLocalTime());
  // Fim exclusivo: sem virada, fim depois do início (24:00 permitido); com virada, fim antes do início e nunca 24:00.
  return w.crossesMidnight()?(end<start):(end>start);
 }
 static int minutes(String hhmm) { return Integer.parseInt(hhmm.substring(0,2))*60+Integer.parseInt(hhmm.substring(3,5)); }

 // ---- 2. Capacidades exigidas e validação contra área e destinos
 public static List<String> requiredCapabilities(PolicyModels.PolicyDocument d) {
  var required=new TreeSet<String>(List.of("faceEnrollment","faceDeletion","perDoorRights","secureTransport"));
  if(!fullTime(d) || d.eligibleContexts().contains("VISITOR") || !"NOT_REQUIRED".equals(d.reservation().mode())) required.add("localValidity");
  switch(d.offline().mode()) {
   case "SAFE_LOCAL_SUBSET" -> { required.add("localValidity"); required.add("offlineRestriction"); }
   default -> required.add("offlineRestriction");
  }
  if(!"NONE".equals(d.companion().mode())) required.add("passageEvents");
  return CAPABILITIES.stream().filter(required::contains).toList();
 }
 /** Uma janela cobre todos os dias, 00:00–24:00, sem virada. */
 static boolean fullTime(PolicyModels.PolicyDocument d) {
  return d.weeklyWindows().stream().anyMatch(w->w.weekdays().size()==7 && !w.crossesMidnight() && "00:00".equals(w.startLocalTime())
   && "24:00".equals(w.endLocalTime()));
 }
 /** Ponto que dá ingresso na área: destino do sentido permitido; sentido desconhecido entra para ser bloqueado. */
 public static boolean entersArea(UUID area,UUID from,UUID to,String flow) {
  return (area.equals(to) && Set.of("FROM_TO","BOTH","UNKNOWN").contains(flow)) || (area.equals(from) && Set.of("TO_FROM","BOTH","UNKNOWN").contains(flow));
 }
 public static List<PolicyModels.ValidationIssue> validate(PolicyModels.PolicyDocument d,PolicyModels.AreaFacts area,
   List<PolicyModels.Destination> destinations,Instant now) {
  var issues=new ArrayList<PolicyModels.ValidationIssue>();
  if("RETIRED".equals(area.status())) issues.add(issue("AREA_RETIRED","/areaId","Área retirada não recebe regra.","BLOCKER",null,null));
  if("INTERDICTED".equals(area.status())) issues.add(issue("AREA_INTERDICTED","/areaId","Área interditada: ingresso segue negado até a liberação.","WARNING",null,null));
  if(!area.timeZone().equals(d.timezone()))
   issues.add(issue("TIMEZONE_MISMATCH","/timezone","O fuso da regra precisa ser o fuso da área.","BLOCKER",null,null));
  if(area.essentialRoute() && (!d.eligibleContexts().contains("RESIDENT") || d.minimumAgeYears()!=null || d.maximumAgeYears()!=null || !fullTime(d)
    || !"NOT_REQUIRED".equals(d.reservation().mode())))
   issues.add(issue("ESSENTIAL_ROUTE_RESTRICTED","/eligibleContexts","Rota residencial essencial não pode restringir moradores por horário, idade ou reserva.","BLOCKER",null,null));
  if(!"NOT_REQUIRED".equals(d.reservation().mode()) && area.amenityId()==null)
   issues.add(issue("RESERVATION_WITHOUT_AMENITY","/reservation/mode","Reserva exigida só em área ligada a espaço reservável.","BLOCKER",null,null));
  if(!d.holidayCalendarIds().isEmpty())
   issues.add(issue("HOLIDAY_CALENDAR_UNKNOWN","/holidayCalendarIds","Calendários de feriado ainda não existem neste condomínio.","BLOCKER",null,null));
  if(!"OFF".equals(d.antiPassbackMode()))
   issues.add(issue("ANTIPASSBACK_UNSUPPORTED","/antiPassbackMode","Antirretorno ainda não é suportado.","BLOCKER",null,null));
  if(d.maximumOccupancy()!=null)
   issues.add(issue("OCCUPANCY_UNSUPPORTED","/maximumOccupancy","Lotação máxima ainda não é suportada.","BLOCKER",null,null));
  if("VERIFIED_PHYSICAL_COMPANION".equals(d.companion().mode()))
   issues.add(issue("COMPANION_TOPOLOGY_UNAVAILABLE","/companion/mode","Acompanhamento físico exige topologia comprovada, ainda indisponível.","BLOCKER",null,null));
  if(d.eligibleContexts().contains("EMERGENCY_RESPONDER"))
   issues.add(issue("EMERGENCY_PROCEDURE_REQUIRED","/eligibleContexts","Socorrista exige procedimento operacional e autorização transitória.","WARNING",null,null));
  if(d.eligibleContexts().contains("OWNER_NONRESIDENT"))
   issues.add(issue("OWNER_DOES_NOT_INHERIT","/eligibleContexts","Proprietário não residente não herda direitos do ocupante.","INFO",null,null));
  var required=requiredCapabilities(d);
  if(destinations.isEmpty()) issues.add(issue("NO_DESTINATION","/areaId","Nenhuma passagem de ingresso para esta área.","BLOCKER",null,null));
  for(var p:destinations) {
   if("UNKNOWN".equals(p.flowDirection()))
    issues.add(issue("DIRECTION_UNKNOWN","/areaId","Sentido da passagem não definido.","BLOCKER",p.pointId(),null));
   if(!"COMMISSIONED".equals(p.pointStatus()) || p.bindingRevision()==null) {
    issues.add(issue("NOT_COMMISSIONED","/areaId","Passagem sem comissionamento aprovado.","BLOCKER",p.pointId(),null));
    continue;
   }
   if(!approved(p,now)) {
    issues.add(issue("HOMOLOGATION_NOT_APPROVED","/areaId","Combinação do equipamento sem aprovação vigente para piloto ou produção.","BLOCKER",p.pointId(),null));
    continue;
   }
   for(String capability:required) if(!p.approvedCapabilities().contains(capability))
    issues.add(issue("CAPABILITY_MISSING","/areaId","Capacidade não aprovada para esta passagem.","BLOCKER",p.pointId(),capability));
  }
  return List.copyOf(issues);
 }
 static boolean approved(PolicyModels.Destination p,Instant now) {
  return ("APROVADO_PILOTO".equals(p.homologationStatus()) || "HOMOLOGADO".equals(p.homologationStatus()))
   && (p.homologationReviewDueAt()==null || p.homologationReviewDueAt().isAfter(now)) && p.approvedCapabilities()!=null;
 }
 public static boolean blocked(List<PolicyModels.ValidationIssue> issues) { return issues.stream().anyMatch(i->"BLOCKER".equals(i.severity())); }
 private static PolicyModels.ValidationIssue issue(String code,String pointer,String message,String severity,UUID point,String capability) {
  return new PolicyModels.ValidationIssue(code,pointer,message,severity,point,capability);
 }

 // ---- 3. Avaliação de um cenário sintético
 public record Outcome(String result,List<String> reasonCodes,List<String> missingCapabilities) {}
 public static Outcome evaluate(PolicyModels.PolicyDocument d,PolicyModels.AreaFacts area,PolicyModels.Destination point,
   PolicyModels.SyntheticSubject s,Instant at) {
  var unavailable=new LinkedHashSet<String>();
  var deny=new LinkedHashSet<String>();
  var assist=new LinkedHashSet<String>();
  var missing=new ArrayList<String>();
  if(!"COMMISSIONED".equals(point.pointStatus()) || point.bindingRevision()==null) unavailable.add("NOT_COMMISSIONED");
  else {
   var required=requiredCapabilities(d);
   for(String capability:required)
    if(!approved(point,at) || !point.approvedCapabilities().contains(capability)) missing.add(capability);
   if(!missing.isEmpty()) deny.add("CAPABILITY_MISSING");
  }
  if(!"ACTIVE".equals(area.status())) deny.add("AREA_INTERDICTED");
  String context=s.context();
  if("VISITOR".equals(context) && "DENY".equals(d.visitorPolicy())) deny.add("VISITOR_NOT_ALLOWED");
  else if(!d.eligibleContexts().contains(context)) deny.add("NO_ELIGIBLE_CONTEXT");
  else if("VISITOR".equals(context) && !s.permitActive()) deny.add("PERMIT_EXPIRED");
  if(d.minimumAgeYears()!=null || d.maximumAgeYears()!=null) {
   if("UNKNOWN".equals(s.ageEvidence())) (("ASSISTED_VERIFICATION".equals(d.failure().onMissingAttribute()))?assist:deny).add("AGE_NOT_VERIFIED");
   else if("VERIFIED_BELOW_MINIMUM".equals(s.ageEvidence()) && d.minimumAgeYears()!=null) deny.add("AGE_BELOW_MINIMUM");
  }
  if(!"NOT_REQUIRED".equals(d.reservation().mode()) && !s.reservationActive()) deny.add("RESERVATION_REQUIRED");
  if("VERIFIED_PHYSICAL_COMPANION".equals(d.companion().mode()) && !s.physicalCompanionVerified()) deny.add("COMPANION_NOT_VERIFIED");
  if("ASSISTED_BY_STAFF".equals(d.companion().mode())) assist.add("ASSISTANCE_REQUIRED");
  if(!inSchedule(d,at)) deny.add("OUTSIDE_SCHEDULE");
  var reasons=new ArrayList<String>();
  reasons.addAll(unavailable); reasons.addAll(deny); reasons.addAll(assist);
  String result=!unavailable.isEmpty()?"UNAVAILABLE":!deny.isEmpty()?"DENY":!assist.isEmpty()?"REQUIRE_ASSISTANCE":"ALLOW";
  if(reasons.isEmpty()) reasons.add("ALLOWED");
  return new Outcome(result,List.copyOf(reasons),List.copyOf(missing));
 }
 /** Janela semanal no fuso da regra, fim exclusivo; janela que vira a noite vale do início até a meia-noite e na manhã seguinte. */
 static boolean inSchedule(PolicyModels.PolicyDocument d,Instant at) {
  ZonedDateTime local=at.atZone(zone(d.timezone()));
  int now=local.getHour()*60+local.getMinute();
  DayOfWeek today=local.getDayOfWeek(),yesterday=today.minus(1);
  for(var w:d.weeklyWindows()) {
   var days=w.weekdays().stream().map(DAYS::get).toList();
   int start=minutes(w.startLocalTime()),end=minutes(w.endLocalTime());
   if(!w.crossesMidnight()) { if(days.contains(today) && now>=start && now<end) return true; }
   else if((days.contains(today) && now>=start) || (days.contains(yesterday) && now<end)) return true;
  }
  return false;
 }
 private static final Map<String,String> EXPLAIN=Map.ofEntries(Map.entry("ALLOWED","Ingresso permitido pela regra simulada."),
  Map.entry("NOT_COMMISSIONED","Passagem sem comissionamento aprovado: nenhuma abertura automática."),
  Map.entry("CAPABILITY_MISSING","O equipamento não tem capacidade aprovada para esta regra."),
  Map.entry("AREA_INTERDICTED","Área interditada ou inativa."),Map.entry("VISITOR_NOT_ALLOWED","Visitantes não entram nesta área."),
  Map.entry("NO_ELIGIBLE_CONTEXT","O vínculo não está entre os permitidos."),Map.entry("PERMIT_EXPIRED","Visitante sem autorização vigente."),
  Map.entry("AGE_NOT_VERIFIED","Idade sem evidência verificada."),Map.entry("AGE_BELOW_MINIMUM","Idade abaixo do mínimo da área."),
  Map.entry("RESERVATION_REQUIRED","Exige reserva vigente."),Map.entry("COMPANION_NOT_VERIFIED","Exige acompanhamento físico comprovado."),
  Map.entry("ASSISTANCE_REQUIRED","Exige atendimento da equipe."),Map.entry("OUTSIDE_SCHEDULE","Fora do horário permitido."));
 public static String explain(List<String> reasons) {
  return String.join(" ",reasons.stream().map(r->EXPLAIN.getOrDefault(r,r)).toList());
 }
 static boolean validSubject(PolicyModels.SyntheticSubject s) {
  return s!=null && s.context()!=null && CONTEXTS.contains(s.context()) && s.ageEvidence()!=null && AGE_EVIDENCE.contains(s.ageEvidence())
   && s.permitActive()!=null && s.reservationActive()!=null && s.physicalCompanionVerified()!=null;
 }
}
