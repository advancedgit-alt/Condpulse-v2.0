package br.com.condpulse.gateway.capability;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import br.com.condpulse.gateway.config.GatewayConfig.Device;
import br.com.condpulse.gateway.config.GatewayConfig.Phase;
import br.com.condpulse.gateway.config.GatewayConfig.Profile;
import br.com.condpulse.gateway.spi.Spi.Brand;
import br.com.condpulse.gateway.spi.Spi.CapabilityName;
import br.com.condpulse.gateway.spi.Spi.ConnectionMode;
import br.com.condpulse.gateway.spi.Spi.DecisionMode;
import br.com.condpulse.gateway.spi.Spi.DeviceIdentity;
import br.com.condpulse.gateway.spi.Spi.Manifest;
import br.com.condpulse.gateway.spi.Spi.OperationState;

/**
 * Registro de capacidades por combinação (marca, SKU, firmware, API, modo de decisão, modo de conexão). Cada
 * capacidade separa documentada (manifesto do adaptador), observada (bancada) e aprovada (MASTER). Desconhecido
 * (null) nunca vale como true. Aprovação de uma combinação não se estende a outro sufixo, firmware ou modo: a
 * comparação é exata.
 */
public final class CapabilityRegistry {
 public record Key(Brand brand,String sku,String firmware,String apiVersion,DecisionMode decisionMode,ConnectionMode connectionMode) {
  public static Key of(Device d) { return new Key(d.brand(),d.sku(),d.firmware(),d.apiVersion(),d.decisionMode(),d.connectionMode()); }
  public static Key of(Profile p) { return new Key(p.brand(),p.sku(),p.firmware(),p.apiVersion(),p.decisionMode(),p.connectionMode()); }
 }
 public record Evidence(Boolean documented,Boolean observed,Boolean approved) {}
 public record Decision(boolean allowed,String reasonCode) {}

 private final Map<Key,Profile> profiles=new java.util.HashMap<>();

 public CapabilityRegistry(List<Profile> configured) {
  for(Profile p:configured) if(profiles.put(Key.of(p),p)!=null) throw new IllegalArgumentException("Perfil repetido: "+Key.of(p));
 }

 public Optional<Profile> profile(Device device) { return Optional.ofNullable(profiles.get(Key.of(device))); }

 public Map<CapabilityName,Evidence> evidence(Device device,Manifest manifest) {
  var profile=profile(device);
  var out=new EnumMap<CapabilityName,Evidence>(CapabilityName.class);
  for(CapabilityName c:CapabilityName.values())
   out.put(c,new Evidence(manifest.documented().get(c),profile.map(p->p.observed().get(c)).orElse(null),profile.map(p->p.approved().get(c)).orElse(null)));
  return out;
 }

 /**
  * Uma capacidade pode ser usada? Em BANCADA basta a documentação oficial dizer true (o ensaio é justamente para
  * observar); em PRODUCAO é preciso aprovação explícita do MASTER sobre observação própria.
  */
 public Decision allows(Device device,Manifest manifest,CapabilityName capability) {
  if(profile(device).isEmpty()) return new Decision(false,"PROFILE_NOT_REGISTERED");
  var e=evidence(device,manifest).get(capability);
  if(device.phase()==Phase.PRODUCAO) {
   if(!Boolean.TRUE.equals(e.approved()) || !Boolean.TRUE.equals(e.observed())) return new Decision(false,"CAPABILITY_NOT_APPROVED");
   return new Decision(true,null);
  }
  if(!Boolean.TRUE.equals(e.documented()) && !Boolean.TRUE.equals(e.observed())) return new Decision(false,"CAPABILITY_MISSING");
  return new Decision(true,null);
 }

 /**
  * Confronta a identificação real com o perfil configurado (AC-019: "reconexão confronta identificação real"). Sem
  * identificação documentada o resultado é UNVERIFIED, nunca OK.
  */
 public enum IdentityCheck { OK, MISMATCH, UNVERIFIED }
 public IdentityCheck checkIdentity(Device device,DeviceIdentity identity) {
  if(identity==null || identity.state()!=OperationState.CONFIRMED) return IdentityCheck.UNVERIFIED;
  if(identity.firmware()!=null && !identity.firmware().equals(device.firmware())) return IdentityCheck.MISMATCH;
  if(identity.sku()!=null && !identity.sku().equals(device.sku())) return IdentityCheck.MISMATCH;
  if(identity.firmware()==null && identity.sku()==null) return IdentityCheck.UNVERIFIED;
  return IdentityCheck.OK;
 }
}
