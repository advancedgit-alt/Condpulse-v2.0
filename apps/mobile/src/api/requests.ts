import { ContractError } from './reservations';

/**
 * Pedidos da unidade no aplicativo (CP-033), validados como na web antes de chegar à tela. Morador ou responsável abre o
 * pedido e conversa nele. Na visão da unidade não existe comentário interno e a equipe aparece sem nome: uma resposta que
 * traga qualquer um dos dois é recusada. Texto simples, sempre exibido como texto.
 */
export const requestCategories = ['MAINTENANCE', 'CLEANING', 'SECURITY', 'NOISE', 'ADMINISTRATIVE', 'OTHER'] as const;
export const requestStates = ['OPEN', 'TRIAGED', 'IN_PROGRESS', 'WAITING_RESIDENT', 'RESOLVED', 'CLOSED'] as const;
export type RequestCategory = typeof requestCategories[number];
export type RequestState = typeof requestStates[number];
export interface RequestMessage {
  messageId: string; authorRole: 'UNIT' | 'STAFF'; authorName: string | null; mine: boolean; body: string; createdAt: string;
}
export interface UnitRequestSummary {
  requestId: string; unitId: string; category: RequestCategory; title: string; state: RequestState; assigned: boolean; dueOn: string | null;
  createdAt: string; updatedAt: string;
}
export interface UnitRequestPage { requests: UnitRequestSummary[]; truncated: boolean }
export interface UnitRequest extends UnitRequestSummary {
  description: string; resolution: string | null; requesterName: string; openedByMe: boolean; resolvedAt: string | null; closedAt: string | null;
  messages: RequestMessage[]; messagesTruncated: boolean;
}
/** Mensagens anteriores a uma já exibida (até 200), em ordem de conversa; truncated: há outras mais antigas. */
export interface RequestMessagePage { messages: RequestMessage[]; truncated: boolean }
export interface OpenRequest { category: RequestCategory; title: string; description: string }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const nullableDate = (value: unknown) => value === null || date(value);
const civil = (value: unknown) => value === null || (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value));
const text = (value: unknown, min: number, max: number): value is string => typeof value === 'string' && [...value].length >= min && [...value].length <= max;
const oneOf = <T extends string>(value: unknown, values: readonly T[]): value is T => typeof value === 'string' && (values as readonly string[]).includes(value);
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

