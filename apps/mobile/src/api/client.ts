import { credentialPage, eventPage } from './access';
import { currentRules, move, type CreateMove } from './moves';
import { arrival, arrivalPage, permit, permitLink, permitPage, rulePage, visitRule, type CreatePermit, type CreateRule } from './visits';
import { currentDelegation, delegation, issued, parcelPage, type CreateDelegation } from './parcels';
import { amenity, availability, ContractError, identifier, list, operationKey, reservation, type CreateReservation } from './reservations';
import { acceptance, waitlistEntry, waitlistPage, type JoinWaitlist, type WaitlistChange } from './waitlist';
/**
 * Chamadas à API do CondPulse com o access token do Auth no cabeçalho Bearer (o aplicativo não usa o BFF de cookies).
 * Um 401 tenta uma renovação e repete uma vez; recusa de novo encerra a sessão. Respostas são validadas antes da tela.
 * Token e corpo de resposta nunca vão para log.
 */
export interface Membership {
  membershipId: string; tenantId: string; tenantName: string; timeZone: string; unitId: string | null;
  role: 'RESIDENT' | 'RESPONSIBLE' | 'DEPENDENT' | 'OWNER' | 'CONCIERGE' | 'MANAGER' | 'SUPPORT';
}
export interface Identity { accountId: string; memberships: Membership[] }

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly uncertain = false) { super(message); this.name = 'ApiError'; }
}
export interface TokenSource {
  accessToken(): Promise<string | null>;
  refresh(): Promise<string | null>;
  expire(): Promise<void>;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ROLES = ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER', 'CONCIERGE', 'MANAGER', 'SUPPORT'] as const;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const invalid = (uncertain = false) => new ApiError(502, 'INVALID_API_RESPONSE', uncertain
  ? 'Não foi possível confirmar o resultado. Atualize e confira antes de tentar de novo.'
  : 'O sistema retornou uma resposta que não pôde ser validada. Tente novamente mais tarde.', uncertain);

export function identity(value: unknown): Identity {
  if (!record(value) || typeof value.accountId !== 'string' || !UUID.test(value.accountId) || !Array.isArray(value.memberships)
      || value.memberships.length > 200) throw invalid();
  return { accountId: value.accountId, memberships: value.memberships.map((item): Membership => {
    if (!record(item) || typeof item.membershipId !== 'string' || !UUID.test(item.membershipId) || typeof item.tenantId !== 'string'
        || !UUID.test(item.tenantId) || typeof item.tenantName !== 'string' || typeof item.timeZone !== 'string'
        || !(item.unitId === null || (typeof item.unitId === 'string' && UUID.test(item.unitId)))
        || !ROLES.includes(item.role as Membership['role'])) throw invalid();
    return { membershipId: item.membershipId, tenantId: item.tenantId, tenantName: item.tenantName, timeZone: item.timeZone,
      unitId: item.unitId as string | null, role: item.role as Membership['role'] };
  }) };
}

