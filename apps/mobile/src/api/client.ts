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
  constructor(readonly status: number, readonly code: string, message: string) { super(message); this.name = 'ApiError'; }
}
export interface TokenSource {
  accessToken(): Promise<string | null>;
  refresh(): Promise<string | null>;
  expire(): Promise<void>;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ROLES = ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER', 'CONCIERGE', 'MANAGER', 'SUPPORT'] as const;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const invalid = () => new ApiError(502, 'INVALID_API_RESPONSE', 'O sistema retornou uma resposta que não pôde ser validada. Tente novamente mais tarde.');

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
  ACCOUNT_INACTIVE: 'Seu acesso precisa ser validado pela gestão.',
  TENANT_ACCESS_DENIED: 'Condomínio não disponível para este acesso.',
};

export type Fetcher = (url: string, init: RequestInit) => Promise<Response>;

export function createApi(options: { baseUrl: string; tokens: TokenSource; fetch?: Fetcher }) {
  const base = new URL(options.baseUrl);
  if (base.protocol !== 'https:' && !(base.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(base.hostname)))
    throw new Error('API_URL_INVALID');
  const fetcher: Fetcher = options.fetch ?? ((url, init) => globalThis.fetch(url, init));
  async function send(path: string, token: string, signal?: AbortSignal): Promise<Response> {
    try {
      return await fetcher(new URL(path, base).toString(), { method: 'GET', headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
        cache: 'no-store', redirect: 'error', ...(signal ? { signal } : {}) });
    } catch {
      throw new ApiError(0, 'NETWORK_UNAVAILABLE', 'Não foi possível conectar ao sistema. Confira sua conexão e tente novamente.');
    }
  }
  async function get<T>(path: string, parse: (value: unknown) => T, signal?: AbortSignal): Promise<T> {
    const token = await options.tokens.accessToken();
    if (!token) throw new ApiError(401, 'AUTHENTICATION_REQUIRED', messages.AUTHENTICATION_REQUIRED!);
    let response = await send(path, token, signal);
    if (response.status === 401) {
      const renewed = await options.tokens.refresh();
      if (!renewed) throw new ApiError(401, 'AUTHENTICATION_REQUIRED', messages.AUTHENTICATION_REQUIRED!);
      response = await send(path, renewed, signal);
      if (response.status === 401) {
        await options.tokens.expire();
        throw new ApiError(401, 'AUTHENTICATION_REQUIRED', messages.AUTHENTICATION_REQUIRED!);
      }
    }
    let value: unknown = undefined;
    if ((response.headers.get('Content-Type') ?? '').match(/application\/(problem\+)?json/)) {
      try { value = await response.json(); } catch { if (response.ok) throw invalid(); }
    } else if (response.ok) throw invalid();
    if (!response.ok) {
      const code = record(value) && typeof value.code === 'string' && Object.hasOwn(messages, value.code) ? value.code : 'HTTP_ERROR';
      throw new ApiError(response.status, code, messages[code] ?? 'Não foi possível concluir agora. Tente novamente mais tarde.');
    }
    return parse(value);
  }
  return { me: (signal?: AbortSignal) => get('/v1/me', identity, signal) };
}
export type CondPulseApi = ReturnType<typeof createApi>;
