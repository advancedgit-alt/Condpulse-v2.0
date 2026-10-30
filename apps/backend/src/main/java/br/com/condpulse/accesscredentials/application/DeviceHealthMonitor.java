package br.com.condpulse.accesscredentials.application;

import java.time.Duration;
import java.util.UUID;
import br.com.condpulse.accessintegration.application.AccessAdapter;
import br.com.condpulse.accessintegration.application.AccessModels;
import br.com.condpulse.accesscredentials.infrastructure.CredentialSyncStore;
import br.com.condpulse.accesscredentials.infrastructure.DeviceHealthStore;
import br.com.condpulse.notifications.application.WorkerTransactions;
import org.springframework.dao.DataAccessException;

/**
 * Consulta a saúde de cada conector ativo pelo adaptador (CP-048) e grava a última observação no condomínio dono do
 * conector. Nenhuma transação fica aberta durante a conversa com o parceiro. Falha ou resposta de outro conector registra
 * o conector como sem resposta e não toca nos equipamentos: a última observação deles envelhece e a tela passa a mostrar
 * "sem informação recente", nunca "no ar". Equipamento informado que não é deste conector é ignorado. Relógio e atraso
 * fora da faixa aceitável ficam sem valor (null), não corrigidos.
 */
public class DeviceHealthMonitor {
 private static final System.Logger LOG=System.getLogger(DeviceHealthMonitor.class.getName());
 static final long MAX_MILLIS=86_400_000L;
 public record Pass(int connectors,int unreachable,int devices,int unknownDevices,int skippedConnectors) {}

 private final WorkerTransactions transactions;
 private final CredentialSyncStore syncs;
 private final DeviceHealthStore store;
 private final CredentialReconciler.Adapters adapters;
 public DeviceHealthMonitor(WorkerTransactions transactions,CredentialSyncStore syncs,DeviceHealthStore store,CredentialReconciler.Adapters adapters) {
  this.transactions=transactions; this.syncs=syncs; this.store=store; this.adapters=adapters;
 }

 public Pass runOnce() {
  int connectors=0,unreachable=0,devices=0,unknown=0,skipped=0;
  for(UUID tenant:transactions.catalog(syncs::activeTenants)) {
   for(var connector:transactions.inTenant(tenant,()->syncs.connectors(tenant))) {
    var adapter=adapters.forConnector(tenant,connector.id(),connector.provider());
    if(adapter.isEmpty()) { skipped++; continue; }
    try {
     var result=check(tenant,connector.id(),adapter.get());
     connectors++; unreachable+=result.unreachable(); devices+=result.devices(); unknown+=result.unknownDevices();
    } catch(DataAccessException failure) {
     LOG.log(System.Logger.Level.WARNING,"Saúde do conector não gravada: {0}",failure.getClass().getSimpleName());
    }
   }
  }
  return new Pass(connectors,unreachable,devices,unknown,skipped);
 }

 Pass check(UUID tenant,UUID connector,AccessAdapter adapter) {
  AccessModels.Health health=null;
  String failure;
  try {
   health=adapter.health(connector.toString());
   failure=health==null?"INVALID_RESPONSE":!connector.toString().equals(health.connectorId())?"CONNECTOR_MISMATCH":!health.reachable()?"CONNECTOR_UNREACHABLE":null;
  } catch(RuntimeException error) {
   LOG.log(System.Logger.Level.WARNING,"Saúde do conector indisponível: {0}",error.getClass().getSimpleName());
   failure="ADAPTER_ERROR";
  }
  if(failure!=null) {
   String reason=failure;
   transactions.inTenant(tenant,()->{ store.unreachable(tenant,connector,reason); return null; });
   return new Pass(1,1,0,0,0);
  }
  var report=health;
  return transactions.inTenant(tenant,()->{
   Integer pending=report.pendingCommands()>=0 && report.pendingCommands()<=1_000_000?report.pendingCommands():null;
   store.reachable(tenant,connector,pending,millis(report.observedDelay(),false));
   var known=store.devices(tenant,connector);
   int stored=0,unknown=0;
   for(var device:report.devices()) {
    UUID id=device==null?null:known.get(device.deviceId());
    if(id==null) { unknown++; continue; }
    store.device(tenant,id,device.online(),device.lastHeartbeat(),millis(device.clockSkew(),true));
    stored++;
   }
   return new Pass(1,0,stored,unknown,0);
  });
 }

 private static Long millis(Duration value,boolean signed) {
  if(value==null) return null;
  long result;
  try { result=value.toMillis(); } catch(ArithmeticException overflow) { return null; }
  if(Math.abs(result)>MAX_MILLIS || (!signed && result<0)) return null;
  return result;
 }
}
