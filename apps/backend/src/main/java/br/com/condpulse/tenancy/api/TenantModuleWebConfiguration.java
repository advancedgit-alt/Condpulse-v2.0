package br.com.condpulse.tenancy.api;

import java.util.EnumSet;
import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import br.com.condpulse.tenancy.application.TenantModuleExempt;
import br.com.condpulse.tenancy.application.TenantModuleFlags;
import br.com.condpulse.tenancy.application.TenantModuleGate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Módulos por condomínio (V044): marca na requisição os módulos exigidos pela rota (anotação {@link RequiresTenantModule}
 * no método ou na classe), preferindo os de flag global ligada. A conferência no banco acontece no executor do condomínio,
 * na mesma transação da operação. Rotas sem anotação não exigem módulo.
 */
@Configuration @Profile("api")
public class TenantModuleWebConfiguration implements WebMvcConfigurer {
 private final TenantModuleFlags flags;
 public TenantModuleWebConfiguration(Environment environment) { flags=TenantModuleFlags.from(environment); }

 @Bean TenantModuleFlags tenantModuleFlags() { return flags; }

 @Override public void addInterceptors(InterceptorRegistry registry) {
  registry.addInterceptor(new ModuleInterceptor(flags));
 }

 static final class ModuleInterceptor implements HandlerInterceptor {
  private final TenantModuleFlags flags;
  ModuleInterceptor(TenantModuleFlags flags) { this.flags=flags; }
  @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
   var required=requiredFor(handler,flags);
   if(required!=null) request.setAttribute(TenantModuleGate.REQUIRED_ATTRIBUTE,required);
   return true;
  }
 }

 /** Módulos aceitos pela rota (preferindo os de flag global ligada); null quando a rota não pertence a módulo liberável. */
 static EnumSet<TenantModule> requiredFor(Object handler,TenantModuleFlags flags) {
  if(!(handler instanceof HandlerMethod method)) return null;
  RequiresTenantModule annotation=method.getMethodAnnotation(RequiresTenantModule.class);
  // Isenção no método vence o módulo declarado na classe.
  if(annotation==null && method.hasMethodAnnotation(TenantModuleExempt.class)) return null;
  if(annotation==null) annotation=method.getBeanType().getAnnotation(RequiresTenantModule.class);
  if(annotation==null) return null;
  var declared=EnumSet.noneOf(TenantModule.class);
  for(TenantModule module:annotation.value()) declared.add(module);
  var required=EnumSet.noneOf(TenantModule.class);
  for(TenantModule module:declared) if(flags.globallyEnabled(module)) required.add(module);
  // Rota de mais de um módulo: só contam os de flag ligada. Se nenhum está ligado, a rota existe por outra flag
  // (ex.: decisões de reserva na gestão) e vale só a decisão do condomínio: nada muda até a central alterar.
  return required.isEmpty()?declared:required;
 }
}
