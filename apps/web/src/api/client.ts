import type {
  Amenity, CancelReservation, CreateReservation, CurrentIdentity, Membership,
  MutationResult, Reservation, ReservationPage, ReservationPolicy, Session, Unit,
} from './types';
import { parseRetryAfterSeconds, remainingCooldown } from './cooldown';

const messages: Record<string, string> = {
  AUTHENTICATION_REQUIRED: 'Entre novamente para continuar.',
  SESSION_EXPIRED: 'Sua sessão terminou. Entre novamente para continuar.',
  INVALID_SESSION_COOKIE: 'Entre novamente para continuar.',
  AUTH_CREDENTIALS_REJECTED: 'Não foi possível validar seu e-mail e sua senha.',
  INVALID_LOGIN_REQUEST: 'Informe seu e-mail e sua senha.',
  ACCOUNT_INACTIVE: 'Seu acesso precisa ser validado pela gestão.',
  AUTH_PROVIDER_UNAVAILABLE: 'O acesso está temporariamente indisponível. Tente novamente mais tarde.',
  AUTH_RATE_LIMITED: 'Aguarde um pouco antes de tentar novamente.',
  AUTH_RATE_LIMIT_UNAVAILABLE: 'Não foi possível verificar o acesso agora. Tente novamente mais tarde.',
  SESSION_REFRESH_REQUIRED: 'Sua sessão precisa ser renovada. Tente novamente.',
  SESSION_REFRESH_IN_PROGRESS: 'A sessão está sendo renovada. Aguarde antes de tentar novamente.',
  CSRF_ORIGIN_REJECTED: 'Não foi possível validar esta operação. Reabra o sistema e tente novamente.',
  ACTION_FORBIDDEN: 'Você não tem permissão para esta operação.',
  TENANT_ACCESS_DENIED: 'Este condomínio não está disponível para seu acesso.',
  UNIT_ACCESS_DENIED: 'Esta unidade não está disponível para esta operação.',
  RESERVATION_ACCESS_DENIED: 'Seu vínculo não permite acessar as reservas.',
  RESERVATION_RESOURCE_UNAVAILABLE: 'A reserva ou a área não está disponível para esta operação.',
  AMENITY_POLICY_NOT_CONFIGURED: 'As regras desta área ainda precisam ser configuradas.',
  AMENITY_APPROVAL_UNAVAILABLE: 'Esta área exige aprovação e ainda não está disponível neste fluxo.',
  AMENITY_POLICY_CHANGED: 'As regras da área mudaram. Atualize e revise as condições antes de reservar.',
  RESERVATION_CONFLICT: 'O período foi ocupado. Atualize os dados e escolha outro horário.',
  UNIT_RESERVATION_QUOTA_REACHED: 'A unidade atingiu o limite de reservas previsto para esta área.',
  RESERVATION_CHANGED: 'A reserva foi alterada. Atualize os dados antes de continuar.',
  RESERVATION_CANCELLATION_CLOSED: 'O prazo para cancelar esta reserva terminou.',
  RESERVATION_RULE_SNAPSHOT_INVALID: 'As regras aceitas desta reserva precisam ser conferidas pela gestão.',
  RESERVATION_WINDOW_INVALID: 'O período informado não atende às regras da área.',
  RESERVATION_RANGE_INVALID: 'Informe um período de consulta de até 31 dias.',
  IDEMPOTENCY_KEY_REUSED: 'Esta solicitação já foi enviada com outros dados. Confira a agenda antes de iniciar outra.',
  IDEMPOTENCY_KEY_EXPIRED: 'O prazo para repetir esta solicitação terminou. Confira a agenda antes de iniciar outra.',
  REQUEST_IN_PROGRESS: 'A operação ainda está em processamento. Aguarde e confira a agenda.',
  DEPENDENCY_UNAVAILABLE: 'Não foi possível consultar o sistema agora. Tente novamente mais tarde.',
  RESERVATION_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação agora. Confira a agenda antes de repetir.',
  TENANT_CONFIGURATION_INVALID: 'A configuração do condomínio precisa ser conferida pela gestão.',
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly uncertain = false,
    readonly retryAfterSeconds?: number,
    readonly retryUntil?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const isUUID = (value: unknown): value is string => typeof value === 'string' && uuidPattern.test(value);
const isString = (value: unknown): value is string => typeof value === 'string';
const isDate = (value: unknown): value is string => isString(value) && Number.isFinite(Date.parse(value));
const isInteger = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const oneOf = <T extends string>(value: unknown, values: readonly T[]): value is T =>
  typeof value === 'string' && values.some((candidate) => candidate === value);

function invalidResponse(uncertain = false): ApiError {
  return new ApiError(502, 'INVALID_API_RESPONSE',
    uncertain ? 'Não foi possível confirmar o resultado. Confira a agenda antes de tentar novamente.'
      : 'O sistema retornou uma resposta que não pôde ser validada. Tente novamente mais tarde.', uncertain);
}

function unknownCommand(): ApiError {
  return new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN',
    'Não foi possível confirmar o resultado. Confira a agenda antes de tentar novamente.', true);
}

