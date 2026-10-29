package br.com.condpulse.notifications.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aviso da caixa interna. Sem nome do visitante nem dado do registro: a tela busca o detalhe com as permissões de quem
 * abre. resourceState é a situação atual do registro para quem lê (null quando o registro não está mais visível).
 * AVAILABLE significa disponível na caixa; READ_IN_APP só existe depois da abertura autenticada pelo destinatário.
 */
public final class NotificationModels {
 private NotificationModels() {}
 public record Notification(UUID notificationId,UUID unitId,String kind,UUID resourceId,String resourceState,String state,
   Instant createdAt,Instant readAt) {}
 public record Page(List<Notification> items,int unread) {}
}
