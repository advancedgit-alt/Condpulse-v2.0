package br.com.condpulse.stays.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.partners.application.PartnerCapability;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.stays.infrastructure.StayStore;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Permanências (CPX-015 a CPX-019, V055). Painel "quem está agora" e fechamento do dia pela portaria (concierge.operate)
 * ou pela equipe do parceiro com alcance (security.command, ops.shift.supervise, ops.remote.operate), sempre com AAL2 e
 * SENSITIVE_VIEWED na mesma transação; justificativa com motivo pelos mesmos papéis; confirmação de saída pelo morador
 * adulto da unidade (o banco confere); regra de tempo pela gestão (policy.edit, AAL2). O banco aplica as mesmas regras.
 */
@Service @Profile("api")
public class StayService {
 /** Capacidades de parceiro que leem e justificam permanências, em ordem estável. */
 static final List<PartnerCapability> PARTNER_READERS=List.of(PartnerCapability.SECURITY_COMMAND,PartnerCapability.SUPERVISE_SHIFT,
  PartnerCapability.OPERATE_REMOTE);
 private final TenantTransactionExecutor transactions;
 private final StayStore store;
 private final VisitStore visits;
 private final SensitiveViewRecorder views;
 public StayService(TenantTransactionExecutor transactions,StayStore store,VisitStore visits,SensitiveViewRecorder views) {
  this.transactions=transactions; this.store=store; this.visits=visits; this.views=Objects.requireNonNull(views);
 }

 /** CPX-017: quem está no condomínio agora (permanências abertas), vencidas primeiro. */
 public StayModels.StayPage open(UUID actor,UUID tenant,AssuranceLevel assurance,boolean overdueOnly) {
  return execute(actor,tenant,assurance,context->{
   String source=requireStaff(context);
   var rows=store.open(tenant,overdueOnly,StayModels.PAGE+1);
   var stays=rows.stream().limit(StayModels.PAGE).toList();
   views.record(tenant,source,"STAYS_NOW",null,null,null,Map.of("count",stays.size(),"overdueOnly",overdueOnly,
    "overdue",stays.stream().filter(StayModels.Stay::overdue).count()));
   return new StayModels.StayPage(stays,rows.size()>StayModels.PAGE,store.now());
  });
 }

 /** CPX-019: entradas do dia (fuso do condomínio) sem saída registrada até o fim do dia; hoje e até 31 dias atrás. */
 public StayModels.DayClose dayClose(UUID actor,UUID tenant,AssuranceLevel assurance,LocalDate date) {
  return execute(actor,tenant,assurance,context->{
   String source=requireStaff(context);
   ZoneId zone=context.timeZone();
   LocalDate today=store.now().atZone(zone).toLocalDate();
   LocalDate day=date==null?today:date;
   if(day.isAfter(today) || day.isBefore(today.minusDays(StayModels.DAY_CLOSE_DAYS)))
    throw new BusinessProblem("STAY_DAY_CLOSE_DATE_INVALID",400,"Escolha hoje ou um dia dos últimos "+StayModels.DAY_CLOSE_DAYS+" dias.");
   var from=day.atStartOfDay(zone).toInstant();
   var until=day.plusDays(1).atStartOfDay(zone).toInstant();
   var rows=store.withoutExit(tenant,from,until,StayModels.DAY_CLOSE_LIMIT+1);
   var entries=rows.stream().limit(StayModels.DAY_CLOSE_LIMIT).toList();
   int stillOpen=(int)entries.stream().filter(stay->"OPEN".equals(stay.state())).count();
   String hash=listHash(day,entries);
   // Evidência (E1.7): a consulta fica no Log de Segurança com o dia, a contagem e o hash da lista entregue.
   views.record(tenant,source,"STAY_DAY_CLOSE",null,null,null,Map.of("date",day.toString(),"count",entries.size(),"stillOpen",stillOpen,
    "listHash",hash));
   return new StayModels.DayClose(day,zone.getId(),from,until,store.now(),entries,rows.size()>StayModels.DAY_CLOSE_LIMIT,stillOpen,hash);
  });
 }

 /** Justificativa (§8.1, passo 5): encerra a escalada de alertas; a permanência continua aberta até a saída. */
 public StayModels.Mutation justify(UUID actor,UUID tenant,AssuranceLevel assurance,UUID stay,StayModels.Justify request) {
  String reason=request==null?null:DisplayText.normalize(request.reason(),500);
  if(reason==null || reason.codePointCount(0,reason.length())<3)
   throw new BusinessProblem("STAY_JUSTIFICATION_REQUIRED",400,"Informe o motivo da justificativa, com 3 a 500 caracteres.");
  if(stay==null) throw notFound();
  return execute(actor,tenant,assurance,context->{
   requireStaff(context);
   if(store.justify(tenant,stay,actor,reason)==1) return new StayModels.Mutation(require(store.find(tenant,stay)),false);
   var current=store.find(tenant,stay);
   if(current==null) throw notFound();
   if(current.justified() && reason.equals(current.justification()) && store.justifiedBy(tenant,stay,actor))
    return new StayModels.Mutation(current,true);
   if(current.justified()) throw new BusinessProblem("STAY_ALREADY_JUSTIFIED",409,"Esta permanência já foi justificada. Atualize a lista.");
   throw notOpen();
  });
 }

