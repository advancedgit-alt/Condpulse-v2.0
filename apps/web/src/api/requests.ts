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
export interface TeamMember { membershipId: UUID; name: string; role: 'MANAGER' | 'CONCIERGE' | 'MAINTENANCE' }
export interface ManagedRequestSummary {
  requestId: UUID; unitId: UUID; unitLabel: string; category: RequestCategory; title: string; state: RequestState;
  assigneeMembershipId: UUID | null; assigneeName: string | null; dueOn: CivilDate | null; createdAt: ISODateTime; updatedAt: ISODateTime;
  version: number;
}
/** Mensagens anteriores a uma já exibida (até 200), em ordem de conversa; truncated: há outras mais antigas. */
export interface RequestMessagePage { messages: RequestMessage[]; truncated: boolean }
export interface ManagedRequestPage { requests: ManagedRequestSummary[]; truncated: boolean }
export interface ManagedRequest extends ManagedRequestSummary {
  description: string; resolution: string | null; requesterName: string; resolvedAt: ISODateTime | null; closedAt: ISODateTime | null;
  messages: RequestMessage[]; messagesTruncated: boolean; team: TeamMember[];
}
/**
 * Zeladoria (V046): pedidos não encerrados atribuídos ao próprio vínculo MAINTENANCE. Sem nome de quem abriu, sem equipe
 * e sem responsável/prazo editáveis: a zeladoria só conversa e muda o andamento (em execução, aguardando a unidade ou
 * concluído com resolução), pela versão lida.
 */
