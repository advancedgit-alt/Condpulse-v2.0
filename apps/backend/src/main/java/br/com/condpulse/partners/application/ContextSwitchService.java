package br.com.condpulse.partners.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.partners.infrastructure.PartnerStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

/**
 * R4.4 (PRD complementar §4.2): entrar no painel de um papel de parceiro grava CONTEXT_SWITCHED no Log de Segurança de
 * cada condomínio afetado, na mesma transação da conferência, só para papéis de segurança ({@link #SECURITY_ROLES}).
 * <ul>
 * <li>Destino: papel ativo da conta no parceiro da rota (PartnerTransactionExecutor) e condomínios alcançados agora por
 * esse papel (platform.partner_reach); foco num condomínio fora do alcance é recusado (403 PARTNER_ACCESS_DENIED).</li>
 * <li>Origem: contexto declarado pela web. Quando é papel de segurança de parceiro, os condomínios que ele ainda alcança
 * também recebem o evento (fim da visão anterior). O banco (V052) confere o alcance do lado gravado em cada linha.</li>
 * <li>details só com origem, destino e lado (identificadores, nunca nome ou dado pessoal); autoria, papel, parceiro e
 * horários vêm do banco (V050).</li>
 * <li>Idempotência: o id de cada evento deriva da conta, da chave da troca (switchId), do conteúdo e do condomínio. A
 * repetição (duplo clique, nova tentativa depois de falha de rede) bate na chave primária, a transação inteira é desfeita
 * e a resposta é ALREADY_RECORDED; contexto igual ao anterior não é troca (NOT_REQUIRED).</li>
 * <li>CP_SECURITY_LOG_ENABLED=false: confere tudo e responde LOG_DISABLED sem gravar, e a web segue.</li>
 * </ul>
 * Não autoriza nada: cada consulta do painel continua conferida por requisição e as leituras sensíveis gravam
 * SENSITIVE_VIEWED; um cliente que não chame esta rota só deixa de registrar a própria troca.
 */
public class ContextSwitchService {
 /** Papéis de parceiro com capacidade security.* ou ops.* (§4.4). TECHNICIAN fica fora (V052). */
 public static final Set<String> SECURITY_ROLES=Set.of("PARTNER_ADMIN","SECURITY_LEAD","SECURITY_SUPERVISOR","REMOTE_OPERATOR");
 static final Set<String> KINDS=Set.of("ENTRY","PLATFORM","TENANT","PARTNER");
 private static final Pattern SESSION=Pattern.compile("[A-Za-z0-9._:-]{8,128}");
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final PartnerTransactionExecutor partners;
 private final PartnerStore store;
 private final boolean logEnabled;

 public ContextSwitchService(PartnerTransactionExecutor partners,PartnerStore store,boolean logEnabled) {
  this.partners=Objects.requireNonNull(partners); this.store=Objects.requireNonNull(store); this.logEnabled=logEnabled;
 }

 record Input(UUID switchId,PartnerModels.ContextRef to,PartnerModels.ContextRef from) {}

