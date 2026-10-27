package br.com.condpulse.identity.bff;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

public final class WebSessionStore {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 private static final RowMapper<StoredWebSession> MAPPER=(r,n)->new StoredWebSession(
  r.getObject("id",UUID.class),r.getString("token_hash"),r.getObject("account_id",UUID.class),
  r.getString("encrypted_access_token"),r.getString("encrypted_refresh_token"),
  r.getTimestamp("access_expires_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getLong("version"));
 public WebSessionStore(JdbcTemplate jdbc,TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }

 private <T> T context(String hash,UUID actor,Supplier<T> operation) {
  return tx.execute(status->{
   jdbc.queryForObject("select set_config('app.session_hash',?,true)",String.class,hash);
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,actor==null?"":actor.toString());
   jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
   return operation.get();
  });
 }
 private void requireActive(UUID accountId) {
  jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,accountId.toString());
  Integer count=jdbc.queryForObject("select count(*) from platform.accounts where id=? and status='ACTIVE'",Integer.class,accountId);
  if(count==null || count!=1)
   throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
 }
 public void create(StoredWebSession session) {
  context(session.tokenHash(),session.accountId(),()->{
   requireActive(session.accountId());
   jdbc.update("""
    insert into platform.web_sessions(id,token_hash,account_id,encrypted_access_token,encrypted_refresh_token,access_expires_at,expires_at)
    values(?,?,?,?,?,?,?)
    """,session.id(),session.tokenHash(),session.accountId(),session.encryptedAccessToken(),
      session.encryptedRefreshToken(),java.sql.Timestamp.from(session.accessExpiresAt()),java.sql.Timestamp.from(session.expiresAt()));
   return null;
  });
 }
 public StoredWebSession load(String hash) {
  return context(hash,null,()->{
   var rows=jdbc.query("select * from platform.web_sessions where token_hash=? and expires_at>now()",MAPPER,hash);
   if(rows.size()!=1) throw absent();
   requireActive(rows.getFirst().accountId());
   return rows.getFirst();
  });
 }
 public StoredWebSession claimRefresh(String hash,UUID lease) {
  StoredWebSession claimed=context(hash,null,()->{
   var rows=jdbc.query("select *,refresh_lease_until>now() as lease_active from platform.web_sessions where token_hash=? and expires_at>now() for update",
    (r,n)->new RefreshCandidate(MAPPER.mapRow(r,n),r.getObject("refresh_lease_id",UUID.class),r.getBoolean("lease_active")),hash);
   if(rows.size()!=1) throw absent();
   var candidate=rows.getFirst(); var session=candidate.session(); requireActive(session.accountId());
   if(candidate.leaseId()!=null) {
    if(candidate.leaseActive())
     throw new BusinessProblem("SESSION_REFRESH_IN_PROGRESS",409,"A sessão está sendo renovada. Tente novamente.");
    // O processo anterior pode ter rotacionado o token antes de cair. Nunca o repetir.
    // Retornar normalmente permite COMMIT do DELETE; erro é lançado somente fora da transação.
    jdbc.update("delete from platform.web_sessions where token_hash=?",hash);
    return null;
   }
   int changed=jdbc.update("""
    update platform.web_sessions set refresh_lease_id=?,refresh_lease_until=now()+interval '30 seconds'
    where token_hash=? and version=? and refresh_lease_id is null and refresh_lease_until is null
    """,lease,hash,session.version());
   if(changed!=1) throw new BusinessProblem("SESSION_REFRESH_IN_PROGRESS",409,"A sessão está sendo renovada. Tente novamente.");
   return session;
  });
  if(claimed==null) throw absent();
  return claimed;
 }
 public boolean completeRefresh(String hash,UUID lease,long expectedVersion,String access,String refresh,Instant accessExpiry) {
  return context(hash,null,()->jdbc.update("""
   update platform.web_sessions set encrypted_access_token=?,encrypted_refresh_token=?,access_expires_at=?,
     version=version+1,refresh_lease_id=null,refresh_lease_until=null
   where token_hash=? and refresh_lease_id=? and refresh_lease_until>now() and version=? and expires_at>now()
   """,access,refresh,java.sql.Timestamp.from(accessExpiry),hash,lease,expectedVersion)==1);
 }
 public void remove(String hash) {
  context(hash,null,()->{jdbc.update("delete from platform.web_sessions where token_hash=?",hash);return null;});
 }
 private BusinessProblem absent() { return new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente para continuar."); }
 private record RefreshCandidate(StoredWebSession session,UUID leaseId,boolean leaseActive) {}
}