 /** Permanências abertas da unidade (adulto ou dependente com permissão de decidir visitas), sem anotações da equipe. */
 public StayModels.StayPage unitOpen(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   if(unit==null || !visits.member(tenant,unit,"decide")) throw unitDenied();
   var rows=store.unitOpen(tenant,unit,StayModels.PAGE+1);
   return new StayModels.StayPage(rows.stream().limit(StayModels.PAGE).map(StayModels.Stay::forUnit).toList(),rows.size()>StayModels.PAGE,store.now());
  });
 }

 /** CPX-018: o morador adulto da unidade confirma que a pessoa saiu; gera STAY_CLOSED (app do morador). */
 public StayModels.Mutation confirmExit(UUID actor,UUID tenant,UUID unit,UUID stay) {
  if(stay==null) throw notFound();
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{
   if(unit==null || !visits.member(tenant,unit,null)) throw new BusinessProblem("STAY_CONFIRMATION_DENIED",403,
    "Só o morador ou responsável da unidade confirma a saída.");
   if(store.confirmExit(tenant,unit,stay,actor)==1) return new StayModels.Mutation(require(store.find(tenant,stay)).forUnit(),false);
   var current=store.find(tenant,stay);
   if(current==null || !unit.equals(current.unitId())) throw notFound();
   if("RESIDENT_CONFIRMED".equals(current.closure()) && store.closedBy(tenant,stay,actor)) return new StayModels.Mutation(current.forUnit(),true);
   throw notOpen();
  });
 }

 public StayModels.Rules rules(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   return new StayModels.Rules(store.rules(tenant));
  });
 }
 /** Regra de tempo do tipo no condomínio (RULE_CHANGED no log pelo banco). */
 public StayModels.Rules changeRule(UUID actor,UUID tenant,AssuranceLevel assurance,String kind,StayModels.RuleChange change) {
  if(kind==null || !StayModels.KIND_SET.contains(kind)) throw new BusinessProblem("STAY_KIND_INVALID",400,"Tipo de permanência inválido.");
  if(change==null || change.maxMinutes()==null || change.l1AfterMinutes()==null || change.l2AfterMinutes()==null
     || change.maxMinutes()<5 || change.maxMinutes()>1440 || change.l1AfterMinutes()<0 || change.l1AfterMinutes()>720
     || change.l2AfterMinutes()<=change.l1AfterMinutes() || change.l2AfterMinutes()>1440)
   throw new BusinessProblem("STAY_RULE_INVALID",400,"Tempo máximo de 5 a 1.440 minutos; N1 de 0 a 720; N2 maior que N1 e até 1.440.");
  String starts=null,ends=null;
  if(Boolean.TRUE.equals(change.scheduleEnabled())) {
   try {
    LocalTime from=LocalTime.parse(change.startsLocal()); LocalTime until=LocalTime.parse(change.endsLocal());
    if(!from.isBefore(until) || from.getSecond()!=0 || until.getSecond()!=0 || from.getNano()!=0 || until.getNano()!=0) throw new IllegalArgumentException();
    starts=from.toString(); ends=until.toString();
   } catch(DateTimeParseException | NullPointerException error) { throw invalidSchedule(); }
   catch(IllegalArgumentException error) { throw invalidSchedule(); }
  } else if(change.startsLocal()!=null || change.endsLocal()!=null) {
   if(change.scheduleEnabled()==null) throw invalidSchedule();
  }
  final String start=starts,end=ends;
  return execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.EDIT_POLICIES);
   int changed=change.version()==null
    ?store.createRule(tenant,kind,change.maxMinutes(),change.l1AfterMinutes(),change.l2AfterMinutes(),actor,change.enabled(),change.scheduleEnabled(),start,end)
    :store.updateRule(tenant,kind,change.maxMinutes(),change.l1AfterMinutes(),change.l2AfterMinutes(),change.version(),actor,change.enabled(),change.scheduleEnabled(),start,end);
   if(changed!=1) throw new BusinessProblem("STAY_RULE_VERSION_CONFLICT",409,"A regra mudou desde a leitura. Atualize antes de salvar.");
   return new StayModels.Rules(store.rules(tenant));
  });
 }

 /**
  * Equipe que lê e justifica permanências, com AAL2: portaria do condomínio ou parceiro com alcance. Devolve a origem da
  * consulta para o Log de Segurança.
  */
 static String requireStaff(TenantContext context) {
  if(context.grantedCapabilities().contains(StaffCapability.OPERATE_CONCIERGE)) {
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE); return "CONCIERGE";
  }
  for(var capability:PARTNER_READERS) {
   if(context.grantedPartnerCapabilities().contains(capability)) { context.requirePartnerCapability(capability); return "REMOTE_CENTRAL"; }
  }
  context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
  return "CONCIERGE";
 }
 /** Hash da lista entregue no fechamento: dia e ids na ordem do relatório (sem dado pessoal). */
 static String listHash(LocalDate day,List<StayModels.Stay> entries) {
  var text=new StringBuilder("cpstay1|").append(day);
  for(var stay:entries) text.append('|').append(stay.stayId());
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
 private static StayModels.Stay require(StayModels.Stay stay) {
  if(stay==null) throw new IllegalStateException("Permanência alterada não encontrada na mesma transação.");
  return stay;
 }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,action);
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("STAY_FORBIDDEN",403,"Esta ação não está disponível para o seu vínculo.");
   if("23514".equals(state) || "23505".equals(state)) throw new BusinessProblem("STAY_STATE_CHANGED",409,"A permanência mudou. Atualize a lista antes de repetir.");
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }
 private static BusinessProblem invalidSchedule() { return new BusinessProblem("STAY_SCHEDULE_INVALID",400,
  "Informe início e fim no formato HH:mm, no mesmo dia, com início anterior ao fim."); }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("STAY_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
 }
 private static BusinessProblem notFound() { return new BusinessProblem("STAY_NOT_FOUND",404,"Permanência não encontrada."); }
 private static BusinessProblem notOpen() { return new BusinessProblem("STAY_NOT_OPEN",409,"Esta permanência não está mais aberta. Atualize a lista."); }
 private static BusinessProblem unitDenied() { return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação."); }
}
