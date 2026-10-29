package br.com.condpulse.identity.bff;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tentativas de recuperação de acesso (V009). Toda operação publica somente o hash do cookie de recuperação no
 * contexto local da transação; a remoção das sessões web da conta usa um marcador próprio, ausente no fluxo comum.
 * Nenhuma transação fica aberta durante chamadas ao provedor: a reserva (lease) impede uso concorrente.
 */
public final class RecoveryStore {
 public static final int MAX_FAILURES=5;
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 private static final RowMapper<Attempt> MAPPER=(r,n)->new Attempt(r.getObject("id",UUID.class),r.getString("token_hash"),
  r.getObject("account_id",UUID.class),r.getString("encrypted_access_token"),r.getObject("factor_id",UUID.class),
  r.getBoolean("mfa_required"),r.getInt("failures"),r.getTimestamp("expires_at").toInstant(),r.getLong("version"));
 public RecoveryStore(JdbcTemplate jdbc,TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }

 public record Attempt(UUID id,String tokenHash,UUID accountId,String encryptedAccessToken,UUID factorId,
     boolean mfaRequired,int failures,Instant expiresAt,long version) {
  /** Contexto autenticado da cifra: impede trocar o token entre tentativas, contas ou sessões web. */
  public String cipherContext() { return contextFor(accountId,tokenHash); }
  public static String contextFor(UUID account,String tokenHash) { return "recovery/"+account+"/"+tokenHash+"/access"; }
  @Override public String toString() { return "RecoveryAttempt[id="+id+",tokens=REDACTED]"; }
 }

