import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isUUID, list, oneOf, record } from './client';
import { isCivilDate, type CivilDate } from './billing';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/**
 * Pedidos da unidade (CP-033, sem anexos). Morador ou responsável abre o pedido e conversa nele; a gestão acompanha,
 * escreve mensagem visível ou comentário interno, define responsável da equipe e prazo comunicado e muda o andamento
 * pela versão lida. Comentário interno nunca chega à unidade; na visão da unidade, a equipe aparece sem nome. Texto
 * simples: a tela nunca interpreta como marcação.
 */
export const requestCategories = ['MAINTENANCE', 'CLEANING', 'SECURITY', 'NOISE', 'ADMINISTRATIVE', 'OTHER'] as const;
export const requestStates = ['OPEN', 'TRIAGED', 'IN_PROGRESS', 'WAITING_RESIDENT', 'RESOLVED', 'CLOSED'] as const;
export type RequestCategory = typeof requestCategories[number];
export type RequestState = typeof requestStates[number];
export type RequestFilter = 'ACTIVE' | 'ALL' | RequestState;
/** Andamentos permitidos a partir de cada um (o mesmo quadro do servidor e do banco). */
export const requestTransitions: Record<RequestState, readonly RequestState[]> = {
  OPEN: ['TRIAGED', 'IN_PROGRESS', 'CLOSED'],
  TRIAGED: ['IN_PROGRESS', 'WAITING_RESIDENT', 'RESOLVED', 'CLOSED'],
  IN_PROGRESS: ['WAITING_RESIDENT', 'RESOLVED', 'CLOSED'],
  WAITING_RESIDENT: ['IN_PROGRESS', 'RESOLVED', 'CLOSED'],
  RESOLVED: ['IN_PROGRESS', 'CLOSED'],
  CLOSED: [],
};

export interface RequestMessage {
  messageId: UUID; authorRole: 'UNIT' | 'STAFF'; authorName: string | null; mine: boolean; visibility: 'RESIDENT' | 'INTERNAL';
  body: string; createdAt: ISODateTime;
}
export interface UnitRequestSummary {
  requestId: UUID; unitId: UUID; category: RequestCategory; title: string; state: RequestState; assigned: boolean; dueOn: CivilDate | null;
  createdAt: ISODateTime; updatedAt: ISODateTime;
}
export interface UnitRequestPage { requests: UnitRequestSummary[]; truncated: boolean }
export interface UnitRequest extends UnitRequestSummary {
  description: string; resolution: string | null; requesterName: string; openedByMe: boolean; resolvedAt: ISODateTime | null;
  closedAt: ISODateTime | null; messages: RequestMessage[]; messagesTruncated: boolean;
}
export interface OpenRequestBody { category: RequestCategory; title: string; description: string }
export interface OpenRequestResult { request: UnitRequest; replayed: boolean }
export interface MessageResult { message: RequestMessage; replayed: boolean }
export interface TeamMember { membershipId: UUID; name: string; role: 'MANAGER' | 'CONCIERGE' }
export interface ManagedRequestSummary {
  requestId: UUID; unitId: UUID; unitLabel: string; category: RequestCategory; title: string; state: RequestState;
  assigneeMembershipId: UUID | null; assigneeName: string | null; dueOn: CivilDate | null; createdAt: ISODateTime; updatedAt: ISODateTime;
  version: number;
}
export interface ManagedRequestPage { requests: ManagedRequestSummary[]; truncated: boolean }
export interface ManagedRequest extends ManagedRequestSummary {
  description: string; resolution: string | null; requesterName: string; resolvedAt: ISODateTime | null; closedAt: ISODateTime | null;
  messages: RequestMessage[]; messagesTruncated: boolean; team: TeamMember[];
}
export interface RequestUpdate {
  state: RequestState; assigneeMembershipId: UUID | null; dueOn: CivilDate | null; resolution: string | null; expectedVersion: number;
}

const text = (minimum: number, maximum: number) => (value: unknown): value is string =>
  typeof value === 'string' && codePoints(value) >= minimum && codePoints(value) <= maximum;
const title = text(3, 120);
const description = text(1, 2000);
const name = text(1, 160);
const resolutionText = text(1, 1000);
const nullableDate = (value: unknown): value is string | null => value === null || isDate(value);
const nullableCivil = (value: unknown): value is string | null => value === null || isCivilDate(value);

