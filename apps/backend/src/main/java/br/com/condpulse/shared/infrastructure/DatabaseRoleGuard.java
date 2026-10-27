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
  Boolean unsafe=jdbc.queryForObject("""
    select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication
     or has_schema_privilege(current_user,'cp','CREATE')
     or has_table_privilege(current_user,'cp.audit_log','UPDATE')
     or has_table_privilege(current_user,'cp.audit_log','DELETE')
    from pg_roles where rolname=current_user
    """,Boolean.class);
  if(!Boolean.FALSE.equals(unsafe))
   throw new IllegalStateException("Role de execução com privilégios incompatíveis.");
  Integer unprotected=jdbc.queryForObject("""
    select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='cp' and c.relkind='r' and(not c.relrowsecurity or not c.relforcerowsecurity)
    """,Integer.class);
  if(unprotected==null || unprotected>0)
   throw new IllegalStateException("Schema de negócio sem proteção RLS completa.");
 }
}
