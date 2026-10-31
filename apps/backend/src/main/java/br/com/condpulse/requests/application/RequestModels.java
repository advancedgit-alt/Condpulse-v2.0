package br.com.condpulse.requests.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Pedidos da unidade (CP-033, sem anexos). Andamento: OPEN (enviado), TRIAGED (em análise), IN_PROGRESS (em execução),
 * WAITING_RESIDENT (aguardando a unidade), RESOLVED (concluído) e CLOSED (encerrado, final). Mensagem da unidade é
 * sempre visível; a equipe escreve mensagem visível ou comentário interno, que nunca chega à unidade.
 */
public final class RequestModels {
 private RequestModels() {}
 public static final List<String> CATEGORIES=List.of("MAINTENANCE","CLEANING","SECURITY","NOISE","ADMINISTRATIVE","OTHER");
 public static final List<String> STATES=List.of("OPEN","TRIAGED","IN_PROGRESS","WAITING_RESIDENT","RESOLVED","CLOSED");

 // Unidade.
 public record Open(String category,String title,String description) {}
 public record UnitMessage(String body) {}
 /** Mensagem: na visão da unidade, autor da equipe aparece sem nome (authorName null) e nunca há comentário interno. */
 public record Message(UUID messageId,String authorRole,String authorName,boolean mine,String visibility,String body,Instant createdAt) {}
 /** Mensagens anteriores a uma já exibida, em ordem de conversa; truncated indica que há outras ainda mais antigas. */
 public record MessagePage(List<Message> messages,boolean truncated) {
  public MessagePage { messages=List.copyOf(messages); }
 }
 public record UnitSummary(UUID requestId,UUID unitId,String category,String title,String state,boolean assigned,LocalDate dueOn,
   Instant createdAt,Instant updatedAt) {}
 public record UnitPage(List<UnitSummary> requests,boolean truncated) {
  public UnitPage { requests=List.copyOf(requests); }
 }
 public record UnitRequest(UUID requestId,UUID unitId,String category,String title,String description,String state,boolean assigned,
   LocalDate dueOn,String resolution,String requesterName,boolean openedByMe,Instant createdAt,Instant updatedAt,Instant resolvedAt,
   Instant closedAt,List<Message> messages,boolean messagesTruncated) {
  public UnitRequest { messages=List.copyOf(messages); }
 }
 public record OpenResult(UnitRequest request,boolean replayed,int status) {}
 public record MessageResult(Message message,boolean replayed,int status) {}

 // Gestão.
 public record StaffMessage(String body,String visibility) {}
 /** Atualização pela versão lida: andamento (pode repetir o atual), responsável da equipe (null tira), prazo e resolução. */
 public record Update(String state,UUID assigneeMembershipId,LocalDate dueOn,String resolution,Long expectedVersion) {}
 public record TeamMember(UUID membershipId,String name,String role) {}
 public record ManagedSummary(UUID requestId,UUID unitId,String unitLabel,String category,String title,String state,
   UUID assigneeMembershipId,String assigneeName,LocalDate dueOn,Instant createdAt,Instant updatedAt,long version) {}
 public record ManagedPage(List<ManagedSummary> requests,boolean truncated) {
  public ManagedPage { requests=List.copyOf(requests); }
 }
 public record ManagedRequest(UUID requestId,UUID unitId,String unitLabel,String category,String title,String description,String state,
   UUID assigneeMembershipId,String assigneeName,LocalDate dueOn,String resolution,String requesterName,Instant createdAt,
   Instant updatedAt,Instant resolvedAt,Instant closedAt,long version,List<Message> messages,boolean messagesTruncated,
   List<TeamMember> team) {
  public ManagedRequest { messages=List.copyOf(messages); team=List.copyOf(team); }
 }

 // Zeladoria (V045): pedidos atribuídos ao próprio vínculo, sem nome de quem abriu nem equipe.
 public record MaintenanceSummary(UUID requestId,UUID unitId,String unitLabel,String category,String title,String state,LocalDate dueOn,
   Instant updatedAt,long version) {}
 public record MaintenancePage(List<MaintenanceSummary> requests,boolean truncated) {
  public MaintenancePage { requests=List.copyOf(requests); }
 }
 public record MaintenanceRequest(UUID requestId,UUID unitId,String unitLabel,String category,String title,String description,String state,
   LocalDate dueOn,String resolution,Instant createdAt,Instant updatedAt,Instant resolvedAt,long version,List<Message> messages,
   boolean messagesTruncated) {
  public MaintenanceRequest { messages=List.copyOf(messages); }
 }
 /** Andamento pela versão lida: em execução, aguardando a unidade ou concluído (com resolução). */
 public record MaintenanceUpdate(String state,String resolution,Long expectedVersion) {}
 public static final List<String> MAINTENANCE_STATES=List.of("IN_PROGRESS","WAITING_RESIDENT","RESOLVED");
}
