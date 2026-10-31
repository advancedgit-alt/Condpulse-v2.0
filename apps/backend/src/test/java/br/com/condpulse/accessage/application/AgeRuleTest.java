package br.com.condpulse.accessage.application;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static br.com.condpulse.accessage.application.AgeRule.LeapDayRule.FEBRUARY_28;
import static br.com.condpulse.accessage.application.AgeRule.LeapDayRule.MARCH_1;
import static br.com.condpulse.accessage.application.AgeRule.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** AC-006: anos completos na data civil, critério de 29/02 por condomínio e predicados de atestação. */
class AgeRuleTest {
 private static final LocalDate BIRTH=LocalDate.of(2008,11,1);

 @Test void eveAndBirthdayAreDefined() {
  // HAC-031/HAC-032: 17 anos e 364 dias nega; no dia do aniversário (data local) atende.
  assertEquals(DOES_NOT_MEET,AgeRule.fromBirthDate(BIRTH,LocalDate.of(2026,10,31),MARCH_1,18));
  assertEquals(17,AgeRule.completedYears(BIRTH,LocalDate.of(2026,10,31),MARCH_1));
  assertEquals(MEETS,AgeRule.fromBirthDate(BIRTH,LocalDate.of(2026,11,1),MARCH_1,18));
  assertEquals(MEETS,AgeRule.fromBirthDate(LocalDate.of(1986,5,10),LocalDate.of(2026,10,31),MARCH_1,18),"40 anos");
 }
 @Test void leapDayRuleIsATenantParameter() {
  // HAC-035: nascido em 29/02 — critério explícito, não implícito em biblioteca.
  LocalDate leap=LocalDate.of(2008,2,29);
  assertEquals(17,AgeRule.completedYears(leap,LocalDate.of(2026,2,28),MARCH_1),"MARCH_1: em 28/02 ainda não completou");
  assertEquals(18,AgeRule.completedYears(leap,LocalDate.of(2026,3,1),MARCH_1),"MARCH_1: completa em 01/03");
  assertEquals(18,AgeRule.completedYears(leap,LocalDate.of(2026,2,28),FEBRUARY_28),"FEBRUARY_28: completa em 28/02");
  assertEquals(17,AgeRule.completedYears(leap,LocalDate.of(2026,2,27),FEBRUARY_28));
  assertEquals(20,AgeRule.completedYears(leap,LocalDate.of(2028,2,29),MARCH_1),"Ano bissexto: o próprio 29/02");
  assertEquals(19,AgeRule.completedYears(leap,LocalDate.of(2028,2,28),FEBRUARY_28),"Em ano bissexto o 28/02 é véspera");
 }
 @Test void unknownNeverPresumesAdulthood() {
  // HAC-033: sem evidência, ou com data impossível, é desconhecido.
  assertEquals(UNKNOWN,AgeRule.fromBirthDate(null,LocalDate.of(2026,10,31),MARCH_1,18));
  assertEquals(UNKNOWN,AgeRule.fromBirthDate(LocalDate.of(2030,1,1),LocalDate.of(2026,10,31),MARCH_1,18));
  assertEquals(UNKNOWN,AgeRule.fromAttestation(18,null,null,18));
 }
 @Test void attestationOnlyConcludesWhatItCovers() {
  assertEquals(MEETS,AgeRule.fromAttestation(18,null,true,18));
  assertEquals(MEETS,AgeRule.fromAttestation(21,null,true,18));
  assertEquals(UNKNOWN,AgeRule.fromAttestation(18,null,true,21),"Atestado de 18+ não prova 21+");
  assertEquals(DOES_NOT_MEET,AgeRule.fromAttestation(18,null,false,18));
  assertEquals(DOES_NOT_MEET,AgeRule.fromAttestation(16,null,false,18),"Não tem 16 então não tem 18");
  assertEquals(UNKNOWN,AgeRule.fromAttestation(18,null,false,16),"Não ter 18 não diz nada sobre 16");
  assertEquals(DOES_NOT_MEET,AgeRule.fromAttestation(null,17,true,18),"Até 17 anos");
 }
 @Test void birthDateIsBoundToItsRowAndNeverStoredInClear() {
  byte[] raw=new byte[32]; new SecureRandom().nextBytes(raw);
  var cipher=BirthDateCipher.fromBase64(Base64.getEncoder().encodeToString(raw),"v1");
  assertTrue(cipher.available());
  UUID tenant=UUID.randomUUID(),subject=UUID.randomUUID(),evidence=UUID.randomUUID();
  byte[] sealed=cipher.encrypt(BIRTH,tenant,subject,evidence);
  assertTrue(sealed.length>=30 && sealed.length<=128,"Cabe no CHECK da V039");
  assertFalse(new String(sealed,java.nio.charset.StandardCharsets.ISO_8859_1).contains("2008"),"Sem data em claro");
  assertEquals(BIRTH,cipher.decrypt(sealed,tenant,subject,evidence));
  assertEquals("AGE_EVIDENCE_KEY_UNAVAILABLE",assertThrows(BusinessProblem.class,
   ()->cipher.decrypt(sealed,tenant,UUID.randomUUID(),evidence)).code(),"Envelope copiado para outro sujeito não decifra");
  assertFalse(BirthDateCipher.fromBase64("",null).available());
  assertFalse(BirthDateCipher.fromBase64("curta","v1").available());
  assertEquals("AGE_EVIDENCE_KEY_UNAVAILABLE",assertThrows(BusinessProblem.class,
   ()->BirthDateCipher.fromBase64(null,"v1").encrypt(BIRTH,tenant,subject,evidence)).code(),"Sem chave: falha fechada");
 }
}
