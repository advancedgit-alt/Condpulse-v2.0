package br.com.condpulse.securitylog.application;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.securitylog.infrastructure.SecurityLogStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Log de Segurança do condomínio (CPX-009 a CPX-011, V050). Busca com filtros e verificação de integridade pela equipe com
 * security.log.read.tenant (gestão local) ou security.log.read (parceiro, publicada pela hierarquia), sempre com AAL2;
 * toda leitura grava SENSITIVE_VIEWED na mesma transação (R5.6). Exportação só com security.log.export e motivo, com
 * manifesto (intervalo de seq, hashes de início e fim, quem exportou) e LOG_EXPORTED na mesma transação (R5.7).
 */
@Service @Profile("api")
public class SecurityLogService {
 private final TenantTransactionExecutor transactions;
 private final SecurityLogStore store;
 public SecurityLogService(TenantTransactionExecutor transactions,SecurityLogStore store) {
  this.transactions=transactions; this.store=store;
 }

 public SecurityLogModels.Page page(UUID actor,UUID tenant,AssuranceLevel assurance,SecurityLogModels.Filter filter) {
  return execute(actor,tenant,assurance,context->{
   boolean partner=requireReader(context);
   var rows=store.page(tenant,filter);
   boolean more=rows.size()>filter.limit();
   var events=rows.stream().limit(filter.limit()).map(e->partner?e:e.maskedForTenant()).toList();
   Long next=more?events.getLast().seq():null;
   var details=new TreeMap<String,Object>(filter.describe());
   details.put("count",events.size());
   // Toda leitura do log pela equipe grava SENSITIVE_VIEWED, independentemente de outras consultas sensíveis (R5.6).
   store.recordView(tenant,"SECURITY_LOG",details);
   return new SecurityLogModels.Page(events,next);
  });
 }

 public SecurityLogModels.Integrity integrity(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->{
   requireReader(context);
   var result=store.verify(tenant);
   store.recordView(tenant,"SECURITY_LOG_INTEGRITY",Map.of("intact",result.intact(),"checkedEvents",result.checkedEvents()));
   return result;
  });
 }

 public SecurityLogModels.Export export(UUID actor,UUID tenant,AssuranceLevel assurance,SecurityLogModels.ExportRequest request) {
  String reason=request==null?null:DisplayText.normalize(request.reason(),500);
  if(reason==null || reason.codePointCount(0,reason.length())<10)
   throw new BusinessProblem("SECURITY_LOG_EXPORT_REASON_REQUIRED",400,"Informe o motivo da exportação (10 a 500 caracteres).");
  if(request.fromSeq()!=null && request.fromSeq()<1 || request.toSeq()!=null && request.toSeq()<1
     || request.fromSeq()!=null && request.toSeq()!=null && request.toSeq()<request.fromSeq())
   throw new BusinessProblem("SECURITY_LOG_EXPORT_RANGE_INVALID",400,"Intervalo de sequência inválido.");
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EXPORT_SECURITY_LOG);
   long last=store.lastSeq(tenant);
   long to=request.toSeq()==null?last:request.toSeq();
   long from=request.fromSeq()==null?Math.max(1,to-SecurityLogModels.MAX_EXPORT+1):request.fromSeq();
   if(last==0 || to>last || from>to)
    throw new BusinessProblem("SECURITY_LOG_EXPORT_RANGE_INVALID",400,"O intervalo pedido não existe no log deste condomínio.");
   if(to-from+1>SecurityLogModels.MAX_EXPORT)
    throw new BusinessProblem("SECURITY_LOG_EXPORT_TOO_LARGE",400,"Exporte no máximo "+SecurityLogModels.MAX_EXPORT+" eventos por vez.");
   var events=store.range(tenant,from,to);
   // Intervalo contíguo e encadeado: um buraco ou elo quebrado não vira arquivo "íntegro".
   if(!SecurityLogModels.linked(events,from,to))
    throw new BusinessProblem("SECURITY_LOG_CHAIN_BROKEN",409,"A cadeia do log está quebrada neste intervalo. Verifique a integridade.");
   UUID eventId=UUID.randomUUID();
   var first=events.getFirst();
   var lastEvent=events.getLast();
   var details=new TreeMap<String,Object>();
   details.put("fromSeq",from); details.put("toSeq",to); details.put("count",events.size());
   details.put("firstPrevHash",first.prevHash()); details.put("firstHash",first.hash()); details.put("lastHash",lastEvent.hash());
   details.put("canonicalization",SecurityLogModels.CANONICALIZATION);
   var exportedAt=store.recordExport(tenant,eventId,reason,details);
   var manifest=new SecurityLogModels.Manifest(tenant,from,to,events.size(),first.prevHash(),first.hash(),lastEvent.hash(),actor,exportedAt,
    reason,eventId,SecurityLogModels.CANONICALIZATION);
   return new SecurityLogModels.Export(manifest,events);
  });
 }

 /** Leitura com AAL2: parceiro (security.log.read) vê tudo; gestão local (read.tenant) sem conta da equipe do parceiro. */
 static boolean requireReader(TenantContext context) {
  var granted=context.grantedCapabilities();
  if(granted.contains(StaffCapability.READ_SECURITY_LOG)) { context.requireCapability(StaffCapability.READ_SECURITY_LOG); return true; }
  context.requireCapability(StaffCapability.READ_SECURITY_LOG_TENANT);
  return false;
 }

 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,action);
  } catch(BusinessProblem problem) {
   throw problem;
  } catch(DataAccessException | TransactionException error) {
   throw new BusinessProblem("SECURITY_LOG_DEPENDENCY_UNAVAILABLE",503,"Não foi possível consultar o log de segurança. Tente novamente.");
  }
 }
}
