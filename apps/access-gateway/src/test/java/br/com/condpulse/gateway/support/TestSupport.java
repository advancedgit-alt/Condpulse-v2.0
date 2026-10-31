package br.com.condpulse.gateway.support;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import br.com.condpulse.gateway.central.CentralApiClient;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.GatewayConfig.*;
import br.com.condpulse.gateway.config.SecretProvider;
import br.com.condpulse.gateway.open.OpenDispatcher;
import br.com.condpulse.gateway.queue.LocalCommandQueue.SyncCommand;
import br.com.condpulse.gateway.spi.BaseAdapter.DeviceContext;
import br.com.condpulse.gateway.spi.Spi.*;

/** Utilitários de teste: relógio controlado, configuração sintética, cofre em memória e plataforma falsa. */
public final class TestSupport {
 private TestSupport() {}
 public static final Instant START=Instant.parse("2030-01-07T15:00:00Z"); // segunda-feira, 12h em São Paulo
 public static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");

 public static final class TestClock extends Clock {
  private volatile Instant now=START;
  public void advance(Duration d) { now=now.plus(d); }
  public void set(Instant i) { now=i; }
  @Override public ZoneId getZone() { return ZoneOffset.UTC; }
  @Override public Clock withZone(ZoneId zone) { return this; }
  @Override public Instant instant() { return now; }
 }

 public static Path tempDir() {
  try { return Files.createTempDirectory("cp-gateway-test"); } catch(IOException e) { throw new IllegalStateException(e); }
 }

 /** Cofre em memória; os valores são sintéticos. */
 public static SecretProvider secrets(Map<String,String> values) {
  return ref->Optional.ofNullable(values.get(ref)).map(String::toCharArray);
 }

 public static Channel channel(String id,String nativeChannel,String openAction) {
  return new Channel(id,"ponto-"+id,1,nativeChannel,openAction,Direction.IN);
 }

 /** Equipamento sintético habilitado, em bancada. */
 public static Device device(String id,Brand brand,ConnectionMode mode,DecisionMode decision,URI base,List<Channel> channels,
     Map<String,String> nativeSchedules,Map<String,String> params,boolean openEnabled) {
  return new Device(id,"prov-"+id,"Equipamento "+id,brand,"SKU-TESTE","FW-TESTE","API-TESTE",mode,decision,
   mode==ConnectionMode.LOCAL?base:null,mode==ConnectionMode.LOCAL?"cred-"+id:null,
   mode==ConnectionMode.NUVEM || decision==DecisionMode.EDGE_ONLINE?"token-"+id:null,"1","epoca-1",true,Phase.BANCADA,openEnabled,
   EnumSet.of(OpenOrigin.PORTARIA_LOCAL,OpenOrigin.PORTARIA_REMOTA,OpenOrigin.MORADOR_APP),1,channels,nativeSchedules,params);
 }

 public static Profile profile(Device d) {
  return new Profile(d.brand(),d.sku(),d.firmware(),d.apiVersion(),d.decisionMode(),d.connectionMode(),Map.of(),Map.of());
 }

 public static GatewayConfig config(List<Device> devices,boolean withProfiles,CondoOpenPolicy openPolicy) {
  var windows=Map.of("comercial",List.of(new WeeklyWindow(EnumSet.of(DayOfWeek.MONDAY,DayOfWeek.TUESDAY,DayOfWeek.WEDNESDAY,DayOfWeek.THURSDAY,DayOfWeek.FRIDAY),
   LocalTime.of(8,0),LocalTime.of(18,0))),"sempre",List.of(new WeeklyWindow(EnumSet.allOf(DayOfWeek.class),LocalTime.MIN,LocalTime.of(23,59,59))),
   // Faixa dos documentos da plataforma (V048: só regra de tempo integral), comissionada pelo MASTER.
   "FULL_TIME",List.of(new WeeklyWindow(EnumSet.allOf(DayOfWeek.class),LocalTime.MIN,LocalTime.of(23,59,59))));
  boolean inbound=devices.stream().anyMatch(d->d.inboundTokenRef()!=null);
  return new GatewayConfig("gateway-teste","conector-teste","Condomínio Teste 1",ZONE,null,
   inbound?new Inbound("127.0.0.1",0,null,null,256*1024):null,Limits.defaults(),openPolicy,windows,
   withProfiles?devices.stream().map(TestSupport::profile).toList():List.of(),devices);
 }

