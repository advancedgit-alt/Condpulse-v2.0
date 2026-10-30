package br.com.condpulse.accesscredentials.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import br.com.condpulse.accessintegration.application.AccessAdapter;
import br.com.condpulse.accessintegration.application.AccessModels;
import br.com.condpulse.accesscredentials.infrastructure.CredentialSyncStore;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.dao.DataAccessException;

/**
 * Leva o estado desejado das credenciais aos equipamentos (CP-046), revogações primeiro. Nenhuma transação fica aberta
 * durante a conversa com o parceiro: lê a pendência, chama o adaptador e grava cada resultado numa transação curta. Não
 * reproduz fila antiga: cada passada compara o estado desejado ATUAL com o confirmado. Resultado incerto ou só recebido é
 * consultado (getApplication) antes de reenviar; resultado de versão que deixou de ser a desejada é descartado pelo banco.
 */
public class CredentialReconciler {
 private static final System.Logger LOG=System.getLogger(CredentialReconciler.class.getName());
 static final long CONFIRM_DELAY=30, REJECTED_DELAY=86_400, MAX_BACKOFF=3_600;

 /** Adaptador por conector; vazio quando não há integração disponível para o provedor (nada é enviado). */
 @FunctionalInterface public interface Adapters { Optional<AccessAdapter> forConnector(UUID tenant,UUID connector,String provider); }
 public static final Adapters NONE=(tenant,connector,provider)->Optional.empty();
 public record Pass(int sent,int confirmed,int stale,int skippedConnectors) {}

 private final WorkerTransactions transactions;
 private final CredentialSyncStore store;
 private final Adapters adapters;
 public CredentialReconciler(WorkerTransactions transactions,CredentialSyncStore store,Adapters adapters) {
  this.transactions=transactions; this.store=store; this.adapters=adapters;
 }

 public Pass runOnce() {
  int sent=0,confirmed=0,stale=0,skipped=0;
  for(UUID tenant:transactions.catalog(store::activeTenants)) {
   for(var connector:transactions.inTenant(tenant,()->store.connectors(tenant))) {
    var adapter=adapters.forConnector(tenant,connector.id(),connector.provider());
    if(adapter.isEmpty()) { skipped++; continue; }
    for(var work:transactions.inTenant(tenant,()->store.due(tenant,connector.id()))) {
     var result=reconcile(tenant,connector.id(),adapter.get(),work);
     sent+=result.sent(); confirmed+=result.confirmed(); stale+=result.stale();
    }
   }
  }
  return new Pass(sent,confirmed,stale,skipped);
 }

