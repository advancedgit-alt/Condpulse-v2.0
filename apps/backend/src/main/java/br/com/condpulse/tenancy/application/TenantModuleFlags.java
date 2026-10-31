package br.com.condpulse.tenancy.application;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Flags globais dos módulos, lidas uma vez na partida (como as rotas condicionais). Valor diferente de "true" é desligado. */
public final class TenantModuleFlags {
 private final Set<TenantModule> enabled;
 public TenantModuleFlags(Set<TenantModule> enabled) {
  this.enabled=Collections.unmodifiableSet(enabled.isEmpty()?EnumSet.noneOf(TenantModule.class):EnumSet.copyOf(enabled));
 }
 public static TenantModuleFlags from(Environment environment) {
  var on=EnumSet.noneOf(TenantModule.class);
  for(TenantModule module:TenantModule.values())
   if("true".equalsIgnoreCase(environment.getProperty(module.flagProperty(),"false").strip())) on.add(module);
  return new TenantModuleFlags(on);
 }
 public boolean globallyEnabled(TenantModule module) { return enabled.contains(module); }
 public Set<TenantModule> enabled() { return enabled; }
}
