package br.com.condpulse.parcels.infrastructure;

import br.com.condpulse.parcels.application.DelegationCodeKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Chave do código de retirada delegada (CP_PARCEL_CODE_KEY). Ausente: delegação indisponível; inválida: não inicia. */
@Configuration(proxyBeanMethods=false) @Profile("api")
public class DelegationCodeConfiguration {
 @Bean DelegationCodeKey delegationCodeKey(@Value("${condpulse.parcels.code-key:}") String key,
   @Value("${condpulse.bff.encryption-key:}") String bffEncryptionKey,@Value("${condpulse.bff.rate-limit-key:}") String bffRateLimitKey) {
  return new DelegationCodeKey(key,bffEncryptionKey,bffRateLimitKey);
 }
}
