package br.com.condpulse.gateway.open;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import br.com.condpulse.gateway.capability.CapabilityRegistry;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.GatewayConfig.OpenOrigin;
import br.com.condpulse.gateway.journal.Journal;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.AccessProviderAdapter;
import br.com.condpulse.gateway.spi.Spi.CapabilityName;
import br.com.condpulse.gateway.spi.Spi.OpenAction;
import br.com.condpulse.gateway.spi.Spi.OpenResult;
import br.com.condpulse.gateway.spi.Spi.Operation;

/**
 * Abertura pontual (AC-020; manual 16.2). Imediatamente antes do envio o gateway valida comando, nonce, validade
 * curta, origem, destino comissionado, revisões e capacidade; então grava DISPATCH_COMMITTED com fsync e só depois
 * chama o adaptador, uma única vez. Depois do compromisso nada é reenviado: timeout, erro ambíguo, reinício ou
 * reconexão resultam em UNKNOWN e exigem nova solicitação consciente. Entre a gravação e a chamada, uma queda pode
 * perder a abertura — perda é preferida a abertura duplicada.
 */
public final class OpenDispatcher implements AutoCloseable {
 public enum Status { RECEIVED, REJECTED, DISPATCH_COMMITTED, SENT, CONFIRMED, UNKNOWN, EXPIRED }