/** Andamento e horários coerentes: concluído com resolução e horário, encerrado com horário, aberto sem resolução. */
function consistent(value: { state: RequestState; resolution: unknown; resolvedAt: unknown; closedAt: unknown }): boolean {
  if ((value.state === 'CLOSED') !== (value.closedAt !== null)) return false;
  if (value.state === 'RESOLVED' && (value.resolution === null || value.resolvedAt === null)) return false;
  if (value.state !== 'RESOLVED' && value.state !== 'CLOSED' && (value.resolution !== null || value.resolvedAt !== null)) return false;
  return true;
}
export function requestMessage(value: unknown, staffView: boolean, uncertain = false): RequestMessage {
  if (!record(value) || !isUUID(value.messageId) || !oneOf(value.authorRole, ['UNIT', 'STAFF']) || typeof value.mine !== 'boolean'
      || !oneOf(value.visibility, ['RESIDENT', 'INTERNAL']) || !description(value.body) || !isDate(value.createdAt)
      || !(value.authorName === null || name(value.authorName))) throw invalidResponse(uncertain);
  // A unidade nunca recebe comentário interno nem o nome da pessoa da equipe; a equipe recebe todos os nomes.
  if (value.visibility === 'INTERNAL' && (value.authorRole !== 'STAFF' || !staffView)) throw invalidResponse(uncertain);
  if (!staffView && value.authorRole === 'STAFF' && value.authorName !== null) throw invalidResponse(uncertain);
  if ((staffView || value.authorRole === 'UNIT') && value.authorName === null) throw invalidResponse(uncertain);
  return { messageId: value.messageId, authorRole: value.authorRole, authorName: value.authorName as string | null, mine: value.mine,
    visibility: value.visibility, body: value.body, createdAt: value.createdAt };
}
function messages(value: unknown, staffView: boolean, uncertain: boolean): RequestMessage[] {
  if (!Array.isArray(value) || value.length > 200) throw invalidResponse(uncertain);
  return list(value, (item) => requestMessage(item, staffView, uncertain));
}
export function unitRequestSummary(value: unknown): UnitRequestSummary {
  // A lista da unidade não traz o texto do pedido nem a conversa.
  if (!record(value) || 'description' in value || 'messages' in value || !isUUID(value.requestId) || !isUUID(value.unitId)
      || !oneOf(value.category, requestCategories) || !title(value.title) || !oneOf(value.state, requestStates)
      || typeof value.assigned !== 'boolean' || !nullableCivil(value.dueOn) || !isDate(value.createdAt) || !isDate(value.updatedAt)) throw invalidResponse();
  return { requestId: value.requestId, unitId: value.unitId, category: value.category, title: value.title, state: value.state,
    assigned: value.assigned, dueOn: value.dueOn as string | null, createdAt: value.createdAt, updatedAt: value.updatedAt };
}
export function unitRequest(value: unknown, uncertain = false): UnitRequest {
  if (!record(value) || !isUUID(value.requestId) || !isUUID(value.unitId) || !oneOf(value.category, requestCategories) || !title(value.title)
      || !description(value.description) || !oneOf(value.state, requestStates) || typeof value.assigned !== 'boolean'
      || !nullableCivil(value.dueOn) || !(value.resolution === null || resolutionText(value.resolution)) || !name(value.requesterName)
      || typeof value.openedByMe !== 'boolean' || !isDate(value.createdAt) || !isDate(value.updatedAt) || !nullableDate(value.resolvedAt)
      || !nullableDate(value.closedAt) || typeof value.messagesTruncated !== 'boolean'
      || !consistent({ state: value.state, resolution: value.resolution, resolvedAt: value.resolvedAt, closedAt: value.closedAt })) throw invalidResponse(uncertain);
  return { requestId: value.requestId, unitId: value.unitId, category: value.category, title: value.title, description: value.description,
    state: value.state, assigned: value.assigned, dueOn: value.dueOn as string | null, resolution: value.resolution as string | null,
    requesterName: value.requesterName, openedByMe: value.openedByMe, createdAt: value.createdAt, updatedAt: value.updatedAt,
    resolvedAt: value.resolvedAt as string | null, closedAt: value.closedAt as string | null,
    messages: messages(value.messages, false, uncertain), messagesTruncated: value.messagesTruncated };
}
function teamMember(value: unknown): TeamMember {
  if (!record(value) || !isUUID(value.membershipId) || !name(value.name) || !oneOf(value.role, ['MANAGER', 'CONCIERGE'])) throw invalidResponse();
  return { membershipId: value.membershipId, name: value.name, role: value.role };
}
function managedFields(value: Record<string, unknown>): boolean {
  return isUUID(value.requestId) && isUUID(value.unitId) && name(value.unitLabel) && oneOf(value.category, requestCategories) && title(value.title)
    && oneOf(value.state, requestStates) && (value.assigneeMembershipId === null || isUUID(value.assigneeMembershipId))
    && (value.assigneeName === null || name(value.assigneeName)) && (value.assigneeMembershipId !== null || value.assigneeName === null)
    && nullableCivil(value.dueOn) && isDate(value.createdAt) && isDate(value.updatedAt) && isInteger(value.version);
}
function summaryOf(value: Record<string, unknown>): ManagedRequestSummary {
  return { requestId: value.requestId as string, unitId: value.unitId as string, unitLabel: value.unitLabel as string,
    category: value.category as RequestCategory, title: value.title as string, state: value.state as RequestState,
    assigneeMembershipId: value.assigneeMembershipId as string | null, assigneeName: value.assigneeName as string | null,
    dueOn: value.dueOn as string | null, createdAt: value.createdAt as string, updatedAt: value.updatedAt as string, version: value.version as number };
}
export function managedRequestSummary(value: unknown): ManagedRequestSummary {
  // A lista da gestão não traz o texto do pedido nem a conversa.
  if (!record(value) || 'description' in value || 'messages' in value || !managedFields(value)) throw invalidResponse();
  return summaryOf(value);
}
export function managedRequest(value: unknown, uncertain = false): ManagedRequest {
  if (!record(value) || !managedFields(value) || !description(value.description) || !(value.resolution === null || resolutionText(value.resolution))
      || !name(value.requesterName) || !nullableDate(value.resolvedAt) || !nullableDate(value.closedAt) || typeof value.messagesTruncated !== 'boolean'
      || !Array.isArray(value.team) || value.team.length > 200
      || !consistent({ state: value.state as RequestState, resolution: value.resolution, resolvedAt: value.resolvedAt, closedAt: value.closedAt })) throw invalidResponse(uncertain);
  return { ...summaryOf(value), description: value.description as string, resolution: value.resolution as string | null,
    requesterName: value.requesterName as string, resolvedAt: value.resolvedAt as string | null, closedAt: value.closedAt as string | null,
    messages: messages(value.messages, true, uncertain), messagesTruncated: value.messagesTruncated, team: list(value.team, teamMember) };
}
function unitPage(value: unknown): UnitRequestPage {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.requests) || value.requests.length > 100) throw invalidResponse();
  return { requests: list(value.requests, unitRequestSummary), truncated: value.truncated };
}
function managedPage(value: unknown): ManagedRequestPage {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.requests) || value.requests.length > 100) throw invalidResponse();
  return { requests: list(value.requests, managedRequestSummary), truncated: value.truncated };
}

