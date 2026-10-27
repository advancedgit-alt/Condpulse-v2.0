package br.com.condpulse.identity.bff;

import java.time.Instant;
import java.util.UUID;

public record StoredWebSession(UUID id,String tokenHash,UUID accountId,String encryptedAccessToken,
 String encryptedRefreshToken,Instant accessExpiresAt,Instant expiresAt,long version) {
 public String cipherContext(String kind) { return accountId+"/"+tokenHash+"/"+kind; }
 @Override public String toString() { return "StoredWebSession[id="+id+",tokens=REDACTED]"; }
}