export function requestMessage(value: unknown): RequestMessage {
  // Visão da unidade: só mensagens visíveis; equipe sem nome (mine vale também para quem tem vínculo de gestão e de
  // morador e respondeu pela gestão); mensagem da unidade com o nome de quem escreveu.
  check(record(value) && uuid(value.messageId) && oneOf(value.authorRole, ['UNIT', 'STAFF'] as const) && typeof value.mine === 'boolean'
    && value.visibility === 'RESIDENT' && text(value.body, 1, 2000) && date(value.createdAt)
    && (value.authorRole === 'STAFF' ? value.authorName === null : text(value.authorName, 1, 160)));
  return { messageId: value.messageId as string, authorRole: value.authorRole as 'UNIT' | 'STAFF', authorName: value.authorName as string | null,
    mine: value.mine as boolean, body: value.body as string, createdAt: value.createdAt as string };
}
function summaryFields(value: Record<string, unknown>, unitId: string): UnitRequestSummary {
  check(uuid(value.requestId) && uuid(value.unitId) && value.unitId.toLowerCase() === unitId.toLowerCase() && oneOf(value.category, requestCategories)
    && text(value.title, 3, 120) && oneOf(value.state, requestStates) && typeof value.assigned === 'boolean' && civil(value.dueOn)
    && date(value.createdAt) && date(value.updatedAt));
  return { requestId: value.requestId as string, unitId: value.unitId as string, category: value.category as RequestCategory, title: value.title as string,
    state: value.state as RequestState, assigned: value.assigned as boolean, dueOn: value.dueOn as string | null, createdAt: value.createdAt as string,
    updatedAt: value.updatedAt as string };
}
export function unitRequestSummary(value: unknown, unitId: string): UnitRequestSummary {
  // A lista não traz o texto do pedido nem a conversa.
  check(record(value) && !('description' in value) && !('messages' in value));
  return summaryFields(value, unitId);
}
export function unitRequestPage(value: unknown, unitId: string): UnitRequestPage {
  check(record(value) && typeof value.truncated === 'boolean' && Array.isArray(value.requests) && value.requests.length <= 100);
  return { requests: (value.requests as unknown[]).map((item) => unitRequestSummary(item, unitId)), truncated: value.truncated as boolean };
}
/** Detalhe; unitId nulo aceita qualquer unidade (o pedido aberto pelo link da lista já é conferido pelo servidor). */
export function unitRequest(value: unknown, unitId: string | null = null): UnitRequest {
  check(record(value) && uuid(value.unitId));
  const summary = summaryFields(value, unitId ?? (value.unitId as string));
  check(text(value.description, 1, 2000) && (value.resolution === null || text(value.resolution, 1, 1000)) && text(value.requesterName, 1, 160)
    && typeof value.openedByMe === 'boolean' && nullableDate(value.resolvedAt) && nullableDate(value.closedAt)
    && typeof value.messagesTruncated === 'boolean' && Array.isArray(value.messages) && value.messages.length <= 200
    // Andamento coerente: encerrado com horário; concluído com resolução e horário; aberto sem resolução.
    && (summary.state === 'CLOSED') === (value.closedAt !== null)
    && !(summary.state === 'RESOLVED' && (value.resolution === null || value.resolvedAt === null))
    && !(summary.state !== 'RESOLVED' && summary.state !== 'CLOSED' && (value.resolution !== null || value.resolvedAt !== null)));
  return { ...summary, description: value.description as string, resolution: value.resolution as string | null, requesterName: value.requesterName as string,
    openedByMe: value.openedByMe as boolean, resolvedAt: value.resolvedAt as string | null, closedAt: value.closedAt as string | null,
    messages: (value.messages as unknown[]).map(requestMessage), messagesTruncated: value.messagesTruncated as boolean };
}

/** Página anterior: ordem de conversa e sem a mensagem de referência (a validação de cada mensagem recusa comentário interno). */
export function messagePage(value: unknown, before: string): RequestMessagePage {
  check(record(value) && typeof value.truncated === 'boolean' && Array.isArray(value.messages) && value.messages.length <= 200);
  const messages = (value.messages as unknown[]).map(requestMessage);
  messages.forEach((item, index) => check(item.messageId.toLowerCase() !== before.toLowerCase()
    && (index === 0 || Date.parse(messages[index - 1]!.createdAt) <= Date.parse(item.createdAt))));
  return { messages, truncated: value.truncated as boolean };
}

const LINE_BREAK = /\r\n|\r|\n|\u2028|\u2029/;
const collapse = (value: string) => value.replace(/\s+/g, ' ').trim();
/** Linha única como o servidor grava (espaços reduzidos); null quando vazia ou acima do limite. Caractere invisível o servidor recusa. */
export function singleLine(value: string, min: number, max: number): string | null {
  const normalized = collapse(value);
  const length = [...normalized].length;
  return length >= Math.max(1, min) && length <= max ? normalized : null;
}
/** Várias linhas: cada uma reduzida, no máximo uma linha em branco seguida, sem linhas em branco nas pontas. */
export function multiline(value: string, max: number): string | null {
  const lines: string[] = [];
  let blank = false;
  for (const line of value.split(LINE_BREAK)) {
    const normalized = collapse(line);
    if (!normalized) { if (lines.length > 0 && !blank) { lines.push(''); blank = true; } continue; }
    lines.push(normalized); blank = false;
  }
  while (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();
  const joined = lines.join('\n');
  return joined && [...joined].length <= max ? joined : null;
}
