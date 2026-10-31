package br.com.condpulse.accessedge.application;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import br.com.condpulse.shared.application.BusinessProblem;

/**
 * Regras puras do relatório do gateway (V048), repetidas pelo banco (CHECK da V047 e gatilho de edge_reports):
 * <ul>
 * <li>ACK é só transporte (comando gravado na fila local durável) e nunca vem depois de um resultado;</li>
 * <li>CONFIRMED exige a leitura de volta do MESMO documento: observedHash igual ao hash do trabalho;</li>
 * <li>relatório de outro documento (hash diferente) é recusado sem efeito;</li>
 * <li>trabalho já confirmado ou superado não muda: o relatório é guardado como CLOSED e o gateway para.</li>
 * </ul>
 */
public final class EdgeReportRules {
 public static final Set<String> STATES=Set.of("ACK","APPLIED_UNVERIFIED","CONFIRMED","PARTIAL","FAILED","UNKNOWN");
 private static final String CODE="[A-Z0-9_]{1,64}",HASH="[0-9a-f]{64}",KEY="[A-Za-z0-9_.:-]{8,128}";
 private EdgeReportRules() {}

 public record Report(String state,String resultCode,String documentHash,String observedHash) {}
 /** Estado do trabalho no banco no momento do relatório. */
 public record Job(String state,String documentHash,String confirmationLevel) {}
 /** outcome APPLIED: efeito no trabalho (estado e nível de confirmação). acknowledge/finish: andamento da entrega. */
 public record Decision(String outcome,String outcomeCode,String confirmationLevel,String observedHash,boolean acknowledge,boolean finish) {}

 public static Report validate(EdgeModels.ReportRequest request) {
  if(request==null || request.state()==null || !STATES.contains(request.state()))
   throw invalid("EDGE_REPORT_STATE_INVALID","Estado do relatório inválido.");
  if(request.resultCode()!=null && !request.resultCode().matches(CODE)) throw invalid("EDGE_REPORT_CODE_INVALID","Código de resultado inválido.");
  if(request.documentHash()==null || !request.documentHash().matches(HASH))
   throw invalid("EDGE_REPORT_DOCUMENT_HASH_REQUIRED","Informe o hash do documento recebido.");
  if(request.observedHash()!=null && !request.observedHash().matches(HASH)) throw invalid("EDGE_REPORT_OBSERVED_HASH_INVALID","Hash lido inválido.");
  if("CONFIRMED".equals(request.state()) && request.observedHash()==null)
   throw invalid("EDGE_REPORT_READBACK_REQUIRED","Confirmação exige o hash lido de volta do equipamento.");
  if("ACK".equals(request.state()) && request.observedHash()!=null)
   throw invalid("EDGE_REPORT_OBSERVED_HASH_INVALID","ACK de transporte não traz leitura de volta.");
  return new Report(request.state(),request.resultCode(),request.documentHash(),request.observedHash());
 }
 public static String idempotencyKey(String key) {
  if(key==null || !key.matches(KEY)) throw invalid("EDGE_IDEMPOTENCY_KEY_REQUIRED","Informe Idempotency-Key (8 a 128 caracteres).");
  return key;
 }
 /** Hash do relatório normalizado (com o trabalho): a mesma chave com outro corpo é conflito. */
 public static String requestHash(java.util.UUID commandId,Report r) {
  String canonical=commandId+"|"+r.state()+"|"+Objects.toString(r.resultCode(),"")+"|"+r.documentHash()+"|"+Objects.toString(r.observedHash(),"");
  return EdgeCredentials.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
 }

 public static Decision decide(Report r,Job job) {
  Objects.requireNonNull(r); Objects.requireNonNull(job);
  if("CONFIRMED".equals(job.state())) return new Decision("CLOSED","JOB_ALREADY_CONFIRMED",null,null,false,true);
  if("SUPERSEDED".equals(job.state())) return new Decision("CLOSED","JOB_SUPERSEDED",null,null,false,true);
  if(!job.documentHash().equals(r.documentHash())) return rejected("DOCUMENT_HASH_MISMATCH");
  if("CONFIRMED".equals(r.state()) && !job.documentHash().equals(r.observedHash())) return rejected("READBACK_HASH_MISMATCH");
  if("ACK".equals(r.state()) && !Set.of("PENDING","ACK").contains(job.state())) return rejected("ACK_AFTER_RESULT");
  String level=switch(r.state()) {
   case "CONFIRMED" -> "STATE_READ";
   case "ACK","APPLIED_UNVERIFIED" -> "TRANSPORT_ACK";
   // Resultado sem prova: mantém o que o transporte já comprovou (nunca STATE_READ fora de CONFIRMED).
   default -> "TRANSPORT_ACK".equals(job.confirmationLevel())?"TRANSPORT_ACK":"NONE";
  };
  boolean ack="ACK".equals(r.state());
  return new Decision("APPLIED",null,level,"CONFIRMED".equals(r.state())?job.documentHash():r.observedHash(),ack,!ack);
 }
 private static Decision rejected(String code) { return new Decision("REJECTED",code,null,null,false,false); }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
