package br.com.condpulse.shared.infrastructure;

import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Trilha append-only na mesma transação da alteração. Nunca recebe segredo, token ou dado biométrico. */
@Repository @Profile("api")
public class AuditRecorder {
 private final JdbcTemplate jdbc;
 public AuditRecorder(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 public void record(UUID tenant,UUID actor,String action,String resourceType,UUID resource,String reason) {
  Objects.requireNonNull(tenant); Objects.requireNonNull(actor); Objects.requireNonNull(resource);
  if(action==null || !action.matches("[A-Z][A-Z0-9_]{2,63}") || resourceType==null || !resourceType.matches("[A-Z][A-Z_]{2,39}"))
   throw new IllegalArgumentException("Ação de auditoria inválida.");
  if(reason!=null && reason.length()>500) throw new IllegalArgumentException("Motivo de auditoria longo demais.");
  jdbc.update("""
   insert into cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id,reason)
   values(?,?,?,?,?,?,?)
   """,tenant,actor,action,resourceType,resource,UUID.randomUUID(),reason);
 }
}
