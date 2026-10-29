package br.com.condpulse.shared.infrastructure;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component @Profile({"api","worker"})
public class DatabaseRoleGuard implements ApplicationRunner {
 private final JdbcTemplate jdbc;
 public DatabaseRoleGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 @Override public void run(ApplicationArguments args) {
  // Trilhas e papéis de plataforma (V010), financeiro (V011), registros de acesso (V014) e caixa interna (V015)
  // conferidos por to_regclass: banco ainda sem a migration não falha aqui. O financeiro nunca apaga: estorno e
  // cancelamento mantêm a linha; o registro de acesso nunca muda: correção é um evento adicional; tarefa do outbox e
  // aviso entregue não são apagados pela aplicação.
  Boolean unsafe=jdbc.queryForObject("""
    select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication
     or has_schema_privilege(current_user,'cp','CREATE')
     or has_schema_privilege(current_user,'platform','CREATE')
     or has_table_privilege(current_user,'cp.audit_log','UPDATE')
     or has_table_privilege(current_user,'cp.audit_log','DELETE')
     or has_table_privilege(current_user,'cp.audit_log','TRUNCATE')
     or coalesce(has_table_privilege(current_user,to_regclass('platform.platform_audit_log'),'UPDATE,DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.platform_roles'),'INSERT,UPDATE,DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.platform_role_events'),'INSERT,UPDATE,DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.billing_plans'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.billing_subscriptions'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.billing_charges'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.billing_payments'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_events'),'UPDATE,DELETE,TRUNCATE'),false)
     or has_table_privilege(current_user,'cp.outbox','DELETE,TRUNCATE')
     or coalesce(has_table_privilege(current_user,to_regclass('cp.notifications'),'DELETE,TRUNCATE'),false)
    from pg_roles where rolname=current_user
    """,Boolean.class);
  if(!Boolean.FALSE.equals(unsafe))
   throw new IllegalStateException("Role de execução com privilégios incompatíveis.");
  Integer unprotected=jdbc.queryForObject("""
    select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where c.relkind='r' and(not c.relrowsecurity or not c.relforcerowsecurity)
     and(n.nspname='cp' or c.oid in(to_regclass('platform.platform_roles'),to_regclass('platform.platform_audit_log'),
      to_regclass('platform.platform_role_events'),to_regclass('platform.billing_plans'),
      to_regclass('platform.billing_subscriptions'),to_regclass('platform.billing_charges'),to_regclass('platform.billing_payments')))
    """,Integer.class);
  if(unprotected==null || unprotected>0)
   throw new IllegalStateException("Schema de negócio sem proteção RLS completa.");
 }
}
