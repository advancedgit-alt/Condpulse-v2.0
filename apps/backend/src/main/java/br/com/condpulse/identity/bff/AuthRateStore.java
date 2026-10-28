package br.com.condpulse.identity.bff;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Somente contadores. A transacao termina antes de qualquer chamada ao Auth. */
public final class AuthRateStore {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public AuthRateStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.jdbc=jdbc; tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(5);
 }
 public AuthRateLimiter.Admission admit(List<AuthRateLimiter.Bucket> buckets) {
  if(buckets==null || buckets.size()!=2 || buckets.get(0).key().equals(buckets.get(1).key()))
   throw new IllegalArgumentException("Dois limites distintos sao obrigatorios.");
  // O GC nao compartilha locks com a ordem global -> sujeito da quota.
  // SKIP LOCKED evita esperar por uma quota que esteja sendo admitida.
  tx.execute(status->{
   clearContext();
   jdbc.update("""
    with expired as (
      select bucket_key from platform.auth_rate_limits where expires_at<=clock_timestamp()
      order by expires_at,bucket_key limit 16 for update skip locked)
    delete from platform.auth_rate_limits where expires_at<=clock_timestamp()
      and bucket_key in (select bucket_key from expired)
    """);
   return null;
  });
  return tx.execute(status->{
   clearContext();
   for(var bucket:buckets) {
    jdbc.queryForObject("select set_config('app.auth_rate_key',?,true)",String.class,bucket.key());
    var result=jdbc.queryForObject("""
     with observed as materialized (select clock_timestamp() as at), changed as (
      insert into platform.auth_rate_limits(bucket_key,window_started_at,attempts,expires_at)
      select ?,at,1,at+(?*interval '1 second') from observed
      on conflict(bucket_key) do update set
       window_started_at=case when platform.auth_rate_limits.expires_at<=(select at from observed)
         then (select at from observed) else platform.auth_rate_limits.window_started_at end,
       attempts=case when platform.auth_rate_limits.expires_at<=(select at from observed)
         then 1 else least(platform.auth_rate_limits.attempts+1,?) end,
       expires_at=case when platform.auth_rate_limits.expires_at<=(select at from observed)
         then (select at from observed)+(?*interval '1 second') else platform.auth_rate_limits.expires_at end
      returning attempts,expires_at)
     select attempts,greatest(1,ceil(extract(epoch from expires_at-at)))::bigint as retry_after
     from changed cross join observed
     """,(rows,n)->new AuthRateLimiter.Admission(rows.getInt("attempts")<=bucket.limit(),
       rows.getInt("attempts")<=bucket.limit()?0:rows.getLong("retry_after")),
     bucket.key(),bucket.seconds(),bucket.limit()+1,bucket.seconds());
    if(result==null) throw new IllegalStateException("Resultado de limite ausente.");
    // Retorno normal preserva COMMIT inclusive quando a tentativa foi negada.
    if(!result.allowed()) return result;
   }
   return AuthRateLimiter.Admission.permitted();
  });
 }
 private void clearContext() {
  jdbc.queryForObject("select set_config('app.actor_id','',true)",String.class);
  jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
  jdbc.queryForObject("select set_config('app.session_hash','',true)",String.class);
  jdbc.queryForObject("select set_config('app.auth_rate_key','',true)",String.class);
 }
}