export function createRequestsApi(client: CondPulseApi = api) {
  const tenant = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  const management = (tenantId: string) => `${tenant(tenantId)}/management/requests`;
  const replayed = (response: Response) => response.headers.get('Idempotency-Replayed') === 'true';
  return {
    unit: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/units/${identifier(unitId)}/requests`, unitPage, signal ? { signal } : {}),
    open: async (tenantId: string, unitId: string, key: string, body: OpenRequestBody) =>
      client.call(`${tenant(tenantId)}/units/${identifier(unitId)}/requests`, (value, response): OpenRequestResult => {
        // Repetição devolve o pedido como está agora; pedido novo nasce enviado, com o texto enviado.
        const request = unitRequest(value, true);
        const repeated = replayed(response);
        if (request.unitId !== unitId || (!repeated && (request.state !== 'OPEN' || request.title !== body.title
          || request.description !== body.description || !request.openedByMe))) throw invalidResponse(true);
        return { request, replayed: repeated };
      }, { method: 'POST', body, key }),
    request: async (tenantId: string, requestId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/requests/${identifier(requestId)}`, (value) => {
        const request = unitRequest(value);
        if (request.requestId !== requestId) throw invalidResponse();
        return request;
      }, signal ? { signal } : {}),
    unitMessage: async (tenantId: string, requestId: string, key: string, body: string) =>
      client.call(`${tenant(tenantId)}/requests/${identifier(requestId)}/messages`, (value, response): MessageResult => {
        const message = requestMessage(value, false, true);
        if (message.authorRole !== 'UNIT' || message.visibility !== 'RESIDENT' || !message.mine || message.body !== body) throw invalidResponse(true);
        return { message, replayed: replayed(response) };
      }, { method: 'POST', body: { body }, key }),
    managed: async (tenantId: string, filter: RequestFilter, signal?: AbortSignal) =>
      client.call(`${management(tenantId)}?state=${encodeURIComponent(filter)}`, managedPage, signal ? { signal } : {}),
    managedRequest: async (tenantId: string, requestId: string, signal?: AbortSignal) =>
      client.call(`${management(tenantId)}/${identifier(requestId)}`, (value) => {
        const request = managedRequest(value);
        if (request.requestId !== requestId) throw invalidResponse();
        return request;
      }, signal ? { signal } : {}),
    staffMessage: async (tenantId: string, requestId: string, key: string, body: string, visibility: 'RESIDENT' | 'INTERNAL') =>
      client.call(`${management(tenantId)}/${identifier(requestId)}/messages`, (value, response): MessageResult => {
        const message = requestMessage(value, true, true);
        if (message.authorRole !== 'STAFF' || message.visibility !== visibility || !message.mine || message.body !== body) throw invalidResponse(true);
        return { message, replayed: replayed(response) };
      }, { method: 'POST', body: { body, visibility }, key }),
    update: async (tenantId: string, requestId: string, body: RequestUpdate) =>
      client.call(`${management(tenantId)}/${identifier(requestId)}/state`, (value) => {
        const request = managedRequest(value, true);
        if (request.requestId !== requestId || request.version !== body.expectedVersion + 1 || request.state !== body.state
          || request.assigneeMembershipId !== body.assigneeMembershipId || request.dueOn !== body.dueOn) throw invalidResponse(true);
        return request;
      }, { method: 'POST', body }),
  };
}
export const requests = createRequestsApi();
