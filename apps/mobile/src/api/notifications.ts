import { ContractError } from './reservations';

/**
 * Caixa interna da unidade no aplicativo (parte do CP-035), validada como na web. AVAILABLE é "disponível na caixa";
 * READ_IN_APP só existe depois da abertura autenticada pelo destinatário. O aviso não traz dado do registro (nome do
 * visitante, descrição): a tela aponta a seção que consulta o detalhe com as permissões de quem abre.
 */
export type NotificationKind = 'ARRIVAL_WAITING' | 'PARCEL_RECEIVED' | 'WAITLIST_OFFERED' | 'REQUEST_UPDATED' | 'MOVE_DECIDED';
export interface InboxNotification {
  notificationId: string; unitId: string; kind: NotificationKind; resourceId: string; resourceState: string | null;
  state: 'AVAILABLE' | 'READ_IN_APP'; createdAt: string; readAt: string | null;
}
export interface InboxPage { items: InboxNotification[]; unread: number }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const arrivalStates = ['WAITING', 'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED'];
const parcelStates = ['RECEIVED', 'DELIVERED'];
const offerStates = ['OFFERED', 'ACCEPTED', 'LEFT', 'EXPIRED', 'CLOSED'];
const statesOf: Record<NotificationKind, string[]> = { ARRIVAL_WAITING: arrivalStates, PARCEL_RECEIVED: parcelStates, WAITLIST_OFFERED: offerStates,
  REQUEST_UPDATED: ['OPEN', 'TRIAGED', 'IN_PROGRESS', 'WAITING_RESIDENT', 'RESOLVED', 'CLOSED'], MOVE_DECIDED: ['REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED'] };
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

export function inboxNotification(value: unknown, unitId: string | null = null): InboxNotification {
  check(record(value) && uuid(value.notificationId) && uuid(value.unitId) && (unitId === null || value.unitId.toLowerCase() === unitId.toLowerCase())
    && typeof value.kind === 'string' && Object.hasOwn(statesOf, value.kind) && uuid(value.resourceId)
    && (value.state === 'AVAILABLE' || value.state === 'READ_IN_APP') && date(value.createdAt) && (value.readAt === null || date(value.readAt))
    && (value.state === 'READ_IN_APP') === (value.readAt !== null)
    && (value.resourceState === null || (typeof value.resourceState === 'string'
      && statesOf[value.kind as NotificationKind].includes(value.resourceState))));
  return { notificationId: value.notificationId as string, unitId: value.unitId as string, kind: value.kind as NotificationKind,
    resourceId: value.resourceId as string, resourceState: value.resourceState as string | null, state: value.state as InboxNotification['state'],
    createdAt: value.createdAt as string, readAt: value.readAt as string | null };
}
export function inboxPage(value: unknown, unitId: string): InboxPage {
  check(record(value) && Array.isArray(value.items) && value.items.length <= 50 && Number.isSafeInteger(value.unread) && (value.unread as number) >= 0);
  const items = (value.items as unknown[]).map((item) => inboxNotification(item, unitId));
  // A contagem cobre todos os avisos da unidade; nunca menos que os não abertos desta página.
  check((value.unread as number) >= items.filter((item) => item.state === 'AVAILABLE').length);
  return { items, unread: value.unread as number };
}
