package br.com.condpulse.announcements.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import br.com.condpulse.announcements.infrastructure.AnnouncementStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Avisos da gestão (CP-032). A gestão (capacidade announcements.publish, AAL2) cria rascunho, edita pela versão lida,
 * publica para o condomínio, blocos ou unidades e arquiva; publicado não muda de texto (para corrigir, arquivar e
 * publicar outro). A unidade vê só o publicado para o próprio público e recebe o texto apenas na abertura autenticada,
 * registrada uma vez — abertura não é ciência jurídica. O banco aplica as mesmas regras.
 */
@Service @Profile("api")
public class AnnouncementService {
 static final Set<String> AUDIENCES=Set.of("TENANT","BLOCKS","UNITS");
 static final int MAX_TARGETS=200;
 private final TenantTransactionExecutor transactions;
 private final AnnouncementStore store;
 private final AuditRecorder audit;
 public AnnouncementService(TenantTransactionExecutor transactions,AnnouncementStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 // Gestão.
 public AnnouncementModels.ManagedPage managed(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return staff(actor,tenant,assurance,Map.of(),context->store.managed(tenant));
 }
 public AnnouncementModels.DraftMutation createDraft(UUID actor,UUID tenant,AssuranceLevel assurance,String key,AnnouncementModels.Draft request) {
  requireKey(key);
  if(request==null) throw invalid("ANNOUNCEMENT_INPUT_INVALID","Informe o título, o texto e o público do aviso.");
  var content=content(request.title(),request.body(),request.audience(),request.audienceIds());
  String hash=AnnouncementRequestHash.draft(content.title(),content.body(),content.audience(),content.ids());
  String route="/v1/tenants/"+tenant+"/management/announcements";
  return staff(actor,tenant,assurance,Map.of("23503",AnnouncementService::targetsInvalid),context->{
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.find(tenant,previous.resource());
    if(replay==null) throw notFound();
    return new AnnouncementModels.DraftMutation(replay,true,previous.responseStatus());
   }
   requireTargets(tenant,content);
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,content.title(),content.body(),content.audience(),content.ids(),actor);
   audit.record(tenant,actor,"ANNOUNCEMENT_DRAFTED","ANNOUNCEMENT",id,null);
   store.finish(tenant,actor,route,key,id,201);
   return new AnnouncementModels.DraftMutation(require(store.find(tenant,id)),false,201);
  });
 }
 public AnnouncementModels.ManagedAnnouncement editDraft(UUID actor,UUID tenant,AssuranceLevel assurance,UUID announcementId,
     AnnouncementModels.EditDraft request) {
  if(announcementId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("ANNOUNCEMENT_VERSION_REQUIRED","Atualize os avisos antes de salvar.");
  var content=content(request.title(),request.body(),request.audience(),request.audienceIds());
  return staff(actor,tenant,assurance,Map.of("23503",AnnouncementService::targetsInvalid,"23514",AnnouncementService::changed),context->{
   var current=store.find(tenant,announcementId);
   if(current==null) throw notFound();
   if(!"DRAFT".equals(current.state())) throw notDraft();
   requireTargets(tenant,content);
   if(current.version()!=request.expectedVersion() || !store.editDraft(tenant,announcementId,request.expectedVersion(),content.title(),
     content.body(),content.audience(),content.ids())) throw changed();
   audit.record(tenant,actor,"ANNOUNCEMENT_EDITED","ANNOUNCEMENT",announcementId,null);
   return require(store.find(tenant,announcementId));
  });
 }
 public AnnouncementModels.ManagedAnnouncement publish(UUID actor,UUID tenant,AssuranceLevel assurance,UUID announcementId,
     AnnouncementModels.Publish request) {
  if(announcementId==null) throw notFound();
  long expected=version(request==null?null:request.expectedVersion());
  return staff(actor,tenant,assurance,Map.of("23503",AnnouncementService::targetsInvalid,"23514",AnnouncementService::changed),context->{
   var current=store.find(tenant,announcementId);
   if(current==null) throw notFound();
   if(!"DRAFT".equals(current.state())) throw notDraft();
   if(current.version()!=expected || !store.transition(tenant,announcementId,expected,"DRAFT","PUBLISHED")) throw changed();
   var published=require(store.find(tenant,announcementId));
   audit.record(tenant,actor,"ANNOUNCEMENT_PUBLISHED","ANNOUNCEMENT",announcementId,null);
   store.event(tenant,"ANNOUNCEMENT_PUBLISHED",announcementId,published.version(),published.audience());
   return published;
  });
 }
 /** Arquiva o publicado (sai da unidade; aberturas continuam registradas) ou descarta o rascunho. */
 public AnnouncementModels.ManagedAnnouncement archive(UUID actor,UUID tenant,AssuranceLevel assurance,UUID announcementId,
     AnnouncementModels.Archive request) {
  if(announcementId==null) throw notFound();
  long expected=version(request==null?null:request.expectedVersion());
  String reason=request==null || request.reason()==null || DisplayText.collapse(request.reason()).isEmpty()?null
   :text(request.reason(),500,"ANNOUNCEMENT_REASON_INVALID","Use até 500 caracteres no motivo.");
  return staff(actor,tenant,assurance,Map.of("23514",AnnouncementService::changed),context->{
   var current=store.find(tenant,announcementId);
   if(current==null) throw notFound();
   if("ARCHIVED".equals(current.state())) throw new BusinessProblem("ANNOUNCEMENT_ARCHIVED",409,"Este aviso já foi arquivado.");
   if(current.version()!=expected || !store.transition(tenant,announcementId,expected,current.state(),"ARCHIVED")) throw changed();
   var archived=require(store.find(tenant,announcementId));
   audit.record(tenant,actor,"ANNOUNCEMENT_ARCHIVED","ANNOUNCEMENT",announcementId,reason);
   store.event(tenant,"ANNOUNCEMENT_ARCHIVED",announcementId,archived.version(),archived.audience());
   return archived;
  });
 }

 // Unidade.
 public AnnouncementModels.UnitPage unitAnnouncements(UUID actor,UUID tenant,UUID unit) {
  return resident(actor,tenant,context->{
   if(unit==null || !store.unitMember(tenant,unit)) throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
   return store.unitAnnouncements(tenant,unit);
  });
 }
 /** Abertura autenticada pela própria conta: devolve o texto e registra a primeira abertura (repetir não muda o horário). */
 public AnnouncementModels.OpenedAnnouncement open(UUID actor,UUID tenant,UUID announcementId) {
  if(announcementId==null) throw notFound();
  return resident(actor,tenant,context->{
   if(!store.visiblePublished(tenant,announcementId)) throw notFound();
   var opened=store.open(tenant,announcementId);
   if(opened==null) throw notFound();
   return opened;
  });
 }

 private record Content(String title,String body,String audience,List<UUID> ids) {}
 private static Content content(String title,String body,String audience,List<UUID> ids) {
  String normalizedTitle=text(title,160,"ANNOUNCEMENT_TITLE_INVALID","Informe o título do aviso, de 3 a 160 caracteres.");
  if(normalizedTitle.codePointCount(0,normalizedTitle.length())<3)
   throw invalid("ANNOUNCEMENT_TITLE_INVALID","Informe o título do aviso, de 3 a 160 caracteres.");
  String normalizedBody=DisplayText.normalizeMultiline(body,5000);
  if(normalizedBody==null) throw invalid("ANNOUNCEMENT_BODY_INVALID","Informe o texto do aviso, com até 5.000 caracteres e sem caracteres invisíveis.");
  if(audience==null || !AUDIENCES.contains(audience)) throw invalid("ANNOUNCEMENT_AUDIENCE_INVALID","Escolha o público: condomínio, blocos ou unidades.");
  List<UUID> targets=ids==null?List.of():ids;
  if(targets.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(targets).size()!=targets.size())
   throw invalid("ANNOUNCEMENT_AUDIENCE_INVALID","Público com bloco ou unidade repetido ou vazio.");
  if("TENANT".equals(audience) ? !targets.isEmpty() : targets.isEmpty() || targets.size()>MAX_TARGETS)
   throw invalid("ANNOUNCEMENT_AUDIENCE_INVALID","Para blocos ou unidades, escolha de 1 a 200; para o condomínio inteiro, nenhum.");
  return new Content(normalizedTitle,normalizedBody,audience,targets.stream().sorted().toList());
 }
 private void requireTargets(UUID tenant,Content content) {
  if(!content.ids().isEmpty() && store.existingTargets(tenant,content.audience(),content.ids())!=content.ids().size()) throw targetsInvalid();
 }
 private static long version(Long value) {
  if(value==null || value<0) throw invalid("ANNOUNCEMENT_VERSION_REQUIRED","Atualize os avisos antes de continuar.");
  return value;
 }
 private static String text(String value,int maximum,String code,String message) {
  String normalized=DisplayText.normalize(value,maximum);
  if(normalized==null) throw invalid(code,message);
  return normalized;
 }
 private static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 private static AnnouncementModels.ManagedAnnouncement require(AnnouncementModels.ManagedAnnouncement value) {
  if(value==null) throw new IllegalStateException("Aviso gravado não foi encontrado na mesma transação.");
  return value;
 }
 private <T> T staff(UUID actor,UUID tenant,AssuranceLevel assurance,Map<String,Supplier<BusinessProblem>> conflicts,Function<TenantContext,T> action) {
  return execute(actor,tenant,assurance,conflicts,context->{
   context.requireCapability(StaffCapability.PUBLISH_ANNOUNCEMENTS);
   return action.apply(context);
  });
 }
 /** Unidade em AAL1: nenhuma capacidade de equipe é publicada, e o banco aplica só o público do aviso. */
 private <T> T resident(UUID actor,UUID tenant,Function<TenantContext,T> action) {
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),action);
 }
 /**
  * Constraints, gatilhos e políticas abortam a transação; traduzir somente depois do rollback do executor. Prazo da
  * transação esgotado é resultado incerto (503).
  */
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Map<String,Supplier<BusinessProblem>> conflicts,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,action);
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   var mapped=state==null?null:conflicts.get(state);
   if(mapped!=null) throw mapped.get();
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("ANNOUNCEMENT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
 }
 private static BusinessProblem notFound() { return new BusinessProblem("ANNOUNCEMENT_NOT_FOUND",404,"Aviso não encontrado."); }
 private static BusinessProblem notDraft() {
  return new BusinessProblem("ANNOUNCEMENT_NOT_DRAFT",409,"Somente rascunho pode ser alterado ou publicado. Para corrigir um aviso publicado, arquive e publique outro.");
 }
 private static BusinessProblem changed() {
  return new BusinessProblem("ANNOUNCEMENT_CHANGED",409,"O aviso foi alterado. Atualize a lista antes de continuar.");
 }
 private static BusinessProblem targetsInvalid() {
  return new BusinessProblem("ANNOUNCEMENT_TARGETS_INVALID",400,"Algum bloco ou unidade do público não existe neste condomínio.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