 public PartnerModels.ContextSwitchResult record(UUID actor,AssuranceLevel assurance,String sessionId,UUID partnerId,
   PartnerModels.ContextSwitchRequest request) {
  var input=input(partnerId,request);
  String session=sessionId!=null && SESSION.matcher(sessionId).matches()?sessionId:null;
  try {
   return partners.execute(actor,partnerId,assurance,context->apply(actor,context,input,session));
  } catch(BusinessProblem problem) {
   throw problem;
  } catch(DataAccessException error) {
   if(duplicate(error)) return new PartnerModels.ContextSwitchResult("ALREADY_RECORDED",0);
   String state=SqlStates.of(error);
   // Alcance perdido entre a conferência e a gravação (contrato encerrado, escala cancelada): nada foi gravado.
   if("42501".equals(state)) throw denied();
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }

 PartnerModels.ContextSwitchResult apply(UUID actor,PartnerContext context,Input input,String session) {
  var to=input.to(); var from=input.from();
  if(!context.roles().contains(to.role()))
   throw new BusinessProblem("PARTNER_ACCESS_DENIED",403,"Este papel não está ativo para a sua conta neste parceiro.");
  boolean toSecurity=SECURITY_ROLES.contains(to.role());
  boolean fromSecurity="PARTNER".equals(from.kind()) && SECURITY_ROLES.contains(from.role());
  // Papéis de segurança exigem AAL2 (R4.3) antes de qualquer consulta de alcance.
  if((toSecurity || fromSecurity || to.tenantId()!=null) && context.assurance()!=AssuranceLevel.AAL2)
   throw new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator de autenticação para continuar.");
  List<UUID> toTenants=List.of();
  if(toSecurity || to.tenantId()!=null) {
   toTenants=store.reach(to.partnerId(),to.role());
   if(to.tenantId()!=null) {
    if(!toTenants.contains(to.tenantId())) throw denied();
    toTenants=List.of(to.tenantId());
   }
  }
  if(!toSecurity && !fromSecurity || sameContext(from,to)) return new PartnerModels.ContextSwitchResult("NOT_REQUIRED",0);
  if(!logEnabled) return new PartnerModels.ContextSwitchResult("LOG_DISABLED",0);
  List<UUID> fromTenants=List.of();
  if(fromSecurity) {
   fromTenants=store.reach(from.partnerId(),from.role());
   if(from.tenantId()!=null) fromTenants=fromTenants.contains(from.tenantId())?List.of(from.tenantId()):List.of();
  }
  var sides=sides(toSecurity?toTenants:List.of(),fromTenants);
  if(sides.isEmpty()) return new PartnerModels.ContextSwitchResult("NO_TENANT_REACHED",0);
  // Ordem fixa das chaves: o mesmo pedido gera o mesmo texto (e o mesmo id) em qualquer instância.
  var described=new LinkedHashMap<String,Object>();
  described.put("from",describe(from)); described.put("to",describe(to));
  String content=json(described);
  var rows=new ArrayList<PartnerStore.ContextSwitchRow>();
  for(var entry:sides.entrySet()) {
   String side=entry.getValue();
   var subject="FROM".equals(side)?from:to;
   var details=new LinkedHashMap<String,Object>();
   details.put("from",describe(from)); details.put("to",describe(to)); details.put("side",side);
   rows.add(new PartnerStore.ContextSwitchRow(eventId(actor,input.switchId(),content,entry.getKey()),entry.getKey(),source(subject.role()),
    subject.partnerId(),json(details)));
  }
  store.recordContextSwitch(rows,session);
  return new PartnerModels.ContextSwitchResult("RECORDED",rows.size());
 }

 /** Lado de cada condomínio, na ordem do id em texto (mesma do PostgreSQL): as cabeças do log travam sempre na mesma ordem. */
 static TreeMap<UUID,String> sides(List<UUID> toTenants,List<UUID> fromTenants) {
  var sides=new TreeMap<UUID,String>(Comparator.comparing(UUID::toString));
  for(UUID tenant:toTenants) sides.put(tenant,"TO");
  for(UUID tenant:fromTenants) sides.merge(tenant,"FROM",(current,added)->"BOTH");
  return sides;
 }

 static boolean sameContext(PartnerModels.ContextRef from,PartnerModels.ContextRef to) {
  return Objects.equals(from.kind(),to.kind()) && Objects.equals(from.partnerId(),to.partnerId()) && Objects.equals(from.role(),to.role())
   && Objects.equals(from.tenantId(),to.tenantId());
 }

 /** Id determinístico: a mesma troca repetida gera o mesmo id em cada condomínio e não grava de novo. */
 static UUID eventId(UUID actor,UUID switchId,String content,UUID tenant) {
  return UUID.nameUUIDFromBytes(("cpx-context-switched|"+actor+"|"+switchId+"|"+content+"|"+tenant).getBytes(StandardCharsets.UTF_8));
 }

 static String source(String role) { return "PARTNER_ADMIN".equals(role)?"MANAGEMENT":"REMOTE_CENTRAL"; }

 /** Contexto em details: só tipo e identificadores, sem campos nulos (forma fechada conferida pela V052). */
 static Map<String,Object> describe(PartnerModels.ContextRef context) {
  var map=new LinkedHashMap<String,Object>();
  map.put("kind",context.kind());
  if(context.partnerId()!=null) map.put("partnerId",context.partnerId().toString());
  if(context.role()!=null) map.put("role",context.role());
  if(context.tenantId()!=null) map.put("tenantId",context.tenantId().toString());
  return map;
 }

 static Input input(UUID partnerId,PartnerModels.ContextSwitchRequest request) {
  if(partnerId==null || request==null || request.switchId()==null) throw invalid("Informe a chave da troca de contexto.");
  if(request.role()==null || !PartnerCapability.ROLES.contains(request.role())) throw invalid("Papel de parceiro desconhecido.");
  var from=request.from()==null?new PartnerModels.ContextRef("ENTRY",null,null,null):request.from();
  if(from.kind()==null || !KINDS.contains(from.kind())) throw invalid("Contexto de origem desconhecido.");
  boolean valid=switch(from.kind()) {
   case "ENTRY","PLATFORM" -> from.partnerId()==null && from.role()==null && from.tenantId()==null;
   case "TENANT" -> from.tenantId()!=null && from.partnerId()==null && from.role()==null;
   default -> from.partnerId()!=null && from.role()!=null && PartnerCapability.ROLES.contains(from.role());
  };
  if(!valid) throw invalid("Contexto de origem incompleto.");
  return new Input(request.switchId(),new PartnerModels.ContextRef("PARTNER",partnerId,request.role(),request.tenantId()),from);
 }

 static boolean duplicate(Throwable error) {
  if(!"23505".equals(SqlStates.of(error))) return false;
  for(Throwable cause=error;cause!=null;cause=cause.getCause())
   if(cause.getMessage()!=null && cause.getMessage().contains("security_log_pkey")) return true;
  return false;
 }

 private static String json(Object value) { return JSON.writeValueAsString(value); }
 private static BusinessProblem invalid(String message) { return new BusinessProblem("CONTEXT_SWITCH_INVALID",400,message); }
 private static BusinessProblem denied() {
  return new BusinessProblem("PARTNER_ACCESS_DENIED",403,"Condomínio fora do contrato do parceiro ou da sua escala vigente.");
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("CONTEXT_SWITCH_UNAVAILABLE",503,"Não foi possível registrar a troca de contexto. Tente novamente.");
 }
}
