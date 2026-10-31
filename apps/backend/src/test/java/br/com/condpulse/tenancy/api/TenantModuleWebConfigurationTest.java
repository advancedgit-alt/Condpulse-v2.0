package br.com.condpulse.tenancy.api;

import java.util.EnumSet;
import java.util.Map;
import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import br.com.condpulse.tenancy.application.TenantModuleFlags;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import static org.junit.jupiter.api.Assertions.*;

class TenantModuleWebConfigurationTest {
 @RequiresTenantModule(TenantModule.PARCELS)
 static class ParcelRoutes {
  public void list() {}
  @RequiresTenantModule({TenantModule.PARCELS,TenantModule.VISITS}) public void units() {}
 }
 static class FreeRoutes { public void me() {} }

 private static HandlerMethod handler(Object bean,String name) throws Exception {
  return new HandlerMethod(bean,bean.getClass().getMethod(name));
 }

 @Test void flagsAreReadAsStrictTrue() {
  var environment=new StandardEnvironment();
  environment.getPropertySources().addFirst(new MapPropertySource("t",Map.of("condpulse.parcels.enabled","true",
   "condpulse.visits.enabled"," TRUE ","condpulse.finance.enabled","yes")));
  var flags=TenantModuleFlags.from(environment);
  assertEquals(EnumSet.of(TenantModule.PARCELS,TenantModule.VISITS),flags.enabled());
 }

 @Test void routeRequirementComesFromMethodOrClassFilteredByGlobalFlags() throws Exception {
  var onlyVisits=new TenantModuleFlags(EnumSet.of(TenantModule.VISITS));
  var routes=new ParcelRoutes();
  assertEquals(EnumSet.of(TenantModule.PARCELS),TenantModuleWebConfiguration.requiredFor(handler(routes,"list"),onlyVisits),
   "Sem nenhum módulo da rota com flag ligada vale a decisão do condomínio (a rota já existe por outra flag)");
  assertEquals(EnumSet.of(TenantModule.VISITS),TenantModuleWebConfiguration.requiredFor(handler(routes,"units"),onlyVisits));
  var all=new TenantModuleFlags(EnumSet.allOf(TenantModule.class));
  assertEquals(EnumSet.of(TenantModule.PARCELS),TenantModuleWebConfiguration.requiredFor(handler(routes,"list"),all));
  assertNull(TenantModuleWebConfiguration.requiredFor(handler(new FreeRoutes(),"me"),all));
  assertNull(TenantModuleWebConfiguration.requiredFor("não é método",all));
 }

 /** Toda rota atrás da flag de um módulo liberável precisa declarar o módulo (senão a central não a desligaria). */
 @Test void everyControllerBehindAModuleFlagDeclaresTheModule() throws Exception {
  var scanner=new ClassPathScanningCandidateComponentProvider(false);
  scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
  int checked=0;
  for(var candidate:scanner.findCandidateComponents("br.com.condpulse")) {
   Class<?> type=Class.forName(candidate.getBeanClassName());
   var flags=new StringBuilder();
   var property=type.getAnnotation(ConditionalOnProperty.class);
   if(property!=null) { for(String name:property.name()) flags.append(name).append(' '); for(String name:property.value()) flags.append(name).append(' '); }
   var expression=type.getAnnotation(ConditionalOnExpression.class);
   if(expression!=null) flags.append(expression.value());
   var declared=type.getAnnotation(RequiresTenantModule.class);
   for(TenantModule module:TenantModule.values()) {
    if(!flags.toString().contains(module.flagProperty())) continue;
    // O BFF do link público de visita confere o módulo na própria consulta (PermitLinkPublicStore).
    if(type.getSimpleName().equals("BffPermitLinkController")) continue;
    assertNotNull(declared,type.getName()+" exige @RequiresTenantModule");
    assertTrue(java.util.Arrays.asList(declared.value()).contains(module),
     type.getName()+" deve declarar "+module);
    checked++;
   }
  }
  assertTrue(checked>=20,"Varredura encontrou os controladores dos módulos: "+checked);
 }
}