function session(value: unknown): Session {
  if (!record(value) || !isUUID(value.accountId) || !isDate(value.expiresAt)) throw invalidResponse(true);
  return { accountId: value.accountId, expiresAt: value.expiresAt };
}

function membership(value: unknown): Membership {
  if (!record(value) || !isUUID(value.membershipId) || !isUUID(value.tenantId)
      || !isString(value.tenantName) || !isString(value.timeZone)
      || !(value.unitId === null || isUUID(value.unitId))
      || !oneOf(value.role, ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER', 'CONCIERGE', 'MANAGER', 'SUPPORT']))
    throw invalidResponse();
  return { membershipId: value.membershipId, tenantId: value.tenantId, tenantName: value.tenantName,
    timeZone: value.timeZone, unitId: value.unitId, role: value.role };
}

function identity(value: unknown): CurrentIdentity {
  if (!record(value) || !isUUID(value.accountId) || !Array.isArray(value.memberships)) throw invalidResponse();
  return { accountId: value.accountId, memberships: value.memberships.map(membership) };
}

function unit(value: unknown): Unit {
  if (!record(value) || !isUUID(value.id) || !isString(value.label)
      || !(value.blockId === null || isUUID(value.blockId))
      || !(value.blockName === null || isString(value.blockName))) throw invalidResponse();
  return { id: value.id, label: value.label, blockId: value.blockId, blockName: value.blockName };
}

function policy(value: unknown): ReservationPolicy {
  if (!record(value) || value.schemaVersion !== 1 || !Array.isArray(value.weekDays)
      || value.weekDays.length === 0 || !value.weekDays.every((day) => isInteger(day) && day >= 1 && day <= 7)
      || !isString(value.opensAt) || !isString(value.closesAt)
      || !isInteger(value.minimumLeadMinutes) || !isInteger(value.maximumAdvanceDays)
      || !isInteger(value.maximumDurationMinutes) || !isInteger(value.maximumActivePerUnit)
      || !isInteger(value.cancellationLeadMinutes)) throw invalidResponse();
  return { schemaVersion: 1, weekDays: [...value.weekDays] as number[], opensAt: value.opensAt, closesAt: value.closesAt,
    minimumLeadMinutes: value.minimumLeadMinutes, maximumAdvanceDays: value.maximumAdvanceDays,
    maximumDurationMinutes: value.maximumDurationMinutes, maximumActivePerUnit: value.maximumActivePerUnit,
    cancellationLeadMinutes: value.cancellationLeadMinutes };
}

function amenity(value: unknown): Amenity {
  if (!record(value) || !isUUID(value.amenityId) || !isString(value.name) || !isInteger(value.capacity)
      || !isInteger(value.policyVersion) || !isInteger(value.feeCents) || !isInteger(value.bufferMinutes)
      || typeof value.approvalRequired !== 'boolean'
      || !oneOf(value.bookingState, ['POLICY_NOT_CONFIGURED', 'APPROVAL_UNAVAILABLE', 'AVAILABLE_FOR_REQUEST']))
    throw invalidResponse();
  return { amenityId: value.amenityId, name: value.name, capacity: value.capacity, policyVersion: value.policyVersion,
    feeCents: value.feeCents, bufferMinutes: value.bufferMinutes, approvalRequired: value.approvalRequired,
    bookingState: value.bookingState, policy: value.policy === null ? null : policy(value.policy) };
}

function reservation(value: unknown, uncertain = false): Reservation {
  if (!record(value) || !isUUID(value.reservationId) || !isUUID(value.unitId) || !isUUID(value.amenityId)
      || !oneOf(value.status, ['HELD', 'PENDING_APPROVAL', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'EXPIRED'])
      || !isDate(value.startsAt) || !isDate(value.endsAt) || !isDate(value.occupiedFrom) || !isDate(value.occupiedUntil)
      || !isInteger(value.acceptedPolicyVersion) || !isInteger(value.feeCents) || !isInteger(value.version))
    throw invalidResponse(uncertain);
  return { reservationId: value.reservationId, unitId: value.unitId, amenityId: value.amenityId, status: value.status,
    startsAt: value.startsAt, endsAt: value.endsAt, occupiedFrom: value.occupiedFrom, occupiedUntil: value.occupiedUntil,
    acceptedPolicyVersion: value.acceptedPolicyVersion, feeCents: value.feeCents, version: value.version };
}

function list<T>(value: unknown, decode: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) throw invalidResponse();
  return value.map(decode);
}

function identifier(value: string): string {
  if (!isUUID(value)) throw new ApiError(400, 'INVALID_RESOURCE_ID', 'O contexto selecionado não é válido. Selecione novamente.');
  return value;
}

function operationKey(value: string): string {
  if (typeof value !== 'string' || !/^[\x21-\x7e]{16,200}$/.test(value))
    throw new ApiError(400, 'INVALID_IDEMPOTENCY_KEY', 'Não foi possível preparar esta solicitação. Reabra o formulário.');
  return value;
}

function abortError(): DOMException { return new DOMException('Consulta cancelada.', 'AbortError'); }
function checkSignal(signal?: AbortSignal): void { if (signal?.aborted) throw abortError(); }

function abortable<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(abortError());
  return new Promise<T>((resolve, reject) => {
    const abort = () => { signal.removeEventListener('abort', abort); reject(abortError()); };
    signal.addEventListener('abort', abort, { once: true });
    promise.then((value) => { signal.removeEventListener('abort', abort); resolve(value); },
      (error: unknown) => { signal.removeEventListener('abort', abort); reject(error); });
  });
}

interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: string;
  key?: string;
  signal?: AbortSignal;
}
interface Reply { response: Response; value: unknown; }
interface Csrf { headerName: 'X-CSRF-Token'; token: string; }

/** Cliente BFF da própria origem. Nenhuma credencial do provedor fica no navegador. */
export class CondPulseApi {
  private readonly fetcher: typeof globalThis.fetch;
  private readonly now: () => number;
  private readonly authCooldowns = new Map<string, number>();
  private generation = 0;
  private authTail: Promise<void> = Promise.resolve();
  private refreshFlight: { generation: number; promise: Promise<Session> } | undefined;
  private csrfFlight: { generation: number; promise: Promise<Csrf> } | undefined;
  private readonly expiredListeners = new Set<() => void>();

  constructor(options: { fetch?: typeof globalThis.fetch; now?: () => number } = {}) {
    this.fetcher = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.now = options.now ?? (() => globalThis.performance.now());
  }

  onSessionExpired(listener: () => void): () => void {
    this.expiredListeners.add(listener);
    return () => { this.expiredListeners.delete(listener); };
  }

  /** Limpa memória e impede respostas de outra sessão; não afirma revogação no servidor. */
  clearSession(): void {
    this.generation += 1; this.csrfFlight = undefined;
    this.authCooldowns.delete('/auth/refresh');
    // A espera de login não guarda identidade e continua válida ao limpar a sessão.
  }

  private expire(generation: number): void {
    if (generation !== this.generation) return;
    this.clearSession();
    for (const listener of this.expiredListeners) {
      try { listener(); } catch { /* Um consumidor não pode impedir a limpeza dos demais. */ }
    }
  }

  private ensureCurrent(generation: number, signal?: AbortSignal): void {
    checkSignal(signal);
    if (generation !== this.generation) throw abortError();
  }

