package br.com.condpulse.accessgrants.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessgrants.infrastructure.GrantStore;
import br.com.condpulse.accessgrants.infrastructure.PublicationStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Publicação dos direitos por destino (AC-012/AC-013, V045). A gestão com vínculos e publicação (AAL2) compila, sob o lock
 * por condomínio, o documento de cada sujeito em cada equipamento; o banco grava só o que mudou, com versão monotônica, e
 * supera o trabalho anterior em aberto. Revogações são publicadas pelo banco na mesma transação da marca. Nenhum
 * estado de aplicação sai daqui: ACK, aplicado sem comprovação, confirmado, parcial, falha e desconhecido vêm só do
 * executor. Recusa do banco num sujeito é registrada por item e não desfaz os demais.
 */
public class PublicationService {
 public static final int MAX_SUBJECTS=2000,MAX_REQUESTED=500,PAGE=500,SAMPLE=50;
 private final TenantTransactionExecutor tenants;
 private final PublicationStore store;
 private final GrantStore grants;
 private final AuditRecorder audit;
 public PublicationService(TenantTransactionExecutor tenants,PublicationStore store,GrantStore grants,AuditRecorder audit) {
  this.tenants=tenants; this.store=store; this.grants=grants; this.audit=audit;
 }

 public PublicationModels.Plan plan(UUID actor,UUID tenant,AssuranceLevel assurance,PublicationModels.PlanRequest request) {
  List<UUID> requested=request==null || request.subjectIds()==null?null:request.subjectIds();
  if(requested!=null && (requested.isEmpty() || requested.size()>MAX_REQUESTED || requested.stream().anyMatch(java.util.Objects::isNull)))
   throw new BusinessProblem("PUBLICATION_SUBJECTS_INVALID",400,"Informe de 1 a "+MAX_REQUESTED+" sujeitos, ou nenhum para todos.");
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   context.requireCapability(StaffCapability.PUBLISH_POLICIES);
   grants.lock(tenant);
   List<UUID> subjects;
   if(requested==null) {
    // Acima do limite a publicação é recusada (nunca truncada em silêncio).
    if(store.countSubjects(tenant)>MAX_SUBJECTS)
     throw new BusinessProblem("PUBLICATION_TOO_MANY_SUBJECTS",409,"Mais de "+MAX_SUBJECTS+" sujeitos: publique por lista de sujeitos.");
    subjects=store.subjects(tenant,MAX_SUBJECTS);
   } else {
    var unique=List.copyOf(new LinkedHashSet<>(requested));
    subjects=store.existingSubjects(tenant,unique);
    if(subjects.size()!=unique.size()) throw new BusinessProblem("ACCESS_SUBJECT_NOT_FOUND",404,"Não encontrado neste condomínio.");
   }
   UUID operation=UUID.randomUUID();
   var result=planSubjects(tenant,operation,subjects,actor);
   audit.record(tenant,actor,"RIGHTS_PUBLICATION_PLANNED","ACCESS_PUBLICATION",operation,"sujeitos="+result.subjects()+" trabalhos="+result.created()
    +" recusados="+result.failedByReason().values().stream().mapToInt(Integer::intValue).sum());
   return result;
  }));
 }

 /**
  * Compila os sujeitos na transação corrente (lock por condomínio já obtido e capacidades conferidas pelo chamador).
  * Cada sujeito num savepoint: recusa do banco (vigência, tamanho, concorrência) fica registrada por item.
  */
 public PublicationModels.Plan planSubjects(UUID tenant,UUID operation,Collection<UUID> subjects,UUID actor) {
  int created=0;
  var failures=new ArrayList<PublicationModels.Failure>();
  for(UUID subject:subjects) {
   try { created+=store.item(()->store.planSubject(tenant,operation,subject,actor)); }
   catch(DataAccessException error) {
    String state=SqlStates.of(error);
    if("42501".equals(state)) throw error;
    failures.add(new PublicationModels.Failure(subject,"23514".equals(state) || "23503".equals(state)?"DB_REFUSED":"DB_CONFLICT"));
    if(!"23514".equals(state) && !"23503".equals(state) && !"23505".equals(state)) throw error;
   }
  }
  var byReason=new LinkedHashMap<String,Integer>();
  for(var f:failures) byReason.merge(f.reason(),1,Integer::sum);
  return new PublicationModels.Plan(operation,subjects.size(),created,Map.copyOf(byReason),failures.stream().limit(SAMPLE).toList());
 }

 /** Item num savepoint da transação corrente (usado pela derivação para registrar recusas por direito). */
 public <T> T item(Supplier<T> action) { return store.item(action); }

 public PublicationModels.PublicationPage publications(UUID actor,UUID tenant,AssuranceLevel assurance,UUID operation,UUID subject,UUID device) {
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
   var rows=store.publications(tenant,operation,subject,device,PAGE+1);
   var counts=store.countByState(tenant,operation,subject,device);
   int total=counts.values().stream().mapToInt(Integer::intValue).sum();
   // Superado não encerra nada: cada destino tocado responde pelo trabalho mais novo dele, mesmo de outra operação.
   int[] current=store.currentDestinations(tenant,operation,subject,device);
   var summary=new PublicationModels.Summary(Map.copyOf(counts),total,current[0],current[1],current[0]>0 && current[1]==current[0]);
   return new PublicationModels.PublicationPage(rows.stream().limit(PAGE).toList(),summary,rows.size()>PAGE);
  }));
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("PUBLICATION_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23505".equals(state) || "40001".equals(state) || "40P01".equals(state))
    throw new BusinessProblem("PUBLICATION_CONFLICT",409,"Publicação alterada ao mesmo tempo. Tente de novo.");
   throw new BusinessProblem("PUBLICATION_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
}
