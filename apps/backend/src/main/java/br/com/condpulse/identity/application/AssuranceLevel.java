package br.com.condpulse.identity.application;

/** Nível de garantia declarado no token já validado. Somente aal2 comprova segundo fator verificado. */
public enum AssuranceLevel {
 AAL1,AAL2;
 public static AssuranceLevel fromClaim(Object claim) { return "aal2".equals(claim)?AAL2:AAL1; }
}
