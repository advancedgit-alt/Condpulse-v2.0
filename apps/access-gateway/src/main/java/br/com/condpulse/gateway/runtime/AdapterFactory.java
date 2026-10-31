package br.com.condpulse.gateway.runtime;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.SecretProvider;
import br.com.condpulse.gateway.spi.AccessProviderAdapter;
import br.com.condpulse.gateway.spi.BaseAdapter.DeviceContext;
import br.com.condpulse.gateway.spi.Spi.ConnectionMode;

/** Cria o adaptador de cada equipamento a partir da marca e do modo de conexão configurados. */
public final class AdapterFactory {
 private static final System.Logger LOG=System.getLogger(AdapterFactory.class.getName());
 private AdapterFactory() {}

 private static AccessProviderAdapter unavailable(GatewayConfig.Device device) {
  LOG.log(System.Logger.Level.WARNING,"Sem adaptador para a marca {0}: equipamento {1} sem adaptador.",device.brand(),device.deviceId());
  return null;
 }

 public static Optional<AccessProviderAdapter> create(GatewayConfig config,GatewayConfig.Device device,SecretProvider secrets,Clock clock) {
  SecretProvider.DeviceCredential credential=null;
  if(device.connectionMode()==ConnectionMode.LOCAL) {
   credential=secrets.deviceCredential(device.credentialRef()).orElse(null);
   if(credential==null) {
    LOG.log(System.Logger.Level.WARNING,"Credencial ausente no cofre para o equipamento {0}: adaptador não criado.",device.deviceId());
    return Optional.empty();
   }
  }
  var context=new DeviceContext(device,config.connectorId(),credential,Duration.ofMillis(config.limits().deviceTimeoutMillis()),
   Duration.ofMillis(config.limits().pushPickupTimeoutMillis()),clock,config.zone());
  return Optional.ofNullable(switch(device.brand()) {
   case CONTROL_ID -> new br.com.condpulse.gateway.adapters.controlid.ControlIdAdapter(context);
   // Adaptadores entram nos PRs empilhados seguintes (Hikvision, Dahua e Intelbras).
   case HIKVISION,DAHUA,INTELBRAS -> unavailable(device);
  });
 }
}
