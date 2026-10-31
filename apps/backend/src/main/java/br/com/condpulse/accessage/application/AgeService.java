package br.com.condpulse.accessage.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessage.infrastructure.AgeStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Evidência de idade (AC-006, V039) e parâmetros do acesso facial por condomínio. A gestão (memberships.manage, AAL2)
 * registra atestação ou data cifrada, revoga e consulta só o predicado "N anos ou mais" na data civil do condomínio.
 * Data ausente, vencida ou revogada responde UNKNOWN: nunca presume maioridade. A data nunca sai do serviço.
 */
public class AgeService {
 private static final Set<String> METHODS=Set.of("AGE_PREDICATE_ATTESTATION","ENCRYPTED_BIRTH_DATE");
 private static final Set<String> VERIFICATION=Set.of("DOCUMENT_REVIEW","TRUSTED_EXISTING_RECORD");
 private final TenantTransactionExecutor transactions;
 private final AgeStore store;
 private final BirthDateCipher cipher;
 private final AuditRecorder audit;
 private final SensitiveViewRecorder views;
 public AgeService(TenantTransactionExecutor transactions,AgeStore store,BirthDateCipher cipher,AuditRecorder audit) {
  this(transactions,store,cipher,audit,SensitiveViewRecorder.disabled());
 }
 public AgeService(TenantTransactionExecutor transactions,AgeStore store,BirthDateCipher cipher,AuditRecorder audit,SensitiveViewRecorder views) {
  this.transactions=transactions; this.store=store; this.cipher=Objects.requireNonNull(cipher); this.audit=audit;
  this.views=Objects.requireNonNull(views);
 }

