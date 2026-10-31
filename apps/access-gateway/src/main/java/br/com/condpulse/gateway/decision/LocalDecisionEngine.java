package br.com.condpulse.gateway.decision;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.GatewayConfig.WeeklyWindow;
import br.com.condpulse.gateway.journal.Journal;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.InboundEndpoint.LocalDecision;

/**
 * Decisão local antes do relé (modo EDGE_ONLINE; manual 11 e 13.3). Usa só a cópia mínima de direitos recebida da
 * plataforma, com prazo: política mais velha que {@code maxPolicyAgeSeconds}, relógio não confiável, canal ambíguo,
 * pessoa desconhecida ou revogada resultam em negativa. Revogação é aplicada aqui no momento em que chega,
 * antes de qualquer confirmação do equipamento. Nunca consulta a nuvem no caminho do relé.
 */
public final class LocalDecisionEngine implements AutoCloseable {
 private record Grant(List<String[]> rights,Instant validFrom,Instant validUntil,long version,boolean revoked) {}

 private final Journal journal;
 private final Clock clock;
 private final GatewayConfig config;
 private final Map<String,Grant> grants=new HashMap<>();
 private final Map<String,String> nativeToSubject=new HashMap<>();
 private volatile Instant lastSync;
 private volatile Duration clockSkew;

 public LocalDecisionEngine(Path dir,Clock clock,GatewayConfig config) {
  this.clock=clock; this.config=config;
  this.journal=new Journal(dir.resolve("decision-snapshot.jsonl"));
  journal.replay(this::apply);
 }

 /** Sincronização bem-sucedida com a plataforma (política fresca). */
 public void markSynced(Instant at) { lastSync=at; }
 /** Desvio observado em relação ao relógio da plataforma; sem observação, relógio não confiável. */
 public void observeClockSkew(Duration skew) { clockSkew=skew; }

 public synchronized void mapNativeUser(String deviceId,String nativeUserRef,String subjectRef) {
  if(subjectRef.equals(nativeToSubject.get(deviceId+"|"+nativeUserRef))) return;
  var m=record("map"); m.put("deviceId",deviceId); m.put("native",nativeUserRef); m.put("subject",subjectRef);
  journal.append(m); apply(m);
 }

 /** Direitos desejados (substitui os anteriores). Versão menor ou igual à última conhecida é ignorada. */
 public synchronized void grant(String deviceId,String subjectRef,long version,List<String[]> rights,Instant validFrom,Instant validUntil) {
  var current=grants.get(deviceId+"|"+subjectRef);
  if(current!=null && current.version()>=version) return;
  var m=record("grant"); m.put("deviceId",deviceId); m.put("subject",subjectRef); m.put("version",version);
  var list=new ArrayList<Object>();
  for(String[] r:rights) list.add(List.of(r[0],r[1]));
  m.put("rights",list);
  m.put("validFrom",validFrom==null?null:validFrom.toString()); m.put("validUntil",validUntil==null?null:validUntil.toString());
  journal.append(m); apply(m);
 }

 /** Revogação: vale imediatamente e bloqueia versões menores ou iguais. */
 public synchronized void revoke(String deviceId,String subjectRef,long version) {
  var current=grants.get(deviceId+"|"+subjectRef);
  if(current!=null && current.revoked() && current.version()>=version) return;
  var m=record("revoke"); m.put("deviceId",deviceId); m.put("subject",subjectRef); m.put("version",Math.max(version,current==null?0:current.version()));
  journal.append(m); apply(m);
 }

 public synchronized LocalDecision decide(String deviceId,String nativeUserRef,String nativeChannel) {
  Instant now=clock.instant();
  var device=config.device(deviceId).orElse(null);
  if(device==null || !device.enabled()) return deny("NOT_COMMISSIONED",null,null);
  var skew=clockSkew;
  if(skew==null || skew.abs().getSeconds()>config.limits().maxClockSkewSeconds()) return deny("CLOCK_UNTRUSTED",null,null);
  var synced=lastSync;
  if(synced==null || Duration.between(synced,now).getSeconds()>config.limits().maxPolicyAgeSeconds()) return deny("STALE_POLICY",null,null);
  GatewayConfig.Channel channel;
  if(nativeChannel!=null) channel=device.channelByNative(nativeChannel).orElse(null);
  else channel=device.channels().size()==1?device.channels().getFirst():null; // terminal de uma porta só
  if(channel==null) return deny("CONTEXT_AMBIGUOUS",null,null);
  String subject=nativeUserRef==null?null:nativeToSubject.get(deviceId+"|"+nativeUserRef);
  if(subject==null) return deny("NO_ELIGIBLE_CONTEXT",null,channel.channelId());
  var grant=grants.get(deviceId+"|"+subject);
  if(grant==null) return deny("NO_ELIGIBLE_CONTEXT",subject,channel.channelId());
  if(grant.revoked()) return deny("REVOKED",subject,channel.channelId());
  if((grant.validFrom()!=null && now.isBefore(grant.validFrom())) || (grant.validUntil()!=null && !now.isBefore(grant.validUntil())))
   return deny("PERMIT_EXPIRED",subject,channel.channelId());
  boolean channelRight=false, inSchedule=false;
  ZonedDateTime local=now.atZone(config.zone());
  for(String[] r:grant.rights()) {
   if(!r[0].equals(channel.channelId())) continue;
   channelRight=true;
   for(WeeklyWindow w:config.schedules().getOrDefault(r[1],List.of()))
    if(w.days().contains(local.getDayOfWeek()) && !local.toLocalTime().isBefore(w.start()) && local.toLocalTime().isBefore(w.end())) inSchedule=true;
  }
  if(!channelRight) return deny("NO_ELIGIBLE_CONTEXT",subject,channel.channelId());
  if(!inSchedule) return deny("OUTSIDE_SCHEDULE",subject,channel.channelId());
  return new LocalDecision(true,List.of("ALLOWED"),subject,channel.channelId());
 }

 private static LocalDecision deny(String reason,String subject,String channel) { return new LocalDecision(false,List.of(reason),subject,channel); }

 private Map<String,Object> record(String type) {
  var m=new LinkedHashMap<String,Object>(); m.put("t",type); m.put("at",clock.instant().toString()); return m;
 }
 private void apply(Map<String,Object> m) {
  String key=Json.string(m,"deviceId")+"|"+Json.string(m,"subject");
  switch(Json.string(m,"t")) {
   case "map" -> nativeToSubject.put(Json.string(m,"deviceId")+"|"+Json.string(m,"native"),Json.string(m,"subject"));
   case "grant" -> {
    var rights=new ArrayList<String[]>();
    for(Object o:Json.list(m.get("rights"))) { var l=Json.list(o); rights.add(new String[]{String.valueOf(l.get(0)),String.valueOf(l.get(1))}); }
    String from=Json.string(m,"validFrom"), until=Json.string(m,"validUntil");
    grants.put(key,new Grant(rights,from==null?null:Instant.parse(from),until==null?null:Instant.parse(until),Json.longValue(m,"version"),false));
   }
   case "revoke" -> grants.put(key,new Grant(List.of(),null,null,Json.longValue(m,"version"),true));
   default -> throw new IllegalStateException("Registro desconhecido no diário de decisão.");
  }
 }

 @Override public void close() { journal.close(); }
}
