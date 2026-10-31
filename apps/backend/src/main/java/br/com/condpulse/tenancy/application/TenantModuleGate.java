package br.com.condpulse.tenancy.application;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Conferência do módulo por condomínio dentro da transação do executor. O interceptador das rotas grava na requisição
 * os módulos aceitos pela rota (preferindo os de flag global ligada); o executor chama {@link #check} depois de conta ativa,
 * condomínio ativo e vínculo (ou concessão técnica) vigentes, com app.tenant_id já publicado. Assim quem não tem acesso
 * ao condomínio recebe 404 antes de saber se o módulo está desligado.
 * Fora de uma requisição marcada (worker, jobs, rotas sem módulo) nada é exigido.
 */
public final class TenantModuleGate {
 /** Atributo da requisição com o conjunto (possivelmente vazio) de módulos aceitos para a rota. */
 public static final String REQUIRED_ATTRIBUTE=TenantModuleGate.class.getName()+".required";
 private TenantModuleGate() {}

 static Set<TenantModule> required() {
  RequestAttributes attributes=RequestContextHolder.getRequestAttributes();
  if(attributes==null) return null;
  Object value=attributes.getAttribute(REQUIRED_ATTRIBUTE,RequestAttributes.SCOPE_REQUEST);
  if(value==null) return null;
  var modules=EnumSet.noneOf(TenantModule.class);
  // Valor de outro tipo é defeito: falha fechada com conjunto vazio.
  if(!(value instanceof Set<?> set)) return modules;
  for(Object item:set) {
   if(!(item instanceof TenantModule module)) return EnumSet.noneOf(TenantModule.class);
   modules.add(module);
  }
  return modules;
 }

 /** Exige que algum dos módulos da rota esteja liberado no condomínio; chamar com app.tenant_id já publicado. */
 public static void check(JdbcTemplate jdbc,UUID tenantId) {
  Set<TenantModule> modules=required();
  if(modules==null) return;
  if(modules.isEmpty()) throw disabled();
  for(TenantModule module:modules) {
   Boolean enabled=jdbc.queryForObject("select platform.tenant_module_enabled(?,?)",Boolean.class,tenantId,module.key());
   if(Boolean.TRUE.equals(enabled)) return;
  }
  throw disabled();
 }
 public static BusinessProblem disabled() {
  return new BusinessProblem("MODULE_DISABLED",403,"Este recurso não está liberado para este condomínio.");
 }
}
