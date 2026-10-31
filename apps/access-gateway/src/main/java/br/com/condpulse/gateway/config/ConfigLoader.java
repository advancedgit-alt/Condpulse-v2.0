package br.com.condpulse.gateway.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import br.com.condpulse.gateway.config.GatewayConfig.*;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.Spi.Brand;
import br.com.condpulse.gateway.spi.Spi.CapabilityName;
import br.com.condpulse.gateway.spi.Spi.ConnectionMode;
import br.com.condpulse.gateway.spi.Spi.DecisionMode;
import br.com.condpulse.gateway.spi.Spi.Direction;

/**
 * Lê a configuração do gateway (JSON, sem segredos). Campo desconhecido é recusado para que erro de digitação não vire
 * padrão silencioso. Exemplo comentado em docs/integracoes/facial/GATEWAY.md.
 */
public final class ConfigLoader {
 private ConfigLoader() {}

 public static GatewayConfig load(Path file) {
  try { return parse(Files.readString(file,StandardCharsets.UTF_8)); }
  catch(IOException e) { throw new IllegalStateException("Configuração ilegível."); }
 }

 public static GatewayConfig parse(String json) {
  var root=Json.object(json);
  allow(root,"gatewayId","connectorId","condoLabel","zone","central","inbound","limits","openPolicy","schedules","profiles","devices");
  var central=Json.map(root.get("central"));
  Central c=null;
  if(central!=null) {
   allow(central,"baseUrl","keystorePath","keystorePasswordRef","truststorePath");
   c=new Central(URI.create(req(central,"baseUrl")),Json.string(central,"keystorePath"),Json.string(central,"keystorePasswordRef"),Json.string(central,"truststorePath"));
  }
  var inbound=Json.map(root.get("inbound"));
  Inbound in=null;
  if(inbound!=null) {
   allow(inbound,"bindAddress","port","tlsKeystorePath","tlsKeystorePasswordRef","maxBodyBytes");
   in=new Inbound(req(inbound,"bindAddress"),intOf(inbound,"port",0),Json.string(inbound,"tlsKeystorePath"),
    Json.string(inbound,"tlsKeystorePasswordRef"),intOf(inbound,"maxBodyBytes",256*1024));
  }
  Limits limits=Limits.defaults();
  var l=Json.map(root.get("limits"));
  if(l!=null) {
   allow(l,"queueSoftLimit","queueHardLimit","maxPolicyAgeSeconds","maxClockSkewSeconds","maxOpenTtlSeconds","deviceTimeoutMillis","pushPickupTimeoutMillis");
   var d=Limits.defaults();
   limits=new Limits(intOf(l,"queueSoftLimit",d.queueSoftLimit()),intOf(l,"queueHardLimit",d.queueHardLimit()),
    longOf(l,"maxPolicyAgeSeconds",d.maxPolicyAgeSeconds()),longOf(l,"maxClockSkewSeconds",d.maxClockSkewSeconds()),
    intOf(l,"maxOpenTtlSeconds",d.maxOpenTtlSeconds()),longOf(l,"deviceTimeoutMillis",d.deviceTimeoutMillis()),
    longOf(l,"pushPickupTimeoutMillis",d.pushPickupTimeoutMillis()));
  }
  CondoOpenPolicy open=CondoOpenPolicy.closed();
  var op=Json.map(root.get("openPolicy"));
  if(op!=null) {
   allow(op,"allowedOrigins","residentAppOpenEnabled");
   open=new CondoOpenPolicy(enums(op.get("allowedOrigins"),OpenOrigin.class),Boolean.TRUE.equals(Json.bool(op,"residentAppOpenEnabled")));
  }
  var schedules=new LinkedHashMap<String,List<WeeklyWindow>>();
  var s=Json.map(root.get("schedules"));
  if(s!=null) for(var e:s.entrySet()) {
   var windows=new ArrayList<WeeklyWindow>();
   for(Object w:Json.list(e.getValue())) {
    var wm=Json.map(w);
    allow(wm,"days","start","end");
    windows.add(new WeeklyWindow(enums(wm.get("days"),DayOfWeek.class),LocalTime.parse(req(wm,"start")),LocalTime.parse(req(wm,"end"))));
   }
   if(windows.isEmpty()) throw new IllegalArgumentException("Faixa de horário sem janela: "+e.getKey());
   schedules.put(e.getKey(),windows);
  }
  var profiles=new ArrayList<Profile>();
  var p=Json.list(root.get("profiles"));
  if(p!=null) for(Object o:p) {
   var pm=Json.map(o);
   allow(pm,"brand","sku","firmware","apiVersion","decisionMode","connectionMode","observed","approved");
   profiles.add(new Profile(Brand.valueOf(req(pm,"brand")),req(pm,"sku"),req(pm,"firmware"),req(pm,"apiVersion"),
    DecisionMode.valueOf(req(pm,"decisionMode")),ConnectionMode.valueOf(req(pm,"connectionMode")),caps(pm.get("observed")),caps(pm.get("approved"))));
  }
  var devices=new ArrayList<Device>();
  var d=Json.list(root.get("devices"));
  if(d!=null) for(Object o:d) devices.add(device(Json.map(o)));
  return new GatewayConfig(req(root,"gatewayId"),req(root,"connectorId"),Json.string(root,"condoLabel"),ZoneId.of(req(root,"zone")),
   c,in,limits,open,schedules,profiles,devices);
 }

