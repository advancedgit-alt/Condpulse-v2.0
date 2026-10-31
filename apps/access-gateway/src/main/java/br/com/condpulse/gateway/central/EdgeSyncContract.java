package br.com.condpulse.gateway.central;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.queue.LocalCommandQueue.CommandType;
import br.com.condpulse.gateway.queue.LocalCommandQueue.SyncCommand;
import br.com.condpulse.gateway.spi.Spi.OperationState;

/**
 * Contrato do gateway com a API central (V048, contracts/openapi.json): entrega dos trabalhos da publicação por destino
 * (V047) e relatório por trabalho. Regras deste lado:
 * <ul>
 * <li>o documento entregue só vira comando se o SHA-256 do texto recebido for o documentHash (integridade);</li>
 * <li>o documento só traz regras de tempo integral (V047): cada canal recebe a faixa {@link #FULL_TIME_SCHEDULE}, que o
 * MASTER comissiona na configuração do equipamento ({@code nativeSchedules}); canal ou faixa não comissionados são
 * recusados pelo núcleo (RIGHT_NOT_REPRESENTABLE), nunca adivinhados;</li>
 * <li>vigências diferentes no mesmo documento viram a interseção (nunca amplia);</li>
 * <li>prioridade e versão desejada da plataforma seguem no comando: prioridade 0 é restrição na fila local;</li>
 * <li>CONFIRMED só sai depois da leitura de volta bater com o comando derivado do documento: o relatório leva o hash
 * desse documento como observedHash. ACK é só o comando gravado na fila local durável.</li>
 * </ul>
 */
public final class EdgeSyncContract {
 public static final String CREDENTIAL_HEADER="X-CondPulse-Edge-Credential";
 public static final String FULL_TIME_SCHEDULE="FULL_TIME";
 /** Máximo de trabalhos por entrega aceito pela API central. */
 public static final int MAX_BATCH=50;
 private static final String HASH="[0-9a-f]{64}";
 private EdgeSyncContract() {}

 /** Documento que não pode ser traduzido: motivo interno, nunca sai do contrato (vira comando bloqueante). */
 static final class Refused extends Exception {
  private final String reasonCode;
  Refused(String reasonCode) { super(reasonCode,null,false,false); this.reasonCode=reasonCode; }
  String reasonCode() { return reasonCode; }
 }
 /** Payload de comando bloqueante: motivo pelo qual o documento recebido não foi aplicado. */
 public static final String BLOCKED_REASON="blockedReason";

 /**
  * Converte um item de {@code EdgeLeasePage.commands}. A identidade do trabalho (comando, equipamento, sujeito, tipo,
  * versão e emissão) é lida ANTES do documento: item sem ela lança IllegalArgumentException (nada a proteger). Documento
  * que não confere com o hash ou não é traduzível nunca é descartado em silêncio (Codex P1 no #153): vira comando
  * restritivo da mesma versão — REVOKE_ALL quando o trabalho era REVOKE_ALL, senão REVOKE_RIGHTS do sujeito no
  * equipamento —, com prioridade 0 e {@link #BLOCKED_REASON}. Na fila ele supera e bloqueia toda versão menor do escopo
  * (inclusive na releitura do diário), executa a retirada segura e é relatado FAILED com o motivo, nunca CONFIRMED.
  */
 public static SyncCommand toCommand(Map<String,Object> raw) {
  String commandId=Json.string(raw,"commandId"),deviceId=Json.string(raw,"deviceId"),hash=Json.string(raw,"documentHash");
  String subjectRef=Json.string(raw,"subjectRef"),typeName=Json.string(raw,"type"),key=Json.string(raw,"idempotencyKey");
  Long version=Json.longValue(raw,"desiredVersion");
  Instant issued;
  CommandType type;
  try { issued=Instant.parse(Objects.requireNonNull(Json.string(raw,"issuedAt"))); type=CommandType.valueOf(Objects.requireNonNull(typeName)); }
  catch(RuntimeException e) { throw new IllegalArgumentException("Comando sem identificação."); }
  if(commandId==null || deviceId==null || subjectRef==null || hash==null || !hash.matches(HASH) || version==null || version<1
    || (type!=CommandType.APPLY_RIGHTS && type!=CommandType.REVOKE_RIGHTS && type!=CommandType.REVOKE_ALL))
   throw new IllegalArgumentException("Comando sem identificação.");
  String idempotencyKey=key==null?commandId:key;
  try {
   String text=Json.string(raw,"document");
   if(text==null || !sha256Hex(text).equals(hash)) throw new Refused("DOCUMENT_INTEGRITY_FAILED");
   Map<String,Object> document;
   try { document=Json.object(text); } catch(RuntimeException e) { throw new Refused("DOCUMENT_NOT_REPRESENTABLE"); }
   if(!"1.0".equals(Json.string(document,"schemaVersion")) || !type.name().equals(Json.string(document,"action"))
     || !subjectRef.equals(Json.string(document,"subjectRef")))
    throw new Refused("DOCUMENT_NOT_REPRESENTABLE");
   var payload=new LinkedHashMap<String,Object>();
   payload.put("documentHash",hash);
   // Prioridade da plataforma (0 = retirada ou redução): a fila local a usa para ordenar e superar (Codex P1 no #153).
   Long priority=Json.longValue(raw,"priority");
   if(priority==null || (priority!=0 && priority!=1)) throw new Refused("DOCUMENT_NOT_REPRESENTABLE");
   payload.put("priority",priority);
   if(type==CommandType.APPLY_RIGHTS) {
    try { rights(document,payload); } catch(Refused r) { throw r; } catch(RuntimeException e) { throw new Refused("DOCUMENT_NOT_REPRESENTABLE"); }
   }
   return new SyncCommand(commandId,idempotencyKey,deviceId,subjectRef,type,version,payload,issued);
  } catch(Refused refused) {
   var payload=new LinkedHashMap<String,Object>();
   payload.put("documentHash",hash);
   payload.put("priority",0L);
   payload.put(BLOCKED_REASON,refused.reasonCode());
   return new SyncCommand(commandId,idempotencyKey,deviceId,subjectRef,type==CommandType.REVOKE_ALL?CommandType.REVOKE_ALL:CommandType.REVOKE_RIGHTS,
    version,payload,issued);
  }
 }

