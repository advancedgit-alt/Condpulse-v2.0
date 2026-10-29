/**
 * Canal de atualização da unidade no celular (CP-036). Mesmas regras da web: o servidor só diz QUAIS assuntos mudaram; a
 * tela consulta o detalhe pelas rotas normais; ao reconectar, consulta tudo de novo; acesso revogado encerra sem nova
 * tentativa. O React Native não tem EventSource: o canal é lido por XMLHttpRequest em fluxo, com o token Bearer no
 * cabeçalho (nunca na URL) e buscado de novo a cada conexão.
 */
export const TOPICS = ['reservations', 'parcels', 'arrivals', 'notifications', 'announcements', 'requests'] as const;
export type Topic = typeof TOPICS[number];

export interface EventSourceLike {
  readonly readyState: number;
  addEventListener(type: string, listener: (event: { data: string }) => void): void;
  onerror: (() => void) | null;
  close(): void;
}
export type EventSourceFactory = (url: string) => EventSourceLike;
export interface Timers { set(callback: () => void, ms: number): unknown; clear(handle: unknown): void }
export interface UnitEventHandlers {
  changed(topics: Topic[]): void;
  resync(): void;
  ended(reason: 'revoked' | 'expired'): void;
}

export const CONNECTING = 0;
export const OPEN = 1;
export const CLOSED = 2;
export const RETRY_BASE_MS = 30_000;
export const RETRY_MAX_MS = 300_000;
export const EXPIRED_RETRY_MS = 5_000;
const DEFAULT_RECONNECT_MS = 5_000;

const systemTimers: Timers = { set: (callback, ms) => setTimeout(callback, ms), clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>) };

export function unitEventsPath(tenantId: string, unitId: string): string {
  return `/v1/tenants/${encodeURIComponent(tenantId)}/units/${encodeURIComponent(unitId)}/events`;
}

function parse(event: { data: string }): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(event.data);
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch { return null; }
}

export function connectUnitEvents(url: string, handlers: UnitEventHandlers, create: EventSourceFactory, timers: Timers = systemTimers): () => void {
  let source: EventSourceLike | null = null;
  let disposed = false;
  let failures = 0;
  let opened = false;
  let pending: unknown = null;
  const schedule = (ms: number) => {
    if (disposed) return;
    if (pending !== null) timers.clear(pending);
    pending = timers.set(() => { pending = null; open(); }, ms);
  };
  const stop = () => { source?.close(); source = null; };
  function open() {
    if (disposed) return;
    stop();
    const current = create(url);
    source = current;
    current.addEventListener('ready', () => {
      if (source !== current) return;
      if (opened) handlers.resync();
      opened = true; failures = 0;
    });
    current.addEventListener('changed', (event) => {
      if (source !== current) return;
      const topics = parse(event)?.topics;
      if (!Array.isArray(topics)) { handlers.resync(); return; }
      const known = topics.filter((topic): topic is Topic => (TOPICS as readonly unknown[]).includes(topic));
      if (known.length !== topics.length) handlers.resync();
      else if (known.length > 0) handlers.changed(known);
    });
    current.addEventListener('end', (event) => {
      if (source !== current) return;
      const reason = parse(event)?.reason;
      if (reason === 'revoked') { stop(); disposed = true; handlers.ended('revoked'); return; }
      if (reason === 'expired') { stop(); handlers.ended('expired'); schedule(EXPIRED_RETRY_MS); }
    });
    current.onerror = () => {
      if (source !== current || current.readyState !== CLOSED) return;
      stop();
      schedule(Math.min(RETRY_MAX_MS, RETRY_BASE_MS * 2 ** failures));
      failures += 1;
    };
  }
  open();
  return () => { disposed = true; if (pending !== null) timers.clear(pending); pending = null; stop(); };
}

