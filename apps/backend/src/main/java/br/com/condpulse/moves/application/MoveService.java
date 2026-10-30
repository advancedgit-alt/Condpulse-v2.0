package br.com.condpulse.moves.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.moves.infrastructure.MoveStore;
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
 * Mudança e entrega de móveis (CP-042). A gestão configura as regras exibidas e decide; a unidade (adulto) pede o período
 * aceitando a versão vigente das regras e pode retirar o pedido antes do início; a portaria vê as aprovadas. O banco
 * confere as regras no fuso do condomínio; a aprovação não reserva elevador nem área.
 */
@Service @Profile("api")
public class MoveService {
 static final Set<String> KINDS=Set.of("MOVE_IN","MOVE_OUT","FURNITURE_DELIVERY");
 static final Set<String> STATES=Set.of("REQUESTED","APPROVED","REJECTED","CANCELLED");
 private final TenantTransactionExecutor transactions;
 private final MoveStore store;
 private final AuditRecorder audit;
 public MoveService(TenantTransactionExecutor transactions,MoveStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 /** Regras vigentes: qualquer vínculo vigente do condomínio (unidade, gestão ou portaria). */
 public MoveModels.CurrentRules rules(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->new MoveModels.CurrentRules(store.rules(tenant)));
 }
 public MoveModels.Rules updateRules(UUID actor,UUID tenant,AssuranceLevel assurance,MoveModels.RulesUpdate request) {
  if(request==null) throw invalid("MOVE_RULES_INVALID","Informe as regras de mudança.");
  String text=DisplayText.normalizeMultiline(request.rulesText(),2000);
  if(text==null) throw invalid("MOVE_RULES_TEXT_INVALID","Descreva as regras com até 2000 caracteres.");
  var days=request.weekDays()==null?List.<Integer>of():request.weekDays().stream().distinct().sorted().toList();
  if(days.isEmpty() || days.size()!=request.weekDays().size() || days.stream().anyMatch(d->d==null || d<1 || d>7))
   throw invalid("MOVE_RULES_DAYS_INVALID","Escolha de 1 a 7 dias da semana, sem repetir.");
  LocalTime start,end;
  try { start=LocalTime.parse(request.startTime()); end=LocalTime.parse(request.endTime()); }
  catch(DateTimeParseException | NullPointerException invalidTime) { throw invalid("MOVE_RULES_TIME_INVALID","Informe o horário no formato HH:MM."); }
  if(!start.isBefore(end) || start.getSecond()!=0 || end.getSecond()!=0)
   throw invalid("MOVE_RULES_TIME_INVALID","O fim do horário precisa ser depois do início.");
  int notice=request.minNoticeHours()==null?-1:request.minNoticeHours();
  int duration=request.maxDurationMinutes()==null?-1:request.maxDurationMinutes();
  if(notice<0 || notice>720) throw invalid("MOVE_RULES_NOTICE_INVALID","A antecedência mínima vai de 0 a 720 horas.");
  if(duration<30 || duration>720) throw invalid("MOVE_RULES_DURATION_INVALID","A duração máxima vai de 30 a 720 minutos.");
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_REQUESTS);
   boolean saved=request.expectedVersion()==null
    ?store.insertRules(tenant,actor,text,days,start.toString(),end.toString(),notice,duration)
    :store.updateRules(tenant,actor,request.expectedVersion(),text,days,start.toString(),end.toString(),notice,duration);
   if(!saved) throw new BusinessProblem("MOVE_RULES_CHANGED",409,"As regras foram alteradas por outra pessoa. Atualize antes de salvar.");
   var rules=store.rules(tenant);
   audit.record(tenant,actor,"MOVE_RULES_UPDATED","MOVE_RULES",tenant,null);
   return rules;
  });
 }

 public MoveModels.Page unitMoves(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   requireAdult(context,unit);
   return store.unitMoves(tenant,unit);
  });
 }
 public MoveModels.Mutation create(UUID actor,UUID tenant,UUID unit,String key,MoveModels.Create request) {
  requireKey(key);
  if(request==null || request.kind()==null || !KINDS.contains(request.kind()))
   throw invalid("MOVE_KIND_INVALID","Escolha mudança de entrada, de saída ou entrega de móveis.");
  if(request.startsAt()==null || request.endsAt()==null || !request.startsAt().isBefore(request.endsAt())
     || request.startsAt().getEpochSecond()%60!=0 || request.endsAt().getEpochSecond()%60!=0
     || request.startsAt().getNano()!=0 || request.endsAt().getNano()!=0)
   throw invalid("MOVE_WINDOW_INVALID","Informe início e fim, em minutos exatos, com o fim depois do início.");
  if(request.acceptedRulesVersion()==null || request.acceptedRulesVersion()<1)
   throw invalid("MOVE_RULES_REQUIRED","Leia e aceite as regras de mudança do condomínio.");
  String provider=DisplayText.normalize(request.providerName(),160);
  if(provider==null || provider.codePointCount(0,provider.length())<2)
   throw invalid("MOVE_PROVIDER_INVALID","Informe o nome do responsável ou da transportadora, com 2 a 160 caracteres.");
  String plate=request.vehiclePlate()==null || DisplayText.collapse(request.vehiclePlate()).isEmpty()?null
   :DisplayText.collapse(request.vehiclePlate()).replace("-","").replace(" ","").toUpperCase(Locale.ROOT);
  if(plate!=null && !plate.matches("[A-Z0-9]{5,8}")) throw invalid("MOVE_PLATE_INVALID","Informe a placa com 5 a 8 letras e números.");
  String items=request.items()==null || DisplayText.collapse(request.items()).isEmpty()?null:DisplayText.normalize(request.items(),500);
  if(request.items()!=null && !DisplayText.collapse(request.items()).isEmpty() && items==null)
   throw invalid("MOVE_ITEMS_INVALID","Descreva os itens com até 500 caracteres.");
  String hash=digest("MOVE|"+unit+"|"+request.kind()+"|"+request.startsAt()+"|"+request.endsAt()+"|"+provider.length()+":"+provider+"|"+plate+"|"
   +(items==null?"-":items.length()+":"+items)+"|"+request.acceptedRulesVersion());
  String route="/v1/tenants/"+tenant+"/units/"+unit+"/moves";
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   requireAdult(context,unit);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.move(tenant,previous.resource(),false);
    if(replay==null) throw notFound();
    return new MoveModels.Mutation(replay,true,previous.responseStatus());
   }
   var rules=store.rules(tenant);
   if(rules==null) throw new BusinessProblem("MOVE_RULES_NOT_CONFIGURED",409,"A gestão ainda não configurou as regras de mudança.");
   if(rules.version()!=request.acceptedRulesVersion())
    throw new BusinessProblem("MOVE_RULES_CHANGED",409,"As regras de mudança foram alteradas. Leia a nova versão antes de pedir.");
   Instant now=store.now();
   if(request.startsAt().isBefore(now.plus(Duration.ofHours(rules.minNoticeHours()))))
    throw new BusinessProblem("MOVE_WINDOW_OUTSIDE_RULES",400,"O pedido precisa de pelo menos "+rules.minNoticeHours()+" horas de antecedência.");
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,unit,actor,request,provider,plate,items);
   var created=store.move(tenant,id,false);
   if(created==null) throw new IllegalStateException("Pedido de mudança não encontrado na mesma transação.");
   audit.record(tenant,actor,"MOVE_REQUESTED","MOVE_REQUEST",id,null);
   store.event(tenant,"MOVE_REQUESTED",created);
   store.finish(tenant,actor,route,key,id,201);
   return new MoveModels.Mutation(created,false,201);
  });
 }
 /** Unidade retira antes do início; gestão cancela com nota até o fim do período. */
 public MoveModels.Move cancel(UUID actor,UUID tenant,AssuranceLevel assurance,UUID moveId,MoveModels.Cancel request) {
  if(moveId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw versionRequired();
  return execute(actor,tenant,assurance,context->{
   boolean manager=context.effectiveCapabilities().contains(StaffCapability.MANAGE_REQUESTS);
   var current=store.move(tenant,moveId,true);
   if(current==null || !manager && !adultOf(context,current.unitId())) throw notFound();
   String note=null;
   if(manager && !adultOf(context,current.unitId())) {
    note=DisplayText.normalize(request.note(),500);
    if(note==null) throw invalid("MOVE_NOTE_REQUIRED","Informe o motivo do cancelamento, com até 500 caracteres.");
   }
   if("CANCELLED".equals(current.state())) return current;
   if(!"REQUESTED".equals(current.state()) && !"APPROVED".equals(current.state())) throw closed();
   if(current.version()!=request.expectedVersion()) throw changed();
   Instant now=store.now();
   if(!manager && !now.isBefore(current.startsAt()))
    throw new BusinessProblem("MOVE_CANCELLATION_CLOSED",409,"O período já começou. Fale com a gestão.");
   if(!store.cancel(tenant,moveId,request.expectedVersion(),actor,note)) throw changed();
   var cancelled=store.move(tenant,moveId,false);
   audit.record(tenant,actor,"MOVE_CANCELLED","MOVE_REQUEST",moveId,note);
   store.event(tenant,"MOVE_CANCELLED",cancelled);
   return cancelled;
  });
 }

 public MoveModels.Page managed(UUID actor,UUID tenant,AssuranceLevel assurance,String state) {
  if(state!=null && !STATES.contains(state)) throw invalid("MOVE_STATE_INVALID","Situação de mudança inválida.");
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_REQUESTS);
   return store.managed(tenant,state);
  });
 }
 public MoveModels.Move decide(UUID actor,UUID tenant,AssuranceLevel assurance,UUID moveId,MoveModels.Decision request) {
  if(moveId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw versionRequired();
  boolean approve="APPROVE".equals(request.decision());
  if(!approve && !"REJECT".equals(request.decision())) throw invalid("MOVE_DECISION_INVALID","Informe APPROVE ou REJECT.");
  String note=request.note()==null || DisplayText.collapse(request.note()).isEmpty()?null:DisplayText.normalize(request.note(),500);
  if(request.note()!=null && !DisplayText.collapse(request.note()).isEmpty() && note==null)
   throw invalid("MOVE_NOTE_INVALID","Use até 500 caracteres na nota.");
  if(!approve && note==null) throw invalid("MOVE_NOTE_REQUIRED","Informe o motivo da recusa.");
  String target=approve?"APPROVED":"REJECTED";
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_REQUESTS);
   var current=store.move(tenant,moveId,true);
   if(current==null) throw notFound();
   if(target.equals(current.state()) && current.version()==request.expectedVersion()+1) return current;
   if(!"REQUESTED".equals(current.state())) throw closed();
   if(current.version()!=request.expectedVersion()) throw changed();
   if(!store.now().isBefore(current.startsAt()))
    throw new BusinessProblem("MOVE_DECISION_CLOSED",409,"O período já começou: o pedido não pode mais ser decidido.");
   if(!store.decide(tenant,moveId,request.expectedVersion(),target,actor,note)) throw changed();
   var decided=store.move(tenant,moveId,false);
   audit.record(tenant,actor,approve?"MOVE_APPROVED":"MOVE_REJECTED","MOVE_REQUEST",moveId,note);
   store.event(tenant,approve?"MOVE_APPROVED":"MOVE_REJECTED",decided);
   return decided;
  });
 }
 /** Portaria: aprovadas que se sobrepõem ao intervalo (até 14 dias; padrão: as próximas 24 horas). */
 public MoveModels.Page concierge(UUID actor,UUID tenant,AssuranceLevel assurance,Instant from,Instant until) {
  if((from==null)!=(until==null) || from!=null && (!from.isBefore(until) || Duration.between(from,until).compareTo(Duration.ofDays(14))>0))
   throw invalid("MOVE_RANGE_INVALID","Informe um intervalo de até 14 dias.");
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   Instant start=from==null?store.now():from;
   return store.approvedBetween(tenant,start,until==null?start.plus(Duration.ofHours(24)):until);
  });
 }

 private static boolean adultOf(TenantContext context,UUID unit) {
  return unit!=null && context.memberships().stream().anyMatch(m->unit.equals(m.unitId()) && ("RESIDENT".equals(m.role()) || "RESPONSIBLE".equals(m.role())));
 }
 private static void requireAdult(TenantContext context,UUID unit) {
  if(!adultOf(context,unit)) throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
 }
 private static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 private static String digest(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("MOVE_NOT_FOUND",404,"Pedido de mudança não encontrado."); }
 private static BusinessProblem versionRequired() { return invalid("MOVE_VERSION_REQUIRED","Atualize o pedido antes de continuar."); }
 private static BusinessProblem changed() { return new BusinessProblem("MOVE_CHANGED",409,"O pedido foi alterado. Atualize antes de continuar."); }
 private static BusinessProblem closed() { return new BusinessProblem("MOVE_CLOSED",409,"Este pedido já foi decidido ou encerrado."); }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,assurance,action); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23514".equals(state)) throw new BusinessProblem("MOVE_WINDOW_OUTSIDE_RULES",400,"O período não atende às regras de mudança do condomínio.");
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw new BusinessProblem("MOVE_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  } catch(TransactionException error) {
   throw new BusinessProblem("MOVE_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  }
 }
}
