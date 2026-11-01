package br.com.condpulse.platform.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Condomínio demo (CPX-046, V055): geração ativa, histórico e o pedido de criar/resetar. */
public final class DemoTenantModels {
 private DemoTenantModels() {}
 /** Geração do demo: condomínio e parceiro sintéticos marcados como DEMO. */
 public record Generation(UUID generationId,int generation,UUID tenantId,String tenantName,UUID partnerId,String partnerName,
   String status,Instant createdAt,UUID createdBy,Instant retiredAt,Map<String,Long> counts) {
  public Generation { counts=counts==null?Map.of():Map.copyOf(counts); }
 }
 /** Estado para a central: geração ativa (ou null) e as últimas aposentadas, mais recentes primeiro. */
 public record DemoState(Generation current,java.util.List<Generation> retired) {
  public DemoState { retired=java.util.List.copyOf(retired); }
 }
 /**
  * Pedido de criar (expectedTenantId null, sem demo ativo) ou resetar (expectedTenantId = demo ativo lido pela tela).
  * confirmation precisa ser exatamente "DEMO".
  */
 public record ProvisionDemo(UUID expectedTenantId,String reason,String confirmation) {}
 /** applied=false: repetição da mesma chave, nada novo foi criado. retiredTenantId: demo aposentado neste reset. */
 public record DemoProvisioned(Generation generation,UUID retiredTenantId,boolean applied) {}
}
