package br.com.condpulse.accesscredentials.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.accessintegration.application.AccessAdapter;
import br.com.condpulse.accessintegration.application.AccessModels;
import br.com.condpulse.accesscredentials.infrastructure.CredentialSyncStore;
import br.com.condpulse.accesscredentials.infrastructure.DeviceEventStore;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.dao.DataAccessException;

/**
 * Busca os eventos de cada conector ativo pelo adaptador (CP-047) e grava no condomínio dono do conector — o condomínio
 * nunca vem do evento. Janela limitada (até 24 h, contrato v1) a partir do último recebimento gravado, com sobreposição
 * de 5 minutos: a deduplicação por eventId absorve a repetição. Passada que para no limite de páginas guarda a
 * continuação e a janela; falha de gravação não avança a posição. Equipamento desconhecido vai para quarentena; campo
 * fora do formato também. Confirmação de credencial por evento (CREDENTIAL_APPLIED/REVOKED com versão) só vale se for
 * a versão e a ação desejadas atuais (guarda da V026); confirmação antiga é ignorada.
 */
public class DeviceEventIngestor {
 private static final System.Logger LOG=System.getLogger(DeviceEventIngestor.class.getName());
 static final Duration OVERLAP=Duration.ofMinutes(5), FIRST_WINDOW=Duration.ofHours(1);
 static final int PAGE=100, MAX_PAGES=50;
 public record Pass(int stored,int duplicates,int quarantined,int confirmations,int skippedConnectors) {}

 private final WorkerTransactions transactions;
 private final CredentialSyncStore syncs;
 private final DeviceEventStore events;
 private final CredentialReconciler.Adapters adapters;
 private final int pageSize,maxPages;
 public DeviceEventIngestor(WorkerTransactions transactions,CredentialSyncStore syncs,DeviceEventStore events,CredentialReconciler.Adapters adapters) {
  this(transactions,syncs,events,adapters,PAGE,MAX_PAGES);
 }
 /** Página e limite de páginas por passada configuráveis para os ensaios. */
 public DeviceEventIngestor(WorkerTransactions transactions,CredentialSyncStore syncs,DeviceEventStore events,CredentialReconciler.Adapters adapters,
     int pageSize,int maxPages) {
  if(pageSize<1 || pageSize>100 || maxPages<1) throw new IllegalArgumentException("Página de 1 a 100 eventos e ao menos uma página.");
  this.transactions=transactions; this.syncs=syncs; this.events=events; this.adapters=adapters; this.pageSize=pageSize; this.maxPages=maxPages;
 }

 public Pass runOnce() {
  int stored=0,duplicates=0,quarantined=0,confirmations=0,skipped=0;
  for(UUID tenant:transactions.catalog(syncs::activeTenants)) {
   for(var connector:transactions.inTenant(tenant,()->syncs.connectors(tenant))) {
    var adapter=adapters.forConnector(tenant,connector.id(),connector.provider());
    if(adapter.isEmpty()) { skipped++; continue; }
    var result=ingest(tenant,connector.id(),adapter.get());
    stored+=result.stored(); duplicates+=result.duplicates(); quarantined+=result.quarantined(); confirmations+=result.confirmations();
   }
  }
  return new Pass(stored,duplicates,quarantined,confirmations,skipped);
 }