 public static DeviceContext context(Device device,Clock clock) {
  return new DeviceContext(device,"conector-teste",new SecretProvider.DeviceCredential("usuario-sintetico","senha-sintetica".toCharArray()),
   Duration.ofSeconds(2),Duration.ofSeconds(2),clock,ZONE);
 }

 public static SyncCommand command(String id,String device,String subject,br.com.condpulse.gateway.queue.LocalCommandQueue.CommandType type,long version,Map<String,Object> payload) {
  return new SyncCommand(id,"idem-"+id,device,subject,type,version,payload,START);
 }

 /** Plataforma falsa: registra o que o gateway envia e entrega o que o teste preparar. */
 public static final class StubCentral implements CentralApiClient {
  public final List<SyncResult> results=new CopyOnWriteArrayList<>();
  public final List<EdgeEvent> events=new CopyOnWriteArrayList<>();
  public final List<EdgeDecision> decisions=new CopyOnWriteArrayList<>();
  public final List<OpenDispatcher.Receipt> receipts=new CopyOnWriteArrayList<>();
  public final List<SyncCommand> nextSync=new ArrayList<>();
  public final List<OpenDispatcher.OpenCommand> nextOpen=new ArrayList<>();
  public final Map<String,byte[]> materials=new java.util.concurrent.ConcurrentHashMap<>();
  public volatile boolean available=true;
  public volatile Set<String> rejectEvents=Set.of();

  private void check() throws CentralUnavailable { if(!available) throw new CentralUnavailable("CENTRAL_UNREACHABLE"); }
  @Override public List<EventIngestResult> postEvents(List<EdgeEvent> batch,String key) throws CentralUnavailable {
   check();
   var out=new ArrayList<EventIngestResult>();
   for(var e:batch) {
    if(rejectEvents.contains(e.eventId())) { out.add(new EventIngestResult(e.eventId(),IngestStatus.REJECTED,"UNKNOWN_DEVICE")); continue; }
    boolean dup=events.stream().anyMatch(x->x.eventId().equals(e.eventId()));
    if(!dup) events.add(e);
    out.add(new EventIngestResult(e.eventId(),dup?IngestStatus.DUPLICATE:IngestStatus.ACCEPTED,null));
   }
   return out;
  }
  @Override public DecisionReceipt postDecision(EdgeDecision d,String key) throws CentralUnavailable { check(); decisions.add(d); return new DecisionReceipt(d.decisionId(),IngestStatus.ACCEPTED,null); }
  @Override public synchronized List<SyncCommand> pullSyncCommands(int max) throws CentralUnavailable { check(); var out=List.copyOf(nextSync); nextSync.clear(); return out; }
  @Override public synchronized List<OpenDispatcher.OpenCommand> pullOpenCommands(int max) throws CentralUnavailable { check(); var out=List.copyOf(nextOpen); nextOpen.clear(); return out; }
  @Override public void reportSyncResult(SyncResult result) throws CentralUnavailable { check(); results.add(result); }
  @Override public void reportOpenReceipt(OpenDispatcher.Receipt receipt) throws CentralUnavailable { check(); receipts.add(receipt); }
  @Override public void reportHealth(GatewayHealth health) throws CentralUnavailable { check(); }
  @Override public byte[] fetchMaterial(String ref,String deviceId) throws CentralUnavailable {
   check();
   byte[] v=materials.get(ref);
   if(v==null) throw new CentralUnavailable("MATERIAL_NOT_FOUND");
   return v.clone();
  }
 }

 /** JPEG mínimo sintético (cabeçalho SOI); não é foto de pessoa. */
 public static byte[] syntheticJpeg() { return new byte[]{(byte)0xFF,(byte)0xD8,(byte)0xFF,(byte)0xE0,0,0x10,'J','F','I','F',0,(byte)0xFF,(byte)0xD9}; }
}
