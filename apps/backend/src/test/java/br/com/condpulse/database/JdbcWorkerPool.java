package br.com.condpulse.database;

import java.util.Set;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * Conexão do worker no banco descartável: login próprio (cp_ci_worker) membro somente de cp_worker, nunca de cp_api.
 * Mesmas travas de host e banco da fixture da API; nunca aceita URL fornecida.
 */
final class JdbcWorkerPool implements AutoCloseable {
 final HikariDataSource pool;
 final JdbcTemplate jdbc;
 final JdbcTransactionManager manager;

 JdbcWorkerPool() {
  if(!"DISPOSABLE".equals(System.getenv("CP_JDBC_TEST_OK"))) throw new IllegalStateException("Ensaio JDBC exige confirmação DISPOSABLE.");
  String host=required("PGHOST"),database=required("PGDATABASE"),port=required("PGPORT");
  if(!Set.of("localhost","127.0.0.1","postgres").contains(host)
      || !(database.equals("condpulse_ci") || database.matches("cp_test_[A-Za-z0-9_]+"))
      || !port.matches("[0-9]{1,5}") || Integer.parseInt(port)<1 || Integer.parseInt(port)>65535)
   throw new IllegalStateException("Host, banco ou porta não permitido para o worker de teste.");
  String user=required("CP_TEST_WORKER_DB_USER");
  if(!"cp_ci_worker".equals(user)) throw new IllegalStateException("Ensaio do worker exige o login descartável cp_ci_worker.");
  HikariConfig config=new HikariConfig();
  config.setJdbcUrl("jdbc:postgresql://"+host+":"+port+"/"+database);
  config.setUsername(user); config.setPassword(required("CP_TEST_WORKER_DB_PASSWORD"));
  config.setPoolName("condpulse-jdbc-worker"); config.setMaximumPoolSize(2); config.setMinimumIdle(0);
  config.setConnectionTimeout(5000); config.setInitializationFailTimeout(5000);
  pool=new HikariDataSource(config); jdbc=new JdbcTemplate(pool); manager=new JdbcTransactionManager(pool);
  try {
   Boolean safe=jdbc.queryForObject("""
    select current_user='cp_ci_worker' and pg_has_role(current_user,'cp_worker','USAGE') and not pg_has_role(current_user,'cp_api','USAGE')
     and not(rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication)
    from pg_roles where rolname=current_user
    """,Boolean.class);
   if(!Boolean.TRUE.equals(safe)) throw new IllegalStateException("Login do worker com privilégios incompatíveis.");
  } catch(RuntimeException ex) { pool.close(); throw ex; }
 }
 private static String required(String name) {
  String value=System.getenv(name);
  if(value==null || value.isBlank()) throw new IllegalStateException("Variável de teste ausente: "+name);
  return value;
 }
 /** Nenhum contexto de condomínio ou de conta sobrevive à devolução da conexão ao pool. */
 void assertEmptyContext() {
  for(String setting:new String[]{"app.actor_id","app.tenant_id","app.capabilities","app.platform_capabilities","app.session_hash","app.invitation_hash"}) {
   String value=jdbc.queryForObject("select coalesce(current_setting(?,true),'')",String.class,setting);
   org.junit.jupiter.api.Assertions.assertEquals("",value,"Contexto do worker não pode sobreviver: "+setting);
  }
 }
 @Override public void close() { pool.close(); }
}
