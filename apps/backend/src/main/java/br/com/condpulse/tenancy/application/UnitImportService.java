package br.com.condpulse.tenancy.application;

import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.infrastructure.UnitImportStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Importação de blocos e unidades em staging (CP-016): gestor com vínculo vigente e AAL2 (units.manage). Abrir o lote
 * só grava a prévia; o mesmo arquivo devolve o mesmo lote vivo. Aprovar cria o que falta, numa transação, com trilha;
 * lote com linha inválida ou repetida exige aceite explícito do parcial. Repetir a aprovação não cria nada.
 */
@Service @Profile("api")
public class UnitImportService {
 private final TenantTransactionExecutor transactions;
 private final UnitImportStore store;
 private final AuditRecorder audit;
 public UnitImportService(TenantTransactionExecutor transactions,UnitImportStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 public UnitImportModels.BatchPage recent(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->new UnitImportModels.BatchPage(store.recent(tenant)));
 }
 public UnitImportModels.Detail detail(UUID actor,UUID tenant,AssuranceLevel assurance,UUID batchId) {
  if(batchId==null) throw notFound();
  return execute(actor,tenant,assurance,context->{
   var batch=store.find(tenant,batchId,false);
   if(batch==null) throw notFound();
   return new UnitImportModels.Detail(batch,store.rows(tenant,batchId));
  });
 }

 public UnitImportModels.Opened open(UUID actor,UUID tenant,AssuranceLevel assurance,UnitImportModels.Open request) {
  if(request==null) throw invalid("IMPORT_FILE_EMPTY","Envie o arquivo CSV.");
  String source=fileName(request.fileName());
  UnitCsvParser.Parsed parsed;
  try { parsed=UnitCsvParser.parse(request.content()); }
  catch(UnitCsvParser.FileProblem problem) { throw invalid(problem.code(),problem.getMessage()); }
  try {
   return execute(actor,tenant,assurance,context->{
    var live=store.liveByHash(tenant,parsed.fileHash());
    if(live!=null) return new UnitImportModels.Opened(new UnitImportModels.Detail(live,store.rows(tenant,live.batchId())),false);
    UUID id=store.insert(tenant,source,parsed);
    audit.record(tenant,actor,"IMPORT_BATCH_OPENED","IMPORT_BATCH",id,null);
    var batch=store.find(tenant,id,false);
    return new UnitImportModels.Opened(new UnitImportModels.Detail(batch,store.rows(tenant,id)),true);
   });
  } catch(BusinessProblem problem) {
   // Outro envio do mesmo arquivo venceu a corrida: o lote vivo dele é o resultado.
   if(!"IMPORT_BATCH_RACE".equals(problem.code())) throw problem;
   return execute(actor,tenant,assurance,context->{
    var live=store.liveByHash(tenant,parsed.fileHash());
    if(live==null) throw new BusinessProblem("IMPORT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível abrir o lote. Tente novamente.");
    return new UnitImportModels.Opened(new UnitImportModels.Detail(live,store.rows(tenant,live.batchId())),false);
   });
  }
 }

