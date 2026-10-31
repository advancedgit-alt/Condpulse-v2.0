package br.com.condpulse.securitylog.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import br.com.condpulse.notifications.application.WorkerTransactions;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Âncora diária do Log de Segurança (CPX-008, R5.3) no worker (cp_worker): para cada condomínio ativo, uma transação
 * própria grava o último hash de cada dia fechado (últimos 7, fuso America/Sao_Paulo) ainda sem âncora. O banco escolhe
 * o evento e confere o hash; repetir não grava nada. Falha num condomínio não impede os demais.
 */
public class SecurityLogAnchorJob {
 private static final System.Logger LOG=System.getLogger(SecurityLogAnchorJob.class.getName());
 private final WorkerTransactions transactions;
 private final JdbcTemplate jdbc;
 public SecurityLogAnchorJob(WorkerTransactions transactions,JdbcTemplate jdbc) {
  this.transactions=Objects.requireNonNull(transactions); this.jdbc=Objects.requireNonNull(jdbc);
 }
 public record Pass(int tenants,int anchors,int failures) {}

 public Pass runOnce() {
  List<UUID> tenants=transactions.catalog(()->jdbc.query("select id from platform.tenants where active order by id",
   (r,n)->r.getObject("id",UUID.class)));
  int anchors=0,failures=0;
  for(UUID tenant:tenants) {
   try {
    Integer written=transactions.inTenant(tenant,()->jdbc.queryForObject("select platform.security_log_anchor_days()",Integer.class));
    anchors+=written==null?0:written;
   } catch(RuntimeException error) {
    failures++;
    LOG.log(System.Logger.Level.WARNING,"Âncora do log de segurança não gravada: {0}",error.getClass().getSimpleName());
   }
  }
  return new Pass(tenants.size(),anchors,failures);
 }
}
