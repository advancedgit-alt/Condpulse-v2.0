import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isUUID, oneOf, record } from './client';
import type { ISODateTime, UUID } from './types';

/**
 * Caixa interna da unidade (parte do CP-035; oferta da fila de espera do CP-040 só para quem entrou na fila). AVAILABLE é "disponível na caixa"; READ_IN_APP só existe depois da
 * abertura autenticada pelo destinatário. O aviso não traz dado do registro (nome do visitante, descrição): a tela leva
 * à página que consulta o detalhe com as permissões de quem abre. resourceState é a situação atual do registro.
 */
export type NotificationKind = 'ARRIVAL_WAITING' | 'PARCEL_RECEIVED' | 'WAITLIST_OFFERED' | 'REQUEST_UPDATED' | 'MOVE_DECIDED' | 'STAY_OVERDUE';
export type NotificationState = 'AVAILABLE' | 'READ_IN_APP';
const arrivalStates = ['WAITING', 'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED'] as const;
const parcelStates = ['RECEIVED', 'DELIVERED'] as const;
const offerStates = ['OFFERED', 'ACCEPTED', 'LEFT', 'EXPIRED', 'CLOSED'] as const;
const requestStates = ['OPEN', 'TRIAGED', 'IN_PROGRESS', 'WAITING_RESIDENT', 'RESOLVED', 'CLOSED'] as const;
const moveStates = ['REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED'] as const;
const statesOf: Record<NotificationKind, readonly string[]> = { ARRIVAL_WAITING: arrivalStates, PARCEL_RECEIVED: parcelStates, WAITLIST_OFFERED: offerStates,
  REQUEST_UPDATED: requestStates, MOVE_DECIDED: moveStates, STAY_OVERDUE: ['OPEN', 'CLOSED', 'VOIDED'] };
export interface InboxNotification {
  notificationId: UUID; unitId: UUID; kind: NotificationKind; resourceId: UUID; resourceState: string | null; state: NotificationState;
  createdAt: ISODateTime; readAt: ISODateTime | null;
}
export interface InboxPage { items: InboxNotification[]; unread: number }

export function inboxNotification(value: unknown, uncertain = false): InboxNotification {
  if (!record(value) || !isUUID(value.notificationId) || !isUUID(value.unitId) || !oneOf(value.kind, ['ARRIVAL_WAITING', 'PARCEL_RECEIVED', 'WAITLIST_OFFERED', 'REQUEST_UPDATED', 'MOVE_DECIDED', 'STAY_OVERDUE'])
      || !isUUID(value.resourceId) || !oneOf(value.state, ['AVAILABLE', 'READ_IN_APP']) || !isDate(value.createdAt)
      || !(value.readAt === null || isDate(value.readAt)) || (value.state === 'READ_IN_APP') !== (value.readAt !== null)
      || !(value.resourceState === null || oneOf(value.resourceState, statesOf[value.kind])))
    throw invalidResponse(uncertain);
  return { notificationId: value.notificationId, unitId: value.unitId, kind: value.kind, resourceId: value.resourceId,
    resourceState: value.resourceState as string | null, state: value.state, createdAt: value.createdAt, readAt: value.readAt as string | null };
}
function inboxPage(value: unknown): InboxPage {
  if (!record(value) || !Array.isArray(value.items) || value.items.length > 50 || !isInteger(value.unread)) throw invalidResponse();
  const items = value.items.map((item) => inboxNotification(item));
  // A contagem cobre todos os avisos da unidade; nunca menos que os não abertos desta página.
  if (value.unread < items.filter((item) => item.state === 'AVAILABLE').length) throw invalidResponse();
  return { items, unread: value.unread };
}

export function createNotificationsApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  return {
    unit: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/notifications`, inboxPage, signal ? { signal } : {}),
    /** Abertura autenticada: única forma de marcar leitura; repetir devolve a leitura já gravada. */
    open: async (tenantId: string, notificationId: string) =>
      client.call(`${base(tenantId)}/notifications/${identifier(notificationId)}/open`, (value) => {
        const opened = inboxNotification(value, true);
        if (opened.notificationId !== notificationId || opened.state !== 'READ_IN_APP') throw invalidResponse(true);
        return opened;
      }, { method: 'POST' }),
  };
}
export const notifications = createNotificationsApi();
