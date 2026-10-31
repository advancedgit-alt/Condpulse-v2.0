package br.com.condpulse.securitylog.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;

/** Contratos do Log de Segurança (CPX-009/CPX-010, V050). */
public final class SecurityLogModels {
 private SecurityLogModels() {}
 public static final Set<String> SEVERITIES=Set.of("INFO","ATTENTION","CRITICAL");
 public static final int DEFAULT_LIMIT=50,MAX_LIMIT=200,MAX_EXPORT=5000;
 static final Duration MAX_PERIOD=Duration.ofDays(366);
 /** Forma canônica do hash: sha256("cpsl1|" + prevHash + "|" + array JSON dos campos), docs/LOG_SEGURANCA.md. */
 public static final String CANONICALIZATION="cpsl1";

 /** Papéis de equipe de parceiro: para a gestão local só aparece o papel, nunca a conta (R5.6). */
 public static final Set<String> PARTNER_ROLES=Set.of("PARTNER_ADMIN","SECURITY_LEAD","SECURITY_SUPERVISOR","REMOTE_OPERATOR","TECHNICIAN");

 public record Filter(String eventType,String severity,UUID actorId,Instant from,Instant until,Long beforeSeq,int limit) {
  /** Valida e normaliza; erro do cliente é 400, nunca consulta ao banco. */
  public static Filter of(String eventType,String severity,UUID actorId,Instant from,Instant until,Long beforeSeq,Integer limit) {
   int size=limit==null?DEFAULT_LIMIT:limit;
   if(size<1 || size>MAX_LIMIT) throw invalid("SECURITY_LOG_LIMIT_INVALID","Use um limite entre 1 e "+MAX_LIMIT+".");
   if(eventType!=null && !eventType.matches("[A-Z][A-Z0-9_]{2,39}"))
    throw invalid("SECURITY_LOG_TYPE_INVALID","Tipo de evento inválido.");
   if(severity!=null && !SEVERITIES.contains(severity)) throw invalid("SECURITY_LOG_SEVERITY_INVALID","Use INFO, ATTENTION ou CRITICAL.");
   if(from!=null && until!=null && !from.isBefore(until)) throw invalid("SECURITY_LOG_PERIOD_INVALID","O início do período deve ser anterior ao fim.");
   if(from!=null && until!=null && Duration.between(from,until).compareTo(MAX_PERIOD)>0)
    throw invalid("SECURITY_LOG_PERIOD_INVALID","Consulte no máximo 366 dias por vez.");
   if(beforeSeq!=null && beforeSeq<2) throw invalid("SECURITY_LOG_CURSOR_INVALID","Cursor de paginação inválido.");
   return new Filter(eventType,severity,actorId,from,until,beforeSeq,size);
  }
  /** Filtros aplicados, para o details do SENSITIVE_VIEWED (sem conteúdo pessoal além do id do autor filtrado). */
  public Map<String,Object> describe() {
   var map=new java.util.TreeMap<String,Object>();
   if(eventType!=null) map.put("eventType",eventType);
   if(severity!=null) map.put("severity",severity);
   if(actorId!=null) map.put("actorId",actorId.toString());
   if(from!=null) map.put("from",from.toString());
   if(until!=null) map.put("until",until.toString());
   if(beforeSeq!=null) map.put("beforeSeq",beforeSeq);
   map.put("limit",limit);
   return map;
  }
 }

 public record Event(UUID eventId,long seq,Instant occurredAt,Instant recordedAt,Long clockSkewMs,String eventType,String severity,
   String source,UUID partnerId,UUID actorAccountId,String actorRole,String subjectType,UUID subjectId,UUID unitId,UUID pointId,
   UUID deviceId,String reason,String evidenceRef,UUID correlationId,String traceId,UUID correctsId,Map<String,Object> details,
   String prevHash,String hash) {
  public Event { details=details==null?Map.of():java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details)); }
  /** Gestão local não vê a conta da equipe do parceiro, só o papel (R5.6). */
  public Event maskedForTenant() {
   if(actorRole==null || !PARTNER_ROLES.contains(actorRole)) return this;
   return new Event(eventId,seq,occurredAt,recordedAt,clockSkewMs,eventType,severity,source,partnerId,null,actorRole,subjectType,subjectId,
    unitId,pointId,deviceId,reason,evidenceRef,correlationId,traceId,correctsId,details,prevHash,hash);
  }
 }
 public record Page(List<Event> events,Long nextBeforeSeq) {
  public Page { events=List.copyOf(events); }
 }
 public record Integrity(boolean intact,long checkedEvents,long lastSeq,String lastHash,Long brokenSeq,String problem,int anchorsChecked,
   Instant checkedAt) {}

 public record ExportRequest(String reason,Long fromSeq,Long toSeq) {}
 public record Manifest(UUID tenantId,long fromSeq,long toSeq,int count,String firstPrevHash,String firstHash,String lastHash,
   UUID exportedBy,Instant exportedAt,String reason,UUID exportEventId,String canonicalization) {}
 public record Export(Manifest manifest,List<Event> events) {
  public Export { events=List.copyOf(events); }
 }

 /** Confere o encadeamento de um intervalo contíguo: seq consecutiva e prevHash igual ao hash anterior. */
 public static boolean linked(List<Event> events,long fromSeq,long toSeq) {
  if(events.size()!=toSeq-fromSeq+1) return false;
  for(int i=0;i<events.size();i++) {
   var event=events.get(i);
   if(event.seq()!=fromSeq+i) return false;
   if(i>0 && !event.prevHash().equals(events.get(i-1).hash())) return false;
  }
  return true;
 }

 static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
