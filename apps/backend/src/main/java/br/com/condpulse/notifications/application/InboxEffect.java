package br.com.condpulse.notifications.application;

import java.util.Map;
import java.util.UUID;
import br.com.condpulse.notifications.infrastructure.InboxStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Caixa interna (parte do CP-035): chegada aguardando resposta e encomenda recebida viram aviso para quem pode agir na
 * unidade. Registro já encerrado quando o worker chega (chegada respondida ou vencida, encomenda retirada ou anulada)
 * ou unidade sem destinatário apto: tarefa SKIPPED, sem aviso. "Disponível na caixa" não é leitura: só a abertura
 * autenticada pelo destinatário marca leitura. Idempotente pela chave tarefa/destinatário/canal.
 */
@Component @Profile("worker")
public class InboxEffect implements OutboxEffect {
 static final Map<String,String> KINDS=Map.of("ARRIVAL_REQUESTED","ARRIVAL_WAITING","PARCEL_RECEIVED","PARCEL_RECEIVED");
 private final InboxStore store;
 public InboxEffect(InboxStore store) { this.store=store; }
 @Override public boolean handles(String eventType) { return KINDS.containsKey(eventType); }
 @Override public boolean idempotent() { return true; }
 @Override public OutboxModels.Outcome apply(UUID tenant,OutboxModels.LeasedEvent event) {
  String kind=KINDS.get(event.type());
  if(kind==null) return OutboxModels.Outcome.SKIPPED;
  if(event.unitId()==null) throw new OutboxModels.PermanentFailure("Evento sem unidade válida no payload.");
  var resource="ARRIVAL_WAITING".equals(kind)?store.arrival(tenant,event.aggregateId()):store.parcel(tenant,event.aggregateId());
  if(resource==null) throw new OutboxModels.PermanentFailure("Registro do evento não encontrado no condomínio.");
  if(!event.unitId().equals(resource.unitId())) throw new OutboxModels.PermanentFailure("Unidade do evento não confere com o registro.");
  if(!resource.open()) return OutboxModels.Outcome.SKIPPED;
  var delivery=store.deliver(tenant,event.unitId(),event.id(),kind,event.aggregateId());
  return delivery.eligible()==0?OutboxModels.Outcome.SKIPPED:OutboxModels.Outcome.DELIVERED;
 }
}
