package br.com.condpulse.platform.application;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.infrastructure.DemoTenantStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Condomínio demo (CPX-046, V055, ADR-0012). A central (papel de plataforma, platform.demo.manage, AAL2) cria ou reseta o
 * demo isolado com dados sintéticos. Toda a gravação é da função do banco com dono cp_demo, na transação da plataforma:
 * trilha da plataforma e Log de Segurança do demo na mesma transação. Condomínio real nunca é resetado (CPD01) e nenhuma
 * conta real recebe papel: o vínculo de pessoas reais segue pelos caminhos explícitos da central.
 */
@Service @Profile("api")
public class DemoTenantService {
 /** Palavra exigida na confirmação da tela. */
 public static final String CONFIRMATION="DEMO";
 private static final java.util.regex.Pattern KEY=java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{16,128}");
 private final PlatformTransactionExecutor transactions;
 private final DemoTenantStore store;
 public DemoTenantService(PlatformTransactionExecutor transactions,DemoTenantStore store) {
  this.transactions=transactions; this.store=store;
 }

 public DemoTenantModels.DemoState state(UUID actor,AssuranceLevel assurance) {
  try {
   return transactions.execute(actor,assurance,context->{
    context.require(PlatformCapability.MANAGE_DEMO);
    return new DemoTenantModels.DemoState(store.current(),store.retired(10));
   });
  } catch(DataAccessException error) { throw translate(error); }
 }

 public DemoTenantModels.DemoProvisioned provision(UUID actor,AssuranceLevel assurance,String idempotencyKey,
   DemoTenantModels.ProvisionDemo request) {
  if(idempotencyKey==null || !KEY.matcher(idempotencyKey).matches())
   throw new BusinessProblem("IDEMPOTENCY_KEY_INVALID",400,"Informe uma chave de operação válida de 16 a 128 caracteres.");
  if(request==null || !CONFIRMATION.equals(request.confirmation()))
   throw new BusinessProblem("DEMO_CONFIRMATION_REQUIRED",400,"Digite DEMO para confirmar a operação no condomínio demo.");
  String reason=DisplayText.normalize(request.reason(),500);
  if(reason==null || reason.length()<3)
   throw new BusinessProblem("AUDIT_REASON_REQUIRED",400,"Informe o motivo (3 a 500 caracteres); ele fica na auditoria da plataforma.");
  try {
   return transactions.execute(actor,assurance,context->{
    context.require(PlatformCapability.MANAGE_DEMO);
    var result=store.provision(request.expectedTenantId(),reason,idempotencyKey);
    var generation=store.generation(result.generationId());
    if(generation==null) throw new IllegalStateException("Geração do demo não visível na mesma transação.");
    return new DemoTenantModels.DemoProvisioned(generation,result.retiredTenantId(),result.applied());
   });
  } catch(DataAccessException error) { throw translate(error); }
 }

 /** Traduz depois do rollback do executor, sem expor mensagem do banco. */
 static BusinessProblem translate(DataAccessException error) {
  String state=SqlStates.of(error);
  if("CPD01".equals(state))
   return new BusinessProblem("DEMO_RESET_REAL_TENANT",422,"Somente o condomínio demo pode ser resetado. Condomínios reais nunca são resetados.");
  if("CPD02".equals(state))
   return new BusinessProblem("DEMO_CHANGED",409,"O condomínio demo mudou desde a última leitura. Atualize a tela e confira antes de repetir.");
  if("CPD03".equals(state))
   return new BusinessProblem("DEMO_KEY_REUSED",409,"Esta operação já foi enviada com outros dados. Atualize a tela antes de repetir.");
  if("22023".equals(state))
   return new BusinessProblem("AUDIT_REASON_REQUIRED",400,"Informe o motivo (3 a 500 caracteres); ele fica na auditoria da plataforma.");
  if("42501".equals(state)) return new BusinessProblem("PLATFORM_ACCESS_DENIED",403,"Esta área é exclusiva da administração da plataforma.");
  return new BusinessProblem("PLATFORM_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
 }
}