 Pass reconcile(UUID tenant,UUID connector,AccessAdapter adapter,CredentialSyncStore.Work work) {
  var action=AccessModels.Action.valueOf(work.action());
  long version=work.desiredVersion();
  Map<String,CredentialSyncStore.Target> byExternal=new HashMap<>();
  work.targets().forEach(t->byExternal.put(t.externalId(),t));
  List<String> query=new ArrayList<>(),send=new ArrayList<>();
  for(var target:work.targets()) {
   boolean sameCommand=target.attemptedVersion()!=null && target.attemptedVersion()==version && work.action().equals(target.attemptedAction());
   // Envio anterior incerto ou só recebido: consultar antes de mandar de novo.
   if(sameCommand && ("UNKNOWN".equals(target.outcome()) || "ACCEPTED".equals(target.outcome()))) query.add(target.externalId());
   else send.add(target.externalId());
  }
  List<AccessModels.Destination> results=new ArrayList<>();
  for(var chunk:chunks(query)) {
   for(var destination:call(()->adapter.getApplication(new AccessModels.ApplicationQuery(connector.toString(),work.externalRef(),version,action,chunk)),chunk)) {
    // A versão não chegou ao equipamento: o comando é idempotente por versão e ação e pode ser repetido.
    if(destination.outcome()==AccessModels.Outcome.UNKNOWN && "VERSION_NOT_PRESENT".equals(destination.reason())) send.add(destination.deviceId());
    else results.add(destination);
   }
  }
  int sent=0;
  String correlation="cpc-"+work.credentialId()+"-"+version+"-"+action.name().toLowerCase(java.util.Locale.ROOT);
  for(var chunk:chunks(send)) {
   results.addAll(call(()->action==AccessModels.Action.REVOKE
    ?adapter.revokeCredential(new AccessModels.RevokeCredential(connector.toString(),correlation,work.externalRef(),version,chunk,"CREDENTIAL_REVOKED"))
    :adapter.applyCredential(new AccessModels.ApplyCredential(connector.toString(),correlation,work.externalRef(),work.externalRef(),version,chunk,
      null,work.validUntil(),null)),chunk));
   sent+=chunk.size();
  }
  int confirmed=0,stale=0;
  for(var destination:results) {
   var target=byExternal.get(destination.deviceId());
   if(target==null) continue;
   // APPLIED de outra versão ou ação (resposta antiga) não confirma: vira recusa desta versão para a consulta seguinte.
   var outcome=destination.outcome();
   if(outcome==AccessModels.Outcome.APPLIED && (destination.appliedVersion()==null || destination.appliedVersion()!=version || destination.appliedAction()!=action))
    outcome=AccessModels.Outcome.UNKNOWN;
   int attempts=target.attemptedVersion()!=null && target.attemptedVersion()==version && work.action().equals(target.attemptedAction())?target.attempts()+1:1;
   long delay=switch(outcome) {
    case APPLIED,REJECTED -> REJECTED_DELAY;
    case ACCEPTED,UNKNOWN -> CONFIRM_DELAY;
    case RATE_LIMITED -> Math.max(1,Optional.ofNullable(destination.retryAfter()).orElse(Duration.ofSeconds(CONFIRM_DELAY)).toSeconds());
    case UNAVAILABLE -> Math.min(MAX_BACKOFF,60L<<Math.min(6,attempts-1));
   };
   try {
    String reason=destination.reason()==null?null:destination.reason().substring(0,Math.min(200,destination.reason().length()));
    var finalOutcome=outcome;
    transactions.inTenant(tenant,()->{ store.record(tenant,work.credentialId(),target.deviceId(),version,work.action(),finalOutcome.name(),reason,delay); return null; });
    if(outcome==AccessModels.Outcome.APPLIED) confirmed++;
   } catch(DataAccessException failure) {
    // O estado desejado mudou durante a conversa (ex.: revogação): resultado obsoleto descartado; a próxima passada envia o atual.
    if("23514".equals(SqlStates.of(failure))) stale++;
    else LOG.log(System.Logger.Level.WARNING,"Resultado de credencial adiado: {0}",failure.getClass().getSimpleName());
   }
  }
  return new Pass(sent,confirmed,stale,0);
 }

 /** Contrato do adaptador: até {@value #MAX_DESTINATIONS} destinos por comando ou consulta. */
 static final int MAX_DESTINATIONS=50;
 static List<List<String>> chunks(List<String> destinations) {
  List<List<String>> result=new ArrayList<>();
  for(int start=0;start<destinations.size();start+=MAX_DESTINATIONS)
   result.add(List.copyOf(destinations.subList(start,Math.min(destinations.size(),start+MAX_DESTINATIONS))));
  return result;
 }

 private interface Call { AccessModels.Application run(); }
 /** Falha do adaptador (rede, parceiro) não derruba a passada: cada destino fica indisponível e volta com espera. */
 private static List<AccessModels.Destination> call(Call call,List<String> destinations) {
  try { return call.run().destinations(); }
  catch(RuntimeException failure) {
   LOG.log(System.Logger.Level.WARNING,"Adaptador de acesso indisponível: {0}",failure.getClass().getSimpleName());
   return destinations.stream().map(d->new AccessModels.Destination(d,AccessModels.Outcome.UNAVAILABLE,null,null,null,"ADAPTER_ERROR",null)).toList();
  }
 }
}