 public UnitImportModels.Decided approve(UUID actor,UUID tenant,AssuranceLevel assurance,UUID batchId,UnitImportModels.Approve request) {
  if(batchId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("IMPORT_VERSION_REQUIRED","Informe a versão lida do lote.");
  boolean acceptPartial=Boolean.TRUE.equals(request.acceptPartial());
  return execute(actor,tenant,assurance,context->{
   // Trava do condomínio antes da do lote (mesma ordem em toda aprovação): lotes diferentes com a mesma unidade em
   // caixa diferente não criam duas unidades equivalentes.
   store.lockTenantImports(tenant);
   var batch=store.find(tenant,batchId,true);
   if(batch==null) throw notFound();
   if("APPROVED".equals(batch.state()))
    return new UnitImportModels.Decided(new UnitImportModels.Detail(batch,store.rows(tenant,batchId)),false);
   if("DISCARDED".equals(batch.state()))
    throw new BusinessProblem("IMPORT_BATCH_DISCARDED",409,"Este lote foi descartado. Envie o arquivo de novo para uma nova prévia.");
   if(batch.version()!=request.expectedVersion()) throw changed();
   boolean partial=batch.invalidRows()+batch.duplicateRows()>0;
   if(partial && !acceptPartial)
    throw new BusinessProblem("IMPORT_PARTIAL_NOT_ACCEPTED",409,
     "O lote tem linhas com erro ou repetidas. Corrija o arquivo ou confirme que aprova só as linhas válidas.");
   var blocks=store.createMissingBlocks(tenant,batchId);
   var units=store.createMissingUnits(tenant,batchId);
   int applied=store.applyRows(tenant,batchId,units);
   if(applied!=batch.validRows()) throw new IllegalStateException("Linhas válidas não aplicadas na mesma transação.");
   if(!store.approve(tenant,batchId,batch.version(),partial,units.size(),batch.validRows()-units.size(),blocks.size())) throw changed();
   String reason="Importação de unidades "+batchId;
   store.auditCreated(tenant,actor,"BLOCK_CREATED","BLOCK",blocks,reason);
   store.auditCreated(tenant,actor,"UNIT_CREATED","UNIT",units,reason);
   audit.record(tenant,actor,partial?"IMPORT_BATCH_APPROVED_PARTIAL":"IMPORT_BATCH_APPROVED","IMPORT_BATCH",batchId,null);
   return new UnitImportModels.Decided(new UnitImportModels.Detail(store.find(tenant,batchId,false),store.rows(tenant,batchId)),true);
  });
 }

 public UnitImportModels.Decided discard(UUID actor,UUID tenant,AssuranceLevel assurance,UUID batchId,UnitImportModels.Discard request) {
  if(batchId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("IMPORT_VERSION_REQUIRED","Informe a versão lida do lote.");
  return execute(actor,tenant,assurance,context->{
   var batch=store.find(tenant,batchId,true);
   if(batch==null) throw notFound();
   if("DISCARDED".equals(batch.state()))
    return new UnitImportModels.Decided(new UnitImportModels.Detail(batch,store.rows(tenant,batchId)),false);
   if("APPROVED".equals(batch.state()))
    throw new BusinessProblem("IMPORT_BATCH_APPROVED",409,"Este lote já foi aprovado e não pode ser descartado.");
   if(batch.version()!=request.expectedVersion() || !store.discard(tenant,batchId,batch.version())) throw changed();
   audit.record(tenant,actor,"IMPORT_BATCH_DISCARDED","IMPORT_BATCH",batchId,null);
   return new UnitImportModels.Decided(new UnitImportModels.Detail(store.find(tenant,batchId,false),store.rows(tenant,batchId)),true);
  });
 }

 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,context->{
    context.requireCapability(StaffCapability.MANAGE_UNITS);
    return action.apply(context);
   });
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23505".equals(state)) throw new BusinessProblem("IMPORT_BATCH_RACE",409,"Outra pessoa da gestão alterou o cadastro ao mesmo tempo. Tente novamente.");
   if("23514".equals(state)) throw changed();
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw new BusinessProblem("IMPORT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a importação. Tente novamente.");
  }
 }
 static String fileName(String value) {
  if(value==null) throw invalid("IMPORT_FILE_NAME_INVALID","Informe o nome do arquivo.");
  String name=value.replace('\\','/');
  name=name.substring(name.lastIndexOf('/')+1).strip();
  if(name.isEmpty() || name.length()>200 || name.chars().anyMatch(Character::isISOControl))
   throw invalid("IMPORT_FILE_NAME_INVALID","O nome do arquivo deve ter até 200 caracteres.");
  return name;
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("IMPORT_BATCH_NOT_FOUND",404,"Lote não encontrado neste condomínio."); }
 private static BusinessProblem changed() {
  return new BusinessProblem("IMPORT_BATCH_CHANGED",409,"O lote ou o cadastro mudou. Atualize a prévia antes de decidir.");
 }
}
