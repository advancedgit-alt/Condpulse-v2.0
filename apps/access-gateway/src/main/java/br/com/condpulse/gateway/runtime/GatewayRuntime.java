package br.com.condpulse.gateway.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import br.com.condpulse.gateway.capability.CapabilityRegistry;
import br.com.condpulse.gateway.central.CentralApiClient;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.SecretProvider;
import br.com.condpulse.gateway.decision.LocalDecisionEngine;
import br.com.condpulse.gateway.events.EventOutbox;
import br.com.condpulse.gateway.inbound.InboundServer;
import br.com.condpulse.gateway.open.OpenDispatcher;
import br.com.condpulse.gateway.queue.LocalCommandQueue;
import br.com.condpulse.gateway.spi.AccessProviderAdapter;

/**
 * Monta e executa o gateway. Um único processo por diretório de dados: o bloqueio exclusivo impede dois gateways
 * decidindo ou transmitindo sobre o mesmo diário (failover para reserva exige eleição/fencing ainda não implementados).
 */
public final class GatewayRuntime implements AutoCloseable {
 private static final System.Logger LOG=System.getLogger(GatewayRuntime.class.getName());

 private final FileChannel lockChannel;
 private final FileLock lock;
 private final LocalCommandQueue queue;
 private final OpenDispatcher opens;
 private final EventOutbox outbox;
 private final LocalDecisionEngine decisions;
 private final br.com.condpulse.gateway.central.DecisionOutbox decisionOutbox;
 private final GatewayService service;
 private final InboundServer inbound;
 private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2,r->{ var t=new Thread(r,"gateway-loop"); t.setDaemon(false); return t; });

 public GatewayRuntime(GatewayConfig config,SecretProvider secrets,CentralApiClient central,Path dataDir,Clock clock) throws IOException {
  Files.createDirectories(dataDir);
  lockChannel=FileChannel.open(dataDir.resolve("gateway.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
  lock=lockChannel.tryLock();
  if(lock==null) { lockChannel.close(); throw new IllegalStateException("Outro processo do gateway usa este diretório de dados."); }
  var registry=new CapabilityRegistry(config.profiles());
  var adapters=new HashMap<String,AccessProviderAdapter>();
  for(var d:config.devices()) AdapterFactory.create(config,d,secrets,clock).ifPresent(a->adapters.put(d.deviceId(),a));
  queue=new LocalCommandQueue(dataDir,clock,config.limits().queueSoftLimit(),config.limits().queueHardLimit());
  opens=new OpenDispatcher(dataDir,clock,config,registry,id->java.util.Optional.ofNullable(adapters.get(id)));
  outbox=new EventOutbox(dataDir,clock,50_000);
  decisions=new LocalDecisionEngine(dataDir,clock,config);
  decisionOutbox=new br.com.condpulse.gateway.central.DecisionOutbox(dataDir,clock,10_000);
  service=new GatewayService(config,registry,adapters,queue,opens,outbox,decisions,decisionOutbox,central,clock);
  inbound=config.inbound()==null?null:new InboundServer(config,secrets,service::handleInbound);
 }

 public GatewayService service() { return service; }

 public void start() {
  if(inbound!=null) inbound.start();
  // Cada etapa isolada: falha na coleta não impede drenar o que já está gravado, nem a saúde.
  scheduler.scheduleWithFixedDelay(steps(
   guard("comandos",service::pullFromCentral),guard("fila",()->service.processQueues(20)),guard("resultados",service::publishPendingResults)),0,2,TimeUnit.SECONDS);
  scheduler.scheduleWithFixedDelay(steps(
   guard("coleta",service::pollEvents),guard("envio de eventos",service::forwardEvents),guard("telemetria",service::forwardDecisions),
   guard("saúde",service::checkHealth)),1,10,TimeUnit.SECONDS);
  LOG.log(System.Logger.Level.INFO,"Gateway iniciado.");
 }

 /** Etapas de um ciclo, em ordem; cada uma deve vir protegida por {@link #guard}. */
 public static Runnable steps(Runnable... steps) { return ()->{ for(Runnable r:steps) r.run(); }; }

 /** Isola a falha de uma etapa: registra e segue para a próxima. */
 public static Runnable guard(String name,Runnable task) {
  return ()->{
   try { task.run(); }
   catch(RuntimeException e) { LOG.log(System.Logger.Level.ERROR,"Ciclo {0} falhou: {1}",name,e.getClass().getSimpleName()); }
  };
 }

 @Override public void close() {
  scheduler.shutdownNow();
  if(inbound!=null) inbound.close();
  queue.close(); opens.close(); outbox.close(); decisions.close(); decisionOutbox.close();
  try { lock.release(); lockChannel.close(); } catch(IOException ignored) {}
 }
}
