package br.com.condpulse.partners.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.securitylog.application.SecurityLogModels;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;

/**
 * Regras puras do Comando de Segurança (CPX-012 a CPX-014). Critério do semáforo "cpx012-v1" (docs/PARCEIROS.md), a
 * partir do §4.3.4 do PRD complementar, só com dados que existem nesta versão:
 * <ul>
 * <li>RED: alerta crítico das últimas 24 h sem incidente (CRITICAL_ALERTS_UNLINKED) ou incidente crítico aberto
 * (CRITICAL_INCIDENTS_OPEN).</li>
 * <li>AMBER: equipamento fora do ar (DEVICES_OFFLINE) ou sem informação recente (DEVICES_UNKNOWN), central sem operador ou
 * supervisor escalado agora (NO_OPERATOR_ON_DUTY), ocorrência de segurança aberta (SECURITY_OCCURRENCES_OPEN) ou incidente
 * aberto não crítico (INCIDENTS_OPEN).</li>
 * <li>GREEN: nenhum dos anteriores.</li>
 * </ul>
 * Portas abertas e permanências vencidas não têm fonte (DOOR_HELD_OPEN não é gravado; cp.stays não existe): ficam em
 * unavailableIndicators e nunca contam como zero. Aberturas remotas sem revisão são indicador sem cor (revisão por amostragem).
 */
public final class SecurityCommandRules {
 private SecurityCommandRules() {}
 public static final String CRITERIA="cpx012-v1";
 public static final List<String> UNAVAILABLE=List.of("DOORS_HELD_OPEN","STAYS_OVERDUE");
 public static final Set<String> SEVERITIES=Set.of("ATTENTION","CRITICAL");
 public static final Set<String> OUTCOMES=Set.of("RESOLVED","FALSE_ALARM","REFERRED");
 public static final Set<String> VERDICTS=Set.of("CONFERRED","IRREGULAR");
 public static final Set<String> INCIDENT_STATES=Set.of("OPEN","CLOSED");
 public static final Set<String> REVIEW_FILTERS=Set.of("PENDING","REVIEWED","ALL");
 public static final int MAX_LINKS=50,MAX_TENANTS=200,MAX_INCIDENTS=200,MAX_TIMELINE=500,DEFAULT_REMOTE_OPENS=50,MAX_REMOTE_OPENS=200;
 public static final String DOSSIER_CANONICALIZATION="cpdx1";

 public record Light(String light,List<String> reasons) {}

 public static Light light(SecurityCommandModels.Indicators value) {
  var reasons=new ArrayList<String>();
  if(value.criticalAlertsUnlinked()>0) reasons.add("CRITICAL_ALERTS_UNLINKED");
  if(value.incidentsCriticalOpen()>0) reasons.add("CRITICAL_INCIDENTS_OPEN");
  boolean red=!reasons.isEmpty();
  if(value.devicesOffline()>0) reasons.add("DEVICES_OFFLINE");
  if(value.devicesUnknown()>0) reasons.add("DEVICES_UNKNOWN");
  if(value.operatorsOnDuty()==0) reasons.add("NO_OPERATOR_ON_DUTY");
  if(value.securityOccurrencesOpen()>0) reasons.add("SECURITY_OCCURRENCES_OPEN");
  if(value.incidentsOpen()>value.incidentsCriticalOpen()) reasons.add("INCIDENTS_OPEN");
  return new Light(red?"RED":reasons.isEmpty()?"GREEN":"AMBER",reasons);
 }
 /** Mais grave primeiro (RED, AMBER, GREEN), depois nome e id. */
 public static int rank(String light) { return switch(light) { case "RED" -> 0; case "AMBER" -> 1; default -> 2; }; }