 private static Device device(Map<String,Object> m) {
  allow(m,"deviceId","deviceProvisioningId","label","brand","sku","firmware","apiVersion","connectionMode","decisionMode","baseUrl",
   "credentialRef","inboundTokenRef","nativeDeviceId","eventEpoch","enabled","phase","openEnabled","openOrigins","installationRevision",
   "channels","nativeSchedules","params");
  var channels=new ArrayList<Channel>();
  var list=Json.list(m.get("channels"));
  if(list!=null) for(Object o:list) {
   var c=Json.map(o);
   allow(c,"channelId","pointId","bindingRevision","nativeChannel","nativeOpenAction","direction");
   channels.add(new Channel(req(c,"channelId"),req(c,"pointId"),longOf(c,"bindingRevision",0),req(c,"nativeChannel"),
    Json.string(c,"nativeOpenAction"),Direction.valueOf(req(c,"direction"))));
  }
  String base=Json.string(m,"baseUrl");
  return new Device(req(m,"deviceId"),req(m,"deviceProvisioningId"),req(m,"label"),Brand.valueOf(req(m,"brand")),req(m,"sku"),
   req(m,"firmware"),req(m,"apiVersion"),ConnectionMode.valueOf(req(m,"connectionMode")),DecisionMode.valueOf(req(m,"decisionMode")),
   base==null?null:URI.create(base),Json.string(m,"credentialRef"),Json.string(m,"inboundTokenRef"),Json.string(m,"nativeDeviceId"),
   req(m,"eventEpoch"),Boolean.TRUE.equals(Json.bool(m,"enabled")),Phase.valueOf(req(m,"phase")),Boolean.TRUE.equals(Json.bool(m,"openEnabled")),
   enums(m.get("openOrigins"),OpenOrigin.class),longOf(m,"installationRevision",0),channels,strings(m.get("nativeSchedules")),strings(m.get("params")));
 }

 private static Map<CapabilityName,Boolean> caps(Object value) {
  var out=new EnumMap<CapabilityName,Boolean>(CapabilityName.class);
  var m=Json.map(value);
  if(m!=null) for(var e:m.entrySet()) {
   if(!(e.getValue() instanceof Boolean b)) throw new IllegalArgumentException("Capacidade deve ser booleana: "+e.getKey());
   out.put(CapabilityName.valueOf(e.getKey()),b);
  }
  return out;
 }
 private static Map<String,String> strings(Object value) {
  var out=new LinkedHashMap<String,String>();
  var m=Json.map(value);
  if(m!=null) for(var e:m.entrySet()) {
   if(!(e.getValue() instanceof String v)) throw new IllegalArgumentException("Valor textual esperado: "+e.getKey());
   out.put(e.getKey(),v);
  }
  return out;
 }
 private static <E extends Enum<E>> Set<E> enums(Object value,Class<E> type) {
  var out=EnumSet.noneOf(type);
  var list=Json.list(value);
  if(list!=null) for(Object o:list) out.add(Enum.valueOf(type,String.valueOf(o)));
  return out;
 }
 private static void allow(Map<String,Object> map,String... keys) {
  var allowed=Set.of(keys);
  for(String k:map.keySet()) if(!allowed.contains(k)) throw new IllegalArgumentException("Campo desconhecido na configuração: "+k);
 }
 private static String req(Map<String,Object> map,String key) {
  String v=Json.string(map,key);
  if(v==null || v.isBlank()) throw new IllegalArgumentException("Campo obrigatório: "+key);
  return v;
 }
 private static int intOf(Map<String,Object> map,String key,int fallback) {
  Long v=Json.longValue(map,key);
  if(v==null) return fallback;
  return Math.toIntExact(v);
 }
 private static long longOf(Map<String,Object> map,String key,long fallback) {
  Long v=Json.longValue(map,key);
  return v==null?fallback:v;
 }
}