/** Subconjunto do XMLHttpRequest usado aqui (o do React Native entrega o texto aos poucos em onprogress). */
export interface XhrLike {
  readonly status: number;
  readonly responseText: string;
  getResponseHeader(name: string): string | null;
  open(method: string, url: string): void;
  setRequestHeader(name: string, value: string): void;
  send(): void;
  abort(): void;
  onprogress: ((event: never) => unknown) | null;
  onload: ((event: never) => unknown) | null;
  onerror: ((event: never) => unknown) | null;
}

/**
 * Leitor de SSE sobre XMLHttpRequest com o comportamento do EventSource do navegador: fim normal do fluxo reconecta
 * sozinho depois do intervalo informado (readyState CONNECTING); recusa (status diferente de 200 ou tipo errado) fica
 * CLOSED e quem chamou decide a espera. Sem token (sessão encerrada), fica CLOSED sem chamar a rede.
 */
export function xhrEventSource(url: string, accessToken: () => Promise<string | null>, newXhr: () => XhrLike,
  timers: Timers = systemTimers): EventSourceLike {
  const listeners = new Map<string, ((event: { data: string }) => void)[]>();
  let readyState = CONNECTING;
  let xhr: XhrLike | null = null;
  let retry = DEFAULT_RECONNECT_MS;
  let pending: unknown = null;
  const self: EventSourceLike = {
    get readyState() { return readyState; },
    addEventListener(type, listener) { listeners.set(type, [...(listeners.get(type) ?? []), listener]); },
    onerror: null,
    close() {
      readyState = CLOSED;
      if (pending !== null) { timers.clear(pending); pending = null; }
      const current = xhr; xhr = null; current?.abort();
    },
  };
  const fail = (closed: boolean) => {
    if (readyState === CLOSED) return;
    xhr = null;
    readyState = closed ? CLOSED : CONNECTING;
    self.onerror?.();
    if (!closed && readyState === CONNECTING) pending = timers.set(() => { pending = null; void connect(); }, retry);
  };
  async function connect() {
    if (readyState === CLOSED) return;
    let token: string | null;
    try { token = await accessToken(); } catch { token = null; }
    if (readyState === CLOSED) return;
    if (!token) { fail(true); return; }
    const request = newXhr();
    xhr = request;
    let offset = 0;
    let buffer = '';
    let event = 'message';
    let data: string[] = [];
    let accepted: boolean | null = null;
    const check = () => {
      if (accepted !== null) return accepted;
      if (request.status === 0) return false;
      accepted = request.status === 200 && (request.getResponseHeader('Content-Type') ?? '').toLowerCase().startsWith('text/event-stream');
      if (accepted) readyState = OPEN;
      return accepted;
    };
    const consume = () => {
      if (xhr !== request || !check()) return;
      buffer += request.responseText.slice(offset);
      offset = request.responseText.length;
      let newline: number;
      while ((newline = buffer.search(/\r?\n/)) >= 0) {
        const line = buffer.slice(0, newline);
        buffer = buffer.slice(newline + (buffer[newline] === '\r' ? 2 : 1));
        if (line === '') {
          if (data.length > 0) { const payload = { data: data.join('\n') }; for (const listener of listeners.get(event) ?? []) listener(payload); }
          event = 'message'; data = [];
          if (xhr !== request) return; // um ouvinte fechou o canal
          continue;
        }
        if (line.startsWith(':')) continue;
        const colon = line.indexOf(':');
        const field = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
        if (field === 'event') event = value || 'message';
        else if (field === 'data') data.push(value);
        else if (field === 'retry' && /^\d+$/.test(value)) retry = Math.max(1_000, Number(value));
      }
    };
    request.onprogress = consume;
    request.onload = () => {
      if (xhr !== request) return;
      if (!check()) { fail(true); return; }
      consume();
      if (xhr === request) fail(false); // fim normal: reconectar como o navegador
    };
    request.onerror = () => { if (xhr === request) fail(accepted !== true); };
    request.open('GET', url);
    request.setRequestHeader('Accept', 'text/event-stream');
    request.setRequestHeader('Authorization', `Bearer ${token}`);
    request.setRequestHeader('Cache-Control', 'no-store');
    request.send();
  }
  void connect();
  return self;
}