 public static String title(String value) {
  String title=DisplayText.normalize(value,120);
  if(title==null || title.codePointCount(0,title.length())<3) throw invalid("INCIDENT_TITLE_INVALID","Informe o título do incidente (3 a 120 caracteres).");
  return title;
 }
 public static String severity(String value) {
  if(value==null || !SEVERITIES.contains(value)) throw invalid("INCIDENT_SEVERITY_INVALID","Use ATTENTION ou CRITICAL.");
  return value;
 }
 /** Eventos distintos, na ordem pedida; no máximo 50 por pedido (o banco limita 50 por incidente). */
 public static List<UUID> events(List<UUID> values,boolean required) {
  if(values==null || values.isEmpty()) {
   if(required) throw invalid("INCIDENT_EVENTS_REQUIRED","Escolha ao menos um evento do log.");
   return List.of();
  }
  if(values.size()>MAX_LINKS || values.stream().anyMatch(java.util.Objects::isNull)) throw invalid("INCIDENT_EVENTS_INVALID","Anexe de 1 a 50 eventos do log.");
  return List.copyOf(new LinkedHashSet<>(values));
 }
 public static long version(Long value) {
  if(value==null || value<0) throw invalid("INCIDENT_VERSION_REQUIRED","Informe a versão lida do incidente.");
  return value;
 }
 public static String outcome(String value) {
  if(value==null || !OUTCOMES.contains(value)) throw invalid("INCIDENT_OUTCOME_INVALID","Use RESOLVED, FALSE_ALARM ou REFERRED.");
  return value;
 }
 public static String report(String value) {
  String report=DisplayText.normalizeMultiline(value,4000);
  if(report==null || report.codePointCount(0,report.length())<10)
   throw invalid("INCIDENT_REPORT_REQUIRED","Escreva o relatório de encerramento (10 a 4.000 caracteres).");
  return report;
 }
 /** Mesmo limite da exportação do log (R5.7): motivo de 10 a 500 caracteres. */
 public static String exportReason(String value) {
  String reason=DisplayText.normalize(value,500);
  if(reason==null || reason.codePointCount(0,reason.length())<10)
   throw invalid("INCIDENT_DOSSIER_REASON_REQUIRED","Informe o motivo da exportação (10 a 500 caracteres).");
  return reason;
 }
 public static String state(String value) {
  if(value==null) return "OPEN";
  if(!INCIDENT_STATES.contains(value)) throw invalid("INCIDENT_STATE_INVALID","Use OPEN ou CLOSED.");
  return value;
 }
 public record ReviewInput(String verdict,String note,UUID correctsReviewId) {}
 /** Irregular e correção exigem motivo (3 a 500); conferida aceita observação opcional. */
 public static ReviewInput review(SecurityCommandModels.ReviewRequest request) {
  if(request==null || request.verdict()==null || !VERDICTS.contains(request.verdict()))
   throw invalid("REMOTE_OPEN_REVIEW_INVALID","Marque como CONFERRED (conferida) ou IRREGULAR (irregular).");
  String note=request.note()==null || request.note().isBlank()?null:DisplayText.normalize(request.note(),500);
  if(note!=null && note.codePointCount(0,note.length())<3) throw invalid("REMOTE_OPEN_REVIEW_NOTE_INVALID","A observação tem de 3 a 500 caracteres.");
  if(note==null && ("IRREGULAR".equals(request.verdict()) || request.correctsReviewId()!=null))
   throw invalid("REMOTE_OPEN_REVIEW_NOTE_REQUIRED","Informe o motivo (3 a 500 caracteres) para irregular ou para corrigir uma revisão.");
  return new ReviewInput(request.verdict(),note,request.correctsReviewId());
 }
 public static String reviewFilter(String value) {
  if(value==null) return "PENDING";
  if(!REVIEW_FILTERS.contains(value)) throw invalid("REMOTE_OPEN_FILTER_INVALID","Use PENDING, REVIEWED ou ALL.");
  return value;
 }
 public static int limit(Integer value) {
  int size=value==null?DEFAULT_REMOTE_OPENS:value;
  if(size<1 || size>MAX_REMOTE_OPENS) throw invalid("REMOTE_OPEN_LIMIT_INVALID","Use um limite entre 1 e "+MAX_REMOTE_OPENS+".");
  return size;
 }

 /** Resumo do dossiê (documentado em docs/LOG_SEGURANCA.md): verificável fora do sistema com os hashes da cadeia. */
 public static String eventsDigest(UUID tenant,UUID incident,List<SecurityLogModels.Event> events) {
  var text=new StringBuilder(DOSSIER_CANONICALIZATION).append('|').append(tenant).append('|').append(incident);
  for(var event:events) text.append('|').append(event.seq()).append(':').append(event.hash());
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
  } catch(NoSuchAlgorithmException impossible) {
   throw new IllegalStateException(impossible);
  }
 }

 static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
