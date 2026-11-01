package br.com.condpulse.partners.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.securitylog.application.SecurityLogModels;

/**
 * Comando de Segurança do gestor líder (CPX-012 a CPX-014, V054): visão da carteira com semáforo, incidentes com linha do
 * tempo a partir do Log de Segurança e dossiê, e revisão de aberturas remotas. Só identificadores e o nome de exibição da
 * equipe; nenhum dado de morador.
 */
public final class SecurityCommandModels {
 private SecurityCommandModels() {}

 /** Contagens do condomínio (cp.security_command_summary, V054) mais a escala vigente do parceiro. */
 public record Indicators(int devicesTotal,int devicesOffline,int devicesUnknown,int securityOccurrencesOpen,int criticalAlertsUnlinked,
   int incidentsOpen,int incidentsCriticalOpen,int remoteOpensPendingReview,int operatorsOnDuty) {}
 /** light: RED, AMBER ou GREEN (critério em SecurityCommandRules e docs/PARCEIROS.md); reasons na ordem de gravidade. */
 public record PortfolioTenant(UUID tenantId,String tenantName,String timeZone,String light,List<String> reasons,Indicators indicators) {
  public PortfolioTenant { reasons=List.copyOf(reasons); }
 }
 /** unavailableIndicators: itens do §4.3.4 sem fonte de dados nesta versão (nunca mostrados como zero). */
 public record Portfolio(UUID partnerId,Instant generatedAt,String criteria,List<String> unavailableIndicators,boolean truncated,
   List<PortfolioTenant> tenants) {
  public Portfolio { unavailableIndicators=List.copyOf(unavailableIndicators); tenants=List.copyOf(tenants); }
 }

 public record TeamMember(UUID memberId,UUID accountId,String role,String displayName) {}
 public record Team(UUID partnerId,List<TeamMember> members) { public Team { members=List.copyOf(members); } }

 public record Incident(UUID id,UUID tenantId,String tenantName,UUID partnerId,String title,String severity,String state,UUID openedBy,
   Instant openedAt,UUID assigneeMemberId,Instant assignedAt,String outcome,String report,UUID closedBy,Instant closedAt,long version,
   int linkCount) {}
 public record IncidentPage(UUID partnerId,String state,boolean truncated,List<Incident> incidents) {
  public IncidentPage { incidents=List.copyOf(incidents); }
 }
 public record IncidentLink(UUID eventId,UUID linkedBy,Instant linkedAt) {}
 /** Linha do tempo: eventos do log do incidente (correlation_id) e eventos anexados, em ordem de seq. */
 public record IncidentDetail(Incident incident,List<IncidentLink> links,List<SecurityLogModels.Event> timeline) {
  public IncidentDetail { links=List.copyOf(links); timeline=List.copyOf(timeline); }
 }
 public record OpenIncident(UUID tenantId,String title,String severity,List<UUID> eventIds) {}
 public record AssignIncident(UUID memberId,Long expectedVersion) {}
 public record LinkEvents(List<UUID> eventIds) {}
 public record CloseIncident(String outcome,String report,Long expectedVersion) {}
 public record DossierRequest(String reason) {}
 /**
  * Manifesto do dossiê: eventsDigest = sha256("cpdx1|" + tenantId + "|" + incidentId + "|" + seq:hash de cada evento em
  * ordem, separados por "|"); cada evento traz o hash da cadeia (cpsl1), conferido no banco antes de exportar.
  */
 public record DossierManifest(UUID tenantId,UUID incidentId,int count,long firstSeq,long lastSeq,String eventsDigest,String canonicalization,
   UUID exportedBy,Instant exportedAt,String reason,UUID exportEventId) {}
 public record Dossier(DossierManifest manifest,Incident incident,List<IncidentLink> links,List<SecurityLogModels.Event> events) {
  public Dossier { links=List.copyOf(links); events=List.copyOf(events); }
 }

 public record Review(UUID reviewEventId,String verdict,String note,UUID reviewedBy,Instant reviewedAt) {}
 /** Abertura remota (REMOTE_OPEN no log) com a última revisão, se houver. */
 public record RemoteOpen(UUID eventId,long seq,Instant occurredAt,Instant recordedAt,UUID operatorAccountId,String operatorRole,
   String reason,UUID unitId,UUID pointId,UUID deviceId,String evidenceRef,Review review) {}
 public record RemoteOpenPage(UUID tenantId,List<RemoteOpen> items,Long nextBeforeSeq) {
  public RemoteOpenPage { items=List.copyOf(items); }
 }
 public record ReviewRequest(String verdict,String note,UUID correctsReviewId) {}
}
