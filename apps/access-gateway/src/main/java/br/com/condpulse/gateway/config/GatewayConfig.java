package br.com.condpulse.gateway.config;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import br.com.condpulse.gateway.spi.Spi.Brand;
import br.com.condpulse.gateway.spi.Spi.CapabilityName;
import br.com.condpulse.gateway.spi.Spi.ConnectionMode;
import br.com.condpulse.gateway.spi.Spi.DecisionMode;
import br.com.condpulse.gateway.spi.Spi.Direction;

/**
 * Configuração de um gateway: um condomínio, seus equipamentos, canais, faixas de horário e perfis de combinação. Não
 * contém segredo: só referências ({@code *Ref}) resolvidas pelo {@link SecretProvider}. O tenant efetivo é o do
 * certificado do gateway na plataforma; {@code condoLabel} é apenas rótulo local.
 */
public record GatewayConfig(String gatewayId,String connectorId,String condoLabel,ZoneId zone,Central central,Inbound inbound,
    Limits limits,CondoOpenPolicy openPolicy,Map<String,List<WeeklyWindow>> schedules,List<Profile> profiles,List<Device> devices) {

 public GatewayConfig {
  requireId(gatewayId,"gatewayId"); requireId(connectorId,"connectorId"); Objects.requireNonNull(zone,"zone");
  Objects.requireNonNull(limits,"limits"); Objects.requireNonNull(openPolicy,"openPolicy");
  schedules=Map.copyOf(schedules); profiles=List.copyOf(profiles); devices=List.copyOf(devices);
  var ids=new java.util.HashSet<String>();
  for(Device d:devices) if(!ids.add(d.deviceId())) throw new IllegalArgumentException("Equipamento repetido: "+d.deviceId());
  for(Device d:devices) for(String ref:d.nativeSchedules().keySet())
   if(!schedules.containsKey(ref)) throw new IllegalArgumentException("Faixa de horário sem janela definida: "+ref);
  if(devices.stream().anyMatch(d->d.inboundTokenRef()!=null) && inbound==null) throw new IllegalArgumentException("Equipamento com entrada exige bloco inbound.");
 }

 public Optional<Device> device(String deviceId) { return devices.stream().filter(d->d.deviceId().equals(deviceId)).findFirst(); }

 /** API central (mTLS). keystorePath/truststorePath são arquivos montados; a senha vem do cofre. */
 public record Central(URI baseUrl,String keystorePath,String keystorePasswordRef,String truststorePath) {
  public Central {
   Objects.requireNonNull(baseUrl,"central.baseUrl");
   if(!"https".equals(baseUrl.getScheme()) || baseUrl.getUserInfo()!=null) throw new IllegalArgumentException("API central exige HTTPS sem credencial na URL.");
  }
 }
 /** Servidor de entrada para chamadas iniciadas pelos equipamentos (online, push, nuvem). */
 public record Inbound(String bindAddress,int port,String tlsKeystorePath,String tlsKeystorePasswordRef,int maxBodyBytes) {
  public Inbound {
   requireId(bindAddress,"inbound.bindAddress");
   if(port<0 || port>65535) throw new IllegalArgumentException("Porta de entrada inválida (0 = porta efêmera, só para testes).");
   if(maxBodyBytes<1024 || maxBodyBytes>4*1024*1024) throw new IllegalArgumentException("Limite de corpo de entrada entre 1 KB e 4 MB.");
  }
 }
 /**
  * queueSoftLimit: acima dele, só revogação entra. queueHardLimit: nem revogação entra e o gateway alarma (nunca
  * descarta em silêncio). maxPolicyAgeSeconds: idade máxima da última sincronização para decidir localmente.
  */
 public record Limits(int queueSoftLimit,int queueHardLimit,long maxPolicyAgeSeconds,long maxClockSkewSeconds,int maxOpenTtlSeconds,
     long deviceTimeoutMillis,long pushPickupTimeoutMillis) {
  public Limits {
   if(queueSoftLimit<1 || queueHardLimit<=queueSoftLimit) throw new IllegalArgumentException("Limites de fila inválidos.");
   if(maxPolicyAgeSeconds<1 || maxClockSkewSeconds<0 || maxClockSkewSeconds>60) throw new IllegalArgumentException("Idade de política ou desvio de relógio inválidos.");
   if(maxOpenTtlSeconds<1 || maxOpenTtlSeconds>10) throw new IllegalArgumentException("Validade de abertura de 1 a 10 segundos.");
   if(deviceTimeoutMillis<200 || deviceTimeoutMillis>60_000) throw new IllegalArgumentException("Timeout de equipamento entre 200 ms e 60 s.");
   if(pushPickupTimeoutMillis<1000 || pushPickupTimeoutMillis>300_000) throw new IllegalArgumentException("Espera de retirada push entre 1 s e 300 s.");
  }
  public static Limits defaults() { return new Limits(5_000,6_000,900,5,10,5_000,30_000); }
 }

 /** Origem de um pedido de abertura pontual. */
 public enum OpenOrigin { PORTARIA_LOCAL, PORTARIA_REMOTA, MORADOR_APP }

 /**
  * Política de abertura do condomínio. A liberação pelo app do morador só vale com residentAppOpenEnabled e com
  * concessão individual do morador atestada pela plataforma no comando (residentGrantRef); começa desligada.
  */
 public record CondoOpenPolicy(Set<OpenOrigin> allowedOrigins,boolean residentAppOpenEnabled) {
  public CondoOpenPolicy {
   allowedOrigins=Set.copyOf(allowedOrigins);
   if(allowedOrigins.contains(OpenOrigin.MORADOR_APP) && !residentAppOpenEnabled)
    throw new IllegalArgumentException("MORADOR_APP exige residentAppOpenEnabled=true no condomínio.");
  }
  public static CondoOpenPolicy closed() { return new CondoOpenPolicy(Set.of(),false); }
 }

 /** Janela semanal no fuso do condomínio. end depois de start; janela que cruza meia-noite vira duas. */
 public record WeeklyWindow(Set<DayOfWeek> days,LocalTime start,LocalTime end) {
  public WeeklyWindow {
   days=Set.copyOf(days);
   if(days.isEmpty() || start==null || end==null || !start.isBefore(end)) throw new IllegalArgumentException("Janela semanal inválida.");
  }
 }

 public enum Phase { BANCADA, PRODUCAO }

 /**
  * Perfil de uma combinação (marca, SKU, firmware, API, modo de decisão, modo de conexão) com o que foi observado em
  * bancada e aprovado pelo MASTER. Ausente = desconhecido, nunca true.
  */
 public record Profile(Brand brand,String sku,String firmware,String apiVersion,DecisionMode decisionMode,ConnectionMode connectionMode,
     Map<CapabilityName,Boolean> observed,Map<CapabilityName,Boolean> approved) {
  public Profile {
   Objects.requireNonNull(brand); requireId(sku,"sku"); requireId(firmware,"firmware"); requireId(apiVersion,"apiVersion");
   Objects.requireNonNull(decisionMode); Objects.requireNonNull(connectionMode);
   observed=Map.copyOf(observed); approved=Map.copyOf(approved);
  }
 }

 /**
  * Canal comissionado de um equipamento. nativeChannel e nativeOpenAction são valores tipados validados pelo
  * adaptador (ex.: número da porta), nunca URL ou comando.
  */
 public record Channel(String channelId,String pointId,long bindingRevision,String nativeChannel,String nativeOpenAction,Direction direction) {
  public Channel {
   requireId(channelId,"channelId"); requireId(pointId,"pointId");
   if(bindingRevision<1) throw new IllegalArgumentException("bindingRevision a partir de 1.");
   if(nativeChannel==null || !nativeChannel.matches("[A-Za-z0-9_=,.:-]{1,64}")) throw new IllegalArgumentException("nativeChannel fora do formato.");
   if(nativeOpenAction!=null && !nativeOpenAction.matches("[A-Za-z0-9_=,.:-]{1,64}")) throw new IllegalArgumentException("nativeOpenAction fora do formato.");
   Objects.requireNonNull(direction);
  }
 }

 /**
  * Equipamento comissionado. LOCAL exige baseUrl (rede do condomínio) e credentialRef; NUVEM exige inboundTokenRef
  * (o equipamento chama a plataforma) e não aceita baseUrl. enabled e openEnabled começam false.
  */
 public record Device(String deviceId,String deviceProvisioningId,String label,Brand brand,String sku,String firmware,String apiVersion,
     ConnectionMode connectionMode,DecisionMode decisionMode,URI baseUrl,String credentialRef,String inboundTokenRef,String nativeDeviceId,
     String eventEpoch,boolean enabled,Phase phase,boolean openEnabled,Set<OpenOrigin> openOrigins,long installationRevision,
     List<Channel> channels,Map<String,String> nativeSchedules,Map<String,String> params) {
  public Device {
   requireId(deviceId,"deviceId"); requireId(deviceProvisioningId,"deviceProvisioningId"); requireId(label,"label");
   Objects.requireNonNull(brand); requireId(sku,"sku"); requireId(firmware,"firmware"); requireId(apiVersion,"apiVersion");
   Objects.requireNonNull(connectionMode); Objects.requireNonNull(decisionMode); requireId(eventEpoch,"eventEpoch"); Objects.requireNonNull(phase);
   if(installationRevision<1) throw new IllegalArgumentException("installationRevision a partir de 1.");
   openOrigins=Set.copyOf(openOrigins); channels=List.copyOf(channels); nativeSchedules=Map.copyOf(nativeSchedules); params=Map.copyOf(params);
   if(connectionMode==ConnectionMode.LOCAL) {
    if(baseUrl==null || credentialRef==null) throw new IllegalArgumentException("Modo LOCAL exige baseUrl e credentialRef: "+deviceId);
    String scheme=baseUrl.getScheme();
    if(!("http".equals(scheme) || "https".equals(scheme)) || baseUrl.getHost()==null || baseUrl.getUserInfo()!=null
       || (baseUrl.getRawPath()!=null && !baseUrl.getRawPath().isEmpty() && !"/".equals(baseUrl.getRawPath())) || baseUrl.getRawQuery()!=null)
     throw new IllegalArgumentException("baseUrl deve ser só esquema, host e porta: "+deviceId);
   } else {
    if(baseUrl!=null) throw new IllegalArgumentException("Modo NUVEM não aceita baseUrl: o equipamento chama a plataforma: "+deviceId);
    if(inboundTokenRef==null) throw new IllegalArgumentException("Modo NUVEM exige inboundTokenRef: "+deviceId);
   }
   if(decisionMode==DecisionMode.EDGE_ONLINE && inboundTokenRef==null) throw new IllegalArgumentException("EDGE_ONLINE exige inboundTokenRef: "+deviceId);
   var ids=new java.util.HashSet<String>();
   for(Channel c:channels) if(!ids.add(c.channelId())) throw new IllegalArgumentException("Canal repetido: "+c.channelId());
   for(var e:nativeSchedules.entrySet()) if(!e.getValue().matches("[A-Za-z0-9_,.-]{1,32}")) throw new IllegalArgumentException("Faixa nativa fora do formato: "+e.getKey());
   for(var e:params.entrySet()) if(!e.getKey().matches("[a-zA-Z0-9_.]{1,64}") || e.getValue()==null || e.getValue().length()>128)
    throw new IllegalArgumentException("Parâmetro fora do formato: "+e.getKey());
  }
  public Optional<Channel> channel(String channelId) { return channels.stream().filter(c->c.channelId().equals(channelId)).findFirst(); }
  public Optional<Channel> channelByNative(String nativeChannel) { return channels.stream().filter(c->c.nativeChannel().equals(nativeChannel)).findFirst(); }
  public String param(String key) { return params.get(key); }
 }

 static void requireId(String value,String name) {
  if(value==null || value.isBlank() || value.length()>200) throw new IllegalArgumentException("Campo obrigatório: "+name);
 }
}