 Pass ingest(UUID tenant,UUID connector,AccessAdapter adapter) {
  Instant now=transactions.inTenant(tenant,events::now);
  var saved=transactions.inTenant(tenant,()->events.position(tenant,connector));
  Instant from,until;
  String cursor;
  Instant floor=now.minus(AccessModels.EventQuery.MAX_WINDOW).plusSeconds(60);
  if(saved.continuation()!=null && !saved.windowFrom().isBefore(floor)) {
   // A passada anterior parou no limite de páginas: continua da mesma página, na mesma janela.
   from=saved.windowFrom(); until=saved.windowUntil(); cursor=saved.continuation();
  } else {
   from=saved.lastReceived()==null?now.minus(FIRST_WINDOW):saved.lastReceived().minus(OVERLAP);
   if(from.isBefore(floor)) from=floor;
   until=now.plusSeconds(1); cursor=null;
  }
  int stored=0,duplicates=0,quarantined=0,confirmations=0;
  Instant newest=null;
  boolean failed=false;
  for(int page=0;page<maxPages && !failed;page++) {
   AccessModels.EventPage result;
   try { result=adapter.getEvents(new AccessModels.EventQuery(connector.toString(),cursor,from,until,pageSize)); }
   catch(RuntimeException failure) {
    LOG.log(System.Logger.Level.WARNING,"Eventos do conector adiados: {0}",failure.getClass().getSimpleName());
    failed=true;
    break;
   }
   for(var event:result.events()) {
    if(event.eventId().length()>200) { LOG.log(System.Logger.Level.WARNING,"Evento com identificador fora do limite descartado."); continue; }
    var outcome=store(tenant,connector,event);
    if(outcome==Stored.FAILED) { failed=true; break; }
    switch(outcome) {
     case STORED -> stored++;
     case QUARANTINED -> quarantined++;
     case DUPLICATE -> duplicates++;
     case CONFIRMED -> { stored++; confirmations++; }
     default -> {}
    }
    if(newest==null || event.receivedAt().isAfter(newest)) newest=event.receivedAt();
   }
   if(failed) break;
   cursor=result.nextCursor();
   if(cursor==null) break;
  }
  // Falha ao gravar ou ao buscar: posição e continuação ficam como estavam; a próxima passada repete (sem duplicar).
  if(!failed) {
   Instant position=newest; String next=cursor; Instant windowFrom=next==null?null:from, windowUntil=next==null?null:until;
   transactions.inTenant(tenant,()->{ events.save(tenant,connector,position,next,windowFrom,windowUntil); return null; });
  }
  return new Pass(stored,duplicates,quarantined,confirmations,0);
 }

 enum Stored { STORED, QUARANTINED, DUPLICATE, CONFIRMED, FAILED }
 private Stored store(UUID tenant,UUID connector,AccessModels.Event event) {
  record Mapped(UUID device,UUID credential,String reason,boolean fresh) {}
  Mapped mapped;
  try {
   mapped=transactions.inTenant(tenant,()->{
    UUID device=events.device(tenant,connector,event.deviceExternalId());
    UUID credential=events.credential(tenant,connector,event.credentialExternalId());
    String evidence=event.evidence();
    String reason=device==null?"UNKNOWN_DEVICE"
     :evidence!=null && !evidence.matches("[A-Z0-9_]{1,60}")?"INVALID_EVIDENCE"
     :event.appliedVersion()!=null && event.appliedVersion()<1?"INVALID_VERSION":null;
    boolean fresh=events.insert(tenant,connector,event.eventId(),device,reason==null?credential:null,event.kind().name(),
     event.direction()==null?null:event.direction().name(),reason==null?evidence:null,reason==null?event.appliedVersion():null,
     event.occurredAt(),event.receivedAt(),reason);
    return new Mapped(device,credential,reason,fresh);
   });
  } catch(DataAccessException failure) {
   // Falha de gravação não é repetição: a passada para aqui sem avançar a posição.
   LOG.log(System.Logger.Level.WARNING,"Evento não gravado: {0}",failure.getClass().getSimpleName());
   return Stored.FAILED;
  }
  if(!mapped.fresh()) return Stored.DUPLICATE;
  if(mapped.reason()!=null) return Stored.QUARANTINED;
  boolean confirmation=(event.kind()==AccessModels.EventKind.CREDENTIAL_APPLIED || event.kind()==AccessModels.EventKind.CREDENTIAL_REVOKED)
   && event.appliedVersion()!=null && mapped.credential()!=null;
  if(!confirmation) return Stored.STORED;
  String action=event.kind()==AccessModels.EventKind.CREDENTIAL_REVOKED?"REVOKE":"APPLY";
  try {
   transactions.inTenant(tenant,()->{
    syncs.record(tenant,mapped.credential(),mapped.device(),event.appliedVersion(),action,"APPLIED","DEVICE_EVENT",CredentialReconciler.REJECTED_DELAY);
    return null;
   });
   return Stored.CONFIRMED;
  } catch(DataAccessException failure) {
   // Versão ou ação que não é mais a desejada: o evento fica no histórico, mas não confirma nada.
   if(!"23514".equals(SqlStates.of(failure))) LOG.log(System.Logger.Level.WARNING,"Confirmação por evento adiada: {0}",failure.getClass().getSimpleName());
   return Stored.STORED;
  }
 }
}