  private auth<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.authTail.then(operation);
    this.authTail = next.then(() => undefined, () => undefined);
    return next;
  }

  private async csrf(generation: number, signal?: AbortSignal): Promise<Csrf> {
    if (!this.csrfFlight || this.csrfFlight.generation !== generation) {
      const promise = this.send('/auth/csrf', {}, generation, false).then(({ value }) => {
        if (!record(value) || value.headerName !== 'X-CSRF-Token' || !isString(value.token)
            || !/^[\x21-\x7e]{1,512}$/.test(value.token)) throw invalidResponse();
        return { headerName: 'X-CSRF-Token' as const, token: value.token };
      });
      this.csrfFlight = { generation, promise };
      void promise.then(() => { if (this.csrfFlight?.promise === promise) this.csrfFlight = undefined; },
        () => { if (this.csrfFlight?.promise === promise) this.csrfFlight = undefined; });
    }
    const result = await abortable(this.csrfFlight.promise, signal);
    this.ensureCurrent(generation, signal);
    return result;
  }

  private async send(path: string, options: RequestOptions, generation: number, useCsrf = true): Promise<Reply> {
    this.ensureCurrent(generation, options.signal);
    const deadline = this.authCooldowns.get(path);
    const remaining = remainingCooldown(deadline, this.now());
    if (remaining > 0) throw new ApiError(429, 'AUTH_RATE_LIMITED', messages.AUTH_RATE_LIMITED!, false, remaining, deadline);
    const mutation = options.method === 'POST';
    const headers = new Headers({ Accept: 'application/json' });
    if (mutation && useCsrf) {
      const token = await this.csrf(generation, options.signal);
      headers.set(token.headerName, token.token);
    }
    if (options.body !== undefined) headers.set('Content-Type', 'application/json');
    if (options.key !== undefined) headers.set('Idempotency-Key', options.key);
    this.ensureCurrent(generation, options.signal);
    let response: Response;
    try {
      response = await this.fetcher(path, { method: options.method ?? 'GET', headers,
        ...(options.body !== undefined ? { body: options.body } : {}),
        ...(options.signal ? { signal: options.signal } : {}), credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
    } catch (error: unknown) {
      if (!mutation && (options.signal?.aborted || (error instanceof Error && error.name === 'AbortError'))) throw abortError();
      if (mutation) throw unknownCommand();
      throw new ApiError(0, 'NETWORK_UNAVAILABLE', 'Não foi possível conectar ao sistema. Confira sua conexão e tente novamente.');
    }
    const ensureResponseCurrent = () => {
      try { this.ensureCurrent(generation, options.signal); }
      catch { if (mutation) throw unknownCommand(); throw abortError(); }
    };
    ensureResponseCurrent();
    let value: unknown;
    if (response.status !== 204) {
      const contentType = response.headers.get('Content-Type') ?? '';
      if (contentType.includes('application/json') || contentType.includes('application/problem+json')) {
        try { value = await response.json() as unknown; }
        catch {
          if (!mutation && options.signal?.aborted) throw abortError();
          if (response.ok || response.status >= 500) throw invalidResponse(mutation);
          // Um401 conhecido continua exigindo novo acesso mesmo se o corpo vier inválido.
          value = undefined;
        }
      } else if (response.ok) throw invalidResponse(mutation);
    }
    ensureResponseCurrent();
    if (!response.ok) {
      if (response.status === 429) {
        const seconds = parseRetryAfterSeconds(response.headers.get('Retry-After'));
        const until = Math.max(this.authCooldowns.get(path) ?? 0, this.now() + seconds * 1_000);
        if (path === '/auth/login' || path === '/auth/refresh') this.authCooldowns.set(path, until);
        throw new ApiError(429, 'AUTH_RATE_LIMITED', messages.AUTH_RATE_LIMITED!, false, seconds, until);
      }
      const candidate = record(value) && isString(value.code) ? value.code : '';
      const code = Object.hasOwn(messages, candidate) ? candidate
        : response.status === 401 ? 'AUTHENTICATION_REQUIRED' : response.status === 404 ? 'API_NOT_AVAILABLE' : 'HTTP_ERROR';
      const message = messages[code] ?? (response.status === 404 ? 'Esta função ainda não está disponível neste ambiente.'
        : response.status === 429 ? 'Aguarde antes de tentar novamente.'
          : response.status === 403 ? 'Não foi possível validar esta operação. Atualize a página e tente novamente.'
            : 'Não foi possível concluir a operação. Tente novamente mais tarde.');
      throw new ApiError(response.status, code, message, mutation && response.status >= 500);
    }
    return { response, value };
  }

  private async request(path: string, options: RequestOptions = {}): Promise<Reply> {
    const generation = this.generation;
    await abortable(this.authTail, options.signal);
    this.ensureCurrent(generation, options.signal);
    try {
      try { return await this.send(path, options, generation); }
      catch (error: unknown) {
        if (!(error instanceof ApiError) || error.status !== 401 || error.code !== 'SESSION_REFRESH_REQUIRED') throw error;
        // O filtro negou antes do controller: pode reenviar UMA vez, com o mesmo corpo/chave.
        await abortable(this.refresh(), options.signal);
        this.ensureCurrent(generation, options.signal);
        return await this.send(path, options, generation);
      }
    } catch (error: unknown) {
      if (error instanceof ApiError && (error.status === 401 || error.code === 'ACCOUNT_INACTIVE')) this.expire(generation);
      throw error;
    }
  }

  async login(credentials: { email: string; password: string }, signal?: AbortSignal): Promise<Session> {
    const body = JSON.stringify({ email: credentials.email, password: credentials.password });
    return this.auth(async () => {
      const reply = await this.send('/auth/login', { method: 'POST', body, ...(signal ? { signal } : {}) }, this.generation);
      const result = session(reply.value);
      this.clearSession();
      return result;
    });
  }

  async refresh(signal?: AbortSignal): Promise<Session> {
    checkSignal(signal);
    const generation = this.generation;
    if (!this.refreshFlight || this.refreshFlight.generation !== generation) {
      const promise = this.auth(async () => {
        try {
          const reply = await this.send('/auth/refresh', { method: 'POST' }, generation);
          return session(reply.value);
        } catch (error: unknown) {
          const preserved = error instanceof ApiError && (
            error.status === 409 && error.code === 'SESSION_REFRESH_IN_PROGRESS'
            || error.status === 429
            || error.status === 503 && error.code === 'AUTH_RATE_LIMIT_UNAVAILABLE');
          if (!preserved)
            this.expire(generation);
          throw error;
        }
      });
      this.refreshFlight = { generation, promise };
      void promise.then(() => { if (this.refreshFlight?.promise === promise) this.refreshFlight = undefined; },
        () => { if (this.refreshFlight?.promise === promise) this.refreshFlight = undefined; });
    }
    return abortable(this.refreshFlight.promise, signal);
  }

  async logout(signal?: AbortSignal): Promise<void> {
    return this.auth(async () => {
      try { await this.send('/auth/logout', { method: 'POST', ...(signal ? { signal } : {}) }, this.generation); }
      finally { this.clearSession(); }
    });
  }

  async currentIdentity(signal?: AbortSignal): Promise<CurrentIdentity> {
    return identity((await this.request('/v1/me', signal ? { signal } : {})).value);
  }
  async units(tenantId: string, signal?: AbortSignal): Promise<Unit[]> {
    return list((await this.request(`/v1/tenants/${identifier(tenantId)}/units`, signal ? { signal } : {})).value, unit);
  }
  async amenities(tenantId: string, signal?: AbortSignal): Promise<Amenity[]> {
    return list((await this.request(`/v1/tenants/${identifier(tenantId)}/amenities`, signal ? { signal } : {})).value, amenity);
  }
  async reservations(tenantId: string, unitId: string, range: { from: string; until: string }, signal?: AbortSignal): Promise<ReservationPage> {
    const query = new URLSearchParams({ unitId: identifier(unitId), from: range.from, until: range.until });
    const { value } = await this.request(`/v1/tenants/${identifier(tenantId)}/reservations?${query}`, signal ? { signal } : {});
    if (!record(value) || typeof value.truncated !== 'boolean') throw invalidResponse();
    return { reservations: list(value.reservations, (item) => reservation(item)), truncated: value.truncated };
  }
  async createReservation(tenantId: string, body: CreateReservation, key: string, signal?: AbortSignal): Promise<MutationResult<Reservation>> {
    operationKey(key);
    const reply = await this.request(`/v1/tenants/${identifier(tenantId)}/reservations`,
      { method: 'POST', body: JSON.stringify(body), key, ...(signal ? { signal } : {}) });
    return { data: reservation(reply.value, true), replayed: reply.response.headers.get('Idempotency-Replayed') === 'true' };
  }
  async cancelReservation(tenantId: string, reservationId: string, body: CancelReservation, key: string, signal?: AbortSignal): Promise<MutationResult<Reservation>> {
    operationKey(key);
    const reply = await this.request(`/v1/tenants/${identifier(tenantId)}/reservations/${identifier(reservationId)}/cancel`,
      { method: 'POST', body: JSON.stringify(body), key, ...(signal ? { signal } : {}) });
    return { data: reservation(reply.value, true), replayed: reply.response.headers.get('Idempotency-Replayed') === 'true' };
  }
}

/** Uma chave por intenção. Quem oferece retry deve conservar esta chave e os mesmos dados. */
export function newOperationKey(): string { return `web:${globalThis.crypto.randomUUID()}`; }
export const api = new CondPulseApi();
