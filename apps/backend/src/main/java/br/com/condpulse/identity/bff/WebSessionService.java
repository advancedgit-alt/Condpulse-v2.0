package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
