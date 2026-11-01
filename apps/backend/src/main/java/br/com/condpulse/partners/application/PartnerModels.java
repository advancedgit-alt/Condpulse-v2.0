package br.com.condpulse.partners.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Parceiro, equipe, contrato parceiro × condomínio e escala (V049). Nenhum campo pessoal além do nome de exibição. */
public final class PartnerModels {
 private PartnerModels() {}
 public record Partner(UUID id,String name,String status,long version,Instant createdAt,Instant updatedAt) {}
 public record PartnerPage(List<Partner> partners) { public PartnerPage { partners=List.copyOf(partners); } }
 public record Member(UUID id,UUID partnerId,UUID accountId,String role,String displayName,String status,long version,
   Instant createdAt,Instant revokedAt,String revokeReason) {}
 public record Contract(UUID id,UUID partnerId,UUID tenantId,String tenantName,String service,String status,long version,
   Instant startsAt,Instant endsAt,Instant endedAt,String endReason) {}
 public record Schedule(UUID id,UUID partnerId,UUID tenantId,UUID memberId,UUID accountId,String status,long version,
   Instant startsAt,Instant endsAt,Instant cancelledAt) {}
 public record PartnerDetail(Partner partner,List<Member> members,List<Contract> contracts) {
  public PartnerDetail { members=List.copyOf(members); contracts=List.copyOf(contracts); }
 }
 public record MemberPage(UUID partnerId,List<Member> members) { public MemberPage { members=List.copyOf(members); } }
 public record ContractPage(UUID partnerId,List<Contract> contracts) { public ContractPage { contracts=List.copyOf(contracts); } }
 public record SchedulePage(UUID partnerId,List<Schedule> schedules) { public SchedulePage { schedules=List.copyOf(schedules); } }

 public record CreatePartner(String name,String reason) {}
 /** ACTIVE ou SUSPENDED pela versão lida; pedido igual ao estado atual devolve applied=false sem gravar. */
 public record ChangePartnerStatus(String status,Long expectedVersion,String reason) {}
 public record PartnerChange(boolean applied,Partner partner) {}
 /** Sem início, vale a partir de agora (relógio do banco). Fim previsto opcional. */
 public record StartContract(UUID tenantId,Instant startsAt,Instant endsAt,String reason) {}
 public record Reason(String reason) {}
 public record AddMember(UUID accountId,String role,String displayName,String reason) {}
 public record CreateSchedule(UUID memberId,UUID tenantId,Instant startsAt,Instant endsAt,String reason) {}
 /**
  * Contexto da web na troca (R4.4): ENTRY (sem contexto anterior nesta janela), PLATFORM (central), TENANT (condomínio
  * por vínculo) ou PARTNER (painel de um papel de parceiro, opcionalmente focado num condomínio). Só identificadores.
  */
 public record ContextRef(String kind,UUID partnerId,String role,UUID tenantId) {}
 /** Entrada no painel do papel role do parceiro da rota; switchId é a chave da troca (repetição não grava de novo). */
 public record ContextSwitchRequest(UUID switchId,String role,UUID tenantId,ContextRef from) {}
 /**
  * RECORDED (recorded eventos gravados), ALREADY_RECORDED (mesma troca já gravada), NOT_REQUIRED (nenhum lado é papel de
  * segurança ou o contexto não mudou), NO_TENANT_REACHED (papel de segurança sem condomínio alcançado agora) ou
  * LOG_DISABLED (CP_SECURITY_LOG_ENABLED=false: conferido, nada gravado).
  */
 public record ContextSwitchResult(String status,int recorded) {}
}