 private <T> T context(String hash,UUID actor,Supplier<T> operation) {
  return tx.execute(status->{
   jdbc.queryForObject("select set_config('app.recovery_hash',?,true)",String.class,hash);
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,actor==null?"":actor.toString());
   jdbc.queryForObject("select set_config('app.session_hash','',true)",String.class);
   jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
   return operation.get();
  });
 }

 /** Cria a tentativa com prazo pelo relógio do banco. A conta precisa estar ativa, como no login. */
 public Instant create(UUID id,String hash,UUID account,String encryptedAccess,UUID factor,boolean mfaRequired,int minutes) {
  return context(hash,account,()->{
   Integer active=jdbc.queryForObject("select count(*) from platform.accounts where id=? and status='ACTIVE'",Integer.class,account);
   if(active==null || active!=1)
    throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
   return jdbc.queryForObject("""
    insert into platform.recovery_attempts(id,token_hash,account_id,encrypted_access_token,factor_id,mfa_required,expires_at)
    values(?,?,?,?,?,?,clock_timestamp()+make_interval(mins=>?))
    returning expires_at
    """,(r,n)->r.getTimestamp("expires_at").toInstant(),id,hash,account,encryptedAccess,factor,mfaRequired,minutes);
  });
 }

 /**
  * Reserva a tentativa para uma conclusão. Tentativa vencida, esgotada ou com reserva anterior expirada (processo
  * interrompido, resultado incerto no provedor) é removida e não volta a ser usada.
  */
 public Attempt claim(String hash,UUID lease) {
  Attempt claimed=context(hash,null,()->{
   var rows=jdbc.query("""
    select *,lease_id is not null as leased,coalesce(lease_until>clock_timestamp(),false) as lease_active,
     expires_at<=clock_timestamp() as expired
    from platform.recovery_attempts where token_hash=? for update
    """,(r,n)->new Candidate(MAPPER.mapRow(r,n),r.getBoolean("leased"),r.getBoolean("lease_active"),r.getBoolean("expired")),hash);
   if(rows.size()!=1) return null;
   var candidate=rows.getFirst();
   if(candidate.leased() && candidate.leaseActive())
    throw new BusinessProblem("RECOVERY_IN_PROGRESS",409,"A troca de senha já está em andamento. Aguarde alguns segundos.");
   if(candidate.expired() || candidate.leased() || candidate.attempt().failures()>=MAX_FAILURES) {
    jdbc.update("delete from platform.recovery_attempts where token_hash=?",hash);
    return null;
   }
   int changed=jdbc.update("""
    update platform.recovery_attempts set lease_id=?,lease_until=clock_timestamp()+interval '30 seconds'
    where token_hash=? and version=? and lease_id is null
    """,lease,hash,candidate.attempt().version());
   if(changed!=1) throw new BusinessProblem("RECOVERY_IN_PROGRESS",409,"A troca de senha já está em andamento. Aguarde alguns segundos.");
   return candidate.attempt();
  });
  if(claimed==null) throw expired();
  return claimed;
 }
 /** Conta ainda ativa: suspensão depois de abrir o link encerra a tentativa antes de qualquer troca. */
 public boolean accountActive(UUID account) {
  Boolean active=tx.execute(status->{
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,account.toString());
   jdbc.queryForObject("select set_config('app.recovery_hash','',true)",String.class);
   jdbc.queryForObject("select set_config('app.session_hash','',true)",String.class);
   jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
   Integer count=jdbc.queryForObject("select count(*) from platform.accounts where id=? and status='ACTIVE'",Integer.class,account);
   return count!=null && count==1;
  });
  return Boolean.TRUE.equals(active);
 }
 /** Guarda o acesso elevado (AAL2) depois do segundo fator; tentativas seguintes não pedem o código de novo. */
 public void elevate(String hash,UUID lease,String encryptedAccess) {
  boolean updated=context(hash,null,()->jdbc.update("""
   update platform.recovery_attempts set encrypted_access_token=?,mfa_required=false,version=version+1
   where token_hash=? and lease_id=? and lease_until>clock_timestamp() and expires_at>clock_timestamp()
   """,encryptedAccess,hash,lease)==1);
  if(!updated) throw expired();
 }
 /** Conta uma recusa definitiva (código ou senha) e libera a reserva. Devolve as tentativas restantes. */
 public int fail(String hash,UUID lease) {
  return context(hash,null,()->{
   var rows=jdbc.query("""
    update platform.recovery_attempts set failures=failures+1,lease_id=null,lease_until=null,version=version+1
    where token_hash=? and lease_id=? and failures<? returning failures
    """,(r,n)->r.getInt("failures"),hash,lease,MAX_FAILURES);
   int failures=rows.isEmpty()?MAX_FAILURES:rows.getFirst();
   if(failures>=MAX_FAILURES) jdbc.update("delete from platform.recovery_attempts where token_hash=?",hash);
   return MAX_FAILURES-failures;
  });
 }
 /** Libera a reserva sem contar falha (limite do provedor, validação local). */
 public void release(String hash,UUID lease) {
  context(hash,null,()->jdbc.update(
   "update platform.recovery_attempts set lease_id=null,lease_until=null where token_hash=? and lease_id=?",hash,lease));
 }
 public void remove(String hash) {
  context(hash,null,()->jdbc.update("delete from platform.recovery_attempts where token_hash=?",hash));
 }
 /** Remove todas as sessões web da conta. Só a transação de recuperação publica o marcador exigido pela V009. */
 public int purgeSessions(UUID account) {
  Integer removed=tx.execute(status->{
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,account.toString());
   jdbc.queryForObject("select set_config('app.recovery_purge','on',true)",String.class);
   jdbc.queryForObject("select set_config('app.session_hash','',true)",String.class);
   jdbc.queryForObject("select set_config('app.recovery_hash','',true)",String.class);
   jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
   return jdbc.update("delete from platform.web_sessions where account_id=?",account);
  });
  return removed==null?0:removed;
 }
 private static BusinessProblem expired() {
  return new BusinessProblem("RECOVERY_SESSION_EXPIRED",401,"O prazo para definir a nova senha terminou. Solicite um novo link.");
 }
 private record Candidate(Attempt attempt,boolean leased,boolean leaseActive,boolean expired) {}
}
