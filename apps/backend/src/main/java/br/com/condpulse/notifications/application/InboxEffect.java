package br.com.condpulse.notifications.application;

import java.util.Map;
import java.util.UUID;
import br.com.condpulse.notifications.infrastructure.InboxStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Caixa interna (parte do CP-035): chegada aguardando resposta e encomenda recebida viram aviso para quem pode agir na
 * unidade; a oferta da fila de espera (CP-040), aviso só para quem entrou na fila; andamento ou resposta da equipe num
 * pedido (CP-033) e decisão de mudança (CP-042), aviso para morador e responsável. Registro já encerrado quando o worker chega (chegada respondida ou vencida, encomenda retirada ou anulada)
 * ou unidade sem destinatário apto: tarefa SKIPPED, sem aviso. "Disponível na caixa" não é leitura: só a abertura
 * autenticada pelo destinatário marca leitura. Idempotente pela chave tarefa/destinatário/canal.
 */
@Component @Profile("worker")
public class InboxEffect implements OutboxEffect {
 static final Map<String,String> KINDS=Map.of("ARRIVAL_REQUESTED","ARRIVAL_WAITING","PARCEL_RECEIVED","PARCEL_RECEIVED",
  "WAITLIST_OFFERED","WAITLIST_OFFERED","REQUEST_UPDATED","REQUEST_UPDATED","REQUEST_MESSAGE_ADDED","REQUEST_UPDATED",
  "MOVE_APPROVED","MOVE_DECIDED","MOVE_REJECTED","MOVE_DECIDED");
 private final InboxStore store;
 public InboxEffect(InboxStore store) { this.store=store; }
 @Override public boolean handles(String eventType) { return KINDS.containsKey(eventType); }
 @Override public boolean idempotent() { return true; }
 @Override public OutboxModels.Outcome apply(UUID tenant,OutboxModels.LeasedEvent event) {
  String kind=KINDS.get(event.type());
  if(kind==null) return OutboxModels.Outcome.SKIPPED;
  if(event.unitId()==null) throw new OutboxModels.PermanentFailure("Evento sem unidade válida no payload.");
  if("WAITLIST_OFFERED".equals(kind)) {
   // Só quem entrou na fila pode aceitar: o aviso vai para o autor da retenção, enquanto ela vale.
   var offer=store.offer(tenant,event.id());
   if(offer==null) throw new OutboxModels.PermanentFailure("Retenção da oferta não encontrada no condomínio.");
   if(!event.unitId().equals(offer.unitId())) throw new OutboxModels.PermanentFailure("Unidade do evento não confere com a retenção.");
   if(!offer.open()) return OutboxModels.Outcome.SKIPPED;
   var delivery=store.deliver(tenant,event.unitId(),event.id(),kind,event.aggregateId(),offer.author());
   return delivery.eligible()==0?OutboxModels.Outcome.SKIPPED:OutboxModels.Outcome.DELIVERED;
  }
  // Resposta da própria unidade não é notícia para ela; só a mensagem visível da equipe.
  if("REQUEST_MESSAGE_ADDED".equals(event.type()) && !store.staffReply(tenant,event.id())) return OutboxModels.Outcome.SKIPPED;
  var resource=switch(kind) {
   case "ARRIVAL_WAITING"->store.arrival(tenant,event.aggregateId());
   case "REQUEST_UPDATED"->store.request(tenant,event.aggregateId());
   case "MOVE_DECIDED"->store.move(tenant,event.aggregateId());
   default->store.parcel(tenant,event.aggregateId());
  };
  if(resource==null) throw new OutboxModels.PermanentFailure("Registro do evento não encontrado no condomínio.");
  if(!event.unitId().equals(resource.unitId())) throw new OutboxModels.PermanentFailure("Unidade do evento não confere com o registro.");
  if(!resource.open()) return OutboxModels.Outcome.SKIPPED;
  var delivery=store.deliver(tenant,event.unitId(),event.id(),kind,event.aggregateId());
  return delivery.eligible()==0?OutboxModels.Outcome.SKIPPED:OutboxModels.Outcome.DELIVERED;
 }
}