 public AgeModels.Settings settings(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   return store.settings(tenant);
  }));
 }
 public AgeModels.Settings updateSettings(UUID actor,UUID tenant,AssuranceLevel assurance,AgeModels.UpdateSettings request) {
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw invalid("FACIAL_SETTINGS_VERSION_REQUIRED","Informe a versão lida.");
  String leap=request.leapDayRule();
  if(leap==null || !Set.of("MARCH_1","FEBRUARY_28").contains(leap)) throw invalid("FACIAL_SETTINGS_LEAP_RULE_INVALID","Critério de 29/02: MARCH_1 ou FEBRUARY_28.");
  if(request.minorFaceEnrollmentEnabled()==null) throw invalid("FACIAL_SETTINGS_MINORS_REQUIRED","Informe se o cadastro facial de menores fica ligado.");
  Integer days=request.ageEvidenceValidityDays();
  if(days==null || days<1 || days>3660) throw invalid("FACIAL_SETTINGS_VALIDITY_INVALID","Validade da evidência de 1 a 3660 dias.");
  String reason=DisplayText.normalize(request.reason(),500);
  if(reason==null || reason.length()<3) throw invalid("AUDIT_REASON_REQUIRED","Informe o motivo da alteração.");
  return mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(!store.saveSettings(tenant,request.expectedVersion(),leap,request.minorFaceEnrollmentEnabled(),days,actor))
    throw new BusinessProblem("FACIAL_SETTINGS_STALE",409,"Os parâmetros mudaram. Atualize e tente de novo.");
   audit.record(tenant,actor,"FACIAL_SETTINGS_UPDATED","FACIAL_SETTINGS",tenant,reason);
   return store.settings(tenant);
  }));
 }

 /** Registra evidência e substitui a válida anterior na mesma transação (correção cadastral). */
 public AgeModels.Evidence record(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject,AgeModels.RecordEvidence request) {
  if(subject==null) throw notFound();
  if(request==null || request.evidenceId()==null) throw invalid("AGE_EVIDENCE_ID_REQUIRED","Informe o identificador da evidência.");
  if(request.method()==null || !METHODS.contains(request.method())) throw invalid("AGE_EVIDENCE_METHOD_INVALID","Método inválido.");
  if(request.verificationMethod()==null || !VERIFICATION.contains(request.verificationMethod())) throw invalid("AGE_EVIDENCE_VERIFICATION_INVALID","Informe como a evidência foi conferida.");
  if(request.sourceEvidenceRef()==null || !request.sourceEvidenceRef().matches("[A-Za-z0-9._:-]{8,120}"))
   throw invalid("AGE_EVIDENCE_SOURCE_INVALID","Referência da prova: 8 a 120 caracteres sem espaço (nunca o número do documento).");
  boolean attestation="AGE_PREDICATE_ATTESTATION".equals(request.method());
  Integer min=attestation?request.minimumAge():null,max=attestation?request.maximumAge():null;
  Boolean predicate=attestation?request.predicateResult():null;
  if(attestation) {
   if(request.birthDate()!=null) throw invalid("AGE_EVIDENCE_SHAPE_INVALID","Atestação não leva data de nascimento.");
   if((min==null && max==null) || predicate==null || (min!=null && (min<0 || min>130)) || (max!=null && (max<0 || max>130))
     || (min!=null && max!=null && max<min)) throw invalid("AGE_EVIDENCE_SHAPE_INVALID","Atestação exige limite de idade (0 a 130) e resultado.");
  } else {
   if(request.birthDate()==null || request.minimumAge()!=null || request.maximumAge()!=null || request.predicateResult()!=null)
    throw invalid("AGE_EVIDENCE_SHAPE_INVALID","Data protegida exige só a data de nascimento.");
   if(request.birthDate().isBefore(LocalDate.of(1890,1,1))) throw invalid("AGE_EVIDENCE_SHAPE_INVALID","Data de nascimento inválida.");
   if(!cipher.available()) throw cipherUnavailable();
  }
  return mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(!store.subjectExists(tenant,subject)) throw notFound();
   if(!attestation && request.birthDate().isAfter(LocalDate.now(context.timeZone())))
    throw invalid("AGE_EVIDENCE_SHAPE_INVALID","Data de nascimento no futuro.");
   var existing=store.find(tenant,request.evidenceId());
   if(existing!=null) {
    // Repetição só com o pedido inteiro igual (inclusive a data, conferida decifrando).
    boolean same=AgeStore.sameShape(existing,subject,request.method(),min,max,predicate,request.verificationMethod(),request.sourceEvidenceRef())
     && (attestation || request.birthDate().equals(cipher.decrypt(existing.ciphertext(),tenant,subject,existing.id())));
    if(same) return existing.projection();
    throw new BusinessProblem("AGE_EVIDENCE_ID_CONFLICT",409,"Este identificador já foi usado para outra evidência.");
   }
   var settings=store.settings(tenant);
   var previous=store.currentForUpdate(tenant,subject);
   if(previous!=null) {
    store.close(tenant,previous.id(),previous.version(),"SUPERSEDED","Correcao ou renovacao",actor);
    audit.record(tenant,actor,"AGE_EVIDENCE_SUPERSEDED","AGE_EVIDENCE",previous.id(),null);
   }
   byte[] sealed=attestation?null:cipher.encrypt(request.birthDate(),tenant,subject,request.evidenceId());
   store.insert(tenant,request.evidenceId(),subject,request.method(),min,max,predicate,sealed,attestation?null:BirthDateCipher.KEY_REF,
    attestation?null:cipher.version(),request.verificationMethod(),request.sourceEvidenceRef(),actor,settings.ageEvidenceValidityDays(),
    previous==null?null:previous.id());
   audit.record(tenant,actor,"AGE_EVIDENCE_RECORDED","AGE_EVIDENCE",request.evidenceId(),null);
   return store.find(tenant,request.evidenceId()).projection();
  }));
 }

 public void revoke(UUID actor,UUID tenant,AssuranceLevel assurance,UUID evidence,AgeModels.RevokeEvidence request) {
  if(evidence==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw invalid("AGE_EVIDENCE_VERSION_REQUIRED","Informe a versão lida.");
  String reason=DisplayText.normalize(request.reason(),300);
  if(reason==null || reason.length()<3) throw invalid("AUDIT_REASON_REQUIRED","Informe o motivo (3 a 300 caracteres).");
  mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(store.find(tenant,evidence)==null) throw notFound();
   if(!store.close(tenant,evidence,request.expectedVersion(),"REVOKED",reason,actor))
    throw new BusinessProblem("AGE_EVIDENCE_STALE",409,"A evidência mudou ou já foi encerrada. Atualize e tente de novo.");
   audit.record(tenant,actor,"AGE_EVIDENCE_REVOKED","AGE_EVIDENCE",evidence,reason);
   return null;
  }));
 }

 public java.util.List<AgeModels.Evidence> history(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject) {
  return mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(!store.subjectExists(tenant,subject)) throw notFound();
   var history=store.history(tenant,subject).stream().map(AgeStore.Row::projection).toList();
   // Ficha da pessoa (evidência de idade) lida pela gestão (CPX-011): SENSITIVE_VIEWED na mesma transação.
   views.record(tenant,"MANAGEMENT","PERSON_AGE_EVIDENCE","ACCESS_SUBJECT",subject,null,java.util.Map.of("count",history.size()));
   return history;
  }));
 }

 /** "N anos ou mais" hoje, na data civil do fuso do condomínio, com o critério de 29/02 do condomínio. */
 public AgeModels.AgeCheck check(UUID actor,UUID tenant,AssuranceLevel assurance,UUID subject,Integer minimumAge) {
  if(subject==null) throw notFound();
  if(minimumAge==null || minimumAge<0 || minimumAge>130) throw invalid("AGE_MINIMUM_INVALID","Idade mínima de 0 a 130.");
  return mapped(()->transactions.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   if(!store.subjectExists(tenant,subject)) throw notFound();
   return assess(store,cipher,tenant,subject,minimumAge,context.timeZone(),true);
  }));
 }

 /**
  * "N anos ou mais" na data civil do fuso, dentro da transação do chamador (mesma regra da conferência da gestão).
  * strictKey=false: data protegida sem a chave configurada vira UNKNOWN (nega) em vez de 503.
  */
 public static AgeModels.AgeCheck assess(AgeStore store,BirthDateCipher cipher,UUID tenant,UUID subject,int minimumAge,java.time.ZoneId zone,boolean strictKey) {
  LocalDate today=LocalDate.now(zone);
  var row=store.current(tenant,subject);
  if(row==null) return new AgeModels.AgeCheck(subject,minimumAge,AgeRule.Outcome.UNKNOWN.name(),today,zone.getId(),null,null);
  AgeRule.Outcome outcome;
  if("AGE_PREDICATE_ATTESTATION".equals(row.method())) {
   // Limite superior atestado envelhece: só conclui "não atende" enquanto ainda for comprovável na data avaliada.
   LocalDate attestedOn=row.verifiedAt().toInstant().atZone(zone).toLocalDate();
   outcome=AgeRule.fromAttestation(row.minimumAge(),row.maximumAge(),row.predicate(),minimumAge,attestedOn,today);
  }
  else if(!strictKey && (cipher==null || !cipher.available())) outcome=AgeRule.Outcome.UNKNOWN;
  else {
   var rule=AgeRule.LeapDayRule.valueOf(store.settings(tenant).leapDayRule());
   outcome=AgeRule.fromBirthDate(cipher.decrypt(row.ciphertext(),tenant,subject,row.id()),today,rule,minimumAge);
  }
  return new AgeModels.AgeCheck(subject,minimumAge,outcome.name(),today,zone.getId(),row.id(),row.validUntil().toInstant());
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("AGE_EVIDENCE_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23514".equals(state) || "23503".equals(state)) throw new BusinessProblem("AGE_EVIDENCE_INVALID",422,"A evidência não atende às regras.");
   if("23505".equals(state)) throw new BusinessProblem("AGE_EVIDENCE_CONFLICT",409,"Cadastro alterado ao mesmo tempo. Tente de novo.");
   throw new BusinessProblem("AGE_EVIDENCE_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem cipherUnavailable() {
  return new BusinessProblem("AGE_EVIDENCE_KEY_UNAVAILABLE",503,"A proteção da data de nascimento não está configurada.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("ACCESS_SUBJECT_NOT_FOUND",404,"Cadastro de acesso não encontrado neste condomínio."); }
}