const messages: Record<string, string> = {
  AUTHENTICATION_REQUIRED: 'Entre novamente para continuar.',
  COMMAND_OUTCOME_UNKNOWN: 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.',
  RESERVATION_ACCESS_DENIED: 'Seu vínculo não permite acessar as reservas.',
  RESERVATION_RESOURCE_UNAVAILABLE: 'A reserva ou a área não está disponível para esta operação.',
  AMENITY_POLICY_NOT_CONFIGURED: 'As regras desta área ainda precisam ser configuradas.',
  AMENITY_POLICY_CHANGED: 'As regras da área mudaram. Confira as condições atualizadas antes de reservar.',
  RESERVATION_CONFLICT: 'Este horário acabou de ser ocupado. A disponibilidade foi atualizada: escolha outro horário.',
  UNIT_RESERVATION_QUOTA_REACHED: 'A unidade atingiu o limite de reservas previsto para esta área.',
  RESERVATION_CHANGED: 'A reserva foi alterada. Atualize antes de continuar.',
  RESERVATION_CANCELLATION_CLOSED: 'O prazo para cancelar esta reserva terminou.',
  RESERVATION_AUTHOR_REQUIRED: 'Somente quem fez a reserva pode cancelá-la.',
  RESERVATION_WINDOW_INVALID: 'O horário escolhido não atende às regras da área. Atualize e escolha outro.',
  RESERVATION_RANGE_INVALID: 'Período de consulta inválido.',
  AMENITY_AVAILABILITY_UNAVAILABLE: 'Não foi possível consultar a ocupação da área agora. Tente novamente.',
  RESERVATION_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir agora. Confira suas reservas antes de repetir.',
  DELEGATION_NAME_INVALID: 'Informe o nome de quem vai retirar, com 2 a 160 caracteres.',
  DELEGATION_DOCUMENT_INVALID: 'Informe só os 2 a 4 últimos caracteres do documento.',
  DELEGATION_VALIDITY_INVALID: 'A autorização vale de 15 minutos a 7 dias.',
  DELEGATION_NOT_ACTIVE: 'Não há autorização em vigor para esta encomenda.',
  DELEGATION_CHANGED: 'A autorização foi alterada. Atualize a encomenda.',
  DELEGATION_NOT_ALLOWED: 'Seu vínculo não permite autorizar retirada desta encomenda.',
  DELEGATION_UNAVAILABLE: 'A retirada por código ainda não está disponível neste condomínio.',
  DELEGATION_NOT_FOUND: 'Não há autorização de retirada em vigor para esta encomenda.',
  PARCEL_NOT_FOUND: 'Encomenda não encontrada.',
  PARCEL_ALREADY_DELIVERED: 'Esta encomenda já foi retirada.',
  PARCEL_VOIDED: 'Este registro de encomenda foi anulado e não está mais para retirada.',
  PARCEL_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir agora. Atualize e confira antes de repetir.',
  UNIT_ACCESS_DENIED: 'Unidade não disponível para esta operação.',
  IDEMPOTENCY_KEY_REUSED: 'Este envio já foi usado para outro pedido. Comece de novo.',
  IDEMPOTENCY_KEY_EXPIRED: 'O prazo para confirmar este envio terminou e não dá para saber se ele foi gravado.',
  ACCOUNT_INACTIVE: 'Seu acesso precisa ser validado pela gestão.',
  MOVE_RULES_NOT_CONFIGURED: 'A gestão ainda não publicou as regras de mudança.',
  MOVE_RULES_CHANGED: 'As regras de mudança foram alteradas. Leia a versão atual antes de continuar.',
  MOVE_WINDOW_OUTSIDE_RULES: 'O período não atende às regras de mudança: confira dias, horário, antecedência e duração.',
  MOVE_WINDOW_INVALID: 'Informe início e fim, com o fim depois do início.',
  MOVE_PROVIDER_INVALID: 'Informe o responsável ou a transportadora, com 2 a 160 caracteres.',
  MOVE_PLATE_INVALID: 'Informe a placa com 5 a 8 letras e números.',
  MOVE_ITEMS_INVALID: 'Descreva os itens com até 500 caracteres.',
  MOVE_CHANGED: 'O pedido de mudança foi alterado. Atualize antes de continuar.',
  MOVE_CLOSED: 'Este pedido de mudança já foi decidido ou encerrado.',
  MOVE_CANCELLATION_CLOSED: 'O período já começou. Fale com a gestão para cancelar.',
  MOVE_NOT_FOUND: 'Pedido de mudança não encontrado.',
  MOVE_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir agora. Confira os pedidos antes de repetir.',
  TENANT_ACCESS_DENIED: 'Condomínio não disponível para este acesso.',
  WAITLIST_SLOT_AVAILABLE: 'O período está livre. Faça a reserva diretamente.',
  WAITLIST_OWN_RESERVATION: 'A unidade já tem reserva ou pedido neste período.',
  WAITLIST_ALREADY_JOINED: 'A unidade já está na fila deste período.',
  WAITLIST_LIMIT_REACHED: 'A unidade já está em 5 filas de espera abertas.',
  WAITLIST_NOT_FOUND: 'Entrada da fila não encontrada.',
  WAITLIST_CHANGED: 'A fila de espera mudou. Atualize antes de continuar.',
  WAITLIST_OFFER_EXPIRED: 'O prazo da oferta terminou e a vaga seguiu para o próximo da fila.',
  WAITLIST_CLOSED: 'Esta entrada da fila já foi encerrada.',
  WAITLIST_AUTHOR_REQUIRED: 'Somente quem entrou na fila pode alterar esta entrada.',
  WAITLIST_PRIORITY: 'Há fila de espera para este período: a vaga vai primeiro para quem aguarda. Entre na fila ou escolha outro horário.',
  AMENITY_INACTIVE: 'A área foi desativada pela gestão. A oferta não pode ser aceita.',
  WAITLIST_REQUEST_INVALID: 'O período escolhido não pode entrar na fila. Atualize e escolha outro.',
  WAITLIST_VERSION_REQUIRED: 'Atualize a fila de espera antes de continuar.',
  RESERVATION_RULE_SNAPSHOT_INVALID: 'As regras aceitas nesta entrada precisam ser revisadas pela gestão.',
  ARRIVAL_EXPIRED: 'O prazo de resposta desta chegada terminou. Siga o procedimento da portaria.',
  ARRIVAL_ALREADY_DECIDED: 'Esta chegada já foi respondida.',
  ARRIVAL_CANCELLED: 'Esta chegada foi encerrada pela portaria.',
  ARRIVAL_NOT_FOUND: 'Chegada não encontrada.',
  ARRIVAL_DECISION_INVALID: 'Responda autorizando ou recusando a entrada.',
  VISIT_ACCESS_DENIED: 'Esta ação não está disponível para o seu vínculo.',
  VISIT_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  VISIT_PERMIT_CHANGED: 'O convite foi alterado. Atualize os convites antes de continuar.',
  VISIT_PERMIT_EXPIRED: 'A vigência deste convite já terminou.',
  VISIT_PERMIT_REVOKED: 'Este convite já foi revogado.',
  VISIT_PERMIT_NOT_FOUND: 'Convite não encontrado.',
  VISIT_PERMIT_INACTIVE: 'Quem convidou não tem mais vínculo para convidar pela unidade: o convite não vale.',
  VISIT_PERMIT_LINK_UNAVAILABLE: 'Só convites em vigor ou agendados podem ser compartilhados.',
  VISIT_PERIOD_INVALID: 'O convite começa a partir de agora (até 90 dias à frente), termina no futuro e dura até 7 dias.',
  VISIT_INPUT_INVALID: 'Informe os dados da visita.',
  VISIT_VERSION_REQUIRED: 'Atualize a lista antes de revogar.',
  VISITOR_NAME_INVALID: 'Informe o nome do visitante, de 2 a 160 caracteres.',
  VISITOR_DOCUMENT_INVALID: 'Informe só o final do documento, de 2 a 4 letras ou números.',
  VISIT_RULE_CHANGED: 'A regra foi alterada. Atualize antes de continuar.',
  VISIT_RULE_REVOKED: 'Esta regra já foi revogada.',
  VISIT_RULE_NOT_FOUND: 'Regra não encontrada.',
  VISIT_RULE_DAYS_INVALID: 'Escolha de 1 a 7 dias da semana, sem repetir.',
  VISIT_RULE_PERIOD_INVALID: 'A regra começa hoje ou depois (até 90 dias à frente) e dura até 180 dias.',
  VISIT_RULE_TIME_INVALID: 'Informe o horário no formato HH:MM, com fim depois do início no mesmo dia.',
  RESERVATION_GUEST_LIMIT: 'A reserva já tem o número máximo de convidados da área.',
  RESERVATION_GUEST_TOO_EARLY: 'Convidados podem ser incluídos a partir de 90 dias antes do evento.',
  RESERVATION_GUEST_UNAVAILABLE: 'Reserva não disponível para convidados.',
  CREDENTIAL_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
};

