package br.com.condpulse.realtime.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import br.com.condpulse.shared.application.BusinessProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Canais abertos desta instância (CP-036). A cada passada, por canal: prazo de vida, credencial (sessão BFF ou token do
 * celular) e estado resumido da unidade, que repete a validação do condomínio. Mudou → evento "changed" só com os nomes
 * dos assuntos; o cliente consulta o detalhe pelas rotas normais, com as próprias permissões. Qualquer perda de acesso
 * encerra o canal com "end" e um motivo; o cliente consulta o estado de novo ao reconectar.
 */
public final class RealtimeHub implements AutoCloseable {
 private static final Logger log=LoggerFactory.getLogger(RealtimeHub.class);

 /** Destino dos eventos (SseEmitter na API). Falha de escrita significa cliente que foi embora. */
 public interface Sink { void send(String event,String data) throws IOException; void complete(); }
 /** Confere se a credencial que abriu o canal ainda vale; lança BusinessProblem ou JwtException quando não. */
 @FunctionalInterface public interface Credential { void verify(); }
 @FunctionalInterface public interface Snapshots { Map<String,String> load(); }
 public record Settings(Duration tick,Duration lifetime,int maxStreams,int maxPerAccount) {
  public Settings {
   if(tick.isNegative() || tick.isZero() || lifetime.compareTo(tick)<0 || maxStreams<1 || maxPerAccount<1)
    throw new IllegalArgumentException("REALTIME_SETTINGS_INVALID");
  }
 }

 private final Settings settings;
 private final Clock clock;
 private final ConcurrentHashMap<UUID,Channel> channels=new ConcurrentHashMap<>();

 public RealtimeHub(Settings settings,Clock clock) { this.settings=Objects.requireNonNull(settings); this.clock=Objects.requireNonNull(clock); }
 public Settings settings() { return settings; }
 public int open() { return channels.size(); }

 /** Registra o canal já validado; acima dos limites recusa antes de abrir (429), sem derrubar canais existentes. */
 public synchronized UUID register(UUID account,Credential credential,Snapshots snapshots,Map<String,String> initial,Sink sink) {
  Objects.requireNonNull(account); Objects.requireNonNull(credential); Objects.requireNonNull(snapshots); Objects.requireNonNull(sink);
  long mine=channels.values().stream().filter(channel->channel.account.equals(account)).count();
  if(channels.size()>=settings.maxStreams() || mine>=settings.maxPerAccount())
   throw new BusinessProblem("REALTIME_LIMIT_REACHED",429,"Muitas conexões abertas. A tela continua atualizando ao recarregar.");
  UUID id=UUID.randomUUID();
  channels.put(id,new Channel(account,credential,snapshots,Map.copyOf(initial),sink,clock.instant().plus(settings.lifetime())));
  return id;
 }
 public void remove(UUID id) { if(id!=null) channels.remove(id); }

 /** Uma passada por todos os canais; erros de um canal não afetam os outros. */
 public void tick() {
  for(var entry:List.copyOf(channels.entrySet())) {
   try { tick(entry.getKey(),entry.getValue()); }
   catch(RuntimeException error) {
    log.warn("event=realtime_tick_failed category={}",error.getClass().getSimpleName());
    end(entry.getKey(),entry.getValue(),"unavailable");
   }
  }
 }

 private void tick(UUID id,Channel channel) {
  if(!clock.instant().isBefore(channel.deadline)) { end(id,channel,"timeout"); return; }
  try { channel.credential.verify(); }
  catch(JwtException expired) { end(id,channel,"expired"); return; }
  catch(BusinessProblem problem) { end(id,channel,problem.status()>=500?"unavailable":"revoked"); return; }
  Map<String,String> current;
  try { current=channel.snapshots.load(); }
  catch(BusinessProblem problem) { end(id,channel,problem.status()>=500?"unavailable":"revoked"); return; }
  var changed=current.entrySet().stream().filter(item->!Objects.equals(channel.last.get(item.getKey()),item.getValue()))
   .map(Map.Entry::getKey).sorted().toList();
  channel.last=Map.copyOf(current);
  try {
   if(changed.isEmpty()) channel.sink.send("ping","{}");
   else channel.sink.send("changed",topics(changed));
  } catch(IOException | IllegalStateException gone) { channels.remove(id); }
 }

 private void end(UUID id,Channel channel,String reason) {
  channels.remove(id);
  try { channel.sink.send("end","{\"reason\":\""+reason+"\"}"); } catch(IOException | IllegalStateException ignored) { /* cliente já saiu */ }
  try { channel.sink.complete(); } catch(RuntimeException ignored) { /* já encerrado */ }
 }

 /** Desligamento da instância: avisa cada cliente para reconectar (em outra instância) e consultar o estado. */
 @Override public void close() {
  for(var entry:List.copyOf(channels.entrySet())) end(entry.getKey(),entry.getValue(),"shutdown");
 }

 public static String topics(List<String> topics) {
  return topics.stream().map(topic->"\""+topic+"\"").collect(Collectors.joining(",","{\"topics\":[","]}"));
 }

 private static final class Channel {
  final UUID account; final Credential credential; final Snapshots snapshots; final Sink sink; final Instant deadline;
  volatile Map<String,String> last;
  Channel(UUID account,Credential credential,Snapshots snapshots,Map<String,String> last,Sink sink,Instant deadline) {
   this.account=account; this.credential=credential; this.snapshots=snapshots; this.last=last; this.sink=sink; this.deadline=deadline;
  }
 }
}
