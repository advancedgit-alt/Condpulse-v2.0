package br.com.condpulse.announcements.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Avisos da gestão (CP-032). Texto simples, sem HTML. Público: TENANT (condomínio), BLOCKS ou UNITS com os
 * identificadores. Estados: DRAFT, PUBLISHED, ARCHIVED. "opens" conta aberturas autenticadas registradas — abertura
 * não é ciência jurídica nem confirmação de recebimento.
 */
public final class AnnouncementModels {
 private AnnouncementModels() {}
 public record ManagedAnnouncement(UUID announcementId,String title,String body,String audience,List<UUID> audienceIds,String state,
   boolean authoredByMe,Instant createdAt,Instant updatedAt,Instant publishedAt,Instant archivedAt,long version,long opens) {
  public ManagedAnnouncement { audienceIds=List.copyOf(audienceIds); }
 }
 public record ManagedPage(List<ManagedAnnouncement> announcements,boolean truncated) {
  public ManagedPage { announcements=List.copyOf(announcements); }
 }
 public record Draft(String title,String body,String audience,List<UUID> audienceIds) {}
 public record EditDraft(String title,String body,String audience,List<UUID> audienceIds,Long expectedVersion) {}
 public record Publish(Long expectedVersion) {}
 public record Archive(Long expectedVersion,String reason) {}
 public record DraftMutation(ManagedAnnouncement announcement,boolean replayed,int status) {}
 /** Lista da unidade: sem o corpo; o texto só vem com a abertura autenticada. */
 public record UnitAnnouncement(UUID announcementId,String title,Instant publishedAt,Instant openedAt) {}
 public record UnitPage(List<UnitAnnouncement> announcements,boolean truncated) {
  public UnitPage { announcements=List.copyOf(announcements); }
 }
 public record OpenedAnnouncement(UUID announcementId,String title,String body,Instant publishedAt,Instant openedAt) {}
}