export type Fetcher = (url: string, init: RequestInit) => Promise<Response>;

export function createApi(options: { baseUrl: string; tokens: TokenSource; fetch?: Fetcher }) {
  const base = new URL(options.baseUrl);
  if (base.protocol !== 'https:' && !(base.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(base.hostname)))
    throw new Error('API_URL_INVALID');
  const fetcher: Fetcher = options.fetch ?? ((url, init) => globalThis.fetch(url, init));
  interface Call { method?: 'GET' | 'POST'; body?: unknown; key?: string; signal?: AbortSignal }
  async function send(path: string, token: string, call: Call): Promise<Response> {
    const mutation = call.method === 'POST';
    const headers: Record<string, string> = { Accept: 'application/json', Authorization: `Bearer ${token}` };
    if (call.body !== undefined) headers['Content-Type'] = 'application/json';
    if (call.key !== undefined) headers['Idempotency-Key'] = call.key;
    try {
      return await fetcher(new URL(path, base).toString(), { method: call.method ?? 'GET', headers, cache: 'no-store', redirect: 'error',
        ...(call.body !== undefined ? { body: JSON.stringify(call.body) } : {}), ...(call.signal ? { signal: call.signal } : {}) });
    } catch {
      // Envio sem resposta: o servidor pode ter gravado; repetir com a mesma chave só confirma.
      if (mutation) throw new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', messages.COMMAND_OUTCOME_UNKNOWN!, true);
      throw new ApiError(0, 'NETWORK_UNAVAILABLE', 'Não foi possível conectar ao sistema. Confira sua conexão e tente novamente.');
    }
  }
  async function request<T>(path: string, parse: (value: unknown, response: Response) => T, call: Call = {}): Promise<T> {
    const mutation = call.method === 'POST';
    const token = await options.tokens.accessToken();
    if (!token) throw new ApiError(401, 'AUTHENTICATION_REQUIRED', messages.AUTHENTICATION_REQUIRED!);
    let response = await send(path, token, call);
    if (response.status === 401) {
      const renewed = await options.tokens.refresh();
      if (!renewed) throw new ApiError(401, 'AUTHENTICATION_REQUIRED', messages.AUTHENTICATION_REQUIRED!);
      response = await send(path, renewed, call);
      if (response.status === 401) {
        await options.tokens.expire();
        throw new ApiError(401, 'AUTHENTICATION_REQUIRED', messages.AUTHENTICATION_REQUIRED!);
      }
    }
    let value: unknown = undefined;
    if ((response.headers.get('Content-Type') ?? '').match(/application\/(problem\+)?json/)) {
      try { value = await response.json(); } catch { if (response.ok) throw invalid(mutation); }
    } else if (response.ok) throw invalid(mutation);
    if (!response.ok) {
      const code = record(value) && typeof value.code === 'string' && Object.hasOwn(messages, value.code) ? value.code
        : mutation && response.status >= 500 ? 'COMMAND_OUTCOME_UNKNOWN' : 'HTTP_ERROR';
      throw new ApiError(response.status, code, messages[code] ?? 'Não foi possível concluir agora. Tente novamente mais tarde.',
        mutation && response.status >= 500);
    }
    try { return parse(value, response); }
    catch (error) {
      if (error instanceof ContractError || error instanceof ApiError) throw invalid(mutation);
      throw error;
    }
  }
  const tenant = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  const replayed = (response: Response) => response.headers.get('Idempotency-Replayed') === 'true';
  return {
    me: (signal?: AbortSignal) => request('/v1/me', identity, signal ? { signal } : {}),
    amenities: (tenantId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/amenities`, (value) => list(value, amenity, 100), signal ? { signal } : {}),
    availability: (tenantId: string, unitId: string, amenityId: string, range: { from: string; until: string }, signal?: AbortSignal) => {
      const query = new URLSearchParams({ unitId: identifier(unitId), from: range.from, until: range.until });
      return request(`${tenant(tenantId)}/amenities/${identifier(amenityId)}/availability?${query}`,
        (value) => availability(value, { tenantId, unitId, amenityId, ...range }), signal ? { signal } : {});
    },
    reservations: (tenantId: string, unitId: string, range: { from: string; until: string }, signal?: AbortSignal) => {
      const query = new URLSearchParams({ unitId: identifier(unitId), from: range.from, until: range.until });
      return request(`${tenant(tenantId)}/reservations?${query}`, (value) => {
        if (!record(value) || typeof value.truncated !== 'boolean') throw invalid();
        return { reservations: list(value.reservations, reservation, 500), truncated: value.truncated };
      }, signal ? { signal } : {});
    },
    createReservation: (tenantId: string, body: CreateReservation, key: string) =>
      request(`${tenant(tenantId)}/reservations`, (value, response) => ({ reservation: reservation(value), replayed: replayed(response) }),
        { method: 'POST', body, key: operationKey(key) }),
    unitCredentials: (tenantId: string, unitId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/access-credentials`, (value) => credentialPage(value, unitId), signal ? { signal } : {}),
    unitDeviceEvents: (tenantId: string, unitId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/device-events`, eventPage, signal ? { signal } : {}),
    unitPermits: (tenantId: string, unitId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/permits`, (value) => permitPage(value, unitId), signal ? { signal } : {}),
    createPermit: (tenantId: string, unitId: string, body: CreatePermit, key: string) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/permits`, (value, response) => {
        const created = permit(value, unitId);
        const repeated = replayed(response);
        // Convite novo nasce em aberto, com o nome e o período enviados; repetição devolve o estado atual.
        if (!repeated && (!['SCHEDULED', 'ACTIVE'].includes(created.state) || created.visitorName !== body.visitorName
          || Date.parse(created.validFrom) !== Date.parse(body.validFrom) || Date.parse(created.validUntil) !== Date.parse(body.validUntil))) throw invalid(true);
        return { permit: created, replayed: repeated };
      }, { method: 'POST', body, key: operationKey(key) }),
    revokePermit: (tenantId: string, unitId: string, permitId: string, body: { reason: string; expectedVersion: number }) =>
      request(`${tenant(tenantId)}/permits/${identifier(permitId)}/revoke`, (value) => {
        const revoked = permit(value, unitId);
        if (revoked.permitId !== permitId || revoked.state !== 'REVOKED') throw invalid(true);
        return revoked;
      }, { method: 'POST', body }),
    shareLink: (tenantId: string, permitId: string) =>
      request(`${tenant(tenantId)}/permits/${identifier(permitId)}/link`, permitLink, { method: 'POST' }),
    revokeLink: (tenantId: string, unitId: string, permitId: string) =>
      request(`${tenant(tenantId)}/permits/${identifier(permitId)}/link/revoke`, (value) => {
        const updated = permit(value, unitId);
        if (updated.permitId !== permitId || updated.shareLinkActive) throw invalid(true);
        return updated;
      }, { method: 'POST' }),
    unitRules: (tenantId: string, unitId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/visit-rules`, (value) => rulePage(value, unitId), signal ? { signal } : {}),
    createRule: (tenantId: string, unitId: string, body: CreateRule, key: string) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/visit-rules`, (value, response) => {
        const created = visitRule(value, unitId);
        const repeated = replayed(response);
        if (!repeated && (created.state !== 'ACTIVE' || created.visitorName !== body.visitorName || created.startTime !== body.startTime
          || created.endTime !== body.endTime || created.weekDays.join() !== body.weekDays.join())) throw invalid(true);
        return { rule: created, replayed: repeated };
      }, { method: 'POST', body, key: operationKey(key) }),
    revokeRule: (tenantId: string, unitId: string, ruleId: string, body: { reason: string; expectedVersion: number }) =>
      request(`${tenant(tenantId)}/visit-rules/${identifier(ruleId)}/revoke`, (value) => {
        const revoked = visitRule(value, unitId);
        if (revoked.ruleId !== ruleId || revoked.state !== 'REVOKED') throw invalid(true);
        return revoked;
      }, { method: 'POST', body }),
    reservationGuests: (tenantId: string, unitId: string, reservationId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/reservations/${identifier(reservationId)}/guests`, (value) => {
        const page = permitPage(value, unitId, 500);
        if (page.permits.some((item) => item.reservationId !== reservationId)) throw invalid();
        return page;
      }, signal ? { signal } : {}),
    addGuest: (tenantId: string, unitId: string, reservationId: string, body: { visitorName: string; documentLast4: string | null }, key: string) =>
      request(`${tenant(tenantId)}/reservations/${identifier(reservationId)}/guests`, (value, response) => {
        const created = permit(value, unitId);
        const repeated = replayed(response);
        if (created.reservationId !== reservationId || (!repeated && created.visitorName !== body.visitorName)) throw invalid(true);
        return { permit: created, replayed: repeated };
      }, { method: 'POST', body, key: operationKey(key) }),
    unitArrivals: (tenantId: string, unitId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/arrivals`, (value) => arrivalPage(value, unitId), signal ? { signal } : {}),
    decideArrival: (tenantId: string, unitId: string, arrivalId: string, decision: 'APPROVE' | 'DENY') =>
      request(`${tenant(tenantId)}/arrivals/${identifier(arrivalId)}/decision`, (value) => {
        const decided = arrival(value, unitId);
        if (decided.arrivalId !== arrivalId) throw invalid(true);
        return decided;
      }, { method: 'POST', body: { decision } }),
    moveRules: (tenantId: string, signal?: AbortSignal) => request(`${tenant(tenantId)}/move-rules`, currentRules, signal ? { signal } : {}),
    unitMoves: (tenantId: string, unitId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/moves`, (value) => {
        if (!record(value) || typeof value.truncated !== 'boolean') throw invalid();
        const moves = list(value.moves, move, 100);
        if (moves.some((item) => item.unitId.toLowerCase() !== unitId.toLowerCase())) throw invalid();
        return { moves, truncated: value.truncated };
      }, signal ? { signal } : {}),
    createMove: (tenantId: string, unitId: string, body: CreateMove, key: string) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/moves`, (value, response) => {
        const created = move(value);
        const repeated = replayed(response);
        // Pedido novo nasce aguardando, com o período e a versão das regras enviados; repetição devolve o estado atual.
        if (created.unitId.toLowerCase() !== unitId.toLowerCase() || (!repeated && (created.state !== 'REQUESTED' || created.kind !== body.kind
          || Date.parse(created.startsAt) !== Date.parse(body.startsAt) || Date.parse(created.endsAt) !== Date.parse(body.endsAt)
          || created.rulesVersion !== body.acceptedRulesVersion))) throw invalid(true);
        return { move: created, replayed: repeated };
      }, { method: 'POST', body, key: operationKey(key) }),
    cancelMove: (tenantId: string, moveId: string, expectedVersion: number) =>
      request(`${tenant(tenantId)}/moves/${identifier(moveId)}/cancel`, (value) => {
        const cancelled = move(value);
        if (cancelled.moveId !== moveId || cancelled.state !== 'CANCELLED') throw invalid(true);
        return cancelled;
      }, { method: 'POST', body: { note: null, expectedVersion } }),
    cancelReservation: (tenantId: string, reservationId: string, body: { unitId: string; expectedVersion: number }, key: string) =>
      request(`${tenant(tenantId)}/reservations/${identifier(reservationId)}/cancel`,
        (value, response) => ({ reservation: reservation(value), replayed: replayed(response) }), { method: 'POST', body, key: operationKey(key) }),
    unitParcels: (tenantId: string, unitId: string, status: 'RECEIVED' | 'DELIVERED', signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/parcels?status=${status}`, (value) => {
        const page = parcelPage(value, unitId);
        if (page.parcels.some((item) => item.status !== status)) throw invalid();
        return page;
      }, signal ? { signal } : {}),
    parcelDelegation: (tenantId: string, parcelId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/parcels/${identifier(parcelId)}/delegation`, (value) => currentDelegation(value, parcelId), signal ? { signal } : {}),
    delegate: (tenantId: string, parcelId: string, body: CreateDelegation, key: string) =>
      request(`${tenant(tenantId)}/parcels/${identifier(parcelId)}/delegation`, (value, response) => issued(value, parcelId, replayed(response)),
        { method: 'POST', body, key: operationKey(key) }),
    revokeDelegation: (tenantId: string, parcelId: string, body: { delegationId: string; expectedVersion: number }) =>
      request(`${tenant(tenantId)}/parcels/${identifier(parcelId)}/delegation/revoke`, (value) => {
        const revoked = delegation(value);
        if (revoked.delegationId !== body.delegationId || revoked.state !== 'REVOKED') throw invalid(true);
        return revoked;
      }, { method: 'POST', body }),
    unitWaitlist: (tenantId: string, unitId: string, signal?: AbortSignal) =>
      request(`${tenant(tenantId)}/units/${identifier(unitId)}/waitlist`, waitlistPage, signal ? { signal } : {}),
    joinWaitlist: (tenantId: string, body: JoinWaitlist, key: string) =>
      request(`${tenant(tenantId)}/waitlist`, (value, response) => ({ entry: waitlistEntry(value), replayed: replayed(response) }),
        { method: 'POST', body, key: operationKey(key) }),
    leaveWaitlist: (tenantId: string, waitlistId: string, body: WaitlistChange) =>
      request(`${tenant(tenantId)}/waitlist/${identifier(waitlistId)}/leave`, waitlistEntry, { method: 'POST', body }),
    acceptOffer: (tenantId: string, waitlistId: string, body: WaitlistChange, key: string) =>
      request(`${tenant(tenantId)}/waitlist/${identifier(waitlistId)}/accept`, acceptance, { method: 'POST', body, key: operationKey(key) }),
  };
}
export type CondPulseApi = ReturnType<typeof createApi>;