 /**
  * Comando emitido pela plataforma. tenant e ator não vêm aqui: são do certificado e do servidor. residentGrantRef é
  * obrigatório para MORADOR_APP e prova que a plataforma conferiu a concessão individual do morador.
  */
 public record OpenCommand(String commandId,String nonce,String deviceId,String channelId,String pointId,long bindingRevision,
     long installationRevision,Instant issuedAt,Instant expiresAt,OpenOrigin origin,String authorizationRef,String residentGrantRef) {
  public OpenCommand {
   for(String v:new String[]{commandId,nonce,deviceId,channelId,pointId,authorizationRef}) if(v==null || v.isBlank() || v.length()>200) throw new IllegalArgumentException("Campo obrigatório do comando de abertura.");
   Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt); Objects.requireNonNull(origin);
  }
 }
 /** Recibo durável. physicalEvidence fica com a plataforma: aceite da API não comprova porta aberta. */
 public record Receipt(String commandId,String deviceId,String channelId,Status status,String reasonCode,Instant updatedAt,Instant dispatchCommittedAt) {
  public boolean replayProhibited() { return true; }
 }

 /** Extensão futura (ex.: liberação por agente de voz/IA). Não implementada: nenhuma origem nova é aceita sem ela. */
 public interface OpenOriginPolicy {
  Optional<String> reject(OpenCommand command,GatewayConfig.Device device);
 }

 private final Journal journal;
 private final Clock clock;
 private final GatewayConfig config;
 private final CapabilityRegistry registry;
 private final Function<String,Optional<AccessProviderAdapter>> adapters;
 private final Map<String,Receipt> receipts=new HashMap<>();
 private final Set<String> nonces=new HashSet<>();
 private volatile java.util.function.Predicate<String> quarantined=id->false;

 /** Equipamentos em quarentena (identidade real divergente do perfil) nunca recebem abertura. */
 public void quarantineCheck(java.util.function.Predicate<String> check) { this.quarantined=check; }

 public OpenDispatcher(Path dir,Clock clock,GatewayConfig config,CapabilityRegistry registry,Function<String,Optional<AccessProviderAdapter>> adapters) {
  this.clock=clock; this.config=config; this.registry=registry; this.adapters=adapters;
  this.journal=new Journal(dir.resolve("open-receipts.jsonl"));
  journal.replay(this::apply);
  for(var r:Map.copyOf(receipts).values())
   if(r.status()==Status.DISPATCH_COMMITTED || r.status()==Status.RECEIVED) record(r.commandId(),r.deviceId(),r.channelId(),null,Status.UNKNOWN,"GATEWAY_RESTARTED_AFTER_COMMIT",r.dispatchCommittedAt());
 }

 public synchronized Optional<Receipt> receipt(String commandId) { return Optional.ofNullable(receipts.get(commandId)); }

 public Receipt dispatch(OpenCommand command) {
  Receipt prepared;
  AccessProviderAdapter adapter;
  GatewayConfig.Channel channel;
  synchronized(this) {
   var existing=receipts.get(command.commandId());
   if(existing!=null) return existing; // mesmo commandId nunca envia de novo
   if(nonces.contains(command.nonce())) return reject(command,"NONCE_REUSED");
   Instant now=clock.instant();
   long ttl=Duration.between(command.issuedAt(),command.expiresAt()).toMillis();
   if(ttl<=0 || ttl>config.limits().maxOpenTtlSeconds()*1000L) return reject(command,"TTL_OUT_OF_RANGE");
   if(command.issuedAt().isAfter(now.plusSeconds(config.limits().maxClockSkewSeconds()))) return reject(command,"CLOCK_UNTRUSTED");
   if(!now.isBefore(command.expiresAt())) return record(command,Status.EXPIRED,"EXPIRED_BEFORE_DISPATCH",null);
   var device=config.device(command.deviceId()).orElse(null);
   if(device==null || !device.enabled()) return reject(command,"NOT_COMMISSIONED");
   channel=device.channel(command.channelId()).orElse(null);
   if(channel==null || !channel.pointId().equals(command.pointId())) return reject(command,"NOT_COMMISSIONED");
   if(channel.bindingRevision()!=command.bindingRevision()) return reject(command,"STALE_BINDING");
   if(device.installationRevision()!=command.installationRevision()) return reject(command,"STALE_INSTALLATION");
   if(quarantined.test(device.deviceId())) return reject(command,"DEVICE_IDENTITY_MISMATCH");
   String originRejection=originRejection(command,device);
   if(originRejection!=null) return reject(command,originRejection);
   adapter=adapters.apply(device.deviceId()).orElse(null);
   if(adapter==null) return reject(command,"ADAPTER_UNAVAILABLE");
   if(!adapter.manifest().supports(Operation.OPEN)) return reject(command,"NAO_SUPORTADO_SEM_DOCUMENTACAO");
   var capability=registry.allows(device,adapter.manifest(),CapabilityName.scopedRemoteOpen);
   if(!capability.allowed()) return reject(command,capability.reasonCode());
   nonces.add(command.nonce());
   prepared=record(command,Status.DISPATCH_COMMITTED,null,now);
  }
  // Fora do bloqueio: a chamada pode demorar. Revalida a validade imediatamente antes do envio.
  if(!clock.instant().isBefore(command.expiresAt())) return record(command,Status.EXPIRED,"EXPIRED_AFTER_COMMIT",prepared.dispatchCommittedAt());
  OpenResult result;
  try {
   result=adapter.open(new OpenAction(command.commandId(),channel.channelId(),channel.nativeChannel(),channel.nativeOpenAction(),command.expiresAt()));
  } catch(RuntimeException e) {
   result=new OpenResult(br.com.condpulse.gateway.spi.Spi.OpenOutcome.AMBIGUOUS,"ADAPTER_ERROR");
  }
  return switch(result.outcome()) {
   case ACCEPTED -> record(command,Status.SENT,result.reasonCode(),prepared.dispatchCommittedAt());
   case CONFIRMED -> record(command,Status.CONFIRMED,result.reasonCode(),prepared.dispatchCommittedAt());
   case NOT_SENT -> record(command,Status.REJECTED,result.reasonCode()==null?"NOT_DELIVERED":result.reasonCode(),prepared.dispatchCommittedAt());
   case REJECTED_BY_DEVICE -> record(command,Status.REJECTED,result.reasonCode()==null?"DEVICE_REJECTED":result.reasonCode(),prepared.dispatchCommittedAt());
   case NAO_SUPORTADO_SEM_DOCUMENTACAO -> record(command,Status.REJECTED,"NAO_SUPORTADO_SEM_DOCUMENTACAO",prepared.dispatchCommittedAt());
   case AMBIGUOUS -> record(command,Status.UNKNOWN,result.reasonCode()==null?"AMBIGUOUS":result.reasonCode(),prepared.dispatchCommittedAt());
  };
 }

 private String originRejection(OpenCommand command,GatewayConfig.Device device) {
  if(!device.openEnabled()) return "OPEN_DISABLED";
  if(!config.openPolicy().allowedOrigins().contains(command.origin())) return "ORIGIN_DISABLED_FOR_CONDO";
  if(!device.openOrigins().contains(command.origin())) return "ORIGIN_DISABLED_FOR_DEVICE";
  if(command.origin()==OpenOrigin.MORADOR_APP) {
   if(!config.openPolicy().residentAppOpenEnabled()) return "RESIDENT_OPEN_DISABLED";
   if(command.residentGrantRef()==null || command.residentGrantRef().isBlank()) return "RESIDENT_GRANT_MISSING";
  }
  return null;
 }

 private synchronized Receipt reject(OpenCommand c,String reason) { return record(c,Status.REJECTED,reason,null); }
 private synchronized Receipt record(OpenCommand c,Status status,String reason,Instant committed) {
  return record(c.commandId(),c.deviceId(),c.channelId(),c.nonce(),status,reason,committed);
 }
 private synchronized Receipt record(String commandId,String deviceId,String channelId,String nonce,Status status,String reason,Instant committed) {
  var m=new LinkedHashMap<String,Object>();
  m.put("commandId",commandId); m.put("deviceId",deviceId); m.put("channelId",channelId); m.put("nonce",nonce);
  m.put("status",status.name()); m.put("reason",reason); m.put("at",clock.instant().toString());
  m.put("committedAt",committed==null?null:committed.toString());
  journal.append(m);
  apply(m);
  return receipts.get(commandId);
 }
 private void apply(Map<String,Object> m) {
  String committed=Json.string(m,"committedAt");
  String nonce=Json.string(m,"nonce");
  if(nonce!=null) nonces.add(nonce);
  var r=new Receipt(Json.string(m,"commandId"),Json.string(m,"deviceId"),Json.string(m,"channelId"),Status.valueOf(Json.string(m,"status")),
   Json.string(m,"reason"),Instant.parse(Json.string(m,"at")),committed==null?null:Instant.parse(committed));
  receipts.put(r.commandId(),r);
 }

 @Override public void close() { journal.close(); }
}
