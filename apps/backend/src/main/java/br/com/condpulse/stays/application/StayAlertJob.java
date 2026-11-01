package br.com.condpulse.stays.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import br.com.condpulse.notifications.application.WorkerTransactions;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Alertas de permanência N1/N2 (CPX-016, V055) no worker (cp_worker): para cada condomínio ativo, uma transação própria
 * marca STAY_DUE, STAY_OVERDUE_L1 e STAY_OVERDUE_L2 das permanências abertas e sem justificativa cujo horário passou. O
 * banco decide o horário, grava cada nível uma única vez (sob a trava da linha) e o evento no Log de Segurança no mesmo
 * COMMIT; repetir não grava nada. O atraso máximo é o intervalo da passada. Falha num condomínio não impede os demais.
 */
public class StayAlertJob {
 private static final System.Logger LOG=System.getLogger(StayAlertJob.class.getName());
 private final WorkerTransactions transactions;
 private final JdbcTemplate jdbc;
 public StayAlertJob(WorkerTransactions transactions,JdbcTemplate jdbc) {
  this.transactions=Objects.requireNonNull(transactions); this.jdbc=Objects.requireNonNull(jdbc);
 }
 public record Pass(int tenants,int stays,int failures) {}

 public Pass runOnce() {
  List<UUID> tenants=transactions.catalog(()->jdbc.query("select id from platform.tenants where active order by id",
   (r,n)->r.getObject("id",UUID.class)));
  int stays=0,failures=0;
  for(UUID tenant:tenants) {
   try {
    Integer changed=transactions.inTenant(tenant,()->jdbc.queryForObject("select cp.stay_alert_pass()",Integer.class));
    stays+=changed==null?0:changed;
   } catch(RuntimeException error) {
    failures++;
    LOG.log(System.Logger.Level.WARNING,"Alertas de permanência não gravados: {0}",error.getClass().getSimpleName());
   }
  }
  return new Pass(tenants.size(),stays,failures);
 }
}
