package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

public final class WebSessionService {
 private final WebSessionStore store;
 private final SupabaseAuthClient auth;
 private final TokenCipher cipher;
 private final JwtDecoder decoder;
 private final BffSettings settings;
 private final Clock clock;
 public WebSessionService(WebSessionStore store,SupabaseAuthClient auth,TokenCipher cipher,
    JwtDecoder decoder,BffSettings settings,Clock clock) {
  this.store=store; this.auth=auth; this.cipher=cipher; this.decoder=decoder; this.settings=settings; this.clock=clock;
 }
 public SessionTicket login(String email,String password) {
  if(email==null || email.isBlank() || email.length()>320 || password==null || password.isEmpty() || password.length()>4096)
   throw new BusinessProblem("INVALID_LOGIN_REQUEST",400,"Informe os dados de acesso.");
  var tokens=auth.password(email,password);
  Jwt jwt;
  try { jwt=decodeNew(tokens.access_token()); }
  catch(RuntimeException ex) { auth.signOutBestEffort(tokens.access_token()); throw ex; }
  UUID account=UUID.fromString(jwt.getSubject());
  String raw=SessionSecrets.newToken(),hash=SessionSecrets.hash(raw);
  var session=new StoredWebSession(UUID.randomUUID(),hash,account,"","",jwt.getExpiresAt(),
    clock.instant().plus(settings.lifetime()),0);
  var stored=new StoredWebSession(session.id(),hash,account,
    cipher.seal(tokens.access_token(),session.cipherContext("access")),
    cipher.seal(tokens.refresh_token(),session.cipherContext("refresh")),
    jwt.getExpiresAt(),session.expiresAt(),0);
  try { store.create(stored); }
  catch(RuntimeException ex) {
   auth.signOutBestEffort(tokens.access_token());
   throw ex;
  }
  return new SessionTicket(raw,account,stored.expiresAt());
 }
 public Jwt authenticate(String raw) {
  var session=store.load(hash(raw));
  String access;
  try { access=cipher.open(session.encryptedAccessToken(),session.cipherContext("access")); }
  catch(IllegalStateException ex) {
   store.remove(session.tokenHash());
   throw new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente para continuar.");
  }
  // Acesso expirado não apaga refresh; cliente renova apenas por POST protegido por CSRF.
  return decoder.decode(access);
 }
 public SessionTicket refresh(String raw) {
  String hash=hash(raw);
  UUID lease=UUID.randomUUID();
  var session=store.claimRefresh(hash,lease);
  SupabaseAuthClient.TokenResponse tokens;
  try {
   String refresh=cipher.open(session.encryptedRefreshToken(),session.cipherContext("refresh"));
   tokens=auth.refresh(refresh);
  } catch(RuntimeException ex) {
   store.remove(hash); // Timeout/rotação incertos falham fechado; nenhuma repetição cega.
   throw ex;
  }
  Jwt jwt;
  try {
   jwt=decodeNew(tokens.access_token());
   if(!session.accountId().toString().equalsIgnoreCase(jwt.getSubject()))
    throw new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Não foi possível validar a sessão.");
   boolean updated=store.completeRefresh(hash,lease,session.version(),
     cipher.seal(tokens.access_token(),session.cipherContext("access")),
     cipher.seal(tokens.refresh_token(),session.cipherContext("refresh")),jwt.getExpiresAt());
   if(!updated) throw new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente para continuar.");
  } catch(RuntimeException ex) {
   store.remove(hash);
   auth.signOutBestEffort(tokens.access_token());
   throw ex;
  }
  return new SessionTicket(raw,session.accountId(),session.expiresAt());
 }
 public void logout(String raw) {
  if(raw==null) return;
  String hash;
  try { hash=SessionSecrets.hash(raw); } catch(IllegalArgumentException ex) { return; }
  StoredWebSession session=null;
  try { session=store.load(hash); }
  catch(BusinessProblem ex) {
   if(ex.status()!=401 && ex.status()!=403) throw ex;
  }
  store.remove(hash); // Invalidação durável antes de qualquer chamada externa.
  if(session!=null) {
   try { auth.signOutBestEffort(cipher.open(session.encryptedAccessToken(),session.cipherContext("access"))); }
   catch(IllegalStateException ex) { /* Sessão local já invalidada, chave antiga não a reativa. */ }
  }
 }
 /** Estado do segundo fator da própria sessão; nunca devolve tokens do provedor. */
 public MfaStatus mfaStatus(String raw) {
  var current=current(raw);
  return new MfaStatus(assurance(current.jwt()),auth.totpFactors(current.accessToken()));
 }
 public SupabaseAuthClient.TotpEnrollment enrollTotp(String raw) {
  return auth.enrollTotp(current(raw).accessToken());
 }
 /**
  * Verifica TOTP e substitui a sessão opaca por uma nova sessão AAL2 com o mesmo limite absoluto.
  * Código rejeitado preserva a sessão atual; elevação de privilégio nunca reaproveita o cookie anterior.
  */
 public SessionTicket verifyTotp(String raw,String factorId,String code) {
  UUID factor=parseFactor(factorId);
  if(code==null || !code.matches("[0-9]{6}"))
   throw new BusinessProblem("MFA_CODE_REJECTED",422,"Informe o código de 6 dígitos do aplicativo autenticador.");
  var current=current(raw);
  var tokens=auth.verifyTotp(current.accessToken(),factor,code);
  Jwt jwt=decodeNew(tokens.access_token());
  UUID account=current.session().accountId();
  if(!account.toString().equalsIgnoreCase(jwt.getSubject()) || assurance(jwt)!=AssuranceLevel.AAL2)
   throw new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Não foi possível confirmar o segundo fator.");
  String nextRaw=SessionSecrets.newToken(),nextHash=SessionSecrets.hash(nextRaw);
  var draft=new StoredWebSession(UUID.randomUUID(),nextHash,account,"","",jwt.getExpiresAt(),
    current.session().expiresAt(),0);
  var stored=new StoredWebSession(draft.id(),nextHash,account,
    cipher.seal(tokens.access_token(),draft.cipherContext("access")),
    cipher.seal(tokens.refresh_token(),draft.cipherContext("refresh")),
    jwt.getExpiresAt(),draft.expiresAt(),0);
  store.create(stored);
  store.remove(current.session().tokenHash());
  return new SessionTicket(nextRaw,account,stored.expiresAt());
 }
 private CurrentAccess current(String raw) {
  var session=store.load(hash(raw));
  String access;
  try { access=cipher.open(session.encryptedAccessToken(),session.cipherContext("access")); }
  catch(IllegalStateException ex) {
   store.remove(session.tokenHash());
   throw new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente para continuar.");
  }
  Jwt jwt;
  try { jwt=decoder.decode(access); }
  catch(JwtException ex) {
   throw new BusinessProblem("SESSION_REFRESH_REQUIRED",401,"Renove a sessão para continuar.");
  }
  if(!session.accountId().toString().equalsIgnoreCase(jwt.getSubject())) {
   store.remove(session.tokenHash());
   throw new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente para continuar.");
  }
  return new CurrentAccess(session,access,jwt);
 }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
 private static UUID parseFactor(String factorId) {
  try {
   UUID parsed=UUID.fromString(factorId);
   if(parsed.toString().equalsIgnoreCase(factorId)) return parsed;
  } catch(IllegalArgumentException | NullPointerException ignored) { /* resposta neutra abaixo */ }
  throw new BusinessProblem("MFA_FACTOR_NOT_FOUND",404,"Fator de autenticação não encontrado.");
 }
 private record CurrentAccess(StoredWebSession session,String accessToken,Jwt jwt) {
  @Override public String toString() { return "CurrentAccess[tokens=REDACTED]"; }
 }
 public record MfaStatus(AssuranceLevel assuranceLevel,List<SupabaseAuthClient.Factor> factors) {
  public MfaStatus { factors=List.copyOf(factors); }
 }
 private String hash(String raw) {
  try { return SessionSecrets.hash(raw); }
  catch(IllegalArgumentException ex) { throw new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente para continuar."); }
 }
 private Jwt decodeNew(String access) {
  try {
   Jwt jwt=decoder.decode(access);
   if(jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(clock.instant()))
    throw new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Não foi possível validar a sessão.");
   return jwt;
  } catch(JwtException ex) {
   throw new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Não foi possível validar a sessão.");
  }
 }
 public record SessionTicket(String opaqueToken,UUID accountId,Instant expiresAt) {
  @Override public String toString() { return "SessionTicket[credentials=REDACTED]"; }
 }
}