export const maintenanceStates = ['IN_PROGRESS', 'WAITING_RESIDENT', 'RESOLVED'] as const;
export type MaintenanceState = typeof maintenanceStates[number];
/** Andamentos de onde a zeladoria pode partir (o servidor e o banco repetem a regra). */
const maintenanceFrom: readonly RequestState[] = ['TRIAGED', 'IN_PROGRESS', 'WAITING_RESIDENT'];
export function maintenanceTransitions(state: RequestState): MaintenanceState[] {
  if (!maintenanceFrom.includes(state)) return [];
  return maintenanceStates.filter((target) => requestTransitions[state].includes(target));
}
export interface MaintenanceRequestSummary {
  requestId: UUID; unitId: UUID; unitLabel: string; category: RequestCategory; title: string; state: RequestState; dueOn: CivilDate | null;
  updatedAt: ISODateTime; version: number;
}
export interface MaintenanceRequestPage { requests: MaintenanceRequestSummary[]; truncated: boolean }
export interface MaintenanceRequest extends MaintenanceRequestSummary {
  description: string; resolution: string | null; createdAt: ISODateTime; resolvedAt: ISODateTime | null;
  messages: RequestMessage[]; messagesTruncated: boolean;
}
export interface MaintenanceUpdate { state: MaintenanceState; resolution: string | null; expectedVersion: number }

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
/** Página anterior: ordem de conversa, sem a mensagem de referência; a unidade nunca recebe comentário interno. */
export function messagePage(value: unknown, staffView: boolean, before: string): RequestMessagePage {
  if (!record(value) || typeof value.truncated !== 'boolean') throw invalidResponse();
  const items = messages(value.messages, staffView, false);
  for (let index = 0; index < items.length; index += 1) {
    if (items[index]!.messageId === before) throw invalidResponse();
    if (index > 0 && Date.parse(items[index - 1]!.createdAt) > Date.parse(items[index]!.createdAt)) throw invalidResponse();
  }
  return { messages: items, truncated: value.truncated };
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
  if (!record(value) || !isUUID(value.membershipId) || !name(value.name) || !oneOf(value.role, ['MANAGER', 'CONCIERGE', 'MAINTENANCE'])) throw invalidResponse();
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
function maintenanceFields(value: Record<string, unknown>): boolean {
  // A zeladoria nunca recebe o nome de quem abriu, o responsável ou a equipe.
  return !('requesterName' in value) && !('team' in value) && !('assigneeName' in value) && isUUID(value.requestId) && isUUID(value.unitId)
    && name(value.unitLabel) && oneOf(value.category, requestCategories) && title(value.title) && oneOf(value.state, requestStates)
    && nullableCivil(value.dueOn) && isDate(value.updatedAt) && isInteger(value.version);
}
function maintenanceSummaryOf(value: Record<string, unknown>): MaintenanceRequestSummary {
  return { requestId: value.requestId as string, unitId: value.unitId as string, unitLabel: value.unitLabel as string,
    category: value.category as RequestCategory, title: value.title as string, state: value.state as RequestState,
    dueOn: value.dueOn as string | null, updatedAt: value.updatedAt as string, version: value.version as number };
}
export function maintenanceRequestSummary(value: unknown): MaintenanceRequestSummary {
  // A lista não traz o texto do pedido nem a conversa; pedido encerrado não é mais da zeladoria.
  if (!record(value) || 'description' in value || 'messages' in value || !maintenanceFields(value) || value.state === 'CLOSED') throw invalidResponse();
  return maintenanceSummaryOf(value);
}
export function maintenanceRequest(value: unknown, uncertain = false): MaintenanceRequest {
  if (!record(value) || !maintenanceFields(value) || !description(value.description) || !(value.resolution === null || resolutionText(value.resolution))
      || !isDate(value.createdAt) || !nullableDate(value.resolvedAt) || typeof value.messagesTruncated !== 'boolean'
      || (value.state === 'RESOLVED' && (value.resolution === null || value.resolvedAt === null))
      || (value.state !== 'RESOLVED' && value.state !== 'CLOSED' && (value.resolution !== null || value.resolvedAt !== null))) throw invalidResponse(uncertain);
  return { ...maintenanceSummaryOf(value), description: value.description as string, resolution: value.resolution as string | null,
    createdAt: value.createdAt as string, resolvedAt: value.resolvedAt as string | null,
    messages: messages(value.messages, true, uncertain), messagesTruncated: value.messagesTruncated };
}
function maintenancePage(value: unknown): MaintenanceRequestPage {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.requests) || value.requests.length > 100) throw invalidResponse();
  const items = list(value.requests, maintenanceRequestSummary);
  if (new Set(items.map((item) => item.requestId)).size !== items.length) throw invalidResponse();
  return { requests: items, truncated: value.truncated };
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
  const maintenance = (tenantId: string) => `${tenant(tenantId)}/maintenance/requests`;
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
    olderMessages: async (tenantId: string, requestId: string, before: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/requests/${identifier(requestId)}/messages?before=${identifier(before)}`,
        (value) => messagePage(value, false, before), signal ? { signal } : {}),
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
    staffOlderMessages: async (tenantId: string, requestId: string, before: string, signal?: AbortSignal) =>
      client.call(`${management(tenantId)}/${identifier(requestId)}/messages?before=${identifier(before)}`,
        (value) => messagePage(value, true, before), signal ? { signal } : {}),
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
    maintenance: async (tenantId: string, signal?: AbortSignal) =>
      client.call(maintenance(tenantId), maintenancePage, signal ? { signal } : {}),
    maintenanceRequest: async (tenantId: string, requestId: string, signal?: AbortSignal) =>
      client.call(`${maintenance(tenantId)}/${identifier(requestId)}`, (value) => {
        const request = maintenanceRequest(value);
        if (request.requestId !== requestId) throw invalidResponse();
        return request;
      }, signal ? { signal } : {}),
    maintenanceOlderMessages: async (tenantId: string, requestId: string, before: string, signal?: AbortSignal) =>
      client.call(`${maintenance(tenantId)}/${identifier(requestId)}/messages?before=${identifier(before)}`,
        (value) => messagePage(value, true, before), signal ? { signal } : {}),
    maintenanceMessage: async (tenantId: string, requestId: string, key: string, body: string, visibility: 'RESIDENT' | 'INTERNAL') =>
      client.call(`${maintenance(tenantId)}/${identifier(requestId)}/messages`, (value, response): MessageResult => {
        const message = requestMessage(value, true, true);
        if (message.authorRole !== 'STAFF' || message.visibility !== visibility || !message.mine || message.body !== body) throw invalidResponse(true);
        return { message, replayed: replayed(response) };
      }, { method: 'POST', body: { body, visibility }, key }),
    maintenanceUpdate: async (tenantId: string, requestId: string, body: MaintenanceUpdate) =>
      client.call(`${maintenance(tenantId)}/${identifier(requestId)}/state`, (value) => {
        const request = maintenanceRequest(value, true);
        if (request.requestId !== requestId || request.version !== body.expectedVersion + 1 || request.state !== body.state) throw invalidResponse(true);
        return request;
      }, { method: 'POST', body }),
  };
}
export const requests = createRequestsApi();
