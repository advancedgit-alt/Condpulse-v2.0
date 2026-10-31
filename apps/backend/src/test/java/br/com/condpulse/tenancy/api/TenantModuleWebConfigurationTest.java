package br.com.condpulse.tenancy.api;

import java.util.EnumSet;
import java.util.Map;
import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import br.com.condpulse.tenancy.application.TenantModuleExempt;
import br.com.condpulse.tenancy.application.TenantModuleFlags;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.bind.annotation.RequestMapping;
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


 /**
  * Controladores lidos direto das classes compiladas: a varredura de componentes do Spring avalia @Profile e
  * @ConditionalOnProperty e, sem perfil/flags, não devolveria nenhum.
  */
 static java.util.List<Class<?>> controllers() throws Exception {
  var resolver=new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
  var readers=new org.springframework.core.type.classreading.CachingMetadataReaderFactory(resolver);
  var found=new java.util.ArrayList<Class<?>>();
  for(var resource:resolver.getResources("classpath*:br/com/condpulse/**/*.class")) {
   var metadata=readers.getMetadataReader(resource).getAnnotationMetadata();
   if(metadata.hasAnnotation(RestController.class.getName()))
    found.add(Class.forName(metadata.getClassName(),false,TenantModuleWebConfigurationTest.class.getClassLoader()));
  }
  assertTrue(found.size()>=30,"Controladores encontrados: "+found.size());
  return found;
 }

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
  int checked=0;
  for(Class<?> type:controllers()) {
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

 /**
  * Raiz da conferência (achado do Codex no #148): TODA rota de condomínio (/v1/tenants/**) declara o módulo liberável
  * ou a isenção explícita com motivo. Rota nova sem nenhuma das duas falha aqui, em vez de escapar da central.
  */
 @Test void everyTenantRouteDeclaresItsModuleOrAnExplicitExemption() throws Exception {
  var missing=new java.util.ArrayList<String>();
  int routes=0;
  for(Class<?> type:controllers()) {
   for(var method:type.getDeclaredMethods()) {
    var mapping=AnnotatedElementUtils.findMergedAnnotation(method,RequestMapping.class);
    if(mapping==null) continue;
    for(String path:mapping.path()) {
     if(!path.startsWith("/v1/tenants/")) continue;
     routes++;
     var module=method.isAnnotationPresent(RequiresTenantModule.class)?method.getAnnotation(RequiresTenantModule.class)
      :method.isAnnotationPresent(TenantModuleExempt.class)?null:type.getAnnotation(RequiresTenantModule.class);
     var exempt=method.isAnnotationPresent(TenantModuleExempt.class)?method.getAnnotation(TenantModuleExempt.class)
      :method.isAnnotationPresent(RequiresTenantModule.class)?null:type.getAnnotation(TenantModuleExempt.class);
     if((module==null)==(exempt==null)) missing.add(type.getSimpleName()+"."+method.getName()+" "+path);
     else if(module!=null) assertTrue(module.value().length>0,path);
     else assertFalse(exempt.value().isBlank(),"Isenção sem motivo: "+path);
    }
   }
  }
  assertTrue(missing.isEmpty(),"Rotas de condomínio sem módulo nem isenção: "+missing);
  assertTrue(routes>=100,"Varredura encontrou as rotas de condomínio: "+routes);
 }
}
