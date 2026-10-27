package br.com.condpulse.identity.bff;

import java.net.URI;
import java.time.Duration;

public final class BffSettings {
 private final URI origin;
 private final URI authBase;
 private final String publishableKey;
 private final Duration lifetime;
 public BffSettings(String origin,String issuer,String publishableKey,long sessionHours) {
  this.origin=URI.create(origin); authBase=URI.create(issuer);
  if(!validHttps(this.origin) || this.origin.getPath()!=null && !this.origin.getPath().isEmpty()
    || this.origin.getQuery()!=null || this.origin.getFragment()!=null)
   throw new IllegalArgumentException("A origem BFF deve ser HTTPS e não conter caminho.");
  if(!validHttps(authBase) || !authBase.getPath().equals("/auth/v1")
    || authBase.getQuery()!=null || authBase.getFragment()!=null)
   throw new IllegalArgumentException("Emissor de autenticação inválido.");
  if(publishableKey==null || publishableKey.isBlank())
   throw new IllegalArgumentException("Configure a chave pública do Auth.");
  if(sessionHours<1 || sessionHours>24) throw new IllegalArgumentException("Vigência BFF inválida.");
  this.publishableKey=publishableKey; lifetime=Duration.ofHours(sessionHours);
 }
 private boolean validHttps(URI uri) {
  return "https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null;
 }
 public String origin() { return origin.toString(); }
 public String authBase() { return authBase.toString(); }
 public String publishableKey() { return publishableKey; }
 public Duration lifetime() { return lifetime; }
 @Override public String toString() { return "BffSettings[origin="+origin+",credentials=REDACTED]"; }
}