 private static void rights(Map<String,Object> document,Map<String,Object> payload) throws Refused {
  var entries=Json.list(document.get("entries"));
  if(entries==null || entries.isEmpty()) throw new Refused("DOCUMENT_NOT_REPRESENTABLE");
  var channels=new TreeSet<String>();
  Instant from=null,until=null;
  for(Object o:entries) {
   var e=Json.map(o);
   String channel=Json.string(e,"channelId");
   Instant start=Instant.parse(Json.string(e,"validFrom")),end=Instant.parse(Json.string(e,"validUntil"));
   if(channel==null || channel.isBlank()) throw new Refused("DOCUMENT_NOT_REPRESENTABLE");
   channels.add(channel);
   from=from==null || start.isAfter(from)?start:from;
   until=until==null || end.isBefore(until)?end:until;
  }
  // Interseção vazia: nada a conceder sem ampliar alguma vigência.
  if(!from.isBefore(until)) throw new Refused("DOCUMENT_VALIDITY_EMPTY");
  var rights=new ArrayList<Object>();
  for(String channel:channels) { var r=new LinkedHashMap<String,Object>(); r.put("channelId",channel); r.put("scheduleRef",FULL_TIME_SCHEDULE); rights.add(r); }
  payload.put("rights",rights);
  payload.put("validFrom",from.toString());
  payload.put("validUntil",until.toString());
 }

 /** Estado aceito pela API: NAO_SUPORTADO_SEM_DOCUMENTACAO é FAILED com o motivo. */
 public static String state(OperationState state) {
  return state==OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO?"FAILED":state.name();
 }
 /** Motivo no formato da API ([A-Z0-9_]{1,64}); o que vier fora vira sublinhado. */
 public static String reasonCode(OperationState state,String reason) {
  String raw=reason!=null?reason:state==OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO?"NAO_SUPORTADO_SEM_DOCUMENTACAO":null;
  if(raw==null) return null;
  String code=raw.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]","_");
  return code.isEmpty()?null:code.length()>64?code.substring(0,64):code;
 }
 /** Corpo do relatório (EdgeReportRequest). CONFIRMED leva o hash do documento cuja leitura de volta bateu. */
 public static Map<String,Object> reportBody(CentralApiClient.SyncResult r) {
  if(r.documentHash()==null || !r.documentHash().matches(HASH)) throw new IllegalArgumentException("Relatório sem hash do documento.");
  var body=new LinkedHashMap<String,Object>();
  body.put("state",state(r.state()));
  body.put("resultCode",reasonCode(r.state(),r.reasonCode()));
  body.put("documentHash",r.documentHash());
  body.put("observedHash",r.state()==OperationState.CONFIRMED?r.documentHash():null);
  return body;
 }
 /** Mesma chave para o mesmo relatório em qualquer repetição (a API devolve o mesmo resultado). */
 public static String idempotencyKey(CentralApiClient.SyncResult r) {
  var body=reportBody(r);
  return "r-"+sha256Hex(r.commandId()+"|"+body.get("state")+"|"+body.get("resultCode")+"|"+r.documentHash()).substring(0,48);
 }

 public static String sha256Hex(String text) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível."); }
 }
}
